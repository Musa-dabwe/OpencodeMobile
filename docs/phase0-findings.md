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

- proot executes **from the app's own sandbox** (`filesDir`), not just /data/local/tmp.
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