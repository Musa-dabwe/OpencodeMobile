package com.musa.opencodeharness.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.ui.SessionListUiState
import com.musa.opencodeharness.ui.sessions.NOT_BUILT_A11Y
import com.musa.opencodeharness.ui.wiredOrDisabled
import com.musa.opencodeharness.ui.theme.Hairline
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import com.musa.opencodeharness.ui.theme.TypeRole
import com.musa.opencodeharness.ui.theme.noDropShadow
import com.musa.opencodeharness.ui.theme.openCode

/** Test hook: the screen root. */
internal const val SETTINGS_TAG = "settings:screen"

/** Test hook: the screen's own heading, which the nav bar's label of the same text would shadow. */
internal const val SETTINGS_TITLE_TAG = "settings:title"

/** Test hook: the `OpenCode server ...` card. */
internal const val SETTINGS_SERVER_CARD_TAG = "settings:serverCard"

/** Test hook: the card's status dot. */
internal const val SETTINGS_SERVER_DOT_TAG = "settings:serverDot"

/** Test hook: the card's headline. */
internal const val SETTINGS_SERVER_HEADLINE_TAG = "settings:serverHeadline"

/** Test hook: the word beside the dot -- the only thing that carries its meaning. */
internal const val SETTINGS_SERVER_WORD_TAG = "settings:serverWord"

/** Test hook: the `v2.0.22 · 127.0.0.1:45997 · Alpine` sub-line. Absent when nothing is known. */
internal const val SETTINGS_SERVER_SUBLINE_TAG = "settings:serverSubline"

/** Test hook: the failure sentence. Absent unless the server is stopped or not answering. */
internal const val SETTINGS_SERVER_DETAIL_TAG = "settings:serverDetail"

/** Test hook: the `Restart` capsule. */
internal const val SETTINGS_RESTART_TAG = "settings:restart"

/** Test hook prefix: one per settings group. */
internal const val SETTINGS_SECTION_TAG_PREFIX = "settings:section:"

/** Test hook prefix: one per row, suffixed with the row's label. */
internal const val SETTINGS_ROW_TAG_PREFIX = "settings:row:"

/** The section tag for a given group title. */
internal fun settingsSectionTag(title: String) = "$SETTINGS_SECTION_TAG_PREFIX$title"

/** The row tag for a given row label. */
internal fun settingsRowTag(label: String) = "$SETTINGS_ROW_TAG_PREFIX$label"

/** Test hook: the diagnostics card holding the relocated runtime log. */
internal const val SETTINGS_LOG_TAG = "settings:log"

/** Test hook: the one line saying how much of the log is not on screen. Absent when none is. */
internal const val SETTINGS_LOG_TRUNCATED_TAG = "settings:logTruncated"

/** The design's minimum touch target, from spec section 7.5 -- the same value the bar is held to. */
private val MinTouchTarget: Dp = 48.dp

/** The status dot's diameter, the same 8dp the Sessions rows use. */
private val StatusDotSize: Dp = 8.dp

/**
 * The Settings destination (design section 3.7) -- the subset real state backs today.
 *
 * ## What this screen is allowed to show
 *
 * Everything on it is read off [SessionListUiState], which is itself read off the launcher's
 * [com.musa.opencodeharness.data.runtime.launch.ServerState], the HTTP client's answer, and
 * `GET /api/info`. There is no constant on this screen that could be mistaken for data: the version
 * comes from the probe, the endpoint from the client's own base URL, and the OS label from a single
 * named fact about what the app installs.
 *
 * ## What it deliberately refuses to show
 *
 * The design's value column -- `sonnet-4.5`, `depth 1`, `ask for bash`, `3 connected`,
 * `9 available`, `on`, `share: manual`, `212 MB` -- describes a product with a model picker, an
 * approval policy, an MCP client, a skills registry and an installed size the app does not measure.
 * Every one of those rows renders DISABLED with no value at all. See [settingsSections].
 *
 * ## Accent
 *
 * `Restart` is the single primary action, and it is the only accent on the screen. While
 * [onRestart] is null it renders as a disabled control -- raised fill, hairline outline, muted
 * label, no click -- rather than an accent capsule that does nothing. Status dots are `status.idle`,
 * a neutral, or `status.error`; never accent, and never a colour that asserts more than the word
 * beside it.
 */
