package com.musa.opencodeharness.data.runtime.install

import java.io.File

/**
 * The concrete destinations for one artifact's members. Pure data — produced by
 * [InstallPlanner.plan], consumed by [RuntimeInstaller], and asserted on in tests.
 */
data class MemberInstall(
    /** Exact member path inside the container archive. */
    val memberPath: String,
    /** Absolute destination file (or symlink target decision) on disk. */
    val destFile: File,
    /** Whether to set the executable bit. */
    val executable: Boolean,
)

data class InstallPlan(
    val artifactId: String,
    /** Tree-unpack destination (rootfs), null for LIFT_MEMBERS. */
    val treeDest: File?,
    /** Members to lift, for LIFT_MEMBERS. */
    val members: List<MemberInstall>,
)

/**
 * Maps manifest artifacts to concrete on-disk destinations. Path layout is fixed here —
 * the single place a Task-4 launcher can rely on:
 *
 * ```
 * filesDir/runtime/
 *   rootfs/                      <- alpine minirootfs, UNPACK_TREE; opencode + lifted libs land inside
 *     usr/local/bin/opencode     <- npm artifact's lifted binary
 *     usr/lib/libstdc++.so.6    <- real file, installed under the soname (see symlink note)
 *     usr/lib/libgcc_s.so.1
 *     etc/ssl/certs/ca-certificates.crt
 *     etc/resolv.conf            <- written by the installer (absent from minirootfs)
 *   proot/
 *     proot, proot-loader, libtalloc.so.2, libandroid-shmem.so
 * ```
 *
 * proot, its loader, and its two shared libraries MUST sit together: `PROOT_LOADER` and
 * `LD_LIBRARY_PATH` point at that directory, and verified-on-device findings say so. Do not
 * split them.
 *
 * Symlink decision for `libstdc++.so.6`: the apk ships it as a symlink to
 * `libstdc++.so.6.0.33`. We lift the versioned REAL member (the one [Payload] pins) and
 * install it under the soname as a plain file. Recreating a symlink would require also
 * shipping the versioned name under rootfs/usr/lib; a real file under the soname is one
 * fewer moving part and ldconfig/soname lookup both work because the file's own SONAME is
 * libstdc++.so.6.
 */
class InstallPlanner(private val runtimeDir: File) {

    val rootfsDir: File get() = File(runtimeDir, "rootfs")
    val prootDir: File get() = File(runtimeDir, "proot")

    fun plan(artifactId: String): InstallPlan = when (artifactId) {
        "alpine-rootfs" -> InstallPlan(artifactId, treeDest = rootfsDir, members = emptyList())
        "opencode-cli" -> InstallPlan(
            artifactId, treeDest = null,
            members = listOf(
                MemberInstall("package/bin/opencode", File(rootfsDir, "usr/local/bin/opencode"), executable = true),
            ),
        )
        "proot" -> InstallPlan(
            artifactId, treeDest = null,
            members = listOf(
                MemberInstall("./data/data/com.termux/files/usr/bin/proot", File(prootDir, "proot"), executable = true),
                MemberInstall("./data/data/com.termux/files/usr/libexec/proot/loader", File(prootDir, "proot-loader"), executable = true),
            ),
        )
        "libtalloc" -> InstallPlan(
            artifactId, treeDest = null,
            members = listOf(
                MemberInstall("./data/data/com.termux/files/usr/lib/libtalloc.so.2", File(prootDir, "libtalloc.so.2"), executable = false),
            ),
        )
        "libandroid-shmem" -> InstallPlan(
            artifactId, treeDest = null,
            members = listOf(
                MemberInstall("./data/data/com.termux/files/usr/lib/libandroid-shmem.so", File(prootDir, "libandroid-shmem.so"), executable = false),
            ),
        )
        "libstdcxx" -> InstallPlan(
            artifactId, treeDest = null,
            members = listOf(
                MemberInstall("usr/lib/libstdc++.so.6.0.33", File(rootfsDir, "usr/lib/libstdc++.so.6"), executable = false),
            ),
        )
        "libgcc" -> InstallPlan(
            artifactId, treeDest = null,
            members = listOf(
                MemberInstall("usr/lib/libgcc_s.so.1", File(rootfsDir, "usr/lib/libgcc_s.so.1"), executable = false),
            ),
        )
        "ca-certificates" -> InstallPlan(
            artifactId, treeDest = null,
            members = listOf(
                MemberInstall("etc/ssl/certs/ca-certificates.crt", File(rootfsDir, "etc/ssl/certs/ca-certificates.crt"), executable = false),
            ),
        )
        else -> throw IllegalArgumentException("No install plan for artifact '$artifactId'")
    }
}
