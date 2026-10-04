package com.musa.opencodeharness.data.runtime

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** The server refused or truncated the body. */
class ArtifactDownloadException(message: String) : IOException(message)

/** Downloaded bytes do not match the manifest's expected SHA-256. Hard failure by design. */
class ArtifactIntegrityException(message: String) : IOException(message)

class ArtifactSizeException(message: String) : IOException(message)

/**
 * Abstraction over the byte source so the downloader is unit-testable without network.
 * The production implementation is [HttpArtifactFetcher]; tests use fakes.
 */
fun interface ArtifactFetcher {
    /**
     * Streams [url]'s body into [sink], resuming after [offset] already-downloaded bytes when
     * [offset] > 0. Returns true when the server honored the range (body starts at [offset]);
     * false when it sent the full body from byte 0. Throws on transport failure.
     */
    suspend fun stream(url: String, offset: Long, sink: OutputStream): Boolean
}

class HttpArtifactFetcher(private val client: HttpClient) : ArtifactFetcher {
    override suspend fun stream(url: String, offset: Long, sink: OutputStream): Boolean {
        val response: HttpResponse = client.get(url) {
            if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
        }
        if (!response.status.isSuccess()) {
            throw ArtifactDownloadException("HTTP ${response.status.value} fetching ${redact(url)}")
        }
        val resumed = offset > 0 && response.status == HttpStatusCode.PartialContent
        response.bodyAsChannel().toInputStream().use { input ->
            input.copyTo(sink, bufferSize = 64 * 1024)
        }
        sink.flush()
        return resumed
    }

    // Artifact URLs carry no userinfo, but redact query strings structurally rather than
    // by accident; the generated server password never appears in this layer by design.
    private fun redact(url: String): String = url.substringBefore('?')
}

/**
 * Downloads runtime artifacts into [artifactsDir], verifying SHA-256 before anything may use
 * the file, and converging safely after process death or a mid-stream failure.
 *
 * Layout per artifact `Foo`:
 * - `Foo` — the trusted artifact. Only present after the download finished AND the hash matched.
 * - `Foo.part` — in-flight bytes. May exist after an interruption; never trusted.
 * - `Foo.trusted` — a two-field marker (`<sizeBytes> <sha256>`) written only after the hash
 *   check passed. Presence of `Foo` alone is not enough: a kill between rename and marker
 *   write leaves no marker, so the file is treated as incomplete and re-fetched.
 *
 * Strategy (temp-file-vs-verify): we chose **temp file + rename + marker**, and resume from
 * the temp file. Rationale: with an ~89 MB artifact a kill is routine; resume reuses the
 * bytes already fetched instead of restarting on a metered connection. The temp file's bytes
 * are NOT trusted upfront — after the final append we hash the complete `.part`, so any
 * corruption (wrong URL, truncated write, tampering by another process) is caught before the
 * file ever reaches the trusted name. A hash mismatch is a hard failure: `.part` is deleted
 * and any stale marker is removed so a later run cannot pick the bad bytes up again.
 */
class RuntimeDownloader(
    private val artifactsDir: File,
    private val fetcher: ArtifactFetcher,
) {
    fun destination(artifact: Artifact): File = File(artifactsDir, artifact.fileName)
    fun partial(artifact: Artifact): File = File(artifactsDir, artifact.fileName + ".part")
    fun trustedMarker(artifact: Artifact): File = File(artifactsDir, artifact.fileName + ".trusted")

    /** True only when a fully-verified artifact is on disk for the current manifest entry. */
    fun isComplete(artifact: Artifact): Boolean {
        val dest = destination(artifact)
        val marker = trustedMarker(artifact)
        if (!dest.isFile || dest.length() != artifact.sizeBytes) return false
        if (!marker.isFile) return false
        return marker.readText().trim() == "${artifact.sizeBytes} ${artifact.sha256}"
    }

    /**
     * Returns the trusted artifact file, downloading and verifying if needed. Never returns
     * a file whose hash has not been checked against [artifact].
     */
    suspend fun ensure(artifact: Artifact): File {
        if (isComplete(artifact)) return destination(artifact)
        artifactsDir.mkdirs()

        val part = partial(artifact)
        var offset: Long = if (part.isFile) part.length() else 0L
        if (offset > artifact.sizeBytes) {
            // A `.part` longer than the artifact cannot be valid; discard and restart.
            part.delete()
            offset = 0L
        }

        val resumed = appendInto(artifact, part, offset)
        if (!resumed && offset > 0L) {
            // Server ignored the Range request: the file now holds offset-bytes + whole body.
            // Redo from byte 0.
            part.delete()
            appendInto(artifact, part, 0L)
        }

        if (part.length() != artifact.sizeBytes) {
            part.delete()
            throw ArtifactSizeException(
                "${artifact.id}: expected ${artifact.sizeBytes} bytes, got ${part.length().coerceAtLeast(0)}"
            )
        }
        val actualSha = sha256Hex(part)
        if (actualSha != artifact.sha256) {
            // Hard failure: delete the bad bytes and any marker so nothing trusts them again.
            part.delete()
            trustedMarker(artifact).delete()
            throw ArtifactIntegrityException("${artifact.id}: sha256 mismatch")
        }

        val dest = destination(artifact)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        trustedMarker(artifact).writeText("${artifact.sizeBytes} ${artifact.sha256}")
        return dest
    }

    private suspend fun appendInto(artifact: Artifact, part: File, offset: Long): Boolean =
        FileOutputStream(part, offset > 0L).use { out ->
            fetcher.stream(artifact.url, offset, out)
        }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
