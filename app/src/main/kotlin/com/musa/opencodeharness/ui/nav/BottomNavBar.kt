package com.musa.opencodeharness.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musa.opencodeharness.ui.theme.Hairline
import com.musa.opencodeharness.ui.theme.TypeRole
import com.musa.opencodeharness.ui.theme.noDropShadow
import com.musa.opencodeharness.ui.theme.openCode

/** Test hook. The visible label is unique, so these exist for bounds rather than identity. */
internal const val NAV_BAR_TAG = "nav:bar"

internal fun navBarTestTag(tab: NavTab) = "nav:tab:${tab.route}"

/**
 * The bottom bar: six destinations, icon over label.
 *
 * ## How it is styled, and what it refuses to inherit
 *
 *  - **Colours** come from [NavBarStyle], which is built out of design tokens. In particular the
 *    inactive colour is `text.disabled` and NOT `colorScheme.disabled`: Material 3 1.4 exposes no
 *    such slot (`darkColorScheme()` does not take the disabled roles, and `ColorScheme`'s
 *    constructor and `copy` are internal), so the design token is the only place this colour can
 *    come from.
 *  - **No drop shadow.** `tonalElevation` is pinned to zero, and `noDropShadow()` strips
 *    anything a caller adds, because `elevation.useShadows` is false in the design and M3's
 *    `NavigationBar` tints itself by elevation out of the box. The only depth cue is the
 *    [Hairline] above the bar -- 1dp of
 *    `elevation.dividerColor` -- over a surface lifted one step from the page.
 *  - **Type** is the `label` role (12sp, weight 500), passed explicitly rather than inherited from
 *    `labelMedium`, so the bar renders at the token the JSON declares.
 *  - **Labels never ellipsize.** `maxLines = 1` with `TextOverflow.Clip` on a `Text` whose
 *    `softWrap` is off: if a label cannot fit it is visibly cut, not silently turned into an
 *    ellipsis that hides which destination it is. `TabSlotWidthTest` asserts every label fits at
 *    393dp so the clip is a guard rather than the normal case.
 */
@Composable
fun BottomNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.openCode
    val style = theme.navBarStyle()
    val labelStyle = theme.typography.textStyle(TypeRole.LABEL)

    Column(modifier = modifier.fillMaxWidth().testTag(NAV_BAR_TAG)) {
        Hairline(color = style.hairline)
        NavigationBar(
            containerColor = style.container,
            contentColor = theme.color.text.primary,
            // Both pinned: M3 tints a NavigationBar by tonal elevation, which is a colour the
            // design does not contain.
            tonalElevation = 0.dp,
            modifier = Modifier.noDropShadow().navigationBarsPadding(),
        ) {
            for (tab in NavTab.order) {
                val isSelected = tab == selected
                val content = style.contentColor(isSelected)
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            // The label below is the accessible name; naming the icon too would
                            // read "Chat, Chat" to a screen reader.
                            contentDescription = null,
                            modifier = Modifier.size(NavIconSize),
                        )
                    },
                    label = {
                        Text(
                            text = tab.label,
                            style = labelStyle,
                            color = content,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                        )
                    },
                    // Without this the label only appears on the selected tab, which on a six-tab
                    // bar means five destinations are unlabelled until you tap them.
                    alwaysShowLabel = true,
                    colors = tabColors(style, isSelected),
                    modifier = Modifier
                        .testTag(navBarTestTag(tab))
                        .align(Alignment.CenterVertically),
                )
            }
        }
    }
}

/**
 * The item colours, from the resolved style.
 *
 * The one function that turns [NavBarStyle] into Material's slots, so no call site can reach for a
 * scheme default by accident -- the failure mode this file exists to prevent.
 */
@Composable
private fun tabColors(style: NavBarStyle, isSelected: Boolean): NavigationBarItemColors =
    NavigationBarItemDefaults.colors(
        selectedIconColor = style.active,
        selectedTextColor = style.active,
        indicatorColor = style.activeIndicator,
        unselectedIconColor = style.inactive,
        unselectedTextColor = style.inactive,
        disabledIconColor = style.inactive,
        disabledTextColor = style.inactive,
    )
