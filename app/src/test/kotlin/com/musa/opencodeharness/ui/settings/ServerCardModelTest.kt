package com.musa.opencodeharness.ui.settings

import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.ui.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings card, as a pure function of real launcher state.
 *
 * Every case here corresponds to a state the app can actually be in, and the brief for this task
 * names them: Running, Dead, Unreachable, Starting. The card is the one surface in the app whose
 * entire job is to not lie, so each test names the mutation that would break it.
 */
class ServerCardModelTest {

    private fun card(
        serverState: ServerState,
        connection: ConnectionState = ConnectionState.Healthy,
        version: String? = "2.0.22",
        serverUrl: String? = "http://127.0.0.1:45997",
    ) = serverCardModel(serverState, connection, version, serverUrl)

    // --- Running ---------------------------------------------------------------------------------

    /**
     * The design's own state, built from the state the launcher reports.
     *
     * Mutation caught: any constant in the sub-line. The port here is 45997 and the version 2.0.22,
     * and both must arrive from the arguments -- a hardcoded `v2.0.22 · 127.0.0.1:4096 · Alpine`
     * fails here because `serverUrl = http://127.0.0.1:4096` is the only way to get 4096.
     */
    @Test
    fun aRunningServerRendersTheDesignsSubLineFromRealState() {
        val model = card(ServerState.Running(pid = 5731, port = 45997))

        assertEquals("OpenCode server running", model.headline)
        assertEquals("running", model.stateWord)
        assertEquals(ServerDot.HEALTHY, model.dot)
        assertEquals("v2.0.22${SUB_LINE_SEPARATOR}127.0.0.1:45997${SUB_LINE_SEPARATOR}Alpine", model.subLine)
        assertNull(model.detail)
    }

    /** The version is what the server REPORTED, so a different server reports a different version. */
    @Test
    fun theVersionComesFromTheProbeAndNotFromAConstant() {
        val model = card(ServerState.Running(pid = 1, port = 45997), version = "2.0.99")

        assertTrue("sub-line was ${model.subLine}", model.subLine!!.startsWith("v2.0.99 "))
    }

    /** A server that sends a leading `v` is not rendered `vv2.0.22`. */
    @Test
    fun aVersionThatAlreadyCarriesItsVIsNotDoublePrefixed() {
        assertTrue(card(ServerState.Running(1, 1), version = "v2.0.22").subLine!!.startsWith("v2.0.22 "))
    }

    /**
     * An unknown version is OMITTED, never filled with a placeholder.
     *
     * Mutation caught: rendering `v?.?.?`, `v0.0.0`, or keeping the previous version across a
     * reload. A missing version segment is the honest answer; anything else is invented data.
     */
    @Test
    fun anUnreportedVersionIsOmittedRatherThanFaked() {
        val model = card(ServerState.Running(1, 45997), version = null)

        assertEquals("127.0.0.1:45997${SUB_LINE_SEPARATOR}Alpine", model.subLine)
        assertTrue("no placeholder may appear: ${model.subLine}", !model.subLine!!.contains("?"))
    }

    /** A blank version string is the same fact as no version, not a different one. */
    @Test
    fun aBlankVersionIsTreatedAsNoVersion() {
        assertEquals(
            "127.0.0.1:45997${SUB_LINE_SEPARATOR}Alpine",
            card(ServerState.Running(1, 45997), version = "   ").subLine,
        )
    }

    /** No endpoint yet (nothing has been built) means no endpoint segment -- again, omitted. */
    @Test
    fun anAbsentEndpointIsOmittedRatherThanFaked() {
        assertEquals("v2.0.22${SUB_LINE_SEPARATOR}Alpine", card(ServerState.Running(1, 1), serverUrl = null).subLine)
    }

