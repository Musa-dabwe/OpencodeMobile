package com.musa.opencodeharness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musa.opencodeharness.data.OpenCodeApi
import com.musa.opencodeharness.data.OpenCodeClient
import com.musa.opencodeharness.data.ServerEndpoint
import com.musa.opencodeharness.data.SessionNode
import com.musa.opencodeharness.data.buildSessionTree
import com.musa.opencodeharness.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * No dependency-injection framework is wired up (and none is needed yet), so the real
 * [OpenCodeClient] is built by [factory] from a [ServerEndpoint] and handed to this ViewModel
 * already constructed. [load] launches on `scope ?: viewModelScope` -- tests pass their own
 * scope, production passes none and gets `viewModelScope`.
 *
 * Lifecycle: the interface this ViewModel holds ([OpenCodeApi]) deliberately does NOT extend
 * [AutoCloseable] -- fakes have nothing to close. The real client's ownership arrives as the
 * separate [closeable] parameter (null in tests, the same [OpenCodeClient] instance in
 * production), so the ViewModel releases the OkHttp engine exactly once in [onCleared] and
 * never calls `close()` on something (a fake) that did not need it. Exactly once because
 * [ViewModel.onCleared] runs exactly once per instance, and the ViewModel owns the closeable
 * outright -- nothing else closes it.
 */
class SessionListViewModel(
    private val api: OpenCodeApi,
    private val closeable: AutoCloseable? = null,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionListUiState())
    val state: StateFlow<SessionListUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Re-runs health + session load. Wired to the retry action on the unreachable state. */
    fun load() {
        _state.value = SessionListUiState(connection = ConnectionState.Connecting)
        (scope ?: viewModelScope).launch {
            val health = try {
                api.health()
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
                api.listSessions()
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
     * or interpolates the endpoint or the password -- only the app-level exception type.
     *
     * The Ktor types were deliberately mapped to [ApiException] inside the client, so this file
     * imports no HTTP-library types at all and every branch here is reachable from a plain fake.
     */
    private fun describe(cause: Throwable): String = when (cause) {
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
        // this is the right single place to release the OkHttp engine. Null for a fake.
        closeable?.close()
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
                initializer {
                    // The real client is constructed HERE, from the injected endpoint, and
                    // handed to the ViewModel already built -- the ViewModel never sees the
                    // endpoint constant and never constructs its own dependency. The same
                    // instance arrives twice: once as the [OpenCodeApi] it calls, once as the
                    // [AutoCloseable] it owns for exactly one close in onCleared.
                    val client = OpenCodeClient(
                        baseUrl = endpoint.baseUrl,
                        password = endpoint.password,
                    )
                    SessionListViewModel(api = client, closeable = client)
                }
            }
    }
}
