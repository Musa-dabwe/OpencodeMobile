package com.musa.opencodeharness.ui.theme

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * The design tokens, for composables that want a colour or a radius the Material 3 scheme has no
 * slot for -- status dots, hairline borders, capsule pills.
 *
 * Reads better than a bare `staticCompositionLocalOf` because the value is always in scope, and
 * it fails loudly if a composable is previewed outside [OpenCodeMobileTheme] rather than silently
 * handing back a default palette.
 */
val LocalOpenCodeTheme = staticCompositionLocalOf<OpenCodeTheme> {
    error("No OpenCodeTheme in this composition -- wrap the content in OpenCodeMobileTheme {}")
}

/** Shorthand for [LocalOpenCodeTheme]. */
val MaterialTheme.openCode: OpenCodeTheme
    @Composable get() = LocalOpenCodeTheme.current

/**
 * The design's hairline: the single way depth is expressed, since `elevation.useShadows` is false.
 */
val OpenCodeTheme.hairlineColor: Color get() = elevation.dividerColor

/**
 * The app's theme. Dark only, by design: the PDF has no light palette and none is invented here.
 *
 * There is deliberately no `dynamicColor` parameter either. Material You would tint the app with
 * the device wallpaper's palette, which is a palette the design does not contain.
 */
@Composable
fun OpenCodeMobileTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Read once per configuration, not per composition: ThemeLoader caches, and `remember` keeps
    // the lookup off the recomposition path entirely.
    val theme = remember(context.applicationContext) { ThemeLoader.load(context) }
    CompositionLocalProvider(LocalOpenCodeTheme provides theme) {
        MaterialTheme(
            colorScheme = theme.color.toColorScheme(),
            typography = theme.typography.toMaterialTypography(),
            shapes = theme.shape.toMaterialShapes(),
            content = content,
        )
    }
}

/**
 * The bridge into Material 3.
 *
 * The design has no second brand colour -- accent is the only warm hue -- so the slots M3 requires
 * but the design does not name are filled with the closest real token rather than a stock M3
 * default. Which is which:
 *
 *  - `primary` is `accent.base`: it is the ONE primary action colour in the design, and M3's
 *    `primary` is exactly that role. `onPrimary` is `text.onAccent`.
 *  - `secondary`/`tertiary` are the two agent hues. They exist because M3 components tint
 *    themselves with them; no design decision depends on it.
 *  - `surface` is `background.raised` and `surfaceVariant` is `background.raisedAlt`, which is the
 *    design's own mapping (search field / inset card / keyboard key vs elevated card and sheet).
 *  - the five `surfaceContainer*` roles step through the four real surface tokens, darkest first.
 *    M3's tonal elevation overlay is never used, so they stay flat.
 *  - `outline` is the hairline, `outlineVariant` the stronger border, because M3 draws dividers
 *    with `outlineVariant` and text-field borders with `outline`, and the design's own divider is
 *    the subtle one.
 *  - the three disabled roles (`onSurfaceDisabled`, `disabled`, `disabledContainer`) CANNOT be set
 *    here: Material3 1.4's `darkColorScheme()` does not take them, and `ColorScheme`'s constructor
 *    and `copy` are both internal to the library. The design's disabled colour is `text.disabled`,
 *    and components in this app read it from the token object -- which is where it belongs anyway,
 *    and which Task 2's bottom bar is specified to use for its inactive colour regardless.
 */
internal fun ColorTokens.toColorScheme() = darkColorScheme(
    primary = accent.base,
    onPrimary = text.onAccent,
    primaryContainer = accent.muted,
    // NOT text.onAccent: on the muted accent that pairing measures 3.24:1, while text.primary
    // measures 4.80:1. Both are real tokens; this is the one that passes.
    onPrimaryContainer = text.primary,
    inversePrimary = accent.base,
    secondary = agent.primary,
    onSecondary = text.onAccent,
    secondaryContainer = background.raisedAlt,
    onSecondaryContainer = text.primary,
    tertiary = agent.subagent,
    onTertiary = text.onAccent,
    tertiaryContainer = background.raisedAlt,
    onTertiaryContainer = text.primary,
    background = background.base,
    onBackground = text.primary,
    surface = background.raised,
    onSurface = text.primary,
    surfaceVariant = background.raisedAlt,
    onSurfaceVariant = text.secondary,
    surfaceContainerLowest = background.inset,
    surfaceContainerLow = background.raised,
    surfaceContainer = background.raised,
    surfaceContainerHigh = background.raisedAlt,
    surfaceContainerHighest = background.overlay,
    surfaceBright = background.overlay,
    surfaceDim = background.inset,
    surfaceTint = accent.base,
    inverseSurface = text.primary,
    inverseOnSurface = background.base,
    error = status.error,
    onError = background.base,
    errorContainer = action.deleteBg,
    onErrorContainer = status.error,
    outline = border.subtle,
    outlineVariant = border.strong,
    scrim = background.scrim,
)

/**
 * The design's divider: `elevation.dividerColor` at `elevation.dividerWidth`.
 *
 * Exists so the hairline cannot be reinvented per screen -- both halves come from the same token
 * group the JSON declares, and a caller that wants a different one has to say so.
 */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = LocalOpenCodeTheme.current.elevation.dividerColor,
) {
    val theme = LocalOpenCodeTheme.current
    HorizontalDivider(
        modifier = modifier,
        thickness = theme.elevation.dividerWidth,
        color = color,
    )
}

/**
 * Strips the drop shadow Material 3 puts on some components by default (a FAB carries 3dp of
 * shadow and 6dp of tonal elevation out of the box).
 *
 * The design has no drop shadows, so a component that wants one has to add this rather than
 * inherit one. With no shadow to draw the layer is otherwise inert.
 */
fun Modifier.noDropShadow(): Modifier = graphicsLayer { shadowElevation = 0f }
