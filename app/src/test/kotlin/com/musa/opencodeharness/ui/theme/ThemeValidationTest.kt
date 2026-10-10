package com.musa.opencodeharness.ui.theme

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way a theme file can be wrong must fail loudly, with a message that names the offending
 * path.
 *
 * The alternative is a colour that quietly renders as black, which looks like a design decision
 * and is not one. These tests assert on the MESSAGE, not merely on the throw: a failure that says
 * "invalid theme" is nearly as useless at 2am as no failure at all.
 */
class ThemeValidationTest {

    private fun assertRejects(json: String, vararg expectedInMessage: String) {
        val thrown = assertThrows(
            "expected this theme to be rejected, but it parsed",
            ThemeFormatException::class.java,
        ) { ThemeLoader.parse(json) }
        val message = thrown.message.orEmpty()
        expectedInMessage.forEach {
            assertTrue("message should mention \"$it\", but was: $message", message.contains(it))
        }
    }

    // --- colours -------------------------------------------------------------------------------

    @Test
    fun aNonHexCharacterIsRejectedWithItsPathAndValue() {
        val json = ThemeFixtures.bundledWith("\"#0C0C0E\"", "\"#0C0C0G\"")
        assertRejects(json, "color.background.base", "#0C0C0G", "not a hex digit")
    }

    @Test
    fun aHexOfTheWrongLengthIsRejected() {
        val json = ThemeFixtures.bundledWith("\"#0C0C0E\"", "\"#0C0C0\"")
        assertRejects(json, "color.background.base", "hex digits")
    }

    @Test
    fun aQuotedColourIsRejected() {
        val json = ThemeFixtures.bundledWith("\"#0C0C0E\"", "\"\\\"#0C0C0E\\\"\"")
        assertRejects(json, "color.background.base", "is not a colour")
    }

    @Test
    fun aNumberWhereAColourBelongsIsRejected() {
        val json = ThemeFixtures.bundledWith("\"#0C0C0E\"", "123")
        assertRejects(json, "color.background.base", "must be a colour")
    }

    // --- structure -----------------------------------------------------------------------------

    @Test
    fun anUnknownKeyInAColourGroupIsRejectedAndListsTheKnownOnes() {
        val json = ThemeFixtures.bundledWith("\"scrim\": \"#0A0A0C\"", "\"scrime\": \"#0A0A0C\"")
        assertRejects(json, "color.background: unknown key \"scrime\"", "base, raised")
    }

    @Test
    fun anUnknownKeyAtTheRootIsRejected() {
        val json = ThemeFixtures.bundledWith("\"version\": 1,", "\"themeName\": \"midnight\", \"version\": 1,")
        assertRejects(json, "unknown key \"themeName\" at the theme root")
    }

    @Test
    fun aMissingRequiredTokenIsNamedRatherThanDefaulted() {
        val json = ThemeFixtures.bundledWith("      \"scrim\": \"#0A0A0C\",\n", "")
        assertRejects(json, "color.background is missing scrim")
    }

    @Test
    fun aMissingColourGroupIsRejected() {
        val json = ThemeFixtures.bundledWith(
            "    \"accent\": {\n      \"base\": \"#FAB282\",\n      \"hover\": \"#FCB082\",\n" +
                "      \"pressed\": \"#F9B282\",\n      \"muted\": \"#89624A\"\n    },",
            "",
        )
        assertRejects(json, "color.accent must be a JSON object")
    }

    // --- references ---------------------------------------------------------------------------

    @Test
    fun aReferenceToAHueTheFileDoesNotDeclareIsRejected() {
        val json = ThemeFixtures.bundledWith("\"#0C0C0E\"", "\"\$hue.blue.400\"")
        assertRejects(json, "color.background.base", "\$hue.blue.400 does not resolve", "declares no")
    }

    @Test
    fun aReferenceToAStepTheHueDoesNotDeclareIsRejected() {
        val json = V2_FORMAT_THEME_JSON.withReplacement(
            "\"\$hovered\": \"#111113\"",
            "\"\$hovered\": \"\$hue.blue.999\"",
        )
        assertRejects(json, "color.background.\$hovered", "\$hue.blue.999 does not resolve", "hue.blue\" has steps")
    }

    @Test
    fun aReferenceThatIsNotAHueReferenceIsRejected() {
        val json = V2_FORMAT_THEME_JSON.withReplacement(
            "\"\$hovered\": \"#111113\"",
            "\"\$hovered\": \"\$accent.400\"",
        )
        assertRejects(json, "only", "supported form is \$hue.<name>.<step>")
    }

    @Test
    fun aHuePaletteThatIsNotTwoLevelsDeepIsRejected() {
        val json = V2_FORMAT_THEME_JSON.withReplacement(
            "\"blue\": { \"400\": \"#4F8CD9\", \"900\": \"#0B1D33\" }",
            "\"blue\": \"#4F8CD9\"",
        )
        assertRejects(json, "hue.blue must be a JSON object")
    }

    // --- scalars -------------------------------------------------------------------------------

    @Test
    fun aQuotedNumberIsRejectedRatherThanCoerced() {
        val json = ThemeFixtures.bundledWith("\"weight\": 700", "\"weight\": \"700\"")
        assertRejects(json, "typography.screenTitle.weight", "unquoted value")
    }

    @Test
    fun aQuotedBooleanIsRejectedRatherThanCoerced() {
        val json = ThemeFixtures.bundledWith("\"useShadows\": false", "\"useShadows\": \"false\"")
        assertRejects(json, "elevation.useShadows", "unquoted value")
    }

    @Test
    fun aWeightOutsideTheHundredRangeIsRejected() {
        val json = ThemeFixtures.bundledWith("\"weight\": 700", "\"weight\": 750")
        assertRejects(json, "typography.screenTitle.weight", "weights are 100..900")
    }

    @Test
    fun aTypefaceOtherThanSystemIsRejected() {
        val json = ThemeFixtures.bundledWith("\"typeface\": \"system\"", "\"typeface\": \"Inter\"")
        assertRejects(json, "typography.typeface", "only \"system\" is supported")
    }

    @Test
    fun malformedJsonIsRejected() {
        assertRejects("{ \"color\": ", "not valid JSON")
    }

    @Test
    fun aRootThatIsNotAnObjectIsRejected() {
        assertRejects("[]", "root must be a JSON object", "found an array")
    }
}
