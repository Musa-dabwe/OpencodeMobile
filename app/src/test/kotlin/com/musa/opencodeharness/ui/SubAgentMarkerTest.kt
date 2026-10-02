package com.musa.opencodeharness.ui

import com.musa.opencodeharness.data.buildSessionTree
import com.musa.opencodeharness.data.model.Envelope
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.openCodeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the rule the session list uses to label a row as a sub-agent.
 *
 * This exists because the tree's own KDoc invites the wrong choice. `SessionNode.children` reads
 * like "is this a sub-agent of something", and deriving the marker from `children.isEmpty()`
 * compiles, runs, and mislabels the majority of real rows. These tests assert on
 * [isSubAgentSession] AND on the tree, so the case that separates the two -- the promoted orphan
 * -- is exercised through both, exactly as the screen sees it.
 */
class SubAgentMarkerTest {

    private fun session(id: String, parentID: String? = null) =
        SessionInfo(id = id, title = "t-$id", parentID = parentID)

    @Test
    fun parentlessSession_isNotASubAgent() {
        assertFalse(session("ses_root").isSubAgentSession)
    }

    @Test
    fun sessionWithParent_isASubAgent() {
        assertTrue(session("ses_child", "ses_root").isSubAgentSession)
    }

    /**
     * THE trap. A childless sub-agent nested under a real parent is still a sub-agent, and the
     * two candidate rules disagree: `parentID != null` says yes, `children.isEmpty()` also says
     * yes here -- so this case alone cannot catch the bug.
     */
    @Test
    fun childlessSubAgentNestedUnderAParent_isStillASubAgent() {
        val tree = buildSessionTree(listOf(session("ses_root"), session("ses_child", "ses_root")))

        val child = tree.single().children.single()
        assertTrue("children is empty, so the wrong rule would also say yes", child.children.isEmpty())
        assertTrue("parentID is non-null, so the right rule says sub-agent", child.session.isSubAgentSession)
    }

    /**
     * The case the two rules actually disagree on, and the only place the bug is observable: a
     * promoted orphan sits at the TOP level of the tree with no children, yet names a parent.
     * `children.isEmpty()` says "root session"; the session record says "sub-agent".
     */
    @Test
    fun promotedOrphan_atTopLevelWithNoChildren_isStillMarkedASubAgent() {
        val tree = buildSessionTree(
            listOf(session("ses_present"), session("ses_orphan", "ses_absent")),
        )

        // The orphan is at the top level...
        val orphan = tree.last()
        assertEquals("ses_orphan", orphan.session.id)
        assertTrue("no children -- this is what trips the wrong rule", orphan.children.isEmpty())
        // ...but it is a sub-agent all the same.
        assertTrue("promoted orphan must still be marked as a sub-agent", orphan.session.isSubAgentSession)
    }

    /**
     * Both candidate rules, run over the real 50-session capture, side by side.
     *
     * Rule A (used): `parentID != null`. Rule B (the trap, taken from the tree KDoc's own
     * wording): "children is empty ... is also the 'is this a sub-agent of something' marker",
     * i.e. `children.isEmpty()`.
     *
     * On the capture the two rules disagree on 13 of 50 rows, and the disagreement is not
     * cosmetic: rule B labels all 34 sub-agent sessions as sub-agents AND 13 genuine
     * childless root sessions as sub-agents too, while calling the 3 sessions that actually
     * HAVE sub-agents something other than sub-agents. It inverts the marker.
     */
    @Test
    fun recordedCapture_theTwoRulesDisagreeOnTheChildlessRootSessions() {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("session.json")) {
            "Missing recorded sample session.json on the test classpath"
        }.bufferedReader().use { it.readText() }
        val sessions = openCodeJson.decodeFromString<Envelope<SessionInfo>>(json).data

        val tree = buildSessionTree(sessions)
        val nodes = tree.flatMap { node -> listOf(node) + node.children }
        assertEquals("all 50 sessions are reachable", 50, nodes.size)

        // Rule A, on the real data: 34 of 50 sessions carry a parentID. Matches the capture.
        val ruleA = nodes.count { it.session.isSubAgentSession }
        assertEquals("34 sessions are sub-agents", 34, ruleA)

        // Rule B, on the same data: every child is childless, and 13 of the 16 roots are too.
        val ruleB = nodes.count { it.children.isEmpty() }
        assertEquals("rule B would call 47 of 50 sessions sub-agents", 47, ruleB)

        // The load-bearing claim: the two rules are not interchangeable.
        val disagreements = nodes.count { it.session.isSubAgentSession != it.children.isEmpty() }
        assertEquals("the 13 childless ROOT sessions are labelled the opposite way", 13, disagreements)
    }
}
