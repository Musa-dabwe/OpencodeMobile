package com.musa.opencodeharness.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.ui.theme.LocalOpenCodeTheme
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import com.musa.opencodeharness.ui.theme.TypeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Six tabs on a 393dp baseline: does every label fit, and does every item stay tappable?
 *
 * The plan flags this as the phase's stated risk ("six tabs may not fit ... decide early, record
 * the decision"), so it is measured rather than eyeballed. Two independent measurements are taken
 * and compared:
 *
 *  1. the **intrinsic** width of each label, laid out by the same Compose text stack the bar uses
 *     at the `label` token's style, and
 *  2. the width the label is actually **given** in the running layout.
 *
 * If (1) and (2) agree, nothing was clipped and no ellipsis was substituted; if (2) is smaller,
 * the label does not fit and the bar is shipping truncated destinations. Comparing them is what
 * makes this a measurement rather than a restatement of the slot arithmetic.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = BASELINE_QUALIFIERS)
class TabFitTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Half a dp: the rounding tolerance between two layouts of the same string. */
    private val tolerance = 0.5f

    @Test
    fun everyTabLabelFitsItsSlotAtThe393DpBaseline() {
        val intrinsic = measureLabels()
        val evenSplit = BASELINE_WIDTH_DP.toFloat() / NavTab.order.size
        val barWidth = rule.onNodeWithTag(NAV_BAR_TAG).getUnclippedBoundsInRoot().widthDp().value
        println("TABFIT bar=${"%.1f".format(barWidth)}dp  naive-sixth=${"%.1f".format(evenSplit)}dp")

        val rows = NavTab.order.map { tab ->
            val text = intrinsic.getValue(tab.label)
            // useUnmergedTree: the NavigationBarItem merges its label into its own semantics node,
            // so an unmerged lookup returns the ITEM's bounds and would report the slot as the
            // label width -- an assertion that could never fail.
            val rendered = rule
                .onNodeWithText(tab.label, useUnmergedTree = true)
                .getUnclippedBoundsInRoot().widthDp().value
            val item = rule.onNodeWithTag(navBarTestTag(tab)).getUnclippedBoundsInRoot()
            Triple(tab, text to rendered, item)
        }
        // Measured for all six first, asserted second: a failure on label three should still leave
        // the whole table in the test output rather than stopping at the first bad row.
        for ((tab, measured, item) in rows) {
            val (text, rendered) = measured
            println(
                "TABFIT label=${tab.label.padEnd(8)} intrinsic=${"%.1f".format(text)}dp " +
                    "rendered=${"%.1f".format(rendered)}dp " +
                    "item=${"%.1f".format(item.widthDp().value)}x" +
                    "${"%.1f".format(item.heightDp().value)}dp " +
                    "headroom=${"%.1f".format(item.widthDp().value - text)}dp " +
                    "x=${"%.1f".format(item.left.value)}..${"%.1f".format(item.right.value)}dp",
            )
        }

        for ((tab, measured, item) in rows) {
            val (text, rendered) = measured
            assertTrue(
                "\"${tab.label}\" needs ${"%.1f".format(text)}dp but its item is only " +
                    "${"%.1f".format(item.widthDp().value)}dp wide: it would truncate, so the " +
                    "label has to be shortened rather than the type shrunk",
                text <= item.widthDp().value,
            )
            assertEquals(
                "\"${tab.label}\" is laid out narrower (${"%.1f".format(rendered)}dp) than it " +
                    "needs (${"%.1f".format(text)}dp), so it was clipped or ellipsized",
                text,
                rendered,
                tolerance,
            )
        }
    }

    /**
     * The mutation this catches: an item smaller than the spec section 7.5 floor, which on a
     * six-item bar is the difference between a comfortable tap and a mis-tap.
     */
    @Test
    fun everyTabItemMeetsThe48DpTouchTarget() {
        measureLabels()
        for (tab in NavTab.order) {
            val item = rule.onNodeWithTag(navBarTestTag(tab)).getUnclippedBoundsInRoot()
            println(
                "TOUCH ${tab.name} ${"%.1f".format(item.widthDp().value)} x " +
                    "${"%.1f".format(item.heightDp().value)} dp",
            )
            assertTrue(
                "${tab.label} is only ${item.widthDp().value}x${item.heightDp().value}dp, below the " +
                    "48dp minimum touch target",
                item.widthDp() >= MinTouchTarget && item.heightDp() >= MinTouchTarget,
            )
        }
    }

    /** Intrinsic width of every label, in dp, at the `label` token's style. */
    private fun measureLabels(): Map<String, Float> {
        lateinit var widths: Map<String, Float>
        rule.setContent {
            OpenCodeMobileTheme {
                val theme = LocalOpenCodeTheme.current
                val measurer = rememberTextMeasurer()
                val style = theme.typography.textStyle(TypeRole.LABEL)
                val density = LocalDensity.current.density
                widths = NavTab.order.associate { tab ->
                    // Pixels from the same text stack the bar lays out with, divided by the same
                    // density: dp, without a rounding step in the middle.
                    tab.label to (measurer.measure(tab.label, style).size.width / density)
                }
                Shell()
            }
        }
        rule.waitForIdle()
        return widths
    }

    @Composable
    private fun Shell() {
        OpenCodeMobileTheme {
            var tab by rememberSaveable { mutableStateOf(NavTab.CHAT) }
            AppShell(selected = tab, onSelect = { tab = it }) { destination ->
                // Chat renders blank here: a placeholder heading repeats the bar's own label and
                // would make "find the label in the tree" ambiguous.
                if (destination == NavTab.CHAT) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize())
                } else {
                    PlaceholderDestination(destination)
                }
            }
        }
    }
}
