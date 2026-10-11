package com.musa.opencodeharness.ui.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.ui.theme.OpenCodeTheme

/**
 * Every colour and metric the Sessions screen paints, as plain data.
 *
 * The same shape as Task 2's `NavBarStyle` and for the same reason: it keeps every value readable
 * and assertable on a plain JVM with no composition, so a test can prove each field really comes
 * from the token it names. No hex appears here; nothing reaches for
 * `MaterialTheme.colorScheme`, because the design's roles (status dots, action fills, agent
 * colours) have no Material 3 slot to inherit and a scheme default would be a colour the design
 * does not contain.
 */
internal data class SessionsStyle(
    /** Page background. */
    val page: Color,
    /** The search field's `raised` fill, per design §3.1. */
    val searchFill: Color,
    /** Screen title (`Sessions`), the back chevron, and nothing else. */
    val title: Color,
    /** Row title: bold, near-white. */
    val rowTitle: Color,
    /** The meta line and the relative timestamp: muted, `text.secondary`. */
    val meta: Color,
    /** `TODAY` / `EARLIER`, in the design's muted section-header colour. */
    val sectionHeader: Color,
    /** The sub-agent's agent name: `agentBlue`. */
    val agentBlue: Color,
    /** The row separator: the 1dp `border.subtle` hairline, the design's only depth cue. */
    val hairline: Color,
    val hairlineWidth: Dp,
    /** Swipe Rename: the action fill, with the label raised to `text.primary` for AA. See below. */
    val renameFill: Color,
    /** Swipe Delete: the design's `deleteBg` fill. */
    val deleteFill: Color,
    /**
     * The status dot for [SessionStatus.IDLE]: `status.idle`, the design's own dot colour for a
     * live session. It measures 5.9:1 against the page, comfortably over the 3:1 non-text floor.
     *
     * The screen paints a row's dot with [statusDotFor], NOT with this field directly -- the dot
     * colour is a function of the row's status, and hard-painting one colour is what let a row
     * whose word read "unknown" wear an idle-green dot.
     */
    val statusDot: Color,
    /** The dot for [SessionStatus.RUNNING]: `status.running`. See [statusDotFor] for its floor. */
    val runningDot: Color,
    /** The dot for [SessionStatus.ERROR]: `status.error`. */
    val errorDot: Color,
    /**
     * The dot for [SessionStatus.UNKNOWN]: `text.secondary` -- deliberately NEUTRAL, not a status
     * colour.
     *
     * UNKNOWN means "this app does not know", and `status.idle` means "healthy and idle", which
     * is an affirmative claim the app cannot make. It is the same token as [meta] because both
     * say the same thing: nothing strong. See [statusDotFor].
     */
    val unknownDot: Color,
    /**
     * The STATUS WORD beside the dot, in `text.primary`.
     *
     * Not `status.running` and not `text.secondary`: the word is the only thing carrying the
     * dot's meaning, so it has to be readable on its own. `text.primary` on the page is 17.5:1.
     */
    val statusWord: Color,
    /** Screen margin, row gap, section gap: all from `spacing`. */
    val screenMargin: Dp,
    val rowGap: Dp,
    val sectionGap: Dp,
    /** The `This project` scope pill: accent OUTLINE, accent TEXT, page fill. */
    val pillOutline: Color,
    val pillText: Color,
    /** The FAB's accent fill and its dark `text.onAccent` label. */
    val fabFill: Color,
    val fabLabel: Color,
    /** The one primary action on this screen. See `accentOwnership` for why it is the FAB. */
    val accent: Color,
    /** Hairline outline on the search field, per design ("search field: surface fill, hairline border"). */
    val searchOutline: Color,
    /** The FAB's capsule height, from `shape.fabHeightDp` (53dp, measured from the design). */
    val fabHeight: Dp = 53.dp,
)

/**
 * Resolves [SessionsStyle] from the design tokens.
 *
 * ## Accent ownership: the FAB is the screen's single primary action
 *
 * The design's own rule is "accent is reserved for the single primary action on a screen," and two
 * things on this screen could claim it: the `+ New session` FAB and the `This project` scope pill.
 * They cannot both be the primary action, so this is the decision and its reasoning:
 *
 *  - **The FAB is the primary action.** It is the one control that CREATES a session -- the one
 *    irreversible, user-initiated thing the screen does. The design also draws the FAB as a solid
 *    accent capsule (`+ New session`, "the ONE primary action colour") and draws the scope pill as
 *    an accent OUTLINE, i.e. the design itself renders the pill as secondary.
 *  - **The scope pill is secondary**: it only narrows what is already shown. It wears accent as a
 *    1dp outline and accent-coloured text, never an accent fill, so it reads as a filter control
 *    and never competes with the FAB's solid accent.
 *
 * Accent therefore appears in exactly two places -- the FAB's fill and the pill's outline/text --
 * and the row status dots are NEVER accent: they come from `status.*` or a neutral token, because
 * accent means "action" and a dot means "state".
 */
internal fun OpenCodeTheme.sessionsStyle(): SessionsStyle = SessionsStyle(
    page = color.background.base,
    searchFill = color.background.raised,
    title = color.text.primary,
    rowTitle = color.text.primary,
    meta = color.text.secondary,
    sectionHeader = color.text.sectionHeader,
    agentBlue = color.agent.primary,
    hairline = color.border.subtle,
    hairlineWidth = elevation.dividerWidth,
    renameFill = color.action.renameBg,
    deleteFill = color.action.deleteBg,
    statusDot = color.status.idle,
    runningDot = color.status.running,
    errorDot = color.status.error,
    unknownDot = color.text.secondary,
    statusWord = color.text.primary,
    screenMargin = spacing.screenMargin,
    rowGap = spacing.rowGap,
    sectionGap = spacing.sectionGap,
    pillOutline = color.accent.base,
    pillText = color.accent.base,
    fabFill = color.accent.base,
    fabLabel = color.text.onAccent,
    accent = color.accent.base,
    searchOutline = color.border.subtle,
)

/**
 * The colour a swipe action's LABEL is drawn in.
 *
 * ## Why this is not the design's `action.rename` / `action.delete`
 *
 * The design pairs `action.rename #5A9AFC` on `action.renameBg #203552` -- measured 4.41:1,
 * which is 0.09 UNDER the 4.5:1 AA floor for normal-size text, and `action.delete` on
 * `deleteBg` measures 4.96:1 (passing, but barely). The brief forbids silently shipping a
 * sub-AA pairing. The honest fix uses no new colour: the label is `text.primary #F2F2F4`, which
 * on the SAME `renameBg #203552` fill measures 11.10:1 and on `deleteBg #3A161A` measures 14.34:1
 * -- both far over AA. So the swipe actions keep the design's exact FILLS (the blue/red identity
 * the design wants, at 4.41:1 as a large block of colour is fine) but draw their text in the
 * design's own near-white, which is what the accessibility floor demands of the TEXT.
 *
 * ## What happened to `action.blueFg`
 *
 * `action.blueFg #3A5687` has NO recoverable intended pairing from the design: on `renameBg` it
 * measures 1.69:1 (effectively invisible) and on the page 2.66:1 (under the 3:1 non-text floor).
 * There is no background in the palette it clears. So it is not used as a foreground anywhere --
 * using it would ship an unreadable action. It is kept as a token (the design file has it, so the
 * loader still parses it) and its one honest role here is documented as "unassigned": nothing on
 * this screen claims it. Inventing a background to pair it with would be inventing a colour value,
 * which the phase constraints forbid. See the task report for the full reasoning.
 */
internal fun OpenCodeTheme.swipeActionLabelColor(): Color = color.text.primary
