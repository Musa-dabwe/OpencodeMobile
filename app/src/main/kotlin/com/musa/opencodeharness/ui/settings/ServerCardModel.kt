package com.musa.opencodeharness.ui.settings

import com.musa.opencodeharness.data.runtime.launch.ServerState
import com.musa.opencodeharness.ui.ConnectionState

/**
 * Which of the three status dot colours the card paints. Deliberately a small closed set rather
 * than a `Color`, so the mapper can be asserted without a theme and the composable has exactly one
 * place that turns a role into a colour.
 */
internal enum class ServerDot { HEALTHY, STARTING, ERROR }

/**
 * What the `OpenCode server` card says, as plain data.
 *
 * Built by [serverCardModel] from real launcher and connection state -- there is no field here a
 * caller can fill with a plausible-looking value by hand, which is the whole point: the design's
 * `v2.0.6 · 127.0.0.1:4096 · Alpine` sub-line is only honest if every part of it is read off
 * something the app actually observed.
 */
internal data class ServerCardModel(
    /** `OpenCode server running` / `stopped` / `not responding` / `starting`. */
    val headline: String,
    /**
     * The word that carries the dot.
     *
     * Never optional. `status.running` measures 2.60:1 on the page, under the 3:1 non-text floor,
     * and Task 3's rule generalises: a dot must never assert more than the word beside it. There is
     * no branch of this card that renders a dot alone.
     */
    val stateWord: String,
    val dot: ServerDot,
    /**
     * `v2.0.22 · 127.0.0.1:45997 · Alpine`, or null when there is nothing real to put in it.
     *
     * Parts are OMITTED rather than filled with a placeholder: no version reported means the version
     * is absent, not `v?.?.?`.
     */
    val subLine: String?,
    /**
     * The failure sentence -- a death's exit code or launch error, or the unreachable reason.
     *
     * Derived from [ServerState] rather than read from `SessionListUiState.serverDeath`, which is a
     * `String?` the runtime sets once and only clears on the next `run()`. A stale sentence beside a
     * freshly spawned server is exactly the lie this card must not tell.
     */
    val detail: String?,
)

/** The design's own card headline, reused verbatim for the state the design shows. */
internal const val SERVER_CARD_HEADLINE = "OpenCode server"

/**
 * The guest OS this app installs and runs the server in.
 *
 * A fact about what the app ships, not a decoration: the runtime manifest installs Alpine `.apk`
 * packages into a minirootfs and the launcher executes `/usr/local/bin/opencode` inside it with
 * `apk` as its package manager. It is named in one constant rather than repeated at a call site so
 * no row can drift from the card.
 */
internal const val RUNTIME_OS_LABEL = "Alpine"

/** The only host the launcher ever binds. Design section 3.7 shows it, so the card asserts it. */
private const val LOOPBACK_HOST = "127.0.0.1"

/** The design's own separator between sub-line segments, the same glyph Task 3's meta line uses. */
internal const val SUB_LINE_SEPARATOR = " · "

/**
 * The card, as a pure function of real state.
 *
 * ## The precedence, and why
 *
 *  1. [ServerState.Dead] wins outright. A server that exited must never leave a card reading
 *     "running", whatever the HTTP client last managed to say.
 *  2. [ServerState.Running] + [ConnectionState.Healthy] is the only state the design's own
 *     `OpenCode server running` describes, and the only one that gets a version and an endpoint.
 *  3. [ServerState.Running] + [ConnectionState.Unreachable] is a distinct and real case: the process
 *     exists but its HTTP server is not answering. It is NOT the same as dead, so it gets its own
 *     word rather than being folded into "stopped".
 *  4. Anything still in flight -- [ServerState.Idle] before the first spawn, or a [ConnectionState.Connecting]
 *     against a live process -- is `starting`.
 *
 * ## No pid, ever
 *
 * `ServerState.Running.pid` is deliberately never read. `Process.pid()` is a Java 9 API absent from
 * `android.jar`, so on every real device it resolves to `-1`, and a card that prints `pid -1` is
 * asserting something false. `ProotHandle.pidOrNull()` exists for the day a UI wants one, and until
 * then the honest answer is to show none. `ServerCardModelTest` pins that by asserting a card built
 * from `pid = -1` is indistinguishable from one built from a plausible pid.
 *
 * @param serverState the launcher's own [ServerState] -- the single source for "is it alive".
 * @param connection what the HTTP client last managed to do against that server.
 * @param version `GET /api/info`'s `version`, or null when the server reported none. NEVER a constant.
 * @param serverUrl the client's base URL, from which the `host:port` endpoint is read.
 */
