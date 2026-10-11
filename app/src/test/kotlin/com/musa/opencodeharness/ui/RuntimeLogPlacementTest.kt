package com.musa.opencodeharness.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.data.runtime.launch.LogBuffer
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.runtime.setup.SetupStep
import com.musa.opencodeharness.ui.nav.BASELINE_QUALIFIERS
import com.musa.opencodeharness.ui.nav.coloursIn
import com.musa.opencodeharness.ui.nav.snapshot
import com.musa.opencodeharness.ui.nav.toArgbInt
import com.musa.opencodeharness.ui.settings.SETTINGS_LOG_TAG
import com.musa.opencodeharness.ui.settings.SETTINGS_LOG_TRUNCATED_TAG
import com.musa.opencodeharness.ui.sessions.SESSIONS_TAG
import com.musa.opencodeharness.ui.settings.DIAGNOSTICS_SECTION
import com.musa.opencodeharness.ui.settings.SettingsScreen
import com.musa.opencodeharness.ui.settings.VISIBLE_LOG_LINES
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import com.musa.opencodeharness.ui.theme.ThemeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Task 4's second deliverable: the Phase 2 runtime log is OFF the Sessions screen and ON the
 * Settings/Diagnostics surface.
 *
 * ## Why both halves are asserted here, in one file
 *
 * The brief for this task is explicit that the "not on Sessions" half is the one that regressed
 * before -- it is the easy half to lose, because a test that only checks the log is REACHABLE passes
 * just as happily when it is rendered in both places. So this file asserts absence on one screen and
 * presence on the other, and does not let either assertion stand alone.
 *
 * ## Why the Sessions half uses REAL log content
 *
 * Asserting that some arbitrary text node is absent proves very little; asserting it against a
 * distinctive line the log would actually contain -- served through a real `LogBuffer`, so the
 * redaction path runs too -- makes a resurrected `LogTail` fail here.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = BASELINE_QUALIFIERS)
class RuntimeLogPlacementTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val theme = ThemeFixtures.theme

    /** A line distinctive enough that finding it anywhere is unambiguous. */
    private val markerLine = "opencode serve: listening on http://127.0.0.1:45997"

    private val password = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    /**
     * Log lines as the app really produces them: through `LogBuffer`, with a registered secret, so
     * redaction has actually run before anything is rendered.
     */
    private fun realLogLines(): List<String> {
        val buffer = LogBuffer()
        buffer.reset(password)
        buffer.append("opencode-cli: already installed, skipping")
        buffer.append(markerLine)
        buffer.append("OPENCODE_SERVER_PASSWORD=$password")
        return buffer.snapshot()
    }

    private fun healthyState() = SessionListUiState(
        connection = ConnectionState.Healthy,
        roots = listOf(com.musa.opencodeharness.data.SessionNode(SessionInfo(id = "s1", title = "A session"), emptyList())),
        setup = SetupStep.Ready,
        logLines = realLogLines(),
        serverUrl = "http://127.0.0.1:45997",
        serverState = com.musa.opencodeharness.data.runtime.launch.ServerState.Running(pid = 5731, port = 45997),
        serverVersion = "2.0.22",
    )

    private fun showSessions(state: SessionListUiState, wired: Boolean = true) {
        rule.setContent {
            OpenCodeMobileTheme {
                SessionListScreen(
                    state = state,
                    onRetry = {},
                    onOpenDiagnostics = if (wired) ({}) else null,
                )
            }
        }
    }

    private fun showSettings(state: SessionListUiState) {
        rule.setContent {
            OpenCodeMobileTheme { SettingsScreen(state = state, onRestart = {}) }
        }
    }

    // --- the log is NOT on the Sessions screen ---------------------------------------------------

    /**
     * THE assertion this whole file exists for.
     *
     * A healthy server, a real `LogBuffer` tail including a line that only the log would contain --
     * and none of it on the Sessions screen. This is what a resurrected `LogTail` fails.
     */
    @Test
    fun theRuntimeLogIsNotOnTheSessionsScreen() {
        showSessions(healthyState())

        rule.onNodeWithTag(SESSIONS_TAG).assertExists()
        rule.onNodeWithText(markerLine).assertDoesNotExist()
        assertFalse(
            "a log line reached the Sessions screen: ${allTexts()}",
            allTexts().any { it == markerLine || it == "opencode-cli: already installed, skipping" },
        )
    }

    /**
     * The same, with a session list that is EMPTY -- the state the old band was most wasteful in.
     *
     * Guards against an implementation that only hides the log when there is something to list.
     */
    @Test
    fun theRuntimeLogIsAbsentFromAnEmptySessionsScreenToo() {
        showSessions(healthyState().copy(roots = emptyList()))

        rule.onNodeWithText(markerLine).assertDoesNotExist()
        assertFalse("a log line reached the Sessions screen: ${allTexts()}", allTexts().contains(markerLine))
    }

    /** No log-specific chrome survives either: the old band's `log · N earlier lines` header is gone. */
    @Test
    fun theOldLogBandHeaderIsGoneFromTheSessionsScreen() {
        showSessions(healthyState())

        assertFalse(
            "the log band's truncation notice survived on Sessions: ${allTexts()}",
            allTexts().any { it.startsWith("log ·") },
        )
    }

    /** The log is a diagnostics artefact, so it must never carry a session's own title. */
    @Test
    fun theSessionsScreenShowsNoDiagnosticsCard() {
        showSessions(healthyState())

        rule.onNodeWithTag(SETTINGS_LOG_TAG).assertDoesNotExist()
    }

    // --- the log IS on the Settings screen ---------------------------------------------------------

    /**
     * The relocation's other half. A `LogBuffer` tail renders on Settings, inside the card spec
     * section 3.5 puts Diagnostics.
     */
    @Test
    fun theRuntimeLogIsReachableFromTheSettingsSurface() {
        showSettings(healthyState())

        rule.onNodeWithTag(SETTINGS_LOG_TAG).assertExists()
        rule.onNodeWithText(markerLine).performScrollTo().assertExists()
        rule.onNodeWithText(DIAGNOSTICS_SECTION).assertExists()
    }

    /**
     * The log is passed through VERBATIM.
     *
     * Mutation caught: the panel filtering, truncating each line, re-ordering it, or substituting a
     * placeholder. `RuntimeWiringTest.logLinesArePassedThroughUnfiltered` already covers the
     * ViewModel half; this covers the rendering half.
     */
    @Test
    fun everyRelocatedLogLineIsRenderedVerbatim() {
        val lines = realLogLines()
        showSettings(healthyState().copy(logLines = lines))

        rule.onNodeWithTag(SETTINGS_LOG_TAG).assertExists()
        for (line in lines) {
            rule.onNodeWithText(line).performScrollTo().assertExists()
        }
    }

    /**
     * REDACTION SURVIVES THE MOVE.
     *
     * The line was appended to a real `LogBuffer` with the password registered, so what the UI is
     * handed already has `***redacted***` in it. This proves the panel neither reverses that nor has a
     * path of its own that could emit the secret -- and it fails if the panel ever starts
     * reformatting lines, since the rendered text has to match the stored text exactly.
     */
    @Test
    fun theRelocatedLogNeverShowsThePassword() {
        val lines = realLogLines()
        assertTrue("the buffer must already be redacted: $lines", lines.none { it.contains(password) })

        showSettings(healthyState().copy(logLines = lines))

        rule.onNodeWithText("OPENCODE_SERVER_PASSWORD=${LogBuffer.REDACTED}").performScrollTo().assertExists()
        assertFalse(
            "the password reached the screen: ${allTexts()}",
            allTexts().any { it.contains(password) },
        )
    }

    /** An empty buffer says so, rather than rendering a blank card that reads as a broken one. */
    @Test
    fun anEmptyLogSaysSoInsteadOfRenderingNothing() {
        showSettings(healthyState().copy(logLines = emptyList()))

        rule.onNodeWithTag(SETTINGS_LOG_TAG).assertExists()
        rule.onNodeWithText("No output yet.").performScrollTo().assertExists()
    }

    /**
     * Truncation is ANNOUNCED, and the count is the real one.
     *
     * ## The expectations are LITERALS, and that is load-bearing
     *
     * The first version of this test built its expected text with `truncatedLabel(8)` -- the very
     * helper production renders through. A mutation that hardcoded that helper to always say "0
     * earlier lines not shown" therefore changed the expectation along with the screen and SURVIVED.
     * It is the `fabColours.contains(style.fabFill)` trap from Task 3, in a new place: an assertion
     * computed by the code under test cannot fail. Every string below is spelled out.
     *
     * The fixture is deliberately longer than the panel shows, so the truncation branch is genuinely
     * reached rather than skipped by a list that fits.
     */
    @Test
    fun aTruncatedLogSaysHowManyLinesAreNotShown() {
        val long = (1..VISIBLE_LOG_LINES + 8).map { "line $it" }
        assertEquals("the fixture must exceed the panel's bound", VISIBLE_LOG_LINES + 8, long.size)
        showSettings(healthyState().copy(logLines = long))

        rule.onNodeWithTag(SETTINGS_LOG_TRUNCATED_TAG).performScrollTo().assertExists()
        rule.onNodeWithText("log · 8 earlier lines not shown").performScrollTo().assertExists()
        // The newest line is on screen; the oldest is not, and the panel says how many are missing.
        rule.onNodeWithText("line 28").performScrollTo().assertExists()
        rule.onNodeWithText("line 1").assertDoesNotExist()
        rule.onNodeWithText("28 lines buffered").performScrollTo().assertExists()
    }

    /** A log that fits shows no truncation notice at all -- an empty "0 earlier lines" would be noise. */
    @Test
    fun aLogThatFitsShowsNoTruncationNotice() {
        showSettings(healthyState().copy(logLines = listOf("only line")))

        rule.onNodeWithTag(SETTINGS_LOG_TAG).assertExists()
        rule.onNodeWithTag(SETTINGS_LOG_TRUNCATED_TAG).assertDoesNotExist()
    }

    // --- the route back from Sessions ---------------------------------------------------------------

    /**
     * A user sitting on a FAILING server is told where the log went, in one line.
     *
     * This is the half of the relocation that is easy to get wrong: moving the log off the landing
     * screen without leaving a route to it makes a failure undiagnosable for exactly the user who
     * needs it. Mutation caught: dropping the hint, or rendering the multi-line tail back.
     */
    @Test
    fun anUnreachableServerPointsAtTheLogInSettings() {
        var opened = 0
        rule.setContent {
            OpenCodeMobileTheme {
                SessionListScreen(
                    state = healthyState().copy(
                        connection = ConnectionState.Unreachable("Could not reach the server."),
                    ),
                    onRetry = {},
                    onOpenDiagnostics = { opened++ },
                )
            }
        }

        rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).assertIsDisplayed()
        rule.onNodeWithText(DIAGNOSTICS_HINT).assertExists()
        // ONE line, not the log: the tail must not have come back with it.
        rule.onNodeWithText(markerLine).assertDoesNotExist()

        rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).performClick()
        assertEquals(1, opened)
    }

    /**
     * A HEALTHY server gets no hint at all -- this screen used to give up vertical space forever for
     * a band whose job was done.
     *
     * Mutation caught: rendering the hint unconditionally.
     */
    @Test
    fun aHealthyServerShowsNoDiagnosticsHint() {
        showSessions(healthyState())

        rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).assertDoesNotExist()
    }

    /** First-run setup is exactly when the log is worth reading, so the route appears there too. */
    @Test
    fun theHintAlsoAppearsWhileTheRuntimeIsBeingSetUp() {
        showSessions(healthyState().copy(setup = SetupStep.Downloading("opencode-cli", 1, 4)))

        rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).assertExists()
    }

    /**
     * The hint is ONE control, not a control inside a control.
     *
     * Mutation caught: putting a second `clickable` on the inner `Text` alongside `wiredOrDisabled`
     * on the Box, which is what this file's implementation originally did. One gesture then had two
     * handlers, and -- the part that actually matters -- the Box's default M3 indication ripples from
     * `colorScheme.primary`, which Task 1 sets to `accent.base`. That would put an accent ripple on
     * a control that is not this screen's primary action; the FAB owns accent.
     *
     * ## Why this is a COUNT and not a pixel assertion
     *
     * A probe test pressed and held the hint and compared the rendered pixels before and after:
     * `added=[]`. Robolectric does not draw the click indication at all, so an "no accent ripple"
     * pixel test would pass identically with `wiredOrDisabled` in place or removed -- the same
     * shadow-test trap Task 2 hit. The property is therefore asserted structurally, where it is
     * observable: how many things on this screen can be clicked, and how many handlers each fires.
     */
    @Test
    fun theHintIsASingleClickableThatFiresExactlyOnce() {
        var opened = 0
        rule.setContent {
            OpenCodeMobileTheme {
                SessionListScreen(
                    state = healthyState().copy(
                        connection = ConnectionState.Unreachable("Could not reach the server."),
                    ),
                    onRetry = {},
                    onOpenDiagnostics = { opened++ },
                )
            }
        }

        // Scoped to the hint's OWN subtree, not the whole screen: the Unreachable pane already has a
        // legitimate `Retry` click, and a screen-wide count fails for the wrong reason (it did, on
        // this test's first run).
        //
        // The clickable node IS the tagged node -- `clickable` does not wrap the semantics tree, and
        // `mergeDescendants` is not on, so the Box and its child Text remain two nodes. The double
        // clickable therefore shows up as an EXTRA clickable DESCENDANT, which is what is counted.
        val nested = rule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and
                    hasAnyAncestor(hasTestTag(SESSIONS_DIAGNOSTICS_TAG)),
            )
            .fetchSemanticsNodes()

        assertEquals(
            "the hint must be ONE control, not a control wrapping another: " +
                "${nested.size} nested clickable(s)",
            0,
            nested.size,
        )
        rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).assert(hasClickAction())

        rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).performClick()
        assertEquals("one tap must fire the callback exactly once, not once per handler", 1, opened)
    }

    /**
     * The hint's own pixels carry no accent, even in the state where it is shown.
     *
     * A resting-state assertion, so it is honest about what it proves: it catches the hint being
     * PAINTED accent. It does NOT catch a pressed-state ripple, which Robolectric does not render
     * (see the probe note on the test above); that half is covered structurally there.
     */
    @Test
    fun theHintIsPaintedInNoAccent() {
        showSessions(healthyState().copy(connection = ConnectionState.Unreachable("Could not reach.")))

        val node = rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).fetchSemanticsNode().boundsInRoot
        val colours = rule.snapshot().coloursIn(
            node.left.roundToInt(),
            node.top.roundToInt(),
            node.right.roundToInt(),
            node.bottom.roundToInt(),
        )

        assertFalse(
            "the hint must not be painted accent ${hexOf(theme.color.accent.base)}: saw $colours",
            colours.contains(theme.color.accent.base.toArgbInt()),
        )
        assertTrue(
            "the hint must be painted in text.secondary so it is readable: saw $colours",
            colours.contains(theme.color.text.secondary.toArgbInt()),
        )
    }

    /** Unwired means no line at all: a muted sentence that promises a tap and takes none. */
    @Test
    fun anUnwiredHintIsNotRenderedAtAll() {
        showSessions(
            healthyState().copy(connection = ConnectionState.Unreachable("Could not reach the server.")),
            wired = false,
        )

        rule.onNodeWithTag(SESSIONS_DIAGNOSTICS_TAG).assertDoesNotExist()
        rule.onNodeWithText(DIAGNOSTICS_HINT).assertDoesNotExist()
    }

    // --- harness ------------------------------------------------------------------------------------

    /** Reads a Compose colour back as the hex the theme JSON holds, for failure messages. */
    private fun hexOf(color: androidx.compose.ui.graphics.Color): String = String.format(
        "#%06X",
        0xFFFFFF and ((color.red * 255f + 0.5f).toInt() shl 16) or
            ((color.green * 255f + 0.5f).toInt() shl 8) or (color.blue * 255f + 0.5f).toInt(),
    )

    /** Every piece of text currently on screen, as a set. */
    private fun allTexts(): Set<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .map { it.text }
            .toSet()
}
