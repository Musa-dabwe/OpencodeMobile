package com.musa.opencodeharness.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import com.musa.opencodeharness.ui.theme.TypeRole
import com.musa.opencodeharness.ui.theme.openCode

/**
 * The app shell: one screen, one bar, and a content slot that keeps each destination's state.
 *
 * ## The state problem this solves
 *
 * Without a state holder, switching tabs swaps the content slot's whole subtree, so everything
 * the outgoing destination remembered is unregistered: a `LazyColumn`'s scroll offset, a text
 * field's contents, any `rememberSaveable` in the tree. Coming back to Chat would show a
 * list that has scrolled itself back to the top, or -- because the ViewModel is Activity-scoped
 * and survives -- the same sessions at the wrong scroll position, which reads as a glitch.
 *
 * [rememberSaveableStateHolder] is the mechanism for exactly this: it holds one
 * `SaveableStateRegistry` per key, registers the key on entry and unregisters it on exit, and
 * replays the saved values when the key returns. So the loaded session list stays loaded and the
 * scroll stays where the user left it, while only the current destination stays composed.
 *
 * ## Why the holder is keyed on [NavTab.route] and not the enum
 *
 * The key has to survive the process being killed and rebuilt, so it cannot be an object identity.
 * `route` is a stable string that is also the tab's identity in the semantics tree, which keeps
 * one source of truth for "which destination is this".
 *
 * [selected] is deliberately *not* saved here. It is the caller's state (see `MainActivity`),
 * because where the app starts is a product decision, and a shell that silently restores its own
 * remembered tab would fight that decision.
 */
@Composable
fun AppShell(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
    destination: @Composable (NavTab) -> Unit,
) {
    val theme = MaterialTheme.openCode
    val stateHolder = rememberSaveableStateHolder()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = theme.color.background.base,
        contentColor = theme.color.text.primary,
        bottomBar = { BottomNavBar(selected = selected, onSelect = onSelect) },
    ) { padding ->
        // Registering under the route is what makes the state survive the switch: on the way out
        // the key's registry is kept, on the way back it is restored before the content composes.
        stateHolder.SaveableStateProvider(selected.route) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                destination(selected)
            }
        }
    }
}

/** Convenience for a destination that is not built: renders [PlaceholderDestination]. */
@Composable
fun NavTab.Placeholder(modifier: Modifier = Modifier) {
    PlaceholderDestination(tab = this, modifier = modifier)
}

/** The type role a destination heading uses. Exposed so screens do not each pick their own. */
internal val DestinationTitleRole: TypeRole = TypeRole.SCREEN_TITLE
