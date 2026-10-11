package com.musa.opencodeharness.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.data.SessionNode
import com.musa.opencodeharness.data.model.Location
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.model.SessionTime
import com.musa.opencodeharness.ui.isSubAgentSession
import com.musa.opencodeharness.ui.theme.Hairline
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import com.musa.opencodeharness.ui.theme.TypeRole
import com.musa.opencodeharness.ui.theme.noDropShadow
import com.musa.opencodeharness.ui.theme.openCode

/** Test hook: the screen root. */
internal const val SESSIONS_TAG = "sessions:screen"

/** Test hook: the header's back chevron. */
internal const val SESSIONS_BACK_TAG = "sessions:back"

/** Test hook: the `This project` scope pill. */
internal const val SESSIONS_SCOPE_TAG = "sessions:scope"

/** Test hook: the search input. */
internal const val SESSIONS_SEARCH_TAG = "sessions:search"

/** Test hook: the search field's placeholder. */
internal const val SESSIONS_SEARCH_PLACEHOLDER = "Search sessions"

/** Test hook: the `+ New session` FAB. */
internal const val SESSIONS_FAB_TAG = "sessions:fab"

/** Test hook prefix: one per rendered row, suffixed with the session id. */
internal const val SESSIONS_ROW_TAG_PREFIX = "sessions:row:"

/** The row tag for a given session id. */
internal fun sessionsRowTag(id: String) = "$SESSIONS_ROW_TAG_PREFIX$id"

/** Test hook: the Rename swipe action. */
internal const val SESSIONS_RENAME_TAG = "sessions:action:rename"

/** Test hook: the Delete swipe action. */
internal const val SESSIONS_DELETE_TAG = "sessions:action:delete"

/** Test hook: the row's status word, which is the only thing that carries the dot's meaning. */
internal const val SESSIONS_STATUS_WORD_TAG_PREFIX = "sessions:status:"

/** The status-word tag for a given session id. */
internal fun sessionsStatusWordTag(id: String) = "$SESSIONS_STATUS_WORD_TAG_PREFIX$id"

/**
 * The Sessions screen (design §3.1).
 *
 * ## What this composable owns, and what it does not
 *
 * Data is not this file's business: it takes the session TREE and renders it. The ViewModel owns
 * the load, the tree and the connection state (`SessionListUiState`). That split is what lets the
 * whole screen be driven from a hand-built tree in a Robolectric test, with no server and no
 * coroutines -- and it is why the rewrite of the old screen kept the state model untouched.
 *
 * ## The two things that must not regress
 *
 *  1. **The nesting rule.** A row is a sub-agent iff `parentID != null` ([isSubAgentSession]),
 *     never "has no children". Indentation is by TREE DEPTH, which is a different fact again --
 *     a promoted orphan (has a parent, parent not in the list) is a sub-agent at depth 0. See
 *     [buildSessionRows].
 *  2. **The status dot is never alone.** `status.running` measures 2.60:1 on the page, under the
 *     3:1 non-text floor, and is acceptable only because the word always sits beside it.
 *     [SessionRow] renders the word in every path; there is no branch that draws a bare dot.
 *
 * ## Accent
 *
 * The `+ New session` FAB is the screen's single primary action and wears the solid accent fill;
 * the scope pill wears an accent OUTLINE because it only filters. Row dots are `status.idle`, never
 * accent. See [sessionsStyle] for the full ruling.
 */
