package com.musa.opencodeharness.data

import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo

/**
 * The seam between the session screen and the concrete OpenCode HTTP client.
 *
 * Why this exists: `OpenCodeClient` is built from a runtime endpoint (proot picks the port,
 * the password is generated per install), so the ViewModel cannot construct it at
 * construction time, and cannot be unit-tested against a fake unless it depends on an
 * interface rather than the concrete class.
 *
 * Deliberately covers ONLY what the UI calls today: [health], [listSessions] and [serverVersion].
 * [OpenCodeClient.listAgents] and [OpenCodeClient.listCommands] are NOT on this interface.
 * The agent picker and slash-command list that need them do not exist yet; until they do,
 * putting them here would be a speculative method the fakes must then stub for no caller.
 * When the picker/slash-command slices land, they extend this interface (or the fake), at
 * which point the compile error on fakes is exactly the reminder to do so deliberately.
 *
 * [OpenCodeClient] implements it. Tests implement it with a fake, no mocking library.
 *
 * Lifecycle: this interface deliberately does NOT extend [AutoCloseable]. The interface is
 * the "calls" seam, not the "owns an OkHttp engine" seam -- fakes have nothing to close, so
 * a closeable-on-every-fake design would force a meaningless `close()` onto test doubles.
 * Ownership is passed separately (see `SessionListViewModel`'s `closeable` parameter).
 */
interface OpenCodeApi {
    suspend fun health(): Health
    suspend fun listSessions(): List<SessionInfo>

    /**
     * The version the server reports, or null when it reports none.
     *
     * Design section 3.7 puts a real version in the Settings card, and the brief for Task 4 is
     * explicit that it must come from the probe rather than a constant -- so it is read from
     * `GET /api/info`, the same route [OpenCodeClient.health] already falls back to, and decoded
     * into the same non-null-`version` type that makes an SPA HTML body impossible to accept.
     *
     * DEFAULTED to null rather than abstract on purpose, for two reasons. It keeps every existing
     * fake compiling with no stub to add, and it keeps "this server does not tell us its version" a
     * representable, honest answer -- a v1 server, a proxy that strips `/api/info`, or a build that
     * failed before the first probe all render a card with no version segment rather than a guess.
     */
    suspend fun serverVersion(): String? = null
}
