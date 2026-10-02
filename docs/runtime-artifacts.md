# Runtime artifact manifest

Local cache of every binary the app downloads at first run. **Do not delete these** — the
network here is metered and re-downloading the OpenCode binary costs ~89 MB.

Location: `~/storage/shared/Build/ocmobile-artifacts/`

| File | Size | Source | Notes |
| --- | --- | --- | --- |
| `opencode-cli-linux-arm64-musl-2.0.22.tgz` | 88,533,443 | `https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.22.tgz` | The agent binary. See below |
| `alpine.tar.gz` | 3,850,365 | `https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz` | Rootfs |
| `proot-aarch64` | 247,488 | `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb` (extracted `bin/proot`) | Needs `libtalloc.so.2` + `libandroid-shmem.so` alongside |
| `proot-loader-aarch64` | 18,136 | same `.deb`, `libexec/proot/loader` | Must be pointed at by `PROOT_LOADER` |
| `libtalloc.so.2` | 33,592 | `https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb` | proot link dependency |
| `libandroid-shmem.so` | 14,432 | `https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb` | proot link dependency |

SHA-256 checksums are in `SHA256SUMS.txt` in the same directory.

## The OpenCode binary

| Field | Value |
| --- | --- |
| Version | **2.0.22** |
| Package | `@opencode/cli-linux-arm64-musl` |
| Dependencies | **none** — self-contained, no Node.js needed |
| Tarball size | 88,533,443 bytes |
| Unpacked binary | 199,167,184 bytes |
| Binary name | **`opencode`** (resolves the spec §3.2 `[VERIFY]`) |
| Linker | `/lib/ld-musl-aarch64.so.1` — matches the Alpine rootfs |
| npm integrity (sha512) | `ChEe1o5ydUZO/7N6LBhbp6TSG4cxEIil9Rejv8fTPxiKFCBUu8E3walzpBGASuwvFdr4iEl2Z/WTpbWHaSPI+A==` |

The sha512 of the downloaded tarball **matches npm's published `dist.integrity` exactly**, so the
artifact is verified end to end against npm's own record.

Version resolution for the app: `https://opencode.ai/update/api/latest/cli/npm` returns
`{"channel":"latest","package":"@opencode/cli","version":"2.0.22","ref":"refs/heads/v2"}`. The app
should read the version from there rather than hardcoding it, then fetch that version's
`dist.integrity` from `https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/<version>`.

## Notes for whoever builds the downloader

- The OpenCode tarball extracts to `package/bin/opencode` — a single file, no directory structure
  to preserve inside the rootfs.
- `proot` from the Termux package hardcodes a loader path under `/data/data/com.termux/`. Running it
  from the app's sandbox **requires** `PROOT_LOADER` to be set, or exec fails with
  `the loader was not found or doesn't work`.
- `proot` links against `libtalloc.so.2` and `libandroid-shmem.so`, which must sit next to it and be
  reachable via `LD_LIBRARY_PATH`.
- The Termux `.deb` packages are the upstream source, but they are Termux's builds. For a shipped
  app it would be worth checking whether a proot build with the loader path compiled in (or
  patching it) is preferable to shipping `PROOT_LOADER` forever. For this slice, `PROOT_LOADER`
  works and is verified.