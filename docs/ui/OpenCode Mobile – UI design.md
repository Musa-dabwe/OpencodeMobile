# OpenCode Mobile — UI design

**Status:** refined design, replaces the Phase 1 Material 3 mockups.
**Source of truth:** `OpenCode Mobile – UI design.pdf` (10 pages, in this folder). This file is the
extractable companion — every colour, type role, and component below was sampled or read out of that
PDF, not invented. When the two disagree, the PDF wins.

Supersedes `docs/specs/OpenCode Harness – UI mockups.md` and `docs/ui/OpenCode Harness – UI mockups.html`,
both of which described the earlier Material 3 layout and have been removed. **Every API shape in those
old mockups was measured on OpenCode v1.18.34 and is not valid for v2** (see `docs/phase0-findings.md`).

---

## 1. Design system

### Surfaces

| Token | Hex | Use |
|---|---|---|
| `bg` | `#0C0C0E` | page background |
| `surface` | `#151517` | search field, inset cards, keyboard keys |
| `surfaceRaised` | `#1A191E` | elevated card, modal sheet |
| `hairline` | `#232227` | 1px dividers between card rows |
| `sheetScrim` | `#0A0A0C` @ 60% | behind the approval sheet |

### Accents

| Token | Hex | Use |
|---|---|---|
| `accent` | `#FAB282` | the ONE primary action colour: send button, "New session", "Allow once", "Return to parent", server-running dot |
| `statusIdle` | `#7DD88E` | idle / healthy / done |
| `statusRunning` | `#6A4F3C` | running / in-progress (muted amber-brown) |
| `statusError` | `#E96776` | provider error, reject |
| `agentBlue` | `#568FDA` | custom/primary agent name chips |
| `actionBlue` | `#5389CC` on `#203552` | Rename swipe action |
| `actionRed` | `#E46A72` on `#3A161A` | Delete swipe action |

**Rule:** accent is reserved for the single primary action on a screen. Status colours carry state.
There is no second "brand" colour — the peach is the only warm hue in the system.

### Type

| Role | Treatment |
|---|---|
| Screen title | Large, bold, tight tracking (`Sessions`, `Agents`, `Settings`) |
| Section header | `TODAY` / `EARLIER` / `MODELS` / `SAFETY` / `APP` — small, uppercase, wide tracking, muted `#45444A` |
| Primary row | Bold, near-white `#ECEBF0` |
| Meta row | Regular, muted — `musa-api · running · 2 subagents`, always `·`-separated |
| Monospace | file paths, `package/bin/opencode`, diffs, command output, `--use-system-ca` |
| Inline code chip | monospace on `surface` with a hairline border, slight inset |

### Shape & depth

- Cards: `#0C0C0E` on `#0C0C0E` page, separated by **hairline dividers, not shadows**. Elevation is
  expressed with a 1px border and a slight surface lift, never a drop shadow.
- Primary action buttons: full-pill (`capsule`), accent fill, dark `#1A1A1A` label.
- Search field: `surface` fill, hairline border, magnifier icon, rounded ~12dp.
- Swipe actions: full-bleed rectangles that reach the card edge — not rounded pills.

---

## 2. Navigation

Bottom bar, 5 tabs, on every root screen (Pages 6, 8, 10):

```
Projects   Chat   Files   Agents   Settings
```

Icon above a small label. The active tab is `accent`; inactive are muted `#45444A`.
**Note:** this is `Projects / Chat / Files / Agents / Settings` — the old mockups had
`Projects / Chat / Files / Terminal`, and `Agents` did not exist at all.

---

## 3. Screens

### 3.1 Sessions (page 5)

- Header: back chevron, title `Sessions`, and an accent-outlined pill button `This project` (a scope
  filter, top-right).
- Search field: `Search sessions`, full width, below the header.
- Rows grouped under `TODAY` and `EARLIER` section headers.
- Each row: **status dot**, bold title, relative timestamp right-aligned (`now`, `Yesterday`, `Mon`,
  `Sat`), then a meta line `project · status · <qualifier>`.
- Sub-agent sessions nest **indented under their parent**, each with its own dot and an agent name
  rendered in `agentBlue`/status colour, followed by a one-line summary.
- Meta qualifiers seen: `running`, `idle`, `shared`, `provider error`, `forked from message 14`.
- Swipe a row to reveal `Rename` / `Delete` as full-bleed coloured actions.
- Bottom-right capsule FAB: `+ New session`.

### 3.2 Chat / session (pages 1, 3, 4)

Header: hamburger, session title, a live **status dot + percentage** (e.g. `38%`), and a kebab menu.
Sub-line under the title: `project · branch` in muted monospace-ish text.

**Message bubbles:**
- User turn: right-aligned, `surfaceRaised` bubble.
- Assistant turn: full-width, no bubble, body text directly on the page background.

**Agent cards** — a bordered rounded card with a coloured left dot, agent name in the agent colour, and
a status line:

| Element | Examples |
|---|---|
| agent + status | `Build  sonnet-4.5`, `bash  running · 6s` |
| thinking line | `Thinking · 4s` |
| tool row | `read  src/auth/session.ts` with a `1–120` range chip right-aligned |
| shell block | `$ npm test -- auth`, then `PASS`/`FAIL` lines, failing line in `statusError` |

