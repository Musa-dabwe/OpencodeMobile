# OpenCode Mobile — Build Spec (working title)

Native Android app: **Kotlin + Jetpack Compose UI** around **OpenCode v2**, running inside a **proot + Alpine** userspace. The UI talks to OpenCode's own HTTP/SSE API. No terminal scraping, no protocol translation.

Package: `org.musa.<appname>` (name TBD). Target: arm64-v8a only. Sideload build (not Play).

> Items marked **\[VERIFY\]** are unconfirmed from public docs. Resolve them in Phase 0 before building on them. Do not guess; report what you find.

---

## 1. Research findings: how Mobile Harness is built

Source: github.com/techjarves/Mobile-Harness (MIT licensed, v1.0.2). Its own README describes the architecture.

1. **Native host**: Kotlin + Jetpack Compose, Material 3. Tabs: Projects, Chat, Files, Terminal, Preview, Settings.
2. **Runtime**: Ubuntu 20.04 ARM64 rootfs run through **PRoot** (no root). Rootfs is downloaded on first run (\~25 MB) and SHA-256 verified, then unpacked.
3. **Agent**: Claude Code CLI downloaded from Anthropic (\~228 MB) into the rootfs during setup. Forks swap the agent (Pi, DeepSeek, Antigravity, Hermes) through an "agent registry" of drivers.
4. **Bridge**: a **C++ JNI process bridge** (native launcher + pseudo-terminal pipe multiplexer) spawns the agent inside proot. The Chat UI parses the agent's streamed JSON output (the Pi fork uses `pi --mode json`).
5. **Foreground service** holds wake locks so the proot process survives in the background. README recommends exempting the app from battery optimization.
6. **Secrets**: API keys stored with Android Keystore AES-256-GCM.
7. **Provider handling**: Claude Code speaks only the Anthropic protocol. Other providers (OpenAI, Kimi, custom) need an Anthropic-compatible endpoint or a "Pocket adapter" that translates protocols. **This is the source of the API-key/endpoint friction.**
8. **Other features**: Files via SAF, diff review and checkpoints (git), Preview tab = sandboxed WebView on localhost, optional toolchains downloaded on demand (Python, JDK, C/C++, PHP).
9. **Targets API 28** for the sideload build to keep proven exec paths for binaries.
10. Known limits (their README): proot is not a security boundary, heavy builds are slow, ncurses apps glitch.

**What we change**: OpenCode is already a client/server system. The TUI is just one client of an HTTP server with an OpenAPI spec. So we skip the JNI bridge and output parsing. The Compose app is simply another client. OpenCode handles providers and keys natively, so the translation layer disappears.

Reuse: the `runtime/` package (PRoot installer, foreground service) is MIT. Adapting it with attribution is fine.

---

## 2. Architecture

```
┌────────────── Android app (Kotlin + Compose) ──────────────┐
│ UI: Projects · Chat · Files · Terminal · Settings           │
│ ViewModels ── OpenCodeClient (Ktor HTTP + SSE) ──┐          │
│ RuntimeService (foreground) · Keystore           │ 127.0.0.1│
└───────────────────────────────┬──────────────────┼─────────┘
                                │ spawn             │
                     ┌──────────▼───────────────────▼──────┐
                     │ proot → Alpine rootfs (aarch64)      │
                     │   opencode v2 server  (localhost)    │
                     │   git, ripgrep, bash, node (if req.) │
                     └──────────────────┬──────────────────┘
                                        ▼
                               LLM providers (HTTPS)
```

- proot shares the Android network namespace, so the app reaches the in-proot server on `127.0.0.1:<port>`.
- Bind to loopback only. Enable HTTP basic auth with a random per-install password. **Confirmed on v2.0.22**: auth is HTTP Basic, username is exactly `opencode`, password comes from `OPENCODE_SERVER_PASSWORD`. If the variable is unset the server runs unsecured (it only prints a warning), so the app must always set it. Wrong credentials return 401. Password lives in Keystore.

### 2.1 Runtime layout (internal storage)

