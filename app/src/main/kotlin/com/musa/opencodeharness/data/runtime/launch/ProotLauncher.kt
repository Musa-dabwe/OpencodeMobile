package com.musa.opencodeharness.data.runtime.launch

import com.musa.opencodeharness.data.runtime.install.InstallPlanner
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/** start() failed before any process existed. The message never contains the password. */
class ServerLaunchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The one seam between the launcher and a real OS process. Production wraps `java.lang.Process`
 * via [RealProcessSpawner]; tests supply a fake with a scripted exit code and a canned stream.
 */
interface SpawnedProcess {
    val pid: Int

    /** MERGED stdout+stderr (the spawner uses `redirectErrorStream(true)`), read via readLine(). */
    val output: InputStream

    /** Blocks until the process exits; returns the JVM's exit code. */
    fun waitFor(): Int

    /** Bounded wait: true if the process exited within [timeoutMillis]. */
    fun waitFor(timeoutMillis: Long): Boolean

    val isAlive: Boolean

    /** SIGTERM. */
    fun destroy()

    /** SIGKILL — the fallback after [destroy] did not finish within the grace period. */
    fun destroyForcibly()
}

/**
 * Spawns the VERIFIED invocation, unchanged — docs/runtime-artifacts.md and the plan freeze it:
 *
 * ```
 * proot -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys \
 *       /usr/local/bin/opencode serve --hostname 127.0.0.1 --port <port>
 * ```
 *
 * The `-b /dev -b /proc -b /sys` binds are mandatory for the Bun binary (it aborts after
 * ~160 ms without them), and every env var below is mandatory — omitting one fails in a way
 * that LOOKS like a permissions problem. Do not "improve" any of it.
 *
 * ## Environment contract (all six, no exceptions)
 *
 * | Var | Value | Why |
 * | --- | --- | --- |
 * | `PROOT_LOADER` | `<runtimeDir>/proot/proot-loader` | Termux proot hardcodes a Termux loader path |
 * | `LD_LIBRARY_PATH` | `<runtimeDir>/proot` | proot needs libtalloc.so.2 + libandroid-shmem.so |
 * | `PATH` | `/bin:/sbin:/usr/bin:/usr/sbin` | guest must not inherit Android's PATH |
 * | `HOME` | `/root` | guest home |
 * | `TMPDIR` | `<rootfs>/tmp` | must be INSIDE the rootfs |
 * | `OPENCODE_SERVER_PASSWORD` | fresh [ServerSecret] | server auth |
 *
 * The password travels **only** in the environment — never in argv (argv is world-readable
 * via `ps` and is interpolated into `ProcessBuilder.start()` failure messages).
 * The rest of the app's environment is inherited with these six keys overwritten: the verified
 * runs differed from the app sandbox in exactly these keys, and inherited vars like
 * `ANDROID_ROOT` are inert inside the guest.
 *
 * ## Lifecycle (this is the deliverable, not just the spawn)
 *
 *  - Death is OBSERVED: a background thread blocks in `waitFor()` and posts
 *    [ServerEvent.Exited] with the exit code (Unix signal deaths arrive as `128 + signal`,
 *    decoded by [decodeSignal]). "start() returned" is never mistaken for "server is up" —
 *    a Bun binary can abort 160 ms later.
 *  - Resumability: [OrphanReaper.reap()] runs before anything else, the [LaunchRecord] is
 *    written BEFORE the spawn (a live server is never recordless), and the record is cleared
 *    once the exit is observed. The record is advisory — see [LaunchRecordStore] for why it
 *    can never brick the app.
 *  - stdout+stderr are merged and pumped line-by-line into [log] (redacted, bounded).
 *  - [stop] sends SIGTERM, escalates to SIGKILL, then sweeps the port: killing proot can
 *    leave the detached `opencode` process running, so every observed death is followed by
 *    `sweep(port)` — state `Dead` must never coexist with a live listener on that port.
 *
 * ## Resumability after process death — the recovery story in one paragraph
 *
 * If the app is killed, the record file survives and names the port the orphan (if any) still
 * holds. Next `start()`: preflight, then `reap()` consumes the record and kills any process
 * whose cmdline is this server's argv for that port (identity by argv, never by stale pid),
 * then a kernel-chosen ephemeral port is picked — the kernel will not hand out a port that is
 * still bound, so even an unreapable listener cannot be double-booked. Nothing here can block
 * startup: the record is a hint that is deleted unconditionally, and every kill is best-effort.
 */
