package com.musa.opencodeharness.ui.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.DpRect

/** The viewport the design PDF was measured at, in dp. Every layout measurement here uses it. */
internal const val BASELINE_WIDTH_DP = 393

/**
 * The Robolectric qualifier string for the tests that measure layout: the design's own baseline
 * width, and xhdpi so a 12sp label renders with strokes thick enough to sample exactly rather
 * than only as antialiased blends.
 */
internal const val BASELINE_QUALIFIERS = "w393dp-h720dp-xhdpi"

/**
 * Draws the real view hierarchy into a bitmap and hands back the pixels.
 *
 * `captureToImage()` is not used: under Robolectric it goes through PixelCopy, which needs a live
 * SurfaceFlinger and times out on a JVM. Drawing the decor view directly runs the same Skia
 * pipeline the device runs, so a colour read out of this bitmap is a colour the UI really paints --
 * which is the whole point of asserting on pixels rather than on a style object.
 */
internal fun <A : ComponentActivity> AndroidComposeTestRule<*, A>.snapshot(): Bitmap {
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(bitmap)) }
    return bitmap
}

/** Every distinct opaque colour in the bitmap, as packed ARGB ints. */
internal fun Bitmap.distinctColours(): Set<Int> {
    val seen = HashSet<Int>()
    for (y in 0 until height) {
        for (x in 0 until width) seen += getPixel(x, y)
    }
    return seen
}

/** Distinct opaque colours inside a pixel rectangle. */
internal fun Bitmap.coloursIn(left: Int, top: Int, right: Int, bottom: Int): Set<Int> {
    val seen = HashSet<Int>()
    for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
        for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) seen += getPixel(x, y)
    }
    return seen
}

/**
 * The topmost row that carries the design's hairline colour across most of its width.
 *
 * Used to locate the bar without hard-coding a height: the bar's top edge is the one place in the
 * app where a full-width 1dp `border.subtle` line is drawn, so finding it calibrates every
 * measurement below to the real layout instead of to an assumption about it.
 */
internal fun Bitmap.findHairlineRow(hairline: Int, minRunFraction: Double = 0.8): Int {
    val needed = (width * minRunFraction).toInt()
    for (y in 0 until height) {
        var run = 0
        var best = 0
        for (x in 0 until width) {
            run = if (getPixel(x, y) == hairline) run + 1 else 0
            if (run > best) best = run
        }
        if (best >= needed) return y
    }
    return -1
}

/**
 * How many consecutive rows below [top] are all hairline.
 *
 * The divider is 1dp, which is two device pixels at xhdpi, so "the row below the hairline" is
 * still hairline. Sampling one row down would measure the divider and call it the bar.
 */
internal fun Bitmap.hairlineThicknessBelow(top: Int): Int {
    var rows = 0
    while (top + rows < height) {
        val row = top + rows
        var allHairline = true
        for (x in 0 until width step 8) {
            if (getPixel(x, row) != hairlineArgb) {
                allHairline = false
                break
            }
        }
        if (!allHairline) break
        rows++
    }
    return rows
}

/** The colour every measurement in this package treats as the bar's top rule. */
internal val hairlineArgb: Int get() = com.musa.opencodeharness.ui.theme.ThemeFixtures.theme.elevation.dividerColor.toArgbInt()

/** The bar's horizontal band as `[top, bottom)` pixel rows. */
internal fun Bitmap.barBand(hairline: Int): IntRange {
    val top = findHairlineRow(hairline)
    require(top >= 0) { "no full-width hairline row: the bar's top edge could not be located" }
    return top until height
}

/** The pixel columns one tab owns: the bar splits its width evenly, so the slot is W/6. */
internal fun Bitmap.tabColumn(slot: Int, slotCount: Int): Pair<Int, Int> =
    (width * slot / slotCount) to (width * (slot + 1) / slotCount)

/**
 * Splits a sorted row list into runs of consecutive rows.
 *
 * Used to tell an icon from the label beneath it without mixing coordinate spaces: the semantics
 * tree is measured in the Compose root's coordinates and the bitmap is measured in the window's,
 * and the two differ by the status-bar inset. Scanning for *runs* of rows avoids needing to know
 * that offset at all.
 */
internal fun List<Int>.consecutiveRuns(): List<IntRange> {
    if (isEmpty()) return emptyList()
    val runs = mutableListOf<IntRange>()
    var start = first()
    var previous = first()
    for (row in drop(1)) {
        if (row != previous + 1) {
            runs += start..previous
            start = row
        }
        previous = row
    }
    runs += start..previous
    return runs
}

/** Packs a token colour the way `Bitmap.getPixel` reports it. */
internal fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255f + 0.5f).toInt(),
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt(),
    )

/** Width of a semantics bounds rectangle, in dp. `DpRect` has no `width` property of its own. */
internal fun DpRect.widthDp() = right - left

/** Height of a semantics bounds rectangle, in dp. */
internal fun DpRect.heightDp() = bottom - top
