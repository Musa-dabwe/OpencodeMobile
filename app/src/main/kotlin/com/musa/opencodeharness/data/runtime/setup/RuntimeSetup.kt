package com.musa.opencodeharness.data.runtime.setup

import com.musa.opencodeharness.data.OpenCodeApi
import com.musa.opencodeharness.data.ServerEndpoint
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.runtime.Artifact
import com.musa.opencodeharness.data.runtime.HttpArtifactFetcher
import com.musa.opencodeharness.data.runtime.HttpNpmResolver
import com.musa.opencodeharness.data.runtime.RuntimeDownloader
import com.musa.opencodeharness.data.runtime.RuntimeManifest
import com.musa.opencodeharness.data.runtime.install.InstallPlanner
import com.musa.opencodeharness.data.runtime.install.RuntimeInstaller
import com.musa.opencodeharness.data.runtime.launch.ProotHandle
import com.musa.opencodeharness.data.runtime.launch.StartedServer
import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.data.runtime.resolveArtifact
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Progress of the first-run flow, in the order it actually happens. */
sealed interface SetupStep {
    data object Idle : SetupStep

    /** Asking npm which OpenCode build is current. */
    data class Resolving(val artifactId: String) : SetupStep

    data class Downloading(val artifactId: String, val index: Int, val total: Int) : SetupStep

    data class Installing(val artifactId: String, val index: Int, val total: Int) : SetupStep

    data object StartingServer : SetupStep

    /** [attempt] is 1-based; the process exists but is not answering `/api/health` yet. */
    data class AwaitingHealth(val attempt: Int) : SetupStep

    data object Ready : SetupStep

    /** [reason] is a complete, user-presentable sentence. Never contains the password. */
    data class Failed(val reason: String) : SetupStep
}

/**
 * Download and install, as the orchestrator sees it. Every method that touches the network or
 * the filesystem sits behind this, so the resume decision table can be driven by a fake.
 */
interface RuntimeStage {
    /** True when a hash-verified container is already on disk. Never hits the network. */
    fun isDownloaded(artifact: Artifact): Boolean

    /** True when [artifactId]'s completion marker AND its installed files are both present. */
    fun isInstalled(artifactId: String): Boolean

    suspend fun download(artifact: Artifact)

    suspend fun install(artifact: Artifact)
}

/** Setup could not reach a usable server. [message] is safe to render. */
class SetupFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What to do about one artifact, given what is already on disk. The whole of the resume story
 * is this pure function.
 */
data class ArtifactPlan(
    val artifactId: String,
    /** Fetch the container. False when already downloaded, OR when already installed. */
    val download: Boolean,
    /** Extract/install it. False when the install marker and its files are already present. */
    val install: Boolean,
) {
    val isNoOp: Boolean get() = !download && !install
}

/**
 * Resume decision for one artifact.
 *
 * The load-bearing rule is **installed implies skip the download**. A container is only ever an
 * input to installing; once the runtime is on disk the ~89 MB npm tarball is not needed to start
 * a server, and re-fetching it on every launch is the difference between a one-time cost and a
 * permanent one. Deleting a `.ok` marker is therefore the documented way to force a rebuild;
 * deleting a `.trusted` marker is the documented way to force a re-download.
 *
 * Neither probe may hit the network — [RuntimeStage.isDownloaded] is the offline overload
 * (`RuntimeDownloader.isComplete(Artifact)`), which for an npm artifact asks only "is there a
 * container and a marker" and never resolves. That is why an already-installed runtime costs
 * zero network on a warm launch.
 */
fun planArtifact(artifactId: String, downloaded: Boolean, installed: Boolean): ArtifactPlan =
    ArtifactPlan(
        artifactId = artifactId,
        download = !downloaded && !installed,
        install = !installed,
    )

