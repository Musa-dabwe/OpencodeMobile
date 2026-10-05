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
     *
     * Return-value contract, precisely: true iff [offset] > 0 AND the server honored the
     * Range request, i.e. the bytes written start at [url] byte [offset]. false means the
     * bytes written start at byte 0 — either because [offset] == 0 (the normal case) or
     * because the server ignored the Range request and sent the whole body (caller must
     * discard any partial already on disk). Callers may branch on the value ONLY when
     * offset > 0.
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
    private val resolver: NpmArtifactResolver? = null,
) {
    fun destination(artifact: Artifact): File = File(artifactsDir, artifact.fileName)
    fun partial(artifact: Artifact): File = File(artifactsDir, artifact.fileName + ".part")
    fun trustedMarker(artifact: Artifact): File = File(artifactsDir, artifact.fileName + ".trusted")

    /**
     * True only when a fully-verified artifact is on disk for the current manifest entry.
     *
     * What it proves: "a verified artifact exists for this entry as of the last successful
     * check" — and nothing more. For pinned artifacts that also implies "up to date" (the pin
     * cannot change under a running app). For npm artifacts it does NOT: npm's published
     * integrity may have advanced since the marker was written, and this overload deliberately
     * does not hit the network to find out. If you need "is this npm's latest?", use [ensure]
     * (or resolve + the [isComplete] overload taking a [ResolvedArtifact]) — blind trust means
     * accepting whatever npm published at resolve time, never claiming freshness offline.
     *
     * Deliberate decision: we trust the `.trusted` marker and do NOT re-hash the destination on
     * every check. Re-hashing the 88 MB opencode artifact on every launch is expensive, and the
     * marker is written only after a successful hash check. Consequence: corruption of the
     * destination that happens *after* verification (disk rot, manual tampering) is NOT
     * re-detected on later runs. Callers that need a fresh guarantee may delete the marker to
     * force re-verification.
     */
    fun isComplete(artifact: Artifact): Boolean {
        // Pinned artifacts can be checked locally; npm artifacts must resolve first (their
        // marker token is npm's published integrity, which we do not know offline).
        val source = artifact.source as? ArtifactSource.Pinned ?: return markerPresent(artifact) {
            destination(artifact).isFile
        }
        val dest = destination(artifact)
        val marker = trustedMarker(artifact)
        if (!dest.isFile || dest.length() != source.sizeBytes) return false
        if (!marker.isFile) return false
        return marker.readText().trim() == "${source.sizeBytes} ${source.sha256}"
    }

    /** Like [isComplete(Artifact)], but checked against a resolved target — this is the
     *  one that proves the on-disk bytes match npm's (or the pin's) CURRENT expected digest. */
    fun isComplete(resolved: ResolvedArtifact): Boolean {
        val dest = destination(resolved.artifact)
        val marker = trustedMarker(resolved.artifact)
        if (!dest.isFile) return false
        resolved.sizeBytes?.let { if (dest.length() != it) return false }
        if (!marker.isFile) return false
        return marker.readText().trim() == resolved.markerToken
    }

    private inline fun markerPresent(artifact: Artifact, body: () -> Boolean): Boolean =
        body() && trustedMarker(artifact).isFile

    /**
     * Returns the trusted artifact file, downloading and verifying if needed. Never returns
     * a file whose hash has not been checked against [artifact].
     *
     * Trust freshness: the artifact URL must be HTTPS and the expected SHA-256 must match —
     * non-HTTPS URLs are refused, making the documented "HTTPS + pinned SHA-256" trust root
     * load-bearing rather than advisory.
     */
    suspend fun ensure(artifact: Artifact): File =
        ensure(resolveArtifact(artifact, resolver))

    suspend fun ensure(resolved: ResolvedArtifact): File {
        val artifact = resolved.artifact
        if (!resolved.url.startsWith("https://", ignoreCase = true)) {
            throw ArtifactDownloadException("refusing non-HTTPS artifact URL for ${artifact.id}")
        }
        if (isComplete(resolved)) {
            // A leftover `.part` from a previous kill is never needed once the artifact is
            // trusted; sweep it so it cannot be mistaken for in-progress state.
            partial(artifact).delete()
            return destination(artifact)
        }
        artifactsDir.mkdirs()

        val part = partial(artifact)
        var offset: Long = if (part.isFile) part.length() else 0L
        val expectedSize = resolved.sizeBytes
        if (expectedSize != null && offset > expectedSize) {
            // A `.part` longer than the artifact cannot be valid; discard and restart.
            part.delete()
            offset = 0L
        }

        val resumed = appendInto(resolved, part, offset)
        if (!resumed && offset > 0L) {
            // Server ignored the Range request: the file now holds offset-bytes + whole body.
            // Redo from byte 0. Note on kill-during-fallback: the corrupt `.part` (old partial
            // + appended whole body) exists on disk only between the append above and the
            // delete below. If the process dies in that window, the next run sees a `.part`
            // LONGER than the artifact (offset + full body), discards it via the length check
            // above, and re-downloads from byte 0 — convergent, at the known cost of one
            // duplicated full download on an ~89 MB artifact. Accepted: fixing it would need a
            // second temp file for the redo, doubling peak disk usage for a rare kill window.
            part.delete()
            appendInto(resolved, part, 0L)
        }

        if (expectedSize != null && part.length() != expectedSize) {
            val actual = part.length()
            part.delete()
            throw ArtifactSizeException(
                "${artifact.id}: expected $expectedSize bytes, got $actual"
            )
        }
        if (!resolved.digest.matches(part)) {
            // Hard failure: delete the bad bytes and any marker so nothing trusts them again.
            part.delete()
            trustedMarker(artifact).delete()
            throw ArtifactIntegrityException("${artifact.id}: integrity mismatch")
        }

        val dest = destination(artifact)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        trustedMarker(artifact).writeText(resolved.markerToken)
        return dest
    }

    private suspend fun appendInto(resolved: ResolvedArtifact, part: File, offset: Long): Boolean =
        FileOutputStream(part, offset > 0L).use { out ->
            fetcher.stream(resolved.url, offset, out)
        }
}