@Composable
fun SettingsScreen(
    state: SessionListUiState,
    /** Null when nothing can restart the server yet; then the capsule renders disabled. */
    onRestart: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.openCode
    val style = theme.settingsStyle()

    val card = serverCardModel(
        serverState = state.serverState,
        connection = state.connection,
        version = state.serverVersion,
        serverUrl = state.serverUrl,
    )

    Box(modifier = modifier.fillMaxSize().background(style.page).testTag(SETTINGS_TAG)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = style.screenMargin),
        ) {
            Text(
                text = SETTINGS_TITLE,
                style = theme.typography.textStyle(TypeRole.SCREEN_TITLE),
                color = style.title,
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).testTag(SETTINGS_TITLE_TAG),
            )

            ServerCard(
                model = card,
                style = style,
                onRestart = onRestart,
            )

            settingsSections().forEach { section ->
                SectionCard(section, style, Modifier.padding(top = style.sectionGap))
            }

            DiagnosticsCard(
                lines = state.logLines,
                style = style,
                modifier = Modifier.padding(top = style.sectionGap),
            )

            Spacer(Modifier.heightIn(min = style.sectionGap))
        }
    }
}

/** The screen's own heading. A constant, so no call site can drift from the design. */
internal const val SETTINGS_TITLE = "Settings"

// --- server card -----------------------------------------------------------------------------------

/**
 * The design's top card: a status dot, `OpenCode server running`, the version/endpoint/OS sub-line,
 * and a `Restart` capsule.
 *
 * ## Why the dot and the word are separate tagged nodes
 *
 * The same structural rule Task 3 applied to session rows: the word is what carries the state, so it
 * has to be a node a test can require rather than a comment claiming it is there. And the dot's
 * colour comes from [ServerCardModel.dot] alone -- there is no branch that paints a colour the word
 * does not support, and no branch that draws a bare dot.
 */
@Composable
private fun ServerCard(
    model: ServerCardModel,
    style: SettingsStyle,
    onRestart: (() -> Unit)?,
) {
    val theme = MaterialTheme.openCode
    val shape = RoundedCornerShape(style.cardRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(style.card, shape)
            // A hairline plus a surface lift, which is the design's whole depth vocabulary.
            // `noDropShadow()` because M3 would otherwise add tonal elevation to a card.
            .noDropShadow()
            .border(1.dp, style.hairline, shape)
            .padding(16.dp)
            .testTag(SETTINGS_SERVER_CARD_TAG),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .testTag(SETTINGS_SERVER_DOT_TAG)
                    .size(StatusDotSize)
                    .background(style.dotColorFor(model.dot), CircleShape)
                    // Decorative: the word beside it carries the state, and naming both would read
                    // "running, running" to a screen reader.
                    .semantics { contentDescription = "" },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = model.headline,
                style = theme.typography.textStyle(TypeRole.ROW_TITLE),
                color = style.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(SETTINGS_SERVER_HEADLINE_TAG),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = model.stateWord,
                style = theme.typography.textStyle(TypeRole.META),
                color = style.muted,
                maxLines = 1,
                modifier = Modifier.testTag(SETTINGS_SERVER_WORD_TAG),
            )
        }

        // The sub-line is omitted entirely rather than filled with placeholders. No version reported
        // means no version segment; no endpoint means no endpoint segment.
        model.subLine?.let { subLine ->
            Text(
                text = subLine,
                style = theme.typography.textStyle(TypeRole.META),
                color = style.subLine,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).testTag(SETTINGS_SERVER_SUBLINE_TAG),
            )
        }

        model.detail?.let { detail ->
            Text(
                text = detail,
                style = theme.typography.textStyle(TypeRole.META),
                color = style.detail,
                modifier = Modifier.padding(top = 6.dp).testTag(SETTINGS_SERVER_DETAIL_TAG),
            )
        }

        RestartButton(
            style = style,
            onRestart = onRestart,
            modifier = Modifier.align(Alignment.End).padding(top = 12.dp),
        )
    }
}

/** The dot's colour, and the only place a [ServerDot] becomes a colour. */
private fun SettingsStyle.dotColorFor(dot: ServerDot): Color = when (dot) {
    ServerDot.HEALTHY -> dotHealthy
    ServerDot.STARTING -> dotStarting
    ServerDot.ERROR -> dotError
}