/**
 * Drives first run end to end: resolve -> download -> install -> start -> health -> ready.
 *
 * ## Why this lives here rather than behind a Setup screen
 *
 * The plan offers a Setup screen, or "drive setup from the existing screen and show progress",
 * and explicitly permits the smaller option if justified. This IS the smaller option; the full
 * argument is in `.superpowers/sdd/phase2-runtime/task-5-report.md`, but in short: the flow is a
 * straight line with no choice for the user to make, navigation is an explicit non-goal for this
 * phase (so a second screen would first require building the host that swaps between them), and
 * one screen means one state machine — "setup finished" and "sessions are loaded" cannot drift
 * into the setup-completed-but-list-stale class of bug.
 *
 * ## Resumability
 *
 * Every step is guarded by an on-disk fact ([RuntimeStage.isDownloaded] / [isInstalled]), so a
 * process death at any point costs only the step that was in flight. There is deliberately no
 * setup lock and no sentinel file: the markers written by Tasks 2 and 3 ARE the state, and
 * [planArtifact] reads them. Adding a third marker could only disagree with the two that are
 * already load-bearing.
 *
 * ## Why health is a probe and not a state read
 *
 * `ServerState.Running` means `ProcessBuilder.start()` returned, NOT that the HTTP server is
 * accepting connections — a Bun binary can abort ~160 ms later. So this polls `GET /api/health`
 * (never `/api/info`, which does not exist and returns the SPA's HTML, so a naive probe
 * "succeeds" with text/html) until it answers, and fails FAST if the process dies first.
 */
