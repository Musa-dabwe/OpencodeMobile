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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.data.SessionNode
import com.musa.opencodeharness.data.runtime.setup.SetupStep
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.ui.theme.Hairline
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme

/**
 * The whole app, top to bottom: first-run progress, the live log, the connection status, and the
 * session list.
 *
 * ## Why setup lives here rather than on its own screen
 *
 * The plan offered a Setup screen or "drive setup from the existing screen and show progress",
 * and explicitly allowed the smaller option if justified. Taken, because:
 *
 *  - the flow is a straight line (resolve -> download -> install -> start -> health) with no
 *    choice for the user to make, so a separate screen would be a screen with nothing to
 *    navigate to and nothing to go back to;
 *  - navigation -- a nav host, routes, a bottom bar -- is an explicit non-goal for this phase,
 *    so a second screen would first require building the machinery that is deferred;
 *  - one screen means one state machine. "Setup finished" and "sessions are loaded" are the
 *    same object, so the setup-completed-but-list-stale bug has nowhere to live;
 *  - it is the same code path after a process death. Resuming is just the flow re-running with
 *    the per-artifact work already skipped, so resume needs no separate screen to appear from.
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
        ConnectionHeader(state.connection, state.serverUrl, onRetry)
        Hairline()

        // Death outranks everything else. A server that has exited must never leave the word
        // "Connected" on screen, so its reason is pinned above whatever else is being shown.
        state.serverDeath?.let { death ->
            Text(
                text = "Server stopped: $death",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.isSettingUp) SetupProgress(state.setup)

        // The live log stays mounted even when healthy: the whole point is that a failure
        // minutes later is still diagnosable. It is collapsed to the tail, not to nothing.
        LogTail(state.logLines)

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
private fun ConnectionHeader(
    connection: ConnectionState,
    serverUrl: String?,
    onRetry: () -> Unit,
) {
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
        // host:port only. The password is never rendered, by ServerEndpoint's second invariant.
        if (serverUrl != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = serverUrl.removePrefix("http://"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * First-run progress. One line, not a spinner stack: the operation is a queue of known artifacts
 * and the interesting thing is WHICH one and how far through, which a percentage alone hides.
 */
@Composable
private fun SetupProgress(step: SetupStep) {
    val text = when (step) {
        SetupStep.Idle -> "Starting up…"
        is SetupStep.Resolving -> "Checking the latest OpenCode release…"
        is SetupStep.Downloading ->
            "Downloading ${step.artifactId} (${step.index}/${step.total})…"
        is SetupStep.Installing ->
            "Installing ${step.artifactId} (${step.index}/${step.total})…"
        SetupStep.StartingServer -> "Starting the OpenCode server…"
        is SetupStep.AwaitingHealth -> "Waiting for the server to answer (attempt ${step.attempt})…"
        SetupStep.Ready -> "Server ready"
        is SetupStep.Failed -> "Setup failed: ${step.reason}"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        if (step !is SetupStep.Ready && step !is SetupStep.Failed) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The tail of the runtime log, newest last.
 *
 * Read-only and already redacted upstream ([com.musa.opencodeharness.data.runtime.launch.LogBuffer]
 * redacts before storing, and the setup layer scrubs its own lines), so this composable has no
 * filtering to bypass and no secret to handle.
 */
@Composable
private fun LogTail(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = "log",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.takeLast(MAX_LOG_LINES).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bounded in the view as well as in LogBuffer: this recomposes on every state emission. */
private const val MAX_LOG_LINES = 200

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
            Hairline()
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
 * one line, has no UI in it, and can be asserted directly. See `SubAgentMarkerTest`.
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
    OpenCodeMobileTheme {
        SessionListScreen(
            state = SessionListUiState(
                connection = ConnectionState.Healthy,
                roots = listOf(
                    SessionNode(
                        previewRoot,
                        listOf(
                            SessionNode(previewSubAgent, emptyList()),
                            SessionNode(previewOrphan, emptyList()),
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
    OpenCodeMobileTheme {
        SessionListScreen(
            state = SessionListUiState(connection = ConnectionState.Healthy),
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "unreachable")
@Composable
private fun UnreachablePreview() {
    OpenCodeMobileTheme {
        SessionListScreen(
            state = SessionListUiState(
                connection = ConnectionState.Unreachable("Could not reach the server (ConnectException)."),
            ),
            onRetry = {},
        )
    }
}