```
filesDir/
  runtime/proot/        (proot binary shipped as jniLibs/arm64-v8a/libproot.so)
  runtime/rootfs/       (Alpine minirootfs, unpacked)
  workspace/<project>/  (bind-mounted to /workspace/<project>)
  opencode-data/        (bind-mounted to /root/.local/share/opencode and /root/.config/opencode)
```

Persist OpenCode's config/data dirs outside the rootfs so rootfs reinstalls never lose sessions or credentials.

### 2.2 proot launch (baseline, adjust in Phase 0)

Flags: `-0 --link2symlink --kill-on-exit -r <rootfs>`, bind `/dev /proc /sys`, workspace, data dirs, and `/sdcard` (for storage access). Set `HOME=/root`, `TMPDIR=/tmp`, PATH. Use `ProcessBuilder` from the service. Redirect output to a log file the UI can tail.

**The `/dev` bind is load-bearing, not decorative.** The minirootfs ships an empty `dev/`, and without `-b /dev` the OpenCode binary loads, starts, and then aborts (`panic: abort() called`) after ~160 ms. Verified on device: adding `-b /dev -b /proc -b /sys` makes `opencode --version` and `opencode serve` work. The rootfs also needs `libstdc++.so.6`, `libgcc_s.so.1` and a CA bundle copied in — see `docs/runtime-artifacts.md`.

---

## 3. Runtime: Alpine + OpenCode v2

### 3.1 Base image

- Alpine `aarch64` minirootfs, **bundled in the APK** (a few MB). No rootfs download step.
- First-run script (runs once inside proot, with progress shown in the setup screen):
  1. Write `/etc/resolv.conf` (public resolvers, e.g. 1.1.1.1 and 8.8.8.8). **\[VERIFY\]** DNS works from inside proot.
  2. `apk update && apk add bash git curl ca-certificates ripgrep libstdc++ libgcc`
  3. Install OpenCode v2 (3.2).
  4. Write default config (3.4).

### 3.2 Installing OpenCode v2

Findings:

- ~~OpenCode v2 replaced Bun with Node.js~~ **Wrong — verified on device.** v2.0.22 is a **Bun 1.4.2 standalone executable** (`--smol`), as its own crash banner reports. There is no Node.js dependency and no npm install step. It does have a new server API. Install docs list **standalone Linux musl ARM64** binaries, plus npm `@opencode/cli` (beta channel was `@opencode-ai/cli@next`).
- v1 had an Alpine/musl bug in its npm wrapper (binary variant not detected). v2 status unknown.
- A community Termux project reports v2 fails natively on Termux (DNS via c-ares raw UDP) but works inside proot Ubuntu. That is our situation, but with Alpine/musl.

Install order (implement as a fallback chain, stop at first that passes `opencode --version`):

1. **Standalone musl arm64 binary** from the official v2 release. Verify SHA-256. Install to `/usr/local/bin/opencode`. **\[VERIFY\]** exact download URL and checksum source on opencode.ai/v2/docs. **Answered**: the v2 installer maps musl targets to the npm package `@opencode/cli-linux-arm64-musl`; resolve the version from `https://opencode.ai/update/api/latest/cli/npm` and take the checksum from that package's `dist.integrity` / `dist.shasum`.
2. **npm**: `apk add nodejs npm`, then `npm i -g @opencode/cli`. If postinstall picks the wrong (glibc) binary, install the `-musl` platform package and copy the binary into place by hand. **\[VERIFY\]** package names. **Superseded**: the v2 installer resolves musl targets to the npm package `@opencode/cli-linux-arm64-musl` directly, and that package has **no dependencies** — it is a self-contained binary, so Node.js is not required on Alpine. Resolve the current version from `https://opencode.ai/update/api/latest/cli/npm`, which returns `{"package":"@opencode/cli","version":"2.0.22","ref":"refs/heads/v2"}`. npm's per-version `dist.integrity` (sha512) and `dist.shasum` are the checksum source, not a docs page. Unpacked size is ~199 MB, which dominates first-run download time.
3. If both fail on musl, **report it**. Do not silently switch to Ubuntu. (Decision for Musa: Alpine vs Ubuntu fallback.)

The installed binary name may be `opencode` or `opencode2`. **\[VERIFY\]**.

### 3.3 Starting the server

