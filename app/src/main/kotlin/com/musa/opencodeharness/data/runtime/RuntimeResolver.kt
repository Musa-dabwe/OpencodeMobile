package com.musa.opencodeharness.data.runtime

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Resolution metadata for an npm-distributed artifact, from npm's own records. */
data class NpmResolution(
    val version: String,
    val url: String,
    /** SRI string exactly as published, e.g. `sha512-<base64>`. */
    val integrity: String,
)

/** Resolution failed, or the resolved version violates the declared floor. */
class ResolutionException(message: String) : Exception(message)

/**
 * Produces a concrete download target for an npm artifact. Injectable so tests run offline.
 */
fun interface NpmArtifactResolver {
    suspend fun resolve(source: ArtifactSource.Npm): NpmResolution
}

/** A concrete download target: the URL to fetch and the digest to verify against. */
data class ResolvedArtifact(
    val artifact: Artifact,
    val url: String,
    /** Expected size in bytes when known ahead of time; null for registry-resolved npm. */
    val sizeBytes: Long?,
    val digest: ExpectedDigest,
    /** Opaque token written to the `.trusted` marker after verification. */
    val markerToken: String,
)

/** Parses [source]'s published integrity SRI and checks a file against it. */
sealed interface ExpectedDigest {
    fun matches(file: java.io.File): Boolean
}

/** SHA-256, lowercase hex — the format pinned artifacts use. Unchanged from before. */
data class Sha256Digest(val hex: String) : ExpectedDigest {
    override fun matches(file: java.io.File): Boolean = fileDigestHex("SHA-256", file) == hex
}

/** npm-published SRI integrity string (`sha512-<base64>`). npm's own trust anchor. */
data class Sha512SriDigest(val sri: String) : ExpectedDigest {
    override fun matches(file: java.io.File): Boolean {
        require(sri.startsWith("sha512-")) { "unsupported integrity: $sri" }
        val actualBase64 = java.util.Base64.getEncoder()
            .encodeToString(fileDigestBytes("SHA-512", file))
        return "sha512-$actualBase64" == sri
    }
}

private fun fileDigestBytes(algorithm: String, file: java.io.File): ByteArray {
    val digest = java.security.MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var read = input.read(buffer)
        while (read >= 0) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return digest.digest()
}

private fun fileDigestHex(algorithm: String, file: java.io.File): String =
    fileDigestBytes(algorithm, file).joinToString("") { "%02x".format(it) }

/**
 * Resolves any artifact to a concrete [ResolvedArtifact]. Pinned artifacts resolve locally
 * with no network. Npm artifacts query [resolver] and then enforce the declared floor, so a
 * sub-floor publish (bad 0.x/1.x release) is refused before any bytes are downloaded.
 */
suspend fun resolveArtifact(artifact: Artifact, resolver: NpmArtifactResolver?): ResolvedArtifact =
    when (val source = artifact.source) {
        is ArtifactSource.Pinned -> ResolvedArtifact(
            artifact = artifact,
            url = source.url,
            sizeBytes = source.sizeBytes,
            digest = Sha256Digest(source.sha256),
            markerToken = "${source.sizeBytes} ${source.sha256}",
        )
        is ArtifactSource.Npm -> {
            requireNotNull(resolver) {
                "npm artifact '${artifact.id}' must be resolved: pass an NpmArtifactResolver"
            }
            val resolution = try {
                resolver.resolve(source)
            } catch (e: ResolutionException) {
                throw e
            } catch (e: Exception) {
                throw ResolutionException("resolution failed for ${source.pkg}: ${e.message}")
            }
            if (isBelowVersion(resolution.version, source.minVersion)) {
                throw ResolutionException(
                    "${source.pkg} resolved to ${resolution.version}, below required floor ${source.minVersion}"
                )
            }
            if (!resolution.integrity.startsWith("sha512-")) {
                throw ResolutionException(
                    "${source.pkg}@${resolution.version} published unsupported integrity '${resolution.integrity}'"
                )
            }
            ResolvedArtifact(
                artifact = artifact,
                url = resolution.url,
                sizeBytes = null,
                digest = Sha512SriDigest(resolution.integrity),
                markerToken = resolution.integrity,
            )
        }
    }

/** Numeric-dotted comparison on the pre-release-stripped prefix. Floor "2.0.0" refuses any 1.x/0.x. */
internal fun isBelowVersion(version: String, floor: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(version); val b = parts(floor)
    for (i in 0 until maxOf(a.size, b.size, 3)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

/** Extracts `version` from the OpenCode update-endpoint document. */
internal fun parseUpdateEndpointVersion(text: String): String =
    Json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.content
        ?: throw ResolutionException("update endpoint document has no 'version'")

/** Extracts `dist.tarball` and `dist.integrity` from a registry version document. */
internal fun parseRegistryVersionDoc(text: String): Pair<String, String> {
    val dist = Json.parseToJsonElement(text).jsonObject["dist"]?.jsonObject
        ?: throw ResolutionException("registry document has no 'dist'")
    val tarball = dist["tarball"]?.jsonPrimitive?.content
    val integrity = dist["integrity"]?.jsonPrimitive?.content
    if (tarball == null || integrity == null) {
        throw ResolutionException("registry 'dist' missing 'tarball' or 'integrity'")
    }
    return tarball to integrity
}

/**
 * Production resolver. Reproduces the official v2 installer's source chain exactly:
 * update endpoint -> version -> registry version document -> dist.tarball + dist.integrity.
 * We download whatever npm publishes as latest, subject only to the declared major-version
 * floor. No pinning, no rollback, no backup: blind trust in the npm publish pipeline.
 */
class HttpNpmResolver(
    private val client: HttpClient,
    private val updateEndpoint: String = "https://opencode.ai/update/api/latest/cli/npm",
    private val registryVersionUrl: (pkg: String, version: String) -> String =
        { pkg, version -> "https://registry.npmjs.org/$pkg/$version" },
) : NpmArtifactResolver {
    override suspend fun resolve(source: ArtifactSource.Npm): NpmResolution {
        val version = try {
            parseUpdateEndpointVersion(client.get(updateEndpoint).bodyAsText())
        } catch (e: ResolutionException) {
            throw e
        } catch (e: Exception) {
            throw ResolutionException("version lookup failed: ${e.message}")
        }
        val (tarball, integrity) = try {
            parseRegistryVersionDoc(client.get(registryVersionUrl(source.pkg, version)).bodyAsText())
        } catch (e: ResolutionException) {
            throw e
        } catch (e: Exception) {
            throw ResolutionException("registry metadata lookup failed: ${e.message}")
        }
        return NpmResolution(version = version, url = tarball, integrity = integrity)
    }
}
