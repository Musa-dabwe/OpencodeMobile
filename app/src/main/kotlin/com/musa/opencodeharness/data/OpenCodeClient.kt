package com.musa.opencodeharness.data

import com.musa.opencodeharness.data.model.Agent
import com.musa.opencodeharness.data.model.Command
import com.musa.opencodeharness.data.model.Envelope
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** The server requires this username literally; only the password is caller-supplied. */
private const val BASIC_AUTH_USER = "opencode"

/**
 * Shared with unit tests so the shapes they lock are the shapes the client decodes.
 *
 * `ignoreUnknownKeys` is mandatory: real session payloads carry many fields beyond the ones
 * we model (tokens, time, fork, revert, location, metadata, ...).
 */
internal val openCodeJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Typed client for the OpenCode HTTP API.
 *
 * The server binds loopback only and is reached over `http://127.0.0.1:<port>` because proot
 * shares Android's network namespace. The password is always supplied by the caller: with
 * `OPENCODE_SERVER_PASSWORD` set on the server, an unauthenticated request gets a 401.
 *
 * Not thread-safe to reconfigure, but all methods are safe to call concurrently. Close it
 * when done; it owns an OkHttp engine.
 *
 * Transport and protocol failures propagate as Ktor exceptions (`ResponseException` for a
 * non-2xx such as a 401 from a wrong password). Callers decide how to present them.
 */
class OpenCodeClient(
    baseUrl: String,
    password: String,
) : OpenCodeApi, AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    // Built once here, never stored, never logged, never logged-to. ktor-client-auth is not a
    // declared dependency, so the Basic header is assembled directly rather than via
    // `basicAuth(...)` -- a one-line value, not the auth plugin's challenge/refresh machinery.
    private val authorization =
        "Basic " + "$BASIC_AUTH_USER:$password".encodeBase64()

    private val http = HttpClient(OkHttp) {
        // Default is true; stated explicitly because a 401 must fail loudly, not decode.
        expectSuccess = true
        install(ContentNegotiation) { json(openCodeJson) }
        defaultRequest {
            header(HttpHeaders.Authorization, authorization)
        }
    }
    // Deliberately no Logging plugin: it risks emitting the Authorization header, and the
    // password must never be logged. The ktor-client-logging dependency stays declared for a
    // later task that adds redacted diagnostics.

    /** `GET /api/health` -> `{"healthy":true}`. Throws [ApiException] if it cannot be read. */
    override suspend fun health(): Health = call { http.get("$base/api/health").body() }

    override suspend fun listSessions(): List<SessionInfo> =
        call { http.get("$base/api/session").body<Envelope<SessionInfo>>().data }

    suspend fun listAgents(): List<Agent> =
        call { http.get("$base/api/agent").body<Envelope<Agent>>().data }

    suspend fun listCommands(): List<Command> =
        call { http.get("$base/api/command").body<Envelope<Command>>().data }

    /**
     * Runs [block], translating any transport failure into [ApiException].
     *
     * `expectSuccess` means a non-2xx arrives as [ClientRequestException]. Cancellation is
     * deliberately rethrown untouched by [asApiException]; see its KDoc for why that matters.
     */
    private suspend inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        throw asApiException(failure)
    }

    override fun close() = http.close()
}

/**
 * Maps one transport failure onto an [ApiException].
 *
 * Split out as a top-level function rather than living inline in `call` so it can be unit-tested
 * directly. The alternative — exercising it through a real request — would need Ktor's
 * `MockEngine`, and the interesting case ([CancellationException]) is not reachable that way
 * anyway, because a cancelled coroutine never returns a response to validate.
 *
 * Cancellation is returned unchanged (not wrapped), and `call` rethrows it. Wrapping it would turn
 * a torn-down ViewModel's load into a rendered "could not reach the server" error, which is
 * exactly the bug the branch exists to prevent.
 */
internal fun asApiException(failure: Throwable): Throwable = when (failure) {
    is CancellationException -> failure
    // Already mapped: re-wrapping would erase the 401-vs-500 distinction the UI branches on.
    // Not reachable today (only this file throws ApiException, and only from inside `call`), but
    // the guard is what makes the function safe to call from anywhere.
    is ApiException -> failure
    is ClientRequestException -> apiExceptionForStatus(failure.response.status.value, failure)
    else -> ApiException.Unreachable(failure)
}

/**
 * The decision that matters, split out so it is testable without constructing a
 * [ClientRequestException] — whose constructor reaches into an `HttpClientCall`, so no fake
 * response can be built for one.
 */
internal fun apiExceptionForStatus(status: Int, cause: Throwable): ApiException =
    if (status == HttpStatusCode.Unauthorized.value) {
        ApiException.Unauthorized(cause)
    } else {
        ApiException.ServerError(status, cause)
    }
