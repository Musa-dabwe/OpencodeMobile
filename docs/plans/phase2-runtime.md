# Plan — Phase 2: the app starts OpenCode itself

Spec: `docs/specs/OpenCode Mobile – Build Spec.md` (binding authority)
Findings: `docs/phase0-findings.md` (all device facts already verified)

## Goal

The app currently reads a hardcoded placeholder endpoint. After this slice, the app downloads its
own runtime, launches `opencode serve` inside proot on the device, and talks to the server **it
started itself** — verified end to end on the connected device.

## The OpenCode artifact — v2, not v1

**Corrected.** An earlier verification pass used v1.18.34 from the `sst/opencode` GitHub releases.
That was the wrong target. The v2 install path is a different distribution channel entirely.

The authoritative version is served by OpenCode itself:

```
https://opencode.ai/update/api/latest/cli/npm
-> {"channel":"latest","package":"@opencode/cli","version":"2.0.22","ref":"refs/heads/v2"}
```

The v2 installer (`https://opencode.ai/v2/install`) resolves musl targets to an npm package, not a
GitHub release asset. For Alpine/aarch64 that is:

| Field | Value |
| --- | --- |
| Package | `@opencode/cli-linux-arm64-musl` |
| Version | `2.0.22` |
| Tarball | `https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.22.tgz` |
| Integrity (sha512) | `ChEe1o5ydUZO/7N6LBhhp6TSG4cxEIil9Rejv8fTPxiKFCBUu8E3walzpBGASuwvFdr4iEl2Z/WTpbWHaSPI+A==` |
| npm shasum (sha1) | `ea1a07c249d8722181aa1886db2de9c70dd98d17` |
| Dependencies | **none** |
| Unpacked size | ~199 MB |

Consequences that change the plan:

- **No Node.js is needed.** The v2 musl package is a self-contained binary, so spec §3.2's
  `apk add nodejs npm` fallback chain is moot. Do not install Node.
- **Spec §3.2's [VERIFY] about the download URL and checksum source is answered**: npm publishes
  per-version `dist.integrity` (sha512) and `dist.shasum`. That is the integrity source to use, not
  a docs page.
- **Spec §3.2's [VERIFY] on the installed binary name is still open** — v2 may install as
  `opencode` or `opencode2`. Verify on device before hardcoding a path.
- **Size changes the first-run experience.** Compressed this is roughly 70 MB, plus ~4 MB rootfs
  and ~300 KB proot. The download is the longest operation the app performs, so Task 2's progress
  and resumability are load-bearing, not nice-to-haves.
- **Everything in `docs/phase0-findings.md` was verified against v1.18.34.** Treat the API-shape
  findings as corroborated-by-v2-spec rather than proven-on-v2. Re-verify the handful of shapes the
  client depends on against the real v2.0.22 binary on device before trusting them in the UI.

## Global Constraints

- Build: `ANDROID_HOME=~/Android/Sdk sh gradlew :app:assembleDebug`.
  `gradlew` is NOT executable — `sh gradlew`, never `./gradlew`.
- Tests: `ANDROID_HOME=~/Android/Sdk sh gradlew :app:testDebugUnitTest --rerun-tasks`.
  `--rerun-tasks` is REQUIRED; without it the task reports UP-TO-DATE and a broken test looks green.
- Kotlin DSL only; dependencies as `libs.*` aliases, never an inline coordinate.
- SDK levels unchanged: compileSdk 36 / targetSdk 34 / minSdk 30. Package stays
  `com.musa.opencodeharness`.
- **Never log the password.** The generated server password must not appear in any log, any
  user-facing string, or any exception message.
- **Verify SHA-256 for every downloaded artifact** (spec §9). A mismatch is a hard failure, not a
  warning.
- **Setup must be resumable after process death** (spec §5.1). An interrupted run must not leave
  a lock or a half-extracted rootfs that bricks the app.
- Do not remove `AndroidManifest.xml`'s INTERNET permission or the loopback network security config.
- Runtime lives in `filesDir`. musl binaries CANNOT exec from `/data/local/tmp` (verified).
- proot requires: `PROOT_LOADER`, `LD_LIBRARY_PATH` including the proot lib dir, and explicit
  `PATH=/bin:/sbin:/usr/bin:/usr/sbin`, `HOME=/root`, `TMPDIR`. Omitting any of these fails in a
  way that looks like a permissions problem. All verified; see docs/phase0-findings.md.

## Decided by the user

- **Download on first run.** Nothing is committed to git and nothing large ships in the APK. The
  app fetches a manifest, downloads artifacts, verifies SHA-256, and unpacks on first launch.
- **Scope: make the app actually start OpenCode.** Client seam, downloader, launcher, endpoint
  resolution, and an end-to-end device verification.

