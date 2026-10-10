package com.musa.opencodeharness.ui.nav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The six bottom-bar glyphs, drawn here rather than pulled from `material-icons-extended`.
 *
 * `material-icons-core` ships about fifty glyphs and none of the six this bar needs (chat,
 * folder, terminal, agents are all extended-set); the extended artifact is a multi-megabyte
 * dependency for six vectors. So they are declared as path data on a 24x24 viewport, which is
 * also the format Material's own icons use.
 *
 * ## Why the fills are black
 *
 * `PAINT` below is a *paint source*, not a colour decision. `Icon` draws the vector and then
 * replaces every non-transparent pixel with its tint, exactly as it does for Material's own
 * black-filled icons, so the value never reaches the screen. Every colour a user can see in the
 * bar comes from a design token via [com.musa.opencodeharness.ui.theme.OpenCodeTheme] -- see
 * `NavBarStyleTest`, which asserts the rendered pixels rather than trusting this comment.
 */
private val PAINT = SolidColor(Color.Black)

private const val STROKE = 1.9f

/** A path string parsed once, at class-init, rather than on every draw. */
private fun nodes(pathData: String): List<PathNode> = PathParser().parsePathString(pathData).toNodes()

private fun icon(name: String, build: ImageVector.Builder.() -> Unit) =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

/** A filled shape. */
private fun ImageVector.Builder.filled(name: String, pathData: String) =
    addPath(pathData = nodes(pathData), name = name, fill = PAINT)

/** An outlined shape: no fill, a round-capped round-joined stroke. */
private fun ImageVector.Builder.outlined(name: String, pathData: String, strokeWidth: Float = STROKE) =
    addPath(
        pathData = nodes(pathData),
        name = name,
        fill = null,
        stroke = PAINT,
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

/** A ring: the two half-arc form, so the outline needs no cap at the seam. */
private fun ring(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} $cy a$r $r 0 1 0 ${r * 2} 0 a$r $r 0 1 0 ${-r * 2} 0 z"

/** A dot: the same two-arc form, filled instead of stroked. */
private fun dot(cx: Float, cy: Float, r: Float): String = ring(cx, cy, r)

internal object NavIcons {

    /** Folder, for Projects: a filled silhouette with a raised tab. */
    val Projects: ImageVector = icon("Projects") {
        filled(
            "folder",
            "M2.6 5.2c0-0.9 0.7-1.6 1.6-1.6h4.4c0.5 0 1 0.2 1.3 0.6l1.6 2h8.9c0.9 0 1.6 0.7 1.6 1.6v11.4c0 "
                + "0.9-0.7 1.6-1.6 1.6H4.2c-0.9 0-1.6-0.7-1.6-1.6z",
        )
    }

    /** Speech bubble, for Chat: a rounded body with a tail on the lower left. */
    val Chat: ImageVector = icon("Chat") {
        filled(
            "bubble",
            "M4.2 4.4h15.6c0.9 0 1.6 0.7 1.6 1.6v8.8c0 0.9-0.7 1.6-1.6 1.6h-7.1l-5.3 4.5c-0.4 0.4-1.1 "
                + "0.1-1.1-0.5v-4H4.2c-0.9 0-1.6-0.7-1.6-1.6V6c0-0.9 0.7-1.6 1.6-1.6z",
        )
    }

    /** Document with a folded corner, for Files: a filled body, a notched corner, a fold line. */
    val Files: ImageVector = icon("Files") {
        filled("sheet", "M6.2 3h7.4v5.4H19V21H6.2z")
        outlined("fold", "M13.6 3v5.4H19", 1.6f)
    }

    /** A terminal window with a prompt caret, for Terminal: outline, caret, cursor. */
    val Terminal: ImageVector = icon("Terminal") {
        outlined("frame", "M3.6 5.4h16.8v13.2H3.6z", 1.8f)
        outlined("caret", "M7.4 9.9l3 2.6-3 2.6")
        outlined("cursor", "M13.2 15.1h4")
    }

    /**
     * An agent head, for Agents: a rounded outline, two eyes and an antenna.
     *
     * Chosen over a "hub of nodes" because three rings and a dot are unreadable at the 20dp this
     * bar draws at, whereas the silhouette still says "not a person, a thing that runs".
     */
    val Agents: ImageVector = icon("Agents") {
        outlined("head", "M4.4 8.4a3 3 0 0 1 3-3h9.2a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H7.4a3 3 0 0 1-3-3z")
        filled("antenna", dot(12f, 2.9f, 1.5f))
        outlined("mast", "M12 5.4v3")
        filled("eyeL", dot(9.2f, 12.9f, 1.15f))
        filled("eyeR", dot(14.8f, 12.9f, 1.15f))
    }

    /** Two sliders, for Settings: the control-panel reading rather than a cog. */
    val Settings: ImageVector = icon("Settings") {
        outlined("railTop", "M3.6 7.6h16.8")
        outlined("railBottom", "M3.6 16.4h16.8")
        outlined("knobTop", ring(9f, 7.6f, 2.5f), 1.8f)
        outlined("knobBottom", ring(15f, 16.4f, 2.5f), 1.8f)
    }
}
