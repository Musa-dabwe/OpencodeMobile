package com.musa.opencodeharness.data.runtime.install

import com.musa.opencodeharness.data.runtime.Artifact
import com.musa.opencodeharness.data.runtime.ArtifactSource
import com.musa.opencodeharness.data.runtime.Payload
import com.musa.opencodeharness.data.runtime.ResolvedArtifact
import com.musa.opencodeharness.data.runtime.UnpackMode
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ExtractionException(message: String) : RuntimeException(message)

/** DNS resolvers the findings require; without resolv.conf, DNS inside proot fails. */
const val RESOLV_CONF = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"

/**
 * Turns the verified downloaded containers into an on-disk runtime. Idempotent and
 * resumable: every artifact installs via a staging area and a completion marker written
 * ONLY after verification succeeds. A kill mid-install leaves either the old complete
 * state or no marker — never a silently half-installed artifact.
 *
 * Verification policy:
 * - PINNED artifact + LIFT_MEMBERS: each lifted member's SHA-256 + size must equal its
 *   [Payload]. The payloads are the only per-member data we trust, because we pinned it.
 * - NPM artifact + LIFT_MEMBERS: no per-member hashing — the tarball's npm sha512 already
 *   covered every byte, and per-member values drift each release. Instead the SUM of the
 *   sizes of ALL tar members (package.json included, per npm's definition of unpackedSize)
 *   must equal `ResolvedArtifact.unpackedSize`. A truncated/partial extraction fails this.
 * - UNPACK_TREE: the container itself was checked by the downloader; extraction preserves
 *   the tree, and the marker means the WHOLE tree made it.
 */
