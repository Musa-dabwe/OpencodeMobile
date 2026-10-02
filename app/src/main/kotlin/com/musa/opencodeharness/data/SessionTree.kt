package com.musa.opencodeharness.data

import com.musa.opencodeharness.data.model.SessionInfo

/**
 * One session and its direct sub-agent children, recursively.
 *
 * [children] is empty for a session with no sub-agents. It is NOT an "is this a sub-agent of
 * something" marker -- it answers the opposite direction of the question, and on the recorded
 * 50-session capture it is empty for 47 of 50 sessions, so a UI that read it as the sub-agent
 * flag would mark every childless root as a sub-agent and the 3 real parents as not. The UI asks
 * [SessionInfo.parentID] instead; see `isSubAgentSession` in the ui package and
 * `SubAgentMarkerTest`, which pins the two apart.
 */
data class SessionNode(
    val session: SessionInfo,
    val children: List<SessionNode>,
)

/**
 * Builds the parent -> children session tree from the flat `GET /api/session` list.
 *
 * The server has NO children-listing route (verified, see docs/phase0-findings.md), so the only
 * way to know that a session is a sub-agent of another is to group the flat list on
 * [SessionInfo.parentID]. That is all this function does: it is pure, has no I/O, no coroutines
 * and no Android dependency, so it is trivially testable against recorded fixtures.
 *
 * Rules, in order of application:
 *
 *  - A session with no `parentID` is a root. Verified on the real capture: 16 of 50 sessions in
 *    `docs/samples/session.json` omit the key entirely.
 *  - Nesting is unbounded, not one level. The capture only nests one level deep (see
 *    `SessionTreeTest.recordedSessionList_groupsIntoParentsAndChildren`), but nothing here assumes
 *    that.
 *  - ORPHANED CHILDREN ARE TREATED AS ROOTS, not dropped. A session whose `parentID` names an id
 *    absent from the list is promoted to the top level. WHY: this tree exists to drive a list UI
 *    over exactly this flat list, so dropping would make a session that the server did return
 *    silently disappear from the UI with no error and no way to reach it. `GET /api/session` is
 *    paginated -- the recorded envelope carries both `cursor.previous` and `cursor.next` -- so a
 *    child landing on a different page than its parent is a real, expected way to get an orphan,
 *    not a corrupt-payload case. Promoting keeps it visible and renderable as an ordinary row;
 *    the alternative trades a display glitch for data loss. The invariant this function upholds:
 *    every input session appears in the tree exactly once.
 *  - Order is preserved. Roots come back in server order, and each node's `children` in server
 *    order, so the UI shows the same sequence it would have shown from the flat list.
 *
 * Defensive handling, kept minimal because none of it was observed on the real server:
 *  - Duplicate ids: first occurrence wins. Ids are primary keys and unique per session, so a
 *    duplicate means two overlapping pages were merged; first-wins is deterministic and guarantees
 *    no session is rendered twice.
 *  - `parentID` cycles (A -> B -> A) and self-parenting (A -> A): every node in such a cycle has a
 *    *known* parent, so none qualifies as a root and the whole group would otherwise vanish.
 *    Nothing reachable from a root is promoted to the top level as childless entries -- each cycle
 *    member becomes its own entry, so none is lost or listed twice. This covers a session hanging
 *    OFF a cycle member too (C with parentID = A): C is stranded rather than cyclic, and is
 *    promoted by the same path, giving up its placement under A but keeping its `parentID` on the
 *    record. Expansion never descends into a session already on the current path, so a cycle can
 *    neither recurse forever nor nest itself.
 */
fun buildSessionTree(sessions: List<SessionInfo>): List<SessionNode> {
    if (sessions.isEmpty()) return emptyList()

    // First occurrence wins; LinkedHashMap keeps input order for the groups built below.
    val byId = LinkedHashMap<String, SessionInfo>(sessions.size)
    for (session in sessions) byId.putIfAbsent(session.id, session)

    // groupBy preserves encounter order, so children stay in server order. The key may be null.
    val childrenByParent = byId.values.groupBy { it.parentID }

    fun isRoot(session: SessionInfo): Boolean =
        session.parentID == null || session.parentID !in byId

    fun expand(session: SessionInfo, ancestors: Set<String>): SessionNode {
        val children = childrenByParent[session.id]
            .orEmpty()
            .filterNot { it.id in ancestors } // cycle guard: never descend onto the current path
            .map { expand(it, ancestors + it.id) }
        return SessionNode(session, children)
    }

    val roots = byId.values.filter(::isRoot)
    val tree = roots.map { expand(it, ancestors = setOf(it.id)) }

    // A root walk cannot reach two kinds of session, and BOTH are promoted to the top level so
    // the "appears exactly once" invariant still holds:
    //   1. the members of a parentID cycle (A <-> B), whose every parent link points inside the
    //      cycle, so nothing in it can ever be a root;
    //   2. anything hanging OFF a cycle member (C with parentID = A) -- C is not in a cycle
    //      itself, it is simply stranded because its parent is only reachable through the cycle.
    // Promoting C loses its placement under A, but it keeps its own `parentID` on the record and
    // stays visible, which is the same trade the orphan rule makes.
    // Every promoted id is seeded as already-on-the-path, so cycle members cannot end up as each
    // other's children and be emitted twice.
    val reachable = HashSet<String>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val session = queue.removeFirst()
        if (reachable.add(session.id)) queue.addAll(childrenByParent[session.id].orEmpty())
    }
    if (reachable.size == byId.size) return tree

    val promoted = byId.keys - reachable
    val promotedIds = promoted.toSet()
    // getValue, not mapNotNull: every id in `promoted` came out of `byId.keys` moments ago.
    return tree + promoted.map { byId.getValue(it) }.map { expand(it, ancestors = promotedIds + it.id) }
}

/**
 * Depth-first search for a session anywhere in the tree, by id. Returns null when absent.
 *
 * Convenience for the UI: expanding a row does not require keeping a parallel id -> node map.
 */
internal fun List<SessionNode>.findSessionNode(id: String): SessionNode? {
    for (node in this) {
        if (node.session.id == id) return node
        node.children.findSessionNode(id)?.let { return it }
    }
    return null
}
