package com.musa.opencodeharness.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.ui.ConnectionState
import com.musa.opencodeharness.ui.SessionListUiState
import com.musa.opencodeharness.ui.nav.BASELINE_QUALIFIERS
import com.musa.opencodeharness.ui.nav.coloursIn
import com.musa.opencodeharness.ui.nav.distinctColours
import com.musa.opencodeharness.ui.nav.snapshot
import com.musa.opencodeharness.ui.nav.toArgbInt
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import com.musa.opencodeharness.ui.theme.ThemeFixtures
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * What the Settings screen actually PAINTS, sampled out of the rendered bitmap.
 *
 * ## Every assertion names a TOKEN, never a `style.*` field
 *
 * The same discipline `SessionsPixelTest` set: an assertion comparing the screen against whatever
 * the resolver happened to produce is self-referential and passes whatever the resolver is wrong
 * about. Each expectation below therefore reads the token straight off the theme.
 *
 * ## What this file deliberately does NOT assert
 *
 * **Drop shadows.** Robolectric's Skia renders none, so a shadow assertion here cannot fail -- it
 * would pass identically with `Modifier.shadow(8.dp)` present or absent. Task 2 found that the hard
 * way and deleted its own shadow test for it. No-shadow is enforced structurally
 * (`elevation.useShadows == false`, `noDropShadow()` on every card) in `SettingsStyleTest`, and
 * visually on the device screenshot, where the compositor does draw shadows.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = BASELINE_QUALIFIERS)
class SettingsPixelTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val theme = ThemeFixtures.theme
    private val style = theme.settingsStyle()

    private fun show(state: SessionListUiState, wired: Boolean = true) {
        rule.setContent {
            OpenCodeMobileTheme {
                SettingsScreen(state = state, onRestart = if (wired) ({}) else null)
            }
        }
    }

    private val running = SessionListUiState(
        connection = ConnectionState.Healthy,
        serverState = ServerState.Running(pid = 5731, port = 45997),
        serverVersion = "2.0.22",
        serverUrl = "http://127.0.0.1:45997",
    )

    private fun bitmapOf(tag: String): Bitmap {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val full = rule.snapshot()
        val left = node.left.roundToInt().coerceIn(0, full.width)
        val top = node.top.roundToInt().coerceIn(0, full.height)
        val right = node.right.roundToInt().coerceIn(0, full.width)
        val bottom = node.bottom.roundToInt().coerceIn(0, full.height)
        return Bitmap.createBitmap(full, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }

    private fun Bitmap.argbOf(): Set<Int> = coloursIn(0, 0, width, height)

    /**
     * The page and the cards are the design's own surfaces, and the card is LIFTED off the page --
     * the only depth cue this design has, since `elevation.useShadows` is false.
     *
     * Mutation caught: a card filled with the page colour (so the group disappears), or a screen
     * still painting the old template background.
     */
    @Test
    fun thePageAndTheCardsArePaintedInTheDesignsSurfaces() {
        show(running)
        rule.onNodeWithTag(SETTINGS_SERVER_CARD_TAG).assertExists()

        val whole = rule.snapshot().distinctColours()

        assertTrue(
            "the page must be background.base ${theme.color.background.base.toArgbHex()}",
            whole.contains(theme.color.background.base.toArgbInt()),
        )
        assertTrue(
            "the card must be background.raised ${theme.color.background.raised.toArgbHex()}",
            whole.contains(theme.color.background.raised.toArgbInt()),
        )
        assertTrue(
            "the card must be separated by the border.subtle hairline ${style.hairline.toArgbHex()}",
            whole.contains(style.hairline.toArgbInt()),
        )
    }

    /**
     * A WIRED Restart is painted in accent -- the design's one primary action, when there is one.
     *
     * The pair of `anUnwiredRestartIsNotPaintedInAccent` below: without this, "never paint accent"
     * would pass forever while the screen quietly lost its only action.
     */
    @Test
    fun aWiredRestartIsPaintedInAccent() {
        show(running)
        rule.onNodeWithTag(SETTINGS_RESTART_TAG).performScrollTo()

        val colours = bitmapOf(SETTINGS_RESTART_TAG).argbOf()

        assertTrue(
            "a wired Restart must be painted in accent.base ${theme.color.accent.base.toArgbHex()}, saw " +
                "${colours.size} colours",
            colours.contains(theme.color.accent.base.toArgbInt()),
        )
    }

    /**
     * An UNWIRED Restart is painted as a disabled control: no accent anywhere inside it.
     *
     * Mutation caught: `if (wired) style.restartFill else style.card` losing its condition, which is
     * exactly what shipped a permanently-live accent FAB on the Sessions screen.
     */
    @Test
    fun anUnwiredRestartIsNotPaintedInAccent() {
        show(running, wired = false)
        rule.onNodeWithTag(SETTINGS_RESTART_TAG).performScrollTo()

        val colours = bitmapOf(SETTINGS_RESTART_TAG).argbOf()

        assertTrue(
            "an inert Restart must not wear accent ${theme.color.accent.base.toArgbHex()}: saw $colours",
            !colours.contains(theme.color.accent.base.toArgbInt()),
        )
    }

    /** A healthy server paints `status.idle` -- the design's own dot for this card. */
    @Test
    fun aHealthyServerPaintsTheDesignsStatusIdleDot() {
        show(running)

        val colours = bitmapOf(SETTINGS_SERVER_DOT_TAG).argbOf()

        assertTrue(
            "a healthy server must paint status.idle ${theme.color.status.idle.toArgbHex()}, saw $colours",
            colours.contains(theme.color.status.idle.toArgbInt()),
        )
    }

    /**
     * A STARTING server paints the NEUTRAL token, and `status.idle` is ABSENT from the card.
     *
     * Mutation caught: `dotStarting` pointed at `status.idle` -- the exact review finding from Task 3
     * recurring here. A green dot beside the word "starting" asserts a health the screen has not
     * established. The second assertion is what makes it decisive: "the neutral is present" would
     * still pass if something else painted green too.
     */
    @Test
    fun aStartingServerPaintsTheNeutralDotAndNotStatusIdle() {
        show(running.copy(serverState = ServerState.Idle, connection = ConnectionState.Connecting))

        val colours = bitmapOf(SETTINGS_SERVER_DOT_TAG).argbOf()

        assertTrue(
            "a STARTING server must paint the neutral ${style.dotStarting.toArgbHex()}, saw $colours",
            colours.contains(style.dotStarting.toArgbInt()),
        )
        assertTrue(
            "and must NOT paint status.idle ${theme.color.status.idle.toArgbHex()} beside the word " +
                "'starting' -- the cue would assert a health the app does not have",
            !colours.contains(theme.color.status.idle.toArgbInt()),
        )
    }

    /**
     * A dead server paints `status.error`, and never accent.
     *
     * Scoped to the DOT's own bounds rather than the whole card, and deliberately so: the card also
     * contains the `Restart` capsule, which IS accent by design, so a card-wide "no accent"
     * assertion would be asserting the opposite of what it claims. The dot is the part whose colour
     * is a state claim.
     *
     * Mutation caught: the dot painted from `connection` instead of `serverState`, or painted in the
     * design's warm hue -- accent means "action" here, and a dot means state.
     */
    @Test
    fun aDeadServerPaintsTheErrorDotAndNeverAccent() {
        show(running.copy(serverState = ServerState.Dead(exitCode = 1, signal = null)))

        val dot = bitmapOf(SETTINGS_SERVER_DOT_TAG).argbOf()

        assertTrue(
            "a stopped server must paint status.error ${theme.color.status.error.toArgbHex()}, saw $dot",
            dot.contains(theme.color.status.error.toArgbInt()),
        )
        assertTrue(
            "and must NOT paint accent ${theme.color.accent.base.toArgbHex()}",
            !dot.contains(theme.color.accent.base.toArgbInt()),
        )
        assertTrue(
            "and must NOT paint the healthy green ${theme.color.status.idle.toArgbHex()}",
            !dot.contains(theme.color.status.idle.toArgbInt()),
        )
    }

    /**
     * The server headline carries no accent either.
     *
     * The other half of the accent rule, and the one that would catch a card re-styled to lean on the
     * design's warm hue for emphasis. Scoped to the headline's own bounds so the `Restart` capsule
     * below it cannot satisfy or break the assertion.
     */
    @Test
    fun theServerHeadlinePaintsNoAccent() {
        show(running)

        val headline = bitmapOf(SETTINGS_SERVER_HEADLINE_TAG).argbOf()

        assertTrue(
            "the headline must be text.primary, never accent ${theme.color.accent.base.toArgbHex()}",
            !headline.contains(theme.color.accent.base.toArgbInt()),
        )
    }

    /**
     * A disabled row paints no accent -- accent on this screen belongs to `Restart` alone.
     *
     * Mutation caught: a row rendering its chevron or its value in accent, which would read as an
     * action the row cannot perform.
     */
    @Test
    fun aDisabledRowPaintsNoAccent() {
        show(running)

        val label = settingsSections().flatMap { it.rows }.first { it.label == "MCP servers" }.label
        val colours = bitmapOf(settingsRowTag(label)).argbOf()

        assertTrue(
            "a disabled row must not paint accent ${theme.color.accent.base.toArgbHex()}: saw $colours",
            !colours.contains(theme.color.accent.base.toArgbInt()),
        )
        assertTrue(
            "a disabled row's label must be readable, in ${style.muted.toArgbHex()}",
            colours.contains(style.muted.toArgbInt()),
        )
    }

    /** Reads a Compose colour back as the hex the JSON holds, for the failure messages. */
    private fun Color.toArgbHex(): String = String.format(
        "#%06X",
        0xFFFFFF and ((red * 255f + 0.5f).toInt() shl 16) or
            ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt(),
    )
}
