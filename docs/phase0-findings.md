# Phase 0 Findings — verified against a live OpenCode server and a real device

Date: 2026-10-02.

Two verification passes were run:

1. **Host pass** — downloaded the official `opencode-linux-x64` release, ran
   `OPENCODE_SERVER_PASSWORD=… opencode serve --hostname 127.0.0.1 --port 45123`, probed with curl.
2. **Device pass** — ran proot + Alpine on a real arm64 Android 12 phone (OPPO Reno 3 / PCLM50,
   SDK 31) inside the app sandbox, and took the runtime all the way to `apk add`.

---

## Part 1 — API (host pass)

Verified server was **v1.18.34**, not v2. Cross-checked against
`https://opencode.ai/v2/openapi.json`.

### Confirmed

| Item | Result |
| --- | --- |
| `serve` command | `OPENCODE_SERVER_PASSWORD=<pw> opencode serve --hostname 127.0.0.1 --port <port>` works |
| Auth | HTTP Basic, user literally `opencode`. Missing creds → 401. Wrong creds → 401 |
| SSE | `GET /api/event` streams `data: {"id","type","data"}` plus `: heartbeat` keepalives |

### Corrections to the spec

1. **`GET /api/info` is NOT a usable health check.** It returns the SPA's `index.html` with
   `Content-Type: text/html` — the route does not exist and falls through to the SPA catch-all.
   Use **`GET /api/health`** → `{"healthy":true}`. The spec names `/api/info` as both the health
   check and the "server identity" call; it does not work.

2. **There is no children-listing route.** The spec marks this `[VERIFY]`. `Session.Info` simply
   carries an optional `parentID`, so the tree is built **client-side** from `GET /api/session`.
   Verified live: 50 sessions, 34 with a `parentID`, 16 roots, 3 distinct parents.

3. **Attachments use `uri`, not `path`.** Two shapes exist:
   - `PromptInput.FileAttachment` — `{ uri (required), name, description, mention }`
   - `Prompt.FileAttachment` — `{ data (base64, required), mime (required), source, name, … }`
   So "copy into `workspace/.attachments/` and reference by path" is only half the story.

4. **Response envelopes DIFFER per endpoint** — this is the subtlest trap:
   - `/api/session` → `{"data":[…],"cursor":{…}}` — **no `location`**
   - `/api/agent`, `/api/command`, `/api/model`, `/api/fs/list` → `{"location":{…},"data":[…]}`
   Both keys are optional in our `Envelope` so either decodes. A bare-array decode fails against both.

5. **Agents have NO `name` field** (0 of 32). Fields present: `id`, `request`, `system`,
   `description`, `mode`, `hidden`, `permissions`. `mode` is `primary` / `subagent` — the field
   the sub-agent UI depends on. So `name` must be nullable with `displayName` falling back to `id`.

6. **A session's own `location` has ONLY `directory`** — no `project`. But the envelope-level
   `location` (agent.json) DOES have `{directory, project}`. One type must decode both.

7. Every other endpoint in the spec's table exists: `/api/session`, `/prompt`, `/message`,
   `/context`, `/interrupt`, `/fork`, `/compact`, `/command`, `/shell`, `/model`, `/agent`,
   `/api/integration`, `…/connect/key`, `/permission`, `/form`, `/diff`, `/revert/*`,
   `/api/fs/read/*`, `/api/fs/list`, `/api/fs/find`, `/api/pty` (+ `/pty/{id}/connect`,
   `/pty/{id}/connect-token`), `/api/mcp`, `/api/vcs/status`, `/api/vcs/diff`, `/api/provider`,
   `/api/skill`, `/api/experimental/session/{id}/log`.

8. Spec drift: the v2 spec reports 117 paths / 141 operations / 256 schemas; the spec document
   says 136 / 245. Pin the exact copy used.

Real captured responses are committed in `docs/samples/`.

---

## Part 2 — Runtime (device pass)

**Everything the spec listed as unverified device work is now verified working.**

Device: OPPO Reno 3 (PCLM50), Android 12, SDK 31, arm64-v8a.

### Verified working

- proot executes, but **NOT from `filesDir`**. See the Task 5 correction below: the app
  cannot `execve()` its own home directory, so proot ships in `jniLibs` and runs from
  `nativeLibraryDir`. The original claim here was true of a `run-as` probe, not of the app.
- Alpine 3.21.3 aarch64 minirootfs unpacks and boots; `/bin/sh`, busybox, `uname` work.
- `/etc/resolv.conf` must be written by us — it is absent from the minirootfs.
- DNS resolves (dl-cdn.alpinelinux.org → 151.101.2.132).
- **HTTPS works**: `apk update` fetched indexes, 25,262 packages available.
- **`apk add --no-cache bash git curl ca-certificates ripgrep` succeeds** (27 MiB, 35 packages):
  - GNU bash 5.2.37 (aarch64-alpine-linux-musl)
  - git 2.47.3
  - curl 8.14.1 (OpenSSL 3.3.3, c-ares 1.34.8)
  - ripgrep 14.1.1

### Hard-won runtime requirements (these WILL bite a naive implementation)

1. **`PROOT_LOADER` must be set.** The Termux proot build hardcodes
   `/data/data/com.termux/files/usr/libexec/proot/loader`. Run from our sandbox it cannot find it
   and fails with `the loader was not found or doesn't work`. Ship our own `loader` and set
   `PROOT_LOADER=<path>` or nothing runs.

2. **`LD_LIBRARY_PATH` must include the directory holding the proot libs.** The Termux proot
   binary needs `libtalloc.so.2` and `libandroid-shmem.so`, which are not in Android's default
   search path. Without them: `CANNOT LINK EXECUTABLE`.

