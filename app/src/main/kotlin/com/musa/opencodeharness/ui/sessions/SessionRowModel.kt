package com.musa.opencodeharness.ui.sessions

import com.musa.opencodeharness.data.SessionNode
import com.musa.opencodeharness.data.model.SessionInfo
import androidx.compose.ui.graphics.Color
import com.musa.opencodeharness.ui.isSubAgentSession
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The two section headers the design groups rows under.
 *
 * Not an enum-with-a-label baked into a composable: the label is uppercase by the DESIGN
 * (`TODAY` / `EARLIER` in wide-tracked caps), so it is data here and a test can read it, rather
 * than a literal buried in a `Text`.
 */
enum class SessionSection(val label: String) {
    TODAY("TODAY"),
    EARLIER("EARLIER"),
}

/**
 * The states a row's status dot can show on THIS screen.
 *
 * The design's meta line carries status words, and this enum is what the dot is coloured FROM --
 * so it enumerates every state the design names, which is what lets [SessionsStyle.statusDotFor]
 * be a total mapping. A total mapping is the point: the review caught a screen where the dot was
 * hard-painted `status.idle` while the word beside it read `unknown`, and every row therefore
 * showed a green "idle" dot next to a word disclaiming exactly that. If a new status arrived
 * without a dot colour, the mapping would not compile rather than silently paint the wrong state.
 *
 * TODAY only [UNKNOWN] is produced, because `GET /api/session` returns no run-status field on any
 * of the 50 recorded sessions and live streaming is a Phase non-goal. The other arms are the
 * design's own vocabulary, held here so that the dot and the word can never drift apart again --
 * they are not a claim that this app knows those states.
 */
enum class SessionStatus { IDLE, RUNNING, ERROR, UNKNOWN }

/** One rendered row: a session, how deep it sits, and what the design's meta line says about it. */
internal data class SessionRowModel(
    val session: SessionInfo,
    /** Tree depth: 0 for a root row, 1 for a sub-agent, and so on. Drives the indentation. */
    val depth: Int,
    /**
     * The section this row's whole top-level GROUP sits under. Every row of a group shares it, so
     * a parent and its sub-agents always land under one header together.
     */
    val section: SessionSection,
    /** The relative timestamp shown right-aligned: `now`, `Yesterday`, `Mon`, `Sat`. */
    val timestamp: String,
    val status: SessionStatus,
    /**
     * The meta line's three `·`-separated segments.
     *
     * Kept as a list rather than a joined string so the parts can be styled independently (the
     * agent name is `agentBlue`, the rest is muted) AND asserted individually, so a test can
     * assert the project segment is present without substring-matching the whole line.
     */
    val metaSegments: List<MetaSegment>,
) {
    val isSubAgent: Boolean get() = session.parentID != null

    /** The `·`-joined meta line, exactly as the design renders it. */
    val metaLine: String get() = metaSegments.joinToString(" · ") { it.text }
}

/** One `·`-separated piece of the meta line, with the design's colour role for it. */
internal data class MetaSegment(val text: String, val emphasis: MetaEmphasis)

/** Which design token colours a meta segment. */
enum class MetaEmphasis {
    /** `text.secondary` -- the ordinary case: project, status, qualifier. */
    MUTED,

    /** `agent.primary` (agentBlue) -- the sub-agent's own agent name. */
    AGENT_BLUE,
}

/**
 * One screen row, already flattened out of the tree: a parent, then its sub-agents, then the
 * next root. Depth is carried on every entry rather than being implied by a nesting data
 * structure, because a `LazyColumn` is flat and the indentation has to be recomputed at the row.
 *
 * Keys: [session.id]. `buildSessionTree` guarantees every input session appears exactly once, so
 * ids are unique within this list and a row keeps its composition (and its swipe offset) across
 * reloads. Callers MUST pass `key = { it.session.id }` to the `items` block.
 */
internal typealias SessionRows = List<SessionRowModel>

