# Phase 0 Findings — verified against a live OpenCode server

Date: 2026-10-02. Method: downloaded the official `opencode-linux-x64` release, ran
`OPENCODE_SERVER_PASSWORD=… opencode serve --hostname 127.0.0.1 --port 45123`, and
probed it with curl. Cross-checked against `https://opencode.ai/v2/openapi.json`.

## Environment caveat (important)

- The verified server is **v1.18.34**, not v2. The spec targets v2. The v2 route table
  below is taken from the official v2 OpenAPI spec; the live server confirms the
  *response shapes* and auth behaviour, and most routes exist in both.
- No Android device was attached and the host is **x86_64 / 11 GB RAM**. Nothing that
  requires arm64 + proot was executed. Those items stay `[VERIFY]`.

## Confirmed

| Item | Result |
| --- | --- |
| `serve` command | `OPENCODE_SERVER_PASSWORD=<pw> opencode serve --hostname 127.0.0.1 --port <port>` — works |
| Auth | HTTP Basic. Missing creds → `401`. Wrong creds → `401`. Correct creds → JSON. Username `opencode` |
| Auth is required | With the password env var set, unauthenticated requests are rejected. Confirms the app must always set it |
| SSE | `GET /api/event` streams `data: {"id":…,"type":…,"data":…}` plus `: heartbeat` keepalives |

## Corrections to the spec

1. **`GET /api/info` is not usable as a health check.** It returns the SPA's `index.html`
   with `Content-Type: text/html`, not JSON — the route does not exist on the server and
   falls through to the SPA catch-all. Use **`GET /api/health`** → `{"healthy":true}`.
   This is the single most important correction: the spec names `/api/info` as both the
   health check and the "server identity" call, and it does not work.

2. **There is no children-listing route.** The spec marks this `[VERIFY]`. The v2 spec
   has no `/session/{id}/children` route. Instead `Session.Info` carries an optional
   `parentID`, so the tree is built **client-side** by fetching `GET /api/session` and
   grouping on `parentID`. Verified live: 50 sessions, 34 with a `parentID`.

3. **Attachments use `uri`, not `path`.** The spec marks this `[VERIFY]`. Two shapes exist:
   - `PromptInput.FileAttachment` — `{ uri (required), name, description, mention }`
   - `Prompt.FileAttachment` — `{ data (Base64, required), mime (required), source, name, … }`
   So the spec's "copy into `workspace/.attachments/` and reference by path" is only
   half the story: a URI is required, and an inline-base64 variant also exists.

4. **Every other endpoint in the spec's table exists.** Verified present in the v2 spec:
   `/api/session`, `/prompt`, `/message`, `/context`, `/interrupt`, `/fork`, `/compact`,
   `/command`, `/shell`, `/model`, `/agent`, `/integration`, `/integration/{id}/connect/key`,
   `/permission`, `/form`, `/diff`, `/revert/*`, `/fs/read/*`, `/fs/list`, `/fs/find`,
   `/pty` (+ `/pty/{id}/connect`, `/pty/{id}/connect-token`), `/mcp`, `/vcs/status`,
   `/vcs/diff`, `/provider`, `/skill`, `/experimental/session/{id}/log`.

5. **Envelope shape.** Most list endpoints return
   `{ "location": {…}, "data": [ … ] }` — note the sibling `location` key alongside `data`.
   The typed client must model both; treating the response as a bare array will break.

6. Spec drift: the v2 spec at `opencode.ai/v2/openapi.json` reports 117 paths / 141
   operations / 256 schemas. The spec document says 136 / 245. Pin the exact copy used.

## Still `[VERIFY]` — need arm64 + proot on a device

- proot + Alpine boot, `apk add`, DNS from inside proot
- the musl arm64 OpenCode binary vs the npm fallback
- `OPENCODE_SERVER_PASSWORD` behaviour on the musl build specifically
- PTY WebSocket from an Android client
- the opencode theme token extraction (v2 theme JSON; needs the v2 package)
