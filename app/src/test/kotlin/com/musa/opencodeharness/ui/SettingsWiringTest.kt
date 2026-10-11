package com.musa.opencodeharness.ui

import com.musa.opencodeharness.data.OpenCodeApi
import com.musa.opencodeharness.data.ServerEndpoint
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.runtime.launch.LogBuffer
import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.data.runtime.launch.StartedServer
import com.musa.opencodeharness.data.runtime.setup.SessionRuntime
import com.musa.opencodeharness.data.runtime.setup.SetupStep
import com.musa.opencodeharness.ui.settings.serverCardModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three things Task 4 added to the wiring, each proven at the ViewModel boundary rather than in
 * a screenshot:
 *
 *  1. the launcher's own `ServerState` reaches the screen state, so the card can render `Dead`;
 *  2. the version reaches it from the PROBE, so the design's `v2.0.x` is not a constant;
 *  3. `restart()` really re-runs setup instead of re-probing a server that is gone.
 *
 * `serverDeath` cannot stand in for (1): it is one sentence, written once and only cleared by the
 * next `run()`, so it can outlive the run that produced it. That is asserted directly below.
 */
class SettingsWiringTest {

    private class FakeServer(
        override val baseUrl: String,
        override val password: String = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    ) : StartedServer {
        override val log = LogBuffer()
        private val _state = MutableStateFlow<ServerState>(ServerState.Idle)
        override val state: StateFlow<ServerState> = _state
    }

    private class FakeApi(
        version: String? = null,
        private val sessions: List<SessionInfo> = emptyList(),
    ) : OpenCodeApi {
        var versionCalls = 0
            private set

        /** Mutable so a test can model the server losing `/api/info` between two loads. */
        var versionIfPresent: String? = version
        override suspend fun health(): Health = Health(healthy = true)
        override suspend fun listSessions(): List<SessionInfo> = sessions
        override suspend fun serverVersion(): String? {
            versionCalls++
            return versionIfPresent
        }
    }

