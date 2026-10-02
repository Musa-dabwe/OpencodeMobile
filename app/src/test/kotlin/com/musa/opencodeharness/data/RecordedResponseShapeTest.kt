package com.musa.opencodeharness.data

import com.musa.opencodeharness.data.model.Agent
import com.musa.opencodeharness.data.model.Envelope
import com.musa.opencodeharness.data.model.Health
import com.musa.opencodeharness.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the recorded response shapes in `docs/samples` (real captures from a running
 * OpenCode server) against the models, so schema drift fails the build instead of the app.
 *
 * The fixtures are the committed sample files themselves, loaded off the test classpath via
 * the `test` resources srcDir wired in `app/build.gradle.kts`. Nothing here is hand-written
 * JSON, and nothing touches a device, a network, or a server.
 *
 * `openCodeJson` is the same instance the client hands to Ktor's ContentNegotiation, so these
 * decode exactly what production decodes.
 */
class RecordedResponseShapeTest {

    private fun sample(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)) {
            "Missing recorded sample $name on the test classpath"
        }.bufferedReader().use { it.readText() }

    private fun sessions(): Envelope<SessionInfo> =
        openCodeJson.decodeFromString(sample("session.json"))

    private fun agents(): Envelope<Agent> =
        openCodeJson.decodeFromString(sample("agent.json"))

    /** The list envelope is `{"data":[…],"cursor":{…}}`; a bare-array decode would fail. */
    @Test
    fun sessionEnvelope_decodesDataAndCursor() {
        val envelope = sessions()

        assertEquals(50, envelope.data.size)
        val cursor = requireNotNull(envelope.cursor) { "session.json carries a cursor" }
        assertNotNull("recorded session.json carries a cursor.previous", cursor.previous)
        assertNotNull("recorded session.json carries a cursor.next", cursor.next)
        // session.json has no sibling `location` key -- only agent.json does.
        assertNull(envelope.location)
    }

    /**
     * The sub-agent grouping invariant (Task 4): a root session omits `parentID` entirely and
     * must decode to null rather than throwing MissingFieldException.
     */
    @Test
    fun session_withoutParentId_decodesWithNullParent() {
        val roots = sessions().data.filter { it.parentID == null }

        assertEquals(16, roots.size)
        assertTrue("every root needs an id to key on", roots.all { it.id.isNotBlank() })
        // A session's own location carries only `directory` -- no `project` sub-object.
        assertTrue(
            "recorded session location has a directory and no project",
            roots.all { it.location?.directory?.isNotBlank() == true && it.location.project == null },
        )
    }

    @Test
    fun session_withParentId_preservesParentId() {
        val all = sessions().data
        val children = all.filter { it.parentID != null }

        assertEquals(34, children.size)
        val ids = all.mapTo(mutableSetOf()) { it.id }
        assertTrue(
            "every recorded parentID resolves to a session in the same list",
            children.all { it.parentID in ids },
        )
        // Verbatim from the capture: this sub-agent session hangs off a root session.
        val child = children.first { it.id == "ses_f4a43dfc4ffeWLCJ8Ey0VJZqHq" }
        assertEquals("ses_f4a637ae1ffewUvbHzzhYgQZ30", child.parentID)
        assertEquals(3, children.mapTo(mutableSetOf()) { it.parentID }.size)
    }

    @Test
    fun health_decodesHealthyTrue() {
        val health = openCodeJson.decodeFromString<Health>(sample("health.json"))

        assertTrue(health.healthy)
    }

    /**
     * `agent.json` is the other envelope shape: `{"location":{…},"data":[…]}` with no cursor,
     * and no agent carries a `name` key -- so `displayName` must fall back to `id`.
     */
    @Test
    fun agentEnvelope_decodesWithIdAsDisplayNameFallback() {
        val envelope = agents()

        assertEquals(32, envelope.data.size)
        assertNull(envelope.cursor)
        assertEquals("/tmp/opencode", envelope.location?.directory)
        assertEquals("global", envelope.location?.project?.id)
        assertTrue("every recorded agent omits name", envelope.data.all { it.name == null })
        assertTrue("every agent exposes a mode", envelope.data.all { it.mode != null })
        assertTrue(
            "displayName falls back to id and is never blank",
            envelope.data.all { it.displayName == it.id && it.displayName.isNotBlank() },
        )
    }
}
