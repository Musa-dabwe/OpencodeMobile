package com.musa.opencodeharness.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import com.musa.opencodeharness.ui.theme.ThemeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The bar's colours and depth, read off the pixels it actually paints.
 *
 * `NavBarStyleTest` proves the values are the right tokens. This proves the tokens reach the
 * screen: the bitmap is the real view hierarchy drawn through Skia, so a colour found in it is a
 * colour a user would see. Between the two, "the bar is styled from tokens" stops being a claim
 * about a data class and becomes a claim about pixels.
 *
 * `captureToImage()` is deliberately not used -- it goes through PixelCopy, which needs a live
 * SurfaceFlinger and times out under Robolectric. See `NavTestHarness.snapshot`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = BASELINE_QUALIFIERS)
class NavBarPixelTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val theme = ThemeFixtures.theme
    private val accent = theme.color.accent.base.toArgbInt()
    private val disabled = theme.color.text.disabled.toArgbInt()
    private val container = theme.color.background.raised.toArgbInt()
    private val page = theme.color.background.base.toArgbInt()
    private val hairline = theme.elevation.dividerColor.toArgbInt()
    private val pill = theme.color.accent.muted.toArgbInt()

    /**
     * The mutation this catches: the inactive colour reaching for `colorScheme.onSurfaceVariant`,
     * or the active one reaching for an M3 default, or either being applied to the icon but not
     * the label. The colours are read per tab column, so swapping two tabs' colours fails too.
     */
    @Test
    fun theSelectedTabPaintsTheAccentAndEveryOtherTabPaintsTextDisabled() {
        rule.setContent { Shell(start = NavTab.FILES) }
        rule.waitForIdle()

        val bitmap = rule.snapshot()
        val band = bitmap.barBand(hairline)

        for ((slot, tab) in NavTab.order.withIndex()) {
            val (left, right) = bitmap.tabColumn(slot, NavTab.order.size)
            val colours = bitmap.coloursIn(left, band.first, right, band.last + 1)

            if (tab == NavTab.FILES) {
                assertTrue(
                    "${tab.label} is selected but no #${Integer.toHexString(accent)} pixel was " +
                        "painted: $colours",
                    accent in colours,
                )
                assertTrue(
                    "the selected tab's pill is not the muted accent: $colours",
                    pill in colours,
                )
                assertFalse(
                    "${tab.label} is selected but is painted in the inactive colour",
                    disabled in colours,
                )
            } else {
                assertTrue(
                    "${tab.label} is inactive but no #${Integer.toHexString(disabled)} pixel was " +
                        "painted: $colours",
                    disabled in colours,
                )
                assertFalse(
                    "${tab.label} is inactive but is painted in the accent: $colours",
                    accent in colours,
                )
            }
        }
    }

    /**
     * The mutation this catches: `tonalElevation` left at a Material default, or `containerColor`
     * swapped from `background.raised` to `background.base`.
     *
     * Material 3 tints a `NavigationBar` by tonal elevation, which would repaint EVERY pixel of the
     * bar with a colour the design does not contain -- so the assertion is on the raw pixels of the
     * row directly beneath the hairline, across the full width, rather than on a colour lookup.
     *
     * ## What this test deliberately does not claim
     *
     * It used to assert "no drop shadow above the bar" by sampling the page. That was deleted: under
     * Robolectric a `Modifier.shadow` draws nothing at all, so adding an 8dp shadow to the bar
     * left every pixel identical and the test passed either way -- a test that cannot fail. The
     * no-shadow guarantee is still enforced structurally (`tonalElevation = 0.dp`, `noDropShadow()`,
     * and `NavBarStyleTest.theDesignForbidsShadowsSoTheBarMustNotClaimAny`) and is checked for real
     * on the device screenshot, where the compositor does draw shadows. See the Task 2 report.
     */
    @Test
    fun theBarIsPaintedInTheContainerTokenWithNoTonalOverlay() {
        rule.setContent { Shell(blank = true) }
        rule.waitForIdle()

        val bitmap = rule.snapshot()
        val barTop = bitmap.findHairlineRow(hairline)
        assertTrue("the bar's hairline was not found, so nothing could be measured", barTop > 0)

        // Measured from BELOW the divider, whose 1dp is two pixels at xhdpi: sampling a hairline
        // row would measure the hairline and call it the bar.
        val firstRow = barTop + bitmap.hairlineThicknessBelow(barTop)
        val sampled = buildList {
            for (row in listOf(firstRow, firstRow + 1)) {
                for (step in 0 until 30) {
                    add(bitmap.getPixel(bitmap.width * step / 30, row))
                }
            }
        }
        assertEquals(
            "the bar is not uniformly its own container token, so something is tinting it",
            setOf(container),
            sampled.toSet(),
        )

        // And the surface it meets: page token above the bar, container token below the hairline.
        assertEquals(
            "the bar does not meet the page cleanly",
            setOf(page),
            bitmap.coloursIn(0, 0, bitmap.width, barTop),
        )
    }

    /**
     * The mutation this catches: a glyph whose path data failed to parse and renders blank, an
     * icon that lost its tint and fell back to the bar's container colour, or a label whose
     * colour was applied to the icon but not the text (or the other way round).
     *
     * The icon and the label are two separate runs of the tab's own colour, stacked with a gap
     * between them, so "both painted" is a shape in the bitmap rather than an assumption about
     * where Material puts them.
     */
    @Test
    fun everyTabPaintsBothItsIconAndItsLabelInTheTokenColour() {
        rule.setContent { Shell(start = NavTab.PROJECTS) }
        rule.waitForIdle()

        val bitmap = rule.snapshot()
        val band = bitmap.barBand(hairline)

        for ((slot, tab) in NavTab.order.withIndex()) {
            val (left, right) = bitmap.tabColumn(slot, NavTab.order.size)
            val expected = if (tab == NavTab.PROJECTS) accent else disabled
            val paintedRows = (band.first..band.last)
                .filter { row -> expected in bitmap.coloursIn(left, row, right, row + 1) }
            val runs = paintedRows.consecutiveRuns()

            assertTrue(
                "${tab.label} painted #${Integer.toHexString(expected)} in " +
                    "${runs.size} block(s) of rows $runs; an icon and a label are both required, " +
                    "so one block means the glyph is blank",
                runs.size >= 2,
            )
        }
    }

    @Composable
    private fun Shell(start: NavTab = NavTab.CHAT, blank: Boolean = false) {
        OpenCodeMobileTheme {
            var tab by rememberSaveable { mutableStateOf(start) }
            AppShell(selected = tab, onSelect = { tab = it }) { destination ->
                when {
                    blank || destination == NavTab.CHAT -> Box(Modifier.fillMaxSize())
                    else -> PlaceholderDestination(destination)
                }
            }
        }
    }
}
