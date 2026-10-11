package com.musa.opencodeharness.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.ui.ConnectionState
import com.musa.opencodeharness.ui.SessionListUiState
import com.musa.opencodeharness.ui.nav.BASELINE_QUALIFIERS
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Settings screen: does it tell the truth about the server, and is every control honest?
 *
 * Runs on Robolectric at the design's 393dp baseline, so what these assertions measure is the layout
 * the design describes.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = BASELINE_QUALIFIERS)
class SettingsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var restarts = 0

    private fun show(state: SessionListUiState, wired: Boolean = true) {
        rule.setContent {
            OpenCodeMobileTheme {
                SettingsScreen(
                    state = state,
                    onRestart = if (wired) {
                        { restarts++ }
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun running() = SessionListUiState(
        connection = ConnectionState.Healthy,
        serverState = ServerState.Running(pid = 5731, port = 45997),
        serverVersion = "2.0.22",
        serverUrl = "http://127.0.0.1:45997",
    )

    // --- the card reflects real launcher state -------------------------------------------------

    /**
     * DELIVERABLE: the card shows the design's `OpenCode server running`, and the version and port
     * are the ones the state carries.
     *
     * Mutation caught: any constant in the card, or a card wired to `connection` only (so a server
     * that died while the client still said Healthy would still read "running").
     */
    @Test
    fun aRunningServerIsRenderedWithTheValuesTheStateCarries() {
        show(running())

        rule.onNodeWithTag(SETTINGS_SERVER_HEADLINE_TAG).assertExists()
        rule.onNodeWithText("OpenCode server running").assertExists()
        rule.onNodeWithTag(SETTINGS_SERVER_WORD_TAG).assertExists()
        rule.onNodeWithTag(SETTINGS_SERVER_SUBLINE_TAG).assertExists()
        rule.onNodeWithText("v2.0.22 · 127.0.0.1:45997 · Alpine").assertExists()
        rule.onNodeWithTag(SETTINGS_SERVER_DETAIL_TAG).assertDoesNotExist()
    }

    /**
     * DELIVERABLE: the `Dead` state. The Phase 2 ledger records that this path was never forcible
     * from adb, so this is a UNIT assertion; see the task report for what that does and does not
     * establish.
     *
     * Mutation caught: folding `ServerState.Dead` into the Healthy branch -- which would leave a
     * dead process reading "running", the single worst thing this card could do.
     */
    @Test
    fun aDeadServerIsRenderedAsStoppedWithItsReason() {
        show(
            running().copy(
                // Deliberately still Healthy: the client caches the last good answer, so this is the
                // combination that would ship the lie if the card read `connection` alone.
                serverState = ServerState.Dead(exitCode = 143, signal = 15),
            ),
        )

        rule.onNodeWithText("OpenCode server running").assertDoesNotExist()
        rule.onNodeWithText("OpenCode server stopped").assertExists()
        rule.onNodeWithText("stopped").assertExists()
        rule.onNodeWithText("exit code 143 (killed by signal 15)").assertExists()
    }

    /** A launch failure names the launcher's own reason, on the card, where the user can read it. */
    @Test
    fun aLaunchFailureIsRenderedOnTheCard() {
        show(
            running().copy(serverState = ServerState.Dead(null, null, launchError = "runtime setup has not run")),
        )

        rule.onNodeWithText("launch failed: runtime setup has not run").assertExists()
    }

    /** Running but silent is its own state, not "stopped". */
    @Test
    fun aRunningServerThatDoesNotAnswerIsRenderedAsNotResponding() {
        show(
            running().copy(
                connection = ConnectionState.Unreachable("Could not reach the server (ConnectException)."),
            ),
        )

        rule.onNodeWithText("OpenCode server not responding").assertExists()
        rule.onNodeWithText("Could not reach the server (ConnectException).").assertExists()
        // Still running, so the endpoint is still true and stays visible.
        rule.onNodeWithText("v2.0.22 · 127.0.0.1:45997 · Alpine").assertExists()
    }

    /** Nothing has been spawned yet: `starting`, and no endpoint invented for it. */
    @Test
    fun aServerThatHasNotStartedYetIsRenderedAsStarting() {
        show(
            SessionListUiState(
                connection = ConnectionState.Connecting,
                serverState = ServerState.Idle,
                serverVersion = null,
                serverUrl = null,
            ),
        )

        rule.onNodeWithText("OpenCode server starting").assertExists()
        rule.onNodeWithTag(SETTINGS_SERVER_SUBLINE_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SETTINGS_SERVER_DETAIL_TAG).assertDoesNotExist()
    }

    /**
     * An unknown version omits its segment rather than printing a placeholder.
     *
     * Mutation caught: `v?.?.?` or a retained version from a previous server.
     */
    @Test
    fun anUnreportedVersionRendersNoVersionSegment() {
        show(running().copy(serverVersion = null))

        rule.onNodeWithText("127.0.0.1:45997 · Alpine").assertExists()
        val texts = allTexts()
        assertTrue("no placeholder may be rendered: $texts", texts.none { it.contains("v?") || it.contains("null") })
    }

    // --- Restart --------------------------------------------------------------------------------

    /**
     * The screen's one real action, at the spec's 48dp floor, and it actually fires.
     *
     * Mutation caught: `onRestart` dropped at the call site, or the capsule sized to its text.
     */
    @Test
    fun restartIsAtLeast48dpTallAndFiresTheCallback() {
        show(running())

        rule.onNodeWithTag(SETTINGS_RESTART_TAG)
            .performScrollTo()
            .assertHeightIsAtLeast(MinTouchTarget)
        rule.onNodeWithTag(SETTINGS_RESTART_TAG).performClick()
        assertEquals(1, restarts)
    }

    /**
     * An unwired Restart is DISABLED -- no click at all, not a click that goes nowhere.
     *
     * Mutation caught: a defaulted `{}` callback, which is exactly what let Task 3 ship a solid
     * accent FAB that did nothing.
     */
    @Test
    fun anUnwiredRestartIsDisabledAndTakesNoClick() {
        show(running(), wired = false)

        rule.onNodeWithTag(SETTINGS_RESTART_TAG)
            .performScrollTo()
            .assertIsNotEnabled()
            .assertHasNoClickAction()
        rule.onNodeWithTag(SETTINGS_RESTART_TAG).performClick()
        assertEquals("an inert Restart must not fire anything", 0, restarts)
    }

    /** A wired Restart is enabled, so the disabled treatment cannot silently become permanent. */
    @Test
    fun aWiredRestartIsEnabled() {
        show(running())

        rule.onNodeWithTag(SETTINGS_RESTART_TAG).performScrollTo()
        rule.onNodeWithTag(SETTINGS_RESTART_TAG).assertHasClickAction()
    }

    // --- rows -----------------------------------------------------------------------------------

    /** The design's three groups, in the design's order, with their own labels. */
    @Test
    fun theDesignsGroupsArePresentWithTheirOwnHeaders() {
        show(running())

        for (title in listOf("MODELS", "SAFETY", "APP")) {
            rule.onNodeWithTag(settingsSectionTag(title)).assertExists()
            rule.onNodeWithText(title).assertExists()
        }
        rule.onNodeWithText("Providers & models").assertExists()
        rule.onNodeWithText("MCP servers").performScrollTo().assertExists()
        rule.onNodeWithText("Server & runtime").performScrollTo().assertExists()
    }

    /**
     * Every row the app cannot populate is DISABLED and takes no click.
     *
     * Mutation caught: `wiredOrDisabled(if (row.enabled) ...)` losing its condition, or a row
     * gaining a `{}` callback so every row looks live.
     */
    @Test
    fun everyUnpopulatedRowIsDisabledAndTakesNoClick() {
        show(running())

        for (label in settingsSections().flatMap { it.rows }.map { it.label }) {
            rule.onNodeWithTag(settingsRowTag(label))
                .performScrollTo()
                .assertIsNotEnabled()
                .assertHasNoClickAction()
        }
    }

    /** Every row clears the 48dp floor (spec section 7.5). */
    @Test
    fun everyRowClearsThe48dpTouchTargetFloor() {
        show(running())

        for (label in settingsSections().flatMap { it.rows }.map { it.label }) {
            rule.onNodeWithTag(settingsRowTag(label))
                .performScrollTo()
                .assertHeightIsAtLeast(MinTouchTarget)
        }
    }

    /**
     * The value column renders NOTHING this app cannot back.
     *
     * The design's own values are named explicitly: `sonnet-4.5`, `depth 1`, `ask for bash`,
     * `3 connected`, `9 available`, `on`, `share: manual`, `212 MB`. None of them can be produced by
     * this build, so a screen that shows any of them is asserting something false. The single value
     * that IS real is Appearance's `dark`, which is checked here too so the assertion cannot be
     * satisfied by simply blanking everything.
     *
     * Mutation caught: filling a row in with the design's placeholder values.
     */
    @Test
    fun noRowRendersAValueThisBuildCannotBack() {
        show(running())

        val texts = allTexts()
        val forbidden = listOf(
            "sonnet-4.5", "depth 1", "ask for bash", "3 connected", "9 available",
            "share: manual", "212 MB",
        )
        for (value in forbidden) {
            assertTrue("the design's placeholder value '$value' must not be rendered: $texts", texts.none { it.contains(value) })
        }
        // And the one honest value IS rendered, so this is not passing by showing nothing at all.
        rule.onNodeWithText(APPEARANCE_VALUE).assertExists()
    }

    /** A disabled row offers no chevron: a chevron is the affordance for "this goes somewhere". */
    @Test
    fun aDisabledRowDrawsNoChevron() {
        show(running())

        // The chevron is drawn only for `row.enabled`, and no row is enabled. Rather than assert on a
        // negative (which would also pass with a missing glyph), this pins the positive half: the
        // enabled path is what draws it, and the model has no enabled row to draw one for.
        assertTrue(
            "a row with nothing behind it must not be marked enabled",
            settingsSections().flatMap { it.rows }.none { it.enabled },
        )
    }

    // --- harness --------------------------------------------------------------------------------

    /** Every piece of text currently on screen, as a set. */
    private fun allTexts(): Set<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .map { it.text }
            .toSet()
}

/**
 * The floor from spec section 7.5, asserted against the RENDERED height rather than against
 * a constant in the composable: a constant would only prove the constant.
 */
private val MinTouchTarget: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(48f)
