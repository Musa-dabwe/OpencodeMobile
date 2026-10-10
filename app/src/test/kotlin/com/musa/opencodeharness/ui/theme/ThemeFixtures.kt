package com.musa.opencodeharness.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue

/**
 * The REAL `assets/themes/opencode-mobile.json`, read from the test classpath.
 *
 * `app/build.gradle.kts` puts `app/src/main/assets` on the test classpath precisely so these
 * tests parse the file that ships. A copy of the palette inside the test source tree would drift
 * from it, and a drifted copy would keep passing after the shipped one was broken.
 */
internal object ThemeFixtures {

    val json: String by lazy {
        val stream = checkNotNull(ThemeFixtures::class.java.classLoader?.getResourceAsStream(ThemeLoader.BUNDLED_THEME_PATH)) {
            "assets/${ThemeLoader.BUNDLED_THEME_PATH} is not on the test classpath -- the tests " +
                "must parse the file that ships, not a copy of it"
        }
        stream.bufferedReader().use { it.readText() }
    }

    val theme: OpenCodeTheme by lazy { ThemeLoader.parse(json) }

    /** The bundled JSON with one exact substring swapped. Fails the test if the anchor moved. */
    fun bundledWith(anchor: String, replacement: String): String = json.withReplacement(anchor, replacement)
}

/**
 * Swaps one exact substring, failing loudly if the anchor is not there.
 *
 * Without this guard a fixture that drifts stops matching, `replace` quietly becomes a no-op, and
 * the test goes on passing while exercising none of its subject.
 */
internal fun String.withReplacement(anchor: String, replacement: String): String {
    assertTrue(
        "fixture precondition: \"$anchor\" is no longer present, so this test would pass " +
            "without exercising anything",
        contains(anchor),
    )
    return replace(anchor, replacement)
}

/**
 * A theme written in the V2 file format: `$schema` and `variant` envelopes, a hue palette, a
 * `$hue.<name>.<step>` reference, the `transparent` keyword and a `$hovered` state key.
 *
 * The bundled palette uses none of those forms, so this fixture is the only thing that exercises
 * them. Every value here is fabricated on purpose -- the point is the syntax, not the colours.
 */
internal val V2_FORMAT_THEME_JSON: String = """
{
  "${'$'}schema": "https://opencode.ai/theme.json",
  "${'$'}comment": ["a comment array must be skipped, not parsed as a token"],
  "variant": "dark",
  "version": 1,
  "source": "synthetic fixture",

  "hue": {
    "blue": { "400": "#4F8CD9", "900": "#0B1D33" }
  },

  "color": {
    "background": {
      "base": "#0C0C0E",
      "raised": "${'$'}hue.blue.900",
      "raisedAlt": "transparent",
      "overlay": "${'$'}hue.blue.400",
      "scrim": "#0A0A0C",
      "inset": "#050505",
      "${'$'}hovered": "#111113"
    },
    "border": {
      "subtle": "${'$'}hue.blue.900",
      "strong": "#302F35",
      "${'$'}focused": "transparent"
    },
    "text": {
      "primary": "#F2F2F4",
      "secondary": "#96959B",
      "disabled": "#56555B",
      "sectionHeader": "#8F8E95",
      "onAccent": "#1A1A1C"
    },
    "accent": {
      "base": "#FAB282",
      "hover": "#FCB082",
      "pressed": "#F9B282",
      "muted": "#89624A"
    },
    "status": { "idle": "#7DD88E", "running": "#6A4F3C", "error": "#E96776" },
    "agent": { "primary": "#568FDA", "subagent": "#75B086", "experimental": "#AB5865" },
    "action": {
      "rename": "#5A9AFC",
      "renameBg": "#203552",
      "delete": "#E36874",
      "deleteBg": "#3A161A",
      "blueFg": "#3A5687"
    }
  },

  "typography": {
    "${'$'}comment": ["skipped"],
    "typeface": "system",
    "screenTitle":   { "sizeSp": 28, "weight": 700, "trackingEm": -0.02, "role": "screen title" },
    "rowTitle":      { "sizeSp": 16, "weight": 600, "trackingEm": 0.0,  "role": "row title" },
    "meta":          { "sizeSp": 13, "weight": 400, "trackingEm": 0.0,  "role": "meta" },
    "sectionHeader": { "sizeSp": 11, "weight": 600, "trackingEm": 0.12, "role": "section header" },
    "body":          { "sizeSp": 15, "weight": 400, "trackingEm": 0.0,  "role": "body" },
    "label":         { "sizeSp": 12, "weight": 500, "trackingEm": 0.0,  "role": "label" },
    "mono":          { "sizeSp": 13, "weight": 400, "trackingEm": 0.0,  "role": "mono" }
  },

  "shape": {
    "capsule": 999,
    "card": 12,
    "chip": 6,
    "fabHeightDp": 53,
    "fabWidthDp": 145
  },
  "spacing": { "screenMarginDp": 16, "sectionGapDp": 24, "rowGapDp": 12, "hairlineDp": 1 },
  "elevation": { "useShadows": false, "dividerColor": "${'$'}hue.blue.900", "dividerWidthDp": 1 },
  "motion": { "fast": 150, "medium": 250, "respectRemoveAnimations": true }
}
""".trimIndent()

/** Every colour token, flattened to `color.<group>.<role>`, so a missing or extra token shows up. */
internal fun OpenCodeTheme.flatColours(): Map<String, Color> = buildMap {
    fun group(name: String, colours: Map<String, Color>) {
        colours.forEach { (role, colour) -> put("$name.$role", colour) }
    }
    group("background", color.background.let { mapOf(
        "base" to it.base, "raised" to it.raised, "raisedAlt" to it.raisedAlt,
        "overlay" to it.overlay, "scrim" to it.scrim, "inset" to it.inset,
    ) })
    group("border", mapOf("subtle" to color.border.subtle, "strong" to color.border.strong))
    group("text", mapOf(
        "primary" to color.text.primary, "secondary" to color.text.secondary,
        "disabled" to color.text.disabled, "sectionHeader" to color.text.sectionHeader,
        "onAccent" to color.text.onAccent,
    ))
    group("accent", mapOf(
        "base" to color.accent.base, "hover" to color.accent.hover,
        "pressed" to color.accent.pressed, "muted" to color.accent.muted,
    ))
    group("status", mapOf(
        "idle" to color.status.idle, "running" to color.status.running, "error" to color.status.error,
    ))
    group("agent", mapOf(
        "primary" to color.agent.primary, "subagent" to color.agent.subagent,
        "experimental" to color.agent.experimental,
    ))
    group("action", mapOf(
        "rename" to color.action.rename, "renameBg" to color.action.renameBg,
        "delete" to color.action.delete, "deleteBg" to color.action.deleteBg,
        "blueFg" to color.action.blueFg,
    ))
    put("elevation.dividerColor", elevation.dividerColor)
}