@Composable
fun SessionsScreen(
    roots: List<SessionNode>,
    modifier: Modifier = Modifier,
    /** Injected so tests are deterministic; production passes the wall clock. */
    nowMillis: Long = System.currentTimeMillis(),
    /** The scope filter's label; the pill is a control, so its text is the caller's. */
    scopeLabel: String = SCOPE_THIS_PROJECT,
    onToggleScope: () -> Unit = {},
    onBack: () -> Unit = {},
    onNewSession: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    val theme = MaterialTheme.openCode
    val style = theme.sessionsStyle()
    var search by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val allRows = remember(roots, nowMillis) { buildSessionRows(roots, nowMillis) }
    // Search filters by title only, so a row is shown iff the server returned it AND its title
    // matches. It can never invent a row, and it never shows a title the server did not send.
    val visibleRows = remember(allRows, search) {
        val needle = search.trim()
        if (needle.isEmpty()) allRows else allRows.filter { row ->
            row.session.title?.contains(needle, ignoreCase = true) == true
        }
    }

    Box(modifier = modifier.fillMaxSize().background(style.page).testTag(SESSIONS_TAG)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SessionsHeader(
                onBack = onBack,
                scopeLabel = scopeLabel,
                onToggleScope = onToggleScope,
                style = style,
                titleStyle = theme.typography.textStyle(TypeRole.SCREEN_TITLE),
                labelStyle = theme.typography.textStyle(TypeRole.LABEL),
            )
            SessionsSearchField(
                value = search,
                onValueChange = { search = it },
                style = style,
                textStyle = theme.typography.textStyle(TypeRole.BODY),
            )
            SessionListBody(
                rows = visibleRows,
                isFiltered = search.isNotBlank(),
                listState = listState,
                style = style,
                bottomInset = FAB_TOTAL_HEIGHT + style.screenMargin * 2,
                rowTitleStyle = theme.typography.textStyle(TypeRole.ROW_TITLE),
                metaStyle = theme.typography.textStyle(TypeRole.META),
                sectionStyle = theme.typography.textStyle(TypeRole.SECTION_HEADER),
                onRename = onRename,
                onDelete = onDelete,
            )
        }
        NewSessionFab(
            style = style,
            labelStyle = theme.typography.textStyle(TypeRole.LABEL),
            onClick = onNewSession,
            modifier = Modifier.align(Alignment.BottomEnd).padding(style.screenMargin),
        )
    }
}

// --- header -------------------------------------------------------------------------------------

/**
 * The header: back chevron, the `Sessions` title, and the scope pill on the right.
 *
 * The chevron is a real 48dp touch target (spec §7.5) wrapping a 24dp glyph, not a bare icon that
 * is too small to hit.
 */
@Composable
private fun SessionsHeader(
    onBack: () -> Unit,
    scopeLabel: String,
    onToggleScope: () -> Unit,
    style: SessionsStyle,
    titleStyle: TextStyle,
    labelStyle: TextStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = style.screenMargin, end = style.screenMargin, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .testTag(SESSIONS_BACK_TAG)
                .size(MinTouchTarget)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(BackChevron, contentDescription = "Back", tint = style.title, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(text = SCREEN_TITLE, style = titleStyle, color = style.title, maxLines = 1)
        Spacer(Modifier.weight(1f))
        ScopePill(scopeLabel, style, labelStyle, onToggleScope)
    }
}

/** The design's own heading. A constant so no call site can drift from the spec. */
internal const val SCREEN_TITLE = "Sessions"

/** The design's scope filter label, top-right. */
internal const val SCOPE_THIS_PROJECT = "This project"

/**
 * The scope filter pill: accent OUTLINE, accent text, `raised` fill, capsule corners.
 *
 * Outline rather than a fill on purpose -- a filled accent pill would be a second primary action
 * competing with the FAB. See [sessionsStyle].
 */
@Composable
private fun ScopePill(
    label: String,
    style: SessionsStyle,
    labelStyle: TextStyle,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = labelStyle,
        color = style.pillText,
        maxLines = 1,
        modifier = Modifier
            .testTag(SESSIONS_SCOPE_TAG)
            .clickable(onClick = onClick)
            .background(style.searchFill, RoundedCornerShape(CapsuleRadius))
            .border(1.dp, style.pillOutline, RoundedCornerShape(CapsuleRadius))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// --- search -------------------------------------------------------------------------------------

/**
 * The search field: `raised` fill, hairline border, magnifier, `Search sessions` placeholder.
 *
 * A plain [BasicTextField] rather than Material's `OutlinedTextField`, because M3's version brings
 * its own indicator colour, its own focused/unfocused fills and its own label padding -- none of
 * which are colours this design contains. Here the fill, the hairline and the icon are all the
 * design's own tokens.
 */
@Composable
private fun SessionsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    style: SessionsStyle,
    textStyle: TextStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = style.screenMargin, vertical = 4.dp)
            .height(SearchHeight)
            .background(style.searchFill, RoundedCornerShape(SearchRadius))
            .border(1.dp, style.searchOutline, RoundedCornerShape(SearchRadius))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Magnifier, contentDescription = null, tint = style.meta, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = SESSIONS_SEARCH_PLACEHOLDER,
                    style = textStyle,
                    color = style.meta,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle.copy(color = style.rowTitle),
                cursorBrush = SolidColor(style.pillText),
                modifier = Modifier.fillMaxWidth().testTag(SESSIONS_SEARCH_TAG),
            )
        }
    }
}

