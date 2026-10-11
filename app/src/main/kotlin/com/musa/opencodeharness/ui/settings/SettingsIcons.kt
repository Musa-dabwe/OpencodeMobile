package com.musa.opencodeharness.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The one glyph the Settings screen needs: the row chevron.
 *
 * Hand-authored on the same basis as Task 2's `NavIcons` and Task 3's `SessionIcons` --
 * `material-icons-core` has no chevron at this weight, and `material-icons-extended` is a
 * multi-megabyte dependency for one path.
 *
 * ## Why the fills are black
 *
 * `PAINT` is a paint SOURCE, not a colour decision: `Icon` replaces every non-transparent pixel
 * with its tint, so this value never reaches the screen. Every colour a user sees comes from
 * [SettingsStyle.chevron], i.e. from a design token.
 */
private val PAINT = SolidColor(Color.Black)

private fun nodes(pathData: String): List<PathNode> = PathParser().parsePathString(pathData).toNodes()

/** The design's `›` row affordance, pointing right. */
internal val RowChevron: ImageVector = ImageVector.Builder(
    name = "RowChevron",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = nodes("M9 5 L16 12 L9 19"),
    name = "chevron",
    fill = null,
    stroke = PAINT,
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()
