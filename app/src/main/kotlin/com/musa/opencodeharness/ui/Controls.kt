package com.musa.opencodeharness.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics

/**
 * Wires a control, or marks it disabled -- never a click that goes nowhere.
 *
 * ## Why `null` and not `{}`
 *
 * A defaulted `{}` callback is indistinguishable from a wired one at the call site, which is how the
 * shipped Sessions screen ended up with a solid-accent FAB that did nothing: the wrapper called
 * `SessionsScreen(roots, modifier)` and every action silently became a no-op. `null` means "this
 * action does not exist yet", and it produces a visibly inert control instead.
 *
 * `mergeDescendants = true` keeps the child's text on the same semantics node, so a disabled control
 * is still announced as "Appearance" rather than as an unlabelled box. The caller is responsible
 * for pairing this with a disabled PAINT -- a control that is marked disabled but still wears accent
 * is worse than one that looks live and fails.
 *
 * Lived in `ui/sessions/SessionsScreen.kt` during Task 3 and moved here when Settings needed the
 * same rule; one implementation, so the two screens cannot disagree about what "inert" means.
 */
internal fun Modifier.wiredOrDisabled(onClick: (() -> Unit)?): Modifier =
    if (onClick != null) {
        clickable(onClick = onClick)
    } else {
        semantics(mergeDescendants = true) { disabled() }
    }
