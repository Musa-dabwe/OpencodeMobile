# Phase 3 — UI shell and design system

**Status:** planned, not started.
**Preceded by:** `phase2-runtime.md` (complete — the app downloads, installs and starts its own
OpenCode server on device; 180 tests; screenshot proof at `docs/plans/` in the Task 5 report).

## What Phase 2 handed us

A working runtime and a working server, but a **placeholder UI**: `Color.kt` is still the stock
Compose template purple (`Purple80`, `Pink40`), there is no navigation, and the session screen is a
single flat list. Task 5's screenshot shows exactly that. Phase 3 makes the app look like the design
PDF and gives it a real navigation shell.

## Two naming collisions this plan resolves

1. **Spec §10 defines its own "Phase 3"** = settings packs, diagnostics, theme import, polish. That is
   NOT this phase. Working plan files are the authority on sequencing (`phase1-slice.md`,
   `phase2-runtime.md`, this one); the spec's §10 list predates them and is stale.
2. **Bottom nav.** Spec §5 says `Projects · Chat · Files · Terminal · Settings`; the design PDF shows
   `Projects · Chat · Files · Agents · Settings`. **Owner ruling: the Terminal tab stays.** The
   resulting nav is 6 tabs, in this order:

   `Projects · Chat · Files · Terminal · Agents · Settings`

   `Agents` is inserted before `Settings` because it is a spec §5.9 first-class feature and the design
   treats it as a root destination. Only `Chat`, `Files` and `Settings` get real content in this phase;
   the rest ship as honest placeholder destinations.

## Design tokens — extracted, not invented

Spec §7 says "**do not invent hex values**" and points at the OpenCode theme JSON. The refined design
supersedes that source, so the tokens were extracted **from the PDF** as the owner directed:

`app/src/main/assets/themes/opencode-mobile.json` — every hex sampled from the rendered pages at
150 dpi, not eyeballed.

| Group | Values |
|---|---|
| Background | `#0C0C0E` base · `#151517` raised · `#1E1D22` raisedAlt · `#050505` inset |
| Border | `#232227` subtle · `#302F35` strong |
| Text | `#F2F2F4` primary · `#96959B` secondary · `#8F8E95` sectionHeader · `#56555B` disabled |
| Accent | `#FAB282` (the ONLY warm hue) |
| Status | idle `#7DD88E` · running `#6A4F3C` · error `#E96776` |
| Agent | primary `#568FDA` · subagent `#75B086` |

**Rules that must survive implementation:**
- Accent is reserved for the single primary action on a screen. Status colours carry state.
- **No drop shadows anywhere.** Depth = 1px hairline `#232227` + surface lift.
- Primary actions are full capsules (radius = height/2). FAB measured at 145×53 dp.
- The design is **dark only**. No light palette is invented; if a light mode is ever wanted it needs a
  design source, not a programmatic inversion.

**Contrast verified** (WCAG, on `#0C0C0E`): primary 17.5:1, secondary 6.6:1, sectionHeader 6.0:1,
accent 10.9:1, status.error 6.2:1, onAccent 9.7:1 — all pass. `text.disabled` is 2.65:1 and **must
only ever mark genuinely non-interactive text**, never something the user must read or act on.

## Global constraints (unchanged)

- `ANDROID_HOME=~/Android/Sdk sh gradlew :app:assembleDebug` — `gradlew` is NOT executable.
- Tests need `--rerun-tasks` or they report UP-TO-DATE and a broken test looks green.
- Kotlin 2.1.0 caps coroutines/serialization versions. SDK levels unchanged (36/34/30). Package
  `com.musa.opencodeharness`. arm64-v8a only.
- **Never log the password.** Task 5 found two real leaks that way (a thrown `SetupFailed` bypassing
  redaction while the ViewModel rendered `cause.message`). Keep writing those tests.
- **Devices only prove anything.** `assembleDebug` proves nothing about the runtime.
- Do not push to git.

---

## Task 1: Theme foundation

Replace the Compose template theme with the extracted design.

- Parse `assets/themes/opencode-mobile.json` at startup. Build an `OpenCodeTheme` model mirroring the
  token tree and expose it through a `CompositionLocal`, bridged to a Material 3 `ColorScheme`.
  (Spec §7 step 1, retargeted at our own file.)
- Implement the `$hue.x.y` reference and `transparent` forms the loader needs, so a future
  OpenCode-format theme can be dropped in unchanged.
- Map typography roles from the JSON into Compose `TextStyle`. **Typeface is the system default, by
  owner decision: bundle nothing.** No downloadable fonts, no `.ttf` in assets, no Google Fonts
  dependency, so there is no font licence to check. Resolve every role with `FontFamily.Default`,
  except `mono` which uses `FontFamily.Monospace`.
  The PDF embeds one subset font named `Glyphless`, so it carried no real typeface to extract
  anyway — only sizes and weights, read visually from the renders as a close approximation. Correct
  those in the JSON if they look wrong on device; do not special-case them in composables.
