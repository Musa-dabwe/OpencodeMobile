package com.musa.opencodeharness.data.runtime.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property tests over generated secrets. NOTE: no literal secret is asserted here on
 * purpose — the test knows FORMAT and PROPERTIES, never a value. The mutation that breaks
 * each test is named in its comment.
 */
class ServerSecretTest {

    private val hex32 = Regex("[0-9a-f]{32}")

    @Test
    fun generatedSecretIs16BytesOfEntropyHexEncoded() {
        // Mutation: ENTROPY_BYTES = 8 -> 16 hex chars, fails the regex. ENTROPY_BYTES = 32
        // -> 64 chars, also fails. Encoding the bytes raw (not hex) -> non-[0-9a-f] chars.
        val secret = ServerSecret.generate()
        assertTrue(
            "expected 32 lowercase-hex chars (16 bytes), got length ${secret.expose().length}",
            hex32.matches(secret.expose()),
        )
        assertEquals(ServerSecret.ENTROPY_BYTES * 2, secret.expose().length)
    }

    @Test
    fun manyGenerationsAreAllUnique() {
        // Mutation: a constant/password-derived-from-time secret fails here — 500 draws in
        // the same millisecond must still all differ.
        val secrets = (1..500).map { ServerSecret.generate().expose() }
        assertEquals("generated secrets collided", 500, secrets.toSet().size)
        assertTrue(secrets.all { hex32.matches(it) })
    }

    @Test
    fun distinctSecretsShowRealBitVariationNotAnIncrementingCounter() {
        // Mutation: an incrementing counter disguised as hex still passes uniqueness but
        // concentrates every value in the first bytes; requiring many distinct FIRST
        // hex chars catches a counter/low-entropy source better than uniqueness alone.
        val firstChars = (1..200).map { ServerSecret.generate().expose().first() }.toSet()
        assertTrue("first hex char varied only $firstChars times — entropy too low", firstChars.size >= 8)
    }

    @Test
    fun toStringNeverContainsTheSecret() {
        // Mutation: `data class ServerSecret(...)` (generated toString includes `value`) or
        // `override fun toString() = value` fails this immediately.
        repeat(20) {
            val secret = ServerSecret.generate()
            assertFalse(
                "toString leaked the secret",
                secret.toString().contains(secret.expose()),
            )
        }
    }

    @Test
    fun toStringIsStableAndRedactedLooking() {
        val secret = ServerSecret.generate()
        assertEquals(secret.toString(), secret.toString())
        assertFalse(secret.toString().contains("null"))
        assertTrue(secret.toString().contains("redacted"))
    }

    @Test
    fun identityEqualityAvoidsASecretInADataClassToString() {
        // Two separately generated secrets are never "equal" — equality over the secret
        // value would invite wrappers whose generated toString prints the value.
        val a = ServerSecret.generate()
        val b = ServerSecret.generate()
        assertNotEquals(a, b)
        assertEquals(a, a)
    }

    @Test
    fun generatedHexIsSafeForBasicAuthCredentials() {
        // Basic auth is base64(user:pass); ':' or control chars inside the password would
        // still work in base64, but restricting to hex keeps every downstream encoding
        // (URL, JSON, shell) trivially safe. Mutation: raw-byte encoding fails the regex.
        val secrets = (1..50).map { ServerSecret.generate().expose() }
        assertTrue(secrets.all { hex32.matches(it) })
    }
}
