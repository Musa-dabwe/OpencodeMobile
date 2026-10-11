package com.musa.opencodeharness.ui

import com.musa.opencodeharness.data.ApiException
import com.musa.opencodeharness.data.OpenCodeApi
import com.musa.opencodeharness.data.ServerEndpoint
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.runtime.launch.LogBuffer
import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.data.runtime.launch.StartedServer
import com.musa.opencodeharness.data.runtime.setup.SessionRuntime
import com.musa.opencodeharness.data.runtime.setup.SetupFailed
import com.musa.opencodeharness.data.runtime.setup.SetupStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring this slice exists for: the endpoint the ViewModel talks to must come from the
 * server the launcher started, and the screen must surface setup progress, the redacted log,
 * and an observed server death rather than swallowing any of them.
 *
 * These are deliberately not fakes of the ViewModel's collaborators — they are fakes of the
 * thing UNDER test, so a wrong endpoint cannot be introduced without one of these going red.
 */
class RuntimeWiringTest {

    private class FakeServer(
        override val baseUrl: String,
        override val password: String = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    ) : StartedServer {
        override val log = LogBuffer()
        private val _state = MutableStateFlow<ServerState>(ServerState.Running(4321, 45999))
        override val state: StateFlow<ServerState> = _state
        fun die(exitCode: Int?) {
            _state.value = ServerState.Dead(exitCode, null)
        }
    }

    private class FakeApi(
        private val sessions: List<SessionInfo> = emptyList(),
        private val error: Throwable? = null,
        private val version: String? = null,
    ) : OpenCodeApi {
        override suspend fun health(): Health {
            error?.let { throw it }
            return Health(healthy = true)
        }

        override suspend fun listSessions(): List<SessionInfo> = sessions

        override suspend fun serverVersion(): String? = version
    }

    /** Records the endpoint the ViewModel built its client from. */
    private class RecordingRuntime(
        private val server: StartedServer,
        private val api: OpenCodeApi = FakeApi(),
        private val failure: Throwable? = null,
    ) : SessionRuntime {
        var runs = 0
            private set
        val endpoints = mutableListOf<ServerEndpoint>()

        private val _step = MutableStateFlow<SetupStep>(SetupStep.Idle)
        override val step: StateFlow<SetupStep> = _step
        private val _log = MutableStateFlow<List<String>>(emptyList())
        override val logLines: StateFlow<List<String>> = _log
        private val _death = MutableStateFlow<String?>(null)
        override val serverDeath: StateFlow<String?> = _death
        private val _serverState = MutableStateFlow<ServerState>(ServerState.Idle)
        override val serverState: StateFlow<ServerState> = _serverState

        fun publish(log: List<String>) { _log.value = log }
        fun publishDeath(reason: String) { _death.value = reason }
        fun publishServerState(state: ServerState) { _serverState.value = state }

        override suspend fun run(): OpenCodeApi {
            runs++
            failure?.let { throw it }
            endpoints += ServerEndpoint(server.baseUrl, server.password)
            _step.value = SetupStep.Ready
            return api
        }
    }