// --- list body ----------------------------------------------------------------------------------

/**
 * The list body: rows grouped under `TODAY` / `EARLIER`, parents before their sub-agents.
 *
 * Section headers are emitted as ITEMS in the same `LazyColumn` rather than as nested lists: the
 * design has no per-section container, and one scroll surface is what makes a sub-agent scroll
 * away with its parent rather than being pinned to a section of its own.
 *
 * The scroll position is remembered INSIDE this composable (Task 2's finding: a `LazyListState`
 * built by the caller and passed in outlives the composition on its own, so a "state survives"
 * test can pass with the mechanism deleted). Here it is the screen's own business, and
 * `SaveableStateHolder` in `AppShell` keeps it across a tab switch because it is `rememberSaveable`
 * underneath.
 */
@Composable
private fun SessionListBody(
    rows: List<SessionRowModel>,
    /** True when a search query is active, which changes what "no rows" MEANS. */
    isFiltered: Boolean,
    listState: LazyListState,
    style: SessionsStyle,
    bottomInset: Dp,
    rowTitleStyle: TextStyle,
    metaStyle: TextStyle,
    sectionStyle: TextStyle,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Headers interleaved with rows in ONE list. Each section header appears at most once, the
    // first time a row of that section does, so a group that is entirely filtered out leaves
    // nothing behind -- an empty `TODAY` header would be a lie about what is on screen.
    val entries = remember(rows) { buildList<ListEntry> {
        var lastSection: SessionSection? = null
        for (row in rows) {
            if (row.section != lastSection) {
                add(ListEntry.Header(row.section))
                lastSection = row.section
            }
            add(ListEntry.Row(row))
        }
    } }

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                // Two different emptinesses, and conflating them is a lie. "The server has none"
                // and "your search matched none" are different facts, and a user who searched
                // `zzz` must not be told the server is empty -- it is not.
                text = if (isFiltered) NO_MATCH_MESSAGE else NO_SESSIONS_MESSAGE,
                style = metaStyle,
                color = style.meta,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(style.screenMargin).testTag(EMPTY_MESSAGE_TAG),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Bottom padding clears the FAB, so the last row can never be pinned under it.
        contentPadding = PaddingValues(bottom = bottomInset),
    ) {
        entries(
            entries = entries,
            style = style,
            rowTitleStyle = rowTitleStyle,
            metaStyle = metaStyle,
            sectionStyle = sectionStyle,
            onRename = onRename,
            onDelete = onDelete,
        )
    }
}

/** One entry in the flat list: either a section header or a row. */
internal sealed interface ListEntry {
    /** Stable and unique per section, so a header never collides with a session id. */
    data class Header(val section: SessionSection) : ListEntry
    data class Row(val row: SessionRowModel) : ListEntry
}

/** What an empty list says when the SERVER returned nothing. Never a fabricated row or count. */
internal const val NO_SESSIONS_MESSAGE = "No sessions on this server yet."

/**
 * What an empty list says when a SEARCH is active and matched nothing.
 *
 * Deliberately distinct from [NO_SESSIONS_MESSAGE]. Found on device: typing a non-matching query
 * showed "No sessions on this server yet" while the server had six sessions, which is exactly the
 * invented-state failure the phase rules forbid.
 */
internal const val NO_MATCH_MESSAGE = "No sessions match this search."

