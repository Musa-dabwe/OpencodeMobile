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
import java.util.Base64

/**
 * Resolution-chain tests. The npm layer is a fake resolver; no network, no Ktor client.
 * Each assertion pins a concrete value the resolver produced (URL, integrity, version) so a
 * swapped-in different behaviour cannot pass.
 */
class RuntimeResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val npmArtifact = Artifact(
        id = "opencode-cli",
        fileName = "opencode-cli-linux-arm64-musl.tgz",
        source = ArtifactSource.Npm(pkg = "@opencode/cli-linux-arm64-musl", minVersion = "2.0.0"),
    )

    private val body: ByteArray = ByteArray(64 * 1024 + 23) { (it * 31 % 253).toByte() }

    private fun sriOf(bytes: ByteArray): String =
        "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))

    private class FakeResolver(
        private val version: String = "2.0.22",
        private val integrity: String = "",
        private val resolution: NpmResolution? = null,
        private val failure: Exception? = null,
    ) : NpmArtifactResolver {
        var calls = 0
        override suspend fun resolve(source: ArtifactSource.Npm): NpmResolution {
            calls++
            failure?.let { throw it }
            return resolution ?: NpmResolution(
                version = version,
                url = "https://registry.npmjs.org/${source.pkg}/-/cli-linux-arm64-musl-$version.tgz",
                integrity = integrity,
                unpackedSize = 199_167_429L,
            )
        }
    }

    private class FakeFetcher(private val body: ByteArray) : ArtifactFetcher {
        var calls = 0
        var lastUrl: String? = null
        override suspend fun stream(url: String, offset: Long, sink: OutputStream): Boolean {
            calls++
            lastUrl = url
            // Contract: true iff offset > 0 AND the server honored the Range header; false
            // means the bytes written start at byte 0. This fake only ever serves whole-body
            // requests, so it writes from byte 0 and reports false — honouring `offset` here
            // would contradict the return value. Tests here only pass offset 0.
            sink.write(body)
            sink.flush()
            return false
        }
    }

    @Test
    fun `version below the declared floor is refused before any download`() = runBlocking {
        val resolver = FakeResolver(version = "1.18.34", integrity = sriOf(body))
        val fetcher = FakeFetcher(body)
        val downloader = RuntimeDownloader(tmp.root, fetcher, resolver)

        var failed: ResolutionException? = null
        try { downloader.ensure(npmArtifact) } catch (e: ResolutionException) { failed = e }
        assertTrue("expected ResolutionException", failed != null)
        assertTrue(
            "message must name both versions, got: ${failed!!.message}",
            failed!!.message!!.contains("1.18.34") && failed!!.message!!.contains("2.0.0"),
        )
        assertEquals(0, fetcher.calls)
        assertFalse(downloader.destination(npmArtifact).exists())
    }

    @Test
    fun `resolution yields the registry URL and integrity, and the download is verified against them`() = runBlocking {
        val expectedUrl = "https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.22.tgz"
        val expectedSri = sriOf(body)
        val resolver = FakeResolver(resolution = NpmResolution("2.0.22", expectedUrl, expectedSri, 199_167_429L))
        val fetcher = FakeFetcher(body)
        val downloader = RuntimeDownloader(tmp.root, fetcher, resolver)

        val dest = downloader.ensure(npmArtifact)

        assertEquals(1, fetcher.calls)
        assertEquals(1, resolver.calls)
        assertArrayEquals(body, dest.readBytes())
        assertEquals(expectedSri, downloader.trustedMarker(npmArtifact).readText().trim())
        assertTrue("marker-matching file must verify against npm's published SRI", Sha512SriDigest(expectedSri).matches(dest))
        // Resolution must have handed the downloader exactly the tarball URL npm published.
        assertEquals(expectedUrl, fetcher.lastUrl)
    }

    @Test
    fun `tarball bytes that do not match npm's published integrity are rejected`() = runBlocking {
        val resolver = FakeResolver(resolution = NpmResolution(
            version = "2.0.22",
            url = "https://registry.npmjs.org/pkg/-/artifact-2.0.22.tgz",
            integrity = sriOf(ByteArray(4) { 9 }), // published for DIFFERENT bytes
            unpackedSize = 199_167_429L,
        ))
        val fetcher = FakeFetcher(body)
        val downloader = RuntimeDownloader(tmp.root, fetcher, resolver)

        var failed: ArtifactIntegrityException? = null
        try { downloader.ensure(npmArtifact) } catch (e: ArtifactIntegrityException) { failed = e }
        assertTrue("expected ArtifactIntegrityException", failed != null)
        assertFalse("final file must not exist", downloader.destination(npmArtifact).exists())
        assertFalse("no trusted marker", downloader.trustedMarker(npmArtifact).exists())
    }

    @Test
    fun `pinned artifact still verifies against pinned SHA-256 even alongside SRI support`() = runBlocking {
        val pinned = Artifact(
            id = "alpine-rootfs",
            fileName = "alpine.tar.gz",
            source = ArtifactSource.Pinned(
                url = "https://example.invalid/alpine.tar.gz",
                sizeBytes = body.size.toLong(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(body)
                    .joinToString("") { "%02x".format(it) },
            ),
        )
        val fetcher = FakeFetcher(body)
        val downloader = RuntimeDownloader(tmp.root, fetcher, FakeResolver())

        val dest = downloader.ensure(pinned)
        assertArrayEquals(body, dest.readBytes())
        val expectedMarker = "${pinned.source.let { (it as ArtifactSource.Pinned).sizeBytes }} ${(pinned.source as ArtifactSource.Pinned).sha256}"
        assertEquals(expectedMarker, downloader.trustedMarker(pinned).readText().trim())
        // And a wrong pinned hash is still a hard failure (not silently downgraded to SRI).
        val evil = pinned.copy(source = (pinned.source as ArtifactSource.Pinned).copy(
            sha256 = "00" + (pinned.source as ArtifactSource.Pinned).sha256.drop(2)))
        var failed: ArtifactIntegrityException? = null
        try { RuntimeDownloader(tmp.root.resolve("evil").also { it.mkdirs() }, fetcher, FakeResolver()).ensure(evil) }
        catch (e: ArtifactIntegrityException) { failed = e }
        assertTrue(failed != null)
    }

    @Test
    fun `resolution failure surfaces as a clear exception and downloads nothing`() = runBlocking {
        val resolver = FakeResolver(failure = ResolutionException("registry returned 503"))
        val fetcher = FakeFetcher(body)
        val downloader = RuntimeDownloader(tmp.root, fetcher, resolver)

        var failed: ResolutionException? = null
        try { downloader.ensure(npmArtifact) } catch (e: ResolutionException) { failed = e }
        assertTrue(failed != null)
        assertEquals("registry returned 503", failed!!.message)
        assertEquals(1, resolver.calls)
        assertEquals(0, fetcher.calls)
        assertFalse(downloader.destination(npmArtifact).exists())
        assertFalse(downloader.partial(npmArtifact).exists())
        assertFalse(downloader.trustedMarker(npmArtifact).exists())
    }

    @Test
    fun `cancellation during resolution propagates instead of becoming ResolutionException`() = runBlocking {
        val resolver = FakeResolver(failure = kotlinx.coroutines.CancellationException("job cancelled"))
        val fetcher = FakeFetcher(body)
        val downloader = RuntimeDownloader(tmp.root, fetcher, resolver)
        var observed: Throwable? = null
        try { downloader.ensure(npmArtifact) } catch (t: Throwable) { observed = t }
        assertTrue(
            "CancellationException must propagate, got ${observed?.javaClass?.name}: ${observed?.message}",
            observed is kotlinx.coroutines.CancellationException,
        )
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun `parsers extract exactly what npm publishes`() {
        val updateDoc = """{"channel":"latest","name":"cli","version":"2.0.22","active":true}"""
        assertEquals("2.0.22", parseUpdateEndpointVersion(updateDoc))

        val registryDoc = """{"name":"@opencode/cli-linux-arm64-musl","version":"2.0.23",
            "dist":{"tarball":"https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.23.tgz",
                    "integrity":"sha512-AAA==","shasum":"bb","unpackedSize":198380997}}"""
        val doc = parseRegistryVersionDoc(registryDoc)
        assertEquals("https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.23.tgz", doc.tarball)
        assertEquals("sha512-AAA==", doc.integrity)
        assertEquals(198380997L, doc.unpackedSize)
    }

    @Test
    fun `npm resolution carries npm's published unpackedSize into the resolved artifact`() = runBlocking {
        val resolver = FakeResolver(resolution = NpmResolution(
            version = "2.0.23",
            url = "https://registry.npmjs.org/pkg/-/cli-2.0.23.tgz",
            integrity = sriOf(body),
            unpackedSize = 198_380_997L,
        ))
        val resolved = resolveArtifact(npmArtifact, resolver)
        assertEquals(198_380_997L, resolved.unpackedSize)
        // Pinned artifacts resolve with null, not a bogus zero/empty value.
        val pinnedArtifact = Artifact(
            id = "p", fileName = "p.bin",
            source = ArtifactSource.Pinned("https://e.invalid/p", body.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }),
        )
        assertEquals(null, resolveArtifact(pinnedArtifact, resolver).unpackedSize)
    }

    @Test
    fun `npm payloads list is empty by policy while every pinned payload keeps hash and size`() {
        // Empty by policy: summing payload sizes would compare the one listed member
        // against npm's all-members total and fail on every install.
        assertEquals(0, embeddedRuntimeManifest.artifact("opencode-cli").payloads.size)

        // Regression guard: every payload of every pinned artifact keeps its hash and size.
        val pinnedArtifacts = embeddedRuntimeManifest.artifacts
            .filter { it.source is ArtifactSource.Pinned }
        assertTrue(pinnedArtifacts.isNotEmpty())
        val pinnedPayloads = pinnedArtifacts.flatMap { it.payloads }
        assertTrue(pinnedPayloads.isNotEmpty())
        assertTrue(
            "a pinned payload lost its hash or size: ${pinnedPayloads.filter { it.sha256 == null || it.sizeBytes == null }}",
            pinnedPayloads.all { it.sha256 != null && it.sizeBytes != null && it.sha256.length == 64 },
        )
    }

    @Test
    fun `registry document missing or non-numeric unpackedSize is a hard failure`() {
        val missing = """{"dist":{"tarball":"https://e.invalid/x.tgz","integrity":"sha512-AAA=="}}"""
        val nonNumeric = """{"dist":{"tarball":"https://e.invalid/x.tgz","integrity":"sha512-AAA==","unpackedSize":"big"}}"""
        var missingFailed: ResolutionException? = null
        try { parseRegistryVersionDoc(missing) } catch (e: ResolutionException) { missingFailed = e }
        assertTrue(missingFailed?.message?.contains("unpackedSize") == true)
        var nonNumericFailed: ResolutionException? = null
        try { parseRegistryVersionDoc(nonNumeric) } catch (e: ResolutionException) { nonNumericFailed = e }
        assertTrue(nonNumericFailed?.message?.contains("unpackedSize") == true)
    }

    @Test
    fun `isBelowVersion treats the floor as inclusive and major lines as breaking`() {
        assertTrue(isBelowVersion("1.18.34", "2.0.0"))
        assertTrue(isBelowVersion("0.9.0", "2.0.0"))
        // Pre-release suffix is ignored in the comparison; a bare "2.0.0" is not below the floor.
        assertFalse(isBelowVersion("2.0.0-beta.1", "2.0.0"))
        assertFalse(isBelowVersion("2.0.0", "2.0.0"))
        assertFalse(isBelowVersion("2.0.22", "2.0.0"))
        assertFalse(isBelowVersion("3.0.0", "2.0.0"))
    }
}
