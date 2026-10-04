package com.musa.opencodeharness.data.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One downloadable runtime artifact.
 *
 * What this describes is exactly the byte stream [url] serves: [fileName] is the name the
 * downloader stores it under, [sizeBytes] and [sha256] (lowercase hex) describe those bytes.
 * Several entries are compressed containers (`.deb`/`.apk`/`.tgz`) whose *useful* content —
 * a single `.so`, the proot binary, the opencode binary — is extracted by later tasks. That is
 * why, for example, the libstdc++ entry is a ~900 KB `.apk` rather than a `.so` file: the
 * download unit is the Alpine package, and Task 3 lifts the `.so` out of it. The payload this
 * container yields is recorded in [note] with its own expected size/hash so Task 3 can check it.
 */
@Serializable
data class Artifact(
    /** Stable id used for logs, state, and as the key in the manifest JSON. */
    val id: String,
    /** Local file name under the runtime artifacts directory. */
    val fileName: String,
    /** HTTPS source URL. */
    val url: String,
    /** Expected size of the downloaded bytes, in bytes. */
    val sizeBytes: Long,
    /** Expected SHA-256 of the downloaded bytes, lowercase hex. */
    val sha256: String,
    /** What this container yields after extraction, with extraction-level size/hash. */
    val note: String = "",
)

/** The pinned, parseable manifest of everything the runtime needs at first launch. */
@Serializable
data class RuntimeManifest(
    val schemaVersion: Int = 1,
    val artifacts: List<Artifact>,
) {
    fun artifact(id: String): Artifact =
        artifacts.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown artifact id '$id'")
}

private val manifestJson = Json { ignoreUnknownKeys = true }

fun parseRuntimeManifest(text: String): RuntimeManifest =
    manifestJson.decodeFromString(RuntimeManifest.serializer(), text)

/**
 * The manifest shipped with the app.
 *
 * Trust story (see below). All hashes were recorded from the real artifacts described in
 * `docs/runtime-artifacts.md`; the OpenCode entry's SHA-256 matches the tarball produced by
 * upstream's npm publish, whose integrity we can re-check independently against
 * `registry.npmjs.org/@opencode/cli-linux-arm64-musl/<version>` (`dist.integrity`, sha512).
 */
val embeddedRuntimeManifest = RuntimeManifest(
    schemaVersion = 1,
    artifacts = listOf(
        Artifact(
            id = "opencode-cli",
            fileName = "opencode-cli-linux-arm64-musl-2.0.22.tgz",
            url = "https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.22.tgz",
            sizeBytes = 88_533_443,
            sha256 = "8f2d1fab861b532210259aadfe14b6325d2d7184417eca4c443bcf6cc711e3bb",
            // Extracts to package/bin/opencode (~199 MB unpacked). Verified end-to-end against
            // npm's published dist.integrity (sha512), so this entry need not be believed on
            // faith alone.
            note = "The OpenCode v2 server binary; unpacked binary is package/bin/opencode.",
        ),
        Artifact(
            id = "alpine-rootfs",
            fileName = "alpine.tar.gz",
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz",
            sizeBytes = 3_850_365,
            sha256 = "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",
            note = "Alpine 3.21.3 aarch64 minirootfs; the proot root filesystem.",
        ),
        Artifact(
            id = "proot",
            fileName = "proot_5.1.107.96_aarch64.deb",
            url = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb",
            sizeBytes = 98_800,
            sha256 = "8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0",
            // bin/proot (247,488 bytes, sha256 1545b85b...dd717) + libexec/proot/loader
            // (18,136 bytes, sha256 cbdef0e6...6b551). Both are extracted by Task 3; shas here
            // verify the .deb container, not the extracted binaries.
            note = "Termux .deb; extract bin/proot and libexec/proot/loader.",
        ),
        Artifact(
            id = "libtalloc",
            fileName = "libtalloc_2.5.0_aarch64.deb",
            url = "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb",
            sizeBytes = 34_036,
            sha256 = "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7",
            // Extracted payload libtalloc.so.2: 33,592 bytes, sha256 742b438c...6309.
            note = "Termux .deb; extract lib/aarch64-linux-android/libtalloc.so.2.",
        ),
        Artifact(
            id = "libandroid-shmem",
            fileName = "libandroid-shmem_0.7_aarch64.deb",
            url = "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",
            sizeBytes = 7_216,
            sha256 = "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
            // Extracted payload libandroid-shmem.so: 14,432 bytes, sha256 84475798...d731.
            note = "Termux .deb; extract lib/aarch64-linux-android/libandroid-shmem.so.",
        ),
        Artifact(
            id = "libstdcxx",
            fileName = "libstdc++-14.2.0-r4.apk",
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/libstdc++-14.2.0-r4.apk",
            sizeBytes = 906_144,
            sha256 = "93dded5af4e42ca59a2e981ffc5acc59c5d7110cec4adaeb15b84dc224526b63",
            // Not a .so file itself: Task 3 extracts usr/lib/libstdc++.so.6 out of this Alpine
            // package. Shipped as the .apk because that is the unit apkovl/packaging provides.
            note = "Alpine .apk; extract usr/lib/libstdc++.so.6.",
        ),
        Artifact(
            id = "libgcc",
            fileName = "libgcc-14.2.0-r4.apk",
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/libgcc-14.2.0-r4.apk",
            sizeBytes = 66_943,
            sha256 = "7cfdc00f102d3b98fe563b31e7a59a7b7ab61850c1751915f34e419e83ef2653",
            note = "Alpine .apk; extract usr/lib/libgcc_s.so.1.",
        ),
        Artifact(
            id = "ca-certificates",
            fileName = "ca-certificates-bundle-20260909-r0.apk",
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/ca-certificates-bundle-20260909-r0.apk",
            sizeBytes = 108_469,
            sha256 = "46aa3bd5988d0bed141242d957a52e4c871066ae4ae1c97195accc0b73e5b94a",
            note = "Alpine .apk; extract etc/ssl/certs/ca-certificates.crt.",
        ),
    ),
)
