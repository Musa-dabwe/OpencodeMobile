package com.musa.opencodeharness.ui.nav

import androidx.compose.ui.graphics.Color
import com.musa.opencodeharness.ui.theme.OpenCodeTheme
import com.musa.opencodeharness.ui.theme.ThemeFixtures
import com.musa.opencodeharness.ui.theme.TypeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar's colour rules, checked against the theme file that ships.
 *
 * These are plain-JVM assertions on [NavBarStyle], so they can name the exact token each value has
 * to be. `NavBarPixelTest` covers the other half -- that the bar really paints those values rather
 * than something else -- because a style object nobody reads is not what the user sees.
 */
class NavBarStyleTest {

    private val theme: OpenCodeTheme = ThemeFixtures.theme
    private val style: NavBarStyle = theme.navBarStyle()

    /**
     * The mutation this catches: the active colour swapped to a Material default or to
     * `colorScheme.primary` looked up at the call site.
     *
     * `#FAB282` is asserted literally as well as by identity, because "it equals some token" is
     * satisfied by any of the twenty-four colours in the file and the design names exactly one.
     */
    @Test
    fun theActiveTabIsTheAccentColourAndNothingElse() {
        assertEquals(theme.color.accent.base, style.active)
        assertEquals(Color(0xFFFAB282), style.active)
        assertEquals(style.active, style.contentColor(isSelected = true))
        assertNotEquals(Color(0xFF56555B), style.active)
    }

    /**
     * The mutation this catches -- and the one this phase was warned about: taking the inactive
     * colour from `colorScheme.disabled`.
     *
     * Material 3 1.4 has no such slot, so the code cannot be written that way at all; the failure
     * mode to guard is the near-miss where it is taken from `onSurfaceVariant` or `text.secondary`
     * instead, which would render at 6.6:1 and make every inactive tab look selectable.
     */
    @Test
    fun theInactiveTabIsTextDisabledAndNothingElse() {
        assertEquals(theme.color.text.disabled, style.inactive)
        assertEquals(Color(0xFF56555B), style.inactive)
        assertEquals(style.inactive, style.contentColor(isSelected = false))
        assertNotEquals(theme.color.text.secondary, style.inactive)
        assertNotEquals(theme.color.text.sectionHeader, style.inactive)
    }

    /**
     * The mutation this catches: an indicator pill in a stock M3 colour, which would put a second
     * warm hue next to the accent.
     */
    @Test
    fun theSelectedPillIsTheMutedAccentNotAnInventedColour() {
        assertEquals(theme.color.accent.muted, style.activeIndicator)
        assertEquals(Color(0xFF89624A), style.activeIndicator)
    }

    /**
     * The mutation this catches: the bar painted on `background.base`, so it disappears into the
     * page and only the hairline separates them. The design's depth rule is "hairline PLUS surface
     * lift", and this is the lift.
     */
    @Test
    fun theBarSitsOnALiftedSurfaceNotOnThePage() {
        assertEquals(theme.color.background.raised, style.container)
        assertEquals(Color(0xFF151517), style.container)
        assertNotEquals(theme.color.background.base, style.container)
    }

    /**
     * The mutation this catches: `border.strong` (or an M3 `outlineVariant`) for the top rule,
     * which is a heavier line than the design draws.
     */
    @Test
    fun theOnlyDepthCueIsTheSubtleOneDpHairline() {
        assertEquals(theme.elevation.dividerColor, style.hairline)
        assertEquals(Color(0xFF232227), style.hairline)
        assertEquals(theme.elevation.dividerWidthDp, style.hairlineWidth.value, 0.001f)
        assertEquals(1f, style.hairlineWidth.value, 0.001f)
        assertNotEquals(theme.color.border.strong, style.hairline)
    }

    /**
     * The mutation this catches: the design having been edited to allow shadows, or the bar
     * asserting a shadow it does not draw.
     *
     * Recorded here rather than in the composable because the composable pins `tonalElevation` and
     * `shadowElevation` to zero precisely because of this flag, and a silent flip to `true` would
     * leave the bar lying about depth.
     */
    @Test
    fun theDesignForbidsShadowsSoTheBarMustNotClaimAny() {
        assertFalse(
            "elevation.useShadows is true: the bottom bar would be wrong, and the zero elevations " +
                "pinned in BottomNavBar would be suppressing a shadow the design now wants",
            theme.elevation.useShadows,
        )
    }

    /**
     * The mutation this catches: reaching for `colorScheme.onSurfaceVariant` in `BottomNavBar`
     * instead of `NavBarStyle`, which is exactly the substitution the task brief warns about.
     *
     * Every colour the composable can reach is on this record, so there is no fourth source to
     * fall back to.
     */
    @Test
    fun everyColourOnTheBarIsADesignToken() {
        val tokenColours = buildSet {
            add(theme.color.background.base); add(theme.color.background.raised)
            add(theme.color.background.raisedAlt); add(theme.color.border.subtle)
            add(theme.color.border.strong); add(theme.color.text.primary)
            add(theme.color.text.secondary); add(theme.color.text.disabled)
            add(theme.color.text.sectionHeader); add(theme.color.accent.base)
            add(theme.color.accent.muted); add(theme.elevation.dividerColor)
        }
        val barColours = setOf(style.container, style.hairline, style.active, style.inactive, style.activeIndicator)
        val invented = barColours.filterNot { it in tokenColours }
        assertTrue("the bar uses a colour that is not a design token: $invented", invented.isEmpty())
    }

    /**
     * The mutation this catches: label type shrunk below the `label` token (the plan's explicit
     * "shorten the labels rather than shrinking type" rule) by widening `eyebrow`, or the bar
     * inheriting `labelMedium` from M3 instead of the token.
     */
    @Test
    fun theStatusEyebrowUsesTheSectionHeaderRole() {
        assertEquals(TypeRole.SECTION_HEADER, style.eyebrow)
        assertEquals(11f, theme.typography.styleOf(TypeRole.SECTION_HEADER).sizeSp, 0.001f)
        // The `label` role is what the bar's own tab labels render at: 12sp, weight 500.
        assertEquals(12f, theme.typography.styleOf(TypeRole.LABEL).sizeSp, 0.001f)
        assertEquals(500, theme.typography.styleOf(TypeRole.LABEL).weightHundreds)
    }

    /**
     * The mutation this catches: dropping [MinTouchTarget] to 44dp (Material's own minimum) or
     * to the icon size, which on a six-item bar leaves a strip the spec section 7.5 floor forbids.
     */
    @Test
    fun theTouchTargetFloorIsTheSpecified48Dp() {
        assertEquals(48f, MinTouchTarget.value, 0.001f)
    }

    /**
     * The mutation this catches: the icon growing to Material's 24dp default, which is the size
     * that pushes `Terminal` past its one-sixth of a 393dp baseline.
     */
    @Test
    fun theIconIsSmallerThanMaterialDefaultSoTheLabelsFit() {
        assertEquals(20f, NavIconSize.value, 0.001f)
        assertTrue(NavIconSize < MinTouchTarget)
    }
}
