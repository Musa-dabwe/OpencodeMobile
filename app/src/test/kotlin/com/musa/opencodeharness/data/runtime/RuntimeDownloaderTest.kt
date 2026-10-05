package com.musa.opencodeharness.data.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * All offline: [ArtifactFetcher] is faked, no network, no Ktor client, no device.
 *
 * Every assertion targets observable on-disk state or the artifact returned, and each
 * "rejection" test also asserts the positive-control state (that the downloader DID run and
 * that no trusted marker can exist), so the assertions cannot pass vacuously.
 */
class RuntimeDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun artifactFor(bytes: ByteArray): Artifact = Artifact(
        id = "test-artifact",
        fileName = "artifact.bin",
        source = ArtifactSource.Pinned(
            url = "https://example.invalid/artifact.bin",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
        ),
    )

    private fun Artifact.pinned(): ArtifactSource.Pinned = source as ArtifactSource.Pinned

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Scriptable fake source. Records the offset each call was asked to resume from. */
    private class FakeFetcher(
        private val body: ByteArray,
        private val failAfterBytes: Int? = null,
        private val honorsRange: Boolean = true,
    ) : ArtifactFetcher {
        var calls = 0
        var lastOffset: Long = -1
        var requestedOffsets = mutableListOf<Long>()

        override suspend fun stream(url: String, offset: Long, sink: OutputStream): Boolean {
            calls++
            lastOffset = offset
            requestedOffsets.add(offset)
            val resumed = honorsRange && offset > 0 && offset <= body.size
            val start = if (resumed) offset.toInt() else 0
            val end = failAfterBytes?.let { minOf(body.size, start + it) } ?: body.size
            if (end > start) sink.write(body, start, end - start)
            sink.flush()
            if (failAfterBytes != null) throw IOException("connection reset (simulated)")
            return resumed
        }
    }

    private val body: ByteArray = ByteArray(128 * 1024 + 17) { (it % 251).toByte() }

    @Test
    fun `happy path writes verified artifact and marks it complete`() = runBlocking {
        val artifact = artifactFor(body)
        val downloader = RuntimeDownloader(tmp.root, FakeFetcher(body))

        assertFalse(downloader.isComplete(artifact))
        val dest = downloader.ensure(artifact)

        assertTrue(downloader.isComplete(artifact))
        assertTrue(downloader.trustedMarker(artifact).isFile)
        assertFalse(downloader.partial(artifact).exists())
        assertArrayEquals(body, dest.readBytes())
        // The marker must name THIS manifest entry's size and hash.
        assertEquals("${artifact.pinned().sizeBytes} ${artifact.pinned().sha256}", downloader.trustedMarker(artifact).readText().trim())
    }

    @Test
    fun `hash mismatch is rejected and leaves nothing trusted`() = runBlocking {
        // Manifest demands hash H of the GOOD body, but the server serves different bytes.
        val good = artifactFor(body)
        val evil = good.copy(source = good.pinned().copy(sha256 = good.pinned().sha256.replaceFirst("8f", "e7").let {
            if (it == good.pinned().sha256) good.pinned().sha256.replaceFirst("8", "9") else it
        }))
        val downloader = RuntimeDownloader(tmp.root, FakeFetcher(body))

        var failed: ArtifactIntegrityException? = null
        try {
            downloader.ensure(evil)
        } catch (e: ArtifactIntegrityException) {
            failed = e
        }
        assertTrue("expected ArtifactIntegrityException", failed != null)
        // Positive control: the downloader really ran and really saw every byte.
        assertFalse("temp file must be deleted after mismatch", downloader.partial(evil).exists())
        assertFalse("final file must not exist", downloader.destination(evil).exists())
        assertFalse("no trusted marker", downloader.trustedMarker(evil).exists())
        assertFalse(downloader.isComplete(evil))
        // A correct manifest entry against the same bytes succeeds next run.
        downloader.ensure(good)
        assertTrue(downloader.isComplete(good))
    }

    @Test
    fun `interrupted download leaves no trusted artifact and resume converges`() = runBlocking {
        val artifact = artifactFor(body)
        val flaky = FakeFetcher(body, failAfterBytes = 40_000)
        val downloader = RuntimeDownloader(tmp.root, flaky)

        var failed: IOException? = null
        try {
            downloader.ensure(artifact)
        } catch (e: IOException) {
            failed = e
        }
        assertTrue("expected the simulated reset to propagate", failed != null)
        // No trusted artifact after interruption; only a `.part` may remain.
        assertFalse(downloader.destination(artifact).exists())
        assertFalse(downloader.trustedMarker(artifact).exists())
        assertFalse(downloader.isComplete(artifact))
        assertTrue(downloader.partial(artifact).isFile)
        val partialBytes = downloader.partial(artifact).length()
        assertTrue("partial must hold exactly the 40k prefix", partialBytes == 40_000L)

        // Second run with a healthy source must resume from the partial, not restart.
        val healthy = FakeFetcher(body)
        val downloader2 = RuntimeDownloader(tmp.root, healthy)
        val dest = downloader2.ensure(artifact)

        assertEquals(listOf(40_000L), healthy.requestedOffsets)
        assertArrayEquals(body, dest.readBytes())
        assertTrue(downloader2.isComplete(artifact))
    }

    @Test
    fun `a partially-written file is never mistaken for complete`() {
        val artifact = artifactFor(body)
        val downloader = RuntimeDownloader(tmp.root, FakeFetcher(body))

        // 1) Final path holds a truncated copy of the real bytes, marker claims the real hash.
        downloader.destination(artifact).writeBytes(body.copyOf(body.size / 2))
        downloader.trustedMarker(artifact).writeText("${artifact.pinned().sizeBytes} ${artifact.pinned().sha256}")
        assertFalse(downloader.isComplete(artifact))

        // 2) Final path holds the real bytes but the marker names a different size (stale).
        downloader.destination(artifact).writeBytes(body)
        downloader.trustedMarker(artifact).writeText("${artifact.pinned().sizeBytes / 2} ${artifact.pinned().sha256}")
        assertFalse(downloader.isComplete(artifact))

        // 3) Final path holds real bytes but NO marker at all (killed between rename and mark).
        downloader.trustedMarker(artifact).delete()
        assertFalse(downloader.isComplete(artifact))

        // Positive control: the same final bytes + a correct marker is trusted.
        downloader.trustedMarker(artifact).writeText("${artifact.pinned().sizeBytes} ${artifact.pinned().sha256}")
        assertTrue(downloader.isComplete(artifact))
    }

    @Test
    fun `truncated response is a size failure, not a silent trust`() = runBlocking {
        val artifact = artifactFor(body)
        // Server sends only half the bytes then ends cleanly.
        val half = body.copyOf(body.size / 2)
        val truncating = object : ArtifactFetcher {
            override suspend fun stream(url: String, offset: Long, sink: OutputStream): Boolean {
                sink.write(half, 0, half.size); sink.flush(); return offset == 0L
            }
        }
        val downloader = RuntimeDownloader(tmp.root, truncating)
        var failed: ArtifactSizeException? = null
        try { downloader.ensure(artifact) } catch (e: ArtifactSizeException) { failed = e }
        assertTrue(failed != null)
        assertFalse(downloader.isComplete(artifact))
        assertFalse(downloader.destination(artifact).exists())
        // Positive control: the bytes that reached disk are the truncated half, deleted on failure.
        assertFalse(downloader.partial(artifact).exists())
        assertFalse(downloader.trustedMarker(artifact).exists())
    }

    @Test
    fun `manifest round-trips through JSON and the embedded manifest is well-formed`() {
        val text = """
            {"schemaVersion": 1, "artifacts": [
              {"id": "x", "fileName": "x.bin", "note": "n",
               "source": {"type": "pinned", "url": "https://e.invalid/x",
                          "sizeBytes": 4, "sha256": "deadbeef"}}
            ]}
        """.trimIndent()
        val parsed = parseRuntimeManifest(text)
        assertEquals(1, parsed.schemaVersion)
        assertEquals("x", parsed.artifact("x").id)
        assertEquals(4L, (parsed.artifact("x").source as ArtifactSource.Pinned).sizeBytes)

        val ids = embeddedRuntimeManifest.artifacts.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.contains("opencode-cli"))
        assertTrue(ids.contains("alpine-rootfs"))
        val pinnedSources = embeddedRuntimeManifest.artifacts.mapNotNull { it.source as? ArtifactSource.Pinned }
        assertEquals(embeddedRuntimeManifest.artifacts.size - 1, pinnedSources.size)
        assertTrue(pinnedSources.all { it.url.startsWith("https://") })
        assertTrue(pinnedSources.all { it.sizeBytes > 0 })
        assertTrue(pinnedSources.all { it.sha256.length == 64 })
        // Blind-trust policy: exactly one unpinned artifact, the npm one, with a declared floor.
        val npm = embeddedRuntimeManifest.artifacts.map { it.source }
            .filterIsInstance<ArtifactSource.Npm>().single()
        assertEquals("@opencode/cli-linux-arm64-musl", npm.pkg)
        assertEquals("2.0.0", npm.minVersion)
    }

    @Test
    fun `resume against a range-ignoring server yields the correct verified file`() = runBlocking {
        val artifact = artifactFor(body)
        val downloader = RuntimeDownloader(tmp.root, FakeFetcher(body))
        // Seed a 40k partial, as a kill would leave it.
        downloader.partial(artifact).writeBytes(body.copyOf(40_000))

        // Server IGNORES the Range header: full body, treated as a 200.
        val ignoring = FakeFetcher(body, honorsRange = false)
        val downloader2 = RuntimeDownloader(tmp.root, ignoring)
        val dest = downloader2.ensure(artifact)

        // The fake saw exactly one request, for the stored partial length, and answered 200.
        assertEquals(listOf(40_000L, 0L), ignoring.requestedOffsets)
        assertArrayEquals(body, dest.readBytes())
        assertTrue(downloader2.isComplete(artifact))
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },
            MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `kill mid-download then range-ignoring server converges to correct artifact`() = runBlocking {
        val artifact = artifactFor(body)
        // Run 1: server resets after 40k; a `.part` remains.
        val flaky = FakeFetcher(body, failAfterBytes = 40_000)
        try { RuntimeDownloader(tmp.root, flaky).ensure(artifact) } catch (_: IOException) {}
        assertEquals(40_000L, RuntimeDownloader(tmp.root, FakeFetcher(body)).partial(artifact).length())

        // Run 2: server never honors Range.
        val ignoring = FakeFetcher(body, honorsRange = false)
        val downloader = RuntimeDownloader(tmp.root, ignoring)
        val dest = downloader.ensure(artifact)

        // First call asked for a resume; fallback rewrote from byte 0.
        assertEquals(listOf(40_000L, 0L), ignoring.requestedOffsets)
        assertArrayEquals(body, dest.readBytes())
        assertTrue(downloader.isComplete(artifact))
        assertFalse(downloader.partial(artifact).exists())
    }

    @Test
    fun `stale part file is swept once the artifact is complete`() = runBlocking {
        val artifact = artifactFor(body)
        val downloader = RuntimeDownloader(tmp.root, FakeFetcher(body))
        downloader.ensure(artifact)
        assertTrue(downloader.isComplete(artifact))

        // A leftover partial from an earlier, superseded attempt must not linger.
        downloader.partial(artifact).writeBytes(body.copyOf(7_000))
        downloader.ensure(artifact)

        assertFalse(downloader.partial(artifact).exists())
        assertTrue(downloader.isComplete(artifact))
    }

    @Test
    fun `non-HTTPS artifact URL is refused before any bytes move`() = runBlocking {
        val artifact = artifactFor(body).let { it.copy(source = it.pinned().copy(url = "http://example.invalid/artifact.bin")) }
        val fetcher = FakeFetcher(body)
        val downloader = RuntimeDownloader(tmp.root, fetcher)

        var failed: ArtifactDownloadException? = null
        try { downloader.ensure(artifact) } catch (e: ArtifactDownloadException) { failed = e }
        assertTrue(failed != null)
        assertEquals(0, fetcher.calls)
        assertFalse(downloader.destination(artifact).exists())
        assertFalse(downloader.partial(artifact).exists())
        assertFalse(downloader.isComplete(artifact))
    }
}