class RuntimeSetup(
    private val manifest: RuntimeManifest,
    private val stage: RuntimeStage,
    private val startServer: () -> StartedServer,
    private val buildApi: (ServerEndpoint) -> OpenCodeApi,
    /** Scope for the log-tail and death-watch jobs only; `run()` itself is caller-scoped. */
    private val scope: CoroutineScope,
    private val healthAttempts: Int = DEFAULT_HEALTH_ATTEMPTS,
    private val healthPollMillis: Long = DEFAULT_HEALTH_POLL_MILLIS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : SessionRuntime {
    private val mutex = Mutex()

    private val _step = MutableStateFlow<SetupStep>(SetupStep.Idle)
    override val step: StateFlow<SetupStep> = _step.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    override val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Idle)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverDeath = MutableStateFlow<String?>(null)

    /**
     * A complete, user-presentable sentence once the server process is observed dead, else null.
     * This is what stops a dead server being swallowed: a `Running` state must never read as
     * "working", so anything that can go wrong downstream has this to check.
     */
    override val serverDeath: StateFlow<String?> = _serverDeath.asStateFlow()

    /** Progress lines authored here, kept so they stay ahead of the server's own output. */
    private var setupLines: List<String> = emptyList()

    /** Set the moment a server exists; reused by a retry so a retry never spawns a second one. */
    private var server: StartedServer? = null

    /** The live password, held ONLY so [note] can scrub it. Never logged, never rendered. */
    @Volatile
    private var secret: String? = null

    private var tailJob: Job? = null
    private var watchJob: Job? = null

    /**
     * Runs (or resumes) the flow and returns the API client bound to the server this app started.
     *
     * The returned client owns an HTTP engine and MUST be closed by the caller — the ViewModel
     * does it in `onCleared`. On every failure path this closes the client it built, so a failed
     * attempt leaks nothing either.
     */
    override suspend fun run(): OpenCodeApi = mutex.withLock {
        _serverDeath.value = null
        var client: OpenCodeApi? = null
        try {
            val total = manifest.artifacts.size
            manifest.artifacts.forEachIndexed { index, artifact ->
                val plan = planArtifact(
                    artifact.id,
                    downloaded = stage.isDownloaded(artifact),
                    installed = stage.isInstalled(artifact.id),
                )
                if (plan.isNoOp) {
                    note("${artifact.id}: already installed, skipping")
                } else {
                    if (plan.download) {
                        _step.value = SetupStep.Downloading(artifact.id, index + 1, total)
                        note("${artifact.id}: downloading")
                        stage.download(artifact)
                    }
                    if (plan.install) {
                        _step.value = SetupStep.Installing(artifact.id, index + 1, total)
                        note("${artifact.id}: installing")
                        stage.install(artifact)
                    }
                }
            }

            val running = serverOrStart()
            _step.value = SetupStep.StartingServer
            note("server process started on ${running.baseUrl.substringAfter("://")}")

            val endpoint = ServerEndpoint(baseUrl = running.baseUrl, password = running.password)
            secret = endpoint.password

            val api = buildApi(endpoint)
            client = api

            awaitHealthy(api, running)
            note("server answered GET /api/health")

            _step.value = SetupStep.Ready
            client = null // ownership transfers to the caller
            api
        } catch (cancellation: CancellationException) {
            client?.let(::closeQuietly)
            throw cancellation
        } catch (failure: Throwable) {
            client?.let(::closeQuietly)
            val reason = scrub(describe(failure))
            _step.value = SetupStep.Failed(reason)
            // ALWAYS re-throw the scrubbed wrapper, never the original. The original's message
            // is what the caller (and the ViewModel, which renders `cause.message`) would show,
            // so re-throwing a pre-scrubbed SetupFailed would leak through the very path this
            // scrub exists to protect. The original is kept as the cause, which is never
            // rendered -- only its top-level message is ever read.
            throw SetupFailed(reason, failure)
        }
    }

    /**
     * Reuses a server that is still Running; otherwise starts one.
     *
     * The reuse matters for the retry button: after a failed health probe the process is often
     * still up, and respawning would hit `ProotLauncher.start()`'s "already running" guard and
     * turn a recoverable network hiccup into a hard failure. A server observed Dead is NOT
     * reused — `ServerEvent.StartRequested` legitimately reopens that state for a fresh run.
     */
    private fun serverOrStart(): StartedServer {
        server?.let { if (it.state.value is ServerState.Running) return it }
        val started = startServer()
        server = started
        secret = started.password
        tail(started)
        watch(started)
        return started
    }

    /** Mirrors the launcher's death state into the UI stream and turns it into a sentence. */
    private fun watch(started: StartedServer) {
        watchJob?.cancel()
        watchJob = scope.launch {
            started.state.collect { state ->
                _serverState.value = state
                if (state is ServerState.Dead) {
                    val reason = describeDeath(state)
                    _serverDeath.value = reason
                    note("SERVER DIED: $reason")
                }
            }
        }
    }

    /**
     * Keeps the UI's log area live: our own progress lines, then the server's redacted tail.
     *
     * The server's lines come from [LogBuffer], which redacts the registered secret BEFORE
     * storing, so redaction survives into the UI without this file knowing the secret's format.
     * The prefix is captured once at start, so later progress lines cannot reorder history.
     */
    private fun tail(started: StartedServer) {
        tailJob?.cancel()
        val prefix = setupLines
        tailJob = scope.launch {
            started.log.revision.collect {
                _logLines.value = prefix + started.log.snapshot()
            }
        }
    }

    private suspend fun awaitHealthy(api: OpenCodeApi, started: StartedServer) {
        var lastReason = "no response"
        for (attempt in 1..healthAttempts) {
            // Death first: a dead server will never answer, and saying so immediately beats
            // burning the whole poll budget to arrive at the same answer more slowly.
            val state = started.state.value
            if (state is ServerState.Dead) {
                throw SetupFailed("The server exited before it became healthy: ${describeDeath(state)}")
            }
            _step.value = SetupStep.AwaitingHealth(attempt)
            try {
                val health: Health = api.health()
                if (health.healthy) return
                lastReason = "server reported healthy=false"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                lastReason = describe(failure)
            }
            if (attempt < healthAttempts) sleep(healthPollMillis)
        }
        throw SetupFailed(
            "The server started but did not answer GET /api/health after $healthAttempts attempts: $lastReason"
        )
    }

    /**
     * Removes the live password from any text bound for the UI.
     *
     * Applied on BOTH paths that put text in front of a user: the progress lines ([note]) and
     * the failure reason rendered as [SetupStep.Failed]. The second one matters because a
     * failure reason is built from an arbitrary exception's message, and "we never interpolate
     * the password ourselves" is a weaker guarantee than "no password reaches the screen at
     * all". It costs one `String.replace` on a path that runs once per failure.
     */
    private fun scrub(text: String): String {
        val live = secret
        // The length floor is not decoration. A real secret is 32 hex chars
        // (ServerSecret.ENTROPY_BYTES x 2); scrubbing a 1-character value would replace every
        // "p" in ordinary prose and mangle the very message it is meant to protect. Skipping a
        // sub-threshold value is the safe direction for the only input that can trigger it --
        // a test double, never ServerSecret.generate().
        return if (live == null || live.length < MIN_SCRUBBABLE_SECRET) text
        else text.replace(live, REDACTED)
    }

    /** Appends one progress line, scrubbed even though the password is never interpolated. */
    private fun note(message: String) {
        val safe = scrub(message)
        setupLines = (setupLines + safe).takeLast(MAX_SETUP_LOG_LINES)
        _logLines.value = setupLines
    }

    private fun describeDeath(state: ServerState.Dead): String {
        state.launchError?.let { return "launch failed: $it" }
        val code = state.exitCode
        return when {
            state.signal != null -> "exit code $code (killed by signal ${state.signal})"
            code != null -> "exit code $code"
            else -> "unknown cause (no exit code was reported)"
        }
    }

    private fun describe(failure: Throwable): String = when (failure) {
        is SetupFailed -> failure.message ?: "setup failed"
        else -> failure.message?.takeIf { it.isNotBlank() }
            ?: failure::class.simpleName
            ?: "setup failed"
    }

    private fun closeQuietly(api: OpenCodeApi) {
        try {
            (api as? AutoCloseable)?.close()
        } catch (ignored: Exception) {
            // Nothing useful to do while already unwinding.
        }
    }

    companion object {
        const val DEFAULT_HEALTH_ATTEMPTS: Int = 40
        const val DEFAULT_HEALTH_POLL_MILLIS: Long = 500
        const val MAX_SETUP_LOG_LINES: Int = 400
        const val REDACTED: String = "***redacted***"

        /** 16 = half of a real secret's 32 hex chars; see [scrub]. */
        const val MIN_SCRUBBABLE_SECRET: Int = 16
    }
}

/**
 * What the session ViewModel needs from the runtime, and nothing more.
 *
 * This is the seam that makes deliverable 1 testable: the ViewModel must obtain its
 * [ServerEndpoint] from the launcher's server rather than from any constant, and that is only
 * provable if the ViewModel depends on an abstraction a test can substitute. A test asserts the
 * endpoint the ViewModel actually used came out of [run]'s server.
 */
interface SessionRuntime {
    val step: StateFlow<SetupStep>
    val logLines: StateFlow<List<String>>
    val serverDeath: StateFlow<String?>

    /**
     * The launcher's own [ServerState], so the Settings card can be a pure function of it.
     *
     * Until Task 4 this field existed on [RuntimeSetup] with NO reader anywhere -- write-only in
     * exactly the way `SessionListUiState.serverUrl` was, which the Task 3 review flagged. It is on
     * the seam now because `ServerState.Dead` is a fact the card has to render, and
     * `serverDeath` is not a substitute: that is one sentence, written once, never cleared until
     * the next `run()`, so it can go stale beside a freshly spawned server.
     *
     * Exposing an existing field is not a behaviour change: nothing about how the launcher, the
     * death watch or the log tail works is touched.
     */
    val serverState: StateFlow<ServerState>

    /** Runs or resumes setup and returns the client bound to the server this app started. */
    suspend fun run(): OpenCodeApi
}

/**
 * Production [RuntimeStage]: Task 2's downloader plus Task 3's installer, wired to the same
 * directories the launcher reads. Deliberately thin — every resumability decision lives in
 * [planArtifact], not here.
 */
class DownloadInstallStage(
    private val downloader: RuntimeDownloader,
    private val installer: RuntimeInstaller,
) : RuntimeStage {

    // The OFFLINE overload on purpose: it never resolves, so a warm launch of an already
    // installed runtime costs zero network calls even for the npm artifact.
    override fun isDownloaded(artifact: Artifact): Boolean = downloader.isComplete(artifact)

    override fun isInstalled(artifactId: String): Boolean = installer.isComplete(artifactId)

    override suspend fun download(artifact: Artifact) {
        downloader.ensure(artifact)
    }

    override suspend fun install(artifact: Artifact) {
        installer.install(artifact)
    }

    companion object {
        /**
         * Wires the production stage for [runtimeDir] (`filesDir/runtime`), keeping the path
         * layout in exactly one place. Containers live beside it in `filesDir/artifacts`.
         *
         * [sharedClient] is used for everything so one engine serves the whole first run
         * instead of four, and it is the caller's to close (the ViewModel owns it).
         */
        fun forRuntimeDir(runtimeDir: File, sharedClient: HttpClient): DownloadInstallStage {
            val parent = runtimeDir.parentFile ?: runtimeDir
            val artifactsDir = File(parent, "artifacts")
            val resolver = HttpNpmResolver(sharedClient)
            return DownloadInstallStage(
                downloader = RuntimeDownloader(
                    artifactsDir = artifactsDir,
                    fetcher = HttpArtifactFetcher(sharedClient),
                    resolver = resolver,
                ),
                installer = RuntimeInstaller(
                    runtimeDir = runtimeDir,
                    artifactsDir = artifactsDir,
                    planner = InstallPlanner(runtimeDir),
                    resolve = { artifact -> resolveArtifact(artifact, resolver) },
                ),
            )
        }

        /** Test/host convenience: builds its own client, which the caller must close. */
        fun forRuntimeDir(runtimeDir: File): DownloadInstallStage =
            forRuntimeDir(runtimeDir, HttpClient(OkHttp))
    }
}

/**
 * Builds the real graph: shared Ktor client -> downloader + installer -> launcher -> setup.
 *
 * Exists so the only production wiring lives in one place, and so the ViewModel takes a
 * [SessionRuntime] rather than reaching for a downloader, a launcher and a client itself.
 *
 * [scope] is the ViewModel's own scope, so the log-tail and death-watch jobs die with it — a
 * download must not outlive the screen that is showing its progress, and neither must a
 * collector of a dead ViewModel's flows.
 *
 * The returned [RuntimeResources] owns everything with a resource: one OkHttp engine, one
 * proot process. The ViewModel closes it exactly once, in `onCleared`.
 */
object ProductionRuntime {

    data class RuntimeResources(
        val runtime: SessionRuntime,
        /** Closes the HTTP engine and stops the server. Idempotent by contract of its owner. */
        val closeable: AutoCloseable,
    )

    fun create(runtimeDir: File, scope: CoroutineScope, nativeLibraryDir: File): RuntimeResources {
        val http = HttpClient(OkHttp)
        val launcher = com.musa.opencodeharness.data.runtime.launch.ProotLauncher(
            runtimeDir = runtimeDir,
            // proot and its loader are EXECUTED, so they come from nativeLibraryDir (populated
            // by extractNativeLibs), not from filesDir, where execve() is denied outright.
            execDir = nativeLibraryDir,
            prootFileName = com.musa.opencodeharness.data.runtime.launch.ProotLauncher.APK_PROOT_FILE_NAME,
            loaderFileName = com.musa.opencodeharness.data.runtime.launch.ProotLauncher.APK_PROOT_LOADER_FILE_NAME,
        )
        val stage = DownloadInstallStage.forRuntimeDir(runtimeDir, http)
        val setup = RuntimeSetup(
            manifest = com.musa.opencodeharness.data.runtime.embeddedRuntimeManifest,
            stage = stage,
            startServer = { launcher.start() },
            buildApi = { endpoint ->
                com.musa.opencodeharness.data.OpenCodeClient(endpoint.baseUrl, endpoint.password)
            },
            scope = scope,
        )
        return RuntimeResources(
            runtime = setup,
            closeable = AutoCloseable {
                // Order matters: stop the server BEFORE closing the engine it answers on, so
                // a shutdown-time health probe cannot outlive its transport.
                try {
                    launcher.stop()
                } catch (ignored: Exception) {
                    // Best effort: stop() already swallows, and a failure here must not
                    // prevent the engine from being released.
                }
                try {
                    http.close()
                } catch (ignored: Exception) {
                    // Nothing useful to do while unwinding.
                }
            },
        )
    }
}
