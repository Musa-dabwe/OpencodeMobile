package com.musa.opencodeharness.data

/**
 * Where the OpenCode server listens and the credentials to talk to it.
 *
 * ## This is a PLACEHOLDER, and both fields are wrong on purpose.
 *
 * The real app resolves these at runtime: the bind address and port come from the proot
 * launcher that starts `opencode serve`, and the password comes from the Keystore-backed
 * per-install secret exported as `OPENCODE_SERVER_PASSWORD`. That wiring is a later task, so
 * this slice hardcodes a value that is documented as fake rather than pretending to be real
 * configuration.
 *
 * Two invariants, kept in the type and honoured by every caller in this slice:
 *
 *  - The server binds loopback only, and proot shares Android's network namespace, so the base
 *    URL is always `http://127.0.0.1:<port>`. Never a LAN address, never https to loopback.
 *  - [password] is never logged, never rendered, and never interpolated into a user-facing
 *    string. [OpenCodeClient] turns it into an `Authorization` header and nothing else. The only
 *    place a real password may be persisted is the Keystore, which does not exist yet.
 */
data class ServerEndpoint(
    val baseUrl: String,
    val password: String,
) {
    companion object {
        /**
         * PLACEHOLDER endpoint for the Phase 1 vertical slice.
         *
         * The port is the one the Phase 0 probe actually used against a live server (see
         * docs/phase0-findings.md), so it is a plausible loopback target rather than an invented
         * one. The password is a literal that is NOT any server's password -- pointing the app
         * at a real server with this value yields the 401 branch of the unreachable state, which
         * is the honest result.
         *
         * Replaced by the proot/Keystore wiring in a later task; nothing should depend on this
         * constant's value.
         */
        val PLACEHOLDER = ServerEndpoint(
            baseUrl = "http://127.0.0.1:45123",
            password = "placeholder-password-not-a-real-secret",
        )
    }
}
