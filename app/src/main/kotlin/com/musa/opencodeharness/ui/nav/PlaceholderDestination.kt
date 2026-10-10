package com.musa.opencodeharness.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.ui.theme.TypeRole
import com.musa.opencodeharness.ui.theme.openCode

internal const val PLACEHOLDER_TAG = "placeholder"

/** Test hook: the eyebrow that says, in as many words, that nothing here is real. */
internal const val NOT_BUILT_LABEL = "NOT BUILT YET"

/** Test hook: the destination's own heading, which the bar's label of the same text would shadow. */
internal const val PLACEHOLDER_TITLE_TAG = "placeholder:title"

/**
 * The body copy for a destination that does not exist.
 *
 * There is exactly one version of this text, for every placeholder, and it names no feature that
 * is not shipped. It is a constant rather than a per-tab string precisely so that a future
 * destination cannot ship with a placeholder that promises something -- which is how a stub
 * turns into a lie.
 */
internal const val NOT_BUILT_BODY: String =
    "This destination has no screen yet. Nothing on it is real."

/**
 * An honest placeholder: the destination's name, a status line, and one sentence.
 *
 * ## What this composable is not allowed to do
 *
 * It does not render invented rows, an empty list that reads as "you have none", or a spinner
 * that reads as "loading". All three are the same failure -- a screen that shows the *shape* of
 * the real thing and lets the user infer the contents. The design's own rule generalises: no
 * value appears here that no token or real state backs, and `PlaceholderDestinationTest` asserts
 * the whole screen's text is exactly the three strings below.
 *
 * ## Why the copy is styled this way
 *
 * The eyebrow is `sectionHeader` (11sp, weight 600, wide tracking) in `text.sectionHeader`, which
 * is the role the design uses for status lines like `TODAY` and section labels. It is deliberately
 * NOT `text.disabled`: that token measures 2.65:1 against the page and is reserved for genuinely
 * non-interactive marks, and a user has to be able to read why the screen is empty. The
 * explanatory line is `body` in `text.secondary` at 6.6:1.
 *
 * Accent is not used anywhere on this screen. The design reserves accent for the single primary
 * action, and there is no action here -- colouring a placeholder warm would imply there is
 * something to act on.
 */
@Composable
fun PlaceholderDestination(
    tab: NavTab,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.openCode
    Scaffold(
        containerColor = theme.color.background.base,
        contentColor = theme.color.text.primary,
        modifier = modifier.fillMaxSize().testTag(PLACEHOLDER_TAG),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = theme.spacing.screenMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                // Decorative: the destination name is the heading directly beneath it.
                contentDescription = null,
                tint = theme.color.text.secondary,
                modifier = Modifier.size(32.dp),
            )
            Column(
                modifier = Modifier.padding(top = theme.spacing.rowGap * 2).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(theme.spacing.rowGap),
            ) {
                Text(
                    text = tab.label,
                    style = theme.typography.textStyle(TypeRole.SCREEN_TITLE),
                    color = theme.color.text.primary,
                    textAlign = TextAlign.Center,
                    // The bar already carries this label, so a node lookup by text would match two
                    // nodes. The heading gets its own tag so "this screen is called X" is a
                    // distinct assertion rather than a coincidence.
                    modifier = Modifier.testTag(PLACEHOLDER_TITLE_TAG),
                )
                Text(
                    text = NOT_BUILT_LABEL,
                    style = theme.typography.textStyle(theme.navBarStyle().eyebrow),
                    color = theme.color.text.sectionHeader,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = NOT_BUILT_BODY,
                    style = theme.typography.textStyle(TypeRole.BODY),
                    color = theme.color.text.secondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