/** Test hook: whichever empty message is showing. */
internal const val EMPTY_MESSAGE_TAG = "sessions:empty"

/** Emits the interleaved header/row list, keyed per entry. */
private fun LazyListScope.entries(
    entries: List<ListEntry>,
    style: SessionsStyle,
    rowTitleStyle: TextStyle,
    metaStyle: TextStyle,
    sectionStyle: TextStyle,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    items(
        count = entries.size,
        // Unique by construction: a section header appears at most once, and `buildSessionTree`
        // guarantees each session id appears once. So no row is ever rebuilt from scratch and no
        // two rows share a key.
        key = { index ->
            when (val entry = entries[index]) {
                is ListEntry.Header -> "header:${entry.section.name}"
                is ListEntry.Row -> "row:${entry.row.session.id}"
            }
        },
    ) { index ->
        when (val entry = entries[index]) {
            is ListEntry.Header -> SectionHeader(entry.section, style, sectionStyle)
            is ListEntry.Row -> SessionRow(
                row = entry.row,
                style = style,
                rowTitleStyle = rowTitleStyle,
                metaStyle = metaStyle,
                onRename = { onRename(entry.row.session.id) },
                onDelete = { onDelete(entry.row.session.id) },
            )
        }
    }
}

/** The `TODAY` / `EARLIER` header: uppercase, wide tracking, muted section-header colour. */
@Composable
private fun SectionHeader(
    section: SessionSection,
    style: SessionsStyle,
    textStyle: TextStyle,
) {
    Text(
        text = section.label,
        style = textStyle,
        color = style.sectionHeader,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = style.screenMargin,
                end = style.screenMargin,
                top = style.sectionGap,
                bottom = 6.dp,
            ),
    )
}

// --- row ----------------------------------------------------------------------------------------

/**
 * One session row: status dot · bold title · right-aligned timestamp · meta line, swipeable.
 *
 * ## Indentation vs. sub-agent-ness: two different facts
 *
 * The row indents by [SessionRowModel.depth] (TREE depth -- how far the row is from a root) and
 * colours the agent qualifier by [SessionRowModel.isSubAgent] (`parentID != null`). They are NOT
 * the same test and must never be swapped: a promoted orphan -- a sub-agent whose parent is not in
 * the list -- has depth 0 because `buildSessionTree` promoted it, while still being a sub-agent.
 * `SessionsScreenTest` asserts exactly that row.
 *
 * ## The a11y rule, enforced in this function
 *
 * The dot is a `status.*` colour and the status WORD is always rendered beside it -- as a tagged
 * node, so a test can prove the pairing rather than trust this comment. `status.running` is
 * 2.60:1 on the page, under the 3:1 non-text floor; it is only acceptable because the word carries
 * the meaning. There is no branch here that renders a dot without that word.
 */
