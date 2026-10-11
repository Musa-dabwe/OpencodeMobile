package com.musa.opencodeharness.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The six root destinations, in the order they appear in the bottom bar.
 *
 * ## Why an enum and not `navigation-compose`
 *
 * The owner ruling and the plan ask for six destinations with per-tab state retention, and the
 * whole of it is one screen: a scaffold, a bar, and a keyed content slot. `navigation-compose`
 * brings a `NavHost`, a back stack, route serialisation, arguments and a graph -- none of which
 * this phase uses, and every one of which is a way to reach a destination the design does not
 * have. The state that actually has to survive a tab switch is per-destination *composition*
 * state, and [androidx.compose.runtime.saveable.SaveableStateHolder] is the mechanism built for
 * exactly that. So the destinations are an enum, the "route" is the enum's [route], and there is
 * no graph to fall out of sync with the bar.
 *
 * ## Why the order matters and is not alphabetical
 *
 * The order is the owner ruling recorded in `docs/plans/phase3-ui.md`: the design PDF shows five
 * tabs, spec section 5 names a different five again, and the owner restored Terminal and inserted
 * Agents before Settings. [NavTabOrderTest] pins the sequence so a well-meaning sort cannot
 * quietly reorder the app's primary navigation.
 *
 * ## [content]
 *
 * `IMPLEMENTED` means the destination renders real application state. `NOT_BUILT` means it ships
 * as an honest placeholder: a labelled screen that says the feature does not exist yet, and
 * nothing else. There is deliberately no third state and no "empty" state that could be mistaken
 * for real data -- see [PlaceholderDestination].
 */
enum class NavTab(
    /** Stable identity for saveable state, the state holder, and the semantics tree. */
    val route: String,
    val label: String,
    val icon: ImageVector,
    val content: Content,
) {
    /** Spec section 5.9. Not built: the project tree has no data source yet. */
    PROJECTS(route = "projects", label = "Projects", icon = NavIcons.Projects, content = Content.NOT_BUILT),

    /** The only destination with real content in this phase. */
    CHAT(route = "chat", label = "Chat", icon = NavIcons.Chat, content = Content.IMPLEMENTED),

    /** Not built. The plan's non-goals rule out a Files implementation for this phase. */
    FILES(route = "files", label = "Files", icon = NavIcons.Files, content = Content.NOT_BUILT),

    /** Owner ruling: restored to the bar even though it is not built. */
    TERMINAL(route = "terminal", label = "Terminal", icon = NavIcons.Terminal, content = Content.NOT_BUILT),

    /** Spec section 5.9, inserted before Settings because the design treats it as a root. */
    AGENTS(route = "agents", label = "Agents", icon = NavIcons.Agents, content = Content.NOT_BUILT),

    /**
     * Design section 3.7. Real from Task 4: the server card is a pure function of the launcher's
     * `ServerState`, and the grouped rows are either derived from real state or honestly disabled.
     */
    SETTINGS(route = "settings", label = "Settings", icon = NavIcons.Settings, content = Content.IMPLEMENTED),
    ;

    enum class Content { IMPLEMENTED, NOT_BUILT }

    companion object {
        /** The ruling, as data. Also what the ordering test compares against. */
        val order: List<NavTab> = listOf(PROJECTS, CHAT, FILES, TERMINAL, AGENTS, SETTINGS)

        /**
         * The screen to open on a cold start: the only destination that has something to show.
         * A different cold start is a product decision, so it is one named constant rather than
         * whatever happens to be first in [order].
         */
        val startDestination: NavTab = CHAT

        /** Back-stack lookups, so a saved route cannot silently fall back to "the first tab". */
        val byRoute: Map<String, NavTab> = entries.associateBy { it.route }
    }
}