/**
 * Builds the display rows for the sessions screen from the session tree.
 *
 * ## The nesting rule is NOT re-derived here
 *
 * The one thing this file is careful about is the rule the phase plan calls out as a trap: "a
 * session is a sub-agent iff `parentID != null`." This function NEVER asks `node.children` whether
 * a session is a sub-agent; it reads [SessionInfo.isSubAgentSession] from the session itself. A
 * childless sub-agent (the common case on real data: 47 of 50 recorded sessions have no children)
 * and a promoted orphan (parent on another page) are BOTH sub-agents, and only `parentID` knows
 * that. `children.isEmpty()` would mislabel 13 of the 50 recorded rows -- see `SubAgentMarkerTest`.
 *
 * The DEPTH here is tree depth -- how many `children` hops a row is from a root -- because that is
 * genuinely what indents a row on screen. Depth and sub-agent-ness are DIFFERENT facts: a promoted
 * orphan has depth 0 (its parent is not in the list, so `buildSessionTree` promoted it to the top
 * level) but IS a sub-agent. So a row indents by depth and colours the agent name by
 * [SessionRowModel.isSubAgent]; the two must never be conflated.
 *
 * ## A section belongs to a GROUP, not to a row
 *
 * `section` is computed ONCE per top-level group -- TODAY if the group OR ANY of its descendants
 * has activity today -- and threaded down through `walk`, and the GROUPS are then ordered
 * TODAY-first before flattening. Two real bugs come from computing it per row instead:
 *
 *  1. **A sub-agent torn out of its parent.** A parent from three days ago whose sub-agent ran a
 *     minute ago carries its own EARLIER stamp; under a per-row section the emission loop puts a
 *     header between them and the screen reads `EARLIER | parent`, `TODAY | child`. That breaks the
 *     design's "sub-agents nest indented under their parent" with a header row, and it separates
 *     the MORE active session from its own tree. A group is a unit: it heads by its most recent
 *     member and stays contiguous.
 *  2. **A repeated `EARLIER` header.** `buildSessionTree` preserves server order and the server
 *     does not sort by recency, so `[older, newer, oldest]` produced
 *     `EARLIER | TODAY | EARLIER` -- the same header twice. Ordering the GROUPS before flattening
 *     makes each section one contiguous run.
 *
 * The sort is on groups, not on flattened rows, so contiguity is structural rather than a
 * consequence of the sort's stability. Within a group, tree order -- parent then its children, in
 * server order -- is untouched.
 */
internal fun buildSessionRows(
    roots: List<SessionNode>,
    nowMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
): List<SessionRowModel> {
    val groups = roots
        .map { root -> root to sectionFor(root.mostRecentStampInGroup(), nowMillis, zone) }
        .sortedBy { (_, section) -> section.ordinal }

    return buildList {
        fun walk(node: SessionNode, depth: Int, section: SessionSection) {
            val session = node.session
            val stamp = session.mostRecentTimeMillis()
            add(
                SessionRowModel(
                    session = session,
                    depth = depth,
                    section = section,
                    timestamp = relativeTimestamp(stamp, nowMillis, zone),
                    status = statusFor(session),
                    metaSegments = metaSegmentsFor(session, node.children.isNotEmpty()),
                ),
            )
            node.children.forEach { walk(it, depth + 1, section) }
        }
        groups.forEach { (root, section) -> walk(root, depth = 0, section = section) }
    }
}

/**
 * The most recent timestamp anywhere in this subtree -- the group itself or any descendant.
 *
 * `null` only when NO session in the group carries a timestamp at all, which then sections as
 * EARLIER: "we do not know when this happened" must not read as "this just happened".
 */
private fun SessionNode.mostRecentStampInGroup(): Long? {
    var newest: Long? = null
    fun visit(node: SessionNode) {
        val stamp = node.session.mostRecentTimeMillis()
        if (stamp != null && (newest == null || stamp > newest!!)) newest = stamp
        node.children.forEach(::visit)
    }
    visit(this)
    return newest
}

/**
 * The section a GROUP falls under: TODAY if its most recent activity is on the same calendar day
 * as "now" in [zone], EARLIER otherwise.
 *
 * The stamp passed here is the group's maximum (see `mostRecentStampInGroup`), never one row's own
 * timestamp -- see the sectioning rules on [buildSessionRows].
 *
 * A group with no timestamp anywhere is EARLIER, not TODAY. "We have no idea when this happened"
 * must not read as "this happened just now" -- the whole point of the section split is recency,
 * and an unknown time is not recent.
 */
