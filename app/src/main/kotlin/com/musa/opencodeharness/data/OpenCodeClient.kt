package com.musa.opencodeharness.data

import com.musa.opencodeharness.data.model.Agent
import com.musa.opencodeharness.data.model.Command
import com.musa.opencodeharness.data.model.Envelope
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
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
) : AutoCloseable {

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

    /** `GET /api/health` -> `{"healthy":true}`. Throws if the server is unreachable. */
    suspend fun health(): Health = http.get("$base/api/health").body()

    suspend fun listSessions(): List<SessionInfo> =
        http.get("$base/api/session").body<Envelope<SessionInfo>>().data

    suspend fun listAgents(): List<Agent> =
        http.get("$base/api/agent").body<Envelope<Agent>>().data

    suspend fun listCommands(): List<Command> =
        http.get("$base/api/command").body<Envelope<Command>>().data

    override fun close() = http.close()
}
