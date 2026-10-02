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

/** The `location` key alongside `data`. Carries the directory the list was scoped to. */
@Serializable
data class Location(
    val directory: String,
    val project: ProjectRef,
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
)

/** One element of `GET /api/agent`. */
@Serializable
data class Agent(
    val id: String,
    val name: String,
    val description: String? = null,
)

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
