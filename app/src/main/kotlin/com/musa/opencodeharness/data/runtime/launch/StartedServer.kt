package com.musa.opencodeharness.data.runtime.launch

import kotlinx.coroutines.flow.StateFlow

/**
 * The launched server, as the setup orchestrator sees it.
 *
 * A seam, not decoration: [ProotHandle] is a concrete class, so without this interface the only
 * way to test "the orchestrator waits for health and fails fast on observed death" would be to
 * spawn a real OS process from the JVM running the tests. It lives in the launch package (not
 * with the orchestrator) so [ProotHandle] can satisfy it without the launcher depending on the
 * layer that consumes it.
 */
interface StartedServer {
    val baseUrl: String
    val password: String
    val log: LogBuffer
    val state: StateFlow<ServerState>
}