internal fun sectionFor(stampMillis: Long?, nowMillis: Long, zone: TimeZone): SessionSection {
    if (stampMillis == null) return SessionSection.EARLIER
    return if (sameCalendarDay(stampMillis, nowMillis, zone)) {
        SessionSection.TODAY
    } else {
        SessionSection.EARLIER
    }
}

private fun sameCalendarDay(aMillis: Long, bMillis: Long, zone: TimeZone): Boolean {
    // Compare year + day-of-year in the target zone, not raw millisecond distance: "today" is a
    // calendar concept and it shifts with the device's timezone and across a DST boundary.
    fun cal(millis: Long): Calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
    return with(cal(aMillis)) {
        get(Calendar.YEAR) == cal(bMillis).get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == cal(bMillis).get(Calendar.DAY_OF_YEAR)
    }
}

/**
 * The right-aligned relative timestamp.
 *
 *  - under a minute -> `now`
 *  - earlier today -> the local clock time, e.g. `14:32` (there is no coarser honest word for
 *    "earlier today" that is not "now"; the design shows `now` for the just-happened case only)
 *  - yesterday -> `Yesterday`
 *  - within the last week -> the weekday short name, e.g. `Mon`, `Sat`
 *  - older -> a short date, e.g. `12 Sep`
 *
 * The design shows `now` / `Yesterday` / `Mon` / `Sat`; the "earlier today" and "older than a
 * week" rungs are extensions this app needs because its data genuinely spans them, and every rung
 * is derived from the real timestamp -- none is a placeholder.
 */
internal fun relativeTimestamp(stampMillis: Long?, nowMillis: Long, zone: TimeZone): String {
    if (stampMillis == null) return UNKNOWN_TIMESTAMP
    if (nowMillis - stampMillis < MINUTE_MILLIS) return "now"
    if (sameCalendarDay(stampMillis, nowMillis, zone)) return clockTime(stampMillis, zone)
    val todayStart = startOfDay(nowMillis, zone)
    if (stampMillis >= todayStart - DAY_MILLIS && stampMillis < todayStart) return "Yesterday"
    if (nowMillis - stampMillis < 7 * DAY_MILLIS) return weekdayShort(stampMillis, zone)
    return shortDate(stampMillis, zone)
}

/** What a row shows when it carries no timestamp. Never a fake "now". */
internal const val UNKNOWN_TIMESTAMP = "--"

private fun clockTime(millis: Long, zone: TimeZone): String =
    java.text.SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }.format(millis)

private fun weekdayShort(millis: Long, zone: TimeZone): String =
    java.text.SimpleDateFormat("EEE", Locale.US).apply { timeZone = zone }.format(millis)

private fun shortDate(millis: Long, zone: TimeZone): String =
    java.text.SimpleDateFormat("d MMM", Locale.US).apply { timeZone = zone }.format(millis)

