package com.musa.opencodeharness.ui.sessions

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.data.buildSessionTree
import com.musa.opencodeharness.data.model.Location
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.model.SessionTime
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * What the screen actually PAINTS, sampled out of the rendered bitmap.
 *
 * ## Every assertion here names a TOKEN, never a `style.*` field
 *
 * An earlier version of this file asserted `fabColours.contains(style.fabFill)`. That is
 * self-referential: it compares the screen against whatever the resolver happened to produce, so
 * pointing `fabFill` at the wrong colour still passed. A mutation run caught it (setting
 * `fabFill = background.raised` survived). Every expectation below therefore reads the token
 * DIRECTLY off the theme, so a misrouted field has to fail here as well as in
 * `SessionsStyleTest`.
 *
 * ## What this file deliberately does NOT assert
 *
 * **Drop shadows.** Robolectric's Skia renders none, so a shadow assertion here cannot fail: it
 * would pass identically with `Modifier.shadow(8.dp)` present or absent. Task 2 found that the
 * hard way and deleted its own shadow test for it. No-shadow is enforced STRUCTURALLY
 * (`elevation.useShadows == false`, `noDropShadow()` on the FAB) in `SessionsStyleTest`, and on
 * the DEVICE screenshot, where the compositor does draw shadows. A pixel test for an effect this
 * environment stubs out is a test that proves nothing.
 *
 * Everything below is a flat colour fill, which Robolectric does render faithfully.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w393dp-h720dp-xhdpi")
class SessionsPixelTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val theme = com.musa.opencodeharness.ui.theme.ThemeFixtures.theme
    private val style = theme.sessionsStyle()
    private val now = 1_789_755_904_855L

    private fun session(id: String, title: String, parentID: String? = null) = SessionInfo(
        id = id,
        title = title,
        parentID = parentID,
        projectID = "prj",
        location = Location(directory = "/home/musa/Projects/OpencodeMobile"),
        time = SessionTime(created = now - 5000, updated = now - 120_000),
    )

    private val tree by lazy {
        buildSessionTree(
            listOf(
                session("ses_root", "Add a keystore-backed session switcher"),
                session("ses_child", "Re-review Task 4 fix round 1", parentID = "ses_root"),
            ),
        )
    }

    /**
     * The production shape: `SessionListScreen` wires no action, so every control is inert.
     * [showWired] is the same screen once the runtime can actually do something.
     */
    private fun show() = rule.setContent {
        OpenCodeMobileTheme { SessionsScreen(roots = tree, nowMillis = now) }
    }

    private fun showWired() = rule.setContent {
        OpenCodeMobileTheme {
            SessionsScreen(
                roots = tree,
                nowMillis = now,
                onToggleScope = {},
                onBack = {},
                onNewSession = {},
                onRename = {},
                onDelete = {},
            )
        }
    }

    /** Counts pixels of a given colour strictly inside a rectangle, skipping a 2px edge. */
    private fun Bitmap.countIn(bounds: androidx.compose.ui.geometry.Rect, argb: Int): Pair<Int, Int> {
        var hits = 0
        var total = 0
        for (y in bounds.top.toInt() + 2 until bounds.bottom.toInt() - 2) {
            for (x in bounds.left.toInt() + 2 until bounds.right.toInt() - 2) {
                total++
                if (getPixel(x, y) == argb) hits++
            }
        }
        return hits to total
    }

    /** The whole screen's pixels. Same technique as Task 2's harness: draw the decor view. */
    private fun snapshot(): Bitmap {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    /** Every distinct opaque colour in the bitmap. */
    private fun Bitmap.colours(): Set<Int> = buildSet {
        for (y in 0 until height) for (x in 0 until width) add(getPixel(x, y))
    }

    /** Distinct colours inside a pixel rectangle. */
    private fun Bitmap.coloursIn(left: Int, top: Int, right: Int, bottom: Int): Set<Int> = buildSet {
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) add(getPixel(x, y))
        }
    }

    /**
     * The semantics-bounds-to-bitmap scale.
     *
     * Compose's `boundsInRoot` on Android is already in the root's PIXEL space, and the snapshot
     * is the decor view at those same pixels, so the scale is exactly 1. Stated as a named value
     * rather than deleted so the assumption is visible: a future density change is one edit here,
     * and a reader can see there is no hidden dp arithmetic.
     */
    private val boundsToBitmap: Float = 1f

    /** Packs a token colour the way `Bitmap.getPixel` reports it. */
    private fun Color.argbInt(): Int = android.graphics.Color.argb(
        (alpha * 255f + 0.5f).toInt(),
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt(),
    )

    // --- the palette -----------------------------------------------------------------------------

    /**
     * The page is the design's page and the search field is the design's `raised` surface.
     *
     * Mutation caught: a screen still painting the old template background, or a search field
     * filled with the page colour instead of the lifted surface.
     */
    @Test
    fun thePageAndSearchFieldPaintTheDesignsSurfaces() {
        show()

        val colours = snapshot().colours()

        assertTrue(
            "the page must be painted in background.base ${theme.color.background.base.toArgbHex()}",
            colours.contains(theme.color.background.base.argbInt()),
        )
        assertTrue(
            "the search field must be painted in background.raised ${theme.color.background.raised.toArgbHex()}",
            colours.contains(theme.color.background.raised.argbInt()),
        )
    }

    /**
     * An UNWIRED FAB is painted as a disabled control: no accent anywhere inside it.
     *
     * This is the pixel half of review #6. `SessionListScreen` passes no `onNewSession`, and the
     * shipped screen painted a solid-accent FAB regardless -- permanently on screen, doing nothing.
     * A regression that re-lights the accent without wiring the callback paints accent here.
     *
     * Mutation caught: `if (wired) style.fabFill else style.searchFill` losing its condition.
     */
    @Test
    fun anUnwiredFabIsNotPaintedInAccent() {
        show()

        val fabBounds = rule.onNodeWithTag(SESSIONS_FAB_TAG).fetchSemanticsNode().boundsInRoot
        val (accentPixels, totalPixels) = snapshot().countIn(fabBounds, theme.color.accent.base.argbInt())

        assertTrue(
            "an inert FAB must not wear accent: $accentPixels of $totalPixels interior pixels are " +
                "accent ${theme.color.accent.base.toArgbHex()}",
            totalPixels > 0 && accentPixels == 0,
        )
    }

    /**
     * A WIRED FAB is painted in accent -- the design's one primary action, when there is one.
     *
     * The pair of the test above. Without this, "never paint accent" would pass forever while the
     * screen quietly lost its primary action.
     */
    @Test
    fun aWiredFabIsPaintedInAccent() {
        showWired()

        val fabBounds = rule.onNodeWithTag(SESSIONS_FAB_TAG).fetchSemanticsNode().boundsInRoot
        val (accentPixels, totalPixels) = snapshot().countIn(fabBounds, theme.color.accent.base.argbInt())

        assertTrue(
            "a wired FAB must be painted in accent.base ${theme.color.accent.base.toArgbHex()}: " +
                "$accentPixels of $totalPixels interior pixels",
            totalPixels > 0 && accentPixels > totalPixels / 4,
        )
    }

    /**
     * An UNWIRED scope pill carries no accent either -- not in its outline and not in its text.
     *
     * A pill that cannot filter must not wear the colour that means "action", or it reads as a
     * working control.
     */
    @Test
    fun anUnwiredScopePillIsNotPaintedInAccent() {
        show()

        val pillBounds = rule.onNodeWithTag(SESSIONS_SCOPE_TAG).fetchSemanticsNode().boundsInRoot
        val (accentPixels, _) = snapshot().countIn(pillBounds, theme.color.accent.base.argbInt())

        assertTrue(
            "an inert scope pill must not paint accent: $accentPixels accent pixels",
            accentPixels == 0,
        )
    }

    /**
     * The status dot is painted in the colour its STATUS asks for, and in no other status colour.
     *
     * These rows are UNKNOWN (the server sends no run status), so the honest dot is the neutral
     * `text.secondary`. The shipped bug painted `status.idle` on every row regardless, which put a
     * green "idle" dot immediately left of the word "unknown" -- the cue asserting a state the
     * word disclaims. Both halves are asserted: the neutral dot is present, and the idle green
     * that used to be here is gone.
     *
     * Mutation caught: `.background(style.statusDot, ...)` instead of
     * `style.statusDotFor(row.status)`; `statusDotFor(UNKNOWN)` pointed back at `status.idle`.
     */
    @Test
    fun theStatusDotIsPaintedFromTheRowsStatusNotAFixedColour() {
        show()

        val rowBounds = rule.onNodeWithTag(sessionsRowTag("ses_root")).fetchSemanticsNode().boundsInRoot
        val bitmap = snapshot()
        val scale = boundsToBitmap

        val rowColours = bitmap.coloursIn(
            (rowBounds.left * scale).roundToInt(),
            (rowBounds.top * scale).roundToInt(),
            (rowBounds.right * scale).roundToInt(),
            (rowBounds.bottom * scale).roundToInt(),
        )

        assertTrue(
            "an UNKNOWN row must paint its dot in the neutral ${style.unknownDot.toArgbHex()}",
            rowColours.contains(style.unknownDot.argbInt()),
        )
        assertTrue(
            "and must NOT paint status.idle ${theme.color.status.idle.toArgbHex()} -- the word " +
                "beside the dot says 'unknown', so an idle-green dot contradicts it",
            !rowColours.contains(theme.color.status.idle.argbInt()),
        )
        assertTrue(
            "the row must NOT paint accent -- accent is reserved for the primary action",
            !rowColours.contains(theme.color.accent.base.argbInt()),
        )
    }

    /**
     * The scope pill is an OUTLINE, not a solid accent fill: it must never be painted in the
     * accent fill across its interior, because a filled accent pill would be a second primary
     * action competing with the FAB.
     *
     * Mutation caught: giving the pill `background(style.accent.base)`. The style test could NOT
     * catch this -- it compares style fields, and the pill's fill is applied in the composable --
     * so without this pixel assertion the mutation survives (verified).
     */
    @Test
    fun theScopePillIsNotPaintedAsASolidAccentFill() {
        showWired()

        val pillBounds = rule.onNodeWithTag(SESSIONS_SCOPE_TAG).fetchSemanticsNode().boundsInRoot
        val bitmap = snapshot()
        val scale = boundsToBitmap

        // Count actual accent pixels in the pill's interior, not distinct colours: an outline
        // contributes only a thin edge, a solid fill contributes the whole area.
        val accentArgb = theme.color.accent.base.argbInt()
        var accentPixels = 0
        var totalPixels = 0
        for (y in (pillBounds.top * scale).roundToInt() + 2 until (pillBounds.bottom * scale).roundToInt() - 2) {
            for (x in (pillBounds.left * scale).roundToInt() + 2 until (pillBounds.right * scale).roundToInt() - 2) {
                totalPixels++
                if (bitmap.getPixel(x, y) == accentArgb) accentPixels++
            }
        }
        assertTrue(
            "the scope pill must be an outline, not a solid accent fill: $accentPixels of " +
                "$totalPixels interior pixels are accent",
            totalPixels > 0 && accentPixels < totalPixels / 4,
        )
    }

    // --- swipe actions ----------------------------------------------------------------------------

    /**
     * A revealed swipe action is painted in the design's own `renameBg` / `deleteBg` fills.
     *
     * This is the assertion that the swipe row actually reveals real full-bleed coloured
     * rectangles, not grey placeholders. Mutation caught: dropping the background, or painting it
     * with a colour outside the palette.
     *
     * NOTE: this is a FILL assertion, not a shadow one -- Robolectric renders flat fills faithfully,
     * so it can genuinely fail. Compare with what this file deliberately does not assert.
     */
    @Test
    fun aRevealedSwipeActionPaintsTheDesignsActionFills() {
        showWired()

        rule.onNodeWithTag(sessionsRowTag("ses_root")).performTouchInput { swipeLeft() }
        rule.waitForIdle()

        val renameBounds = rule.onNodeWithTag(SESSIONS_RENAME_TAG).fetchSemanticsNode().boundsInRoot
        val bitmap = snapshot()
        val scale = boundsToBitmap

        val renameColours = bitmap.coloursIn(
            (renameBounds.left * scale).roundToInt(),
            (renameBounds.top * scale).roundToInt() + 2,
            (renameBounds.right * scale).roundToInt(),
            (renameBounds.bottom * scale).roundToInt() - 2,
        )

        assertTrue(
            "Rename must be painted in action.renameBg ${theme.color.action.renameBg.toArgbHex()}, saw " +
                renameColours.size + " colours in its bounds",
            renameColours.contains(theme.color.action.renameBg.argbInt()),
        )
    }

    /**
     * The swipe action's LABEL is painted in `text.primary`, not the design's `action.rename`.
     *
     * `action.rename` on `action.renameBg` measures 4.41:1, which is UNDER the 4.5:1 AA floor --
     * so the screen raises the label to near-white. This is a pixel-level check that the AA fix
     * actually reached the screen, not just the style object.
     *
     * Mutation caught: restoring `color.action.rename` as the swipe label.
     */
    @Test
    fun theSwipeLabelIsPaintedInTextPrimaryNotTheSubAaActionColour() {
        showWired()

        rule.onNodeWithTag(sessionsRowTag("ses_root")).performTouchInput { swipeLeft() }
        rule.waitForIdle()

        val renameBounds = rule.onNodeWithTag(SESSIONS_RENAME_TAG).fetchSemanticsNode().boundsInRoot
        val bitmap = snapshot()
        val scale = boundsToBitmap
        val label = theme.swipeActionLabelColor()

        val renameColours = bitmap.coloursIn(
            (renameBounds.left * scale).roundToInt(),
            (renameBounds.top * scale).roundToInt() + 2,
            (renameBounds.right * scale).roundToInt(),
            (renameBounds.bottom * scale).roundToInt() - 2,
        )

        assertTrue(
            "the Rename label must be painted in text.primary ${label.toArgbHex()}",
            renameColours.contains(label.argbInt()),
        )
        assertTrue(
            "the Rename label must NOT be painted in the sub-AA action colour " +
                "${theme.color.action.rename.toArgbHex()}",
            !renameColours.contains(theme.color.action.rename.argbInt()),
        )
    }

    // --- search -----------------------------------------------------------------------------------

    /**
     * Typing replaces the placeholder, so the screen never shows "Search sessions" and a query at
     * the same time.
     *
     * Mutation caught: the placeholder rendering unconditionally.
     */
    @Test
    fun typingInSearchRemovesThePlaceholder() {
        show()

        rule.onNodeWithTag(SESSIONS_SEARCH_TAG).performTextInput("keyst")

        rule.onNodeWithText(SESSIONS_SEARCH_PLACEHOLDER).assertDoesNotExist()
    }

    /** Reads a Compose colour back as the hex the JSON holds, for the failure messages. */
    private fun Color.toArgbHex(): String = String.format("#%06X", 0xFFFFFF and (red * 255f + 0.5f).toInt() shl 16 or
        ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt())
}
