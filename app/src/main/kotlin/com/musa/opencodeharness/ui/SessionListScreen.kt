package com.musa.opencodeharness.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.data.SessionNode
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.ui.theme.ComposeEmptyActivityTheme

/**
 * The Phase 1 vertical slice, top to bottom: connection status, then the session list.
 *
 * Stateless on purpose -- it takes a [SessionListUiState] and an `onRetry` callback, and holds no
 * coroutine scope, no client and no remembered mutable state. The ViewModel owns the load, and
 * this recomposes from whatever it published.
 */
@Composable
fun SessionListScreen(
    state: SessionListUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ConnectionHeader(state.connection, onRetry)
        HorizontalDivider()

        // All four states the slice promises to render, keyed on the state itself:
        //   connecting | unreachable | healthy-with-sessions | healthy-and-empty.
        when (val connection = state.connection) {
            ConnectionState.Connecting -> Centred {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Connecting to the OpenCode server…")
            }

            is ConnectionState.Unreachable -> Centred {
                Text(
                    text = connection.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }

            ConnectionState.Healthy ->
                if (state.isEmptyResult) {
                    Centred {
                        Text(
                            text = "Connected. No sessions on the server yet.",
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    SessionList(state.roots)
                }
        }
    }
}

@Composable
private fun ConnectionHeader(connection: ConnectionState, onRetry: () -> Unit) {
    val (label, color) = when (connection) {
        ConnectionState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.secondary
        ConnectionState.Healthy -> "Connected" to MaterialTheme.colorScheme.primary
        is ConnectionState.Unreachable -> "Not connected" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

/**
 * The list body.
 *
 * The tree is flattened depth-first into rows carrying their depth, which is what produces the
 * indentation. Keys are session ids: stable, unique per the `buildSessionTree` invariant that
 * every session appears exactly once, and stable across reloads so a row keeps its scroll
 * position and its composed children instead of being rebuilt on every list emission.
 */
@Composable
private fun SessionList(roots: List<SessionNode>) {
    val rows = roots.toRows()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = rows, key = { it.session.id }) { row ->
            SessionRow(session = row.session, depth = row.depth)
            HorizontalDivider()
        }
    }
}

@Composable
private fun SessionRow(session: SessionInfo, depth: Int) {
    // THE sub-agent marker. See [isSubAgentSession] for why this is `parentID != null` and NOT
    // `children.isEmpty()` -- the two disagree for every childless sub-agent.
    val isSubAgent = session.isSubAgentSession

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Indent by depth, so a sub-agent reads as nested under its parent.
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = session.title?.takeIf { it.isNotBlank() } ?: "(untitled session)",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = if (isSubAgent) {
                // Showing the parent id is what makes a promoted orphan legible: its parent is
                // not in the list, and without the id the row would look merely misplaced.
                "└ sub-agent of ${session.parentID}"
            } else {
                "• session"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether this session was spawned by a parent session (a sub-agent) or opened at top level.
 *
 * THE single source of truth for the child/parent marker, and the reason the marker is not
 * derived from the tree at all:
 *
 *  - `buildSessionTree` groups on `parentID`, so a non-null `parentID` IS the server saying
 *    "I am a sub-agent of that session". That is the whole signal.
 *  - `SessionNode.children` answers a different question -- what hangs off THIS session -- and is
 *    empty for most sessions, sub-agents included. Substituting it inverts the marker: on the
 *    recorded 50-session capture, `children.isEmpty()` holds for 47 of 50 sessions, so it would
 *    call all 34 sub-agents AND 13 genuine childless roots "sub-agent", while the 3 sessions that
 *    actually have sub-agents would stop reading as parents.
 *  - The two also diverge for a promoted orphan: a session whose `parentID` names an id absent
 *    from the list is deliberately promoted to the top level by `buildSessionTree` (never
 *    dropped), so it appears as a root with no children. It is still a sub-agent. Only
 *    `parentID` gets that right.
 *  - Measured on the real capture, the two rules disagree on 13 of 50 rows -- see
 *    `SubAgentMarkerTest`, which asserts that number so the two cannot be swapped later.
 *
 * Deliberately a property of the session record rather than of the flattened row, so the rule is
 * one line, has no UI in it, and can be asserted directly. See `SessionListScreenTest`.
 */
internal val SessionInfo.isSubAgentSession: Boolean get() = parentID != null

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** One rendered row: the session plus how deep it sits in the tree. */
private data class SessionRowData(val session: SessionInfo, val depth: Int)

/**
 * Depth-first flatten of the tree into rows, parents before their children.
 *
 * Order matches what the server returned (see `buildSessionTree`'s order rule), so the list
 * reads the same as the flat response would. A local function rather than a coroutine: this is
 * pure and bounded by the session count.
 */
private fun List<SessionNode>.toRows(): List<SessionRowData> {
    val rows = ArrayList<SessionRowData>()
    fun walk(nodes: List<SessionNode>, depth: Int) {
        for (node in nodes) {
            rows += SessionRowData(node.session, depth)
            walk(node.children, depth + 1)
        }
    }
    walk(this, 0)
    return rows
}

// --- previews ---------------------------------------------------------------------------------

private val previewRoot = SessionInfo(id = "ses_root", title = "Add a keystore-backed session switcher")

private val previewSubAgent = SessionInfo(
    id = "ses_sub",
    title = "sub-agent of the root",
    parentID = "ses_root",
)

/** Promoted orphan: a sub-agent whose parent is not in the list. Must still read as a sub-agent. */
private val previewOrphan = SessionInfo(
    id = "ses_orphan",
    title = "orphan (parent is on another page)",
    parentID = "ses_absent",
)

@Preview(showBackground = true, name = "connected")
@Composable
private fun ConnectedPreview() {
    ComposeEmptyActivityTheme {
        SessionListScreen(
            state = SessionListUiState(
                connection = ConnectionState.Healthy,
                roots = listOf(
                    com.musa.opencodeharness.data.SessionNode(
                        previewRoot,
                        listOf(
                            com.musa.opencodeharness.data.SessionNode(previewSubAgent, emptyList()),
                            com.musa.opencodeharness.data.SessionNode(previewOrphan, emptyList()),
                        ),
                    ),
                ),
            ),
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "empty")
@Composable
private fun EmptyPreview() {
    ComposeEmptyActivityTheme {
        SessionListScreen(
            state = SessionListUiState(connection = ConnectionState.Healthy),
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "unreachable")
@Composable
private fun UnreachablePreview() {
    ComposeEmptyActivityTheme {
        SessionListScreen(
            state = SessionListUiState(
                connection = ConnectionState.Unreachable("Could not reach the server (ConnectException)."),
            ),
            onRetry = {},
        )
    }
}