class ProotLauncher(
    private val runtimeDir: File,
    /** Task 3's path helper — rootfsDir/prootDir come from here, never from string literals. */
    private val planner: InstallPlanner = InstallPlanner(runtimeDir),
    private val spawn: (command: List<String>, env: Map<String, String>) -> SpawnedProcess =
        { command, env -> RealProcessSpawner(command, env) },
    private val portPicker: PortPicker = PortPicker(),
    private val reaper: OrphanReaper = defaultReaper(runtimeDir),
    val log: LogBuffer = LogBuffer(),
    val lifecycle: ServerLifecycle = ServerLifecycle(),
    private val secretFactory: () -> ServerSecret = { ServerSecret.generate() },
) {
    private val recordStore = LaunchRecordStore(File(runtimeDir, RECORD_FILE_NAME))

    private val lock = Any()
    private var current: SpawnedProcess? = null
    private var currentPort: Int? = null

    @Volatile
    private var logReader: Thread? = null

    /**
     * Starts the server. Throws [ServerLaunchException] if the runtime was never installed or
     * the OS refuses the spawn; in every failure path the state ends as
     * `Dead(launchError=...)` so the UI can observe the failure, not just catch it.
     */
    fun start(): ProotHandle = synchronized(lock) {
        val state = lifecycle.state.value
        if (state is ServerState.Running) {
            throw ServerLaunchException("server already running (pid ${state.pid}, port ${state.port})")
        }
        // Deliberate intent: the ONLY event that reopens a Dead state, so a stale event from
        // the previous run can never resurrect it — but a genuine restart can.
        lifecycle.post(ServerEvent.StartRequested)
        preflight()

        // Recovery: consume any previous run's record and kill its orphan before we pick a
        // port (bind(0) then cannot collide with it either way — belt and braces).
        reaper.reap()

        val port = try {
            portPicker.choose()
        } catch (e: Exception) {
            // Observable failure, not just a thrown one: the UI tails state, not catch blocks.
            val message = "no free loopback port: ${e.message}"
            lifecycle.post(ServerEvent.LaunchFailed(message))
            throw ServerLaunchException(message, e)
        }
        val secret = secretFactory()
        log.reset(secret.expose())

        val rootfs = planner.rootfsDir
        // TMPDIR must exist INSIDE the rootfs; the minirootfs ships tmp/ but be defensive.
        File(rootfs, "tmp").mkdirs()
        val command = buildCommand(rootfs, port)
        val env = buildEnv(secret)

        // Record BEFORE spawn: a kill between spawn and record-write would leave a live,
        // unfindable server. A record without a server (kill in the other order) is the safe
        // direction — next run just finds no matching process and clears it.
        recordStore.write(LaunchRecord(port))

        val process = try {
            spawn(command, env)
        } catch (e: Exception) {
            recordStore.clear()
            // Message carries the command's problem (missing binary, EACCES) — argv has no
            // password, and we never interpolate `env`, so the secret cannot reach this text.
            val message = "failed to spawn proot: ${e.message}"
            lifecycle.post(ServerEvent.LaunchFailed(message))
            throw ServerLaunchException(message, e)
        }

        lifecycle.post(ServerEvent.Spawned(process.pid, port))
        current = process
        currentPort = port

        startReader(process)
        startReaper(process, port)

        ProotHandle(port = port, secret = secret, launcher = this)
    }

    /**
     * SIGTERM the child, escalate to SIGKILL after a grace period, then sweep the port so the
     * tracee cannot outlive our declared state. Idempotent; never throws.
     */
    fun stop() {
        val (process, port) = synchronized(lock) {
            val p = current ?: return
            Pair(p, currentPort)
        }
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(STOP_GRACE_MILLIS)) process.destroyForcibly()
            }
        } catch (e: Exception) {
            // Best effort — the reaper thread still observes the exit.
        }
        port?.let { reaper.sweep(it) }
        // The reaper thread clears the record when it observes the exit; clear here too so a
        // stop() that somehow beats it still leaves no record behind.
        try {
            recordStore.clear()
        } catch (e: Exception) {
            // Advisory file; nothing to do.
        }
    }

    /** Paths the plan requires to exist; Task 2/4 ruling: clear message, never a null deref. */
    private fun preflight() {
        val missing = mutableListOf<String>()
        val proot = File(planner.prootDir, "proot")
        val loader = File(planner.prootDir, "proot-loader")
        val opencode = File(planner.rootfsDir, "usr/local/bin/opencode")
        if (!planner.rootfsDir.isDirectory) missing += planner.rootfsDir.absolutePath
        for (file in listOf(proot, loader, opencode)) {
            if (!file.isFile) missing += file.absolutePath
            else if (!file.canExecute()) missing += "${file.absolutePath} (not executable)"
        }
        if (missing.isNotEmpty()) {
            val message =
                "runtime setup has not run (or is incomplete): missing ${missing.joinToString(", ")}. " +
                    "Install the runtime before starting the server."
            // Observable as Dead(launchError) AND thrown: the UI can render either surface.
            lifecycle.post(ServerEvent.LaunchFailed(message))
            throw ServerLaunchException(message)
        }
    }

    private fun buildCommand(rootfs: File, port: Int): List<String> = listOf(
        File(planner.prootDir, "proot").absolutePath,
        "-0",
        "--link2symlink",
        "-r", rootfs.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "/usr/local/bin/opencode",
        "serve",
        "--hostname", "127.0.0.1",
        "--port", port.toString(),
    )

    private fun buildEnv(secret: ServerSecret): Map<String, String> = linkedMapOf(
        "PROOT_LOADER" to File(planner.prootDir, "proot-loader").absolutePath,
        "LD_LIBRARY_PATH" to planner.prootDir.absolutePath,
        "PATH" to "/bin:/sbin:/usr/bin:/usr/sbin",
        "HOME" to "/root",
        "TMPDIR" to File(planner.rootfsDir, "tmp").absolutePath,
        "OPENCODE_SERVER_PASSWORD" to secret.expose(),
    )

    /**
     * Merges lines into [log] with readLine() — never a raw byte read — so a secret that the
     * server echoes can never be split across two appends and dodge redaction half-way.
     */
    private fun startReader(process: SpawnedProcess) {
        val thread = Thread({
            try {
                BufferedReader(InputStreamReader(process.output, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        log.append(line)
                    }
                }
            } catch (e: Exception) {
                // Stream closed at process death — the normal way this loop ends.
            }
        }, "opencode-log-reader").apply { isDaemon = true }
        // Assign BEFORE start() so the death observer can never see a stale reader.
        logReader = thread
        thread.start()
    }

    /**
     * Observes death. Joins the reader first (bounded) so that by the time `Dead` is
     * observable, everything the process printed is already in [log].
     *
     * The publish + sweep + cleanup are gated on `current === process` under the launcher
     * lock. That gate matters two ways: a death observer whose run was already replaced
     * (restart raced with the old observer) must not post an `Exited` that would kill the
     * NEW run's state, must not sweep the NEW run's port, and must not clear the NEW run's
     * record.
     */
    private fun startReaper(process: SpawnedProcess, port: Int) {
        val thread = Thread({
            val exitCode = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            // Drain the reader so `Dead` implies "log complete" (bounded, never hangs).
            logReader?.join(LOG_DRAIN_MILLIS)
            synchronized(lock) {
                if (current === process) {
                    lifecycle.post(ServerEvent.Exited(exitCode))
                    // Once declared dead, nothing may still serve this port: proot can die
                    // while its tracee survives detached, and that listener must go too.
                    reaper.sweep(port)
                    current = null
                    try {
                        recordStore.clear()
                    } catch (e: Exception) {
                        // Advisory file.
                    }
                }
            }
        }, "opencode-death-observer").apply { isDaemon = true }
        thread.start()
    }

    internal companion object {
        const val RECORD_FILE_NAME: String = ".launch-record"
        const val STOP_GRACE_MILLIS: Long = 2_000
        const val LOG_DRAIN_MILLIS: Long = 1_000

        /**
         * Production recovery wiring: read /proc ourselves, SIGKILL victims directly —
         * every readable cmdline is a same-uid process, i.e. ours to kill.
         */
        fun defaultReaper(runtimeDir: File): OrphanReaper = OrphanReaper(
            store = LaunchRecordStore(File(runtimeDir, RECORD_FILE_NAME)),
            readProcCmdlines = { OrphanReaper.readProcCmdlines() },
            kill = { pid -> android.os.Process.killProcess(pid) },
        )
    }
}

