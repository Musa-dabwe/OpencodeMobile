package com.musa.opencodeharness.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette is only worth extracting into JSON if a change to it has to be deliberate, so these
 * tests pin every token to the value the design records -- not merely "it parsed".
 *
 * They read `assets/themes/opencode-mobile.json` itself (see [ThemeFixtures]), so an edit to the
 * file that nobody intended fails here instead of silently shipping.
 */
class ThemeParseTest {

    @Test
    fun everyColourTokenEqualsTheValueSampledFromTheDesign() {
        val expected = mapOf(
            "background.base" to 0xFF0C0C0E,
            "background.raised" to 0xFF151517,
            "background.raisedAlt" to 0xFF1E1D22,
            "background.overlay" to 0xFF1A1A1C,
            "background.scrim" to 0xFF0A0A0C,
            "background.inset" to 0xFF050505,
            "border.subtle" to 0xFF232227,
            "border.strong" to 0xFF302F35,
            "text.primary" to 0xFFF2F2F4,
            "text.secondary" to 0xFF96959B,
            "text.disabled" to 0xFF56555B,
            "text.sectionHeader" to 0xFF8F8E95,
            "text.onAccent" to 0xFF1A1A1C,
            "accent.base" to 0xFFFAB282,
            "accent.hover" to 0xFFFCB082,
            "accent.pressed" to 0xFFF9B282,
            "accent.muted" to 0xFF89624A,
            "status.idle" to 0xFF7DD88E,
            "status.running" to 0xFF6A4F3C,
            "status.error" to 0xFFE96776,
            "agent.primary" to 0xFF568FDA,
            "agent.subagent" to 0xFF75B086,
            "agent.experimental" to 0xFFAB5865,
            "action.rename" to 0xFF5A9AFC,
            "action.renameBg" to 0xFF203552,
            "action.delete" to 0xFFE36874,
            "action.deleteBg" to 0xFF3A161A,
            "action.blueFg" to 0xFF3A5687,
            "elevation.dividerColor" to 0xFF232227,
        ).mapValues { (_, argb) -> Color(argb.toLong()) }

        val actual = ThemeFixtures.theme.flatColours()

        // Compared as whole maps: an ADDED token fails here too, which a per-key loop would not.
        assertEquals(
            "colour tokens differ from the design. Missing: ${expected.keys - actual.keys}, " +
                "unexpected: ${actual.keys - expected.keys}, " +
                "changed: ${expected.keys.intersect(actual.keys).filter { expected[it] != actual[it] }}",
            expected,
            actual,
        )
    }

    @Test
    fun everyNonColourTokenEqualsTheValueTheDesignRecords() {
        val theme = ThemeFixtures.theme
        assertEquals(1, theme.version)
        assertEquals("OpenCode Mobile - UI design.pdf", theme.source)
        assertEquals("system", theme.typography.typeface)

        assertEquals(999f, theme.shape.capsuleDp, 0f)
        assertEquals(12f, theme.shape.cardDp, 0f)
        assertEquals(6f, theme.shape.chipDp, 0f)
        assertEquals(53f, theme.shape.fabHeightDp, 0f)
        assertEquals(145f, theme.shape.fabWidthDp, 0f)

        assertEquals(16f, theme.spacing.screenMarginDp, 0f)
        assertEquals(24f, theme.spacing.sectionGapDp, 0f)
        assertEquals(12f, theme.spacing.rowGapDp, 0f)
        assertEquals(1f, theme.spacing.hairlineDp, 0f)

        // The design has no drop shadows. If this ever reads true, the design changed and the
        // screenshot in the phase report has to change with it.
        assertFalse(theme.elevation.useShadows)
        assertEquals(1f, theme.elevation.dividerWidthDp, 0f)

        assertEquals(150, theme.motion.fastMillis)
        assertEquals(250, theme.motion.mediumMillis)
        assertTrue(theme.motion.respectRemoveAnimations)
    }

