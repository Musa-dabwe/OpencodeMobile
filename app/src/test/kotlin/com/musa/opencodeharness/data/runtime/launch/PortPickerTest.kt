package com.musa.opencodeharness.data.runtime.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class PortPickerTest {

    // ---- contention, against REAL sockets ----

    @Test
    fun chosenPortIsNeverOneThatIsCurrentlyBound() {
        // Simulates a second install (or any other process) holding a port: bind(0) must not
        // hand it out. Mutation that breaks this: returning a hardcoded/low/swept port
        // instead of letting the kernel choose.
        ServerSocket().use { occupied ->
            occupied.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            val held = occupied.localPort

            val chosen = PortPicker().choose()

            assertNotEquals("picker returned a port that is bound right now", held, chosen)
            // And the chosen port must actually be free: bind it ourselves.
            ServerSocket().use { probe ->
                probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), chosen))
                assertEquals(chosen, probe.localPort)
            }
        }
    }

    @Test
    fun chosenPortIsInRange() {
        val chosen = PortPicker().choose()
        assertTrue("port out of range: $chosen", chosen in 1..65535)
    }

    // ---- retry behaviour, against a scripted fake binder ----

    @Test
    fun transientBindFailureIsRetriedUntilSuccess() {
        var calls = 0
        val picker = PortPicker(
            binder = {
                calls++
                if (calls < 3) throw BindException("Address already in use")
                41234
            },
            attempts = 5,
        )
        assertEquals(41234, picker.choose())
        assertEquals("should stop at the first success", 3, calls)
    }

    @Test
    fun persistentBindFailureThrowsAfterExactlyTheConfiguredAttempts() {
        var calls = 0
        val picker = PortPicker(
            binder = {
                calls++
                throw BindException("Address already in use")
            },
            attempts = 3,
        )
        try {
            picker.choose()
            fail("expected PortSelectionException")
        } catch (e: PortSelectionException) {
            assertEquals(3, calls)
            assertTrue("cause should be the last bind failure", e.cause is BindException)
        }
    }

    @Test
    fun outOfRangePortFromBinderFailsImmediately() {
        var calls = 0
        val picker = PortPicker(
            binder = {
                calls++
                0 // a broken binder that "chose" nothing
            },
            attempts = 5,
        )
        try {
            picker.choose()
            fail("expected PortSelectionException for port 0")
        } catch (e: PortSelectionException) {
            assertEquals("must not retry a programmatic error", 1, calls)
        }
    }

    @Test
    fun singleAttemptConfigurationIsHonoured() {
        var calls = 0
        val picker = PortPicker(
            binder = {
                calls++
                throw BindException("nope")
            },
            attempts = 1,
        )
        try {
            picker.choose()
            fail("expected PortSelectionException")
        } catch (e: PortSelectionException) {
            assertEquals(1, calls)
        }
    }
}