**Attachment card:** a bordered block with a filename and `Pasted image · 184 KB`.

**Composer (docked bottom):** placeholder `Message, / for commands, @ for files` — all three hints are
literal features. Below it a row: `+` circle button, `● Build` model pill, `sonnet-4.5` / effort
(`high`) pill, and a circular accent **send/stop** button (square icon while a run is in flight).

**Slash-command autocomplete** (page 3): a bordered panel listing matches with a `4 results` count,
grouped by `BUILT-IN`, `PROJECT`, `SKILLS`. Each row is `/command` in accent + a description, with a
right-aligned type chip (`Git repo`, `subagent`, `skill`). The typed prefix stays in the composer above,
which opens the system keyboard (`?123`, `Done`, space bar).

**Approval sheet** (page 4): a bottom sheet with a grab handle, a warning icon, title
(`Run a command?`), and a sub-line naming the requester (`Build agent wants to push to a remote`).
Body: a `BASH` label, the command in a monospace block, the working directory, then a quoted explanation
plus the pattern chip that would be whitelisted (`git push*`). A line states
`Auto-approve is off · 1 more request waiting`. Actions: full-width accent `Allow once`, then a row of
`Always allow` / `Reject`.

### 3.3 Sub-agents (page 7)

Header: `< 2 subagents · 1 running`. Prompt line shows the sub-agent name in accent monospace.

Each sub-agent is a bordered card with a **progress bar along its top edge** (accent when running,
`statusIdle` when done), then: agent name in its colour, a check/dot, a right-aligned
`done · 14s · 6 steps` status, a one-line task summary, an inner mono block for its edits
(`edit src/auth/session.test.ts`), and action buttons — `Open session ›`, plus `Stop` on the running card.

### 3.4 Sub-agent detail (page 8)

Header: back chevron, parent title truncated with `…`, `› explore`, then `< 1/2 ›` pager arrows.
A chip row: `Finished · 14s`, `Read-only agent`, `Depth 1 of 1`.

Body: `TASK FROM BUILD` label, the task text, then tool cards with match counts
(`grep refreshToken — 7 matches`), a file list with `+ 3 more`, and a `Result` heading in prose.
Bottom: full-width accent `Return to parent` with an up arrow, and the hint
`Swipe left or right to switch between subagent sessions`.

### 3.5 Agents (page 6)

Segmented control: `Primary` / `Subagents` / `System`, with the active segment on `surface`.
Agent rows: coloured dot, name (bold, in the agent colour), a `built-in` / `custom` / `experimental`
badge, a right-aligned model (`inherits model`, `sonnet-4.5`, `hidden`), a description, and capability
chips — `edit` (neutral), `bash` (green), `web` (green), `read-only` (blue), `edit denied` (red),
`bash: ask` (amber).
Bottom-right capsule FAB: `+ New agent`.

### 3.6 Files (page 8 bottom / page 9)

Header: `~ / projects`. Rows: folder name bold, then a meta line
`42 items · Git repo` / `1.2 KB`. Actions appear as an inline expanded panel on the selected row:
`Open in chat`, `Add to Projects`, `Rename`, `Copy · today`, `Move`, `Info`, `Delete`.

### 3.7 Settings (page 10)

Top: a `surface` card reading `OpenCode server running` with a `statusIdle` dot, a sub-line
`v2.0.6 · 127.0.0.1:4096 · Alpine`, and a `Restart` button.

Grouped sections with `surface` cards and hairline dividers:

| Section | Row | Value |
|---|---|---|
| `MODELS` | Providers & models | `sonnet-4.5` |
| | Agents & subagents | `depth 1` |
| `SAFETY` | Permissions | `ask for bash` |
| | MCP servers | `3 connected` |
| | Skills & commands | `9 available` |
| `APP` | Appearance | `opencode · dark` |
| | Notifications | `on` |
| | Sessions & data | `share: manual` |
| | Server & runtime | `212 MB` |

Each row has a `›` chevron; values are right-aligned muted.

---

## 4. What carries into the mobile app now

Directly buildable from this design today, against the Phase 2 runtime that now works:

- Sessions screen (3.1) — the app already builds a session tree from `/api/session` and knows
  `parentID != null` means sub-agent, which maps exactly to the nested sub-agent rows.
- Settings → the `OpenCode server running` card (3.7) — `ProotLauncher` exposes `ServerState` and the
  chosen port; `v2.0.x · 127.0.0.1:<port> · Alpine` maps onto real state.
- Bottom nav shell (2) with `Agents`/`Files` as placeholders until those phases land.

**Requires later spec phases — do not build now:** composer/send/streaming, slash commands, approval
sheet, permission model, MCP, skills, attachments, projects.

### Naming correction to carry forward

The design uses **Agents**, not the old mockups' **Terminal**. There is no Terminal tab in the refined
design, so `docs/specs/OpenCode Mobile – Build Spec.md`'s Terminal screen should be reconciled against it.
