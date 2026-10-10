package com.musa.opencodeharness.data.runtime.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerLifecycleTest {

    private val lifecycle = ServerLifecycle()

    private fun running(pid: Int = 11, port: Int = 41234): ServerState.Running =
        ServerState.Running(pid, port)

    // ---- transition: the happy paths ----

    @Test
    fun idleSpawnedBecomesRunning() {
        val next = transition(ServerState.Idle, ServerEvent.Spawned(pid = 7, port = 40000))
        assertEquals(running(pid = 7, port = 40000), next)
    }

    @Test
    fun runningExitedZeroBecomesDeadWithoutSignal() {
        val next = transition(running(), ServerEvent.Exited(0))
        assertEquals(ServerState.Dead(exitCode = 0, signal = null), next)
    }

    @Test
    fun idleLaunchFailedBecomesDeadCarryingMessage() {
        val next = transition(ServerState.Idle, ServerEvent.LaunchFailed("spawn refused"))
        assertEquals(ServerState.Dead(null, null, launchError = "spawn refused"), next)
    }

    // ---- transition: death is terminal, never resurrectable ----

    @Test
    fun deadPlusSpawnedStaysDead() {
        // The mutation that must break this: allowing Dead + Spawned -> Running would let a
        // late reaper event resurrect a dead run and hide the death from the UI.
        val dead = ServerState.Dead(exitCode = 137, signal = 9)
        assertEquals(dead, transition(dead, ServerEvent.Spawned(1, 40000)))
    }

    @Test
    fun deadPlusDuplicateExitedStaysDeadUnchanged() {
        val dead = ServerState.Dead(exitCode = 1, signal = null)
        assertEquals(dead, transition(dead, ServerEvent.Exited(2)))
    }

    @Test
    fun deadPlusLaunchFailedStaysDead() {
        val dead = ServerState.Dead(exitCode = 0, signal = null)
        assertEquals(dead, transition(dead, ServerEvent.LaunchFailed("later failure")))
    }

    // ---- StartRequested: the one deliberate way out of Dead ----

    @Test
    fun deadPlusStartRequestedReopensAsIdleForANewRun() {
        // Without this rule a finished run could never be restarted (regression caught by
        // ProotLauncherTest.startCanBeRepeatedAfterDeath). The mutation: dropping the
        // `Dead + StartRequested -> Idle` branch leaves every restart stuck in Dead.
        val dead = ServerState.Dead(exitCode = 0, signal = null)
        assertEquals(ServerState.Idle, transition(dead, ServerEvent.StartRequested))
    }

    @Test
    fun startRequestedOnIdleOrRunningIsANoOp() {
        assertEquals(ServerState.Idle, transition(ServerState.Idle, ServerEvent.StartRequested))
        // start() rejects Running before posting StartRequested; if it ever did post, the
        // state must not be wiped back to Idle underneath a live run.
        assertEquals(running(), transition(running(), ServerEvent.StartRequested))
    }

    @Test
    fun staleSpawnedWithoutStartRequestedStillCannotResurrectDead() {
        // Restart goes Dead -> StartRequested -> Idle -> Spawned -> Running. A STALE
        // Spawned arriving without the StartRequested that opens a run must still be
        // ignored: only explicit intent reopens Dead.
        val dead = ServerState.Dead(exitCode = 137, signal = 9)
        assertEquals(dead, transition(dead, ServerEvent.Spawned(1, 40000)))
    }

    @Test
    fun runningPlusSpawnedIgnoresDoubleSpawn() {
        assertEquals(running(), transition(running(), ServerEvent.Spawned(99, 41999)))
    }

    @Test
    fun idlePlusStrayExitedStaysIdle() {
        // A reaper event arriving with nothing spawned must not flip Idle into Dead.
        assertEquals(ServerState.Idle, transition(ServerState.Idle, ServerEvent.Exited(0)))
    }

    @Test
    fun runningPlusLaunchFailedStaysRunning() {
        assertEquals(running(), transition(running(), ServerEvent.LaunchFailed("noise")))
    }

    // ---- decodeSignal: Unix 128 + N convention, decoded only when in range ----

    @Test
    fun signalDecodesCommonKillCodes() {
        // Mutation that must break this: `exitCode - 128` unconditionally (or no decoding).
        assertEquals(9, decodeSignal(137)) // SIGKILL
        assertEquals(15, decodeSignal(143)) // SIGTERM
        assertEquals(1, decodeSignal(129))
        assertEquals(64, decodeSignal(192))
    }

    @Test
    fun signalStaysNullOutsideTheConvention() {
        assertEquals(null, decodeSignal(0))
        assertEquals(null, decodeSignal(1)) // raw 1 is an exit code, not a signal
        assertEquals(null, decodeSignal(128)) // would decode to signal 0 — invalid
        assertEquals(null, decodeSignal(193)) // 128 + 65: beyond the signal range we trust
        assertEquals(null, decodeSignal(-1))
        assertEquals(null, decodeSignal(null))
    }

    // ---- ServerLifecycle holder: post() writes through to the observable ----

    @Test
    fun postUpdatesObservableState() {
        lifecycle.post(ServerEvent.Spawned(3, 40000))
        assertEquals(running(pid = 3, port = 40000), lifecycle.state.value)
        lifecycle.post(ServerEvent.Exited(137))
        assertEquals(ServerState.Dead(137, 9), lifecycle.state.value)
    }

    @Test
    fun startsIdle() {
        assertEquals(ServerState.Idle, lifecycle.state.value)
    }
}
