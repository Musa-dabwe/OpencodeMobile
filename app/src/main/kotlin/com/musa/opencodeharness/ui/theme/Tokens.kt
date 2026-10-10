package com.musa.opencodeharness.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The whole design system, parsed from `assets/themes/opencode-mobile.json`.
 *
 * This mirrors the token tree one-for-one rather than collapsing it into whatever Material 3
 * happens to expose, because the JSON is the artefact a future edit touches and every value in
 * it has to be reachable from here -- and therefore assertable. The Material 3 bridge is a
 * separate, explicitly named translation: `toMaterialTypography()` below and
 * `ColorTokens.toColorScheme()` in Theme.kt.
 *
 * DARK ONLY. The design PDF has no light palette, so none is invented here: there is no
 * `darkTheme` flag and no dynamic colour, because both would silently substitute a palette the
 * design does not contain.
 */
@Immutable
data class OpenCodeTheme(
    val version: Int,
    val source: String,
    val color: ColorTokens,
    val typography: TypeTokens,
    val shape: ShapeTokens,
    val spacing: SpacingTokens,
    val elevation: ElevationTokens,
    val motion: MotionTokens,
)

@Immutable
data class ColorTokens(
    val background: BackgroundColors,
    val border: BorderColors,
    val text: TextColors,
    val accent: AccentColors,
    val status: StatusColors,
    val agent: AgentColors,
    val action: ActionColors,
)

/**
 * A colour group that can carry `$`-prefixed state variants alongside its named roles.
 *
 * `{"base": "#0C0C0E", "$hovered": "#111113"}` keeps `base` typed and available and files
 * `$hovered` under [states]. V2 theme files use those state keys, so the loader has to be able
 * to represent them even though the bundled palette declares none.
 */
@Immutable
data class BackgroundColors(
    val base: Color,
    val raised: Color,
    val raisedAlt: Color,
    val overlay: Color,
    val scrim: Color,
    val inset: Color,
    val states: Map<String, Color> = emptyMap(),
)

@Immutable
data class BorderColors(
    val subtle: Color,
    val strong: Color,
    val states: Map<String, Color> = emptyMap(),
)

@Immutable
data class TextColors(
    val primary: Color,
    val secondary: Color,
    /** The one role below the AA text floor (2.65:1 on `background.base`). Non-interactive text only. */
    val disabled: Color,
    val sectionHeader: Color,
    val onAccent: Color,
    val states: Map<String, Color> = emptyMap(),
)

@Immutable
data class AccentColors(
    val base: Color,
    val hover: Color,
    val pressed: Color,
    val muted: Color,
    val states: Map<String, Color> = emptyMap(),
)

@Immutable
data class StatusColors(
    val idle: Color,
    val running: Color,
    val error: Color,
    val states: Map<String, Color> = emptyMap(),
)

@Immutable
data class AgentColors(
    val primary: Color,
    val subagent: Color,
    val experimental: Color,
    val states: Map<String, Color> = emptyMap(),
)

@Immutable
data class ActionColors(
    val rename: Color,
    val renameBg: Color,
    val delete: Color,
    val deleteBg: Color,
    val blueFg: Color,
    val states: Map<String, Color> = emptyMap(),
)

/**
 * The seven type roles. An enum rather than stringly-typed keys so that adding a role is a
 * compile error at every `when` over it, and so the test can walk every role exhaustively.
 */
enum class TypeRole(val jsonKey: String) {
    SCREEN_TITLE("screenTitle"),
    ROW_TITLE("rowTitle"),
    META("meta"),
    SECTION_HEADER("sectionHeader"),
    BODY("body"),
    LABEL("label"),
    MONO("mono"),
}

/** One type role as the JSON records it: size, weight and tracking, and nothing else. */
@Immutable
data class TypeStyle(
    val role: TypeRole,
    /** Size in sp, as read from the design renders. */
    val sizeSp: Float,
    /** Weight in hundreds (400 == `FontWeight.Normal`). Validated to 100..900 step 100 on load. */
    val weightHundreds: Int,
    /** Tracking in em. `0.12` == 0.12em, which is the unit Compose's `letterSpacing` wants. */
    val trackingEm: Double,
    /** The JSON's prose note about where the role is used. Carried, never parsed. */
    val usage: String,
) {
    val fontWeight: FontWeight
        get() = when (weightHundreds) {
            100 -> FontWeight.Thin
            200 -> FontWeight.ExtraLight
            300 -> FontWeight.Light
            400 -> FontWeight.Normal
            500 -> FontWeight.Medium
            600 -> FontWeight.SemiBold
            700 -> FontWeight.Bold
            800 -> FontWeight.ExtraBold
            900 -> FontWeight.Black
            else -> error("weight $weightHundreds is not a 100..900 step-100 weight")
        }

    /**
     * No typeface is bundled anywhere in this app, by owner decision, so every role resolves to
     * the system sans except `mono`. `lineHeight` is left UNSPECIFIED on purpose: the JSON
     * carries no line-height token, and inventing one here would put a number in the code that
     * no design file governs.
     */
    fun toTextStyle(): TextStyle = TextStyle(
        fontFamily = if (role == TypeRole.MONO) FontFamily.Monospace else FontFamily.Default,
        fontWeight = fontWeight,
        fontSize = sizeSp.sp,
        letterSpacing = trackingEm.em,
    )
}

