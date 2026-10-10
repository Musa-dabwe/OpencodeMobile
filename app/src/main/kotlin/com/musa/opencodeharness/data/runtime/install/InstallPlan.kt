package com.musa.opencodeharness.data.runtime.install

import com.musa.opencodeharness.data.runtime.Artifact
import com.musa.opencodeharness.data.runtime.RuntimeManifest
import com.musa.opencodeharness.data.runtime.UnpackMode
import com.musa.opencodeharness.data.runtime.embeddedRuntimeManifest
import java.io.File

data class MemberInstall(
    /** Exact member path inside the container archive — from the manifest, never re-typed. */
    val memberPath: String,
    val destFile: File,
    val executable: Boolean,
)

data class InstallPlan(
    val artifactId: String,
    /** Tree-unpack destination (rootfs), null for LIFT_MEMBERS. */
    val treeDest: File?,
    val members: List<MemberInstall>,
)

/**
 * Derives install plans from the manifest. The manifest is the source of truth for WHICH
 * members to lift and WHAT to call them ([com.musa.opencodeharness.data.runtime.Artifact.installMembers])
 * and HOW to treat the container ([com.musa.opencodeharness.data.runtime.Artifact.unpackMode]);
 * this class only owns destination DIRECTORIES.
 *
 * There is deliberately no per-artifact table of member paths here — a previous version
 * duplicated every memberPath in a `when` and the two had already silently drifted
 * (Payload.memberPath vs installMembers.memberPath), so a re-pin could have been ignored.
 * The only remaining per-artifact choices are these two small maps:
 *
 * filesDir/runtime/
 *   rootfs/  (alpine minirootfs, UNPACK_TREE)
 *     usr/local/bin/opencode
 *     usr/lib/…, etc/ssl/certs/…
 *   proot/   (proot, proot-loader, libtalloc.so.2, libandroid-shmem.so — co-located because
 *            LD_LIBRARY_PATH and PROOT_LOADER point here; verified on device)
 */
class InstallPlanner(
    private val runtimeDir: File,
    private val manifest: RuntimeManifest = embeddedRuntimeManifest,
) {
    val rootfsDir: File get() = File(runtimeDir, "rootfs")
    val prootDir: File get() = File(runtimeDir, "proot")

    private enum class DestRoot { PROOT, USR_LOCAL_BIN, USR_LIB, CA_CERTS }

    /** artifact id -> destination directory. The ONLY per-artifact choice left. */
    private val DESTINATION = mapOf(
        "proot" to DestRoot.PROOT,
        "libtalloc" to DestRoot.PROOT,
        "libandroid-shmem" to DestRoot.PROOT,
        "opencode-cli" to DestRoot.USR_LOCAL_BIN,
        "libstdcxx" to DestRoot.USR_LIB,
        "libgcc" to DestRoot.USR_LIB,
        "ca-certificates" to DestRoot.CA_CERTS,
    )

    /** installAs names that need the +x bit. */
    private val EXECUTABLES = setOf("proot", "proot-loader", "opencode")

    private fun destDir(root: DestRoot): File = when (root) {
        DestRoot.PROOT -> prootDir
        DestRoot.USR_LOCAL_BIN -> File(rootfsDir, "usr/local/bin")
        DestRoot.USR_LIB -> File(rootfsDir, "usr/lib")
        DestRoot.CA_CERTS -> File(rootfsDir, "etc/ssl/certs")
    }

    fun plan(artifactId: String): InstallPlan = plan(manifest.artifact(artifactId))

    fun plan(artifact: Artifact): InstallPlan = when (artifact.unpackMode) {
        UnpackMode.UNPACK_TREE -> InstallPlan(artifact.id, treeDest = rootfsDir, members = emptyList())
        UnpackMode.LIFT_MEMBERS -> {
            val root = DESTINATION[artifact.id]
                ?: throw IllegalArgumentException("No destination directory mapped for artifact '${artifact.id}'")
            InstallPlan(
                artifact.id,
                treeDest = null,
                members = artifact.installMembers.map { m ->
                    MemberInstall(m.memberPath, File(destDir(root), m.installAs), m.installAs in EXECUTABLES)
                },
            )
        }
    }
}
