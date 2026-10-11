package com.musa.opencodeharness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musa.opencodeharness.data.ApiException
import com.musa.opencodeharness.data.OpenCodeApi
import com.musa.opencodeharness.data.OpenCodeClient
import com.musa.opencodeharness.data.SessionNode
import com.musa.opencodeharness.data.buildSessionTree
import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.data.runtime.setup.ProductionRuntime
import com.musa.opencodeharness.data.runtime.setup.SessionRuntime
import com.musa.opencodeharness.data.runtime.setup.SetupFailed
import com.musa.opencodeharness.data.runtime.setup.SetupStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * The connection half of the screen. Exactly three cases, matching the three things the client
 * can tell us: we have not heard back yet, the server answered `/api/health`, or it did not.
 *
 * [Unreachable] covers "we could not get a usable answer", which is deliberately one bucket for
 * several distinct failures -- see [describe] for how each is phrased. The distinction that
 * matters to the user is "the server is not there" versus "the server is there but rejected us",
 * and both are named explicitly rather than collapsed into a generic "error".
 */
sealed interface ConnectionState {
    /** A load is in flight. The previous session list, if any, is not shown while connecting. */
    data object Connecting : ConnectionState

    /** `GET /api/health` returned `{"healthy":true}`. */
    data object Healthy : ConnectionState

    /** No usable answer. [reason] is a complete, user-presentable sentence. */
    data class Unreachable(val reason: String) : ConnectionState
}

/**
 * Everything the session screen renders: the first-run flow's progress, the live log, any
 * observed server death, and the connection state plus session tree.
 *
 * [roots] is the output of [buildSessionTree], not the flat server list, so the screen can
 * indent sub-agent sessions under their parent. It is non-empty only while [connection] is
 * [ConnectionState.Healthy] -- a failed load clears it rather than leaving a stale list on
 * screen next to an error.
 *
 * The setup fields are additive with defaults, so every existing construction site and test
 * keeps working unchanged; that is deliberate rather than incidental, because a refactor of a
 * tested state machine should not force its tests to change shape.
 */
data class SessionListUiState(
    val connection: ConnectionState = ConnectionState.Connecting,
    val roots: List<SessionNode> = emptyList(),
    /** Where the first-run flow is. [SetupStep.Ready] means the server answered `/api/health`. */
    val setup: SetupStep = SetupStep.Idle,
    /** Redacted, bounded tail: our progress lines plus the server's stdout/stderr. */
    val logLines: List<String> = emptyList(),
    /** Complete sentence once the server process is observed dead, else null. Never swallowed. */
    val serverDeath: String? = null,
    /** `127.0.0.1:<port>` of the server this app started. Null until one exists. */
    val serverUrl: String? = null,
    /**
     * The launcher's own view of the server process, mirrored from the runtime.
     *
     * This is the field the Settings card is a pure function of (see
     * `serverCardModel`). [serverDeath] cannot replace it: that is one sentence, written once and
     * only cleared by the next `run()`, so it can outlive the run that produced it -- while this is
     * re-posted by the launcher on every transition.
     *
     * Defaults to [ServerState.Idle] so every existing construction site and test keeps working,
     * which is the same additive-because-tested rule [logLines] follows.
     */
    val serverState: ServerState = ServerState.Idle,
    /**
     * Whatever `GET /api/info` reported, or null.
     *
     * NEVER a constant: the binary is whatever npm resolved at install time, so a hardcoded version
     * would be a claim this build cannot back. Null means "nobody has told us", and the card renders
     * no version segment rather than a placeholder.
     */
    val serverVersion: String? = null,
) {
    /**
     * True once the server answered and the list came back empty. Distinct from "still
     * connecting" and from "failed", so the screen can say "no sessions yet" rather than either
     * spinning forever or showing an error for a perfectly healthy empty server.
     */
    val isEmptyResult: Boolean
        get() = connection is ConnectionState.Healthy && roots.isEmpty()

    /** True while the first-run flow still has work to do, so the screen can show progress. */
    val isSettingUp: Boolean
        get() = setup !is SetupStep.Ready && setup !is SetupStep.Failed
}

/**
 * Drives the one screen in this slice: run setup, start the server, read the session list,
 * group it into a tree.
 *
 * ## Two construction paths, deliberately
 *
 *  - [runtime] is the production path: the [SessionRuntime] owns download/install/start/health,
 *    and its `run()` returns a client bound to the server **the launcher just started**. There
 *    is no endpoint constant in this file any more; the base URL and password come from the
 *    live `ProotHandle`.
 *  - [api] is the test path: a fake, injected directly, so the state machine below is testable
 *    with no network and no process.
 *
 * Exactly one of them must be non-null. The [closeable] releases real resources (the OkHttp
 * engine and the server) exactly once, in [onCleared], and is null in tests because a fake owns
 * nothing.
 *
 * Lifecycle note: `ViewModel.onCleared` runs when the Activity finishes for good, not on
 * rotation, so the server and its engine survive a rotation and are released exactly once.
 */
