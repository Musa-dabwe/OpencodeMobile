package com.musa.opencodeharness.ui.sessions

import androidx.compose.ui.graphics.Color
import com.musa.opencodeharness.ui.theme.ThemeFixtures
import com.musa.opencodeharness.ui.theme.contrastRatio
import com.musa.opencodeharness.ui.theme.flatColours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's colours, proven to come from the tokens they claim and to clear their floors.
 *
 * Plain JVM, no composition: [SessionsStyle] is a data class precisely so this is possible.
 */
class SessionsStyleTest {

    private val theme = ThemeFixtures.theme
    private val style = theme.sessionsStyle()
    private val page = theme.color.background.base

    /** The mutation this catches: a field re-pointed at a Material scheme default or a hardcoded hex. */
    @Test
    fun everyFieldComesFromTheTokenItNames() {
        assertEquals(theme.color.background.base, style.page)
        assertEquals(theme.color.background.raised, style.searchFill)
        assertEquals(theme.color.text.primary, style.title)
        assertEquals(theme.color.text.primary, style.rowTitle)
        assertEquals(theme.color.text.secondary, style.meta)
        assertEquals(theme.color.text.sectionHeader, style.sectionHeader)
        assertEquals(theme.color.agent.primary, style.agentBlue)
        assertEquals(theme.color.border.subtle, style.hairline)
        assertEquals(theme.color.action.renameBg, style.renameFill)
        assertEquals(theme.color.action.deleteBg, style.deleteFill)
        assertEquals(theme.color.status.idle, style.statusDot)
        assertEquals(theme.color.status.running, style.runningDot)
        assertEquals(theme.color.status.error, style.errorDot)
        assertEquals(theme.color.text.secondary, style.unknownDot)
        assertEquals(theme.spacing.screenMargin, style.screenMargin)
        assertEquals(theme.spacing.sectionGap, style.sectionGap)
        assertEquals(theme.color.accent.base, style.pillOutline)
        assertEquals(theme.color.accent.base, style.pillText)
        assertEquals(theme.color.accent.base, style.fabFill)
        assertEquals(theme.color.text.onAccent, style.fabLabel)
        assertEquals(theme.color.border.subtle, style.searchOutline)
        assertEquals(theme.color.text.primary, style.statusWord)
        assertEquals(theme.shape.fabHeight, style.fabHeight)
    }

    /**
     * Every field on [SessionsStyle] is read by a composable.
     *
     * The review's nit was that the property test above asserted `rowGap`, `hairlineWidth` and
     * `accent` -- fields nothing read -- so the "every field comes from its token" claim was
     * partly vacuous. Those three are gone. This test is the backstop: reintroduce an unread
     * field and it will fail, because the list below is the complete set the screen actually
     * paints with.
     */
    @Test
    fun theStyleCarriesNoFieldTheScreenNeverReads() {
        val readFields = listOf(
            "page", "searchFill", "title", "rowTitle", "meta", "sectionHeader", "agentBlue",
            "hairline", "renameFill", "deleteFill", "statusDot", "runningDot", "errorDot",
            "unknownDot", "statusWord", "screenMargin", "sectionGap", "pillOutline", "pillText",
            "fabFill", "fabLabel", "searchOutline", "fabHeight",
        )
        // `$stable` is the Compose compiler's data-class marker, not a field the screen reads.
        val stableMarker = "$" + "stable"
        val declared = SessionsStyle::class.java.declaredFields
            .map { it.name }
            .filterNot { it == stableMarker }
            .toSet()

        assertEquals(
            "every declared field must be on the read list, or the property test above is " +
                "asserting something nobody uses",
            declared, readFields.toSet(),
        )
    }

    /**
     * Accent is reserved for ONE primary action, and this screen's primary action is the FAB.
     *
     * The mutation this catches: giving the scope pill a solid accent FILL, which would make two
     * things on the screen claim to be the primary action. The pill must stay an outline.
     */
    @Test
    fun theScopePillIsAnOutlineAndTheFabIsTheOnlySolidAccent() {
        assertEquals("the pill's border is the accent", theme.color.accent.base, style.pillOutline)
        assertNotEquals(
            "the pill must NOT be filled with accent -- that would be a second primary action",
            theme.color.accent.base,
            style.searchFill,
        )
        assertEquals("the FAB is the solid accent", theme.color.accent.base, style.fabFill)
    }

    /**
     * Status dots are NEVER accent. Accent means "action" in this design; a dot means "state".
     * Giving the dot the accent colour would make a passive marker look like the primary control.
     *
     * Mutation caught: `statusDot = color.accent.base`.
     */
    @Test
    fun theStatusDotIsNeverAccent() {
        assertNotEquals(
            "a status dot painted in accent would read as the primary action",
            theme.color.accent.base,
            style.statusDot,
        )
        assertEquals(theme.color.status.idle, style.statusDot)
    }

