package com.musa.opencodeharness.data.runtime.launch

import java.io.File

/** What survives a process kill about the last launch attempt: the port. Nothing else. */
data class LaunchRecord(val port: Int)

/**
 * The on-disk record of which port the previous run's server was (or was about to be) started
 * on. Written BEFORE the spawn so there is no window where a live server has no record, and
 * cleared by the reaper once the exit is observed.
 *
 * ## Why this is not a lock, and cannot brick the app
 *
 * A lock blocks: you must acquire it, and a stale lock wedges every later run. This record
 * blocks nothing. `read()` is advisory — a missing, truncated, or garbled file parses to
 * `null` and startup proceeds; [OrphanReaper] deletes it unconditionally before doing
 * anything else, so even a record left behind by a kill mid-write is a one-shot hint that is
 * consumed, not a gate that can fail. The absolute worst case of a lost or ignored record is
 * that an orphaned server is not found and keeps running alongside the new one — a resource
 * leak, never a brick (the new server picks its own free port, see [PortPicker]).
 *
 * Contains **only the port** — never the password (per-plan constraint: the secret is memory
 * only for this slice).
 */
class LaunchRecordStore(private val file: File) {

    fun read(): LaunchRecord? {
        val text = try {
            if (!file.isFile) return null
            file.readText()
        } catch (e: Exception) {
            // Unreadable is treated exactly like absent: advisory data must never fail startup.
            return null
        }
        val port = text.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size != 2 || parts[0].trim() != "port") null
                else parts[1].trim().toIntOrNull()
            }
            .firstOrNull()
            ?: return null
        if (port !in 1..65535) return null
        return LaunchRecord(port)
    }

    /** Atomic-ish: write a sibling temp file, then rename over the target. */
    fun write(record: LaunchRecord) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("port=${record.port}\n")
        if (!tmp.renameTo(file)) {
            // Fallback for filesystems that refuse rename-over; still no partial parse window
            // that matters, because read() tolerates garbage anyway.
            file.delete()
            if (!tmp.renameTo(file)) file.writeText("port=${record.port}\n")
        }
        tmp.delete()
    }

    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}