class SessionListViewModel(
    /** Production: the runtime whose `run()` returns a client bound to the started server. */
    private val runtime: SessionRuntime? = null,
    /** Test: a pre-built seam. Mutually exclusive with [runtime]. */
    private val api: OpenCodeApi? = null,
    private val closeable: AutoCloseable? = null,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    init {
        require((runtime == null) != (api == null)) {
            "SessionListViewModel takes exactly one of runtime (production) or api (tests)"
        }
    }

    private val _state = MutableStateFlow(SessionListUiState())
    val state: StateFlow<SessionListUiState> = _state.asStateFlow()

    /** The client in use. Set on first successful setup/load; closed once, in [onCleared]. */
    private var activeApi: OpenCodeApi? = null
    private var activeCloseable: AutoCloseable? = null

    init {
        // Mirror the runtime's progress into the UI state. Collected in the same scope as the
        // load, so a cleared ViewModel stops collecting rather than updating dead state.
        runtime?.let { rt ->
            (scope ?: viewModelScope).launch {
                launch { rt.step.collect { s -> _state.update { it.copy(setup = s) } } }
                launch { rt.logLines.collect { lines -> _state.update { it.copy(logLines = lines) } } }
                // The Settings card reads this directly, so the launcher's state is mirrored rather
                // than inferred from the connection: a process can be alive while its HTTP server is
                // not answering, and those are two different things the card words differently.
                launch { rt.serverState.collect { s -> _state.update { it.copy(serverState = s) } } }
                launch {
                    rt.serverDeath.collect { reason ->
                        _state.update { it.copy(serverDeath = reason) }
                        // A dead server must change what the user sees. Clearing the session
                        // list stops "Connected" from sitting above a server that is gone.
                        if (reason != null) {
                            _state.update {
                                it.copy(
                                    connection = ConnectionState.Unreachable(
                                        "The OpenCode server stopped: $reason"
                                    ),
                                    roots = emptyList(),
                                )
                            }
                        }
                    }
                }
            }
        }
        load()
    }

    /** Re-runs setup + health + session load. Wired to the retry action on the unreachable state. */
    fun load() {
        // The version is cleared BEFORE the probe: it belongs to the server that answered last time,
        // and carrying it across a reload would put a stale version beside a server that is still
        // starting. It comes straight back if the probe succeeds.
        _state.update { it.copy(connection = ConnectionState.Connecting, serverVersion = null) }
        (scope ?: viewModelScope).launch {
            val client = resolveApi() ?: return@launch

            val health = try {
                client.health()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation: the coroutine is being torn down (onCleared, or the
                // ViewModel scope is cancelled) and must unwind normally. Catching Throwable
                // below without this branch would convert a cancel into a rendered error.
                throw cancellation
            } catch (cause: Throwable) {
                failed(describe(cause))
                return@launch
            }

            if (!health.healthy) {
                // The transport worked and the server answered, but with healthy=false. That is
                // not unreachable, so it gets its own message rather than the generic one.
                failed("Server answered, but reported healthy=false.")
                return@launch
            }

            // Read the version off the same live server the session list is about to come from.
            // A failure here is NOT fatal to the load: the list is still worth showing, and the card
            // simply renders no version segment. Only cancellation propagates.
            val version = try {
                client.serverVersion()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            if (version != null) _state.update { it.copy(serverVersion = version) }

            val sessions = try {
                client.listSessions()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Throwable) {
                // Health is fine but the list did not come back, so saying "server unreachable"
                // would be wrong. Say what actually happened.
                failed("Server is healthy, but the session list could not be read: ${describe(cause)}")
                return@launch
            }

            _state.update {
                it.copy(
                    connection = ConnectionState.Healthy,
                    // Client-side grouping: the server has no children-listing route, so the tree
                    // is derived from the flat list on `parentID`.
                    roots = buildSessionTree(sessions),
                )
            }
        }
    }

    /**
     * Restarts the server: drops the client this ViewModel is holding and runs setup again.
     *
     * ## Why this is not just [load]
     *
     * [load] re-probes the server it already has, which is right for "the network blipped" and wrong
     * for "the server is gone": [resolveApi] caches its client, so a plain [load] after a death would
     * health-probe a port nothing is listening on and report Unreachable forever, with no new process
     * ever spawned. That is exactly the shape of a `Restart` button that does not restart.
     *
     * Releasing the cached client is what makes the next [resolveApi] fall through to
     * `SessionRuntime.run()`, which starts a genuine new server. The old client owns an OkHttp
     * engine, so it is closed here rather than leaked -- the same ordering `onCleared` uses.
     *
     * Double-tapping is safe: [load] is not guarded here, but `RuntimeSetup.run()` holds a `Mutex` and
     * `ProotLauncher.start()` is synchronized and refuses to start a second server, so a second tap
     * joins the first run instead of racing it.
     */
    fun restart() {
        val previous = activeCloseable
        activeApi = null
        activeCloseable = null
        try {
            previous?.close()
        } catch (ignored: Exception) {
            // Releasing the old engine must never stop the restart that replaces it.
        }
        load()
    }

    /**
     * The client to use, running setup first on the production path.
     *
     * This is the wiring the whole slice exists for: the endpoint is whatever
     * [SessionRuntime.run] bound to the server the launcher started. There is no fallback to a
     * constant, and no path on which a placeholder can be reached.
     *
     * The result is remembered so a retry after a transient failure re-uses the running server
     * instead of spawning a second one; [RuntimeSetup] makes the same decision on its side.
     */
    private suspend fun resolveApi(): OpenCodeApi? {
        activeApi?.let { return it }
        val prebuilt = api
        if (prebuilt != null) {
            activeApi = prebuilt
            return prebuilt
        }
        val rt = runtime ?: return null
        return try {
            rt.run().also { client ->
                activeApi = client
                // The client produced by setup owns an engine; the ViewModel owns it now.
                activeCloseable = client as? AutoCloseable
                _state.update { it.copy(serverUrl = loopbackOf(client)) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Throwable) {
            failed(describe(cause))
            null
        }
    }

    /**
     * The endpoint the app is talking to, for the header line.
     *
     * Reads it off the base URL by removing the scheme, so no password and no credential is ever
     * derived or displayed. Returns null when the URL is not one of ours, rather than guessing.
     */
    private fun loopbackOf(client: OpenCodeApi): String? =
        (client as? OpenCodeClient)?.let { it.baseUrlForDisplay }

    private fun failed(reason: String) {
        _state.update {
            it.copy(connection = ConnectionState.Unreachable(reason), roots = emptyList())
        }
    }

    /**
     * Maps a transport failure to one honest sentence. Nothing here reads a response body, logs,
     * or interpolates the endpoint or the password -- only the app-level exception type.
     *
     * The Ktor types were deliberately mapped to [ApiException] inside the client, so this file
     * imports no HTTP-library types at all and every branch here is reachable from a plain fake.
     */
    private fun describe(cause: Throwable): String = when (cause) {
        // A setup failure already carries a complete, user-presentable sentence -- and
        // RuntimeSetup guarantees it was scrubbed before it was thrown. Rendering it is both
        // safe and the difference between "Could not reach the server (SetupFailed)" and the
        // actual reason the first run failed.
        is SetupFailed -> cause.message?.takeIf { it.isNotBlank() } ?: "Setup failed."
        is ApiException.Unauthorized -> "Server rejected the credentials (HTTP 401)."
        is ApiException.ServerError -> "Server answered with HTTP ${cause.status}."
        is ApiException.Unreachable ->
            // The underlying cause carries the useful part -- OkHttp surfaces connection refusal,
            // timeouts and DNS as distinct exception types. Only the simple name is used, which
            // holds no address and no secret.
            "Could not reach the server (${cause.cause?.javaClass?.simpleName ?: "unknown error"})."
        else -> "Could not reach the server (${cause::class.simpleName ?: "unknown error"})."
    }

    override fun onCleared() {
        // ViewModel.onCleared runs when the Activity is finishing for good, not on rotation, so
        // this is the right single place to release the OkHttp engine and stop the server. Null
        // for a fake, which owns nothing.
        try {
            activeCloseable?.close()
        } catch (ignored: Exception) {
            // Never let release failure mask the original teardown.
        }
        closeable?.close()
        super.onCleared()
    }

    companion object {
        /**
         * Factory for `by viewModels { … }` in the Activity.
         *
         * Takes a directory (production passes `filesDir/runtime`), never a [com.musa.opencodeharness.data.ServerEndpoint]:
         * the endpoint is discovered from the server the launcher starts, so it cannot be
         * supplied by a caller and cannot go stale.
         *
         * The graph is built ONCE here, outside the initializer, so a `viewModels` re-creation
         * (process death and restore) cannot build a second launcher or a second HTTP engine for
         * a ViewModel that already owns one.
         */
        fun factory(
            runtimeDir: File,
            nativeLibraryDir: File,
            scope: CoroutineScope,
        ): ViewModelProvider.Factory {
            val resources = ProductionRuntime.create(runtimeDir, scope, nativeLibraryDir)
            return viewModelFactory {
                initializer {
                    SessionListViewModel(
                        runtime = resources.runtime,
                        closeable = resources.closeable,
                    )
                }
            }
        }
    }
}
