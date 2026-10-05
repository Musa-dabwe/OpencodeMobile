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

    private fun tarGz(file: File, members: List<Pair<String, ByteArray>>, symlinks: List<Pair<String, String>> = emptyList()) {
        TarArchiveOutputStream(GZIPOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, data) in members) {
                val e = TarArchiveEntry(name)
                e.size = data.size.toLong()
                e.mode = 0b111_101_101 // rwxr-xr-x
                tar.putArchiveEntry(e)
                tar.write(data)
                tar.closeArchiveEntry()
            }
            for ((name, target) in symlinks) {
                val e = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                e.linkName = target
                tar.putArchiveEntry(e)
                tar.closeArchiveEntry()
            }
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
        val artifact = Artifact(
            id = "libtalloc", fileName = tgz.name,
            source = ArtifactSource.Pinned("https://x", tgz.length(), sha256Hex(tgz.readBytes())),
            payloads = listOf(Payload("libtalloc.so.2", "x", 5, "00".repeat(32))),
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
    fun `npm install fails when a member is truncated out of the tar`() = runBlocking {
        val artifacts = tmp.newFolder("artifacts")
        val runtime = tmp.newFolder("runtime")
        val bin = ByteArray(10_000)
        val tgz = File(artifacts, "opencode-cli-linux-arm64-musl.tgz")
        // Archive claims only bin/opencode; unpackedSize includes the missing package.json.
        tarGz(tgz, listOf("package/bin/opencode" to bin))
        val artifact = Artifact(
            id = "opencode-cli", fileName = tgz.name,
            source = ArtifactSource.Npm("@opencode/cli-linux-arm64-musl", "2.0.0"),
            installMembers = listOf(InstallMember("package/bin/opencode", "opencode")),
        )
        val resolved = ResolvedArtifact(artifact, "https://x", null, Sha512SriDigest("sha512-AAA="), "sha512-AAA=", (bin.size + 245).toLong())
        val inst = installer(runtime, artifacts) { resolved }
        var failed = false
        try { inst.install(artifact) } catch (e: ExtractionException) { failed = true }
        assertTrue(failed)
        assertFalse(inst.isComplete("opencode-cli"))
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
            payloads = listOf(
                Payload("proot", "x", 9, sha256Hex("proot-bin".toByteArray())),
                Payload("proot-loader", "y", 10, "00".repeat(32)),
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
            payloads = listOf(Payload("libandroid-shmem.so", "x", member.size.toLong(), sha256Hex(member))),
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
}