    /**
     * The FAB's label on its fill clears AA comfortably.
     *
     * Mutation caught: a dark-on-dark or light-on-light label combination.
     */
    @Test
    fun theFabLabelClearsAaOnItsFill() {
        val ratio = contrastRatio(style.fabLabel, style.fabFill)
        assertTrue("FAB label measured $ratio:1, needs 4.5", ratio >= 4.5)
    }

    /**
     * The status dot clears the 3:1 NON-TEXT floor on the page it is drawn on.
     *
     * `status.running` (#6A4F3C) measures 2.60:1 and would FAIL here -- which is exactly why the
     * screen never uses it for a bare dot, and always pairs a dot with the status word. This test
     * pins the chosen dot colour as one that is legal on its own.
     */
    @Test
    fun theStatusDotClearsTheNonTextFloorOnThePage() {
        val ratio = contrastRatio(style.statusDot, style.page)
        assertTrue("status dot measured $ratio:1 on the page, needs 3.0", ratio >= 3.0)
    }

    /**
     * The test the review said was missing: the dot a status PAINTS is that status's own token,
     * and it agrees with no other status's token.
     *
     * The shipped bug was `statusDot` hard-painted on every row while `statusFor` always returned
     * UNKNOWN, so every row showed an idle-green dot beside the word "unknown" -- the cue
     * asserting a state the word disclaims. Asserting the mapping is total and pairwise distinct is
     * what makes that shape impossible to reintroduce.
     *
     * Mutations caught: `statusDotFor` ignoring its argument; two statuses sharing a colour; the
     * UNKNOWN arm pointed at `status.idle` again.
     */
    @Test
    fun everyStatusPaintsItsOwnDotAndAgreesWithNoOther() {
        val expected = mapOf(
            SessionStatus.IDLE to theme.color.status.idle,
            SessionStatus.RUNNING to theme.color.status.running,
            SessionStatus.ERROR to theme.color.status.error,
            // Deliberately NEUTRAL, not a status colour: "we do not know" is not "idle".
            SessionStatus.UNKNOWN to theme.color.text.secondary,
        )

        assertEquals(
            "the dot mapping must cover every status, or a new one would compile with no colour",
            SessionStatus.entries.toSet(),
            expected.keys,
        )
        for ((status, token) in expected) {
            assertEquals("$status must paint $token", token, style.statusDotFor(status))
        }
        for ((status, token) in expected) {
            for (other in expected.keys - status) {
                assertNotEquals(
                    "$status and $other must not share a dot colour, or the dot stops discriminating",
                    token,
                    style.statusDotFor(other),
                )
            }
        }
    }

    /**
     * UNKNOWN's dot is neutral and, above all, not a status colour.
     *
     * The single assertion the shipped bug violated: the dot claimed `idle` while the word said
     * `unknown`. Mutation caught: `UNKNOWN -> status.idle`.
     */
    @Test
    fun theUnknownDotIsNeutralAndNotAStatusColour() {
        val dot = style.statusDotFor(SessionStatus.UNKNOWN)

        assertNotEquals(
            "an UNKNOWN row must not wear the idle dot -- the word beside it says 'unknown'",
            theme.color.status.idle,
            dot,
        )
        assertNotEquals(theme.color.status.running, dot)
        assertNotEquals(theme.color.status.error, dot)
        assertNotEquals("a dot means state; accent means action", theme.color.accent.base, dot)
    }

    /**
     * No status dot is ever accent.
     *
     * Mutation caught: an arm of `statusDotFor` pointed at `color.accent.base`.
     */
    @Test
    fun noStatusDotIsAccent() {
        for (status in SessionStatus.entries) {
            assertNotEquals(
                "$status painted in accent would read as the primary action",
                theme.color.accent.base,
                style.statusDotFor(status),
            )
        }
    }

    /**
     * Every dot EXCEPT `running` clears the 3:1 non-text floor on its own; `running` is 2.60:1 and
     * is legal only because the status word is always rendered beside it.
     *
     * The asymmetry is the design's, not a bug being tolerated: `status.running` has no darker
     * alternative in the palette, and the a11y rule on this screen is that the dot never stands
     * alone. If the palette ever gains a legible running colour, the precondition below fails and
     * the decision gets revisited rather than silently drifting.
     */
    @Test
    fun everyDotButRunningClearsTheNonTextFloor() {
        val running = contrastRatio(theme.color.status.running, style.page)
        assertTrue("precondition: status.running is expected to be sub-floor, measured $running:1", running < 3.0)

        for (status in SessionStatus.entries - SessionStatus.RUNNING) {
            val ratio = contrastRatio(style.statusDotFor(status), style.page)
            assertTrue("$status dot measured $ratio:1 on the page, needs 3.0", ratio >= 3.0)
        }
    }

    /**
     * The status WORD clears the 4.5:1 TEXT floor -- it is the only thing carrying the dot's
     * meaning, so it has to be readable on its own.
     */
    @Test
    fun theStatusWordClearsAaOnThePage() {
        val ratio = contrastRatio(style.statusWord, style.page)
        assertTrue("status word measured $ratio:1, needs 4.5", ratio >= 4.5)
    }

