package com.musa.opencodeharness.ui.settings

import com.musa.opencodeharness.ui.theme.ThemeFixtures
import com.musa.opencodeharness.ui.theme.flatColours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every colour and metric the Settings screen paints, compared against the token it names.
 *
 * The same shape as `NavBarStyleTest` and `SessionsStyleTest`: the screen reads a plain data object
 * so the mapping can be asserted on the JVM with no composition. A field pointed at the wrong token
 * fails here, and `SettingsPixelTest` then proves the screen actually paints what this resolves to.
 */
class SettingsStyleTest {

    private val theme = ThemeFixtures.theme
    private val style = theme.settingsStyle()

    @Test
    fun everyFieldComesFromTheTokenItIsDocumentedToComeFrom() {
        assertEquals(theme.color.background.base, style.page)
        assertEquals(theme.color.background.raised, style.card)
        assertEquals(theme.color.border.subtle, style.hairline)
        assertEquals(theme.color.text.primary, style.title)
        assertEquals(theme.color.text.secondary, style.muted)
        assertEquals(theme.color.text.sectionHeader, style.sectionHeader)
        assertEquals(theme.color.text.secondary, style.subLine)
        assertEquals(theme.color.status.error, style.detail)
        assertEquals(theme.color.status.idle, style.dotHealthy)
        assertEquals(theme.color.text.secondary, style.dotStarting)
        assertEquals(theme.color.status.error, style.dotError)
        assertEquals(theme.color.accent.base, style.restartFill)
        assertEquals(theme.color.text.onAccent, style.restartLabel)
        assertEquals(theme.color.text.secondary, style.chevron)
        assertEquals(theme.color.text.secondary, style.logText)
        assertEquals(theme.spacing.screenMargin, style.screenMargin)
        assertEquals(theme.spacing.sectionGap, style.sectionGap)
        assertEquals(theme.spacing.rowGap, style.rowGap)
        assertEquals(theme.shape.card, style.cardRadius)
        assertEquals(theme.shape.capsule, style.capsule)
    }

    /**
     * The three dot colours must be three genuinely different tokens.
     *
     * Mutation caught: `dotStarting` pointed at `status.idle`, which is the exact regression Task 3
     * was reviewed for -- a green cue sitting beside a word that does not claim health. Asserting
     * only `dotStarting == text.secondary` would catch one wrong route; asserting the three are
     * pairwise distinct catches ANY substitution, including an invented one.
     */
    @Test
    fun theThreeDotColoursAreDistinctTokens() {
        val dots = listOf(style.dotHealthy, style.dotStarting, style.dotError).toSet()

        assertEquals("a dot role was pointed at another role's colour", 3, dots.size)
        assertNotEquals(style.dotHealthy, style.dotStarting)
        assertNotEquals(style.dotError, style.dotStarting)
    }

    /**
     * A dot is never accent.
     *
     * Accent means "action" in this design and a dot means state. This is the assertion the brief
     * for this task singles out, and it is cheap here and decisive in `SettingsPixelTest`.
     */
    @Test
    fun noDotColourIsAccent() {
        for (dot in listOf(style.dotHealthy, style.dotStarting, style.dotError)) {
            assertNotEquals("a status dot must never be accent", theme.color.accent.base, dot)
        }
    }

    /**
     * Accent appears exactly once in the style: the `Restart` fill.
     *
     * Mutation caught: `restartLabel` or `chevron` re-pointed at accent, which would put the design's
     * one warm hue on a label that is not an action.
     */
    @Test
    fun accentIsUsedForTheRestartFillAndNothingElseInThisStyle() {
        val accent = theme.color.accent.base
        val carriers = listOf(
            "page" to style.page, "card" to style.card, "hairline" to style.hairline,
            "title" to style.title, "muted" to style.muted, "sectionHeader" to style.sectionHeader,
            "subLine" to style.subLine, "detail" to style.detail, "dotHealthy" to style.dotHealthy,
            "dotStarting" to style.dotStarting, "dotError" to style.dotError,
            "restartFill" to style.restartFill, "restartLabel" to style.restartLabel,
            "chevron" to style.chevron, "logText" to style.logText,
        ).filter { it.second == accent }.map { it.first }

        assertEquals(listOf("restartFill"), carriers)
    }

    /**
     * The design has no drop shadows, so there is nothing for a shadow to be switched on for.
     *
     * Asserted here rather than on a pixel, because Robolectric's Skia renders no shadows at all --
     * a pixel assertion for one would pass identically with and without it. Task 2 found that the
     * hard way. The structural check lives here; the visual one belongs on a device screenshot.
     */
    @Test
    fun theDesignHasNoDropShadowsToInherit() {
        assertTrue("elevation.useShadows must stay false for this design", !theme.elevation.useShadows)
    }

    /**
     * Every value on the card is at or above the WCAG AA floor for text.
     *
     * The brief for this task calls out `text.disabled` (2.65:1) explicitly. This style does not read
     * it at all, so this assertion is the mechanism rather than a comment: if a future edit re-points
     * `muted` or `subLine` at it, this fails.
     */
    @Test
    fun noStyleFieldUsesTheSubAaDisabledToken() {
        for ((name, colour) in listOf(
            "muted" to style.muted,
            "subLine" to style.subLine,
            "logText" to style.logText,
            "chevron" to style.chevron,
            "title" to style.title,
            "sectionHeader" to style.sectionHeader,
            "detail" to style.detail,
        )) {
            assertNotEquals(
                "$name must not be text.disabled -- it carries text a user has to read",
                theme.color.text.disabled,
                colour,
            )
        }
    }

    /** Nothing here may be a Material 3 scheme default: every colour is a real design token. */
    @Test
    fun everyStyleColourIsOneOfTheDesignsOwnTokens() {
        val palette = theme.flatColours().values.toSet()
        for ((name, colour) in listOf(
            "page" to style.page, "card" to style.card, "hairline" to style.hairline,
            "title" to style.title, "muted" to style.muted, "sectionHeader" to style.sectionHeader,
            "subLine" to style.subLine, "detail" to style.detail, "dotHealthy" to style.dotHealthy,
            "dotStarting" to style.dotStarting, "dotError" to style.dotError,
            "restartFill" to style.restartFill, "restartLabel" to style.restartLabel,
            "chevron" to style.chevron, "logText" to style.logText,
        )) {
            assertTrue("$name ($colour) is not a token from the theme JSON", palette.contains(colour))
        }
    }
}