3. **`/data/local/tmp` cannot exec musl binaries** (rc=126). The runtime must live in the app's
   own `filesDir`. This is not a permissions problem — the same binary runs fine there.

4. **Environment must be set explicitly**, or the guest inherits Android's:
   - `PATH=/bin:/sbin:/usr/bin:/usr/sbin` — otherwise `cat`, `ls`, `apk` are all "not found"
     because the host PATH points at Android system dirs.
   - `HOME=/root`, and `TMPDIR` inside the rootfs.

5. **`apk` needs its lock cleared after an interrupted run**, else:
   `ERROR: Unable to lock database: temporary error`. Remove `lib/apk/db/lock` on resume.
   The setup flow is explicitly required to be resumable after process death — this is the
   concrete failure it must handle.

6. **The app must declare `android.permission.INTERNET`.** It did not, and without it Android
   denies every socket. Added.

7. **Do not diagnose network from `adb shell run-as`.** `run-as` runs in a different SELinux
   domain that lacks the `inet` group, so it reports `Operation not permitted` on every socket
   while the app itself has full network. Verified inside the real app process:
   `TCP_443_OK`, DNS OK, group `3003` (inet) present. **Any future network diagnostic must run in
   the app's own context** — this is precisely what spec §3.5's Diagnostics screen requires.

8. Useful flags confirmed working: `-0 --link2symlink -r <rootfs>`; `PROOT_NO_SECCOMP=1` was
   set defensively but was not required (TCP failed identically without it during diagnosis).

### Still open (need more work, not blocked)

- OpenCode v2 **musl arm64** binary install — the fallbacks and exact download URL remain
  `[VERIFY]` per spec §3.2. We now have a working Alpine environment to test it in.
- PTY WebSocket from the Android client.
- opencode theme token extraction.

---

## Part 3 — Phase 2 corrections (Task 5, verified on the Reno 3)

Three findings here were wrong, or right for the wrong reason. Each was found by running the
real app on the device, not by reading the code.

### 1. proot CANNOT be exec'd from `filesDir` (the old claim above was wrong)

`ProcessBuilder.start()` on a binary in the app's own home directory fails with:

```
failed to spawn proot: Cannot run program ".../files/runtime/proot/proot": error=13, Permission denied
```

The same binary, with the same mode (`-rwxr-xr-x`) and the same SELinux label
(`app_data_file`), runs fine under `adb shell run-as`. That asymmetry is the whole tell:
`run-as` sits in a different domain, and the platform refuses `execve()` of an app's home
directory for apps targeting API 29+. **Part 2's "proot executes from the app's own sandbox"
was an over-read of a `run-as` probe** — the same class of mistake as item 7 in that list.

The fix, and what it costs:

- proot and its loader ship in `jniLibs/arm64-v8a/` as `libproot.so` and `libprootloader.so`.
  265 KB in the APK, against an 89 MB artifact that still downloads on first run.
- `android:extractNativeLibs="true"` is REQUIRED. API 30+ otherwise loads `.so` straight out
  of the APK and leaves `nativeLibraryDir` **empty**, so there is nothing to execute.
- `nativeLibraryDir` is `system`-owned and **not app-writable** (`Permission denied` on
  `mkdir`/`touch`/`cp` as the app), so copying at setup time is not an option. Shipping the
  file is the only route in.
- The two shared libraries (`libtalloc.so.2`, `libandroid-shmem.so`) are **dlopen'd** via
  `LD_LIBRARY_PATH`, never executed, so they stay in `filesDir/runtime/proot`. Only the two
  binaries that are executed have to move.

### 2. On v2, the health route is the INVERSE of what v1 showed

Measured against the real v2.0.22 binary with HTTP Basic auth (`opencode` / generated password):

| Route | v1.18.34 (what the notes said) | v2.0.22 (measured) |
| --- | --- | --- |
| `GET /api/health` | `{"healthy":true}` | **404, empty body** |
| `GET /api/info` | did not exist; SPA `index.html` | **200** `{"version":"2.0.22","pid":…,"urls":[…],"paths":{…}}` |

Unauthenticated, `/api/health` returns 401 (it sits behind the auth middleware) and
`/api/info` returns 200 with the SPA's HTML — which is exactly why the v1 warning existed and
why a status-only probe is unsafe.

The client now probes `/api/health` first and falls back to `/api/info` **only** on a 404. The
SPA failure mode is closed structurally rather than by trusting the route name: the body is
decoded into `ServerInfo`, whose `version` is non-null, so HTML throws during deserialisation.
A 200 is never sufficient on its own. The real response is captured in `docs/samples/info.json`.

**Generalisation: every API shape in Part 2 was verified against v1.18.34. Treat all of them
as unverified for v2 until measured against the real binary.**

### 3. The child environment being INHERITED (Task 4's judgement call) works

Task 4 flagged "inherit with six keys overwritten" as never device-verified. It is now verified:
with `PROOT_LOADER`, `LD_LIBRARY_PATH`, `PATH`, `HOME`, `TMPDIR` and `OPENCODE_SERVER_PASSWORD`
overwritten on an inherited environment, the server starts and serves. Replacing the
environment wholesale was **not** needed and was not tried.

Two harmless warnings appear at startup and are not failures:

```
proot warning: can't canonicalize /data/data/com.termux/files/usr/tmp/: No such file or directory
proot warning: Unable to create temp directory for f2fs bug probe: No such file or directory
```

### 4. Server death could not be forced from outside the app

`adb shell run-as ... kill -9 <server pid>` fails with `Permission denied`: `runas_app` cannot
signal processes in the app's own domain. So the `ServerState.Dead` UI path could not be forced
from adb. What *was* verified is the in-app kill path — after a force-stop and relaunch the app
reaped the orphan and picked a new port (38579 -> 40075), so the old port was genuinely freed.