/**
 * What [ProotLauncher.start] hands back: the port, the in-memory secret (for building a
 * [com.musa.opencodeharness.data.ServerEndpoint] — Task 5), the log tail, and the death
 * observable. [toString] deliberately prints neither the secret nor anything derived from it.
 */
class ProotHandle internal constructor(
    val port: Int,
    private val secret: ServerSecret,
    private val launcher: ProotLauncher,
) : StartedServer {
    override val baseUrl: String get() = "http://127.0.0.1:$port"

    /** In-memory only; goes to an Authorization header and the server's env, nothing else. */
    override val password: String get() = secret.expose()

    override val log: LogBuffer get() = launcher.log

    /** Collect this to observe Running -> Dead(exitCode, signal). */
    override val state: StateFlow<ServerState> get() = launcher.lifecycle.state

    /**
     * The child's pid, or null when the platform could not report one.
     *
     * `Process.pid()` is a Java 9 API that never made it into android.jar, so [pidOf] resolves
     * it reflectively and returns -1 on every real Android device. Task 4 deferred this as
     * cosmetic; it is now load-bearing, because a UI that renders "pid -1" is lying. Null is
     * the honest answer and recovery never depended on the pid anyway — orphans are identified
     * by argv (see [OrphanReaper]), not by a number read out of a dead process's record.
     */
    fun pidOrNull(): Int? = state.value.let { if (it is ServerState.Running) it.pid.takeIf { p -> p > 0 } else null }

    fun stop() = launcher.stop()

    override fun toString(): String = "ProotHandle(port=$port)"
}

