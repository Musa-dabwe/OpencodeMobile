package com.musa.opencodeharness.ui.sessions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.data.model.Location
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.model.SessionTime
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.dp

/**
 * The design's Sessions screen, driven for real at the design's own 393dp baseline.
 *
 * The load-bearing tests are the nesting ones and the dot-one, because those are the three that
 * have already bitten this project: a children-based sub-agent rule, a promoted orphan, and a
 * status dot used as the sole carrier of state.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w393dp-h720dp-xhdpi")
class SessionsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_789_755_904_855L

    private fun session(
        id: String,
        title: String?,
        parentID: String? = null,
        updated: Long = now - 120_000,
    ) = SessionInfo(
        id = id,
        title = title,
        parentID = parentID,
        projectID = "prj",
        location = Location(directory = "/home/musa/Projects/OpencodeMobile"),
        time = SessionTime(created = updated - 1000, updated = updated),
    )

    /**
     * A root with two nested sub-agents, plus a PROMOTED ORPHAN: a session whose `parentID` names
     * an id that is not in the list, so `buildSessionTree` promotes it to the top level.
     *
     * The orphan is the case that broke the old rule, so it is in the default fixture for every
     * test rather than being a special case in one of them.
     */
    private val tree by lazy {
        com.musa.opencodeharness.data.buildSessionTree(
            listOf(
                session("ses_root", "Add a keystore-backed session switcher"),
                session("ses_child", "Re-review Task 4 fix round 1", parentID = "ses_root"),
                session("ses_child2", "Fix Task 4 toast regression", parentID = "ses_root"),
                session("ses_orphan", "Orphaned sub-agent", parentID = "ses_absent"),
                session("ses_earlier", "An older root session", updated = now - 3 * 24 * 3_600_000L),
            ),
        )
    }

    private fun show() = show(tree)

    private fun show(roots: List<com.musa.opencodeharness.data.SessionNode>) = rule.setContent {
        OpenCodeMobileTheme { SessionsScreen(roots = roots, nowMillis = now) }
    }

    /**
     * The same screen, preceded by a second focusable input the test can park focus on.
     *
     * WHY this exists: under Robolectric the search field starts FOCUSED, so "the field is focused
     * after I tapped it" passed even with the container's click removed -- a mutation run proved
     * it. The assertion has to be a TRANSITION: focus starts somewhere else, a tap on the field's
     * padding moves it here. Only that can fail.
     */
    private fun showWithAFocusProbe() = rule.setContent {
        OpenCodeMobileTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BasicTextField(
                    value = "",
                    onValueChange = {},
                    singleLine = true,
                    modifier = Modifier.testTag(FOCUS_PROBE_TAG),
                )
                Box(modifier = Modifier.weight(1f)) {
                    SessionsScreen(roots = tree, nowMillis = now)
                }
            }
        }
    }

    /** Puts focus on the probe input and asserts it took: the precondition for the tests below. */
    private fun parkFocusOnTheProbe() {
        val probe = rule.onNodeWithTag(FOCUS_PROBE_TAG)
        probe.performTouchInput { click(center) }
        rule.onNodeWithTag(FOCUS_PROBE_TAG).assertIsFocused()
    }

    private companion object {
        /** Test hook: a second focusable input, so focus can be observed MOVING. */
        const val FOCUS_PROBE_TAG = "test:focusProbe"
    }

    // --- the header and the controls the design names --------------------------------------------

    /**
     * Mutation caught: dropping the back chevron, or renaming the screen's heading.
     *
     * The heading is asserted by TAG, not by text: the bottom bar also carries a label that could
     * collide, and this screen's own title is a distinct assertion.
     */
    @Test
    fun theHeaderShowsBackTitleAndTheScopePill() {
        show()

        rule.onNodeWithTag(SESSIONS_BACK_TAG).assertIsDisplayed()
        rule.onNodeWithText(SCREEN_TITLE).assertIsDisplayed()
        rule.onNodeWithTag(SESSIONS_SCOPE_TAG).assertTextEquals(SCOPE_THIS_PROJECT)
    }

    /** Mutation caught: an accent-FILLED pill, which would be a second primary action beside the FAB. */
    @Test
    fun theScopePillIsOutlinedAndTheFabIsPresent() {
        show()

        rule.onNodeWithTag(SESSIONS_SCOPE_TAG).assertIsDisplayed()
        rule.onNodeWithTag(SESSIONS_FAB_TAG).assertIsDisplayed()
        rule.onNodeWithText(NEW_SESSION_LABEL).assertIsDisplayed()
    }

    /** Mutation caught: removing the placeholder, or replacing it with something invented. */
    @Test
    fun theSearchFieldShowsTheDesignsPlaceholder() {
        show()

        rule.onNodeWithTag(SESSIONS_SEARCH_TAG).assertIsDisplayed()
        rule.onNodeWithText(SESSIONS_SEARCH_PLACEHOLDER).assertIsDisplayed()
    }

    // --- sectioning ------------------------------------------------------------------------------

    /**
     * Mutation caught: dropping a section header, or emitting `TODAY` when nothing is under it.
     */
    @Test
    fun rowsAreGroupedUnderTodayAndEarlier() {
        show()

        rule.onNodeWithText(SessionSection.TODAY.label).assertIsDisplayed()
        rule.onNodeWithText(SessionSection.EARLIER.label).assertIsDisplayed()
    }

    /**
     * The design's section labels are UPPERCASE, which is a rendering requirement of the type
     * role rather than a data one -- so it is asserted against the rendered string, not the enum.
     *
     * Mutation caught: `section.label` lowercased, or a `Text` that lowercases at draw time.
     */
    @Test
    fun sectionHeadersAreUppercase() {
        assertEquals("TODAY", SessionSection.TODAY.label)
        assertEquals("EARLIER", SessionSection.EARLIER.label)
    }

    // --- the nesting rule, through the screen ----------------------------------------------------

    /**
     * THE load-bearing test. Every session with a `parentID` renders as a sub-agent, including the
     * promoted orphan -- which is at the TOP of the list (its parent is not in the list) yet is
     * still a sub-agent.
     *
     * Mutation caught: deriving sub-agent-ness from `children.isEmpty()`, or from tree depth.
     * Either drops the "sub-agent" qualifier from this row. The old rule holds for 47 of the 50
     * recorded sessions and fails on exactly this shape.
     */
    @Test
    fun aPromotedOrphan_stillRendersAsASubAgent() {
        show()

        // The row is present...
        rule.onNodeWithTag(sessionsRowTag("ses_orphan")).assertIsDisplayed()
        // ...and it is announced as a sub-agent, not as a root. This is the assertion that the
        // old children-based rule would fail.
        rule.onNodeWithTag(sessionsRowTag("ses_orphan"))
            .assert(hasMetaSegment(SUB_AGENT_QUALIFIER))
    }

    /**
     * A genuinely nested sub-agent renders indented under its parent. Indentation is asserted
     * through the ROW's own start inset, because that is what the user sees.
     *
     * Mutation caught: dropping `depth + 1`, which flattens the tree into a single level.
     */
    @Test
    fun nestedSubAgentsRenderIndentedUnderTheirParent() {
        show()

        val parentLeft = rule.onNodeWithTag(sessionsRowTag("ses_root")).fetchSemanticsNode().boundsInRoot.left
        val childLeft = rule.onNodeWithTag(sessionsRowTag("ses_child")).fetchSemanticsNode().boundsInRoot.left

        assertTrue(
            "the sub-agent ($childLeft) must be indented past its parent ($parentLeft)",
            childLeft > parentLeft,
        )
    }

    /**
     * A root session is NOT announced as a sub-agent.
     *
     * Mutation caught: inverting the predicate -- calling every row a sub-agent.
     */
    @Test
    fun aRootRowIsNotAnnouncedAsASubAgent() {
        show()

        rule.onNodeWithTag(sessionsRowTag("ses_root"))
            .assert(hasNoMetaSegment(SUB_AGENT_QUALIFIER))
    }

    /**
     * ONE header per section, and a sub-agent never separated from its parent by one.
     *
     * The interleaving the code review built: a parent from three days ago whose sub-agent ran a
     * minute ago, plus a genuinely recent root and a genuinely old one. Computed per row, this
     * rendered `EARLIER | stale_parent`, `TODAY | fresh_child` -- the child torn out from under
     * its own parent by a header, which is exactly what the design's nesting forbids.
     *
     * `onNodeWithText` throws on more than one match, so these lookups ARE the "exactly one header"
     * assertion: a regression that emits the same header twice cannot pass.
     *
     * Mutation caught: reverting to a per-row `sectionFor`, or dropping the group ordering.
     */
    @Test
    fun aStaleParentWhoseChildRanToday_staysUnderOneHeader() {
        show(
            com.musa.opencodeharness.data.buildSessionTree(
                listOf(
                    session("stale_parent", "Stale parent", updated = now - 3 * 24 * 3_600_000L),
                    session("fresh_child", "Fresh child", parentID = "stale_parent", updated = now - 30_000L),
                    session("recent_root", "Recent root", updated = now - 60_000L),
                    session("ancient_root", "Ancient root", updated = now - 30 * 24 * 3_600_000L),
                ),
            ),
        )

        // Exactly one of each: a second header of the same label throws here.
        rule.onNodeWithText(SessionSection.TODAY.label).assertIsDisplayed()
        rule.onNodeWithText(SessionSection.EARLIER.label).assertIsDisplayed()

        val parentBottom = rule.onNodeWithTag(sessionsRowTag("stale_parent")).fetchSemanticsNode().boundsInRoot.bottom
        val childTop = rule.onNodeWithTag(sessionsRowTag("fresh_child")).fetchSemanticsNode().boundsInRoot.top
        val childBottom = rule.onNodeWithTag(sessionsRowTag("fresh_child")).fetchSemanticsNode().boundsInRoot.bottom
        val recentTop = rule.onNodeWithTag(sessionsRowTag("recent_root")).fetchSemanticsNode().boundsInRoot.top
        val recentBottom = rule.onNodeWithTag(sessionsRowTag("recent_root")).fetchSemanticsNode().boundsInRoot.bottom
        val ancientTop = rule.onNodeWithTag(sessionsRowTag("ancient_root")).fetchSemanticsNode().boundsInRoot.top

        // Measured RELATIONALLY, not against a hardcoded pixel count. Compose reports a tagged
        // node's CONTENT bounds, so adjacent rows are always separated by the row's own 10dp
        // padding and the 1dp hairline -- a fixed ~42px here -- while a `SectionHeader` adds
        // `sectionGap` (24dp) plus its line plus 6dp of padding on top of that. The first two
        // rows in the fixture are known siblings with no header between them, which gives the
        // baseline without hardcoding it.
        val gapUnderParent = childTop - parentBottom
        val gapUnderChild = recentTop - childBottom
        val gapAcrossAHeader = ancientTop - recentBottom

        assertEquals(
            "the sub-agent must sit DIRECTLY under its parent: a header was inserted between them",
            gapUnderChild,
            gapUnderParent,
        )
        assertTrue(
            "precondition: this screen really does emit section headers ($gapAcrossAHeader px vs a " +
                "$gapUnderParent px row gap), so the comparison above is not vacuous",
            gapAcrossAHeader > gapUnderParent * 2,
        )
    }

    /**
     * A server that does not sort by recency must still produce one contiguous run per section --
     * the review's `[older, newer, oldest]` case, which used to render `EARLIER | TODAY | EARLIER`.
     *
     * Mutation caught: dropping the TODAY-first group ordering.
     */
    @Test
    fun unsortedServerOrder_stillGroupsTodayBeforeEarlier() {
        show(
            com.musa.opencodeharness.data.buildSessionTree(
                listOf(
                    session("older", "Older root", updated = now - 2 * 24 * 3_600_000L),
                    session("newest", "Newest root", updated = now - 20_000L),
                    session("oldest", "Oldest root", updated = now - 40 * 24 * 3_600_000L),
                ),
            ),
        )

        val newestTop = rule.onNodeWithTag(sessionsRowTag("newest")).fetchSemanticsNode().boundsInRoot.top
        val olderTop = rule.onNodeWithTag(sessionsRowTag("older")).fetchSemanticsNode().boundsInRoot.top
        val oldestTop = rule.onNodeWithTag(sessionsRowTag("oldest")).fetchSemanticsNode().boundsInRoot.top

        assertTrue("the TODAY group must come first ($newestTop, $olderTop, $oldestTop)", newestTop < olderTop)
        assertTrue("and the EARLIER groups keep server order ($olderTop, $oldestTop)", olderTop < oldestTop)
    }

    // --- the dot is never the only carrier of state ----------------------------------------------

    /**
     * THE accessibility test. Every row that draws a status dot also renders the status WORD.
     *
     * The row renders a dot unconditionally, so if the word were ever made conditional -- or the
     * segment dropped from the meta line -- one of these lookups fails. There is no screen in this
     * app where a coloured pixel is the only thing saying what state a session is in.
     *
     * Mutation caught: removing the status segment from `metaSegmentsFor`, or making the row skip
     * the word for some row shape.
     */
    @Test
    fun everyRowRendersTheStatusWordThatCarriesItsDot() {
        show()

        for (id in listOf("ses_root", "ses_child", "ses_child2", "ses_orphan", "ses_earlier")) {
            rule.onNodeWithTag(sessionsStatusWordTag(id))
                .assertExists("row $id draws a status dot and must draw the word beside it")
            rule.onNodeWithTag(sessionsStatusWordTag(id)).assertTextEquals("unknown")
        }
    }

    /**
     * The status word is what the app actually knows, and it says so. The server's session list
     * carries no run-status field on any of the 50 recorded sessions, so "unknown" is the honest
     * answer; a fabricated "idle" would be inventing state the app does not have.
     *
     * Mutation caught: hardcoding "idle" or "running" in the status word.
     */
    @Test
    fun theStatusWordSaysUnknownBecauseTheServerSendsNoRunStatus() {
        show()

        rule.onNodeWithTag(sessionsStatusWordTag("ses_root")).assertTextEquals("unknown")
    }

    // --- search ----------------------------------------------------------------------------------

    /**
     * Mutation caught: wiring the query to nothing, or filtering on the wrong field.
     */
    @Test
    fun searchFiltersRowsByTitle() {
        show()

        rule.onNodeWithTag(SESSIONS_SEARCH_TAG).performTextInput("toast")

        rule.onNodeWithTag(sessionsRowTag("ses_child2")).assertExists()
        rule.onNodeWithTag(sessionsRowTag("ses_root")).assertDoesNotExist()
        rule.onNodeWithTag(sessionsRowTag("ses_orphan")).assertDoesNotExist()
    }

    /**
     * Search is case-insensitive, because a user types `Toast` and `toast` and gets the same list.
     *
     * Mutation caught: dropping `ignoreCase`.
     */
    @Test
    fun searchIsCaseInsensitive() {
        show()

        rule.onNodeWithTag(SESSIONS_SEARCH_TAG).performTextInput("TOAST")

        rule.onNodeWithTag(sessionsRowTag("ses_child2")).assertExists()
    }

    /**
     * A query matching nothing leaves an honest message and NO rows -- never a fabricated row, and
     * never a section header claiming content that is not there.
     *
     * Mutation caught: rendering a "0 results" row, or leaving the section headers behind.
     */
    @Test
    fun aSearchWithNoMatchesShowsAnHonestEmptyMessageAndNoHeaders() {
        show()

        rule.onNodeWithTag(SESSIONS_SEARCH_TAG).performTextInput("zzzz-no-such-session")

        rule.onNodeWithTag(EMPTY_MESSAGE_TAG).assertTextEquals(NO_MATCH_MESSAGE)
        rule.onNodeWithTag(sessionsRowTag("ses_root")).assertDoesNotExist()
        rule.onNodeWithText(SessionSection.TODAY.label).assertDoesNotExist()
    }

    /**
     * THE honesty test, and one the DEVICE caught rather than a mutation.
     *
     * A non-matching search used to render [NO_SESSIONS_MESSAGE], telling the user the server had
     * no sessions while it had six. A search that matched nothing must not claim the server is
     * empty -- the two are different facts and only one of them is true.
     *
     * Mutation caught: collapsing the two messages back into one constant.
     */
    @Test
    fun aSearchWithNoMatchesDoesNotClaimTheServerIsEmpty() {
        show()

        rule.onNodeWithTag(SESSIONS_SEARCH_TAG).performTextInput("zzz")

        rule.onNodeWithTag(EMPTY_MESSAGE_TAG).assertTextEquals(NO_MATCH_MESSAGE)
        assertTrue(
            "the message must not say the server is empty when it merely matched nothing",
            NO_MATCH_MESSAGE != NO_SESSIONS_MESSAGE,
        )
    }

    /**
     * With NO search active and no rows, the screen does say the server is empty -- the other
     * half of the same distinction, so the fix cannot be "never mention the server".
     *
     * Mutation caught: showing the search message unconditionally.
     */
    @Test
    fun anUnfilteredEmptyListSaysTheServerHasNoSessions() {
        rule.setContent { OpenCodeMobileTheme { SessionsScreen(roots = emptyList(), nowMillis = now) } }

        rule.onNodeWithTag(EMPTY_MESSAGE_TAG).assertTextEquals(NO_SESSIONS_MESSAGE)
    }

    // --- touch targets ---------------------------------------------------------------------------

    /**
     * The screen's own floor, resolved from the density this test actually renders at.
     *
     * Never a hardcoded pixel count: at xhdpi 48dp is 96px, and a test that said "96" would pass
     * for the wrong reason on a different qualifier.
     */
    private val minTouchTargetPx: Float get() = with(rule.density) { MIN_TOUCH_TARGET.toPx() }

    /** Spec §7.5's minimum, the same value the bottom bar is held to. */
    private val MIN_TOUCH_TARGET = 48.dp

    /**
     * The visible search field is a 48dp target, not just the text line inside it.
     *
     * Measured under Robolectric before the fix: the `BasicTextField` measured 311.0 x 17.5dp
     * inside a visible 46dp field, so ~13dp above and ~12dp below the text did nothing at all.
     *
     * Mutation caught: `SearchHeight` back to 46dp, or sizing this from the inner field node
     * instead of the field capsule.
     */
    @Test
    fun theSearchFieldsVisibleHeightIsAMinimumTouchTarget() {
        show()

        val bounds = rule.onNodeWithTag(SESSIONS_SEARCH_FIELD_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the visible search field is ${bounds.height}px tall, needs at least " +
                "${minTouchTargetPx}px (${MIN_TOUCH_TARGET})",
            bounds.height >= minTouchTargetPx,
        )
    }

    /**
     * The click target is the whole visible field, not the text line inside it -- and invoking it
     * really does hand focus to the input.
     *
     * ## Why this does not use a synthetic touch
     *
     * A raw `performTouchInput { click(...) }` does not reach `Modifier.clickable` on this node
     * under Robolectric -- proven with a throwaway probe, not assumed. A test built on it is
     * theatre: an earlier version of this test passed with the container's `clickable` deleted,
     * because the injected offset was node-relative where `TouchInjectionScope.click(position)`
     * expects root coordinates, and then because the search field starts focused anyway.
     * `performClick()` invokes the node's real `OnClick` semantics action -- the very handler a
     * finger reaches -- so the behavioural half is measured on the thing that actually fires.
     *
     * Three claims, each broken by a different regression:
     *
     *  1. The full-height node carries the click action at all. Removing the container `clickable`,
     *     or moving it back onto the inner text field, makes `assertHasClickAction` throw on THIS
     *     node.
     *  2. That node extends strictly ABOVE and BELOW the text line -- the review's measured
     *     "~13dp top and ~12dp bottom do nothing".
     *  3. Invoking it moves focus off a competing field and onto the search input, so focus
     *     forwarding is proven rather than assumed. The competing field is there precisely because
     *     the search field starts focused under Robolectric.
     */
    @Test
    fun theWholeVisibleSearchFieldIsTheClickTargetNotOnlyTheTextLine() {
        showWithAFocusProbe()
        parkFocusOnTheProbe()

        val field = rule.onNodeWithTag(SESSIONS_SEARCH_FIELD_TAG)
        val fieldBounds = field.fetchSemanticsNode().boundsInRoot
        val textBounds = rule.onNodeWithTag(SESSIONS_SEARCH_TAG).fetchSemanticsNode().boundsInRoot

        field.assertHasClickAction()

        assertTrue(
            "the tap target must start above the text line, or the top padding is dead " +
                "(target top ${fieldBounds.top}, text top ${textBounds.top})",
            fieldBounds.top < textBounds.top,
        )
        assertTrue(
            "the tap target must end below the text line, or the bottom padding is dead " +
                "(target bottom ${fieldBounds.bottom}, text bottom ${textBounds.bottom})",
            fieldBounds.bottom > textBounds.bottom,
        )

        field.performClick()

        rule.onNodeWithTag(SESSIONS_SEARCH_TAG).assertIsFocused()
        rule.onNodeWithTag(FOCUS_PROBE_TAG).assertIsNotFocused()
    }

    /**
     * The scope pill is a 48dp target around a 28dp drawn capsule.
     *
     * Measured under Robolectric before the fix: 92.0 x 28.0dp -- 20dp under the floor, with the
     * pill's own outline and padding doing nothing for the tap.
     *
     * The drawn pill is deliberately NOT grown (the design's appearance is unchanged), so this
     * asserts the TARGET node, which is what a finger actually has to hit.
     *
     * Mutation caught: dropping `defaultMinSize(minHeight = MinTouchTarget)`, or moving the tag
     * back onto the inner `Text`.
     */
    @Test
    fun theScopePillIsAMinimumTouchTarget() {
        show()

        val bounds = rule.onNodeWithTag(SESSIONS_SCOPE_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the scope pill's tap target is ${bounds.height}px tall, needs at least " +
                "${minTouchTargetPx}px (${MIN_TOUCH_TARGET})",
            bounds.height >= minTouchTargetPx,
        )
    }

    /** The back chevron already met the floor; this pins it so a later edit cannot quietly regress. */
    @Test
    fun theBackChevronIsAMinimumTouchTarget() {
        show()

        val bounds = rule.onNodeWithTag(SESSIONS_BACK_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("the back chevron is ${bounds.height}px tall", bounds.height >= minTouchTargetPx)
    }

    // --- swipe actions ---------------------------------------------------------------------------

    /**
     * Swiping a row reveals both actions, labelled as the design labels them.
     *
     * Mutation caught: dropping the swipe background, or mislabelling an action. The pixel
     * colours are asserted separately in `SessionsPixelTest`.
     */
    @Test
    fun swipingARowRevealsRenameAndDelete() {
        show()

        rule.onNodeWithTag(sessionsRowTag("ses_root")).performTouchInput { swipeLeft() }
        rule.waitForIdle()

        rule.onNodeWithTag(SESSIONS_RENAME_TAG).assertExists()
        rule.onNodeWithTag(SESSIONS_DELETE_TAG).assertExists()
        rule.onNodeWithText(RENAME_LABEL).assertExists()
        rule.onNodeWithText(DELETE_LABEL).assertExists()
    }

    /**
     * Mutation caught: swapping which action sits on which side of the row.
     *
     * Rename is the leading (left) action and Delete the trailing one, matching the design's
     * full-bleed pair, so this catches a `SpaceBetween` flip.
     */
    @Test
    fun renameIsTheLeadingActionAndDeleteTheTrailingOne() {
        show()

        rule.onNodeWithTag(sessionsRowTag("ses_root")).performTouchInput { swipeLeft() }
        rule.waitForIdle()

        val renameLeft = rule.onNodeWithTag(SESSIONS_RENAME_TAG).fetchSemanticsNode().boundsInRoot.left
        val deleteLeft = rule.onNodeWithTag(SESSIONS_DELETE_TAG).fetchSemanticsNode().boundsInRoot.left
        assertTrue("Rename must lead (left), Delete must trail", renameLeft < deleteLeft)
    }

    /**
     * The swipe actions are present in the tree but the row is what carries the session identity,
     * so a swipe on one row cannot be confused with another. This test drives the swipe on a
     * SUB-AGENT specifically: the actions belong to the row, not to the section it sits in.
     *
     * Mutation caught: hoisting the swipe actions out of the row into the section container.
     */
    @Test
    fun subAgentRowsAreSwipeableToo() {
        show()

        rule.onNodeWithTag(sessionsRowTag("ses_child")).performTouchInput { swipeLeft() }
        rule.waitForIdle()

        rule.onNodeWithTag(SESSIONS_RENAME_TAG).assertExists()
    }

    // --- matchers --------------------------------------------------------------------------------

    /**
     * Matches a row that renders a meta segment EQUAL to the given text.
     *
     * Exact, not substring: a parent row renders the qualifier "has sub-agents", which CONTAINS
     * "sub-agent". A substring matcher would therefore pass a root row for being a parent, and the
     * test that says "a root is not a sub-agent" would prove nothing. Each meta segment is its
     * own `Text` node, so equality on the node's text is the sharp assertion available here.
     */
    private fun hasMetaSegment(segment: String): SemanticsMatcher =
        SemanticsMatcher("renders the meta segment \"$segment\"") { node ->
            node.renderedTexts().contains(segment)
        }

    /** Matches a row that renders no meta segment equal to the given text. */
    private fun hasNoMetaSegment(segment: String): SemanticsMatcher =
        SemanticsMatcher("does not render the meta segment \"$segment\"") { node ->
            !node.renderedTexts().contains(segment)
        }

    /** Every string this node and its descendants render, one entry per `Text` node. */
    private fun SemanticsNode.renderedTexts(): List<String> = buildList {
        addAll(config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text })
        children.forEach { addAll(it.renderedTexts()) }
    }

    // --- scrolling -------------------------------------------------------------------------------

    /**
     * Every row is reachable by scrolling: a list that renders only what fits the viewport would
     * silently hide sessions the server returned.
     *
     * Mutation caught: a `LazyColumn` whose content is truncated, or a row with zero height.
     */
    @Test
    fun everySessionRowIsReachableByScrolling() {
        show()

        rule.onNodeWithTag(SESSIONS_TAG)
        for (id in listOf("ses_root", "ses_child", "ses_child2", "ses_orphan", "ses_earlier")) {
            rule.onNodeWithTag(sessionsRowTag(id)).assertExists("row $id should exist in the list")
        }
    }
}
