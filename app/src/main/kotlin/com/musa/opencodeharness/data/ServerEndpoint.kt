package com.musa.opencodeharness.data

/**
 * Where the OpenCode server listens and the credentials to talk to it.
 *
 * Produced by the runtime, never declared here: `ProotLauncher.start()` picks a free loopback
 * port and generates the per-run password, and its [ProotHandle] is what fills this in. There
 * used to be a `PLACEHOLDER` constant here, pointing at a port no server had ever bound; it is
 * gone, and its absence is the point -- an endpoint this app did not start is not an endpoint
 * the app is willing to talk to.
 *
 * Two invariants, kept in the type and honoured by every caller:
 *
 *  - The server binds loopback only, and proot shares Android's network namespace, so the base
 *    URL is always `http://127.0.0.1:<port>`. Never a LAN address, never https to loopback.
 *  - [password] is never logged, never rendered, and never interpolated into a user-facing
 *    string. [OpenCodeClient] turns it into an `Authorization` header and nothing else, and the
 *    UI shows only `host:port`. The only place a real password may be persisted is the
 *    Keystore, which does not exist yet; until then it lives in memory for the life of the
 *    process that generated it.
 */
data class ServerEndpoint(
    val baseUrl: String,
    val password: String,
)
