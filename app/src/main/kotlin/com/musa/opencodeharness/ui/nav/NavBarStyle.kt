package com.musa.opencodeharness.ui.nav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.ui.theme.OpenCodeTheme
import com.musa.opencodeharness.ui.theme.TypeRole

/**
 * The bar's colour rules, as a plain data class rather than as a composable.
 *
 * Every value comes from a token. Nothing here is a hex, and nothing here reaches for
 * `MaterialTheme.colorScheme` -- which is the trap this phase inherited from Task 1: Material 3
 * 1.4's `darkColorScheme()` has no disabled slot (`ColorScheme`'s constructor and `copy` are both
 * internal), so `colorScheme.disabled` does not exist and a bar written against it does not
 * compile. The design's inactive colour is `text.disabled`, and this file is where that decision
 * becomes a value the test can read.
 *
 * Kept separate from the composable so the rules are assertable on a plain JVM, with no
 * composition: `NavBarStyleTest` compares each field to the token it claims to come from, and
 * `NavBarPixelTest` goes further and samples the pixels the bar actually paints.
 */
internal data class NavBarStyle(
    /** Bar background: a lifted surface, so the bar reads as sitting above the page. */
    val container: Color,
    /** The single depth cue -- `elevation.dividerColor`, since the design has no shadows. */
    val hairline: Color,
    val hairlineWidth: Dp,
    /** Icon and label of the selected tab: the design's one warm hue. */
    val active: Color,
    /** Icon and label of every other tab: `text.disabled`, which the design marks non-interactive. */
    val inactive: Color,
    /** The pill behind the selected icon: the muted accent, the design's own fill for it. */
    val activeIndicator: Color,
    /** The 11sp wide-tracked eyebrow style, reused for a destination's status line. */
    val eyebrow: TypeRole = TypeRole.SECTION_HEADER,
) {
    /** Icon and label colour for one tab. One function, so no call site can pick its own. */
    fun contentColor(isSelected: Boolean): Color = if (isSelected) active else inactive
}

/**
 * Resolves [NavBarStyle] from the design tokens.
 *
 * `elevation.useShadows` is false for this design and is not read here on purpose: depth is a
 * hairline plus a surface lift, so there is nothing for the flag to switch. It is asserted
 * against in `NavBarStyleTest` so a future edit that turns it on is caught.
 */
internal fun OpenCodeTheme.navBarStyle(): NavBarStyle = NavBarStyle(
    container = color.background.raised,
    hairline = elevation.dividerColor,
    hairlineWidth = elevation.dividerWidth,
    active = color.accent.base,
    inactive = color.text.disabled,
    activeIndicator = color.accent.muted,
)

/**
 * The design's minimum touch target, from spec section 7.5.
 *
 * Stated here rather than left to the component default so the value the bar was checked against
 * is the one on record, and so [TabSlotWidthTest] can divide the viewport by it.
 */
internal val MinTouchTarget: Dp = 48.dp

/**
 * The icon size the bar draws at.
 *
 * 20dp rather than Material's 24dp default: the design's labels sit under the icon on a 393dp
 * baseline split six ways, and the smaller glyph is what makes that fit without shrinking type.
 */
internal val NavIconSize: Dp = 20.dp
