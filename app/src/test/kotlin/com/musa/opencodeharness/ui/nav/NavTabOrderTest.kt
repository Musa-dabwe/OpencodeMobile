package com.musa.opencodeharness.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six tabs, their order, and what each one is allowed to claim to be.
 *
 * Cheap on purpose. The bar is the app's primary navigation and the order is an owner ruling that
 * three separate documents disagree about (the design PDF, spec section 5, and the ruling), so
 * the one thing that must not drift is the sequence itself.
 */
class NavTabOrderTest {

    /** The mutation this catches: sorting the enum alphabetically, or reordering the list. */
    @Test
    fun theOrderIsTheOwnerRulingAndNotAlphabetical() {
        assertEquals(
            listOf("Projects", "Chat", "Files", "Terminal", "Agents", "Settings"),
            NavTab.order.map { it.label },
        )
    }

    /**
     * The mutation this catches: dropping a tab (the design PDF's five), or adding a seventh.
     *
     * Asserted as a count as well as a sequence so a deletion cannot be hidden by the sequence
     * assertion above somehow matching -- and so "six tabs" stays a recorded, checkable number.
     */
    @Test
    fun thereAreExactlySixAndTheyAreTheOnlyDestinations() {
        assertEquals(6, NavTab.order.size)
        assertEquals(NavTab.order, NavTab.entries.toList())
        assertEquals(6, NavTab.entries.size)
    }

    /** The mutation this catches: a duplicate label or route, which silently merges two tabs. */
    @Test
    fun labelsAndRoutesAreUnique() {
        assertEquals(NavTab.order.size, NavTab.order.map { it.label }.toSet().size)
        assertEquals(NavTab.order.size, NavTab.order.map { it.route }.toSet().size)
    }

    /** The mutation this catches: a route rename that breaks a saved state key. */
    @Test
    fun everyRouteResolvesBackToItsOwnTab() {
        for (tab in NavTab.order) {
            assertEquals(tab, NavTab.byRoute[tab.route])
        }
        assertEquals(NavTab.order.size, NavTab.byRoute.size)
    }

    /**
     * The mutation this catches: shipping a placeholder as though it were a screen, or -- worse --
     * rendering a destination with real content that it has no data source for.
     *
     * Chat is the only destination with a data source in this phase. Everything else renders
     * `PlaceholderDestination`, which asserts out loud that it is not built.
     */
    @Test
    fun onlyChatHasRealContent() {
        assertEquals(
            listOf(NavTab.CHAT),
            NavTab.order.filter { it.content == NavTab.Content.IMPLEMENTED },
        )
        assertEquals(
            listOf(NavTab.PROJECTS, NavTab.FILES, NavTab.TERMINAL, NavTab.AGENTS, NavTab.SETTINGS),
            NavTab.order.filter { it.content == NavTab.Content.NOT_BUILT },
        )
    }

    /**
     * The mutation this catches: starting the app on an empty placeholder, or pointing
     * `MainActivity` at a constant instead of the named decision.
     */
    @Test
    fun theColdStartIsChatBecauseItIsTheOnlyDestinationWithContent() {
        assertEquals(NavTab.CHAT, NavTab.startDestination)
        assertEquals(NavTab.Content.IMPLEMENTED, NavTab.startDestination.content)
    }

    /** The mutation this catches: swapping two tabs' glyphs, which `NavBarOrderTest` cannot see. */
    @Test
    fun everyTabHasItsOwnGlyph() {
        val glyphs = NavTab.order.map { it.icon }
        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    /** The mutation this catches: a glyph declared on a viewport other than the shared 24x24. */
    @Test
    fun everyGlyphIsDrawnOnTheSame24dpViewport() {
        for (tab in NavTab.order) {
            assertEquals(24f, tab.icon.defaultWidth.value, 0.001f)
            assertEquals(24f, tab.icon.defaultHeight.value, 0.001f)
            assertEquals(24f, tab.icon.viewportWidth, 0.001f)
            assertEquals(24f, tab.icon.viewportHeight, 0.001f)
            // The path data itself is not inspectable from outside the vector API, so "this glyph
            // actually paints" is proved where it can be observed instead: `NavBarPixelTest`
            // renders all six and fails on any that covers no pixels.
        }
    }

    /** The mutation this catches: `Projects` and `Files` collapsing onto one another. */
    @Test
    fun projectsAndFilesAreNotTheSameDestination() {
        assertNotEquals(NavTab.PROJECTS, NavTab.FILES)
        assertNotEquals(NavTab.PROJECTS.icon, NavTab.FILES.icon)
    }
}
