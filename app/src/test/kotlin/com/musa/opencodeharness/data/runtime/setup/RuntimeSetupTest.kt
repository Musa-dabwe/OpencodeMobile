package com.musa.opencodeharness.data.runtime.setup

import com.musa.opencodeharness.data.OpenCodeApi
import com.musa.opencodeharness.data.ServerEndpoint
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.runtime.Artifact
import com.musa.opencodeharness.data.runtime.ArtifactSource
import com.musa.opencodeharness.data.runtime.RuntimeManifest
import com.musa.opencodeharness.data.runtime.launch.LogBuffer
import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.data.runtime.launch.StartedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume decision table, the health wait, and the failure wording.
 *
 * Every test here names the mutation that would break it; those that protect a security or
 * resume property were additionally confirmed by mutating production code and observing red.
 */
class RuntimeSetupTest {

    private companion object {
        /**
         * Shaped like a real [com.musa.opencodeharness.data.runtime.launch.ServerSecret]: 32 hex
         * chars. It has to be, because `RuntimeSetup.scrub` refuses to scrub anything shorter
         * than [RuntimeSetup.MIN_SCRUBBABLE_SECRET] -- a 1-character "password" would scrub
         * every "p" out of ordinary prose instead of removing a secret.
         */
        const val SECRET_VALUE = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    }

    // --- fakes ---------------------------------------------------------------------------------

    private class FakeStage(
        private val downloaded: MutableSet<String> = mutableSetOf(),
        private val installed: MutableSet<String> = mutableSetOf(),
    ) : RuntimeStage {
        val downloads = mutableListOf<String>()
        val installs = mutableListOf<String>()
        var downloadError: Throwable? = null
        var installError: Throwable? = null

        override fun isDownloaded(artifact: Artifact) = artifact.id in downloaded
        override fun isInstalled(artifactId: String) = artifactId in installed
        override suspend fun download(artifact: Artifact) {
            downloadError?.let { throw it }
            downloads += artifact.id
            downloaded += artifact.id
        }

        override suspend fun install(artifact: Artifact) {
            installError?.let { throw it }
            installs += artifact.id
            installed += artifact.id
        }
    }

    private class FakeServer(
        override val baseUrl: String = "http://127.0.0.1:45999",
        override val password: String = SECRET_VALUE,
        log: LogBuffer = LogBuffer(),
    ) : StartedServer {
        override val log: LogBuffer = log
        private val _state = MutableStateFlow<ServerState>(
            ServerState.Running(pid = 4321, port = 45999)
        )
        override val state: StateFlow<ServerState> = _state

        fun die(exitCode: Int?) {
            _state.value = ServerState.Dead(
                exitCode = exitCode,
                signal = if (exitCode != null && exitCode in 129..192) exitCode - 128 else null,
            )
        }
    }

    private class FakeApi(
        private val healthyAfter: Int = 1,
        private val error: Throwable? = null,
    ) : OpenCodeApi, AutoCloseable {
        var healthCalls = 0
            private set
        var closed = false
            private set

        override suspend fun health(): Health {
            healthCalls++
            if (healthCalls < healthyAfter) throw IllegalStateException("not up yet")
            error?.let { throw it }
            return Health(healthy = true)
        }

        override suspend fun listSessions(): List<SessionInfo> = emptyList()

        override fun close() { closed = true }
    }

    private fun artifact(id: String) = Artifact(
        id = id,
        fileName = "$id.bin",
        source = ArtifactSource.Pinned(url = "https://example.invalid/$id", sizeBytes = 1, sha256 = "0"),
    )

    private fun manifestOf(vararg ids: String) = RuntimeManifest(artifacts = ids.map(::artifact))

