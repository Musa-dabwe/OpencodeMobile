package com.musa.opencodeharness.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The literal a theme file uses for "no colour at all". */
const val TRANSPARENT_KEYWORD: String = "transparent"

/**
 * Thrown for any theme file the loader cannot turn into a palette.
 *
 * Loudly, always, with a message naming the offending path -- a colour that quietly falls back to
 * black is indistinguishable from a real design decision, and the whole reason these tokens were
 * extracted into JSON is that they are machine-checkable.
 */
class ThemeFormatException(message: String) : IllegalArgumentException(message)

/**
 * Parses `#RGB`, `#RGBA`, `#RRGGBB` and `#RRGGBBAA`.
 *
 * [path] is the JSON path of the value ("color.background.base") and goes into the message, so a
 * failure points at the line to fix rather than at "a colour".
 *
 * Compose's own `Color(int)` overload is deliberately not used for the 6-digit case: it exists,
 * but going through an explicit `0xFF...` long keeps the alpha handling visible at the call site.
 */
internal fun parseHexColor(raw: String, path: String): Color {
    val digits = raw.substring(1)
    val expanded = when (digits.length) {
        // #RGB and #RGBA are CSS shorthand: each digit is doubled.
        3, 4 -> digits.flatMap { listOf(it, it) }.joinToString("")
        6, 8 -> digits
        else -> throw ThemeFormatException(
            "$path: \"$raw\" is not a hex colour -- expected #RGB, #RGBA, #RRGGBB or #RRGGBBAA, " +
                "but it has ${digits.length} hex digits",
        )
    }
    val value = expanded.toLongOrNull(16) ?: throw ThemeFormatException(
        "$path: \"$raw\" is not a hex colour -- \"$expanded\" contains a character that is not a " +
            "hex digit (only 0-9 and a-f are valid)",
    )
    val argb = when (expanded.length) {
        // A bare #RRGGBB is opaque by definition.
        6 -> 0xFF000000L or value
        // #RRGGBBAA puts alpha LAST, while Compose packs it first, so the bytes are swapped.
        // Reading it straight into the int yields a colour with the alpha channel's value in
        // the blue slot and an almost-transparent alpha -- green-looking and unreadable.
        else -> ((value and 0xFF) shl 24) or (value shr 8)
    }
    return Color(argb)
}

/**
 * WCAG 2.x relative luminance of an sRGB colour.
 *
 * Pure arithmetic on the packed channels, deliberately not Compose's `Color.luminance()`: that one
 * resolves through the platform colour-space machinery, and this has to be runnable in a plain JVM
 * unit test with no device and no Robolectric.
 */
fun relativeLuminance(color: Color): Double {
    val argb = color.toArgb()
    // Unpacked from the packed ARGB int rather than read off Color.red/green/blue: those
    // accessors have changed scale between Compose versions, and a contrast floor that silently
    // changes scale is worse than no floor at all.
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). Order-independent. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