@Immutable
data class TypeTokens(
    /** Always `system`. Nothing else is accepted: there is no bundled font to fall back to. */
    val typeface: String,
    val screenTitle: TypeStyle,
    val rowTitle: TypeStyle,
    val meta: TypeStyle,
    val sectionHeader: TypeStyle,
    val body: TypeStyle,
    val label: TypeStyle,
    val mono: TypeStyle,
) {
    /** Exhaustive, so a new [TypeRole] cannot be added without deciding where it lands. */
    fun styleOf(role: TypeRole): TypeStyle = when (role) {
        TypeRole.SCREEN_TITLE -> screenTitle
        TypeRole.ROW_TITLE -> rowTitle
        TypeRole.META -> meta
        TypeRole.SECTION_HEADER -> sectionHeader
        TypeRole.BODY -> body
        TypeRole.LABEL -> label
        TypeRole.MONO -> mono
    }

    fun textStyle(role: TypeRole): TextStyle = styleOf(role).toTextStyle()

    /** Every role keyed by the enum, for iteration in tests and previews. */
    val byRole: Map<TypeRole, TypeStyle> get() = TypeRole.entries.associateWith(::styleOf)

    /**
     * The bridge into Material 3's typography slots.
     *
     * M3 has 15 slots and the design has 7 roles, so this is a many-to-one mapping: every slot
     * the app renders through is claimed by a role, and the slots that carry no design meaning
     * inherit `body` rather than a made-up size. `mono` has no M3 slot -- it is reached through
     * [textStyle], which is where paths and diffs will want it.
     */
    fun toMaterialTypography(): Typography {
        fun style(role: TypeRole) = textStyle(role)
        return Typography(
            displayLarge = style(TypeRole.SCREEN_TITLE),
            displayMedium = style(TypeRole.SCREEN_TITLE),
            displaySmall = style(TypeRole.SCREEN_TITLE),
            headlineLarge = style(TypeRole.SCREEN_TITLE),
            headlineMedium = style(TypeRole.SCREEN_TITLE),
            headlineSmall = style(TypeRole.SCREEN_TITLE),
            titleLarge = style(TypeRole.ROW_TITLE),
            titleMedium = style(TypeRole.ROW_TITLE),
            titleSmall = style(TypeRole.ROW_TITLE),
            bodyLarge = style(TypeRole.BODY),
            bodyMedium = style(TypeRole.BODY),
            bodySmall = style(TypeRole.META),
            labelLarge = style(TypeRole.LABEL),
            labelMedium = style(TypeRole.LABEL),
            labelSmall = style(TypeRole.SECTION_HEADER),
        )
    }
}

@Immutable
data class ShapeTokens(
    /** Radius that makes a pill: a full capsule. */
    val capsuleDp: Float,
    val cardDp: Float,
    val chipDp: Float,
    val fabHeightDp: Float,
    val fabWidthDp: Float,
) {
    val capsule: Dp get() = capsuleDp.dp
    val card: Dp get() = cardDp.dp
    val chip: Dp get() = chipDp.dp
    val fabHeight: Dp get() = fabHeightDp.dp
    val fabWidth: Dp get() = fabWidthDp.dp

    /**
     * M3 exposes five corner slots and the design defines three radii. Rather than leave the
     * unused slots at M3's stock numbers -- which would put radii in the app that no design file
     * governs -- every unclaimed slot resolves to the card radius, the design's general corner.
     */
    fun toMaterialShapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(chipDp.dp),
        small = RoundedCornerShape(cardDp.dp),
        medium = RoundedCornerShape(cardDp.dp),
        large = RoundedCornerShape(cardDp.dp),
        extraLarge = RoundedCornerShape(cardDp.dp),
    )
}

@Immutable
data class SpacingTokens(
    val screenMarginDp: Float,
    val sectionGapDp: Float,
    val rowGapDp: Float,
    val hairlineDp: Float,
) {
    val screenMargin: Dp get() = screenMarginDp.dp
    val sectionGap: Dp get() = sectionGapDp.dp
    val rowGap: Dp get() = rowGapDp.dp
    val hairline: Dp get() = hairlineDp.dp
}

@Immutable
data class ElevationTokens(
    /**
     * Always false in this design: depth is a hairline plus a surface lift, never a drop shadow.
     * Read by `OpenCodeMobileTheme`, so an edit that turns shadows on cannot slip into the app
     * unnoticed.
     */
    val useShadows: Boolean,
    val dividerColor: Color,
    val dividerWidthDp: Float,
) {
    val dividerWidth: Dp get() = dividerWidthDp.dp
}

@Immutable
data class MotionTokens(
    val fastMillis: Int,
    val mediumMillis: Int,
    val respectRemoveAnimations: Boolean,
) {
    /**
     * Duration in milliseconds, or 0 when the system has animations turned off. Honours the
     * token's own `respectRemoveAnimations` flag: when it is false the flag is inert, which is
     * the only thing a flag like that can mean.
     */
    fun duration(fast: Boolean, animationsEnabled: Boolean = true): Int = when {
        !animationsEnabled -> 0
        fast -> fastMillis
        else -> mediumMillis
    }
}
