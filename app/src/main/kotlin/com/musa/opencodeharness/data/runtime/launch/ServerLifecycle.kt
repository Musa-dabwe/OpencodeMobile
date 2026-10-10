package com.musa.opencodeharness.data.runtime.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the launcher knows about the server process. Deliberately only states an observer can
 * honestly confirm:
 *
 *  - [Idle] — nothing spawned (yet).
 *  - [Running] — `ProcessBuilder.start()` succeeded; the process exists. It is NOT a claim
 *    that the HTTP server is accepting connections — that needs a health probe and is a later
 *    wiring concern (Task 5). A Bun binary can abort ~160 ms after spawn (see
 *    docs/runtime-artifacts.md), so [Running] can be very short-lived, which is precisely why
 *    death must be observable and not inferred from "start() returned".
 *  - [Dead] — the process is gone, with the exit code the JVM reported, or the launch failure
 *    if the process never existed.
 */
sealed interface ServerState {
    data object Idle : ServerState

    data class Running(val pid: Int, val port: Int) : ServerState

    data class Dead(
        /** Null when the process never started, or when the wait was interrupted. */
        val exitCode: Int?,
        /** Non-null only when [exitCode] looks like the Unix `128 + signal` convention. */
        val signal: Int?,
        /** Non-null only for a launch failure that happened before any process existed. */
        val launchError: String? = null,
    ) : ServerState
}

/** Events fed to [transition]. */
sealed interface ServerEvent {
    data class Spawned(val pid: Int, val port: Int) : ServerEvent

    data class Exited(val exitCode: Int?) : ServerEvent

    data class LaunchFailed(val message: String) : ServerEvent

    /**
     * Posted by [ProotLauncher.start] before doing any work: the deliberate intent to begin a
     * (re)run. This is what separates "a fresh run legitimately leaves Dead" from "a stale
     * event resurrected a dead run" — only this explicit intent reopens [ServerState.Dead];
     * a late [Spawned]/[Exited] on its own still cannot.
     */
    data object StartRequested : ServerEvent
}

/**
 * The death-detection state machine, as a pure function so it is testable without a process.
 *
 * Transition rules:
 *
 *  - Idle + Spawned -> Running.
 *  - Idle + LaunchFailed -> Dead(null, null, message): start() failed before any process.
 *  - Running + Exited -> Dead(exitCode, signal?).
 *  - Dead + StartRequested -> Idle: the launcher's deliberate intent to begin a NEW run.
 *    This is the only way out of Dead — without it, restart-after-death would be impossible.
 *  - Any event not applicable to the current state is **ignored** (returns the state
 *    unchanged), never an exception and never a resurrection: a late or duplicated event —
 *    a reaper firing after `stop()`, an exit racing a launch failure, a stale `Spawned`
 *    without a preceding `StartRequested` — must not be able to move the machine backwards.
 *    `Dead` stays terminal for one run; only an explicit [ServerEvent.StartRequested]
 *    opens the next one.
 *
 * Signal derivation: on Unix the JVM reports a signal-killed process as exit code
 * `128 + signal` (the shell convention), so a code in (128, 192] is decoded back to the
 * signal number. Codes <= 128 and absurd codes stay `signal = null` rather than inventing a
 * signal that was never delivered.
 */
fun transition(state: ServerState, event: ServerEvent): ServerState = when {
    state is ServerState.Idle && event is ServerEvent.Spawned ->
        ServerState.Running(pid = event.pid, port = event.port)

    state is ServerState.Idle && event is ServerEvent.LaunchFailed ->
        ServerState.Dead(exitCode = null, signal = null, launchError = event.message)

    state is ServerState.Running && event is ServerEvent.Exited ->
        ServerState.Dead(exitCode = event.exitCode, signal = decodeSignal(event.exitCode))

    state is ServerState.Dead && event is ServerEvent.StartRequested ->
        ServerState.Idle

    // Re-opening a finished launch: a no-op so a duplicated StartRequested cannot, say,
    // wipe a Running state the launcher is about to replace anyway.
    state is ServerState.Idle && event is ServerEvent.StartRequested ->
        ServerState.Idle

    state is ServerState.Running && event is ServerEvent.StartRequested ->
        state

    else -> state
}

/** `128 + N` for N in 1..64 (a range that includes every real-time signal) -> N, else null. */
internal fun decodeSignal(exitCode: Int?): Int? {
    if (exitCode == null) return null
    val signal = exitCode - 128
    return signal.takeIf { it in 1..64 }
}

/**
 * Holds the current [ServerState] and applies [transition] under a lock so the reader thread's
 * `Exited` and the UI thread's observations cannot interleave into a lost update.
 */
class ServerLifecycle {
    private val lock = Any()
    private val _state = MutableStateFlow<ServerState>(ServerState.Idle)

    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun post(event: ServerEvent): ServerState = synchronized(lock) {
        val next = transition(_state.value, event)
        _state.value = next
        next
    }
}
