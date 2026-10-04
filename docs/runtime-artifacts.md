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

## Running OpenCode v2 on the device — required extras

**The v2 binary is Bun, not Node.js.** Spec §3.2 says "OpenCode v2 replaced Bun with Node.js". That
is wrong. A crash report from the running binary states plainly:

```
Bun v1.4.2 (744846f84) Linux arm64
Linux Kernel v4.19.157 | musl
Args: "/usr/local/bin/opencode" "--smol" "--user-agent=opencode/latest/2.0.22/cli" "--use-system-ca" ...
```

`--smol` means it is a Bun standalone executable. Consequence: no Node.js, and no npm fallback
chain — the npm package *is* the Bun binary.

Three things the minirootfs does not provide, all required:

1. **`libstdc++.so.6`** — without it: `Error relocating ...: symbol not found` on the C++ RTTI
   symbols. From `libstdc++-14.2.0-r4.apk`, install the `.so` into `<rootfs>/usr/lib/`.
2. **`libgcc_s.so.1`** — without it, `libstdc++.so.6` itself fails to load on the unwinder symbols
   (`__unordtf2`, `_Unwind_Resume`, `_Unwind_SetIP`). From `libgcc-14.2.0-r4.apk`.
3. **`/dev` must be bind-mounted.** This is the one that is easy to miss. The minirootfs ships an
   empty `dev/`, and without `-b /dev` the binary loads, starts, and then dies with
   `panic(main thread): abort() called` after ~160 ms. Binding `/dev` (and `/proc`, `/sys`) makes it
   work. Bun needs `/dev/shm`.

**`-b` binds are NOT optional for the OpenCode binary.** Earlier notes in this repo said binds were
"not needed" — that was true for a busybox shell and false for Bun. Correct invocation:

```
proot -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys /usr/local/bin/opencode serve --hostname 127.0.0.1 --port <port>
```

with `TMPDIR`, `LD_LIBRARY_PATH`, `PROOT_LOADER`, `HOME=/root` and `PATH` set as before.

Also worth extracting: `ca-certificates-bundle` provides `/etc/ssl/certs/ca-certificates.crt`,
which the binary needs because it passes `--use-system-ca`. Without it TLS to model providers fails.

### Verified working on device

```
$ proot ... /usr/local/bin/opencode --version
opencode v2.0.22

$ proot ... /usr/local/bin/opencode serve --hostname 127.0.0.1 --port 45997
server listening on http://127.0.0.1:45997
```

And the listener is real — `/proc/net/tcp` shows `0100007F:B3AD` in state `0A` (LISTEN), i.e.
127.0.0.1:45997.

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