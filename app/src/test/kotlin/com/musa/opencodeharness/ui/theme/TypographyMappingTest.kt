package com.musa.opencodeharness.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Type roles come out of the JSON as size, weight and tracking, and nowhere else.
 *
 * The typeface itself is the system default by owner decision -- nothing is bundled, so there is
 * no font file, no licence, and no `mono` role that quietly loads a typeface from somewhere else.
 */
class TypographyMappingTest {

    private val typography = ThemeFixtures.theme.typography

    @Test
    fun everyRoleMapsToTheTextStyleTheDesignRecords() {
        val expected = mapOf(
            TypeRole.SCREEN_TITLE to TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                letterSpacing = (-0.02).em,
            ),
            TypeRole.ROW_TITLE to TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 0.0.em,
            ),
            TypeRole.META to TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                letterSpacing = 0.0.em,
            ),
            TypeRole.SECTION_HEADER to TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 0.12.em,
            ),
            TypeRole.BODY to TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                letterSpacing = 0.0.em,
            ),
            TypeRole.LABEL to TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 0.0.em,
            ),
            TypeRole.MONO to TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                letterSpacing = 0.0.em,
            ),
        )

        TypeRole.entries.forEach { role ->
            assertEquals("${role.jsonKey} maps to the wrong TextStyle", expected.getValue(role), typography.textStyle(role))
        }
        // Nothing added to the model without being pinned here.
        assertEquals(TypeRole.entries.toSet(), expected.keys)
    }

    @Test
    fun everyRoleResolvesToASystemTypefaceAndOnlyMonoIsMonospaced() {
        TypeRole.entries.forEach { role ->
            val family = typography.textStyle(role).fontFamily
            val wanted = if (role == TypeRole.MONO) FontFamily.Monospace else FontFamily.Default
            assertEquals("${role.jsonKey} resolves to the wrong font family", wanted, family)
            // Nothing may smuggle in a bundled typeface: these are the only two families the app
            // can name, because the only two it has.
            assertTrue(
                "${role.jsonKey} resolved to $family, which is neither the system sans nor the " +
                    "system monospace",
                family == FontFamily.Default || family == FontFamily.Monospace,
            )
        }
    }

    @Test
    fun noRoleCarriesALineHeightTheDesignNeverMeasured() {
        // The JSON has no line-height token. Inventing one in code would put a number in the app
        // that no design file governs, so every role leaves it to the platform font metrics.
        TypeRole.entries.forEach { role ->
            assertEquals(
                "${role.jsonKey} has an invented line height",
                TextUnit.Unspecified,
                typography.textStyle(role).lineHeight,
            )
        }
    }

    @Test
    fun weightsAreReadInHundreds() {
        assertEquals(700, typography.screenTitle.weightHundreds)
        assertEquals(FontWeight.Bold, typography.screenTitle.fontWeight)
        assertEquals(600, typography.sectionHeader.weightHundreds)
        assertEquals(FontWeight.SemiBold, typography.sectionHeader.fontWeight)
        assertEquals(500, typography.label.weightHundreds)
        assertEquals(FontWeight.Medium, typography.label.fontWeight)
        assertEquals(400, typography.body.weightHundreds)
        assertEquals(FontWeight.Normal, typography.body.fontWeight)
    }

    @Test
    fun theUsageNoteTravelsWithTheRole() {
        // Documentation, not behaviour -- but it is carried through the parser rather than left in
        // the file, so a role cannot be documented one way and rendered another.
        assertEquals("Sessions / Agents / Settings", typography.screenTitle.usage)
        assertEquals("TODAY / MODELS / SAFETY", typography.sectionHeader.usage)
        assertEquals("paths, commands, diffs", typography.mono.usage)
    }

    @Test
    fun materialSlotsAreClaimedByTheRoleThatOwnsThem() {
        val m3 = typography.toMaterialTypography()

        assertEquals(typography.textStyle(TypeRole.ROW_TITLE), m3.titleMedium)
        assertEquals(typography.textStyle(TypeRole.BODY), m3.bodyLarge)
        assertEquals(typography.textStyle(TypeRole.META), m3.bodySmall)
        assertEquals(typography.textStyle(TypeRole.LABEL), m3.labelMedium)
        assertEquals(typography.textStyle(TypeRole.SECTION_HEADER), m3.labelSmall)
        assertEquals(typography.textStyle(TypeRole.SCREEN_TITLE), m3.headlineSmall)
    }
}
