package com.musa.opencodeharness.data.model

import kotlinx.serialization.Serializable

/**
 * The response envelope every OpenCode list endpoint returns.
 *
 * Verified against a live v1.18.34 server:
 * `{"location":{"directory":"/tmp/opencode","project":{"id":"global","directory":"/"}},"data":[…]}`
 *
 * The v2 OpenAPI spec instead documents `{"data":[…],"cursor":{…}}` for `/api/session`.
 * Both sibling keys are therefore optional here so either server shape decodes; a bare
 * array fails against both. See docs/phase0-findings.md section 5.
 */
@Serializable
data class Envelope<T>(
    val data: List<T>,
    val location: Location? = null,
    val cursor: Cursor? = null,
)

/**
 * The `location` key. It appears in two shapes, and the same type has to decode both:
 *
 *  - envelope level, e.g. `docs/samples/agent.json`:
 *    `{"directory":"/tmp/opencode","project":{"id":"global","directory":"/"}}`
 *  - a session's own location, e.g. `docs/samples/session.json` (50/50 sessions):
 *    `{"directory":"/home/musa/Projects/Wordwise"}` -- no `project` at all
 *
 * So `project` is optional; requiring it threw `MissingFieldException` on the session list.
 */
@Serializable
data class Location(
    val directory: String,
    val project: ProjectRef? = null,
)

@Serializable
data class ProjectRef(
    val id: String,
    val directory: String,
)

/** Pagination cursor. Only documented in the v2 spec; absent on the verified v1 server. */
@Serializable
data class Cursor(
    val previous: String? = null,
    val next: String? = null,
)

/**
 * `GET /api/health` -> `{"healthy":true}`.
 *
 * NOT `/api/info`: that route does not exist on the server and falls through to the SPA
 * catch-all, returning `index.html` with `Content-Type: text/html`.
 */
@Serializable
data class Health(
    val healthy: Boolean,
)

/** One element of `GET /api/session`. Ids match `^ses`. */
@Serializable
data class SessionInfo(
    val id: String,
    val title: String? = null,
    /**
     * Absent on root sessions, present on sub-agent sessions (50 sessions, 34 with a
     * parentID on the verified server). There is no children-listing route -- the session
     * tree is built client-side by grouping on this field.
     */
    val parentID: String? = null,
    val projectID: String? = null,
    /** Its own location; see [Location] for why `project` is absent here. */
    val location: Location? = null,
    val time: SessionTime? = null,
)

/**
 * Session timestamps. Present on 50/50 sessions in `docs/samples/session.json` as
 * `{"created":<epoch millis>,"updated":<epoch millis>}` (e.g. 1789755400251). Both fields
 * are nullable so a partial payload degrades instead of throwing.
 */
@Serializable
data class SessionTime(
    val created: Long? = null,
    val updated: Long? = null,
)

/**
 * One element of `GET /api/agent`.
 *
 * NOTE: the live payload in `docs/samples/agent.json` carries `id`, `request`, `system`,
 * `description`, `mode`, `hidden`, `permissions` -- and NO `name` key on any of its 32
 * agents. `name` is therefore optional here; it is a v2-schema field only.
 */
@Serializable
data class Agent(
    val id: String,
    val description: String? = null,
    /**
     * Present on 32/32 agents in `docs/samples/agent.json`, with exactly two observed
     * values: `"primary"` and `"subagent"`. This is what distinguishes the main agents from
     * sub-agents (spec section 5.9).
     *
     * A nullable `String`, NOT an enum: an enum that meets a third value crashes on decode,
     * whereas a String can be compared literally and defaulted by the caller.
     */
    val mode: String? = null,
    val name: String? = null,
) {
    /**
     * What to show in the UI. `name` is absent from the live payload, so `id` is the
     * fallback -- it is present on 32/32 agents. An empty-string default would render every
     * real agent as a blank label.
     */
    val displayName: String get() = name ?: id
}

/**
 * One element of `GET /api/model`.
 *
 * Only the identifying fields are modelled; `capabilities`, `cost`, `variants`, `limit` and
 * friends are not needed until a model picker exists, and are absorbed by
 * `ignoreUnknownKeys`.
 */
@Serializable
data class Model(
    val id: String,
    val name: String,
    val providerID: String,
)

/** One element of `GET /api/command`. Only `name` is required by the schema. */
@Serializable
data class Command(
    val name: String,
    /** Absent on commands with no description. */
    val description: String? = null,
)

/**
 * One element of `GET /api/fs/list`.
 *
 * `type` is documented as `"file"` / `"directory"` in the v2 spec but kept as a `String`
 * because the v1 server's exact vocabulary was not probed. Compare literally rather than
 * through an enum until it has been.
 */
@Serializable
data class FsEntry(
    val path: String,
    val type: String,
)
