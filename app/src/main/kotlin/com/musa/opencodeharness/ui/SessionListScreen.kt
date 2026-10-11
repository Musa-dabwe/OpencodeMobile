package com.musa.opencodeharness.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.runtime.setup.SetupStep
import com.musa.opencodeharness.ui.sessions.SessionsScreen
import com.musa.opencodeharness.ui.theme.TypeRole
import com.musa.opencodeharness.ui.theme.openCode

/**
 * The Chat destination: the design's Sessions screen plus the runtime states that surround it.
 *
 * ## Why this file still exists after the Task 3 rewrite
 *
 * Task 3 replaced the PRESENTATION of the session list -- header, search, sectioned rows, swipe
 * actions, FAB -- with the design. It did not remove any of the runtime states that screen was
 * also carrying, because those are real and dropping them would be a regression, not a cleanup:
 *
 *  - **First-run setup progress.** `SetupStep` is where a download/install/start actually is. The
 *    design's Sessions page has no progress bar because a designed product is already installed;
 *    this app is not, and hiding the progress would make a 40-second silent wait.
 *  - **The server-death banner.** A server that exited must never leave "Connected" on screen.
 *  - **Connecting / unreachable / healthy-empty.** The design has no error state; an app that
 *    cannot reach its own server must say so rather than render an empty list.
 *
 * Those states are rendered here in the design's tokens (raised surface, hairline, secondary text)
 * and the session list itself is [SessionsScreen]. Keeping the wrapper means the ViewModel, its
 * tests (`RuntimeWiringTest`, `SessionListViewModelTest`) and the state model are all untouched.
 *
 * Stateless on purpose -- it takes a [SessionListUiState] and an `onRetry` callback, and holds no
 * coroutine scope, no client and no remembered mutable state. The ViewModel owns the load.
 */
@Composable
fun SessionListScreen(
    state: SessionListUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.openCode

    Column(modifier = modifier.fillMaxSize()) {
        // Death outranks everything else. A server that has exited must never leave a session
        // list -- or any other live-looking state -- above the reason it stopped.
        state.serverDeath?.let { death ->
            Text(
                text = "Server stopped: $death",
                style = theme.typography.textStyle(TypeRole.META),
                color = theme.color.status.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.isSettingUp) SetupProgress(state.setup, modifier = Modifier.fillMaxWidth())

        when (val connection = state.connection) {
            ConnectionState.Connecting -> StatusPane("Connecting to the OpenCode server…")

            is ConnectionState.Unreachable -> StatusPane(
                primary = connection.reason,
                action = onRetry,
            )

            ConnectionState.Healthy -> {
                if (state.isEmptyResult) {
                    // Honest emptiness: the server answered and there is nothing there. This is
                    // distinct from "still loading" and from "failed", and it names no count,
                    // because no count is known.
                    StatusPane("Connected. No sessions on the server yet.")
                } else {
                    SessionsScreen(roots = state.roots, modifier = Modifier.weight(1f))
                }
            }
        }

        // The live log stays mounted: the whole point is that a failure minutes later is still
        // diagnosable. It is collapsed to the tail, not to nothing. Read-only and already
        // redacted upstream, so this composable has no secret to handle.
        LogTail(state.logLines, modifier = Modifier.fillMaxWidth())
    }
}

/** A centred, non-interactive state message. Never a spinner over an invented empty list. */
@Composable
private fun StatusPane(primary: String, action: (() -> Unit)? = null) {
    val theme = MaterialTheme.openCode
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = primary,
            style = theme.typography.textStyle(TypeRole.BODY),
            color = theme.color.text.secondary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Retry",
                style = theme.typography.textStyle(TypeRole.LABEL),
                color = theme.color.accent.base,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .retryTap(action),
            )
        }
    }
}

// The accent-coloured Retry. `indication = null` because this design has no ripple colour; the
// accent text itself is the affordance, matching the scope pill's treatment.
private fun Modifier.retryTap(action: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, onClick = action)

/**
 * First-run progress: one line naming WHICH artifact and how far, not a spinner stack.
 *
 * The queue of known artifacts is what makes this useful; a percentage alone hides which thing
 * the app is waiting on.
 */
@Composable
private fun SetupProgress(step: SetupStep, modifier: Modifier = Modifier) {
    val theme = MaterialTheme.openCode
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
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = text, style = theme.typography.textStyle(TypeRole.META), color = theme.color.text.secondary)
        if (step !is SetupStep.Ready && step !is SetupStep.Failed) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = theme.color.accent.base,
                trackColor = theme.color.background.raised,
            )
        }
    }
}

/**
 * The tail of the runtime log, newest last.
 *
 * Already redacted upstream ([com.musa.opencodeharness.data.runtime.launch.LogBuffer] redacts
 * before storing, and the setup layer scrubs its own lines), so this has no filtering to bypass.
 *
 * ## Why it is bounded to a few VISIBLE lines and says how many are hidden
 *
 * A first run emits one "already installed, skipping" line per artifact, which is a dozen lines
 * before the user sees a single session -- on device that took roughly 30% of the screen. The log
 * is diagnostics, not content, so it gets a fixed budget. It is NOT silently truncated: when
 * lines are hidden the count is stated, because a log that quietly drops entries is the failure
 * mode the panel exists to prevent. [LogBuffer] still holds the full bounded tail; nothing is
 * lost, it is just not all on screen at once.
 *
 * Task 5 owns turning this into the design's real setup surface. Until then it stays honest and
 * out of the way.
 */
@Composable
private fun LogTail(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val theme = MaterialTheme.openCode
    val visible = lines.takeLast(VISIBLE_LOG_LINES)
    val hidden = lines.size - visible.size
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = if (hidden > 0) "log · $hidden earlier lines not shown" else "log",
            style = theme.typography.textStyle(TypeRole.SECTION_HEADER),
            color = theme.color.text.sectionHeader,
        )
        visible.forEach { line ->
            Text(
                text = line,
                style = theme.typography.textStyle(TypeRole.MONO),
                color = theme.color.text.secondary,
            )
        }
    }
}

/** How many log lines the screen shows at once. See [LogTail] for why this is not everything. */
private const val VISIBLE_LOG_LINES = 4

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
