package com.musa.opencodeharness.data.runtime.launch

import com.musa.opencodeharness.data.runtime.install.InstallPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests the launcher through its injected seams — fake process, fake port, fake /proc — so
 * argv/env construction, death observation, record lifecycle, log capture and recovery run
 * for real without a device. Each test names the mutation that must break it.
 */
class ProotLauncherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var planner: InstallPlanner
    private lateinit var recordFile: File

    private val fixedPort = 41234

    @Before
    fun setUp() {
        runtimeDir = File(tmp.root, "runtime")
        planner = InstallPlanner(runtimeDir)
        recordFile = File(runtimeDir, ProotLauncher.RECORD_FILE_NAME)
    }

    // ---- fixtures ----

    private class FakeProcess(
        override val pid: Int = 101,
        outputText: String = "",
    ) : SpawnedProcess {
        override val output: InputStream = ByteArrayInputStream(outputText.toByteArray())
        private val exited = CountDownLatch(1)

        @Volatile private var code = 0
        @Volatile var destroyCalls = 0
        @Volatile var destroyForciblyCalls = 0

        fun exit(value: Int) {
            code = value
            exited.countDown()
        }

        override fun waitFor(): Int {
            exited.await()
            return code
        }

        override fun waitFor(timeoutMillis: Long): Boolean =
            exited.await(timeoutMillis, TimeUnit.MILLISECONDS)

        override val isAlive: Boolean get() = exited.count > 0

        override fun destroy() {
            destroyCalls++
            exit(143)
        }

        override fun destroyForcibly() {
            destroyForciblyCalls++
            exit(137)
        }
    }

    private fun installRuntime() {
        File(planner.rootfsDir, "usr/local/bin").mkdirs()
        File(planner.rootfsDir, "usr/local/bin/opencode").apply {
            writeText("fake-opencode")
            setExecutable(true)
        }
        File(planner.rootfsDir, "tmp").mkdirs()
        planner.prootDir.mkdirs()
        File(planner.prootDir, "proot").apply {
            writeText("fake-proot")
            setExecutable(true)
        }
        File(planner.prootDir, "proot-loader").apply {
            writeText("fake-loader")
            setExecutable(true)
        }
    }

    private fun cmdlineFor(port: Int) =
        "${planner.prootDir.absolutePath}/proot -0 --link2symlink -r ${planner.rootfsDir.absolutePath} " +
            "-b /dev -b /proc -b /sys /usr/local/bin/opencode serve " +
            "--hostname 127.0.0.1 --port $port"

    private fun buildLauncher(
        spawn: (command: List<String>, env: Map<String, String>) -> SpawnedProcess,
        procCmdlines: MutableMap<Int, String> = mutableMapOf(),
        killed: MutableList<Int> = mutableListOf(),
        onProcRead: () -> Unit = {},
    ): ProotLauncher {
        val store = LaunchRecordStore(recordFile)
        // stop()'s sweep and the death-observer's sweep can race; one lock keeps the fake
        // /proc consistent, and assertions compare DISTINCT kills for the same reason.
        val mapLock = Any()
        return ProotLauncher(
            runtimeDir = runtimeDir,
            spawn = spawn,
            portPicker = PortPicker(binder = { fixedPort }),
            reaper = OrphanReaper(
                store = store,
                readProcCmdlines = {
                    onProcRead()
                    synchronized(mapLock) { HashMap(procCmdlines) }
                },
                kill = { pid ->
                    synchronized(mapLock) {
                        killed.add(pid)
                        procCmdlines.remove(pid)
                    }
                },
                waitMillis = { /* no real sleeping in tests */ },
            ),
        )
    }

    private fun awaitState(
        launcher: ProotLauncher,
        timeoutMs: Long = 5_000,
        predicate: (ServerState) -> Boolean,
    ): ServerState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = launcher.lifecycle.state.value
            if (predicate(state)) return state
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for state; current=${launcher.lifecycle.state.value}")
    }

    private fun awaitFileGone(file: File, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!file.exists()) return
            Thread.sleep(5)
        }
        throw AssertionError("file never removed: $file")
    }

    // ---- argv / env: the frozen invocation ----

    @Test
    fun spawnReceivesTheExactVerifiedInvocationAndSixMandatoryEnvVars() {
        installRuntime()
        var capturedCommand: List<String>? = null
        var capturedEnv: Map<String, String>? = null
        val launcher = buildLauncher(spawn = { command, env ->
            capturedCommand = command
            capturedEnv = env
            FakeProcess()
        })

        val handle = launcher.start()

        assertNotNull("spawn must have been called", capturedCommand)
        val command = capturedCommand!!
        assertEquals(
            listOf(
                File(planner.prootDir, "proot").absolutePath,
                "-0",
                "--link2symlink",
                "-r", planner.rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "/usr/local/bin/opencode",
                "serve",
                "--hostname", "127.0.0.1",
                "--port", fixedPort.toString(),
            ),
            command,
        )

        val env = capturedEnv!!
        assertEquals(6, env.size)
        assertEquals(File(planner.prootDir, "proot-loader").absolutePath, env["PROOT_LOADER"])
        assertEquals(planner.prootDir.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals("/bin:/sbin:/usr/bin:/usr/sbin", env["PATH"])
        assertEquals("/root", env["HOME"])
        assertEquals(
            "TMPDIR must be inside the rootfs",
            File(planner.rootfsDir, "tmp").absolutePath,
            env["TMPDIR"],
        )
        assertTrue(env.getValue("TMPDIR").startsWith(planner.rootfsDir.absolutePath))

        // Password: in the env, matching the handle, and absolutely nowhere near argv.
        val password = env.getValue("OPENCODE_SERVER_PASSWORD")
        assertEquals(handle.password, password)
        assertTrue(password.isNotBlank())
        for (arg in command) {
            assertFalse("password leaked into argv: $arg", arg.contains(password))
        }
    }

    // ---- preflight: Task 2/4 ruling — clear message, never a null deref ----

    @Test
    fun missingRuntimeFailsWithClearMessageAndNeverSpawns() {
        var spawnCalls = 0
        val launcher = buildLauncher(spawn = { _, _ ->
            spawnCalls++
            FakeProcess()
        })
        try {
            launcher.start()
            fail("expected ServerLaunchException")
        } catch (e: ServerLaunchException) {
            assertTrue(e.message!!.contains("runtime setup has not run"))
            assertTrue("message must name the missing path", e.message!!.contains("proot"))
            assertTrue(e.message!!.contains("Install the runtime"))
        }
        assertEquals("must not spawn without a runtime", 0, spawnCalls)
        val state = launcher.lifecycle.state.value
        assertTrue("failure must be observable, current=$state", state is ServerState.Dead)
        assertTrue((state as ServerState.Dead).launchError!!.contains("runtime setup has not run"))
    }

    @Test
    fun nonExecutableBinaryIsRejectedWithAnActionableMessage() {
        installRuntime()
        File(planner.rootfsDir, "usr/local/bin/opencode").setExecutable(false)
        var spawnCalls = 0
        val launcher = buildLauncher(spawn = { _, _ ->
            spawnCalls++
            FakeProcess()
        })
        try {
            launcher.start()
            fail("expected ServerLaunchException")
        } catch (e: ServerLaunchException) {
            // The classic symptom on device is a bare "Permission denied" from exec; this
            // preflight turns it into a message that names the file and the cause.
            assertTrue(e.message!!.contains("not executable"))
            assertTrue(e.message!!.contains("opencode"))
        }
        assertEquals(0, spawnCalls)
    }

    // ---- death observation ----

    @Test
    fun exitIsObservedWithCodeAndDecodedSignal() {
        installRuntime()
        lateinit var fake: FakeProcess
        val launcher = buildLauncher(spawn = { _, _ ->
            FakeProcess(pid = 101).also { fake = it }
        })

        val handle = launcher.start()
        assertEquals("start() must synchronously reach Running", ServerState.Running(101, fixedPort), handle.state.value)

        fake.exit(137)
        val dead = awaitState(launcher) { it is ServerState.Dead }
        assertEquals(137, (dead as ServerState.Dead).exitCode)
        assertEquals(9, dead.signal)
        awaitFileGone(recordFile)
    }

    @Test
    fun cleanExitObservesCodeZeroWithoutSignal() {
        installRuntime()
        lateinit var fake: FakeProcess
        val launcher = buildLauncher(spawn = { _, _ -> FakeProcess().also { fake = it } })
        launcher.start()
        fake.exit(0)
        val dead = awaitState(launcher) { it is ServerState.Dead }
        assertEquals(0, (dead as ServerState.Dead).exitCode)
        assertNull(dead.signal)
    }

    @Test
    fun startCanBeRepeatedAfterDeath() {
        installRuntime()
        val fakes = mutableListOf<FakeProcess>()
        val launcher = buildLauncher(spawn = { _, _ -> FakeProcess().also { fakes.add(it) } })
        launcher.start()
        fakes[0].exit(0)
        awaitState(launcher) { it is ServerState.Dead }

        launcher.start() // Dead is terminal for the previous run only
        assertEquals(ServerState.Running(101, fixedPort), launcher.lifecycle.state.value)
        assertEquals(2, fakes.size)
        launcher.stop()
    }

    // ---- record lifecycle: written before spawn, cleared on death ----

    @Test
    fun recordExistsAtSpawnTimeSoALiveServerIsNeverRecordless() {
        installRuntime()
        var recordAtSpawn: String? = null
        val launcher = buildLauncher(spawn = { _, _ ->
            recordAtSpawn = if (recordFile.isFile) recordFile.readText() else null
            FakeProcess()
        })
        launcher.start()
        assertNotNull("record must be written before the process exists", recordAtSpawn)
        assertTrue(recordAtSpawn!!.contains(fixedPort.toString()))
    }

    @Test
    fun spawnFailureEndsDeadClearsRecordAndNeverEmbedsThePassword() {
        installRuntime()
        var capturedEnv: Map<String, String>? = null
        val launcher = buildLauncher(spawn = { _, env ->
            capturedEnv = env
            throw IOException("permission denied")
        })
        try {
            launcher.start()
            fail("expected ServerLaunchException")
        } catch (e: ServerLaunchException) {
            assertTrue(e.message!!.contains("permission denied"))
            val password = capturedEnv!!.getValue("OPENCODE_SERVER_PASSWORD")
            assertFalse("password must never reach an exception message", e.message!!.contains(password))
        }
        val state = launcher.lifecycle.state.value as ServerState.Dead
        assertTrue(state.launchError!!.contains("permission denied"))
        assertFalse("failed spawn must not leave a record", recordFile.exists())
    }

    // ---- orphan recovery: reap runs before spawn ----

    @Test
    fun previousRunOrphanIsReapedBeforeTheNewProcessSpawns() {
        installRuntime()
        LaunchRecordStore(recordFile).write(LaunchRecord(40000))
        val procCmdlines = mutableMapOf(4242 to cmdlineFor(40000))
        val killed = mutableListOf<Int>()
        val order = mutableListOf<String>()
        val launcher = buildLauncher(
            spawn = { _, _ ->
                order.add("spawn")
                FakeProcess()
            },
            procCmdlines = procCmdlines,
            killed = killed,
            onProcRead = { order.add("reap-read") },
        )
        launcher.start()
        assertTrue("reap must happen before spawn", order.indexOf("reap-read") < order.indexOf("spawn"))
        assertEquals("orphan with the recorded port must be killed", listOf(4242), killed)
    }

    // ---- log capture ----

    @Test
    fun stdoutStderrAreTailedAndThePasswordIsRedactedOutOfTheBuffer() {
        installRuntime()
        var capturedEnv: Map<String, String>? = null
        lateinit var fake: FakeProcess
        val launcher = buildLauncher(spawn = { _, env ->
            capturedEnv = env
            val password = env.getValue("OPENCODE_SERVER_PASSWORD")
            FakeProcess(
                outputText = "server listening on http://127.0.0.1:$fixedPort\n" +
                    "DEBUG auth token=$password\n" +
                    "second line\n",
            ).also { fake = it }
        })
        val handle = launcher.start()
        fake.exit(0)
        awaitState(launcher) { it is ServerState.Dead } // reader is joined before Dead posts

        val rendered = handle.log.render()
        val password = capturedEnv!!.getValue("OPENCODE_SERVER_PASSWORD")
        assertFalse("password leaked into the log buffer", rendered.contains(password))
        assertTrue(rendered.contains(LogBuffer.REDACTED))
        assertTrue(rendered.contains("server listening on http://127.0.0.1:$fixedPort"))
        assertTrue(rendered.contains("second line"))
    }

    @Test
    fun handleToStringNeverContainsThePassword() {
        installRuntime()
        val launcher = buildLauncher(spawn = { _, _ -> FakeProcess() })
        val handle = launcher.start()
        assertFalse(handle.toString().contains(handle.password))
        assertTrue(handle.toString().contains(fixedPort.toString()))
        launcher.stop()
    }

    // ---- stop() ----

    @Test
    fun stopTerminatesTheProcessSweepsTheTraceeAndObservesDeath() {
        installRuntime()
        val procCmdlines = mutableMapOf(4243 to cmdlineFor(fixedPort))
        val killed = mutableListOf<Int>()
        lateinit var fake: FakeProcess
        val launcher = buildLauncher(
            spawn = { _, _ -> FakeProcess().also { fake = it } },
            procCmdlines = procCmdlines,
            killed = killed,
        )
        launcher.start()
        launcher.stop()
        assertEquals("SIGTERM first", 1, fake.destroyCalls)
        val dead = awaitState(launcher) { it is ServerState.Dead }
        assertEquals(143, (dead as ServerState.Dead).exitCode)
        assertEquals(15, dead.signal)
        assertEquals("stop() must sweep the tracee", setOf(4243), killed.toSet())
        awaitFileGone(recordFile)
    }

    @Test
    fun secondStartWhileRunningIsRejectedAndDoesNotDoubleSpawn() {
        installRuntime()
        var spawnCalls = 0
        val launcher = buildLauncher(spawn = { _, _ ->
            spawnCalls++
            FakeProcess()
        })
        launcher.start()
        try {
            launcher.start()
            fail("expected ServerLaunchException")
        } catch (e: ServerLaunchException) {
            assertTrue(e.message!!.contains("already running"))
        }
        assertEquals(1, spawnCalls)
        launcher.stop()
    }

    // ---- port plumbing ----

    @Test
    fun chosenPortFromPickerFlowsIntoCommandRecordAndBaseUrl() {
        installRuntime()
        var capturedCommand: List<String>? = null
        val launcher = buildLauncher(spawn = { command, _ ->
            capturedCommand = command
            FakeProcess()
        })
        val handle = launcher.start()
        assertEquals("http://127.0.0.1:$fixedPort", handle.baseUrl)
        assertEquals(fixedPort, handle.port)
        assertEquals(fixedPort.toString(), capturedCommand!!.last())
        assertNotEquals(0, handle.port)
        launcher.stop()
    }
}
