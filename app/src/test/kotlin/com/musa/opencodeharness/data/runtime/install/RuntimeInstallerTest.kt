package com.musa.opencodeharness.data.runtime.install

import com.musa.opencodeharness.data.runtime.Artifact
import com.musa.opencodeharness.data.runtime.ArtifactSource
import com.musa.opencodeharness.data.runtime.InstallMember
import com.musa.opencodeharness.data.runtime.Payload
import com.musa.opencodeharness.data.runtime.ResolvedArtifact
import com.musa.opencodeharness.data.runtime.Sha256Digest
import com.musa.opencodeharness.data.runtime.Sha512SriDigest
import com.musa.opencodeharness.data.runtime.UnpackMode
import com.musa.opencodeharness.data.runtime.embeddedRuntimeManifest
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Builds small synthetic containers — no network, no real artifacts. */
class RuntimeInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tarGz(
        file: File,
        members: List<Pair<String, ByteArray>>,
        symlinks: List<Pair<String, String>> = emptyList(),
        /** Write symlinks before members: needed when a member path traverses a planted link. */
        symlinksFirst: Boolean = false,
    ) {
        TarArchiveOutputStream(GZIPOutputStream(FileOutputStream(file))).use { tar ->
            fun writeMembers() {
                for ((name, data) in members) {
                    val e = TarArchiveEntry(name)
                    e.size = data.size.toLong()
                    e.mode = 0b111_101_101 // rwxr-xr-x
                    tar.putArchiveEntry(e)
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
            }
            fun writeSymlinks() {
                for ((name, target) in symlinks) {
                    val e = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                    e.linkName = target
                    tar.putArchiveEntry(e)
                    tar.closeArchiveEntry()
                }
            }
            if (symlinksFirst) { writeSymlinks(); writeMembers() } else { writeMembers(); writeSymlinks() }
        }
    }

    /**
     * Writes a gzip tar with byte-exact member names. Unlike [tarGz], this bypasses
     * `TarArchiveEntry(String)`, whose `normalizeFileName` strips leading `/` — so a
     * hostile absolute member ("/etc/passwd") actually reaches the reader. Production's
     * `TarArchiveInputStream` does NOT normalise on read (verified against
     * commons-compress 1.26.2), so this is what a real hostile archive looks like.
     */
    private fun tarGzRaw(file: File, members: List<Pair<String, ByteArray>>) {
        GZIPOutputStream(FileOutputStream(file)).use { gz ->
            for ((name, data) in members) {
                val header = ByteArray(512)
                val nb = name.toByteArray(Charsets.US_ASCII)
                check(nb.size <= 100) { "name too long for ustar: $name" }
                nb.copyInto(header, 0)
                "0000777 ".toByteArray(Charsets.US_ASCII).copyInto(header, 100)
                "0000000 ".toByteArray(Charsets.US_ASCII).copyInto(header, 108)
                "0000000 ".toByteArray(Charsets.US_ASCII).copyInto(header, 116)
                "%011o ".format(data.size).toByteArray(Charsets.US_ASCII).copyInto(header, 124)
                "00000000000 ".toByteArray(Charsets.US_ASCII).copyInto(header, 136)
                header[156] = '0'.code.toByte()
                "ustar  \u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
                var sum = 0
                for (i in 0 until 512) sum += if (i in 148 until 156) 32 else header[i].toInt() and 0xFF
                "%06o\u0000 ".format(sum).toByteArray(Charsets.US_ASCII).copyInto(header, 148)
                gz.write(header)
                gz.write(data)
                val pad = (512 - data.size % 512) % 512
                if (pad > 0) gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024))
        }
    }

    /** ar archive: debian-binary + control.tar.xz + data.tar.xz (like a real .deb). */
    private fun deb(file: File, dataTarXz: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
            fun member(name: String, payload: ByteArray) {
                val header = (name.padEnd(16) + "0           " + "0     " + "0     " +
                    "100644  " + payload.size.toString().padStart(10) + "`\n").toByteArray(Charsets.US_ASCII)
                check(header.size == 60) { header.size }
                out.write(header)
                out.write(payload)
                if (payload.size % 2 == 1) out.write(0x0A)
            }
            member("debian-binary", "2.0\n".toByteArray())
            val emptyTarXz = ByteArrayOutputStream().use { bos ->
                XZCompressorOutputStream(bos).use { xz ->
                    TarArchiveOutputStream(xz).use { tar -> tar.finish() }
                }
                bos.toByteArray()
            }
            member("control.tar.xz", emptyTarXz)
            member("data.tar.xz", dataTarXz)
        }
    }

    private fun tarXz(members: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(bos)).use { tar ->
            for ((name, data) in members) {
                val e = TarArchiveEntry(name)
                e.size = data.size.toLong()
                e.mode = 0b111_101_101
                tar.putArchiveEntry(e)
                tar.write(data)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return bos.toByteArray()
    }

    private fun installer(runtimeDir: File, artifactsDir: File, resolve: suspend (Artifact) -> ResolvedArtifact = { error("no resolve") }) =
        RuntimeInstaller(runtimeDir, artifactsDir, InstallPlanner(runtimeDir), resolve)

    @Test
    fun `install plan for embedded manifest produces the expected paths`() {
        val root = File("/sdcard/runtime")
        val planner = InstallPlanner(root)
        assertEquals(File(root, "rootfs"), planner.plan("alpine-rootfs").treeDest)
        assertEquals(File(root, "rootfs/usr/local/bin/opencode"), planner.plan("opencode-cli").members.single().destFile)
        assertTrue(planner.plan("opencode-cli").members.single().executable)
        assertEquals(listOf(
            File(root, "proot/proot"), File(root, "proot/proot-loader"),
        ), planner.plan("proot").members.map { it.destFile })
        assertTrue(planner.plan("proot").members.all { it.executable || it.destFile.name == "proot-loader" })
        assertEquals(File(root, "proot/libtalloc.so.2"), planner.plan("libtalloc").members.single().destFile)
        assertEquals(File(root, "proot/libandroid-shmem.so"), planner.plan("libandroid-shmem").members.single().destFile)
        assertEquals(File(root, "rootfs/usr/lib/libstdc++.so.6"), planner.plan("libstdcxx").members.single().destFile)
        assertEquals(File(root, "rootfs/usr/lib/libgcc_s.so.1"), planner.plan("libgcc").members.single().destFile)
        assertEquals(File(root, "rootfs/etc/ssl/certs/ca-certificates.crt"), planner.plan("ca-certificates").members.single().destFile)
    }

    @Test
    fun `a pinned member is lifted correctly from a small tar gz`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val memberBytes = "fake-libgcc-bytes".toByteArray()
        val tgz = File(artifacts, "libgcc-14.2.0-r4.apk")
        tarGz(tgz, listOf("usr/lib/libgcc_s.so.1" to memberBytes, ".PKGINFO" to "x".toByteArray()))
        val artifact = Artifact(
            id = "libgcc", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            payloads = listOf(Payload("libgcc_s.so.1", "usr/lib/libgcc_s.so.1", memberBytes.size.toLong(), sha256Hex(memberBytes))),
            installMembers = listOf(InstallMember("usr/lib/libgcc_s.so.1", "libgcc_s.so.1")),
        )
        val inst = installer(runtime, artifacts)
        inst.install(artifact)
        assertArrayEquals(memberBytes, File(runtime, "rootfs/usr/lib/libgcc_s.so.1").readBytes())
        assertTrue(inst.isComplete("libgcc"))
    }

    @Test
    fun `a pinned member whose hash does not match is rejected`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val tgz = File(artifacts, "libtalloc.deb.tgz")
        tarGz(tgz, listOf("./data/data/com.termux/files/usr/lib/libtalloc.so.2" to "bytes".toByteArray()))
        // installMembers mirrors payloads (self-consistency guard); the memberPath is the
        // real one so extraction reaches the hash check, which is what this test pins.
        val artifact = Artifact(
            id = "libtalloc", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            payloads = listOf(Payload("libtalloc.so.2", "./data/data/com.termux/files/usr/lib/libtalloc.so.2", 5, "00".repeat(32))),
            installMembers = listOf(InstallMember("./data/data/com.termux/files/usr/lib/libtalloc.so.2", "libtalloc.so.2")),
        )
        val inst = installer(runtime, artifacts)
        var failed = false
        try { inst.install(artifact) } catch (e: ExtractionException) { failed = true }
        assertTrue(failed)
        assertFalse(inst.isComplete("libtalloc"))
        assertFalse(File(runtime, "proot/libtalloc.so.2").exists())
    }

    @Test
    fun `npm install sums ALL extracted members against unpackedSize`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val pkgJson = """{"name":"@opencode/cli-linux-arm64-musl"}""".toByteArray()
        val bin = ByteArray(10_000) { (it % 251).toByte() }
        val tgz = File(artifacts, "opencode-cli-linux-arm64-musl.tgz")
        tarGz(tgz, listOf("package/package.json" to pkgJson, "package/bin/opencode" to bin))
        val total = pkgJson.size + bin.size
        val artifact = Artifact(
            id = "opencode-cli", fileName = tgz.name,
            source = ArtifactSource.Npm("@opencode/cli-linux-arm64-musl", "2.0.0"),
            unpackMode = UnpackMode.LIFT_MEMBERS,
            installMembers = listOf(InstallMember("package/bin/opencode", "opencode")),
        )
        val resolved = ResolvedArtifact(artifact, "https://x", null, Sha512SriDigest("sha512-AAA="), "sha512-AAA=", total.toLong())
        val inst = installer(runtime, artifacts) { resolved }
        inst.install(artifact)
        assertArrayEquals(bin, File(runtime, "rootfs/usr/local/bin/opencode").readBytes())
        val installed = File(runtime, "rootfs/usr/local/bin/opencode")
        assertTrue(installed.canExecute())
        // The check must be total-vs-unpackedSize; a payloads-only sum (0, since payloads is
        // empty for npm) would NOT equal unpackedSize:
        assertEquals(0, artifact.payloads.sumOf { it.sizeBytes ?: 0L }.toInt())
        assertTrue(total != installed.length().toInt())
        assertTrue(inst.isComplete("opencode-cli"))
    }

    @Test
    fun `npm install rejects an archive inflated beyond npm unpackedSize`() = runBlocking {
        // Contract pin: the extraction total must cover EVERY member, including ones the
        // installer did not install. Under a wrong "sum only installMembers bytes"
        // implementation this archive would PASS (installMembers bytes == claimed total);
        // under the correct contract it must FAIL because of the smuggled extra member.
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val bin = ByteArray(10_000)
        val smuggled = ByteArray(245)
        val tgz = File(artifacts, "opencode-cli-linux-arm64-musl.tgz")
        tarGz(tgz, listOf("package/bin/opencode" to bin, "package/evil.txt" to smuggled))
        val artifact = Artifact(
            id = "opencode-cli", fileName = tgz.name,
            source = ArtifactSource.Npm("@opencode/cli-linux-arm64-musl", "2.0.0"),
            installMembers = listOf(InstallMember("package/bin/opencode", "opencode")),
        )
        // npm's unpackedSize claims only the binary: the smuggled member makes the
        // real extraction 10_245 bytes, so a correct installer MUST reject this.
        val resolved = ResolvedArtifact(artifact, "https://x", null, Sha512SriDigest("sha512-AAA="), "sha512-AAA=", bin.size.toLong())
        val inst = installer(runtime, artifacts) { resolved }
        var failed = false
        try { inst.install(artifact) } catch (e: ExtractionException) { failed = true }
        assertTrue(failed)
        assertFalse(inst.isComplete("opencode-cli"))
        assertFalse(File(runtime, "rootfs/usr/local/bin/opencode").exists())
    }

    @Test
    fun `manifest payload memberPaths agree with installMembers for pinned artifacts`() {
        // Guards the drift that Important-1 found: Payload and installMembers must name the
        // same members, so matching payloads by memberPath is well-defined.
        for (artifact in embeddedRuntimeManifest.artifacts) {
            if (artifact.source !is ArtifactSource.Pinned) continue
            if (artifact.unpackMode != UnpackMode.LIFT_MEMBERS) continue
            val members = artifact.installMembers.map { it.memberPath.removePrefix("./") }.toSet()
            val payloads = artifact.payloads.map { it.memberPath.removePrefix("./") }.toSet()
            assertEquals("${artifact.id}: payload/installMember memberPaths disagree", members, payloads)
            // And pinned payloads keep their hashes/sizes (regression guard).
            assertTrue(artifact.payloads.all { it.sha256 != null && it.sizeBytes != null })
        }
    }

    @Test
    fun `symlink fallback target resolution rejects escaping linkNames`() {
        val staging = tmp.newFolder("staging")
        // Absolute linkName is treated as rootfs-relative, inside staging: OK.
        val ok = resolveContainedLinkTarget(staging, "/bin/busybox")
        assertTrue(ok.canonicalPath.startsWith(staging.canonicalPath))
        // Relative escape must throw, never be followed.
        var failed = false
        try { resolveContainedLinkTarget(staging, "../../outside/victim") } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun `re-running an install is idempotent`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val memberBytes = "libgcc".toByteArray()
        val apk = File(artifacts, "libgcc-14.2.0-r4.apk")
        tarGz(apk, listOf("usr/lib/libgcc_s.so.1" to memberBytes))
        val artifact = Artifact(
            id = "libgcc", fileName = apk.name,
            source = ArtifactSource.Pinned("https://x", apk.length(), sha256Hex(apk.readBytes())),
            payloads = listOf(Payload("libgcc_s.so.1", "usr/lib/libgcc_s.so.1", memberBytes.size.toLong(), sha256Hex(memberBytes))),
            installMembers = listOf(InstallMember("usr/lib/libgcc_s.so.1", "libgcc_s.so.1")),
        )
        val inst = installer(runtime, artifacts)
        inst.install(artifact)
        val first = File(runtime, "rootfs/usr/lib/libgcc_s.so.1").readBytes()
        val markerMtime = File(runtime, ".markers/libgcc.ok").lastModified()
        inst.install(artifact) // second run must converge, not error or duplicate
        assertArrayEquals(first, File(runtime, "rootfs/usr/lib/libgcc_s.so.1").readBytes())
        assertEquals(markerMtime, File(runtime, ".markers/libgcc.ok").lastModified())
        assertTrue(inst.isComplete("libgcc"))
    }

    @Test
    fun `a partially-completed install is not reported as complete`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        // proot artifact whose tar LACKS the proot-loader member: install must fail, and
        // the partial state must not look complete.
        val payload = tarXz(listOf("./data/data/com.termux/files/usr/bin/proot" to "proot-bin".toByteArray()))
        val debFile = File(artifacts, "proot_5.1.107.96_aarch64.deb")
        deb(debFile, payload)
        val artifact = Artifact(
            id = "proot", fileName = debFile.name,
            source = ArtifactSource.Pinned("https://x", debFile.length(), sha256Hex(debFile.readBytes())),
            // Real memberPaths (self-consistency guard); the loader is absent from the
            // tar, so extraction must fail with "member ... not found".
            payloads = listOf(
                Payload("proot", "./data/data/com.termux/files/usr/bin/proot", 9, sha256Hex("proot-bin".toByteArray())),
                Payload("proot-loader", "./data/data/com.termux/files/usr/libexec/proot/loader", 10, "00".repeat(32)),
            ),
            installMembers = listOf(
                InstallMember("./data/data/com.termux/files/usr/bin/proot", "proot"),
                InstallMember("./data/data/com.termux/files/usr/libexec/proot/loader", "proot-loader"),
            ),
        )
        val inst = installer(runtime, artifacts)
        var failed = false
        try { inst.install(artifact) } catch (e: ExtractionException) { failed = true }
        assertTrue(failed)
        assertFalse(inst.isComplete("proot"))
        assertFalse(File(runtime, ".markers/proot.ok").exists())
    }

    @Test
    fun `deb members lift through ar plus xz`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val member = "shmem-bytes".toByteArray()
        val payload = tarXz(listOf("./data/data/com.termux/files/usr/lib/libandroid-shmem.so" to member))
        val debFile = File(artifacts, "libandroid-shmem_0.7_aarch64.deb")
        deb(debFile, payload)
        val artifact = Artifact(
            id = "libandroid-shmem", fileName = debFile.name,
            source = ArtifactSource.Pinned("https://x", debFile.length(), sha256Hex(debFile.readBytes())),
            // Real memberPath (self-consistency guard) so extraction verifies and lifts.
            payloads = listOf(Payload("libandroid-shmem.so", "./data/data/com.termux/files/usr/lib/libandroid-shmem.so", member.size.toLong(), sha256Hex(member))),
            installMembers = listOf(InstallMember("./data/data/com.termux/files/usr/lib/libandroid-shmem.so", "libandroid-shmem.so")),
        )
        val inst = installer(runtime, artifacts)
        inst.install(artifact)
        assertArrayEquals(member, File(runtime, "proot/libandroid-shmem.so").readBytes())
        assertTrue(inst.isComplete("libandroid-shmem"))
    }

    @Test
    fun `path traversal members are rejected`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val tgz = File(artifacts, "alpine.tar.gz")
        tarGz(tgz, listOf("../evil.txt" to "evil".toByteArray(), "etc/hostname" to "h".toByteArray()))
        val artifact = Artifact(
            id = "alpine-rootfs", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            unpackMode = UnpackMode.UNPACK_TREE,
        )
        val inst = installer(runtime, artifacts)
        var failed = false
        try { inst.install(artifact) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertFalse(File(runtime, "evil.txt").exists())
        assertFalse(inst.isComplete("alpine-rootfs"))
    }

    @Test
    fun `tree unpack writes resolv conf and preserves files`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val tgz = File(artifacts, "alpine.tar.gz")
        tarGz(tgz, listOf("etc/os-release" to "NAME=alpine".toByteArray(), "bin/sh" to "elf".toByteArray()))
        val artifact = Artifact(
            id = "alpine-rootfs", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            unpackMode = UnpackMode.UNPACK_TREE,
        )
        val inst = installer(runtime, artifacts)
        inst.install(artifact)
        assertEquals("NAME=alpine", File(runtime, "rootfs/etc/os-release").readText())
        assertEquals(RESOLV_CONF, File(runtime, "rootfs/etc/resolv.conf").readText())
        assertTrue(File(runtime, "rootfs/bin/sh").canExecute())
        assertTrue(inst.isComplete("alpine-rootfs"))
    }

    @Test
    fun `planted resolv conf symlink cannot escape the staging root`() = runBlocking {
        // Hostile rootfs: plants etc/resolv.conf as a symlink to an outside file. The
        // installer must not let its own resolv.conf write follow that link.
        val outsideDir = tmp.newFolder("outside")
        val outside = File(outsideDir, "victim.conf").apply { writeText("ORIGINAL") }
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val tgz = File(artifacts, "alpine.tar.gz")
        tarGz(
            tgz,
            members = listOf("etc/hostname" to "h".toByteArray()),
            symlinks = listOf("etc/resolv.conf" to outside.absolutePath),
        )
        val artifact = Artifact(
            id = "alpine-rootfs", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            unpackMode = UnpackMode.UNPACK_TREE,
        )
        val inst = installer(runtime, artifacts)
        inst.install(artifact)
        assertEquals("outside file must survive", "ORIGINAL", outside.readText())
        assertEquals(RESOLV_CONF, File(runtime, "rootfs/etc/resolv.conf").readText())
    }

    @Test
    fun `absolute member path in a tree archive is rejected`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val tgz = File(artifacts, "alpine.tar.gz")
        // MUST use tarGzRaw: tarGz's TarArchiveEntry strips leading "/" at write time,
        // so the archive would contain "etc/passwd" and the test would be vacuous.
        tarGzRaw(tgz, listOf("/etc/passwd" to "x".toByteArray()))
        val artifact = Artifact(
            id = "alpine-rootfs", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            unpackMode = UnpackMode.UNPACK_TREE,
        )
        val inst = installer(runtime, artifacts)
        var failed = false
        try { inst.install(artifact) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertFalse(inst.isComplete("alpine-rootfs"))
    }

    @Test
    fun `file written through a planted dir symlink cannot escape staging`() = runBlocking {
        // Hostile rootfs: 'etc' is a symlink to an outside dir (absolute linkName), and a
        // later member 'etc/pwned' would resolve outside staging. The canonical-path
        // guard resolves through the planted link and must reject the write.
        val outsideDir = tmp.newFolder("outside")
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val tgz = File(artifacts, "alpine.tar.gz")
        tarGz(
            tgz,
            members = listOf("etc/pwned" to "pwned".toByteArray()),
            symlinks = listOf("etc" to outsideDir.absolutePath),
            symlinksFirst = true,
        )
        val artifact = Artifact(
            id = "alpine-rootfs", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            unpackMode = UnpackMode.UNPACK_TREE,
        )
        val inst = installer(runtime, artifacts)
        var failed = false
        try { inst.install(artifact) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertFalse(File(outsideDir, "pwned").exists())
        assertFalse(inst.isComplete("alpine-rootfs"))
    }
}