    /**
     * A pid of `-1` must change nothing -- that is what every real Android device reports.
     *
     * Mutation caught: `"pid $pid"` appearing in the headline, the sub-line or the detail. The
     * brief for this task says to guard before surfacing a pid; this pins the stronger version of
     * that, which is that none is surfaced at all, so there is nothing left to guard.
     */
    @Test
    fun noPidIsEverSurfacedEvenWhenThePlatformReportsTheAndroidSentinel() {
        val real = card(ServerState.Running(pid = 5731, port = 45997))
        val sentinel = card(ServerState.Running(pid = -1, port = 45997))

        assertEquals(real, sentinel)
        assertTrue(
            "the card must never render a pid: ${sentinel.subLine} / ${sentinel.detail}",
            listOfNotNull(sentinel.subLine, sentinel.detail, sentinel.headline, sentinel.stateWord)
                .none { it.contains("-1") || it.contains("5731") || it.lowercase().contains("pid") },
        )
    }

    // --- Dead ------------------------------------------------------------------------------------

    /**
     * A stopped server must never leave the card reading "running", whatever the client last said.
     *
     * `ConnectionState.Healthy` is passed deliberately: the HTTP client caches the last good answer,
     * so a dead process with a stale Healthy is exactly the combination that would ship a lie.
     * Mutation caught: folding Dead into the Running branch, or dropping the Dead branch entirely.
     */
    @Test
    fun aDeadServerReadsAsStoppedEvenWhileTheConnectionSaysHealthy() {
        val model = card(
            ServerState.Dead(exitCode = 1, signal = null),
            connection = ConnectionState.Healthy,
        )

        assertEquals("OpenCode server stopped", model.headline)
        assertEquals("stopped", model.stateWord)
        assertEquals(ServerDot.ERROR, model.dot)
        assertEquals("exit code 1", model.detail)
    }

    /** The design's state word, `running`, must be absent from a stopped card. */
    @Test
    fun aDeadServerNeverShowsTheDesignsRunningHeadline() {
        val model = card(ServerState.Dead(exitCode = 1, signal = null))

        assertTrue(model.headline != "OpenCode server running")
        assertTrue(model.headline.startsWith(SERVER_CARD_HEADLINE))
    }

    /**
     * A signal death names the signal; a bare exit code names only the code.
     *
     * Mutation caught: printing `signal = null`, or inventing a signal for a code that never had one.
     */
    @Test
    fun theDeathSentenceNamesASignalOnlyWhenThereWasOne() {
        assertEquals(
            "exit code 143 (killed by signal 15)",
            card(ServerState.Dead(exitCode = 143, signal = 15)).detail,
        )
        assertEquals("exit code 0", card(ServerState.Dead(exitCode = 0, signal = null)).detail)
    }

    /** A launch that never produced a process says so, and says why. */
    @Test
    fun aLaunchFailureIsReportedAsSuchAndCarriesTheLaunchersOwnReason() {
        val model = card(ServerState.Dead(exitCode = null, signal = null, launchError = "missing proot-loader"))

        assertEquals("launch failed: missing proot-loader", model.detail)
    }

    /** No exit code and no launch error is a real case, and it must not read as `exit code null`. */
    @Test
    fun aDeathWithNothingReportedSaysSoRatherThanPrintingNull() {
        val detail = card(ServerState.Dead(exitCode = null, signal = null)).detail

        assertEquals("unknown cause (no exit code was reported)", detail)
        assertTrue("must not print null: $detail", !detail!!.contains("null"))
    }

    /**
     * A dead server has no port to advertise.
     *
     * Mutation caught: keeping the sub-line on the Dead branch, which would point the user at an
     * endpoint nothing is listening on.
     */
    @Test
    fun aDeadServerDoesNotAdvertiseAnEndpoint() {
        assertNull(card(ServerState.Dead(exitCode = 1, signal = null)).subLine)
    }

    // --- Unreachable -----------------------------------------------------------------------------

