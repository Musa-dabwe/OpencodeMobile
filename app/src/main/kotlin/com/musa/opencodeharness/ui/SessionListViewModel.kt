package com.musa.opencodeharness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musa.opencodeharness.data.OpenCodeClient
import com.musa.opencodeharness.data.ServerEndpoint
import com.musa.opencodeharness.data.SessionNode
import com.musa.opencodeharness.data.buildSessionTree
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * Everything the session screen renders: the connection state plus the session tree.
 *
 * [roots] is the output of [buildSessionTree], not the flat server list, so the screen can
 * indent sub-agent sessions under their parent. It is non-empty only while [connection] is
 * [ConnectionState.Healthy] -- a failed load clears it rather than leaving a stale list on
 * screen next to an error.
 */
data class SessionListUiState(
    val connection: ConnectionState = ConnectionState.Connecting,
    val roots: List<SessionNode> = emptyList(),
) {
    /**
     * True once the server answered and the list came back empty. Distinct from "still
     * connecting" and from "failed", so the screen can say "no sessions yet" rather than either
     * spinning forever or showing an error for a perfectly healthy empty server.
     */
    val isEmptyResult: Boolean
        get() = connection is ConnectionState.Healthy && roots.isEmpty()
}

/**
 * Drives the one screen in this slice: connect, read the session list, group it into a tree.
 *
 * No dependency-injection framework is wired up (and none is needed yet), so the [OpenCodeClient]
 * is built here from a [ServerEndpoint] supplied by [factory] and torn down in [onCleared].
 *
 * The client is created ONCE per ViewModel instance on purpose:
 * [OpenCodeClient] is [AutoCloseable] and owns an OkHttp engine, so building one per load or per
 * recomposition would leak an engine's thread pool and connection pool. One ViewModel -> one
 * client -> one `close()`.
 */
class SessionListViewModel(
    endpoint: ServerEndpoint,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionListUiState())
    val state: StateFlow<SessionListUiState> = _state.asStateFlow()

    /**
     * Deliberately a `val` created at construction, not a `fun get() = OpenCodeClient(...)`.
     * See the class KDoc for why.
     */
    private val client: OpenCodeClient = OpenCodeClient(
        baseUrl = endpoint.baseUrl,
        password = endpoint.password,
    )

    init {
        load()
    }

    /** Re-runs health + session load. Wired to the retry action on the unreachable state. */
    fun load() {
        _state.value = SessionListUiState(connection = ConnectionState.Connecting)
        viewModelScope.launch {
            val health = try {
                client.health()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation: the coroutine is being torn down (onCleared, or the
                // ViewModel scope is cancelled) and must unwind normally. Catching Throwable
                // below without this branch would convert a cancel into a rendered error.
                throw cancellation
            } catch (cause: Throwable) {
                _state.value = failed(describe(cause))
                return@launch
            }

            if (!health.healthy) {
                // The transport worked and the server answered, but with healthy=false. That is
                // not unreachable, so it gets its own message rather than the generic one.
                _state.value = failed("Server answered, but reported healthy=false.")
                return@launch
            }

            val sessions = try {
                client.listSessions()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Throwable) {
                // Health is fine but the list did not come back, so saying "server unreachable"
                // would be wrong. Say what actually happened.
                _state.value = failed("Server is healthy, but the session list could not be read: ${describe(cause)}")
                return@launch
            }

            _state.value = SessionListUiState(
                connection = ConnectionState.Healthy,
                // Client-side grouping: the server has no children-listing route, so the tree is
                // derived from the flat list on `parentID`.
                roots = buildSessionTree(sessions),
            )
        }
    }

    private fun failed(reason: String) =
        SessionListUiState(connection = ConnectionState.Unreachable(reason))

    /**
     * Maps a transport failure to one honest sentence. Nothing here reads a response body, logs,
     * or interpolates the endpoint or the password -- only the HTTP status or the exception type.
     */
    private fun describe(cause: Throwable): String = when (cause) {
        is ClientRequestException ->
            if (cause.response.status == HttpStatusCode.Unauthorized) {
                "Server rejected the credentials (HTTP 401). The password is a placeholder in this build."
            } else {
                "Server answered with HTTP ${cause.response.status.value}."
            }
        // OkHttp surfaces connection refusal/timeouts/DNS as its own exception types, so the
        // simple name is the useful part; it carries no address and no secret.
        else -> "Could not reach the server (${cause::class.simpleName ?: "unknown error"})."
    }

    override fun onCleared() {
        // ViewModel.onCleared runs when the Activity is finishing for good, not on rotation, so
        // this is the right single place to release the OkHttp engine.
        client.close()
        super.onCleared()
    }

    companion object {
        /**
         * Factory for `by viewModels { … }` in the Activity. Exists instead of a no-arg
         * constructor so the endpoint stays an explicit input: swapping
         * [ServerEndpoint.PLACEHOLDER] for the proot/Keystore wiring is a one-line change at the
         * call site.
         */
        fun factory(endpoint: ServerEndpoint = ServerEndpoint.PLACEHOLDER): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SessionListViewModel(endpoint) }
            }
    }
}
