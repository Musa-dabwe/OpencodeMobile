package com.musa.opencodeharness.ui.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The three glyphs the Sessions screen needs: a back chevron, a magnifier and a plus.
 *
 * Hand-authored on the same basis as Task 2's `NavIcons`: `material-icons-core` has a search icon
 * but neither a chevron nor a plus at this weight, and `material-icons-extended` is a
 * multi-megabyte dependency for three vectors. Declared as path data on a 24x24 viewport, which is
 * the format Material's own icons use.
 *
 * ## Why the fills are black
 *
 * `PAINT` is a paint SOURCE, not a colour decision. `Icon` replaces every non-transparent pixel
 * with its tint, so this value never reaches the screen; every colour a user sees comes from a
 * design token via [com.musa.opencodeharness.ui.theme.OpenCodeTheme]. `SessionsPixelTest` asserts
 * the rendered pixels rather than trusting this comment.
 */
private val PAINT = SolidColor(Color.Black)

private const val STROKE = 2f

private fun nodes(pathData: String): List<PathNode> = PathParser().parsePathString(pathData).toNodes()

private fun icon(name: String, build: ImageVector.Builder.() -> Unit) =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

/** An outlined shape: no fill, a round-capped round-joined stroke. */
private fun ImageVector.Builder.outlined(
    name: String,
    pathData: String,
    strokeWidth: Float = STROKE,
) = addPath(
    pathData = nodes(pathData),
    name = name,
    fill = null,
    stroke = PAINT,
    strokeLineWidth = strokeWidth,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
)

/** The header's back chevron, pointing left. */
internal val BackChevron: ImageVector = icon("BackChevron") {
    outlined("chevron", "M15 5 L8 12 L15 19")
}

/** The search field's magnifier: a ring plus a handle. */
internal val Magnifier: ImageVector = icon("Magnifier") {
    outlined("ring", "M10.5 4 A6.5 6.5 0 1 0 10.5 17 A6.5 6.5 0 1 0 10.5 4 Z", 1.9f)
    outlined("handle", "M15.4 15.4 L20 20", 1.9f)
}

/** The FAB's plus: two crossing strokes. */
internal val PlusGlyph: ImageVector = icon("PlusGlyph") {
    outlined("vertical", "M12 5 L12 19")
    outlined("horizontal", "M5 12 L19 12")
}