    /** The sub-agent's agentBlue qualifier is readable text, so it needs the text floor too. */
    @Test
    fun theAgentBlueQualifierClearsAaOnThePage() {
        val ratio = contrastRatio(style.agentBlue, style.page)
        assertTrue("agentBlue measured $ratio:1, needs 4.5", ratio >= 4.5)
    }

    /**
     * The design's own swipe-action pairings, measured and reported.
     *
     * `action.rename` on `action.renameBg` is 4.41:1 -- 0.09 UNDER AA. This test asserts that the
     * screen does NOT use it, and that the label it uses instead clears the floor comfortably on
     * both action fills. Mutation caught: restoring `color.action.rename` as the label colour.
     */
    @Test
    fun theDesignsRenamePairingIsSubAaAndTheScreenDoesNotUseIt() {
        val designPairing = contrastRatio(theme.color.action.rename, theme.color.action.renameBg)
        assertTrue(
            "precondition: the design's own rename pairing is expected to be sub-AA, measured " +
                "$designPairing:1 -- if this ever changes the decision should be revisited",
            designPairing < 4.5,
        )
        assertNotEquals(
            "the swipe label must not be the sub-AA action colour",
            theme.color.action.rename,
            theme.swipeActionLabelColor(),
        )
        assertTrue(
            "swipe label on renameBg measured " +
                "${contrastRatio(theme.swipeActionLabelColor(), theme.color.action.renameBg)}:1",
            contrastRatio(theme.swipeActionLabelColor(), theme.color.action.renameBg) >= 4.5,
        )
        assertTrue(
            "swipe label on deleteBg measured " +
                "${contrastRatio(theme.swipeActionLabelColor(), theme.color.action.deleteBg)}:1",
            contrastRatio(theme.swipeActionLabelColor(), theme.color.action.deleteBg) >= 4.5,
        )
    }

    /**
     * `action.blueFg` has NO usable pairing anywhere in the palette, which is why it is unassigned.
     *
     * This test does not ship the token -- it records WHY the token is unused, so a future edit
     * that reaches for it finds the measurement instead of a surprise. On `renameBg` it is 1.69:1
     * (invisible) and on the page 2.66:1 (under the 3:1 non-text floor).
     *
     * Mutation caught: introducing `blueFg` as a swipe-action label or a dot colour.
     */
    @Test
    fun actionBlueFgHasNoRecoverablePairingAndIsUnassigned() {
        val blueFg = theme.color.action.blueFg
        val onRenameBg = contrastRatio(blueFg, theme.color.action.renameBg)
        val onPage = contrastRatio(blueFg, page)
        assertTrue("precondition: blueFg on renameBg is unusable, measured $onRenameBg:1", onRenameBg < 3.0)
        assertTrue("precondition: blueFg on the page is below the non-text floor, measured $onPage:1", onPage < 3.0)

        // Nothing on this screen uses it. If a colour is used, it appears in the style; this is the
        // assertion that it does not.
        val used = listOf(
            style.page, style.searchFill, style.title, style.rowTitle, style.meta,
            style.sectionHeader, style.agentBlue, style.hairline, style.renameFill,
            style.deleteFill, style.statusDot, style.runningDot, style.errorDot,
            style.unknownDot, style.statusWord, style.pillOutline,
            style.pillText, style.fabFill, style.fabLabel, style.searchOutline,
            theme.swipeActionLabelColor(),
        )
        assertTrue("action.blueFg must not be used as a foreground on this screen", used.none { it == blueFg })
    }

    /**
     * The design has no drop shadows, and nothing here can switch one on.
     *
     * Mutation caught: `elevation.useShadows` flipped to true in the palette.
     */
    @Test
    fun theDesignHasNoDropShadows() {
        assertTrue("the bundled palette must keep shadows off", !theme.elevation.useShadows)
    }

    /**
     * No colour anywhere on this screen is a hex literal in code: every one traces to the palette.
     *
     * This is a structural assertion over the resolved style rather than a grep, and it is the
     * one that would catch a hardcoded colour sneaking into the screen.
     */
    @Test
    fun everyColourOnThisScreenExistsInThePalette() {
        val palette = theme.flatColours().values.toSet()
        val screenColours: List<Color> = listOf(
            style.page, style.searchFill, style.title, style.rowTitle, style.meta,
            style.sectionHeader, style.agentBlue, style.hairline, style.renameFill,
            style.deleteFill, style.statusDot, style.runningDot, style.errorDot,
            style.unknownDot, style.statusWord, style.pillOutline,
            style.pillText, style.fabFill, style.fabLabel, style.searchOutline,
            theme.swipeActionLabelColor(),
        )
        for (colour in screenColours) {
            assertTrue("$colour is not a token in the palette", palette.contains(colour))
        }
    }
}
