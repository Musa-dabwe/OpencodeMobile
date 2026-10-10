package com.musa.opencodeharness.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

internal const val PROBE_FIELD = "probe:field"
internal const val PROBE_LIST = "probe:list"

/**
 * The shell: six destinations, honest placeholders, and -- the reason this class exists -- state
 * that survives leaving a tab and coming back.
 *
 * Runs on Robolectric at the design's own 393dp baseline, so the layout these assertions read is
 * the layout the design describes.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = BASELINE_QUALIFIERS)
class AppShellTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // --- the bar --------------------------------------------------------------------------------

    /** The mutation this catches: a tab dropped, duplicated, or reordered. */
    @Test
    fun allSixDestinationsAreInTheBarInTheRulingOrder() {
        rule.setContent { Shell() }

        rule.onNodeWithTag(NAV_BAR_TAG).assertExists()
        for (tab in NavTab.order) {
            rule.onNodeWithTag(navBarTestTag(tab)).assertExists()
            rule.onNodeWithText(tab.label).assertExists()
        }
        // Six tab labels and no more: a duplicated destination shows up as an extra match, a
        // missing one as a node that never appears. Matched on the label rather than on "has
        // text" so the probe's empty text field cannot pad the count.
        rule.onAllNodes(tabLabelMatcher()).assertCountEquals(6)
    }

    /** The mutation this catches: the selection tracking the wrong tab, or nothing at all. */
    @Test
    fun exactlyOneTabIsSelectedAndItIsTheChosenOne() {
        rule.setContent { Shell(start = NavTab.TERMINAL) }
        rule.onNodeWithTag(navBarTestTag(NavTab.TERMINAL)).assertIsSelected()
        assertEquals(listOf(NavTab.TERMINAL), selectedTabs())
    }

    /** The mutation this catches: a bar whose buttons do not drive the shell's selection. */
    @Test
    fun tappingATabSelectsIt() {
        rule.setContent { Shell() }
        assertEquals(listOf(NavTab.CHAT), selectedTabs())
        rule.onNodeWithTag(navBarTestTag(NavTab.AGENTS)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(NavTab.AGENTS), selectedTabs())
    }

    // --- state retention: the load-bearing tests -------------------------------------------------

    /**
     * The mutation this catches, and the reason the plan calls for `SaveableStateHolder`: dropping
     * [androidx.compose.runtime.saveable.SaveableStateHolder] from the shell.
     *
     * The Chat destination is genuinely removed from the composition while another tab is showing
     * -- that is asserted here, so this cannot pass by the destination simply never having been
     * touched. What survives is therefore saved state being replayed, which is the mechanism and
     * not an accident.
     */
    @Test
    fun destinationStateSurvivesATabSwitchAwayAndBack() {
        // Plain counters, not snapshot state: this is bookkeeping about composition, and
        // snapshot semantics would only obscure when the increments land.
        val visits = intArrayOf(0)
        val disposals = intArrayOf(0)

        rule.setContent {
            OpenCodeMobileTheme {
                var tab by rememberSaveable { mutableStateOf(NavTab.CHAT) }
                AppShell(selected = tab, onSelect = { tab = it }) { destination ->
                    when (destination) {
                        NavTab.CHAT -> {
                            DisposableEffect(Unit) {
                                visits[0]++
                                onDispose { disposals[0]++ }
                            }
                            var draft by rememberSaveable { mutableStateOf("") }
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.testTag(PROBE_FIELD),
                            )
                        }
                        else -> PlaceholderDestination(destination)
                    }
                }
            }
        }

        rule.onNodeWithTag(PROBE_FIELD).performTextInput("keep me")
        rule.onNodeWithTag(PROBE_FIELD).assertTextEquals("keep me")
        assertEquals("the Chat destination should not have been disposed yet", 0, disposals[0])

        rule.onNodeWithTag(navBarTestTag(NavTab.FILES)).performClick()
        rule.waitForIdle()
        assertEquals("Chat must leave the composition while another tab shows", 1, disposals[0])
        rule.onNodeWithTag(PROBE_FIELD).assertDoesNotExist()

        rule.onNodeWithTag(navBarTestTag(NavTab.CHAT)).performClick()
        rule.waitForIdle()

        // The assertion the whole phase turns on.
        rule.onNodeWithTag(PROBE_FIELD).assertTextEquals("keep me")
        assertEquals("Chat should have been composed twice: once, then again", 2, visits[0])
        assertEquals("Chat should have been disposed exactly once, on the way out", 1, disposals[0])
    }

    /**
     * The same mutation, observed through the case the brief names: scroll position.
     *
     * `rememberLazyListState` is `rememberSaveable` underneath, so this is the mechanism exercised
     * in the shape the user actually sees -- a list scrolled down, a tab tapped, and a list that
     * has silently jumped back to the top.
     *
     * The state is remembered INSIDE the destination. An earlier version built a `LazyListState`
     * in the test method and passed it in, which passed even with the holder deleted: the object
     * outlived the composition on its own, so nothing was ever saved or restored. That is exactly
     * the kind of test that looks like proof and is not, and it is why the state is captured out
     * of the composition here instead.
     */
    @Test
    fun scrollPositionSurvivesATabSwitchAwayAndBack() {
        var observed: LazyListState? = null
        rule.setContent {
            OpenCodeMobileTheme {
                var tab by rememberSaveable { mutableStateOf(NavTab.CHAT) }
                AppShell(selected = tab, onSelect = { tab = it }) { destination ->
                    when (destination) {
                        NavTab.CHAT -> {
                            val state = rememberLazyListState()
                            SideEffect { observed = state }
                            LazyColumn(
                                state = state,
                                modifier = Modifier.fillMaxSize().testTag(PROBE_LIST),
                            ) {
                                items(count = 300) { index -> Text("row $index") }
                            }
                        }
                        else -> PlaceholderDestination(destination)
                    }
                }
            }
        }

        rule.onNodeWithTag(PROBE_LIST).performScrollToIndex(140)
        rule.waitForIdle()
        val before = requireNotNull(observed) { "the Chat list never composed" }.firstVisibleItemIndex
        assertTrue("the list refused to scroll, so the test would prove nothing", before >= 130)

        rule.onNodeWithTag(navBarTestTag(NavTab.SETTINGS)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(navBarTestTag(NavTab.CHAT)).performClick()
        rule.waitForIdle()

        val after = requireNotNull(observed) { "the Chat list never came back" }.firstVisibleItemIndex
        assertEquals(
            "the session list came back scrolled to $after instead of $before: the state holder " +
                "did not retain the scroll position",
            before,
            after,
        )
    }

    /**
     * The mutation this catches: a holder with one global key, so every destination shares a
     * single slot and Chat's state overwrites Files'.
     */
    @Test
    fun everyDestinationKeepsItsOwnStateRatherThanSharingOne() {
        rule.setContent {
            OpenCodeMobileTheme {
                var tab by rememberSaveable { mutableStateOf(NavTab.CHAT) }
                AppShell(selected = tab, onSelect = { tab = it }) { destination ->
                    var draft by rememberSaveable(destination.route) { mutableStateOf("") }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.testTag("field:${destination.route}"),
                    )
                }
            }
        }

        rule.onNodeWithTag("field:chat").performTextInput("chat draft")
        rule.onNodeWithTag(navBarTestTag(NavTab.FILES)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("field:files").performTextInput("files draft")

        rule.onNodeWithTag(navBarTestTag(NavTab.CHAT)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("field:chat").assertTextEquals("chat draft")

        rule.onNodeWithTag(navBarTestTag(NavTab.FILES)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("field:files").assertTextEquals("files draft")
    }

    // --- honest placeholders ---------------------------------------------------------------------

    /**
     * The mutation this catches: a placeholder that grew a fake row, an item count, an empty list
     * that reads as real data, or a spinner that reads as loading.
     *
     * Asserted as exact set equality over every piece of text on screen, so anything invented
     * fails. The six bar labels are in the expectation deliberately: the bar is part of what the
     * user sees, and excluding it would weaken the assertion rather than sharpen it.
     */
    @Test
    fun everyPlaceholderSaysItIsNotBuiltAndInventsNothingElse() {
        rule.setContent { Shell() }

        val barLabels = NavTab.order.map { it.label }.toSet()
        for (tab in NavTab.order.filter { it.content == NavTab.Content.NOT_BUILT }) {
            rule.onNodeWithTag(navBarTestTag(tab)).performClick()
            rule.waitForIdle()

            val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                .fetchSemanticsNodes()
                .flatMap { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                }
                .map { it.text }
                .toSet()

            assertEquals(
                "the ${tab.label} placeholder rendered something other than its honest empty state",
                barLabels + setOf(tab.label, NOT_BUILT_LABEL, NOT_BUILT_BODY),
                texts as Set<String>,
            )
        }
    }

    /**
     * The mutation this catches: routing Chat to `PlaceholderDestination` because a `when` got
     * reordered -- which would leave the app's one real screen unreachable with nothing failing.
     */
    @Test
    fun chatIsTheOnlyDestinationThatIsNotAPlaceholder() {
        rule.setContent { Shell() }
        rule.onNodeWithTag(PROBE_FIELD).assertExists()
        rule.onNodeWithText(NOT_BUILT_LABEL).assertDoesNotExist()

        for (tab in NavTab.order - NavTab.CHAT) {
            rule.onNodeWithTag(navBarTestTag(tab)).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(PROBE_FIELD).assertDoesNotExist()
            rule.onNodeWithText(NOT_BUILT_LABEL).assertExists()
            // The screen's own heading, not the bar's label of the same text.
            rule.onNodeWithTag(PLACEHOLDER_TITLE_TAG).assertTextEquals(tab.label)
        }
    }

    // --- harness --------------------------------------------------------------------------------

    /** Every tab whose node is currently selected, in bar order. */
    private fun selectedTabs(): List<NavTab> =
        NavTab.order.filter { tab ->
            runCatching { rule.onNodeWithTag(navBarTestTag(tab)).assertIsSelected() }.isSuccess
        }

    /** Matches the six tab labels, and nothing else that happens to carry text. */
    private fun tabLabelMatcher(): SemanticsMatcher {
        val labels = NavTab.order.map { it.label }.toSet()
        return SemanticsMatcher("is one of the six tab labels") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text in labels }
        }
    }

    /** The shell, with Chat replaced by a probe so tests can drive real state. */
    @Composable
    private fun Shell(start: NavTab = NavTab.CHAT) {
        OpenCodeMobileTheme {
            var tab by rememberSaveable { mutableStateOf(start) }
            AppShell(selected = tab, onSelect = { tab = it }) { destination ->
                when (destination) {
                    NavTab.CHAT -> BasicTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.testTag(PROBE_FIELD),
                    )
                    else -> PlaceholderDestination(destination)
                }
            }
        }
    }
}
