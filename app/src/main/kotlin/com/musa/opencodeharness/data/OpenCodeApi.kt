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
 * Deliberately covers ONLY what the UI calls today: [health] and [listSessions].
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
}