## Task 1: Client seam

`OpenCodeClient` is welded into the ViewModel's constructor, so the ViewModel cannot be tested and
the endpoint cannot change after construction. The endpoint WILL change — proot picks the port, the
password is generated per install.

- Extract a small interface (e.g. `OpenCodeApi`) covering exactly what the app calls today:
  `health()`, `listSessions()`. `listAgents`/`listCommands` are on the client but unused by the UI —
  decide whether they belong on the interface now or stay off it, and justify the choice.
- `OpenCodeClient` implements it.
- `SessionListViewModel` depends on the interface, not the concrete class, and does not construct
  the client itself. It receives an already-built instance (or a provider) from its factory.
- The ViewModel must still release the real client's resources exactly once.
- Add unit tests for the ViewModel state machine against a fake implementation: connecting → healthy,
  healthy → sessions, health throws → unreachable, 401 → credentials message, list throws after a
  healthy health → its own message, and cancellation is not swallowed.
- No UI changes. No proot work.

## Task 2: Runtime manifest and downloader

- A manifest describing the artifacts the app needs: proot binary + loader + the two shared libs,
  the Alpine rootfs, and the **v2.0.22 musl arm64 OpenCode binary** (see above). Each entry: URL,
  size, and integrity hash.
- The manifest itself needs a known-good source and integrity story — decide and document where the
  manifest is hosted and how it is trusted (spec §9 requires verification of downloaded artifacts).
  Note the OpenCode artifact's own integrity is available straight from npm, so the manifest does
  not have to be the sole source of truth for it.
- A downloader that fetches artifacts, streams to disk in `filesDir`, verifies the hash before
  anything uses the file, and is **resumable**: an interrupted download must not leave a partial
  file that later looks complete. Re-running picks up or discards cleanly. The OpenCode artifact is
  ~70 MB, so this must survive a process kill mid-stream.
- A fake/stub manifest URL must be injectable so this is unit-testable WITHOUT network.
- Tests: hash mismatch is rejected and the file is not used; interrupted download leaves no
  trusted artifact; happy path marks the artifact complete.
- Do NOT write UI. Do NOT wire the ViewModel yet.

## Task 3: Rootfs and binary installation

- Unpack the Alpine rootfs tarball into `filesDir/runtime/rootfs` using a pure-Java tar.gz
  decompressor (no new dependency unless a vetted one is genuinely needed — check the version
  ceiling in AGENTS.md first).
- Unpack/place proot, its loader, `libtalloc`, `libandroid-shmem`, and the opencode binary.
- Extract the opencode binary from its own tarball into the rootfs at the right path.
- **Idempotent and resumable**: re-running after process death must converge to a correct state, not
  corrupt it. Handle the apk lock case documented in the findings.
- Extract `/etc/resolv.conf` (absent from the minirootfs — verified).
- Unit-testable parts (path layout, idempotence of the install plan) should be; the actual exec is
  not unit-testable and will be verified on device in Task 5.
- No UI.

## Task 4: ProotLauncher and server startup

- A launcher that spawns proot with the exact environment and flags that were proven to work on
  device (`-0 --link2symlink -r <rootfs>`, plus `PROOT_LOADER`, `LD_LIBRARY_PATH`, `PATH`, `HOME`,
  `TMPDIR`).
- Starts `opencode serve --hostname 127.0.0.1 --port <chosen>` with
  `OPENCODE_SERVER_PASSWORD` set to a freshly generated secret.
- Captures stdout/stderr to a log the UI can tail (spec §2.2) and surfaces server-death detection.
- Port selection: choose a free loopback port rather than hardcoding. Two installs must not collide.
- Password generation: random per install, never logged, never persisted outside Keystore.
  If Keystore is out of scope for this slice, generate per-process and document that explicitly —
  but do not write it to disk in plaintext.
- **The single most important deliverable in this slice**: it must actually work on the device.
- No UI beyond what is needed to observe it.

## Task 5: Wire it up and verify end to end on device

- Setup screen with a live log, resumable (spec §5.1) — or, if that is too large, drive setup from
  the existing screen and show progress. Decide and justify the smaller option if taken.
- The ViewModel obtains its endpoint from the launcher instead of `ServerEndpoint.PLACEHOLDER`.
- Delete `ServerEndpoint.PLACEHOLDER` once nothing references it.
- **Verify on the connected device**: install, run, and prove the app reaches an OpenCode server it
  started itself. Capture a screenshot. Do not claim this without a device screenshot showing the
  session list rendering real data.
- Update `AGENTS.md` and `docs/phase0-findings.md` with anything new learned.

## Non-goals for this slice

Foreground service, wake locks, notification, streaming/SSE, tool cards, permissions, media,
theming, bottom navigation, Files/Terminal screens. Those are later spec phases.