v2 runs as a background service by default; the community wrapper needed `--standalone` inside proot because the background server froze. A Phase 0 run on v2.0.22 (Ubuntu/glibc build, arm64) confirmed the pattern:

```
OPENCODE_SERVER_PASSWORD=<secret> opencode serve --hostname 127.0.0.1 --port <port>
```

with the binary at `node_modules/@opencode/cli-linux-arm64/bin/opencode` after an npm install. The musl build could not run on that glibc host, as expected. **\[VERIFY\]** the same on Alpine.

Requirements:

- listen on `127.0.0.1` only, fixed port chosen by the app
- health check via `GET /api/health` → `{"healthy":true}`. **Not `/api/info`** — that route does not exist and falls through to the SPA catch-all, returning `index.html` with `Content-Type: text/html`. Verified on a live server.
- app restarts it if it dies; service keeps a wake lock while a session is running

### 3.4 Default config

Do not hardcode provider settings. OpenCode reads `~/.config/opencode/opencode.json(c)` (same locations as v1). Seed only what the app needs (e.g. permissions defaults). Provider credentials go through the API (section 6.3).

### 3.5 Network diagnostics (important)

The original pain was keys/connections failing. Add **Settings → Diagnostics** that runs inside proot: DNS lookup, HTTPS request to the active provider endpoint, and `GET /api/provider`. Show a plain pass/fail per step. If Node DNS fails while `curl` works, offer a fix (resolv.conf rewrite, `NODE_OPTIONS=--dns-result-order=ipv4first`; **\[VERIFY\]** which actually helps).

---

## 4. OpenCode API surface the app uses

Base: `http://127.0.0.1:<port>/api/...`. OpenAPI spec: `https://opencode.ai/v2/openapi.json` (136 operations, 245 schemas). **Hand-write a typed Kotlin client for the subset below** with Ktor + kotlinx.serialization, generated/checked against the pinned spec. Many routes are labelled *experimental*; **pin the OpenCode version and store the spec version in the repo**. Beta changes have already broken integrations.

| Need | Endpoint |
| --- | --- |
| health/identity | `GET /api/health` (see note above — `/api/info` does not exist) |
| sessions | `GET/POST /api/session`, `GET/PATCH/DELETE /api/session/{id}` |
| send message | `POST /api/session/{id}/prompt` (`text`, `files[]`, `agents[]`, `skills[]`) |
| history (paged) | `GET /api/session/{id}/message` (cursor, `type` filter) |
| context | `GET /api/session/{id}/context` |
| stop | `POST /api/session/{id}/interrupt` |
| child sessions (sub-agents) | **No children-listing route exists.** `Session.Info` carries an optional `parentID`, so the tree is built client-side by grouping `GET /api/session` on it. Verified on a live server (50 sessions, 34 with a parent) |
| fork / compact | `POST .../fork`, `POST .../compact` |
| slash commands | `GET /api/command`, `POST .../command` |
| shell in session | `POST /api/session/{id}/shell` |
| model / agent | `GET /api/model`, `GET /api/agent`, `POST .../model`, `POST .../agent` |
| providers + keys | `GET /api/integration`, `POST /api/integration/{id}/connect/key`, OAuth routes |
| permissions | `GET/POST .../permission`, `POST .../permission/{req}/reply` |
| agent questions | `GET/POST .../form`, `POST .../form/{id}/reply` |
| diffs / revert | `GET .../diff`, `POST .../revert/stage`, `.../revert/commit`, `DELETE .../revert` |
| files | `GET /api/fs/read/*`, `/api/fs/list`, `/api/fs/find` |
| terminal | `/api/pty` (create, WebSocket connect token) |
| live events | `GET /api/event` (SSE; **confirmed path on v2.0.22** — `/event` is not the stream); durable replay: `GET /api/experimental/session/{id}/log?after=&follow=true` |
| MCP | `GET /api/mcp` and experimental add/connect/disconnect |
| git | `GET /api/vcs/status`, `/api/vcs/diff` |

**Resilience**: Android will drop SSE connections when backgrounded. On reconnect, resume from the session log using the last seen sequence (`after=`) instead of reloading everything. Keep the last sequence per session in memory and persist on pause.

---

## 5. Screens