    @Test
    fun theBundledPaletteDeclaresNoStateVariants() {
        // The design file uses plain hexes only. State keys are a loader capability, tested
        // against the V2 fixture below, so an unexpected one here means the JSON gained something.
        assertEquals(
            emptyMap<String, Color>(),
            ThemeFixtures.theme.color.background.states,
        )
        assertEquals(emptyMap<String, Color>(), ThemeFixtures.theme.color.text.states)
        assertEquals(emptyMap<String, Color>(), ThemeFixtures.theme.color.accent.states)
    }

    // --- the V2 value forms, which the bundled palette does not itself use ------------------------

    @Test
    fun v2HueReferencesResolveAgainstTheFilesOwnPalette() {
        val theme = ThemeLoader.parse(V2_FORMAT_THEME_JSON)

        assertEquals(Color(0xFF0B1D33L), theme.color.background.raised)
        assertEquals(Color(0xFF4F8CD9L), theme.color.background.overlay)
        // A reference works outside `color.*` too -- the elevation divider is one.
        assertEquals(Color(0xFF0B1D33L), theme.elevation.dividerColor)
    }

    @Test
    fun transparentResolvesToAZeroAlphaColour() {
        val theme = ThemeLoader.parse(V2_FORMAT_THEME_JSON)

        assertEquals(Color.Transparent, theme.color.background.raisedAlt)
        assertEquals(0f, theme.color.background.raisedAlt.alpha, 0f)
    }

    @Test
    fun stateKeysResolveAlongsideTheRolesTheyBelongTo() {
        val theme = ThemeLoader.parse(V2_FORMAT_THEME_JSON)

        assertEquals(Color(0xFF111113L), theme.color.background.states["hovered"])
        assertEquals(Color.Transparent, theme.color.border.states["focused"])
        // The `$` is the marker, not part of the state's name.
        assertEquals(setOf("hovered"), theme.color.background.states.keys)
        // A state key must not shadow a role.
        assertEquals(Color(0xFF0C0C0EL), theme.color.background.base)
    }

    @Test
    fun fileEnvelopeKeysAreSkippedRatherThanRejected() {
        // `$schema`, `$comment`, `variant` and `name` describe the file; they are not tokens.
        val theme = ThemeLoader.parse(V2_FORMAT_THEME_JSON)
        assertEquals("synthetic fixture", theme.source)
        assertEquals(1, theme.version)
    }

    @Test
    fun hexShorthandAndAlphaFormsExpandToTheSameColour() {
        // #RGB doubles every digit -- #8f8 becomes #88FF88. It is NOT "the same colour in fewer
        // characters": #0C0C0E has no shorthand, because its digits are not doubles.
        // #RRGGBBAA puts alpha last and has to be repacked into the 0xAARRGGBB long Compose
        // stores, or the alpha value lands in the blue channel and the colour is near-invisible.
        val json = V2_FORMAT_THEME_JSON
            .withReplacement("\"base\": \"#0C0C0E\"", "\"base\": \"#8f8\"")
            .withReplacement("\"scrim\": \"#0A0A0C\"", "\"scrim\": \"#0A0\"")
            .withReplacement("\"inset\": \"#050505\"", "\"inset\": \"#050505cc\"")
            .withReplacement("\"\$hovered\": \"#111113\"", "\"\$hovered\": \"#1f3\"")

        val theme = ThemeLoader.parse(json)

        assertEquals(Color(0xFF88FF88L), theme.color.background.base)
        assertEquals(Color(0xFF00AA00L), theme.color.background.scrim)
        assertEquals(0xCC / 255f, theme.color.background.inset.alpha, 0.001f)
        assertEquals(5 / 255f, theme.color.background.inset.red, 0.001f)
        assertEquals(5 / 255f, theme.color.background.inset.blue, 0.001f)
        assertEquals(Color(0xFF11FF33L), theme.color.background.states["hovered"])
    }
}