@Composable
private fun SessionRow(
    row: SessionRowModel,
    style: SessionsStyle,
    rowTitleStyle: TextStyle,
    metaStyle: TextStyle,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeActionRow(
        style = style,
        onRename = onRename,
        onDelete = onDelete,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Indent by tree depth so a sub-agent reads as nested under its parent.
                    .padding(
                        start = style.screenMargin + NestedIndent * row.depth,
                        end = style.screenMargin,
                        top = 10.dp,
                        bottom = 10.dp,
                    )
                    // The tag sits on the PADDED row, not the wrapper, because this node's left
                    // edge is the indentation the user sees. Tagged on the wrapper it would span
                    // the full width at every depth and the nesting assertion would be vacuous.
                    .testTag(sessionsRowTag(row.session.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Box(
                modifier = Modifier
                    .size(StatusDotSize)
                    .background(style.statusDot, CircleShape)
                    // The dot is decorative: the word beside it is what carries the state, and
                    // naming both would read "unknown, unknown" to a screen reader.
                    .semantics { contentDescription = "" },
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.session.title?.takeIf { it.isNotBlank() } ?: UNTITLED_SESSION,
                    style = rowTitleStyle,
                    color = style.rowTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaLine(
                        row = row,
                        style = style,
                        metaStyle = metaStyle,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
                Text(
                    text = row.timestamp,
                    style = metaStyle,
                    color = style.meta,
                    maxLines = 1,
                )
            }
            // The hairline that separates rows: the design's ONLY depth cue. 1dp of
            // `border.subtle`, no shadow -- `elevation.useShadows` is false.
            Hairline(
                modifier = Modifier.padding(start = style.screenMargin),
                color = style.hairline,
            )
        }
    }
}

/**
 * The `project · status · qualifier` meta line.
 *
 * Each segment carries its own emphasis, so the agent name renders in `agentBlue` while project
 * and status stay muted. The STATUS segment is emitted as its own tagged node: that is the
 * accessibility requirement made structural -- the word that carries the dot's meaning is a node a
 * test can require, so "the dot is never the only carrier of state" stops being a comment.
 */
@Composable
private fun MetaLine(
    row: SessionRowModel,
    style: SessionsStyle,
    metaStyle: TextStyle,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        row.metaSegments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(text = META_SEPARATOR, style = metaStyle, color = style.meta)
            }
            val isStatus = segment.text == row.session.statusWord()
            Text(
                text = segment.text,
                style = metaStyle,
                color = when {
                    isStatus -> style.statusWord
                    segment.emphasis == MetaEmphasis.AGENT_BLUE -> style.agentBlue
                    else -> style.meta
                },
                maxLines = 1,
                modifier = if (isStatus) {
                    Modifier.testTag(sessionsStatusWordTag(row.session.id))
                } else {
                    Modifier
                },
            )
        }
    }
}

/** The design's meta separator. */
internal const val META_SEPARATOR = "·"

/**
 * A row whose background reveals full-bleed `Rename` / `Delete` actions when swiped left.
 *
 * Hand-rolled rather than Material's `SwipeToDismissBox`, because that DISMISSES the row and this
 * design REVEALS actions and keeps the row. The drag clamps to the actions' combined width and
 * snaps back on release past the halfway point, so a short accidental swipe does not leave the row
 * stuck open.
 *
 * The action fills are the design's `renameBg` / `deleteBg` -- full-bleed rectangles reaching the
 * card edge, not rounded pills -- and the labels are `text.primary`, which is the AA fix documented
 * on [swipeActionLabelColor].
 */
@Composable
private fun SwipeActionRow(
    style: SessionsStyle,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val labelColor = MaterialTheme.openCode.swipeActionLabelColor()
    val labelStyle = MaterialTheme.openCode.typography.textStyle(TypeRole.LABEL)
    var offsetX by remember { mutableFloatStateOf(0f) }
    // How far the row can travel: the combined width of the two revealed actions.
    val actionsWidthPx = with(LocalDensity.current) { (SwipeActionWidth * 2).toPx() }
    // The actions are COMPOSED ONLY WHILE THE ROW IS REVEALED, not painted behind every row at
    // all times. That is an accessibility decision, not an optimisation: with them always in the
    // tree, a screen reader on a five-row list announces five "Rename" and five "Delete" buttons
    // for actions the user has not revealed, and TalkBack's swipe gesture would land on an
    // invisible button. A row the user has not swiped has no actions, so it has no action nodes.
    val revealed = offsetX < 0f

    Box(modifier = Modifier.fillMaxWidth()) {
        if (revealed) {
            // The actions are anchored to the END of the row as a single strip exactly as wide as
            // the maximum reveal, because the content slides LEFT: the vacated strip IS the
            // trailing one. Anchoring each action independently (or spacing them to the two
            // edges) leaves the leading action under the opaque content and unreachable.
            Row(
                modifier = Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.End,
            ) {
                SwipeActionButton(
                    label = RENAME_LABEL,
                    labelStyle = labelStyle,
                    fill = style.renameFill,
                    labelColor = labelColor,
                    tag = SESSIONS_RENAME_TAG,
                    onClick = { onRename(); offsetX = 0f },
                    modifier = Modifier.width(SwipeActionWidth).fillMaxHeight(),
                )
                SwipeActionButton(
                    label = DELETE_LABEL,
                    labelStyle = labelStyle,
                    fill = style.deleteFill,
                    labelColor = labelColor,
                    tag = SESSIONS_DELETE_TAG,
                    onClick = { onDelete(); offsetX = 0f },
                    modifier = Modifier.width(SwipeActionWidth).fillMaxHeight(),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            offsetX = if (offsetX < -actionsWidthPx / 2f) -actionsWidthPx else 0f
                        },
                        onDragCancel = { offsetX = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount).coerceIn(-actionsWidthPx, 0f)
                    }
                },
        ) { content() }
    }
}

