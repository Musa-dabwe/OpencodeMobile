package com.musa.opencodeharness.data.runtime.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rolling, bounded, in-memory tail of the server's stdout+stderr, for the UI to display
 * (spec §2.2: "Redirect output to a log file the UI can tail" — we keep it in memory instead
 * of on disk because nothing needs the log to outlive the process, and a disk log is one more
 * file that could rot or be read after the secret's owner is gone).
 *
 * Three guarantees, all load-bearing:
 *
 *  1. **Bounded.** At most [maxLines] lines, each at most [maxLineChars] characters, so the
 *     buffer cannot grow without bound no matter how chatty the server is. The oldest lines
 *     are evicted first (rolling tail).
 *  2. **Secrets never enter.** Every line is passed through [redact] before storage: any
 *     registered secret is replaced with [REDACTED]. The server receives the password via
 *     env (never argv), so it should never echo one — this is deliberate belt-and-braces for
 *     "should never". Lines arrive via `readLine()`, so a secret can never be split across
 *     two appends and sneak in half-redacted.
 *  3. **Thread-safe.** Appends come from the log reader thread, reads from the UI thread.
 *
 * [revision] increments on every mutation so a collector can re-read [snapshot] cheaply
 * without the buffer itself being a Compose state holder.
 */
class LogBuffer(
    val maxLines: Int = DEFAULT_MAX_LINES,
    val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
) {
    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxLineChars > 0) { "maxLineChars must be positive" }
    }

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val secrets = mutableListOf<String>()

    private val _revision = MutableStateFlow(0L)

    /** Monotonic; changes whenever lines or redaction targets change. */
    val revision: StateFlow<Long> = _revision

    /**
     * Replaces the redaction targets and clears the buffered lines. Called once per server
     * start: the previous run's secret is dead with its server, and its lines are stale.
     */
    fun reset(vararg newSecrets: String) {
        synchronized(lock) {
            lines.clear()
            secrets.clear()
            newSecrets.filter { it.isNotEmpty() }.forEach { secrets.add(it) }
        }
        _revision.value = _revision.value + 1
    }

    /** Appends one line: redacted, truncated to [maxLineChars], evicting the oldest if full. */
    fun append(raw: String) {
        val line = redact(raw)
        synchronized(lock) {
            // Truncate BEFORE storing so a single pathological line cannot blow the cap.
            lines.addLast(if (line.length > maxLineChars) line.substring(0, maxLineChars) else line)
            while (lines.size > maxLines) lines.removeFirst()
        }
        _revision.value = _revision.value + 1
    }

    /** A point-in-time copy of the buffered lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** The whole tail as one string, for a plain text UI. */
    fun render(): String = synchronized(lock) { lines.joinToString("\n") }

    /** Replaces every registered secret with [REDACTED]. Exposed for direct testing. */
    fun redact(text: String): String {
        val targets = synchronized(lock) { secrets.toList() }
        var out = text
        for (secret in targets) out = out.replace(secret, REDACTED)
        return out
    }

    companion object {
        /** 1000 lines x 2048 chars ≈ 2 MB worst case — the hard memory ceiling. */
        const val DEFAULT_MAX_LINES: Int = 1000

        const val DEFAULT_MAX_LINE_CHARS: Int = 2048

        const val REDACTED: String = "***redacted***"
    }
}