- Kill `Purple80`/`Pink40`/`ComposeEmptyActivityTheme`.

**Done when:** no template colour remains in the source tree, the app renders in the design's dark
palette, and a unit test asserts every token parses and every foreground meets its contrast floor.

## Task 2: Navigation shell

Six-tab bottom nav per the ruling above.

- `NavigationBar` styled from tokens: icon above a small label, active tab in `accent`, inactive in
  `#56555B`.
- **Six tabs is at the upper limit for a bottom bar.** Verify on the 393dp baseline that labels do not
  truncate or collide; if they do, shorten labels and record the decision rather than shrinking type.
- Each tab is a real destination with state preserved across tab switches (Compose `SaveableStateHolder`),
  so switching away from Chat and back must not lose the scroll or the loaded list.

**Done when:** all six destinations navigate, state survives a switch, and a screenshot shows the bar
on device.

## Task 3: Sessions screen (§3.1 of the design)

The screen that already exists, rebuilt to the design.

- Header: back chevron, title, and an accent-outlined scope pill (`This project`).
- Search field in the `raised` surface style.
- Rows grouped under `TODAY` / `EARLIER` section headers with the uppercase wide-tracking style.
- Row anatomy: status dot · bold title · right-aligned relative timestamp · meta line
  `project · status · qualifier`.
- **Sub-agent sessions nest indented under their parent**, each with its own dot and an agent name in
  `agentBlue`. The tree already exists — `buildSessionTree` groups by `parentID`, and the ledger
  records that "a session is a sub-agent iff `parentID != null`" holds on real data (the
  children-based rule fails on promoted orphans).
- Swipe a row for full-bleed `Rename` / `Delete` actions in the action colours.
- `+ New session` capsule FAB.

**Done when:** real session data from the app's own server renders with correct nesting, verified by
screenshot on device.

## Task 4: Settings surface (§3.7 of the design)

The subset that real state backs today.

- The `OpenCode server running` card: status dot, `v2.0.x · 127.0.0.1:<port> · Alpine`, `Restart`.
  This maps straight onto `ProotLauncher`'s `ServerState` and chosen port.
- Grouped sections with hairline dividers: `MODELS`, `SAFETY`, `APP`, chevron rows, right-aligned
  values.
- Rows we cannot honestly populate yet (`MCP servers`, `Skills & commands`, `Appearance`, `Server &
  runtime` size) are either derived from real state where possible or shown disabled — **never
  rendered with invented values.**

**Done when:** the server card reflects real launcher state, including the `Dead` state.

## Task 5: Wire the setup surface to the theme

The first-run/setup flow currently prints a raw monospace log dump. Bring it onto the tokens.

- Determinate progress per setup step, plus the live `LogBuffer` tail in a mono-styled panel.
- **Redaction must survive into the UI.** Never bypass `LogBuffer.redact`.
- Server death shows as a visible state, not a silent stall.

**Done when:** a fresh install shows themed progress and a readable log, on device.

---

## Carried-forward verification debt

These are **not** UI work and must not be silently dropped. Phase 2 could not close them:

1. **Download → install is NOT device-verified.** The device was seeded from a host cache at ~14 KB/s.
   A genuine first run has never been observed end to end. This is the biggest untested path in the
   product and it is what a new user hits first.
2. **SELinux symlink handling and DNS inside proot remain unverified.**
3. **`Process.pid()` returns `-1`** on Android (Java 9 API absent from `android.jar`). Guard before
   any UI surfaces a pid.
4. **Nine deferred minors** in `.superpowers/sdd/phase2-runtime/progress.md`. Two worth doing in this
   phase because they are cheap and now visible: drop the now-unused downloaded `proot` artifact
   (it is shipped in `jniLibs` and the downloaded copy is dead weight), and add `abiFilters` so a
   transitive dependency's `.so` stops shipping for 4 ABIs while ours is arm64-v8a only.

## Non-goals for this phase

No composer, no streaming/SSE, no slash commands, no approval sheet, no permission flow, no Files or
Terminal implementation, no theming import UI, no light mode, no animation pass. `Projects`, `Files`,
`Terminal` and `Agents` ship as placeholder destinations only — real content is later phases.

## Risks

- **Six tabs may not fit** at 393dp with `This project`-scale labels. Decide early, record the
  decision.
- **The typography is an approximation**, since the PDF carries no font metadata. If it looks wrong on
  device, correct it in the JSON and say so in the ledger rather than special-casing in composables.
- **Restyling `SessionListScreen` is a rewrite, not a patch.** Its 371 lines were written against the
  template theme. Budget for replacing it rather than nudging it, and keep its state model.