internal fun serverCardModel(
    serverState: ServerState,
    connection: ConnectionState,
    version: String?,
    serverUrl: String?,
): ServerCardModel {
    // The sub-line describes a SERVER, so it is built only on the branch where one exists. A card
    // reading `starting` with `Alpine` under it would imply the runtime is installed and the server
    // is on its way, which is an inference the app has not made yet.
    val subLine = if (serverState is ServerState.Running) {
        listOfNotNull(
            version?.trim()?.takeIf { it.isNotEmpty() }?.let(::withVPrefix),
            endpointLabel(serverUrl),
            RUNTIME_OS_LABEL,
        ).takeIf { it.isNotEmpty() }?.joinToString(SUB_LINE_SEPARATOR)
    } else {
        null
    }

    return when (serverState) {
        is ServerState.Dead -> ServerCardModel(
            headline = "$SERVER_CARD_HEADLINE stopped",
            stateWord = "stopped",
            dot = ServerDot.ERROR,
            // A dead server has no port to advertise, so the sub-line is dropped rather than
            // pointing the user at an endpoint nothing is listening on.
            subLine = null,
            detail = deathSentence(serverState),
        )

        is ServerState.Running -> when (connection) {
            is ConnectionState.Healthy -> ServerCardModel(
                headline = "$SERVER_CARD_HEADLINE running",
                stateWord = "running",
                dot = ServerDot.HEALTHY,
                subLine = subLine,
                detail = null,
            )

            is ConnectionState.Unreachable -> ServerCardModel(
                headline = "$SERVER_CARD_HEADLINE not responding",
                stateWord = "not responding",
                dot = ServerDot.ERROR,
                subLine = subLine,
                detail = connection.reason,
            )

            ConnectionState.Connecting -> ServerCardModel(
                headline = "$SERVER_CARD_HEADLINE starting",
                stateWord = "starting",
                dot = ServerDot.STARTING,
                subLine = subLine,
                detail = null,
            )
        }

        ServerState.Idle -> ServerCardModel(
            headline = "$SERVER_CARD_HEADLINE starting",
            stateWord = "starting",
            dot = ServerDot.STARTING,
            subLine = subLine,
            detail = null,
        )
    }
}

/**
 * One complete, user-presentable sentence about why the server is gone.
 *
 * ## Why this duplicates `RuntimeSetup.describeDeath`
 *
 * Because the card must be a pure function of [ServerState] to be unit-testable at all: the
 * runtime's version is private, and it only exists on the production path, so a test that could not
 * build its own `ServerState.Dead` would be a test of the wiring rather than of the card. The two
 * cover the same three cases (launch error, signal, bare exit code); if they ever disagree, the
 * card wins, because the card is reading the CURRENT state rather than a sentence written once.
 */
internal fun deathSentence(state: ServerState.Dead): String {
    state.launchError?.let { return "launch failed: $it" }
    val code = state.exitCode
    return when {
        state.signal != null -> "exit code $code (killed by signal ${state.signal})"
        code != null -> "exit code $code"
        else -> "unknown cause (no exit code was reported)"
    }
}

/**
 * The `127.0.0.1:<port>` half of the design's sub-line, read off the client's own base URL.
 *
 * ## What is deliberately rejected
 *
 * The scheme is stripped (the design shows a bare `host:port`), and anything that is not a loopback
 * `host:digits` is refused rather than guessed at. The launcher only ever binds 127.0.0.1, so this
 * branch is unreachable in production -- which is exactly why it can be a cheap guard instead of a
 * format to keep in sync: if a future endpoint ever stopped being loopback, the card would show no
 * endpoint at all rather than rendering a remote host as if it were the user's own server.
 */
internal fun endpointLabel(serverUrl: String?): String? {
    val raw = serverUrl?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val authority = raw.substringAfter("://", raw).trimEnd('/')
    // A path or query means this is a URL rather than a bare authority; refuse rather than truncate.
    if (authority.isEmpty() || '/' in authority) return null
    if (authority.substringBefore(':') != LOOPBACK_HOST) return null
    val port = authority.substringAfter(':', "")
    return port.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "$LOOPBACK_HOST:$it" }
}

/** `2.0.22` reads as `v2.0.22` in the design; a server that already sends a `v` is left alone. */
private fun withVPrefix(version: String): String =
    if (version.startsWith("v")) version else "v$version"
