package com.musa.opencodeharness.data.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where an artifact's bytes and integrity come from. Two trust postures, differently loaded:
 *
 * - [Pinned]: url/size/SHA-256 hardcoded here and reviewed into the app. The strongest root —
 *   a swapped rootfs or an injected agent binary is caught before use. Everything except
 *   OpenCode is pinned: they change rarely, and a silent swap costs more than a re-download.
 * - [Npm]: fresh `dist.tarball` + `dist.integrity` (SRI sha512) fetched from npm's registry at
 *   download time. Same trust anchor the official v2 installer uses; pinning would just mean
 *   a code change plus app release for every upstream patch. A declared
 *   [Npm.minVersion] floor refuses a bad 0.x/1.x publish.
 */
@Serializable
sealed interface ArtifactSource {
    @Serializable
    @kotlinx.serialization.SerialName("pinned")
    data class Pinned(
        /** HTTPS source URL. */
        val url: String,
        /** Expected size of the downloaded bytes, in bytes. */
        val sizeBytes: Long,
        /** Expected SHA-256 of the downloaded bytes, lowercase hex. */
        val sha256: String,
    ) : ArtifactSource

    @Serializable
    @kotlinx.serialization.SerialName("npm")
    data class Npm(
        /** npm package name, e.g. `@opencode/cli-linux-arm64-musl`. */
        val pkg: String,
        /** Refuse a resolved version below this floor (major-line guard). */
        val minVersion: String,
    ) : ArtifactSource
}

/**
 * One downloadable runtime artifact. The download unit is always the container served at the
 * resolved URL (`.tgz`/`.deb`/`.apk`); the useful member it yields lives in [payloads].
 */
@Serializable
data class Artifact(
    /** Stable id used for logs, state, and as the key in the manifest JSON. */
    val id: String,
    /** Local file name under the runtime artifacts directory. */
    val fileName: String,
    /** How this artifact's URL + integrity are obtained. */
    val source: ArtifactSource,
    /**
     * What this container yields after extraction — structured for Task 3.
     *
     * Contract on the extraction check: for PINNED artifacts (where every Payload carries a
     * non-null sizeBytes and sha256) Task 3 sums `payloads[].sizeBytes` and re-hashes each
     * member. For NPM artifacts this list is empty BY POLICY and Task 3 instead compares the
     * SUM OF ALL extracted member sizes against `ResolvedArtifact.unpackedSize`. Never sum
     * `payloads[].sizeBytes` for an npm artifact — the list being empty makes that attempt
     * a guaranteed-visible mismatch rather than a silent wrong comparison.
     *
     * [installMembers] is what tells Task 3 WHICH members to lift, and is set for every artifact
     * including npm ones. It is separate from [payloads] on purpose: `payloads` carries
     * per-member expectations that drift on every upstream release (hence empty for npm), while
     * the member PATHS are stable. Empty [payloads] therefore means "no per-member expectations",
     * never "nothing to extract".
     */
    val payloads: List<Payload> = emptyList(),
    /**
     * How Task 3 consumes this container. The two are genuinely different operations: the Alpine
     * minirootfs is an operating system image unpacked whole into `filesDir/runtime/rootfs`,
     * while every other artifact is a container we lift a named member or two out of. Do not let
     * this be inferred from an empty [installMembers] — an empty list means "lift nothing", not
     * "unpack everything".
     */
    val unpackMode: UnpackMode = UnpackMode.LIFT_MEMBERS,
    /**
     * Archive member paths Task 3 lifts, each installed under the given file name.
     *
     * Present for every artifact, npm included — an empty [payloads] must never be read as
     * "nothing to extract". Paths and install names are stable across upstream releases; only
     * the per-member sizes and hashes in [payloads] drift, which is why they are separate.
     */
    val installMembers: List<InstallMember> = emptyList(),
    /** Freeform prose about the artifact; not consumed programmatically. */
    val note: String = "",
)

/**
 * One file to lift out of a container, and the name to install it under.
 *
 * Carries no size or hash: those live in [Payload] and are intentionally absent for npm
 * artifacts, whose members change every release. This type is only "where it is and what to
 * call it", which does not drift.
 */