/** Production spawner: `ProcessBuilder` with stderr merged into stdout. */
object RealProcessSpawner {
    operator fun invoke(command: List<String>, env: Map<String, String>): SpawnedProcess {
        val builder = ProcessBuilder(command)
        // One stream: the UI tails a single ordered log, and nothing is lost between pipes.
        builder.redirectErrorStream(true)
        // Inherit the app environment, override the six mandatory keys (see class doc).
        builder.environment().putAll(env)
        val process = builder.start()
        return object : SpawnedProcess {
            override val pid: Int
                get() = pidOf(process)

            override val output: InputStream get() = process.inputStream

            override fun waitFor(): Int = process.waitFor()

            override fun waitFor(timeoutMillis: Long): Boolean =
                try {
                    process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }

            override val isAlive: Boolean get() = process.isAlive

            override fun destroy() = process.destroy()

            override fun destroyForcibly() {
                process.destroyForcibly()
            }
        }
    }
}

/**
 * `Process.pid()` never made it into android.jar's `java.lang.Process` (it is a Java 9 API),
 * so ask reflectively and degrade to -1 when the runtime lacks it. The pid is informational
 * only: recovery identifies processes by argv, never by pid (see [OrphanReaper]).
 */
private fun pidOf(process: Process): Int = try {
    Process::class.java.getMethod("pid").invoke(process) as Int
} catch (e: Exception) {
    -1
}