private fun startOfDay(millis: Long, zone: TimeZone): Long =
    Calendar.getInstance(zone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * The status a row can honestly claim today.
 *
 * `GET /api/session` carries no run-status field on any of the 50 recorded sessions, and live
 * streaming is a Phase non-goal, so the app genuinely cannot distinguish "idle" from "running".
 * Rather than guess, the row reports [SessionStatus.UNKNOWN] and the meta line spells out
 * "unknown" next to the dot. See [SessionInfo.statusWord] and the a11y note on the screen: the
 * dot must NEVER be the only carrier of state, and "unknown" is the honest word for "this app
 * does not know".
 */
internal fun statusFor(session: SessionInfo): SessionStatus = SessionStatus.UNKNOWN

/**
 * The dot colour for a status, from the style's own tokens.
 *
 * ## Why UNKNOWN is NOT a status colour
 *
 * `status.idle` is the design's own "idle / healthy / done" and reads green -- an affirmative
 * claim about state. UNKNOWN means "this app does not know", which is not the same claim, so it
 * gets a NEUTRAL token (`text.secondary`, the same one the meta line already uses) rather than a
 * status colour. Accent is out of the question by the design's own rule: accent means "action", a
 * dot means "state".
 *
 * ## Why `status.running` is here despite being below the non-text floor
 *
 * `status.running` measures 2.60:1 against the page, under the 3:1 floor for a non-text cue. It
 * is still the only honest colour for that state, and it is legal on this screen because the
 * status WORD is always rendered beside the dot (see `MetaLine`) -- the pairing, not the pixel,
 * is what carries the state. `SessionsStyleTest` pins that rule.
 */
internal fun SessionsStyle.statusDotFor(status: SessionStatus): Color = when (status) {
    SessionStatus.IDLE -> statusDot
    SessionStatus.RUNNING -> runningDot
    SessionStatus.ERROR -> errorDot
    SessionStatus.UNKNOWN -> unknownDot
}

/**
 * The meta line: `project · status · qualifier`.
 *
 * Only segments backed by real data appear. A session with no project directory contributes no
 * project segment (we do not invent one), and the status segment is ALWAYS present -- see
 * [SessionInfo.statusWord].
 */
internal fun metaSegmentsFor(session: SessionInfo, hasChildren: Boolean): List<MetaSegment> {
    val segments = mutableListOf<MetaSegment>()

    // project: the last path segment of the session's directory, e.g. `Wordwise` from
    // `/home/musa/Projects/Wordwise`. Real data (present on 50/50 recorded sessions); a session
    // with no location simply has no project segment.
    session.location?.directory?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let {
        segments += MetaSegment(it, MetaEmphasis.MUTED)
    }

    // status: always, and always adjacent to the dot. "unknown" is a real answer -- it is what
    // the app knows -- and the a11y rule is that the dot never stands alone.
    segments += MetaSegment(session.statusWord(), MetaEmphasis.MUTED)

    // qualifier: for a sub-agent, its agent name in agentBlue (the design's nesting treatment). We
    // do not currently model an `agent` field on SessionInfo (the v2 session payload has one, but
    // our model does not decode it and the brief says do not widen scope), so a sub-agent with no
    // decodable agent name gets the honest "sub-agent" qualifier instead of a fabricated name.
    if (session.isSubAgentSession) {
        segments += MetaSegment(session.subAgentQualifier(), MetaEmphasis.AGENT_BLUE)
    }

    // A session that spawned sub-agents is a parent: qualify it as such (the design's "2
    // subagents" qualifier). Real data -- the children are in the tree we were handed.
    if (hasChildren) {
        segments += MetaSegment("has sub-agents", MetaEmphasis.MUTED)
    }

    return segments
}

/**
 * The status word shown in the meta line. The screen renders the dot AND this word together,
 * always; that pairing is the a11y requirement, because `status.running` (and any dot-only cue)
 * is below the 3:1 non-text floor and is only acceptable with a text label beside it.
 */
internal fun SessionInfo.statusWord(): String = when (statusFor(this)) {
    SessionStatus.IDLE -> "idle"
    SessionStatus.RUNNING -> "running"
    // Not the design's "provider error": this model has no provider field, so claiming a provider
    // failed would be inventing state. "error" is what an error IS.
    SessionStatus.ERROR -> "error"
    SessionStatus.UNKNOWN -> "unknown"
}

/**
 * The sub-agent's own qualifier, in `agentBlue`.
 *
 * Our [SessionInfo] does not decode the `agent` field the v2 payload carries, so there is no
 * agent name to render and we must not invent one. The honest label is the word "sub-agent". The
 * design wants an agent NAME here; that arrives when the model gains the field, and the wording
 * of this function is the single place that changes.
 */
internal fun SessionInfo.subAgentQualifier(): String = SUB_AGENT_QUALIFIER

/**
 * The sub-agent marker rendered in `agentBlue`. A named constant rather than a literal at the
 * call site, so the screen test asserts against the SAME string the screen renders -- a reworded
 * marker cannot pass the test that says orphans are still sub-agents.
 */
internal const val SUB_AGENT_QUALIFIER = "sub-agent"

/**
 * The timestamp a row groups by: the most recent activity, falling back to creation.
 *
 * `updated` is what the user last touched; `created` is what exists if the server never sent an
 * update. A session with neither is genuinely undated and groups to EARLIER with an
 * [UNKNOWN_TIMESTAMP] label.
 */
internal fun SessionInfo.mostRecentTimeMillis(): Long? =
    time?.updated ?: time?.created

private const val MINUTE_MILLIS = 60_000L
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
