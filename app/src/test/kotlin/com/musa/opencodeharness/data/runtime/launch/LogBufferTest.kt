package com.musa.opencodeharness.data.runtime.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    // ---- capping / eviction (the "cannot grow without bound" guarantee) ----

    @Test
    fun oldestLinesAreEvictedWhenCapIsExceeded() {
        // Mutation that must break this: dropping the `while (lines.size > maxLines)` eviction.
        val buffer = LogBuffer(maxLines = 5, maxLineChars = 100)
        repeat(20) { buffer.append("line-$it") }
        val snapshot = buffer.snapshot()
        assertEquals(5, snapshot.size)
        assertEquals("line-15", snapshot.first())
        assertEquals("line-19", snapshot.last())
    }

    @Test
    fun singleLineIsTruncatedToTheCharacterCap() {
        // Mutation: removing the substring truncation lets one megabyte line through.
        val buffer = LogBuffer(maxLines = 10, maxLineChars = 16)
        buffer.append("x".repeat(10_000))
        val snapshot = buffer.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(16, snapshot[0].length)
    }

    @Test
    fun capConfigIsEnforced() {
        try {
            LogBuffer(maxLines = 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            LogBuffer(maxLineChars = -1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---- redaction (the "password can never enter the buffer" guarantee) ----

    @Test
    fun secretNeverEntersTheBufferWhereverItAppears() {
        // Mutation that must break this: removing the redact() call in append().
        val buffer = LogBuffer()
        val secret = "a".repeat(32)
        buffer.reset(secret)
        buffer.append("before $secret after")
        buffer.append(secret)
        buffer.append("$secret$secret")
        val rendered = buffer.render()
        assertFalse("secret leaked into the log", rendered.contains(secret))
        assertTrue(rendered.contains(LogBuffer.REDACTED))
        assertEquals(3, buffer.snapshot().size)
    }

    @Test
    fun redactionTargetsAreReplacedNotJustAppendedTo() {
        val buffer = LogBuffer()
        val oldSecret = "old-secret-value"
        buffer.reset(oldSecret)
        buffer.append("token=$oldSecret")
        assertEquals("token=${LogBuffer.REDACTED}", buffer.snapshot().single())
    }

    @Test
    fun resetClearsPreviousLinesAndPreviousTargets() {
        val buffer = LogBuffer()
        buffer.reset("secret-one")
        buffer.append("old line with secret-one")
        buffer.reset("secret-two")
        assertTrue("reset must clear the old tail", buffer.snapshot().isEmpty())
        // Old target is no longer redacted after reset (its server is dead), new one is.
        buffer.append("secret-one secret-two")
        val line = buffer.snapshot().single()
        assertFalse(line.contains("secret-two"))
        assertTrue(line.contains("secret-one"))
    }

    @Test
    fun emptySecretsAreIgnoredSoResetNeverDisablesRedactionAccidentally() {
        val buffer = LogBuffer()
        buffer.reset("")
        buffer.append("nothing to hide")
        assertEquals("nothing to hide", buffer.snapshot().single())
    }

    // ---- plain behaviour and the revision signal for the UI ----

    @Test
    fun appendWithoutSecretsStoresLinesVerbatim() {
        val buffer = LogBuffer()
        buffer.reset()
        buffer.append("server listening on http://127.0.0.1:40000")
        assertEquals("server listening on http://127.0.0.1:40000", buffer.snapshot().single())
    }

    @Test
    fun revisionIncrementsOnEveryMutation() {
        val buffer = LogBuffer()
        val start = buffer.revision.value
        buffer.append("one")
        buffer.append("two")
        val afterAppend = buffer.revision.value
        assertEquals(start + 2, afterAppend)
        buffer.reset()
        assertTrue("reset must bump the revision", buffer.revision.value > afterAppend)
    }

    @Test
    fun snapshotIsACopyNotALiveView() {
        val buffer = LogBuffer(maxLines = 10)
        buffer.append("first")
        val snapshot = buffer.snapshot()
        buffer.append("second")
        assertEquals(1, snapshot.size)
        assertEquals(2, buffer.snapshot().size)
    }
}
