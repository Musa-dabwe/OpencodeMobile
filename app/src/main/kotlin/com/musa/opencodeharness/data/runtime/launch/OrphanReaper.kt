package com.musa.opencodeharness.data.runtime.launch

/**
 * Finds and kills servers left over from a previous, dead run of this app.
 *
 * ## The recovery story (Global Constraints: setup must be resumable after process death)
 *
 * When the app process dies (swipe-away, LMK, crash), its child `proot` may survive: proot is
 * an independent process, and killing the tracee's parent does not necessarily take the
 * `opencode serve` tracee with it (there is no `--kill-on-exit` in the verified invocation —
 * the invocation is frozen, see docs/runtime-artifacts.md). The next run therefore has to cope
 * with a half-orphaned server:
 *
 *  1. **Detect, do not adopt.** The previous password existed only in that process's memory
 *     (per-plan: memory only, no Keystore this slice), so a surviving server is *unusable* by
 *     the new run — authenticating is impossible. Adoption is off the table; the sane options
 *     are kill-and-restart or ignore-and-use-a-different-port.
 *  2. **Identify by command line, not by pid.** A pid read from a file after process death may
 *     already have been reused by something else entirely. Instead we scan the readable
 *     `/proc/<pid>/cmdline`s for our own signature: the argv of this instance is
 *     `... opencode serve --hostname 127.0.0.1 --port <port>`, so a process matches only if
 *     its cmdline has BOTH an `opencode` argv token AND a `--port <recorded port>` argument
 *     pair ([matchesInstance] tokenises, so port 4567 can never match `--port 45678`). Only
 *     same-uid processes have readable cmdlines, and no other same-uid process runs this
 *     argv, so a match is our orphan with near-certainty.
 *  3. **Never block, never throw.** The record is consumed (deleted) unconditionally, any
 *     kill is best-effort, and an unreadable `/proc` degrades to "orphan not found" — the
 *     new run then simply picks a port the kernel proves free ([PortPicker]) and starts
 *     alongside the leak. Worst case is a leaked process, never a bricked app.
 *  4. **Post-stop sweep.** [sweep] is also used after `stop()`/`destroy()`, because killing
 *     proot alone can leave the tracee running; the tracee carries the same `--port` argv.
 */
class OrphanReaper(
    private val store: LaunchRecordStore,
    /** pid -> cmdline for processes whose cmdline we may read (same uid); never throws. */
    private val readProcCmdlines: () -> Map<Int, String>,
    /** Sends SIGKILL to [pid]; best-effort, may throw (callers wrap). */
    private val kill: (Int) -> Unit,
    private val waitMillis: (Long) -> Unit = { Thread.sleep(it) },
) {
    /**
     * Consumes the launch record and kills every process matching its port.
     * Returns the number of processes killed. Throws nothing, ever.
     */
    fun reap(): Int {
        val record = try {
            store.read()
        } catch (e: Exception) {
            null
        }
        // Delete FIRST: whatever happens below, this run cannot be blocked by the record.
        try {
            store.clear()
        } catch (e: Exception) {
            // A record we cannot delete is still only a hint; proceed.
        }
        if (record == null) return 0
        return killMatching(record.port)
    }

    /** Kills any process whose cmdline is this server instance's argv for [port]. */
    fun sweep(port: Int): Int = try {
        killMatching(port)
    } catch (e: Exception) {
        0
    }

    private fun killMatching(port: Int): Int {
        val victims = HashSet<Int>()
        collectMatching(port, victims)
        for (pid in victims) {
            try {
                kill(pid)
            } catch (e: Exception) {
                // Best effort: a failed kill leaves a leak, not a wedge.
            }
        }
        // The tracee can outlive proot for a moment (or entirely, if it was reparented);
        // poll briefly and re-kill anything still showing our argv.
        if (victims.isNotEmpty()) {
            repeat(REAP_POLLS) {
                waitMillis(REAP_POLL_MILLIS)
                val survivors = HashSet<Int>()
                collectMatching(port, survivors)
                if (survivors.isEmpty()) return victims.size
                for (pid in survivors) {
                    try {
                        kill(pid)
                        victims.add(pid)
                    } catch (e: Exception) {
                        // Already dead or unkillable — done with it.
                    }
                }
            }
        }
        return victims.size
    }

    private fun collectMatching(port: Int, into: MutableSet<Int>) {
        val cmdlines = try {
            readProcCmdlines()
        } catch (e: Exception) {
            emptyMap()
        }
        for ((pid, cmdline) in cmdlines) {
            if (matchesInstance(cmdline, port)) into.add(pid)
        }
    }

    companion object {
        private const val REAP_POLLS = 10
        private const val REAP_POLL_MILLIS = 100L

        /**
         * True iff [cmdline] is (or belongs to) the `opencode serve` instance we started on
         * [port]. Both required tokens must appear as whitespace-separated argv tokens:
         * `opencode` alone matches nothing (this app's own package name contains "opencode"
         * but never a `--port` argument), and `--port` must be followed by exactly our port
         * number, so a recorded port can never match a longer port number.
         */
        fun matchesInstance(cmdline: String, port: Int): Boolean {
            val tokens = cmdline.split(WHITESPACE).filter { it.isNotEmpty() }
            var sawOpencode = false
            for (i in tokens.indices) {
                val token = tokens[i]
                if (token == "opencode" || token.endsWith("/opencode")) sawOpencode = true
                if (token == "--port" && i + 1 < tokens.size && tokens[i + 1] == port.toString()) {
                    return sawOpencode
                }
            }
            return false
        }

        /**
         * Reads readable cmdlines from /proc. Same-uid only — other uids fail the kernel's
         * ptrace check and are skipped, so the map is exactly "processes we could kill".
         * A restricted or unlistable /proc yields an empty map: reap then kills nothing,
         * which is the safe degradation (leak, never brick).
         */
        fun readProcCmdlines(): Map<Int, String> {
            val out = HashMap<Int, String>()
            val names = java.io.File("/proc").list() ?: return out
            for (name in names) {
                val pid = name.toIntOrNull() ?: continue
                try {
                    val raw = java.io.File("/proc", "$name/cmdline").readBytes()
                    if (raw.isEmpty()) continue // zombie or kernel thread: nothing to kill
                    // cmdline is NUL-separated; spaces make it tokenisable like real argv.
                    out[pid] = String(raw, Charsets.UTF_8).replace(Char(0), ' ')
                } catch (e: Exception) {
                    // Other-uid, vanished, or restricted: not ours, skip.
                }
            }
            return out
        }

        private val WHITESPACE = Regex("\\s+")
    }
}