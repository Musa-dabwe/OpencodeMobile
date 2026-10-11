package com.musa.opencodeharness.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.musa.opencodeharness.ui.theme.OpenCodeTheme

/**
 * Every colour and metric the Settings screen paints, as plain data.
 *
 * The same shape, and for the same reason, as Task 2's `NavBarStyle` and Task 3's
 * `SessionsStyle`: it keeps every value readable and assertable on a plain JVM with no
 * composition, so `SettingsStyleTest` can prove each field really comes from the token it names and
 * `SettingsPixelTest` can go on to prove the screen paints it.
 *
 * ## Every field here is read by a composable
 *
 * Task 3's `SessionsStyle` shipped three fields nothing read (`rowGap`, `hairlineWidth`, `accent`)
 * and the test that asserted them could not fail. They were deleted rather than re-pointed. This
 * file is written with that in mind: there is no speculative field, and `SettingsStyleTest`
 * compares every declared field against the token it is documented to come from.
 *
 * No hex appears here, and nothing reaches for `MaterialTheme.colorScheme` -- the design's roles
 * (status dots, chevrons, section labels) have no Material 3 slot to inherit, and a scheme default
 * would be a colour the design does not contain.
 */
internal data class SettingsStyle(
    /** Page background. */
    val page: Color,
    /** The `surface` card the design draws the server card and every settings group on. */
    val card: Color,
    /** The 1dp `border.subtle` rule: the design's only depth cue, since there are no shadows. */
    val hairline: Color,
    /** The screen title, and any row that is actually wired. */
    val title: Color,
    /**
     * A DISABLED row's label, every right-aligned value, and the log lines.
     *
     * `text.secondary` at 6.6:1, deliberately NOT `text.disabled`. That token measures 2.65:1 on the
     * page and is reserved for genuinely non-interactive marks; a user has to be able to READ a row
     * to know what the setting would have been. Same ruling `PlaceholderDestination` applied to its
     * eyebrow. So `text.disabled` is not read by this screen at all.
     */
    val muted: Color,
    /** `MODELS` / `SAFETY` / `APP` / `DIAGNOSTICS`. */
    val sectionHeader: Color,
    /** The card's `v2.0.22 · 127.0.0.1:45997 · Alpine` sub-line. */
    val subLine: Color,
    /**
     * The card's detail line (a death sentence, an unreachable reason) -- the one place a failure
     * is spelled out, so it is `status.error` rather than muted.
     */
    val detail: Color,
    /** The status dot beside a HEALTHY server: `status.idle`, the design's own dot for this card. */
    val dotHealthy: Color,
    /**
     * The status dot while the server is STARTING: `text.secondary`, deliberately NEUTRAL.
     *
     * Same ruling as Task 3's UNKNOWN dot: "we do not know yet" is not an affirmative state, so it
     * must not wear a status colour that asserts one. Never accent -- a dot means state, and accent
     * means action.
     */
    val dotStarting: Color,
    /** The status dot for a stopped server, or one that stopped answering: `status.error`. */
    val dotError: Color,
    /** The `Restart` capsule fill -- the screen's single primary action. */
    val restartFill: Color,
    /** The label on that capsule: `text.onAccent`. */
    val restartLabel: Color,
    /** The row chevron, drawn only on a row that actually goes somewhere. */
    val chevron: Color,
    /** The runtime log lines: mono, muted, inside the diagnostics card. */
    val logText: Color,
    val screenMargin: Dp,
    val sectionGap: Dp,
    val rowGap: Dp,
    /** The cards' corner: the design's general corner radius. */
    val cardRadius: Dp,
    /** The `Restart` capsule. */
    val capsule: Dp,
)

/**
 * Resolves [SettingsStyle] from the design tokens.
 *
 * ## Accent ownership
 *
 * The design reserves accent for "the single primary action on a screen", and this screen has
 * exactly one candidate: `Restart`. So the card's fill is the only accent on the screen, and a
 * restart action that is not wired renders DISABLED -- raised fill, hairline outline, muted label,
 * no click -- rather than an accent button that does nothing. That is the Task 3 FAB ruling, and it
 * is why the `Restart` callback is nullable (`null` = "this action does not exist yet") rather than
 * defaulted to `{}`.
 */
internal fun OpenCodeTheme.settingsStyle(): SettingsStyle = SettingsStyle(
    page = color.background.base,
    card = color.background.raised,
    hairline = color.border.subtle,
    title = color.text.primary,
    muted = color.text.secondary,
    sectionHeader = color.text.sectionHeader,
    subLine = color.text.secondary,
    detail = color.status.error,
    dotHealthy = color.status.idle,
    dotStarting = color.text.secondary,
    dotError = color.status.error,
    restartFill = color.accent.base,
    restartLabel = color.text.onAccent,
    chevron = color.text.secondary,
    logText = color.text.secondary,
    screenMargin = spacing.screenMargin,
    sectionGap = spacing.sectionGap,
    rowGap = spacing.rowGap,
    cardRadius = shape.card,
    capsule = shape.capsule,
)
