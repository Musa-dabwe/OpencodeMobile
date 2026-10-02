package com.musa.opencodeharness.ui

import com.musa.opencodeharness.data.ApiException
import com.musa.opencodeharness.data.OpenCodeApi
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine tests for [SessionListViewModel], run against a hand-written fake -- no mock
 * library, no network. The fake's suspend functions complete immediately, so with an
 * [Dispatchers.Unconfined] scope the load launched in the init block settles synchronously.
 */
class SessionListViewModelTest {

    /** Scriptable stand-in for the seam. Records calls so ordering is assertable too. */
    private class FakeApi(
        private val healthResult: Health = Health(healthy = true),
        private val healthError: Throwable? = null,
        private val sessions: List<SessionInfo> = emptyList(),
        private val sessionsError: Throwable? = null,
    ) : OpenCodeApi {
        var healthCalls = 0
            private set
        var listSessionsCalls = 0
            private set

        override suspend fun health(): Health {
            healthCalls++
            healthError?.let { throw it }
            return healthResult
        }

        override suspend fun listSessions(): List<SessionInfo> {
            listSessionsCalls++
            sessionsError?.let { throw it }
            return sessions
        }
    }

    private fun session(id: String, parentID: String? = null) =
        SessionInfo(id = id, title = "t-$id", parentID = parentID)

    private fun viewModelOf(fake: FakeApi): SessionListViewModel =
        SessionListViewModel(
            api = fake,
            // Null closeable: the fake has nothing to release. The real client is closed by the
            // production factory's onCleared path, not in these tests.
            closeable = null,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    @Test
    fun healthyWithSessions_setsHealthyConnectionWithTree() {
        val fake = FakeApi(
            sessions = listOf(
                session("ses_root"),
                session("ses_child", "ses_root"),
            ),
        )

        val vm = viewModelOf(fake)

        assertEquals(ConnectionState.Healthy, vm.state.value.connection)
        // The tree, not the flat list: the child must be nested under the root.
        assertEquals(1, vm.state.value.roots.size)
        assertEquals("ses_root", vm.state.value.roots.single().session.id)
        assertEquals(
            listOf("ses_child"),
            vm.state.value.roots.single().children.map { it.session.id },
        )
        assertFalse(vm.state.value.isEmptyResult)
        assertEquals(1, fake.healthCalls)
        assertEquals(1, fake.listSessionsCalls)
    }

    @Test
    fun healthThrows_setsUnreachableWithTransportMessage() {
        val fake = FakeApi(healthError = RuntimeException("boom"))

        val vm = viewModelOf(fake)

        val state = vm.state.value
        assertTrue( state.connection is ConnectionState.Unreachable)
        assertEquals(
            "Could not reach the server (RuntimeException).",
            (state.connection as ConnectionState.Unreachable).reason,
        )
        // Sessions must not be attempted once health failed.
        assertEquals(0, fake.listSessionsCalls)
        assertTrue(state.roots.isEmpty())
    }

    @Test
    fun healthNotHealthy_getsItsOwnMessageNotTheGenericOne() {
        val fake = FakeApi(healthResult = Health(healthy = false))

        val vm = viewModelOf(fake)

        assertTrue(vm.state.value.connection is ConnectionState.Unreachable)
        val reason = (vm.state.value.connection as ConnectionState.Unreachable).reason
        assertEquals("Server answered, but reported healthy=false.", reason)
        // Non-vacuous guard: the generic transport failure phrasing must NOT be what we got.
        assertFalse(reason.startsWith("Could not reach the server"))
        assertEquals(0, fake.listSessionsCalls)
    }

    @Test
    fun unauthorizedFromHealth_getsCredentialsMessage() {
        val fake = FakeApi(healthError = ApiException.Unauthorized(null))

        val vm = viewModelOf(fake)

        assertTrue(vm.state.value.connection is ConnectionState.Unreachable)
        assertEquals(
            "Server rejected the credentials (HTTP 401).",
            (vm.state.value.connection as ConnectionState.Unreachable).reason,
        )
    }

    @Test
    fun healthyHealthButSessionsThrows_getsItsOwnDistinctMessage() {
        val fake = FakeApi(sessionsError = RuntimeException("boom"))

        val vm = viewModelOf(fake)

        assertTrue(vm.state.value.connection is ConnectionState.Unreachable)
        val reason = (vm.state.value.connection as ConnectionState.Unreachable).reason
        assertEquals(
            "Server is healthy, but the session list could not be read: Could not reach the server (RuntimeException).",
            reason,
        )
        // It must NOT read like the server was unreachable overall.
        assertFalse(reason.startsWith("Could not reach the server"))
    }

    @Test
    fun cancellationFromHealth_isNotSwallowedIntoErrorState() {
        val fake = FakeApi(healthError = CancellationException())

        val vm = viewModelOf(fake)

        // If cancellation were swallowed by the Throwable branch, connection would be
        // Unreachable("Could not reach the server (CancellationException)."). It must stay
        // exactly where load() left it: Connecting.
        assertEquals(ConnectionState.Connecting, vm.state.value.connection)
        assertEquals(0, fake.listSessionsCalls)
    }

    @Test
    fun cancellationFromListSessions_isNotSwallowedIntoErrorState() {
        val fake = FakeApi(sessionsError = CancellationException())

        val vm = viewModelOf(fake)

        assertEquals(ConnectionState.Connecting, vm.state.value.connection)
        assertEquals(1, fake.listSessionsCalls)
    }

    @Test
    fun healthyWithZeroSessions_yieldsEmptyResultNotError() {
        val fake = FakeApi(sessions = emptyList())

        val vm = viewModelOf(fake)

        assertEquals(ConnectionState.Healthy, vm.state.value.connection)
        assertTrue(vm.state.value.roots.isEmpty())
        assertTrue(vm.state.value.isEmptyResult)
        assertFalse(vm.state.value.connection is ConnectionState.Unreachable)
        assertEquals(1, fake.healthCalls)
        assertEquals(1, fake.listSessionsCalls)
    }
}