Bottom nav (6 tabs): **Projects · Chat · Files · Terminal · Agents · Settings**. **Design principle: the UI follows the refined design in `docs/ui/OpenCode Mobile – UI design.pdf`**, replacing the earlier OpenCode Desktop port. Reuse its layout, labels, control types and settings structure wherever the phone form factor allows (section 5.8).

> **Amended** — this originally read `Projects · Chat · Files · Terminal · Settings` and pointed at `opencode-harness-mockups.html`. The owner ruled that the **Terminal tab stays** and the refined design's **Agents** tab is added, giving 6 tabs. The mockups were replaced by the design PDF; the extracted tokens live in `app/src/main/assets/themes/opencode-mobile.json`. See `docs/plans/phase3-ui.md`.

### 5.1 First-run setup

3 steps, single screen with live log like Mobile Harness: (1) extract runtime, (2) install OpenCode, (3) connect a provider. Must be resumable after process death.

### 5.2 Projects

List of workspaces (name, path, last activity). "New project", "Quick project". A project maps to a directory under `workspace/` passed as the session location.

- **Quick project**: creates a real directory under `workspace/` and opens a chat. The first prompt is used to generate a short title through OpenCode (it already generates session titles; **\[VERIFY\]** whether to reuse that or call `POST /api/experimental/generate`). The directory is renamed to a slug of the AI title and the project card shows the title. The session is a normal OpenCode session located in that directory.
- **Shortcut projects**: a project opened from the file manager (5.4) is stored as a link entry in the projects registry (path + display name), not copied or moved. Card shows a "shortcut" badge and the real path. Deleting a shortcut never deletes the folder.

### 5.3 Chat (main screen)