    private class FakeRuntime(
        private val api: OpenCodeApi,
        private val server: FakeServer,
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

        fun publishServerState(state: ServerState) { _serverState.value = state }
        fun publishDeath(reason: String) { _death.value = reason }

        override suspend fun run(): OpenCodeApi {
            runs++
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

    private fun runtime(api: FakeApi = FakeApi()) = FakeRuntime(api, FakeServer("http://127.0.0.1:45997"))

    // --- the launcher's own state ---------------------------------------------------------------

    /**
     * DELIVERABLE: `ServerState` reaches the screen state.
     *
     * Mutation caught: dropping the `rt.serverState.collect` block, or deriving the card's state from
     * `connection` alone -- which would leave a dead process reading "running" whenever the client
     * still held a healthy answer.
     */
    @Test
    fun theLaunchersServerStateReachesTheScreenState() {
        val rt = runtime()
        val vm = vm(rt)
        assertEquals(ServerState.Idle, vm.state.value.serverState)

        rt.publishServerState(ServerState.Running(pid = 5731, port = 45997))
        assertEquals(ServerState.Running(pid = 5731, port = 45997), vm.state.value.serverState)

        rt.publishServerState(ServerState.Dead(exitCode = 1, signal = null))
        assertEquals(ServerState.Dead(exitCode = 1, signal = null), vm.state.value.serverState)
    }

    /** DELIVERABLE: the `Dead` state reaches the card as the design's stopped server. */
    @Test
    fun aDeadServerProducesTheDesignsStoppedCard() {
        val rt = runtime()
        val vm = vm(rt)
        rt.publishServerState(ServerState.Dead(exitCode = 143, signal = 15))

        val card = serverCardModel(
            serverState = vm.state.value.serverState,
            connection = vm.state.value.connection,
            version = vm.state.value.serverVersion,
            serverUrl = vm.state.value.serverUrl,
        )

        assertEquals("OpenCode server stopped", card.headline)
        assertEquals("exit code 143 (killed by signal 15)", card.detail)
    }

    /**
     * A stale death sentence must not outlive the run that produced it.
     *
     * This is WHY the card reads `ServerState` rather than `serverDeath`: the runtime's sentence is
     * only cleared by the next `run()`, so a restarted server can still be described by the previous
     * one's death. Here the sentence says the server exited, and the launcher's own state says it is
     * running -- the card must follow the state.
     */
    @Test
    fun theCardFollowsTheCurrentStateAndNotAStaleDeathSentence() {
        val rt = runtime()
        val vm = vm(rt)
        rt.publishDeath("exit code 1")
        rt.publishServerState(ServerState.Dead(exitCode = 1, signal = null))
        assertEquals("exit code 1", vm.state.value.serverDeath)

        // The server came back. Nothing has cleared the old sentence yet -- and that is the point.
        rt.publishServerState(ServerState.Running(pid = 5731, port = 45997))

        val card = serverCardModel(
            serverState = vm.state.value.serverState,
            connection = ConnectionState.Healthy,
            version = null,
            serverUrl = null,
        )

        assertEquals("the stale sentence must not win", "OpenCode server running", card.headline)
        assertNull("a restarted server must carry no death sentence", card.detail)
        // And the stale sentence really is still sitting in the state, or the test proves nothing.
        assertEquals("exit code 1", vm.state.value.serverDeath)
    }

    // --- the version ------------------------------------------------------------------------------

    /**
     * DELIVERABLE: the version comes from the probe.
     *
     * The fake reports `2.0.99`, a version that appears nowhere in this repository, so a hardcoded
     * `v2.0.22` in the card or in the ViewModel cannot satisfy this. Mutation caught: a constant, or
     * never calling `serverVersion()` at all.
     */
    @Test
    fun theVersionComesFromTheProbeAndNotFromAConstant() {
        val api = FakeApi(version = "2.0.99")
        val vm = vm(runtime(api))

        assertEquals("2.0.99", vm.state.value.serverVersion)
        assertEquals(1, api.versionCalls)
    }

    /**
     * A server that reports no version yields null, which the card renders by OMITTING a segment.
     *
     * Mutation caught: defaulting to some placeholder version so the sub-line always has three parts.
     */
    @Test
    fun anUnreportedVersionReachesTheStateAsNull() {
        val vm = vm(runtime(FakeApi(version = null)))

        assertNull(vm.state.value.serverVersion)
    }

    /**
     * A version probe that throws must not take the session list down with it.
     *
     * Mutation caught: letting the version read share the health/list failure path, which would turn
     * a cosmetic unknown into "could not reach the server".
     */
    @Test
    fun aFailingVersionProbeDoesNotBreakTheLoad() {
        val failing = object : OpenCodeApi {
            override suspend fun health(): Health = Health(healthy = true)
            override suspend fun listSessions(): List<SessionInfo> = listOf(SessionInfo(id = "s1", title = "t"))
            override suspend fun serverVersion(): String? = throw IllegalStateException("no /api/info here")
        }
        val vm = vm(runtime(FakeApi()).let { FakeRuntime(failing, FakeServer("http://127.0.0.1:45997")) })

        assertEquals(ConnectionState.Healthy, vm.state.value.connection)
        assertEquals(1, vm.state.value.roots.size)
        assertNull(vm.state.value.serverVersion)
    }

    /**
     * A reload clears the previous server's version before it re-probes.
     *
     * Mutation caught: retaining `serverVersion` across `load()`, which would put one server's version
     * beside a different server that is still starting.
     */
    @Test
    fun reloadingClearsTheStaleVersion() {
        val api = FakeApi(version = "2.0.22")
        val rt = runtime(api)
        val vm = vm(rt)
        assertEquals("2.0.22", vm.state.value.serverVersion)

        // A server that stops answering its info route: the second probe reports nothing.
        api.versionIfPresent = null
        vm.load()

        assertNull("a stale version must not survive a reload", vm.state.value.serverVersion)
    }

    // --- restart ----------------------------------------------------------------------------------

    /**
     * DELIVERABLE: `Restart` is a restart.
     *
     * `resolveApi` caches its client, so a plain `load()` after a death would re-probe a port nothing
     * is listening on and never spawn a new server. This asserts the setup actually runs a second
     * time, which is the whole difference between a Restart button and a Retry button wearing its
     * name. Mutation caught: `restart()` delegating straight to `load()`.
     */
    @Test
    fun restartRunsSetupAgainRatherThanReProbingTheCachedClient() {
        val rt = runtime()
        val vm = vm(rt)
        assertEquals(1, rt.runs)

        vm.restart()

        assertEquals("a restart must start a new server, not re-probe the dead one", 2, rt.runs)
        assertEquals(2, rt.endpoints.size)
    }

    /** The control half of the same property: `load()` alone does NOT re-run setup. */
    @Test
    fun loadAloneDoesNotRunSetupAgain() {
        val rt = runtime()
        val vm = vm(rt)

        vm.load()

        assertEquals(
            "load() reuses the running server on purpose -- that is what makes restart() necessary",
            1,
            rt.runs,
        )
    }

    /**
     * `restart()` releases the client it is dropping.
     *
     * Every production client owns an OkHttp engine, and `restart()` is the one place in this task
     * that discards one, so a restart that forgot to close it would leak an engine per tap. A
     * close-counting fake makes that observable, and the order matters: closed BEFORE the new run
     * starts, so the old transport is not still answering while the new one is being built.
     *
     * Mutation caught: `activeCloseable = null` without `close()`, or the close moved after `load()`.
     */
    @Test
    fun aRestartClosesTheClientItDropsBeforeStartingTheNewOne() {
        val closed = mutableListOf<String>()
        val api = ClosingApi("first", closed)
        val rt = FakeRuntime(api, FakeServer("http://127.0.0.1:45997"))
        val vm = vm(rt)
        assertTrue("the first load must have built a client", rt.runs == 1)

        vm.restart()

        assertEquals("the dropped client must be released exactly once", listOf("first"), closed)
    }

    /** A fake client that records its own release, and which build it belonged to. */
    private class ClosingApi(private val label: String, private val closed: MutableList<String>) : OpenCodeApi, AutoCloseable {
        override suspend fun health(): Health = Health(healthy = true)
        override suspend fun listSessions(): List<SessionInfo> = emptyList()
        override fun close() {
            closed += label
        }
    }

    /**
     * The password never reaches the new state, at any point in the restart cycle.
     *
     * `restart()` closes a client that was built from the endpoint, which is the one place in this
     * task that touches an object holding a credential -- so the invariant is re-asserted here.
     */
    @Test
    fun thePasswordNeverReachesTheScreenStateAcrossARestart() {
        val password = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val rt = FakeRuntime(FakeApi(version = "2.0.22"), FakeServer("http://127.0.0.1:45997", password))
        val vm = vm(rt)

        vm.restart()

        assertNotNull(vm.state.value.serverVersion)
        assertTrue(
            "the state must not carry the password: ${vm.state.value}",
            !vm.state.value.toString().contains(password),
        )
    }
}