    /**
     * Process alive, HTTP silent. That is NOT the same as dead, so it gets its own word.
     *
     * Mutation caught: folding Unreachable into the Dead branch, which would tell the user their
     * server exited when it is running and merely not answering.
     */
    @Test
    fun aRunningProcessThatDoesNotAnswerReadsAsNotResponding() {
        val model = card(
            ServerState.Running(pid = 5731, port = 45997),
            connection = ConnectionState.Unreachable("Could not reach the server (ConnectException)."),
        )

        assertEquals("OpenCode server not responding", model.headline)
        assertEquals("not responding", model.stateWord)
        assertEquals(ServerDot.ERROR, model.dot)
        assertEquals("Could not reach the server (ConnectException).", model.detail)
        // It IS running, so the endpoint is still the truth and stays on screen.
        assertTrue(model.subLine!!.contains("127.0.0.1:45997"))
    }

    // --- Starting --------------------------------------------------------------------------------

    /**
     * "We do not know yet" must not wear a status colour.
     *
     * Mutation caught: mapping STARTING onto `status.idle` (green) or `status.error` (red). Task 3's
     * ruling is general: never paint a cue that asserts more than the word beside it, and "starting"
     * asserts neither health nor failure. [SettingsStyleTest] proves the colour it resolves to is the
     * neutral one.
     */
    @Test
    fun startingIsItsOwnStateAndNotEitherHealthyOrError() {
        val fromIdle = card(ServerState.Idle, connection = ConnectionState.Connecting)
        val fromConnecting = card(
            ServerState.Running(pid = 5731, port = 45997),
            connection = ConnectionState.Connecting,
        )

        assertEquals("OpenCode server starting", fromIdle.headline)
        assertEquals("OpenCode server starting", fromConnecting.headline)
        assertEquals(ServerDot.STARTING, fromIdle.dot)
        assertEquals(ServerDot.STARTING, fromConnecting.dot)
        assertTrue(fromIdle.dot != ServerDot.HEALTHY && fromIdle.dot != ServerDot.ERROR)
        assertNull("an in-flight server has no failure to explain", fromIdle.detail)
        // Nothing has been spawned, so there is no version, no endpoint and no OS to advertise:
        // a sub-line reading `Alpine` under "starting" would imply an installed runtime the app has
        // not confirmed.
        assertNull("a server that does not exist yet must not advertise one", fromIdle.subLine)
    }

    // --- endpointLabel ---------------------------------------------------------------------------

    /** The design's `127.0.0.1:<port>`, from the client's own base URL. */
    @Test
    fun theEndpointIsHostAndPortWithNoScheme() {
        assertEquals("127.0.0.1:45997", endpointLabel("http://127.0.0.1:45997"))
        assertEquals("127.0.0.1:4096", endpointLabel("http://127.0.0.1:4096/"))
        assertEquals("127.0.0.1:4096", endpointLabel("  http://127.0.0.1:4096  "))
    }

    /**
     * Anything that is not a bare loopback authority is refused rather than rendered.
     *
     * The launcher only ever binds 127.0.0.1, so these branches are unreachable in production --
     * which is the point: if a future endpoint stopped being loopback, the card would show no endpoint
     * at all rather than advertising someone else's host as the user's own server.
     */
    @Test
    fun anythingThatIsNotALoopbackAuthorityIsRefused() {
        assertNull(endpointLabel("http://example.com:4096"))
        assertNull(endpointLabel("http://192.168.1.10:4096"))
        assertNull(endpointLabel("https://127.0.0.1"))
        assertNull(endpointLabel("http://127.0.0.1"))
        assertNull(endpointLabel("http://127.0.0.1:port"))
        assertNull(endpointLabel("http://127.0.0.1:4096/api"))
        assertNull(endpointLabel(""))
        assertNull(endpointLabel(null))
    }

    /** Nothing that could carry a credential may reach the endpoint label. */
    @Test
    fun theEndpointLabelNeverCarriesUserInfo() {
        val label = endpointLabel("http://opencode:a1b2c3d4e5f60718@127.0.0.1:45997")

        assertEquals("the host must be the whole authority's host", null, label)
    }
}