@Serializable
data class InstallMember(
    /**
     * Path of the member inside the container archive, e.g. `package/bin/opencode`.
     *
     * Exact as that archive stores it, so the string differs by container type and is deliberately
     * NOT normalised across them: `.tgz`/`.apk` are plain tars (`usr/lib/libgcc_s.so.1`), while a
     * `.deb` is an `ar` archive whose payload sits in an inner tar under Termux's install root
     * (`./data/data/com.termux/files/usr/bin/proot`). Every value here was read out of the real
     * artifact — a wrong path silently extracts nothing.
     */
    val memberPath: String,
    /** File name Task 3 installs under, e.g. `opencode`. */
    val installAs: String,
)

/** How an artifact's container is turned into installed files. */
@Serializable
enum class UnpackMode {
    /** Unpack the whole archive as a directory tree — the Alpine minirootfs. */
    UNPACK_TREE,

    /** Lift only the members named in [Artifact.installMembers]. */
    LIFT_MEMBERS,
}

/**
 * A file to be extracted out of a container artifact (`.tgz`/`.deb`/`.apk`). Structured so
 * Task 3 consumes data rather than parsing [Artifact.note] prose.
 */
@Serializable
data class Payload(
    /** File name Task 3 installs under, e.g. `libstdc++.so.6`. */
    val name: String,
    /** Path of the member inside the container archive. */
    val memberPath: String,
    /**
     * Expected size of THIS member, for pinned artifacts only. Null for npm artifacts:
     * per-member sizes drift on every upstream release and the right check there is the SUM
     * of extracted member sizes against [NpmResolution.unpackedSize] (npm's total over ALL
     * members, not just one), reachable via [ResolvedArtifact.unpackedSize].
     */
    val sizeBytes: Long? = null,
    /**
     * Expected SHA-256 of THIS member, pinned artifacts only. It is deliberately null for
     * npm artifacts: the tarball's SHA-512 (from `dist.integrity`) already covers every
     * member, so re-hashing a member verifies the same bytes twice and catches nothing. A
     * payload hash only guards against extraction bugs for artifacts WE pinned.
     */
    val sha256: String? = null,
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
            fileName = "opencode-cli-linux-arm64-musl.tgz",
            source = ArtifactSource.Npm(
                pkg = "@opencode/cli-linux-arm64-musl",
                minVersion = "2.0.0",
            ),
            // Deliberately EMPTY for the npm artifact. `payloads` is the list whose
            // `sizeBytes` values are summed to check a PINNED artifact's extraction — and
            // for opencode that sum would be wrong by construction, because npm's
            // `unpackedSize` covers every member of the tarball (package.json included),
            // not just `package/bin/opencode`. Listing a single member here would invite
            // the failing comparison 199,167,184 != 199,167,429. For npm artifacts the
            // extraction check is: the SUM of the sizes of ALL extracted members ==
            // ResolvedArtifact.unpackedSize.
            payloads = emptyList(),
            // Which member to lift still lives in structured data, not just the comment above.
            installMembers = listOf(
                InstallMember(memberPath = "package/bin/opencode", installAs = "opencode"),
            ),
            note = "The OpenCode v2 server binary; URL and integrity resolve from npm at download time.",
        ),
        Artifact(
            id = "alpine-rootfs",
            fileName = "alpine.tar.gz",
            source = ArtifactSource.Pinned(

                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz",

                sizeBytes = 3_850_365,

                sha256 = "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",

            ),
            payloads = emptyList(),
            unpackMode = UnpackMode.UNPACK_TREE,
            note = "Alpine 3.21.3 aarch64 minirootfs; unpacked as-is into filesDir/runtime/rootfs.",
        ),
        Artifact(
            id = "proot",
            fileName = "proot_5.1.107.96_aarch64.deb",
            source = ArtifactSource.Pinned(

                url = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb",

                sizeBytes = 98_800,

                sha256 = "8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0",

            ),
            payloads = listOf(
                Payload("proot", "./data/data/com.termux/files/usr/bin/proot", 247_488,
                    "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717"),
                Payload("proot-loader", "./data/data/com.termux/files/usr/libexec/proot/loader", 18_136,
                    "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551"),
            ),
            installMembers = listOf(
                InstallMember(memberPath = "./data/data/com.termux/files/usr/bin/proot", installAs = "proot"),
                InstallMember(memberPath = "./data/data/com.termux/files/usr/libexec/proot/loader", installAs = "proot-loader"),
            ),
            note = "Termux .deb; the entry's hash verifies the .deb container, payload hashes " +
                "verify the extracted members.",
        ),
        Artifact(
            id = "libtalloc",
            fileName = "libtalloc_2.5.0_aarch64.deb",
            source = ArtifactSource.Pinned(

                url = "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb",

                sizeBytes = 34_036,

                sha256 = "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7",

            ),
            payloads = listOf(
                Payload("libtalloc.so.2", "./data/data/com.termux/files/usr/lib/libtalloc.so.2", 33_592,
                    "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309"),
            ),
            installMembers = listOf(
                InstallMember(memberPath = "./data/data/com.termux/files/usr/lib/libtalloc.so.2", installAs = "libtalloc.so.2"),
            ),
            note = "Termux .deb; proot link dependency.",
        ),
        Artifact(
            id = "libandroid-shmem",
            fileName = "libandroid-shmem_0.7_aarch64.deb",
            source = ArtifactSource.Pinned(

                url = "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",

                sizeBytes = 7_216,

                sha256 = "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",

            ),
            payloads = listOf(
                Payload("libandroid-shmem.so", "./data/data/com.termux/files/usr/lib/libandroid-shmem.so", 14_432,
                    "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"),
            ),
            installMembers = listOf(
                InstallMember(memberPath = "./data/data/com.termux/files/usr/lib/libandroid-shmem.so", installAs = "libandroid-shmem.so"),
            ),
            note = "Termux .deb; proot link dependency.",
        ),
        Artifact(
            id = "libstdcxx",
            fileName = "libstdc++-14.2.0-r4.apk",
            source = ArtifactSource.Pinned(

                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/libstdc++-14.2.0-r4.apk",

                sizeBytes = 906_144,

                sha256 = "93dded5af4e42ca59a2e981ffc5acc59c5d7110cec4adaeb15b84dc224526b63",

            ),
            payloads = listOf(
                Payload("libstdc++.so.6", "usr/lib/libstdc++.so.6.0.33", 2_754_992,
                    "1f1a71be4319632f743507ef7c6eaebaccad8d6f277055cf2c6b16b544f9f418"),
            ),
            installMembers = listOf(
                InstallMember(memberPath = "usr/lib/libstdc++.so.6.0.33", installAs = "libstdc++.so.6"),
            ),
            note = "Alpine .apk; the download unit is the package, not the .so member.",
        ),
        Artifact(
            id = "libgcc",
            fileName = "libgcc-14.2.0-r4.apk",
            source = ArtifactSource.Pinned(

                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/libgcc-14.2.0-r4.apk",

                sizeBytes = 66_943,

                sha256 = "7cfdc00f102d3b98fe563b31e7a59a7b7ab61850c1751915f34e419e83ef2653",

            ),
            payloads = listOf(
                Payload("libgcc_s.so.1", "usr/lib/libgcc_s.so.1", 133_008,
                    "69c45f16064b7b61b9a3d64c37395d13368a8fe719745d49e9508ec650fea971"),
            ),
            installMembers = listOf(
                InstallMember(memberPath = "usr/lib/libgcc_s.so.1", installAs = "libgcc_s.so.1"),
            ),
            note = "Alpine .apk; the download unit is the package, not the .so member.",
        ),
        Artifact(
            id = "ca-certificates",
            fileName = "ca-certificates-bundle-20260909-r0.apk",
            source = ArtifactSource.Pinned(

                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/ca-certificates-bundle-20260909-r0.apk",

                sizeBytes = 108_469,

                sha256 = "46aa3bd5988d0bed141242d957a52e4c871066ae4ae1c97195accc0b73e5b94a",

            ),
            payloads = listOf(
                Payload("ca-certificates.crt", "etc/ssl/certs/ca-certificates.crt", 181_724,
                    "028fca5150ee1915c4474489985d55c54fceaa680c754cc8d1301734f01b2232"),
            ),
            installMembers = listOf(
                InstallMember(memberPath = "etc/ssl/certs/ca-certificates.crt", installAs = "ca-certificates.crt"),
            ),
            note = "Alpine .apk; the download unit is the package, not the .crt member.",
        ),
    ),
)
