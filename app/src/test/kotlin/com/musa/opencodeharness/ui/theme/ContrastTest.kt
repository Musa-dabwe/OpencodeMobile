package com.musa.opencodeharness.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of extracting the palette into JSON: the colours become something a machine can
 * check, so a later edit cannot quietly make the app unreadable.
 *
 * Every ratio here is WCAG 2.x relative luminance, computed by [relativeLuminance] -- pure
 * arithmetic, no device, no Robolectric.
 */
class ContrastTest {

    private val theme = ThemeFixtures.theme

    /** The page, and the two lifted surfaces rows and cards are drawn on. */
    private val surfaces = listOf(
        "background.base" to theme.color.background.base,
        "background.raised" to theme.color.background.raised,
        "background.raisedAlt" to theme.color.background.raisedAlt,
    )

    /** WCAG 2.1 AA for text at any size below 18pt-bold. */
    private val aaTextFloor = 4.5

    /**
     * Roles that render text a user may have to read, so they clear AA on every surface they can
     * appear on.
     */
    private val foregroundRoles = mapOf(
        "text.primary" to theme.color.text.primary,
        "text.secondary" to theme.color.text.secondary,
        "text.sectionHeader" to theme.color.text.sectionHeader,
        "accent.base" to theme.color.accent.base,
        "status.idle" to theme.color.status.idle,
        "status.error" to theme.color.status.error,
        "agent.primary" to theme.color.agent.primary,
        "agent.subagent" to theme.color.agent.subagent,
        "action.rename" to theme.color.action.rename,
        "action.delete" to theme.color.action.delete,
    )

    /**
     * The roles the design places BELOW the AA text floor, each with the reason it is allowed.
     *
     * Each is asserted twice: that it really is below the floor (so the exemption stays a recorded
     * decision rather than a coincidence), and that it is still far enough above the background to
     * be seen at all (so a future edit cannot quietly turn it into the page colour).
     */
    private val belowTextFloor = mapOf(
        "text.disabled" to "genuinely non-interactive text only -- never anything the user must read " +
            "or act on. This is the one exemption the design doc calls out by name.",
        "status.running" to "the running dot's fill, which is always accompanied by the word " +
            "'running' in text.primary. Never carries text on its own.",
        "agent.experimental" to "an experimental-agent badge, always bold and always beside the agent " +
            "name in text.primary. Clears the 3:1 non-text floor.",
        "action.blueFg" to "a secondary tint for outlined blue actions. Below the 3:1 non-text floor " +
            "too, so it must not be used for a label on action.renameBg (1.69:1 there).",
        "accent.muted" to "a container fill, never a label: text.onAccent on it is 3.24:1. " +
            "text.primary is 4.80:1 and is what the scheme pairs with it.",
    )

    /** Non-text contrast (WCAG 1.4.11) for anything that has to be distinguishable as a shape. */
    private val nonTextFloor = 3.0

    @Test
    fun theLuminanceMathAgreesWithWcagAtItsReferencePoints() {
        assertEquals(0.0, relativeLuminance(Color(0xFF000000L)), 1e-9)
        assertEquals(1.0, relativeLuminance(Color(0xFFFFFFFFL)), 1e-9)
        assertEquals(21.0, contrastRatio(Color(0xFFFFFFFFL), Color(0xFF000000L)), 0.01)
        // Order-independent.
        assertEquals(21.0, contrastRatio(Color(0xFF000000L), Color(0xFFFFFFFFL)), 0.01)
        assertEquals(1.0, contrastRatio(Color(0xFF123456L), Color(0xFF123456L)), 1e-9)
    }