/**
 * `Restart`: a full capsule in accent, the screen's one primary action.
 *
 * While [onRestart] is null it is painted the way a platform paints a disabled button -- the raised
 * surface, a hairline outline and a muted label -- and carries disabled semantics. It keeps its
 * shape, size and label so the layout still matches the design; what it loses is the promise. This
 * is the Task 3 FAB ruling applied to the one control this screen has.
 *
 * The 48dp height is spec section 7.5, asserted by `SettingsScreenTest`.
 */
@Composable
private fun RestartButton(
    style: SettingsStyle,
    onRestart: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val wired = onRestart != null
    val theme = MaterialTheme.openCode
    val shape = RoundedCornerShape(style.capsule)
    Box(
        modifier = modifier
            .noDropShadow()
            .testTag(SETTINGS_RESTART_TAG)
            .background(if (wired) style.restartFill else style.card, shape)
            .then(
                if (wired) {
                    Modifier
                } else {
                    Modifier.border(1.dp, style.hairline, shape)
                },
            )
            .wiredOrDisabled(onRestart)
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = RESTART_LABEL,
            style = theme.typography.textStyle(TypeRole.LABEL),
            color = if (wired) style.restartLabel else style.muted,
            maxLines = 1,
            modifier = if (wired) Modifier else Modifier.semantics {
                disabled()
                contentDescription = "$RESTART_LABEL: $NOT_BUILT_A11Y"
            },
        )
    }
}

/** The design's button label. */
internal const val RESTART_LABEL = "Restart"

// --- sections --------------------------------------------------------------------------------------

/**
 * One grouped card: the uppercase section header, then the rows separated by hairlines.
 *
 * Dividers are `Hairline`, so they read `elevation.dividerColor` at `elevation.dividerWidth` and
 * cannot be reinvented per screen. They sit inset to the screen margin, matching the Sessions rows,
 * so the card's own hairline reads as the group and the inner ones as the rows.
 */
@Composable
private fun SectionCard(
    section: SettingsSection,
    style: SettingsStyle,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.openCode
    val shape = RoundedCornerShape(style.cardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(style.card, shape)
            .noDropShadow()
            .border(1.dp, style.hairline, shape)
            .testTag(settingsSectionTag(section.title)),
    ) {
        Text(
            text = section.title,
            style = theme.typography.textStyle(TypeRole.SECTION_HEADER),
            color = style.sectionHeader,
            maxLines = 1,
            modifier = Modifier
                .padding(start = style.rowGap * 2, end = style.rowGap * 2, top = style.rowGap, bottom = 8.dp),
        )
        section.rows.forEachIndexed { index, row ->
            if (index > 0) Hairline(modifier = Modifier.padding(horizontal = style.rowGap * 2))
            SettingsRowView(row, style)
        }
    }
}

/**
 * One row: label, right-aligned muted value, and a chevron.
 *
 * ## No chevron and no click on a disabled row
 *
 * The design draws a `›` on every row. A chevron IS the affordance for "this goes somewhere", so a
 * disabled row does not get one: offering a gesture that leads nowhere is the promise the phase rules
 * forbid, and Task 3 applied exactly this ruling to the swipe actions (an action that is not wired is
 * not offered at all). The label is `text.secondary`, not `text.disabled`, because the user still has
 * to be able to read what the setting would have been -- see [SettingsStyle.muted].
 */
