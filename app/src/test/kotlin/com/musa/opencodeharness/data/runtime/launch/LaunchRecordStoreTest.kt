package com.musa.opencodeharness.data.runtime.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchRecordStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): Pair<LaunchRecordStore, File> {
        val file = File(tmp.root, ".launch-record")
        return LaunchRecordStore(file) to file
    }

    @Test
    fun writeThenReadRoundTripsThePort() {
        val (store, _) = store()
        store.write(LaunchRecord(45997))
        assertEquals(LaunchRecord(45997), store.read())
    }

    @Test
    fun overwriteReplacesThePreviousPort() {
        val (store, _) = store()
        store.write(LaunchRecord(40000))
        store.write(LaunchRecord(40001))
        assertEquals(LaunchRecord(40001), store.read())
    }

    @Test
    fun missingFileReadsAsNull() {
        val (store, _) = store()
        assertNull(store.read())
    }

    @Test
    fun garbageNeverThrowsAndReadsAsNull() {
        // The anti-lock property: a garbled record must degrade to "no record", never an
        // exception that would fail startup. Mutation: read() without the tolerant parse
        // (e.g. `text.substringAfter("port=").toInt()`) throws or mis-parses here.
        val (store, file) = store()
        val cases = listOf(
            "",
            "not a record at all",
            "port=",
            "port=abc",
            "port=0",
            "port=70000",
            "port=-5",
            "pid=1234", // an older format must not parse
            "port",
        )
        for (garbage in cases) {
            file.writeText(garbage)
            assertNull("did not read as null: '$garbage'", store.read())
        }
    }

    @Test
    fun truncatedTempFileDoesNotBreakRead() {
        // A kill during write() can leave the sibling .tmp behind; read() must not care.
        val (store, file) = store()
        File(file.parentFile, file.name + ".tmp").writeText("port=4")
        assertNull(store.read())
        store.write(LaunchRecord(41000))
        assertEquals(LaunchRecord(41000), store.read())
    }

    @Test
    fun clearRemovesTheRecordAndIsIdempotent() {
        val (store, file) = store()
        store.write(LaunchRecord(42000))
        assertTrue(file.isFile)
        store.clear()
        assertFalse(file.isFile)
        assertNull(store.read())
        store.clear() // second clear must not throw
        assertNull(store.read())
    }

    @Test
    fun clearAlsoRemovesALeftoverTempFile() {
        val (store, file) = store()
        val tmpFile = File(file.parentFile, file.name + ".tmp")
        tmpFile.writeText("port=4")
        store.clear()
        assertFalse("stale temp file survived clear()", tmpFile.exists())
    }

    @Test
    fun recordContainsOnlyThePortNeverASecret() {
        // The plan allows the password in memory only. If anyone ever adds a field, this
        // fails: whatever they add must not be present in the on-disk bytes as a second
        // `key=value`, and specifically no 32-hex-char value may appear.
        val (store, file) = store()
        store.write(LaunchRecord(45997))
        val text = file.readText()
        assertEquals("port=45997\n", text)
        assertFalse(Regex("[0-9a-f]{32}").containsMatchIn(text))
    }
}