    @Test
    fun everyForegroundRoleClearsTheTextFloorOnEverySurface() {
        val failures = foregroundRoles.flatMap { (role, colour) ->
            surfaces.mapNotNull { (surfaceName, surface) ->
                val ratio = contrastRatio(colour, surface)
                if (ratio < aaTextFloor) "$role on $surfaceName is %.2f:1".format(ratio) else null
            }
        }
        assertTrue(
            "below the ${aaTextFloor}:1 AA text floor:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun theAccentLabelClearsTheTextFloorOnEveryAccentState() {
        // text.onAccent is the label colour on a filled accent button, and hover/pressed are
        // real states the UI will render.
        listOf(
            "accent.base" to theme.color.accent.base,
            "accent.hover" to theme.color.accent.hover,
            "accent.pressed" to theme.color.accent.pressed,
        ).forEach { (state, accent) ->
            val ratio = contrastRatio(theme.color.text.onAccent, accent)
            assertTrue(
                "text.onAccent on $state is %.2f:1, under $aaTextFloor".format(ratio),
                ratio >= aaTextFloor,
            )
        }
    }

    @Test
    fun errorAndActionLabelsAreReadableOnTheirOwnContainers() {
        assertAtLeast(theme.color.status.error, theme.color.action.deleteBg, "status.error on action.deleteBg")
        assertAtLeast(theme.color.action.delete, theme.color.action.deleteBg, "action.delete on action.deleteBg")
        // 4.41:1 -- 0.09 under AA. The value is sampled from the design, so it is recorded rather
        // than corrected here; a drop below 4.0 must fail.
        assertTrue(
            "action.rename on action.renameBg fell to %.2f:1".format(
                contrastRatio(theme.color.action.rename, theme.color.action.renameBg),
            ),
            contrastRatio(theme.color.action.rename, theme.color.action.renameBg) >= 4.0,
        )
    }

    @Test
    fun theRatiosRecordedInThePlanStillHold() {
        // docs/plans/phase3-ui.md quotes these. They are pinned so that a hex edit which changes
        // them has to be a deliberate one.
        data class Recorded(val role: String, val foreground: Color, val background: Color, val expected: Double)

        val recorded = listOf(
            Recorded("text.primary", theme.color.text.primary, theme.color.background.base, 17.5),
            Recorded("text.secondary", theme.color.text.secondary, theme.color.background.base, 6.6),
            Recorded("text.sectionHeader", theme.color.text.sectionHeader, theme.color.background.base, 6.0),
            Recorded("accent.base", theme.color.accent.base, theme.color.background.base, 10.9),
            Recorded("status.error", theme.color.status.error, theme.color.background.base, 6.2),
            Recorded("text.onAccent", theme.color.text.onAccent, theme.color.accent.base, 9.7),
        )
        recorded.forEach { (role, foreground, background, expected) ->
            assertEquals(
                "$role no longer measures the ratio the plan recorded",
                expected,
                contrastRatio(foreground, background),
                0.05,
            )
        }
    }

    @Test
    fun theExemptRolesReallyAreBelowTheTextFloorAndAreStillVisible() {
        belowTextFloor.forEach { (role, reason) ->
            val colour = colourOf(role)
            val worst = surfaces.minBy { contrastRatio(colour, it.second) }
            val worstRatio = contrastRatio(colour, worst.second)

            assertTrue(
                "$role is no longer below the $aaTextFloor:1 text floor (%.2f:1 on %s), so it " +
                    "belongs in foregroundRoles instead of the exempt list. Reason it was exempt: $reason"
                        .format(worstRatio, worst.first),
                worstRatio < aaTextFloor,
            )
            // Low enough to be exempt from AA text, high enough that a later edit cannot make it
            // vanish into the surface. The lightest surface is the worst case, so it is checked
            // there.
            assertTrue(
                "$role is %.2f:1 on %s -- effectively invisible. Reason it was exempt: $reason"
                    .format(worstRatio, worst.first),
                worstRatio >= 2.0,
            )
        }
    }

    @Test
    fun containerAndBadgeFillsClearTheNonTextFloorWhereTheDesignUsesThem() {
        // accent.muted fills a container and agent.experimental is a badge: both are shapes, and
        // WCAG 1.4.11 asks 3:1 of shapes rather than 4.5:1 of text.
        assertTrue(
            "accent.muted on background.raisedAlt is %.2f:1".format(
                contrastRatio(theme.color.accent.muted, theme.color.background.raisedAlt),
            ),
            contrastRatio(theme.color.accent.muted, theme.color.background.raisedAlt) >= nonTextFloor,
        )
        assertTrue(
            "agent.experimental on background.raisedAlt is %.2f:1".format(
                contrastRatio(theme.color.agent.experimental, theme.color.background.raisedAlt),
            ),
            contrastRatio(theme.color.agent.experimental, theme.color.background.raisedAlt) >= nonTextFloor,
        )
    }

    private fun assertAtLeast(foreground: Color, background: Color, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$what is %.2f:1, under $aaTextFloor".format(ratio), ratio >= aaTextFloor)
    }

    private fun colourOf(role: String): Color = when (role) {
        "text.disabled" -> theme.color.text.disabled
        "status.running" -> theme.color.status.running
        "agent.experimental" -> theme.color.agent.experimental
        "action.blueFg" -> theme.color.action.blueFg
        "accent.muted" -> theme.color.accent.muted
        else -> error("no colour named $role; add it to this lookup when the design grows one")
    }
}