    private fun setupOf(
        stage: RuntimeStage,
        server: StartedServer,
        api: OpenCodeApi,
        scope: CoroutineScope,
        healthAttempts: Int = 3,
    ) = RuntimeSetup(
        manifest = manifestOf("alpha", "beta"),
        stage = stage,
        startServer = { server },
        buildApi = { api },
        scope = scope,
        healthAttempts = healthAttempts,
        healthPollMillis = 0,
        sleep = { },
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // --- the resume decision table ------------------------------------------------------------

    /**
     * THE table. Four combinations, four answers, and the third row is the one that costs money.
     *
     * Mutation that must break it: changing `download = !downloaded && !installed` to
     * `download = !downloaded` — i.e. re-downloading the 89 MB npm tarball on every launch of an
     * already-installed runtime.
     */
    @Test
    fun resumeDecisionTable() {
        assertEquals(
            ArtifactPlan("a", download = true, install = true),
            planArtifact("a", downloaded = false, installed = false),
        )
        assertEquals(
            ArtifactPlan("a", download = false, install = true),
            planArtifact("a", downloaded = true, installed = false),
        )
        assertEquals(
            ArtifactPlan("a", download = false, install = false),
            planArtifact("a", downloaded = true, installed = true),
        )
        // Installed but the container is gone: STILL no download. The container is only an
        // input to installing, and a runtime that is already on disk can start a server.
        assertEquals(
            ArtifactPlan("a", download = false, install = false),
            planArtifact("a", downloaded = false, installed = true),
        )
    }

    /** An already-installed runtime must do no work at all — the warm-launch property. */
    @Test
    fun installedRuntimeDoesNoDownloadOrInstall() = runBlocking {
        val stage = FakeStage(installed = mutableSetOf("alpha", "beta"))
        val server = FakeServer()
        val api = FakeApi()
        val sut = setupOf(stage, server, api, testScope())

        sut.run()

        assertEquals(emptyList<String>(), stage.downloads)
        assertEquals(emptyList<String>(), stage.installs)
        assertEquals(SetupStep.Ready, sut.step.value)
    }

    /** A half-finished install resumes at the install, not at the download. */
    @Test
    fun downloadedButNotInstalledSkipsDownload() = runBlocking {
        val stage = FakeStage(downloaded = mutableSetOf("alpha", "beta"))
        val setup = setupOf(stage, FakeServer(), FakeApi(), testScope())

        setup.run()

        assertEquals(emptyList<String>(), stage.downloads)
        assertEquals(listOf("alpha", "beta"), stage.installs)
    }

    /** A fresh device does the whole thing, in manifest order. */
    @Test
    fun freshRunDownloadsThenInstallsEveryArtifact() = runBlocking {
        val stage = FakeStage()
        val setup = setupOf(stage, FakeServer(), FakeApi(), testScope())

        setup.run()

        assertEquals(listOf("alpha", "beta"), stage.downloads)
        assertEquals(listOf("alpha", "beta"), stage.installs)
    }

    // --- health, death, failure ----------------------------------------------------------------

    /**
     * `Running` is NOT healthy. The setup must actually poll before claiming readiness.
     *
     * Mutation: returning on the first `ServerState.Running` read instead of after `health()`.
     */
    @Test
    fun doesNotClaimReadyWhileHealthStillFails() = runBlocking {
        val api = FakeApi(healthyAfter = 3)
        val setup = setupOf(FakeStage(installed = mutableSetOf("alpha", "beta")), FakeServer(), api, testScope())

        setup.run()

        assertEquals(3, api.healthCalls)
        assertEquals(SetupStep.Ready, setup.step.value)
    }

    /**
     * A server that dies during startup must fail immediately, with the exit code, and must not
     * burn the remaining poll budget discovering what it could have been told.
     *
     * Mutation: dropping the `is ServerState.Dead` check, which turns this into a timeout with a
     * misleading message and healthAttempts wasted calls against a dead port.
     */
    @Test
    fun deadServerFailsFastWithExitCode() = runBlocking {
        val server = FakeServer()
        // Kills the server on its first probe, then fails like a refused connection would.
        val killing = object : OpenCodeApi {
            var calls = 0
            override suspend fun health(): Health {
                calls++
                server.die(1)
                throw IllegalStateException("connection refused")
            }

            override suspend fun listSessions(): List<SessionInfo> = emptyList()
        }
        val sut = RuntimeSetup(
            manifest = manifestOf("alpha", "beta"),
            stage = FakeStage(installed = mutableSetOf("alpha", "beta")),
            startServer = { server },
            buildApi = { killing },
            scope = testScope(),
            healthAttempts = 50,
            healthPollMillis = 0,
            sleep = { },
        )

        val failure = runCatching { sut.run() }.exceptionOrNull()

        assertTrue(failure is SetupFailed)
        assertTrue(
            "message must name the exit code, was: ${failure?.message}",
            failure?.message?.contains("exit code 1") == true,
        )
        assertEquals("must stop polling the moment it is dead", 1, killing.calls)
        assertTrue(sut.step.value is SetupStep.Failed)
    }

    /** A launch failure (never had a process) is reported as such, not as an exit code. */
    @Test
    fun launchFailureIsReportedAsLaunchFailure() = runBlocking {
        val dead = object : StartedServer {
            override val baseUrl = "http://127.0.0.1:1"
            override val password = SECRET_VALUE
            override val log = LogBuffer()
            override val state: StateFlow<ServerState> = MutableStateFlow(
                ServerState.Dead(exitCode = null, signal = null, launchError = "runtime setup has not run")
            )
        }
        val sut = setupOf(FakeStage(installed = mutableSetOf("alpha", "beta")), dead, FakeApi(), testScope())

        val failure = runCatching { sut.run() }.exceptionOrNull()

        assertTrue(failure is SetupFailed)
        assertTrue(failure?.message?.contains("launch failed") == true)
        assertTrue(failure?.message?.contains("runtime setup has not run") == true)
    }

    /** A client built for a failed attempt is closed, so a retry does not leak an engine. */
    @Test
    fun clientIsClosedWhenSetupFails() = runBlocking {
        val api = FakeApi(error = IllegalStateException("nope"))
        val sut = setupOf(FakeStage(installed = mutableSetOf("alpha", "beta")), FakeServer(), api, testScope())

        runCatching { sut.run() }

        assertTrue("a failed attempt must not leak its HTTP engine", api.closed)
    }

    /** A successful run hands ownership to the caller instead of closing it. */
    @Test
    fun clientIsNotClosedOnSuccess() = runBlocking {
        val api = FakeApi()
        val sut = setupOf(FakeStage(installed = mutableSetOf("alpha", "beta")), FakeServer(), api, testScope())

        sut.run()

        assertFalse(api.closed)
    }

    /** A retry must re-use a still-running server rather than spawn a second one. */
    @Test
    fun retryReusesRunningServer() = runBlocking {
        val stage = FakeStage(installed = mutableSetOf("alpha", "beta"))
        var starts = 0
        val server = FakeServer()
        val sut = RuntimeSetup(
            manifest = manifestOf("alpha", "beta"),
            stage = stage,
            startServer = { starts++; server },
            buildApi = { FakeApi() },
            scope = testScope(),
            healthAttempts = 1,
            healthPollMillis = 0,
            sleep = { },
        )

        sut.run()
        sut.run()

        assertEquals("a retry must not spawn a second server", 1, starts)
    }

    /** Cancellation is control flow, not a setup failure. */
    @Test
    fun cancellationIsNotSwallowed() = runBlocking {
        val stage = FakeStage().apply { downloadError = CancellationException("gone") }
        val sut = setupOf(stage, FakeServer(), FakeApi(), testScope())

        val thrown = runCatching { sut.run() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue("a cancel must not be rendered as a setup failure", sut.step.value !is SetupStep.Failed)
    }

    // --- the password never escapes ------------------------------------------------------------

    /**
     * SECURITY. The server's own log is shown in the UI, so if it ever echoed the password the
     * redaction must still hold on the way out. Mutation: registering the secret AFTER tailing,
     * or not registering it at all.
     */
    @Test
    fun serverLogTailIsRedactedInTheUi() = runBlocking {
        val log = LogBuffer()
        log.reset(SECRET_VALUE)
        val server = FakeServer(log = log)
        val sut = setupOf(FakeStage(installed = mutableSetOf("alpha", "beta")), server, FakeApi(), testScope())
        sut.run()

        log.append("server starting with password $SECRET_VALUE in its env")
        kotlinx.coroutines.yield()

        val rendered = sut.logLines.value.joinToString("\n")
        assertFalse("the password must never reach the UI", rendered.contains(SECRET_VALUE))
        assertTrue(rendered.contains(LogBuffer.REDACTED))
    }

    /**
     * SECURITY. The health-failure reason is built from an arbitrary exception's message and is
     * rendered to the user, so it goes through the same scrub as every other line of text.
     *
     * This is the window that matters: by the time a health probe can fail, the launcher has
     * generated the password, so it EXISTS and can leak. (An install-time failure cannot leak
     * it — no password has been generated yet.)
     *
     * Mutation: reverting `scrub(describe(failure))` to `describe(failure)` fails this test.
     */
    @Test
    fun healthFailureReasonNeverContainsThePassword() = runBlocking {
        val api = FakeApi(error = IllegalStateException("auth failed for $SECRET_VALUE"))
        val sut = setupOf(FakeStage(installed = mutableSetOf("alpha", "beta")), FakeServer(), api, testScope())

        val failure = runCatching { sut.run() }.exceptionOrNull()

        assertTrue(failure is SetupFailed)
        assertTrue(
            "the failure message must not carry the password: ${failure?.message}",
            failure?.message?.contains(SECRET_VALUE) != true,
        )
        assertTrue(failure?.message?.contains(RuntimeSetup.REDACTED) == true)
        val step = sut.step.value
        assertTrue(step is SetupStep.Failed)
        assertTrue((step as SetupStep.Failed).reason.contains(RuntimeSetup.REDACTED))
        assertTrue(sut.logLines.value.none { it.contains(SECRET_VALUE) })
    }

    /** Nothing in the progress lines names the endpoint's credentials either. */
    @Test
    fun progressLinesNeverContainThePassword() = runBlocking {
        val sut = setupOf(FakeStage(), FakeServer(), FakeApi(), testScope())
        sut.run()

        assertTrue(sut.logLines.value.none { it.contains(SECRET_VALUE) })
        assertNull(sut.serverDeath.value)
    }
}