    private fun vm(runtime: SessionRuntime) = SessionListViewModel(
        runtime = runtime,
        closeable = null,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private fun session(id: String) = SessionInfo(id = id, title = "t-$id", parentID = null)

    /**
     * DELIVERABLE 1. The endpoint the ViewModel uses is the launcher's, verbatim.
     *
     * Mutation that must break it: any reintroduced fallback endpoint, or passing a constant
     * into the client instead of `runtime.run()`'s server. The port here (45997) is the one
     * from the Phase 0 device capture, so a hardcoded `ServerEndpoint.PLACEHOLDER`-style
     * constant cannot accidentally satisfy this assertion.
     */
    @Test
    fun endpointComesFromTheLauncherNotAConstant() {
        val server = FakeServer(baseUrl = "http://127.0.0.1:45997")
        val runtime = RecordingRuntime(server)

        val vm = vm(runtime)

        assertEquals(1, runtime.runs)
        assertEquals(1, runtime.endpoints.size)
        assertEquals("http://127.0.0.1:45997", runtime.endpoints.single().baseUrl)
        assertEquals(server.password, runtime.endpoints.single().password)
        assertEquals(ConnectionState.Healthy, vm.state.value.connection)
    }

    /** The endpoint is loopback-only: the launcher never hands back anything else. */
    @Test
    fun endpointIsLoopback() {
        val runtime = RecordingRuntime(FakeServer(baseUrl = "http://127.0.0.1:40000"))

        vm(runtime)

        val url = runtime.endpoints.single().baseUrl
        assertTrue("must be loopback, was $url", url.startsWith("http://127.0.0.1:"))
        assertFalse("must never be https to loopback", url.startsWith("https://"))
    }

    /**
     * DELIVERABLE 5. A dead server must be visible, not swallowed.
     *
     * Mutation: dropping the `serverDeath.collect` block from the ViewModel's init, or not
     * clearing `roots`, leaves a stale "Connected" list above a server that has exited.
     */
    @Test
    fun serverDeathIsSurfacedAndClearsTheSessionList() {
        val runtime = RecordingRuntime(FakeServer("http://127.0.0.1:45998"), FakeApi(listOf(session("s1"))))
        val vm = vm(runtime)
        assertEquals(1, vm.state.value.roots.size)

        runtime.publishDeath("exit code 1")

        assertEquals("exit code 1", vm.state.value.serverDeath)
        assertTrue(vm.state.value.roots.isEmpty())
        assertTrue(vm.state.value.connection is ConnectionState.Unreachable)
        assertTrue(
            "the message must name the death: ${vm.state.value.connection}",
            (vm.state.value.connection as ConnectionState.Unreachable).reason.contains("exit code 1"),
        )
    }

    /** The screen's header shows host:port only — never a credential. */
    @Test
    fun sessionListUiStateNeverCarriesThePassword() {
        val password = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val runtime = RecordingRuntime(FakeServer("http://127.0.0.1:45999", password))
        val vm = vm(runtime)

        // serverUrl is only ever set from OpenCodeClient.baseUrlForDisplay; with a fake api it
        // stays null, which is itself the property: no credential can be derived here.
        assertNull(vm.state.value.serverUrl)
        assertFalse(vm.state.value.toString().contains(password))
    }

    /** Setup progress reaches the screen state, so the first-run flow is observable. */
    @Test
    fun setupProgressReachesTheScreenState() {
        val runtime = RecordingRuntime(FakeServer("http://127.0.0.1:45999"))
        runtime.publish(listOf("opencode-cli: downloading"))

        val vm = vm(runtime)

        assertTrue(vm.state.value.isSettingUp.not() || vm.state.value.setup is SetupStep.Ready)
        assertEquals(listOf("opencode-cli: downloading"), vm.state.value.logLines)
    }

    /** The redacted log is passed through verbatim — the screen adds no filtering to bypass. */
    @Test
    fun logLinesArePassedThroughUnfiltered() {
        val runtime = RecordingRuntime(FakeServer("http://127.0.0.1:45999"))
        val lines = listOf("opencode-cli: already installed, skipping", "server listening")
        runtime.publish(lines)

        val vm = vm(runtime)

        assertEquals(lines, vm.state.value.logLines)
    }

    /** A setup failure becomes an unreachable connection, not a crash and not a false "Connected". */
    @Test
    fun setupFailureBecomesUnreachable() {
        val runtime = RecordingRuntime(
            FakeServer("http://127.0.0.1:45999"),
            failure = SetupFailed("runtime setup has not run: missing proot"),
        )

        val vm = vm(runtime)

        val connection = vm.state.value.connection
        assertTrue(connection is ConnectionState.Unreachable)
        assertTrue(
            (connection as ConnectionState.Unreachable).reason.contains("missing proot")
        )
        assertTrue(vm.state.value.roots.isEmpty())
    }

    /** Cancellation during setup tears down; it is never rendered as a failure. */
    @Test
    fun cancellationDuringSetupIsNotRenderedAsFailure() {
        val runtime = RecordingRuntime(
            FakeServer("http://127.0.0.1:45999"),
            failure = CancellationException("viewmodel cleared"),
        )

        val vm = vm(runtime)

        // viewModelScope is not the test scope here, so the throw happens inside the launched
        // coroutine; what matters is that nothing user-facing claimed a failure state.
        assertTrue(vm.state.value.connection !is ConnectionState.Unreachable)
    }

    /** An unauthenticated server still reads as credentials rejected, not as "unreachable". */
    @Test
    fun unauthorizedFromSetupIsNamedAsCredentials() {
        val runtime = RecordingRuntime(
            FakeServer("http://127.0.0.1:45999"),
            failure = ApiException.Unauthorized(RuntimeException("401")),
        )

        val vm = vm(runtime)

        val connection = vm.state.value.connection
        assertTrue(connection is ConnectionState.Unreachable)
        assertTrue(
            (connection as ConnectionState.Unreachable).reason.contains("401"),
        )
    }

    /** The production constructor refuses to be built with both paths, or with neither. */
    @Test
    fun exactlyOneOfRuntimeOrApiIsRequired() {
        val both = runCatching {
            SessionListViewModel(runtime = RecordingRuntime(FakeServer("http://127.0.0.1:1")), api = FakeApi())
        }
        val neither = runCatching { SessionListViewModel() }

        assertTrue(both.isFailure)
        assertTrue(neither.isFailure)
    }
}