- Session switcher (sheet), new/fork/compact in overflow.
- Top bar chips: **agent**, **model** (tap to switch), context usage if the API exposes it.
- Message list and composer. Below the input, like Desktop: agent dropdown, model picker, variant picker.
- **Pasting images**: paste from the keyboard clipboard (use `ContentReceiver` / `Modifier.contentReceiver` so keyboards, Gboard clipboard and share-to-app can insert images), plus picker and camera. Pasted images appear as removable thumbnails above the input and are sent in `files[]` (6.4). Also accept images shared into the app from other apps.
- **Slash commands**: typing `/` at the start of the input opens a filtered list above the composer. It must include **all built-in OpenCode commands** (e.g. `/new`, `/models`, `/compact`, `/undo`, `/redo`, `/share`, `/unshare`, `/export`, `/init`, `/connect`, `/help`; **\[VERIFY\]** the complete list and which are applicable on mobile via the API), **custom commands** from `.opencode/commands/` and `~/.config/opencode/commands/`, **skills** from `.opencode/skills/` and `~/.config/opencode/skills/`, and **MCP prompts**. Do not hardcode the list: load it from `GET /api/command` and the skills endpoint, and show a small badge (custom / skill / mcp) like Desktop does. Commands that open pickers (`/models`, `/sessions`) open the matching bottom sheet instead of sending text.
- `@` file picker (`/api/fs/find`), `!` shell prefix, send/stop toggle.
- Pending **permission** and **form** requests render as inline cards pinned above the composer with Allow once / Always / Deny (map to the API's reply decisions).
- Changes button opens the diff view for the latest turn.

### 5.4 Files (file manager)

A Termux-style file manager as its own tab. Roots: Workspace, Downloads, Phone storage (`/sdcard`, requires storage permission), rootfs home. Breadcrumb navigation, list rows with icon, name, item count, git marker and number of chats in that folder.

- **Long-press a folder** opens a context menu: **Rename, Copy, Move, Open in chat, Open as project, Info, Delete** (Delete confirms, shows item count).
- **Open in chat**: starts a new session whose working directory is that folder and switches to Chat.
- **Open as project**: adds a shortcut to the folder in Projects (5.2) and shows a snackbar "Shortcut added to Projects" with View. No copy, no move.
- Info: path, size, items, modified, git branch if any, sessions in this folder.
- Files: tap opens a viewer (syntax highlight, image/audio/video preview). Long-press files gets the same menu minus "Open as project". Multi-select, paste (copy/move target), and new file/folder via the + button.
- File operations run through the proot filesystem path for rootfs/workspace and through direct file APIs for `/sdcard`. Moves across the two are copy then delete, with progress.

### 5.5 Terminal

Preferred: OpenCode's PTY API over WebSocket. Needs a terminal emulator view; choose a library in Phase 0 (extra-keys bar: Esc, Ctrl, Alt, Tab, arrows, like the Mobile Harness screenshot). Fallback: plain proot shell via `ProcessBuilder`.

### 5.6 Settings

Port OpenCode Desktop settings as top tabs **General · Shortcuts · Providers · Models** (as seen in Desktop; **\[VERIFY\]** exact rows against the Desktop source in `packages/app`, and port labels, ordering and control types one to one).

- **General**: color scheme (System/Light/Dark), theme, font, notifications (agent finished, permission requests, errors), sound effects. Add a mobile-only **Runtime** group: full storage access, restart/reinstall/update OpenCode (checksum verified), diagnostics, battery-optimization prompt, optional toolchains (`apk add` packs: python3, openjdk17, build-base, php).
- **Shortcuts**: shown for hardware keyboards; list Desktop actions and allow rebinding.
- **Providers**: Connected / Popular providers lists with Connect buttons (API key or OAuth), exactly like Desktop.
- **Models**: model visibility toggles per provider.

### 5.7 Provider / API key flow

List integrations from the API. For key-based providers, send the key to `POST /api/integration/{id}/connect/key`. **OpenCode stores the credential itself.** The app never translates protocols. For OAuth providers use the begin/poll/complete routes and open the authorization URL in a Custom Tab. "Test" button = a one-shot `POST /api/experimental/generate` with a tiny prompt and show the real error text on failure.

---

### 5.8 Port rule

The reference is the OpenCode Desktop app (source: the OpenCode repo, `packages/app` and `packages/desktop`). Phase 0 must clone it and produce `docs/desktop-port-map.md`: for each Desktop screen, dialog, button and settings row, the Compose composable that will replace it. Keep Desktop's naming, ordering, icons and behavior; adapt only where touch or screen size requires (e.g. sidebar becomes a bottom sheet, tabs become the session switcher).

- Branding: no references to other vendors' agents anywhere in the UI. The app is OpenCode only.

### 5.9 Sub-agents (first-class feature)

OpenCode lets a primary agent delegate work to sub-agents, each running in its own child session. The app must support sub-agent driven development end to end, not just show it. Sub-agents are sessions inside the same server process, so running several in parallel costs tokens and network, not extra Android processes.

#### Agent management (Settings → Agents, also reachable from the agent picker)

- List agents from `GET /api/agent`, grouped Primary / Sub-agent, with built-in vs custom marked.
- Create, edit and delete custom agents. Agents are markdown files with frontmatter in `.opencode/agents/` (project) and `~/.config/opencode/agents/` (global); **\[VERIFY\]** the v2 file format and fields against the docs. Editor fields: name, description, mode (primary / subagent), model override, allowed tools and permissions, system prompt. Save by writing the file, then refresh the list.
- "Start from template": ships four optional starter agents (explorer: read-only codebase search; implementer; reviewer; tester). Created only on tap, never seeded automatically.

#### Invoking

- `@agent-name` in the composer (separate "Agents" group above files in the @ picker). Sends the name in `agents[]` of the prompt.
- Primary agents also delegate on their own; the UI must render that identically.
- Optional per-agent model override picker in the agent editor.

#### Chat treatment

- Each delegation renders as a sub-agent card: agent name, task title, status (queued / running / done / failed), elapsed time, tool count, and a collapsed live timeline of its tool calls. Running state uses the shimmer bar.
- Parallel sub-agents stack as separate cards; the top bar shows a pill "N agents running" that opens a bottom sheet listing them with per-agent Stop.
- Tap a card, or "Open session", to open the child session full screen with a back arrow to the parent and a breadcrumb "Parent › agent". Child sessions appear nested under their parent in the session switcher (tree by parent id).
- Permission and question cards raised by a sub-agent are labeled with the agent's name and appear in the parent chat so the user is never blocked silently.
- Stopping the parent interrupts its children; individual children can also be interrupted (`/interrupt` on the child session).
- Results: when a sub-agent finishes, show its summary inline in the card; the parent's following message continues the conversation as normal.

#### Android behavior

- Foreground notification text reflects activity, e.g. "2 agents running"; completion notification names the agent.
- Cap concurrent visible live timelines (default 3 expanded) to keep Compose recomposition cheap; others show headline only.
- Child sessions are included in the SSE stream and in the session log replay, so reconnecting restores their state.

**Acceptance**: from a prompt like "use the explorer agent to find where retries happen, then have the implementer add backoff", the user sees two sub-agent cards, can open either child session, answer a permission prompt raised by one, and stop one while the other continues.

---

## 6. Rich chat rendering

Message types from the API: `user`, `assistant`, `system`, `synthetic`, `skill`, `shell`, `compaction`, `agent-switched`, `model-switched`, `location-switched`. Render each distinctly.

### 6.1 Text

- Streaming Markdown: headings, lists, tables, blockquotes, inline code, links, task lists. Re-parse incrementally without flicker (only update the last block).
- Code blocks: language label, copy button, horizontal scroll (never wrap), syntax colors from theme tokens.
- Reasoning/thinking sections collapsed by default with a shimmer while streaming.

### 6.2 Tool calls

Collapsible cards with icon, title, status (pending / running / done / error), duration. Specialized bodies:

- **shell / bash**: monospace output, exit state, stop button if running.
- **edit / write**: inline diff using `diff.*` tokens, tap for full diff view.
- **read / grep / find**: compact file list with tap-to-open.
- **subagent**: rich card, see 5.9.
- Unknown tools: pretty-printed JSON input/output.

### 6.3 Media (image, audio, video)

Detect media in three places: (a) user attachments, (b) markdown image/link syntax in assistant text, (c) file paths in tool results or `write` outputs with media extensions. Resolve workspace paths through `GET /api/fs/read/*` (authenticated) and remote URLs directly.

- **Image**: Coil, rounded card, tap for fullscreen pinch-zoom viewer, long-press save/share.
- **Audio**: inline player card (play/pause, seek, duration, waveform optional) on Media3.
- **Video**: inline thumbnail with play overlay, fullscreen player with Media3 controls.
- Files larger than a threshold: show a file card with Open/Share instead of inlining.
- Note: the model only "outputs" media if a tool or MCP server produces files or URLs. The UI's job is to render them when they appear.

### 6.4 Attachments in prompts

Use `files[]` in the prompt body. Two shapes exist, both confirmed from the v2 OpenAPI schema: `PromptInput.FileAttachment` requires **`uri`** (not `path`), plus optional `name`/`description`/`mention`; a separate `Prompt.FileAttachment` takes inline `data` (base64) + `mime` + `source`. Copy picked Android content URIs into `workspace/.attachments/` first, then reference them.

---

## 7. Visual design: OpenCode look

OpenCode V2 themes are a JSON token tree: `text`, `background` (with `raised` depth levels), `border`, `scrollbar`, `diff`, `syntax`, `markdown`, plus hue palettes for light and dark (gray, red, orange, yellow, green, cyan, blue, purple, with `accent`, `interactive`, `neutral` aliases, steps 100–900). Built-ins include `opencode`, `tokyonight`, `catppuccin`, `gruvbox`, `nord`, `dracula` and more.

Implementation:

1. Build a `OpenCodeTheme` Kotlin model mirroring that token tree, exposed through a `CompositionLocal`, bridged to Material 3 `ColorScheme`.
2. **Do not invent hex values.** ~~Extract the built-in `opencode` theme (dark and light) from the OpenCode source or package during Phase 0 and ship it as `assets/themes/opencode.json`.~~ **Amended:** the refined design PDF (`docs/ui/`) is now the source of truth. Tokens were sampled from its rendered pages at 150 dpi and ship as `assets/themes/opencode-mobile.json`; the design is **dark only**, so no light palette is invented. The loader must still accept the V2 theme file format (hex, `transparent`, `$hue.x.y` references, state keys like `$hovered`) so a real OpenCode theme can be dropped in unchanged.
3. Users can import additional theme JSON files and pick from bundled ones. Default: the refined design, **dark only for now** (was `opencode`, mode `system`).
4. Typography: **system typeface throughout, by owner decision — no font is bundled.** Monospace (`FontFamily.Monospace`) for code, tool cards and terminal; the system sans (`FontFamily.Default`) for everything else. No downloadable fonts, no bundled `.ttf`, no Google Fonts dependency, so there is no font licence to check.
5. Surfaces: use `background.raised` levels for cards, sheets and dialogs. Keep borders 1 dp, low contrast. Generous touch targets (48 dp min).

### Animations (subtle, purposeful)

- Streaming: soft caret at the end of the growing text; per-block fade-in, no per-character jitter.
- Tool cards: expand/collapse with animated height; running state has a slow shimmer bar; done state settles to a checkmark.
- Thinking: shimmering placeholder line.
- Message arrival: 150 ms slide and fade. Auto-scroll only when the user is at the bottom; otherwise show a "jump to latest" pill.
- Permission/form cards: spring-in above the composer.
- Setup progress: determinate bars with smooth interpolation.
- Respect the system "remove animations" setting.

---

## 8. Android lifecycle and reliability

- `RuntimeService` (foreground, type `dataSync` or `specialUse`, **\[VERIFY\]** which is accepted for the target SDK) with a persistent notification showing server status and a Stop action.
- Wake lock only while a session is running or terminal is open.
- Android 12+ may kill proot child processes (phantom process limits). Keep the process tree small: one OpenCode server, no extra daemons. Document the ADB workaround in an in-app help page, do not require it.
- Detect server death, show a banner, offer one-tap restart. Never lose a prompt: the API "durably admits" inputs, so on reconnect check `GET .../inbox` before resending.
- Storage: request `MANAGE_EXTERNAL_STORAGE` only if the user enables "full storage access"; otherwise bind app-specific storage. Offer SAF import/export for projects.

---

## 9. Security

- Server bound to loopback, basic auth with random password (Keystore).
- Provider keys held by OpenCode in its own data dir inside app-private storage. Do not log keys. Redact in diagnostics.
- Verify SHA-256 for every downloaded artifact (OpenCode binary, optional packs).
- Show the proot caveat (not a hardened sandbox) in onboarding.
- Tool permissions default to **ask**; the UI must make Allow/Deny obvious and show the exact command or path.

---

## 10. Build plan

**Phase 0 — spikes (report findings, no UI):**

1. Launch proot + Alpine from a bare Compose app; `apk add` works; DNS works.
2. Install OpenCode v2 via 3.2; capture the working command, version, binary name.
3. Start the server headless; `curl /api/health` from the app; confirm auth flag.
4. Connect a provider with a key via the API; send a prompt; watch the SSE stream; dump real event and message JSON samples into `docs/samples/`.
5. Extract the `opencode` theme tokens.
6. Test PTY WebSocket from the app.

**Phase 1:** runtime service, setup flow, typed client, Chat with streaming text, tool cards, permissions, provider setup, theme. **Phase 2:** media rendering, attachments, diffs and revert, Files, Terminal, session management, sub-agent cards, child-session navigation and agent management (5.9). **Phase 3:** settings packs, diagnostics, theme import, polish and animation pass.

> **Amended — this phase numbering is stale.** It predates the working plan files in `docs/plans/`, which are the authority on sequencing: `phase1-slice.md` (client, tree, minimal screen) → `phase2-runtime.md` (download, install, launch — **complete**) → `phase3-ui.md` (theme from the design PDF, 6-tab shell, Sessions, Settings). The "Phase 3" described above (settings packs, diagnostics, theme import, polish) remains **unstarted** and is not the same thing as `phase3-ui.md`.

Acceptance for Phase 1: fresh install → setup completes offline-resumable → key entered → prompt streams → file edited in workspace → diff visible → permission prompt answered.

## 11. Non-goals

No Play Store build, no Docker/KVM, no custom model protocol translation, no JNI terminal bridge unless the PTY API fails, no iOS.

## 12. Deliverables

Gradle project, `docs/ARCHITECTURE.md`, `docs/samples/` (real API JSON), `docs/phase0-report.md`, signed debug APK per phase. Commit messages clear; one phase per PR.