/** One full-bleed swipe action. Rectangle, not a pill: the design says so explicitly. */
@Composable
private fun SwipeActionButton(
    label: String,
    labelStyle: TextStyle,
    fill: Color,
    labelColor: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(fill).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = labelStyle,
            color = labelColor,
            maxLines = 1,
        )
    }
}

/** The design's swipe action labels. */
internal const val RENAME_LABEL = "Rename"

/** The design's swipe action labels. */
internal const val DELETE_LABEL = "Delete"

// --- FAB ----------------------------------------------------------------------------------------

/** The `+ New session` capsule FAB, bottom-right. The screen's ONE solid-accent action. */
@Composable
private fun NewSessionFab(
    style: SessionsStyle,
    labelStyle: TextStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // M3's FAB ships with 6dp of tonal elevation and a shadow layer; both stripped,
            // because `elevation.useShadows` is false and depth here is a hairline plus a lift.
            .noDropShadow()
            .testTag(SESSIONS_FAB_TAG)
            .background(style.fabFill, RoundedCornerShape(CapsuleRadius))
            .clickable(onClick = onClick)
            .height(style.fabHeight)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PlusGlyph, contentDescription = null, tint = style.fabLabel, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = NEW_SESSION_LABEL, style = labelStyle, color = style.fabLabel, maxLines = 1)
        }
    }
}

/** The design's FAB label. */
internal const val NEW_SESSION_LABEL = "New session"

// --- metrics ------------------------------------------------------------------------------------

/** Minimum touch target, from spec §7.5 -- the same value the bottom bar is held to. */
private val MinTouchTarget: Dp = 48.dp

/** The status dot's diameter. */
private val StatusDotSize: Dp = 8.dp

/** How far one nesting level indents a row. */
private val NestedIndent: Dp = 16.dp

/** The design's search field: `surface` fill, ~12dp radius. */
private val SearchRadius: Dp = 12.dp

/** The search field's height: comfortably over the 48dp touch target once padded. */
private val SearchHeight: Dp = 46.dp

/** Full-capsule radius, the design's shape for pills and primary buttons. */
private val CapsuleRadius: Dp = 50.dp

/** One swipe action's width: wide enough for `Rename` at 12sp, half the reveal. */
private val SwipeActionWidth: Dp = 104.dp

/** FAB height plus one screen margin: what the list's bottom padding must clear. */
private val FAB_TOTAL_HEIGHT: Dp = 53.dp

/** The honest label for a session the server gave no title. Never a fake name. */
internal const val UNTITLED_SESSION = "(untitled session)"

// --- previews ------------------------------------------------------------------------------------

private val previewParent = SessionInfo(
    id = "ses_root",
    title = "Add a keystore-backed session switcher",
    projectID = "prj_1",
    location = Location(directory = "/home/musa/Projects/OpencodeMobile"),
    time = SessionTime(created = 1_789_755_400_251L, updated = 1_789_755_904_855L),
)

private val previewChild = SessionInfo(
    id = "ses_sub",
    title = "Re-review Task 4 fix round 1",
    parentID = "ses_root",
    projectID = "prj_1",
    location = Location(directory = "/home/musa/Projects/OpencodeMobile"),
    time = SessionTime(created = 1_789_755_278_950L, updated = 1_789_755_292_151L),
)

@Preview(showBackground = true, name = "sessions")
@Composable
private fun SessionsPreview() {
    OpenCodeMobileTheme {
        SessionsScreen(
            roots = listOf(SessionNode(previewParent, listOf(SessionNode(previewChild, emptyList())))),
            nowMillis = 1_789_755_904_855L,
        )
    }
}
