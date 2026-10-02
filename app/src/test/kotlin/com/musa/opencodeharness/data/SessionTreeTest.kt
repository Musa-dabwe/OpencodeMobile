package com.musa.opencodeharness.data

import com.musa.opencodeharness.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [buildSessionTree], the client-side grouping that replaces the children-listing
 * route the server does not have.
 *
 * The last test runs the grouping over the real capture in `docs/samples/session.json`
 * (50 sessions, loaded off the test classpath exactly as `RecordedResponseShapeTest` does), so
 * the counts asserted there are measured facts about a live server, not invented ones.
 */
class SessionTreeTest {

    private fun session(id: String, parentID: String? = null) =
        SessionInfo(id = id, title = "t-$id", parentID = parentID)

    /** Every session in the tree, parents before children. */
    private fun List<SessionNode>.flattenIds(): List<String> =
        flatMap { listOf(it.session.id) + it.children.flattenIds() }

    // --- flat list of only roots -------------------------------------------------------------

    @Test
    fun listOfOnlyRoots_producesRootNodesWithNoChildren() {
        val sessions = listOf(session("ses_a"), session("ses_b"), session("ses_c"))

        val tree = buildSessionTree(sessions)

        assertEquals(3, tree.size)
        assertEquals(listOf("ses_a", "ses_b", "ses_c"), tree.map { it.session.id })
        assertTrue("no session has children", tree.all { it.children.isEmpty() })
    }

    // --- one parent with children ------------------------------------------------------------

    @Test
    fun oneParentWithChildren_nestsThemUnderIt() {
        val sessions = listOf(
            session("ses_parent"),
            session("ses_child_1", "ses_parent"),
            session("ses_child_2", "ses_parent"),
        )

        val tree = buildSessionTree(sessions)

        // The child that arrives before its parent in the input list must still resolve.
        assertEquals(1, tree.size)
        assertEquals("ses_parent", tree.single().session.id)
        assertEquals(listOf("ses_child_1", "ses_child_2"), tree.single().children.map { it.session.id })
        assertTrue("grandchildren do not exist here", tree.single().children.all { it.children.isEmpty() })
    }

    @Test
    fun parentListedAfterItsChild_isStillResolved() {
        val sessions = listOf(
            session("ses_child", "ses_parent"),
            session("ses_parent"),
        )

        val tree = buildSessionTree(sessions)

        assertEquals(listOf("ses_parent"), tree.map { it.session.id })
        assertEquals(listOf("ses_child"), tree.single().children.map { it.session.id })
    }

    // --- nested grandchildren ----------------------------------------------------------------

    @Test
    fun nestedGrandchildren_nestToArbitraryDepth() {
        val sessions = listOf(
            session("ses_l1"),
            session("ses_l2", "ses_l1"),
            session("ses_l3", "ses_l2"),
            session("ses_l4", "ses_l3"),
            session("ses_other"),
        )

        val tree = buildSessionTree(sessions)

        assertEquals(2, tree.size)
        val l1 = tree.first { it.session.id == "ses_l1" }
        val l2 = l1.children.single()
        val l3 = l2.children.single()
        val l4 = l3.children.single()
        assertEquals("ses_l2", l2.session.id)
        assertEquals("ses_l3", l3.session.id)
        assertEquals("ses_l4", l4.session.id)
        assertTrue("the deepest node has no children", l4.children.isEmpty())
        assertTrue("unrelated root stays a root", tree.first { it.session.id == "ses_other" }.children.isEmpty())
        // Nothing may be lost or duplicated at depth.
        assertEquals(5, tree.flattenIds().size)
        assertEquals(sessions.map { it.id }.toSet(), tree.flattenIds().toSet())
    }

    // --- orphan decision: promoted to root, never dropped -------------------------------------

    /**
     * The documented orphan decision: a session whose parentID is absent from the list becomes a
     * ROOT. Dropping it would hide a session the server actually returned.
     */
    @Test
    fun orphanWithUnknownParent_isPromotedToRootNotDropped() {
        val sessions = listOf(
            session("ses_present"),
            session("ses_orphan", "ses_does_not_exist"),
        )

        val tree = buildSessionTree(sessions)

        assertEquals(2, tree.size)
        assertEquals(listOf("ses_present", "ses_orphan"), tree.map { it.session.id })
        // It keeps its parentID on the record (the data is not rewritten), but the tree places it
        // at top level so the UI can render it.
        assertEquals("ses_does_not_exist", tree.last().session.parentID)
        assertTrue("an orphan has no children to expand", tree.last().children.isEmpty())
    }

    @Test
    fun orphanChain_isFullyPreserved() {
        // An orphan that is itself the parent of more orphans: promotion must not swallow them.
        val sessions = listOf(
            session("ses_orphan_parent", "ses_gone"),
            session("ses_orphan_child", "ses_orphan_parent"),
        )

        val tree = buildSessionTree(sessions)

        assertEquals(1, tree.size)
        assertEquals("ses_orphan_parent", tree.single().session.id)
        assertEquals(listOf("ses_orphan_child"), tree.single().children.map { it.session.id })
    }

    // --- empty input -------------------------------------------------------------------------

    @Test
    fun emptyList_returnsEmptyTree() {
        assertEquals(emptyList<SessionNode>(), buildSessionTree(emptyList()))
    }

    // --- mixed roots and children ------------------------------------------------------------

