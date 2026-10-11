package com.musa.opencodeharness.ui.sessions

import com.musa.opencodeharness.data.buildSessionTree
import com.musa.opencodeharness.data.model.Location
import com.musa.opencodeharness.data.model.SessionInfo
import com.musa.opencodeharness.data.model.SessionTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The row model: sectioning, timestamps, and the meta line.
 *
 * Every test here names the mutation it catches. A test that cannot name one is a comment with
 * assertions in it, and is deleted rather than kept.
 */
class SessionRowModelTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** A fixed "now": 2026-09-12 15:00 UTC. Chosen so weekday/date boundaries are unambiguous. */
    private val now: Long = run {
        Calendar.getInstance(utc).apply {
            clear(); set(2026, Calendar.SEPTEMBER, 12, 15, 0, 0)
        }.timeInMillis
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear(); set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun session(
        id: String,
        title: String? = "t-$id",
        parentID: String? = null,
        updated: Long? = now,
        directory: String? = "/home/musa/Projects/OpencodeMobile",
    ) = SessionInfo(
        id = id,
        title = title,
        parentID = parentID,
        location = directory?.let { Location(directory = it) },
        time = updated?.let { SessionTime(created = it - 1000, updated = it) },
    )

    // --- the nesting rule ------------------------------------------------------------------------

    /**
     * THE trap, restated for the row model: a promoted orphan (has a `parentID`, parent NOT in
     * the list, so `buildSessionTree` promotes it to the top level) is still a sub-agent.
     *
     * Mutation caught: reading sub-agent-ness off tree depth, or off `children.isEmpty()`. Either
     * turns this row into a root and drops the agentBlue qualifier.
     */
    @Test
    fun promotedOrphan_isStillASubAgent() {
        val tree = buildSessionTree(listOf(session("ses_orphan", parentID = "ses_absent")))

        val row = buildSessionRows(tree, now, utc).single()

        assertTrue(
            "a session with a parentID is a sub-agent even when its parent is not in the list",
            row.isSubAgent,
        )
        assertEquals(
            "and it must carry the sub-agent qualifier in the meta line",
            "sub-agent",
            row.metaSegments.first { it.emphasis == MetaEmphasis.AGENT_BLUE }.text,
        )
    }

    /**
     * A nested sub-agent indents: its depth is 1 against its parent's 0. This is the OTHER half of
     * the nesting rule, and it is a different test because depth is a different fact from
     * sub-agent-ness.
     *
     * Mutation caught: dropping `depth + 1` in `walk`, which flattens the tree.
     */
    @Test
    fun nestedSubAgent_indentsOneLevelDeeperThanItsParent() {
        val tree = buildSessionTree(listOf(session("ses_root"), session("ses_child", parentID = "ses_root")))

        val rows = buildSessionRows(tree, now, utc)

        assertEquals(listOf(0, 1), rows.map { it.depth })
    }

    /**
     * The promoted orphan is at depth 0 (no visible parent to nest under) and is still a
     * sub-agent. If these two facts are ever conflated -- one property driving both -- exactly one
     * of the two tests above fails. That is the point of having both.
     */
    @Test
    fun promotedOrphan_isASubAgentAtDepthZero() {
        val tree = buildSessionTree(
            listOf(session("ses_root"), session("ses_child", parentID = "ses_root"), session("ses_orphan", parentID = "ses_absent")),
        )

        val orphan = buildSessionRows(tree, now, utc).single { it.session.id == "ses_orphan" }

        assertEquals("no parent in the list, so it sits at the top level", 0, orphan.depth)
        assertTrue("but it is still a sub-agent", orphan.isSubAgent)
    }

    /**
     * A genuine root is NOT a sub-agent.
     *
     * Mutation caught: the reverse error -- treating `parentID == null` as sub-agent, or
     * inverting the predicate.
     */
    @Test
    fun aRootSession_isNotASubAgent() {
        val rows = buildSessionRows(buildSessionTree(listOf(session("ses_root"))), now, utc)

        assertFalse(rows.single().isSubAgent)
    }

    /**
     * Depth is unbounded, not one level: the flatten recurses.
     *
     * Mutation caught: walking only `node.children.first()` or capping depth at 1.
     */
    @Test
    fun nestingIsUnboundedNotOneLevel() {
        val tree = buildSessionTree(listOf(
            session("a"),
            session("b", parentID = "a"),
            session("c", parentID = "b"),
        ))

        assertEquals(listOf(0, 1, 2), buildSessionRows(tree, now, utc).map { it.depth })
    }

    // --- sectioning -----------------------------------------------------------------------------

    /**
     * Mutation caught: flipping the comparison, or grouping on `created` instead of `updated` when
     * only `updated` moved the session into today.
     */
    @Test
    fun aSessionUpdatedToday_isUnderToday() {
        val row = buildSessionRows(
            buildSessionTree(listOf(session("s", updated = at(2026, Calendar.SEPTEMBER, 12, 9, 0)))),
            now, utc,
        ).single()

        assertEquals(SessionSection.TODAY, row.section)
    }

    /**
     * Mutation caught: treating "within 24 hours" as today, which would put a 23:00 session from
     * yesterday under TODAY when "now" is 09:00.
     */
    @Test
    fun aSessionUpdatedYesterday_isUnderEarlier_evenWithin24Hours() {
        val row = buildSessionRows(
            buildSessionTree(listOf(session("s", updated = at(2026, Calendar.SEPTEMBER, 11, 23, 0)))),
            now, utc,
        ).single()

        assertEquals(SessionSection.EARLIER, row.section)
    }

    /**
     * An undated session is EARLIER, never TODAY. "We do not know when this happened" must not
     * read as "this just happened" -- recency is the whole reason the section split exists.
     *
     * Mutation caught: defaulting a null timestamp to `now`.
     */
    @Test
    fun aSessionWithNoTimestamp_isUnderEarlier_notToday() {
        val undated = SessionInfo(id = "s", title = "t")
        val row = buildSessionRows(buildSessionTree(listOf(undated)), now, utc).single()

        assertEquals(SessionSection.EARLIER, row.section)
        assertEquals(UNKNOWN_TIMESTAMP, row.timestamp)
    }

    /**
     * Sections are emitted in server order and a header appears only where a row of that section
     * actually starts -- so a fully filtered-out group leaves no header claiming content.
     */
    @Test
    fun sectionHeadersAppearOnlyWhereARowOfThatSectionExists() {
        val rows = buildSessionRows(
            buildSessionTree(listOf(
                session("today", updated = at(2026, Calendar.SEPTEMBER, 12, 8, 0)),
                session("earlier", updated = at(2026, Calendar.SEPTEMBER, 10, 8, 0)),
            )),
            now, utc,
        )

        val sections = rows.map { it.section }
        assertEquals(listOf(SessionSection.TODAY, SessionSection.EARLIER), sections)
    }

    /**
     * A section is a property of the whole GROUP, not of the row that happens to be rendering.
     *
     * This is the exact case the code review caught: a parent from three days ago whose sub-agent
     * ran a minute ago. Computed per row, the two land in different sections, the emission loop
     * puts a header between them, and the screen shows `EARLIER | parent`, `TODAY | child` --
     * tearing the sub-agent out from under its own parent, which is precisely what the design's
     * nesting says must not happen.
     *
     * Mutation caught: reverting to `sectionFor(row.mostRecentTimeMillis(), ...)`.
     */
    @Test
    fun aGroupWhoseChildRanToday_staysTogetherUnderToday() {
        val tree = buildSessionTree(listOf(
            session("stale_parent", updated = at(2026, Calendar.SEPTEMBER, 9, 8, 0)),
            session("fresh_child", parentID = "stale_parent", updated = at(2026, Calendar.SEPTEMBER, 12, 14, 58)),
        ))

        val rows = buildSessionRows(tree, now, utc)

        assertEquals(
            "the parent is not itself recent, but its group is -- both rows must share one section",
            listOf(SessionSection.TODAY, SessionSection.TODAY),
            rows.map { it.section },
        )
        assertEquals(
            "and the child must still render immediately after its parent, never across a header",
            listOf("stale_parent", "fresh_child"),
            rows.map { it.session.id },
        )
    }

    /**
     * Nesting is unbounded, so the rule has to hold all the way down: only the LEAF is recent and
     * the whole chain still heads under TODAY.
     *
     * Mutation caught: computing the group stamp from the ROOT only, which would put the recent
     * leaf's ancestors in a different section than the leaf.
     */
    @Test
    fun aDeepChainWhoseLeafIsRecent_keepsTheWholeChainUnderToday() {
        val tree = buildSessionTree(listOf(
            session("a", updated = at(2026, Calendar.SEPTEMBER, 1, 8, 0)),
            session("b", parentID = "a", updated = at(2026, Calendar.SEPTEMBER, 5, 8, 0)),
            session("c", parentID = "b", updated = at(2026, Calendar.SEPTEMBER, 12, 9, 0)),
        ))

        val rows = buildSessionRows(tree, now, utc)

        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
        assertTrue(
            "the whole chain shares one section, got ${rows.map { it.section }}",
            rows.all { it.section == SessionSection.TODAY },
        )
    }

    /**
     * The server does NOT sort by recency (`buildSessionTree` preserves server order and the
     * recorded envelope is paginated), so `[older, newer, oldest]` is an ordinary input.
     *
     * Under per-row sectioning this rendered `EARLIER | TODAY | EARLIER` -- the same header twice.
     * Grouping first and ordering TODAY-first makes each section one contiguous run, and the sort
     * is stable so the two EARLIER groups keep their server order relative to each other.
     *
     * Mutation caught: dropping the `sortedBy`, or sorting by anything other than the section.
     */
    @Test
    fun serverOrderThatIsNotRecencySorted_putsTodayFirstAndKeepsEachSectionContiguous() {
        val tree = buildSessionTree(listOf(
            session("older", updated = at(2026, Calendar.SEPTEMBER, 10, 8, 0)),
            session("newest", updated = at(2026, Calendar.SEPTEMBER, 12, 14, 0)),
            session("oldest", updated = at(2026, Calendar.JUNE, 1, 8, 0)),
        ))

        val rows = buildSessionRows(tree, now, utc)

        assertEquals(
            "TODAY groups first, then EARLIER in server order",
            listOf(
                "newest" to SessionSection.TODAY,
                "older" to SessionSection.EARLIER,
                "oldest" to SessionSection.EARLIER,
            ),
            rows.map { it.session.id to it.section },
        )
        // Contiguity stated directly as "one run per section": collapse consecutive repeats and
        // there must be exactly two runs. This is the property that makes the screen emit the same
        // header exactly once -- the review's bug rendered `EARLIER | TODAY | EARLIER`, i.e. THREE
        // runs, from a three-row list.
        assertEquals(
            "each section must occupy exactly one contiguous run, so the screen emits one header each",
            listOf(SessionSection.TODAY, SessionSection.EARLIER),
            runsOf(rows.map { it.section }),
        )
    }

    /**
     * A promoted orphan is its OWN group -- its parent is not in the list -- so it heads by its own
     * timestamp, with nothing to inherit from a parent that is not on screen.
     *
     * Mutation caught: reading the group stamp from a `parentID` lookup that does not exist, or
     * dropping orphans from the group ordering entirely.
     */
    @Test
    fun aPromotedOrphanIsItsOwnGroupAndUsesItsOwnTimestamp() {
        val tree = buildSessionTree(listOf(
            session("fresh_orphan", parentID = "ses_absent", updated = at(2026, Calendar.SEPTEMBER, 12, 11, 0)),
            session("stale_orphan", parentID = "ses_gone", updated = at(2026, Calendar.SEPTEMBER, 3, 11, 0)),
        ))

        val rows = buildSessionRows(tree, now, utc)

        assertEquals(
            listOf("fresh_orphan" to SessionSection.TODAY, "stale_orphan" to SessionSection.EARLIER),
            rows.map { it.session.id to it.section },
        )
        assertTrue("and both are still sub-agents", rows.all { it.isSubAgent })
    }

    /**
     * A group whose EVERY session is undated is EARLIER, and an undated child must not drag an
     * undated group into TODAY by accident.
     *
     * Mutation caught: defaulting a missing group stamp to `now`.
     */
    @Test
    fun aGroupWithNoTimestampsAnywhere_isUnderEarlier() {
        val tree = buildSessionTree(listOf(
            SessionInfo(id = "bare_parent"),
            SessionInfo(id = "bare_child", parentID = "bare_parent"),
        ))

        val rows = buildSessionRows(tree, now, utc)

        assertTrue("no timestamp anywhere means no recency, got ${rows.map { it.section }}",
            rows.all { it.section == SessionSection.EARLIER })
    }

    /** Collapses consecutive repeats, so `[T, E, E, T]` becomes `[T, E, T]` -- three runs. */
    private fun runsOf(sections: List<SessionSection>): List<SessionSection> =
        sections.fold(mutableListOf()) { acc, section ->
            if (acc.lastOrNull() != section) acc += section
            acc
        }

    // --- relative timestamps --------------------------------------------------------------------

    /** Mutation caught: a fixed literal, or a "hours since" that never says `now`. */
    @Test
    fun aSessionUpdatedSecondsAgo_readsNow() {
        assertEquals("now", relativeTimestamp(now - 20_000, now, utc))
    }

    /** Mutation caught: dropping the `now` rung entirely. */
    @Test
    fun aSessionUpdatedEarlierToday_readsItsClockTime() {
        assertEquals("09:30", relativeTimestamp(at(2026, Calendar.SEPTEMBER, 12, 9, 30), now, utc))
    }

    /** Mutation caught: a 24-hour window instead of a calendar-day comparison. */
    @Test
    fun aSessionUpdatedYesterday_readsYesterday() {
        assertEquals("Yesterday", relativeTimestamp(at(2026, Calendar.SEPTEMBER, 11, 23, 0), now, utc))
    }

    /** Mutation caught: a rolling 7-day window, which mislabels the day boundary. */
    @Test
    fun aSessionThreeDaysAgo_readsItsWeekday() {
        // 2026-09-12 is a Saturday; three days earlier is Wednesday.
        assertEquals("Wed", relativeTimestamp(at(2026, Calendar.SEPTEMBER, 9, 10, 0), now, utc))
    }

    /** Mutation caught: showing a weekday for a session from three months ago, which is a lie. */
    @Test
    fun anOldSession_readsAShortDateNotAWeekday() {
        assertEquals("12 Jun", relativeTimestamp(at(2026, Calendar.JUNE, 12, 10, 0), now, utc))
    }

    /** An undated row never shows a fabricated time. */
    @Test
    fun aSessionWithNoTimestamp_readsTheUnknownMarker() {
        assertEquals(UNKNOWN_TIMESTAMP, relativeTimestamp(null, now, utc))
    }

    // --- the meta line --------------------------------------------------------------------------

    /**
     * The design's `project · status · qualifier`, assembled from real data only.
     *
     * Mutation caught: dropping the project segment, or dropping the status segment -- the latter
     * is the accessibility requirement, since the status word is what makes the dot legal.
     */
    @Test
    fun theMetaLineIsProjectStatusThenQualifier() {
        val row = buildSessionRows(
            buildSessionTree(listOf(session("s", updated = now))),
            now, utc,
        ).single()

        assertEquals(listOf("OpencodeMobile", "unknown"), row.metaSegments.map { it.text })
        assertEquals("OpencodeMobile · unknown", row.metaLine)
    }

    /**
     * A session with no location contributes NO project segment -- we do not invent one.
     *
     * Mutation caught: defaulting the project to `Unknown`, `default`, or the server's cwd.
     */
    @Test
    fun aSessionWithNoProject_getsNoProjectSegment() {
        val row = buildSessionRows(
            buildSessionTree(listOf(SessionInfo(id = "s", title = "t"))),
            now, utc,
        ).single()

        // The only segment is the status word: there is no project segment at all, rather than a
        // placeholder standing in for one. `MUTED` is shared by both, so the assertion is on the
        // exact segment list -- a made-up project would add a second entry and fail here.
        assertEquals(listOf("unknown"), row.metaSegments.map { it.text })
    }

    /**
     * A session that spawned sub-agents says so. Real data: the children are in the tree.
     *
     * Mutation caught: dropping the qualifier, or counting sessions rather than children.
     */
    @Test
    fun aParentSession_isQualifiedByItsSubAgents() {
        val tree = buildSessionTree(listOf(
            session("root"),
            session("c1", parentID = "root"),
            session("c2", parentID = "root"),
        ))

        val parent = buildSessionRows(tree, now, utc).first { it.session.id == "root" }

        assertEquals(
            listOf("OpencodeMobile", "unknown", "has sub-agents"),
            parent.metaSegments.map { it.text },
        )
    }

    /**
     * THE accessibility rule, at the model level: every row's meta line contains a segment equal
     * to `statusWord()`. If the status segment is ever dropped, this fails.
     *
     * Mutation caught: removing the status segment from `metaSegmentsFor`, or making it optional
     * for some row shape.
     */
    @Test
    fun everyRowCarriesTheStatusWordBesideItsDot() {
        val rows = buildSessionRows(
            buildSessionTree(listOf(
                session("root"),
                session("child", parentID = "root"),
                session("orphan", parentID = "ses_absent"),
                SessionInfo(id = "bare", title = "no location, no time"),
            )),
            now, utc,
        )

        for (row in rows) {
            assertTrue(
                "row ${row.session.id} has a status dot but no status word: " +
                    "${row.metaSegments.map { it.text }}",
                row.metaSegments.any { it.text == row.session.statusWord() },
            )
        }
    }
}
