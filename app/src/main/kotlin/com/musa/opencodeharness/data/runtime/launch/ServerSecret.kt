package com.musa.opencodeharness.data.runtime.launch

import java.security.SecureRandom

/**
 * The `OPENCODE_SERVER_PASSWORD` value for one server run.
 *
 * ## Rules this type exists to enforce
 *
 *  - [toString] NEVER contains the value, so the secret cannot leak through string
 *    interpolation in a log line, a crash report, a Compose `Text`, or an exception message
 *    that happens to embed an object.
 *  - The value is passed to the server through the process ENVIRONMENT only, never through
 *    argv — `ps` output and `ProcessBuilder.start()` failure messages both contain argv, and
 *    neither may ever contain the password.
 *  - Lifetime: generated per start() and kept in memory. NOT persisted anywhere — no disk
 *    file, no Keystore (explicitly out of scope for this slice, per the plan: "If Keystore is
 *    out of scope for this slice, generate per-process and document that explicitly — but do
 *    not write it to disk in plaintext"). Consequence: after process death the previous
 *    server's password is unrecoverable, which is exactly why [OrphanReaper] kills an orphan
 *    server instead of trying to adopt one.
 *
 * Entropy: [ENTROPY_BYTES] bytes straight from [SecureRandom] (128 bits), hex-encoded so the
 * value is restricted to `[0-9a-f]` — a charset that is safe inside an HTTP Basic auth
 * credential, a URL-encoded form, a shell, and a JSON body without any escaping concerns.
 */
class ServerSecret internal constructor(
    private val value: String,
) {
    /** The plaintext. Callers: the server's environment, and an Authorization header. Nothing else. */
    fun expose(): String = value

    /** Redacted by construction — see the class doc. */
    override fun toString(): String = "ServerSecret(***redacted***)"

    // Identity equality only; implementing equals/hashCode over `value` would invite putting
    // the secret into a data class's generated toString via a wrapper.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** 16 bytes = 128 bits of entropy, the floor this slice requires. */
        const val ENTROPY_BYTES: Int = 16

        private val HEX = "0123456789abcdef".toCharArray()

        /** Generates a fresh secret. [random] is injectable so tests can pin the entropy source. */
        fun generate(random: SecureRandom = SecureRandom()): ServerSecret {
            val bytes = ByteArray(ENTROPY_BYTES)
            random.nextBytes(bytes)
            val chars = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xff
                chars[i * 2] = HEX[b ushr 4]
                chars[i * 2 + 1] = HEX[b and 0x0f]
            }
            return ServerSecret(String(chars))
        }
    }
}