    @Test
    fun mixedRootsAndChildren_eachSessionAppearsExactlyOnce() {
        val sessions = listOf(
            session("ses_root_1"),
            session("ses_child_a", "ses_root_1"),
            session("ses_child_b", "ses_root_1"),
            session("ses_grandchild", "ses_child_a"),
            session("ses_root_2"),
            session("ses_root_3"),
            session("ses_orphan", "ses_missing"),
        )

        val tree = buildSessionTree(sessions)

        // 3 genuine roots + 1 promoted orphan at top level.
        assertEquals(listOf("ses_root_1", "ses_root_2", "ses_root_3", "ses_orphan"), tree.map { it.session.id })
        assertEquals(2, tree.first().children.size)
        assertEquals(listOf("ses_grandchild"), tree.first().children.first().children.map { it.session.id })
        assertEquals(7, tree.flattenIds().size)
        assertEquals(sessions.map { it.id }.toSet(), tree.flattenIds().toSet())
    }

    @Test
    fun findSessionNode_locatesNestedAndMissingSessions() {
        val tree = buildSessionTree(
            listOf(
                session("ses_root"),
                session("ses_child", "ses_root"),
                session("ses_grandchild", "ses_child"),
            ),
        )

        assertNotNull("nested session is findable", tree.findSessionNode("ses_grandchild"))
        assertNull("absent id returns null", tree.findSessionNode("ses_nope"))
    }

    // --- defensive: duplicate ids and parentID cycles -----------------------------------------

    @Test
    fun duplicateIds_firstOccurrenceWinsAndSessionAppearsOnce() {
        val tree = buildSessionTree(
            listOf(
                // Same id twice. First occurrence wins, so the copy with the unresolvable
                // parentID is the one kept and the clean duplicate is discarded.
                session("ses_root", "ses_ghost"),
                session("ses_root"),
                session("ses_child", "ses_root"),
            ),
        )

        assertEquals(1, tree.size)
        assertEquals("ses_root", tree.single().session.id)
        // `ses_ghost` is not in the list, so the surviving copy is an orphan -> promoted to root.
        assertEquals("ses_ghost", tree.single().session.parentID)
        // The discarded duplicate did not become a second root; the real child still nests.
        assertEquals(listOf("ses_child"), tree.single().children.map { it.session.id })
        // Two distinct sessions, listed exactly once each.
        assertEquals(2, tree.flattenIds().size)
        assertEquals(setOf("ses_root", "ses_child"), tree.flattenIds().toSet())
    }

    @Test
    fun parentIdCycle_doesNotHangAndKeepsEverySessionVisible() {
        // A -> B -> A: neither has an absent parent, so neither is a root by the plain rule.
        val tree = buildSessionTree(
            listOf(
                session("ses_a", "ses_b"),
                session("ses_b", "ses_a"),
                session("ses_root"),
            ),
        )

        assertEquals(3, tree.flattenIds().size)
        assertEquals(setOf("ses_a", "ses_b", "ses_root"), tree.flattenIds().toSet())
        assertTrue("no session is its own descendant", tree.all { node -> node.session.id !in node.children.flattenIds() })
    }

    // --- the real capture --------------------------------------------------------------------

    /**
     * The strong test: group the recorded 50-session response and assert the structure that was
     * actually measured on the live server.
     *
     * Measured facts (see the capture, `docs/samples/session.json`):
     *  - 50 sessions, 16 with no `parentID` -> 16 roots.
     *  - 34 children hanging off exactly 3 distinct parents, and all 3 parents are themselves
     *    roots. Child counts are 11, 11 and 12.
     *  - Therefore the capture nests only ONE level: no child has children. This test pins that
     *    so a re-capture that grows deeper is noticed rather than silently accepted.
     */
    @Test
    fun recordedSessionList_groupsIntoParentsAndChildren() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("session.json")) {
            "Missing recorded sample session.json on the test classpath"
        }.bufferedReader().use { it.readText() }
        val sessions = openCodeJson.decodeFromString<com.musa.opencodeharness.data.model.Envelope<SessionInfo>>(json).data

        assertEquals(50, sessions.size)

        val tree = buildSessionTree(sessions)

        assertEquals("16 sessions omit parentID, so 16 roots", 16, tree.size)
        // Every root in this capture really is parentless -- no orphan promotion happened.
        assertTrue("all roots have no parentID", tree.all { it.session.parentID == null })

        val parents = tree.filter { it.children.isNotEmpty() }
        assertEquals("exactly 3 distinct parents in the capture", 3, parents.size)
        assertEquals(listOf(11, 11, 12), parents.map { it.children.size }.sorted())
        // 34 children total; the other 13 roots are plain sessions with no sub-agents.
        assertEquals(34, parents.sumOf { it.children.size })
        assertEquals(13, tree.count { it.children.isEmpty() })

        // Verbatim from the capture: ses_f4a43dfc4ffeWLCJ8Ey0VJZqHq is a child of
        // ses_f4a637ae1ffewUvbHzzhYgQZ30.
        val parent = tree.first { it.session.id == "ses_f4a637ae1ffewUvbHzzhYgQZ30" }
        assertEquals("ses_f4a43dfc4ffeWLCJ8Ey0VJZqHq", parent.children.first().session.id)

        // Nothing lost, nothing duplicated.
        val ids = tree.flattenIds()
        assertEquals(50, ids.size)
        assertEquals(sessions.map { it.id }.toSet(), ids.toSet())
        // The capture nests exactly TWO levels of nodes (root -> child) and no deeper; the
        // grouping itself supports arbitrary depth, as `nestedGrandchildren_nestToArbitraryDepth`
        // shows. Pinning the number makes a deeper re-capture visible instead of silent.
        assertEquals(2, tree.maxOf { node -> node.nodeDepth() })
    }

    private fun SessionNode.nodeDepth(): Int = 1 + (children.maxOfOrNull { it.nodeDepth() } ?: 0)
}
