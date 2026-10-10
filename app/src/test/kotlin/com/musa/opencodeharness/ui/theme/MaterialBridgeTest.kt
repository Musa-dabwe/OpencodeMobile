package com.musa.opencodeharness.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The translation into Material 3, which is the only place in the app where a design token can be
 * quietly reshaped. Every mapping is pinned so the translation cannot drift.
 */
class MaterialBridgeTest {

    private val theme = ThemeFixtures.theme
    private val scheme: ColorScheme = theme.color.toColorScheme()

    @Test
    fun accentIsThePrimaryActionColour() {
        assertEquals(theme.color.accent.base, scheme.primary)
        assertEquals(theme.color.text.onAccent, scheme.onPrimary)
        // accent.muted is a container, and its label is text.primary, not text.onAccent.
        assertEquals(theme.color.accent.muted, scheme.primaryContainer)
        assertEquals(theme.color.text.primary, scheme.onPrimaryContainer)
    }

    @Test
    fun surfacesStepThroughTheFourRealSurfaceTokens() {
        assertEquals(theme.color.background.base, scheme.background)
        assertEquals(theme.color.text.primary, scheme.onBackground)
        assertEquals(theme.color.background.raised, scheme.surface)
        assertEquals(theme.color.background.raisedAlt, scheme.surfaceVariant)
        assertEquals(theme.color.text.secondary, scheme.onSurfaceVariant)
        assertEquals(theme.color.background.inset, scheme.surfaceContainerLowest)
        assertEquals(theme.color.background.raised, scheme.surfaceContainerLow)
        assertEquals(theme.color.background.raised, scheme.surfaceContainer)
        assertEquals(theme.color.background.raisedAlt, scheme.surfaceContainerHigh)
        assertEquals(theme.color.background.overlay, scheme.surfaceContainerHighest)
        assertEquals(theme.color.background.overlay, scheme.surfaceBright)
        assertEquals(theme.color.background.inset, scheme.surfaceDim)
        assertEquals(theme.color.background.scrim, scheme.scrim)
    }

    @Test
    fun theSecondAndThirdSlotsAreTheAgentHues() {
        // The design has no second brand colour, so these exist only so M3 components that tint
        // themselves still land on a real token.
        assertEquals(theme.color.agent.primary, scheme.secondary)
        assertEquals(theme.color.agent.subagent, scheme.tertiary)
    }

    @Test
    fun statusAndActionErrorsAreWired() {
        assertEquals(theme.color.status.error, scheme.error)
        assertEquals(theme.color.background.base, scheme.onError)
        assertEquals(theme.color.action.deleteBg, scheme.errorContainer)
        assertEquals(theme.color.status.error, scheme.onErrorContainer)
    }

    @Test
    fun theHairlineIsTheOutlineRole() {
        assertEquals(theme.color.border.subtle, scheme.outline)
        assertEquals(theme.color.border.strong, scheme.outlineVariant)
        assertEquals(theme.color.border.subtle, theme.elevation.dividerColor)
    }

    @Test
    fun shapeSlotsResolveToTheDesignsRadii() {
        val shapes = theme.shape.toMaterialShapes()

        assertEquals(RoundedCornerShape(6.dp), shapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), shapes.small)
        assertEquals(RoundedCornerShape(12.dp), shapes.medium)
        assertEquals(RoundedCornerShape(12.dp), shapes.large)
        assertEquals(RoundedCornerShape(12.dp), shapes.extraLarge)
    }

    @Test
    fun theDesignsDepthModelIsHairlinePlusLiftAndNeverAShadow() {
        assertFalse(theme.elevation.useShadows)
        assertEquals(1f, theme.elevation.dividerWidthDp, 0f)
        assertEquals(1.dp, theme.spacing.hairline)
    }

    @Test
    fun motionDurationsComeFromTheTokensAndCollapseWhenAnimationsAreOff() {
        assertEquals(150, theme.motion.duration(fast = true))
        assertEquals(250, theme.motion.duration(fast = false))
        // Respecting the system setting means zero, not "a shorter animation".
        assertEquals(0, theme.motion.duration(fast = true, animationsEnabled = false))
        assertEquals(0, theme.motion.duration(fast = false, animationsEnabled = false))
    }
}