@Composable
private fun SettingsRowView(
    row: SettingsRow,
    style: SettingsStyle,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.openCode
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(start = style.rowGap * 2, end = style.rowGap * 2)
            .wiredOrDisabled(if (row.enabled) ({}) else null)
            .testTag(settingsRowTag(row.label)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = theme.typography.textStyle(TypeRole.BODY),
            color = if (row.enabled) style.title else style.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        row.value?.let { value ->
            Spacer(Modifier.width(style.rowGap))
            Text(
                text = value,
                style = theme.typography.textStyle(TypeRole.META),
                color = style.muted,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
        if (row.enabled) {
            Spacer(Modifier.width(style.rowGap))
            Icon(
                imageVector = RowChevron,
                contentDescription = null,
                tint = style.chevron,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// --- diagnostics ------------------------------------------------------------------------------------

/**
 * The runtime log, RELOCATED here from the Sessions screen.
 *
 * ## Why it moved
 *
 * The band sat permanently below the session list, stealing vertical space from a screen whose rows
 * are grouped and nested, and it never went away -- including once the server was healthy and its job
 * was done. The design's Sessions screen is header, search, rows and a FAB; there is no log panel on
 * it. Spec section 3.5 is Diagnostics, and this card is it.
 *
 * It is a RELOCATION, not a deletion. The capability Phase 2 built deliberately is intact: the same
 * `LogBuffer` tail, the same redaction, the same "N earlier lines not shown" honesty about truncation.
 *
 * ## Redaction
 *
 * There is no filtering here and there must never be any. [LogBuffer] replaces every registered
 * secret with `***redacted***` BEFORE storing a line, and `RuntimeSetup` scrubs its own progress
 * lines, so everything reaching this card is already safe. A panel that "helpfully" re-filtered here
 * would be a second place to get redaction wrong; a panel that rendered anything it was not given
 * would be a way to leak. It renders its input and nothing else.
 *
 * ## Why the bound is larger than it was on Sessions
 *
 * The old band had to share a fixed height with a list, so it showed four lines. This card sits in the
 * page's own scroll, so nothing is hidden behind a clipped box -- every line shown is reachable by
 * scrolling -- and twenty lines is about the most that stays a useful diagnostic rather than a wall.
 * Truncation is still announced rather than silent.
 */
@Composable
private fun DiagnosticsCard(
    lines: List<String>,
    style: SettingsStyle,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.openCode
    val shape = RoundedCornerShape(style.cardRadius)
    val visible = lines.takeLast(VISIBLE_LOG_LINES)
    val hidden = lines.size - visible.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(style.card, shape)
            .noDropShadow()
            .border(1.dp, style.hairline, shape)
            .testTag(settingsSectionTag(DIAGNOSTICS_SECTION)),
    ) {
        Text(
            text = DIAGNOSTICS_SECTION,
            style = theme.typography.textStyle(TypeRole.SECTION_HEADER),
            color = style.sectionHeader,
            maxLines = 1,
            modifier = Modifier.padding(start = style.rowGap * 2, end = style.rowGap * 2, top = style.rowGap, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = style.rowGap * 2)
                .padding(bottom = style.rowGap)
                .testTag(SETTINGS_LOG_TAG),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = SERVER_LOG_LABEL,
                    style = theme.typography.textStyle(TypeRole.ROW_TITLE),
                    color = style.title,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // A real count of what this buffer holds, named as what it is. Never a fabricated
                // total: `LogBuffer` is a bounded rolling tail, so "N buffered" is exactly true.
                Text(
                    text = bufferedLabel(lines.size),
                    style = theme.typography.textStyle(TypeRole.META),
                    color = style.muted,
                    maxLines = 1,
                )
            }

            if (hidden > 0) {
                Text(
                    text = truncatedLabel(hidden),
                    style = theme.typography.textStyle(TypeRole.SECTION_HEADER),
                    color = style.sectionHeader,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp).testTag(SETTINGS_LOG_TRUNCATED_TAG),
                )
            }

            if (visible.isEmpty()) {
                Text(
                    text = NO_LOG_LINES,
                    style = theme.typography.textStyle(TypeRole.MONO),
                    color = style.logText,
                )
            } else {
                visible.forEach { line ->
                    Text(
                        text = line,
                        style = theme.typography.textStyle(TypeRole.MONO),
                        color = style.logText,
                    )
                }
            }
        }
    }
}

/** The design's group this card belongs to. Spec section 3.5 is the Diagnostics screen. */
internal const val DIAGNOSTICS_SECTION = "DIAGNOSTICS"

/** The card's own heading. */
internal const val SERVER_LOG_LABEL = "Server log"

/** What an empty buffer says. Honest, and it names no artefact. */
internal const val NO_LOG_LINES = "No output yet."

/** How many log lines the card shows at once. See [DiagnosticsCard] for why this is not everything. */
internal const val VISIBLE_LOG_LINES = 20

/** `N lines buffered` -- the real size of the rolling tail, never an invented total. */
private fun bufferedLabel(count: Int): String = "$count lines buffered"

/** The truncation notice. A log that quietly drops entries is the failure the panel exists to stop. */
private fun truncatedLabel(hidden: Int): String = "log · $hidden earlier lines not shown"

// --- previews --------------------------------------------------------------------------------------

@Preview(showBackground = true, name = "settings")
@Composable
private fun SettingsPreview() {
    OpenCodeMobileTheme {
        SettingsScreen(state = SessionListUiState())
    }
}
