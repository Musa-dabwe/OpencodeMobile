package com.musa.opencodeharness.data.runtime.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrphanReaperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prootCmdline =
        "/data/user/0/com.musa.opencodeharness/files/runtime/proot/proot -0 --link2symlink " +
            "-r /data/user/0/com.musa.opencodeharness/files/runtime/rootfs " +
            "-b /dev -b /proc -b /sys /usr/local/bin/opencode serve " +
            "--hostname 127.0.0.1 --port 45997"

    private val traceeCmdline =
        "/data/user/0/com.musa.opencodeharness/files/runtime/rootfs/usr/local/bin/opencode " +
            "serve --hostname 127.0.0.1 --port 45997"

    // ---- matchesInstance: identity by argv, the core of the recovery story ----

    @Test
    fun matchesProotAndTraceeArgvForOurPort() {
        assertTrue(OrphanReaper.matchesInstance(prootCmdline, 45997))
        assertTrue(OrphanReaper.matchesInstance(traceeCmdline, 45997))
    }

    @Test
    fun rejectsADifferentPort() {
        // Mutation: dropping the `tokens[i + 1] == port.toString()` check fails both.
        assertFalse(OrphanReaper.matchesInstance(prootCmdline, 45998))
        assertFalse(OrphanReaper.matchesInstance(traceeCmdline, 40000))
    }

    @Test
    fun rejectsAPrefixedPortNumber() {
        // Port 4599 must not match `--port 45997` (string-prefix containment bug).
        assertFalse(OrphanReaper.matchesInstance(prootCmdline, 4599))
        assertFalse(OrphanReaper.matchesInstance(prootCmdline, 459970))
    }

    @Test
    fun rejectsOurOwnPackageNameWithoutAPortArgument() {
        // The app process cmdline looks like this — it contains "opencode" and must still
        // never match, or start() would kill the app itself.
        assertFalse(
            OrphanReaper.matchesInstance("com.musa.opencodeharness", 45997),
        )
        assertFalse(
            OrphanReaper.matchesInstance("/system/bin/app_process64 com.musa.opencodeharness", 45997),
        )
    }

    @Test
    fun rejectsPortWithoutOpencodeAndOpencodeWithoutPort() {
        assertFalse(OrphanReaper.matchesInstance("python3 -m http.server --port 45997", 45997))
        assertFalse(OrphanReaper.matchesInstance("/usr/local/bin/opencode --version", 45997))
        assertFalse(OrphanReaper.matchesInstance("", 45997))
    }

    // ---- reap: record consumed, victims killed, never throws ----

    private fun reaper(
        record: LaunchRecord?,
        cmdlines: MutableMap<Int, String>,
        killed: MutableList<Int>,
        killThrows: Boolean = false,
        readerThrows: Boolean = false,
    ): OrphanReaper {
        val store = LaunchRecordStore(File(tmp.root, ".launch-record"))
        if (record != null) store.write(record)
        return OrphanReaper(
            store = store,
            readProcCmdlines = {
                if (readerThrows) throw SecurityException("proc restricted")
                HashMap(cmdlines)
            },
            kill = { pid ->
                killed.add(pid)
                if (killThrows) throw SecurityException("cannot kill")
                cmdlines.remove(pid)
            },
            waitMillis = { /* no real sleeping in tests */ },
        )
    }

    @Test
    fun reapsRecordAndKillsTheMatchingOrphan() {
        val cmdlines = mutableMapOf(4242 to prootCmdline, 4243 to traceeCmdline, 7777 to "unrelated")
        val killed = mutableListOf<Int>()
        val store = LaunchRecordStore(File(tmp.root, ".launch-record"))
        store.write(LaunchRecord(45997))
        val subject = OrphanReaper(
            store = store,
            readProcCmdlines = { HashMap(cmdlines) },
            kill = { pid -> killed.add(pid); cmdlines.remove(pid) },
            waitMillis = {},
        )
        val count = subject.reap()
        assertEquals(setOf(4242, 4243), killed.toSet())
        assertEquals(2, count)
        assertEquals("unrelated must not be killed", 1, cmdlines.size)
        assertNull("record must be consumed", store.read())
    }

    @Test
    fun staleRecordWithNoMatchingProcessStillClearsAndNeverThrows() {
        val killed = mutableListOf<Int>()
        val subject = reaper(LaunchRecord(45997), mutableMapOf(9999 to "some other app"), killed)
        assertEquals(0, subject.reap())
        assertTrue(killed.isEmpty())
        assertNull(LaunchRecordStore(File(tmp.root, ".launch-record")).read())
    }

    @Test
    fun noRecordMeansNoKillAndNoThrow() {
        val killed = mutableListOf<Int>()
        val subject = reaper(null, mutableMapOf(4242 to prootCmdline), killed)
        assertEquals(0, subject.reap())
        assertTrue("without a record nothing may be killed", killed.isEmpty())
    }

    @Test
    fun failingKillAndFailingProcReaderNeverPropagate() {
        val cmdlines = mutableMapOf(4242 to prootCmdline)
        val killed = mutableListOf<Int>()
        val throwing = reaper(LaunchRecord(45997), cmdlines, killed, killThrows = true)
        assertEquals("the kill attempt still counts as one victim found", 1, throwing.reap())
        assertTrue("the kill was attempted despite throwing", killed.isNotEmpty())
        assertNull(LaunchRecordStore(File(tmp.root, ".launch-record")).read())

        val store = LaunchRecordStore(File(tmp.root, ".launch-record"))
        store.write(LaunchRecord(45997))
        val restricted = OrphanReaper(
            store = store,
            readProcCmdlines = { throw SecurityException("restricted") },
            kill = { fail("must not kill when /proc is unreadable") },
            waitMillis = {},
        )
        assertEquals(0, restricted.reap())
        assertNull("record consumed even when /proc is unreadable", store.read())
    }

    @Test
    fun reapPollsUntilTheVictimIsGone() {
        // The poll loop exists so a detached tracee that lingers a beat gets killed too.
        // Mutation: removing the survivor poll makes `waitCalls` stay 0.
        val cmdlines = mutableMapOf(4242 to traceeCmdline)
        var waitCalls = 0
        val store = LaunchRecordStore(File(tmp.root, ".launch-record"))
        store.write(LaunchRecord(45997))
        val subject = OrphanReaper(
            store = store,
            readProcCmdlines = { HashMap(cmdlines) },
            kill = { pid -> cmdlines.remove(pid) },
            waitMillis = { waitCalls++ },
        )
        subject.reap()
        assertTrue("reap should poll at least once for survivors", waitCalls >= 1)
    }

    @Test
    fun sweepKillsMatchingProcessWithoutAnyRecord() {
        val cmdlines = mutableMapOf(4242 to traceeCmdline)
        val killed = mutableListOf<Int>()
        val subject = OrphanReaper(
            store = LaunchRecordStore(File(tmp.root, ".launch-record")),
            readProcCmdlines = { HashMap(cmdlines) },
            kill = { pid -> killed.add(pid); cmdlines.remove(pid) },
            waitMillis = {},
        )
        assertEquals(1, subject.sweep(45997))
        assertEquals(listOf(4242), killed)
        // And a sweep for a port nobody holds is a harmless no-op.
        assertEquals(0, subject.sweep(40000))
    }
}