class RuntimeInstaller(
    private val runtimeDir: File,
    private val artifactsDir: File,
    private val planner: InstallPlanner,
    private val resolve: suspend (Artifact) -> ResolvedArtifact,
) {
    val rootfsDir: File get() = planner.rootfsDir
    private val markersDir: File get() = File(runtimeDir, ".markers")

    fun isComplete(artifactId: String): Boolean {
        val marker = File(markersDir, "$artifactId.ok")
        if (!marker.isFile) return false
        return when (val plan = try { planner.plan(artifactId) } catch (e: Exception) { return false }) {
            else -> (plan.treeDest?.let { it.isDirectory && File(it, "etc/resolv.conf").isFile }
                ?: plan.members.all { it.destFile.isFile })
        }
    }

    suspend fun install(artifact: Artifact) {
        val plan = planner.plan(artifact.id)
        if (isComplete(artifact.id)) return
        markersDir.mkdirs()
        val staging = File(runtimeDir, ".staging-${artifact.id}")
        staging.deleteRecursively()
        try {
            val container = File(artifactsDir, artifact.fileName)
            require(container.isFile) { "missing downloaded artifact ${container.name}" }
            when (artifact.unpackMode) {
                UnpackMode.UNPACK_TREE -> installTree(container, staging, plan)
                UnpackMode.LIFT_MEMBERS -> installMembers(artifact, container, staging, plan)
            }
            // Success only now: marker last, so a marker implies full install.
            File(markersDir, "${artifact.id}.ok").writeText("ok\n")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun installTree(container: File, staging: File, plan: InstallPlan) {
        require(plan.treeDest != null)
        openPayloadTar(container).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name
                require(!name.startsWith("/")) { "absolute path in archive: $name" }
                val dest = File(staging, name)
                val canonicalStaging = staging.canonicalPath + File.separator
                require(dest.canonicalPath == staging.canonicalPath || dest.canonicalPath.startsWith(canonicalStaging)) {
                    "path traversal in archive member: $name"
                }
                when {
                    entry.isDirectory -> dest.mkdirs()
                    entry.isSymbolicLink -> {
                        dest.parentFile?.mkdirs()
                        dest.delete()
                        try {
                            java.nio.file.Files.createSymbolicLink(
                                dest.toPath(), java.nio.file.Paths.get(entry.linkName)
                            )
                        } catch (e: UnsupportedOperationException) {
                            // Filesystem without symlink support: fall back to materialising the
                            // link target IF that target also unpacks in this archive.
                            val target = File(staging, entry.linkName.removePrefix("./"))
                            if (target.isFile) target.copyTo(dest, overwrite = true)
                            else throw ExtractionException("symlink ${entry.name} -> ${entry.linkName} unsupported and target not in archive")
                        }
                    }
                    entry.isFile -> {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out -> tar.copyTo(out) }
                        if ((entry.mode and 0b001_000_000) != 0) dest.setExecutable(true, false)
                        dest.setReadable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
        }
        // Minirootfs ships no resolv.conf; DNS inside proot breaks without it.
        val etc = File(staging, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(RESOLV_CONF)
        // Converge: only a complete staging tree gets the final name.
        val final = plan.treeDest!!
        if (final.exists()) final.deleteRecursively()
        if (!staging.renameTo(final)) {
            staging.copyRecursively(final, overwrite = true)
            staging.deleteRecursively()
        }
    }

    private suspend fun installMembers(artifact: Artifact, container: File, staging: File, plan: InstallPlan) {
        staging.mkdirs()
        val wanted = plan.members.associateBy { it.memberPath }
        val extractedSizes = HashMap<String, Long>()
        var totalMembersBytes = 0L
        val stagedFiles = HashMap<String, File>()

        openPayloadTar(container).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (entry.isDirectory) { entry = tar.nextEntry; continue }
                if (entry.isSymbolicLink) { entry = tar.nextEntry; continue }
                // Sum over ALL members — the npm check needs the whole package, and for
                // pinned LIFT_MEMBERS artifacts this is just informational.
                // Manifest member paths for debs carry a './' prefix; tar entries may or may
                // not, so match on both forms.
                val member: MemberInstall? = wanted[entry.name]
                    ?: wanted[entry.name.removePrefix("./")]
                    ?: wanted["./" + entry.name]
                val out: File? = member?.let { File(staging, it.destFile.name).also { f -> f.parentFile?.mkdirs() } }
                var written = 0L
                if (out != null) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(out).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var n = tar.read(buf)
                        while (n > 0) {
                            os.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            n = tar.read(buf)
                        }
                    }
                    extractedSizes[member!!.memberPath] = written
                    stagedFiles[member.memberPath] = out
                    verifyMember(artifact, member, written, digest.digest())
                } else {
                    // Still count bytes read from this member so the npm total is exact.
                    val buf = ByteArray(64 * 1024)
                    var n = tar.read(buf)
                    while (n > 0) { written += n; n = tar.read(buf) }
                }
                totalMembersBytes += written
                entry = tar.nextEntry
            }
        }

        when (val source = artifact.source) {
            is ArtifactSource.Pinned -> { /* per-member hash+size already checked in verifyMember */ }
            is ArtifactSource.Npm -> {
                val resolved = resolve(artifact)
                if (resolved.unpackedSize != totalMembersBytes) {
                    throw ExtractionException(
                        "extraction size mismatch for ${artifact.id}: unpacked $totalMembersBytes, npm says ${resolved.unpackedSize}"
                    )
                }
            }
        }

        // Every wanted member must have been found — a renamed member path in a new upstream
        // release would otherwise "succeed" installing nothing.
        for (member in plan.members) {
            val staged = stagedFiles[member.memberPath]
                ?: throw ExtractionException("member ${member.memberPath} not found in ${artifact.fileName}")
            member.destFile.parentFile?.mkdirs()
            staged.copyTo(member.destFile, overwrite = true)
            if (member.executable) member.destFile.setExecutable(true, false)
            member.destFile.setReadable(true, false)
        }
    }

    private fun verifyMember(artifact: Artifact, member: MemberInstall, size: Long, sha: ByteArray) {
        if (artifact.source !is ArtifactSource.Pinned) return
        val expected = artifact.payloads.firstOrNull { it.name == member.destFile.name }
            ?: throw ExtractionException("no Payload pin for member ${member.destFile.name}")
        if (expected.sizeBytes != size) {
            throw ExtractionException("${member.destFile.name}: size $size != expected ${expected.sizeBytes}")
        }
        val hex = sha.joinToString("") { "%02x".format(it) }
        if (expected.sha256 != hex) {
            throw ExtractionException("${member.destFile.name}: sha256 mismatch")
        }
    }
}
