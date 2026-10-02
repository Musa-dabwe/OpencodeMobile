# Plan — OpenCode Mobile, Phase 1 vertical slice

Spec: `docs/specs/OpenCode Mobile – Build Spec.md` (binding authority)
Findings this plan builds on: `docs/phase0-findings.md` (verified live against a real server)

## Global Constraints

These bind every task. They come from the spec and from verified findings.

- Build command is exactly: `ANDROID_HOME=~/Android/Sdk sh gradlew :app:assembleDebug`.
  `gradlew` is NOT executable — always `sh gradlew`, never `./gradlew`.
- Kotlin DSL only (`.kts`). Versions go in `gradle/libs.versions.toml` as `libs.*`
  aliases; never inline a coordinate in `app/build.gradle.kts`.
- Package/namespace is `com.musa.opencodeharness` (both namespace and applicationId).
  Do NOT rename. Keep new code under that package.
- SDK levels stay as-is: compileSdk 36, targetSdk 34, minSdk 30. Do not "fix" the skew.
- **Health check is `GET /api/health` → `{"healthy":true}`. NOT `/api/info`** — that
  route does not exist and returns the SPA's HTML. Verified; see findings.
- Server auth is HTTP Basic, username exactly `opencode`, password from
  `OPENCODE_SERVER_PASSWORD`. The app must ALWAYS set it. 401 on missing/wrong.
- Sub-agent sessions have NO children-listing route. `Session.Info` carries an optional
  `parentID`; the session tree is built CLIENT-SIDE by grouping `GET /api/session`
  results on `parentID`. Verified live (50 sessions, 34 with parentID).
- Attachments: `PromptInput.FileAttachment` requires `uri` (NOT `path`), plus optional
  `name`/`description`/`mention`. A separate `Prompt.FileAttachment` takes inline
  `data` (base64) + `mime` + `source`. Verified from the v2 schema.
- List endpoints return `{ "location": {…}, "data": [ … ] }`. Model BOTH keys. A bare-array
  decode will fail. Verified live on /api/session, /api/agent, /api/model, /api/command,
  /api/fs/list.
- No device is attached and the host is x86_64. Nothing in this plan may claim to have
  run on arm64/proot. Device-only items stay `[VERIFY]` in the findings file.
- No tests exist and no test dependencies are declared. The first test added in a task
  must add its own dependencies in that same task.
- Server base URL is `http://127.0.0.1:<port>`. proot shares Android's network namespace.

## Task 1: Build config for the network + serialization stack

Add the dependencies Phase 1 needs and prove the module still compiles.

- In `gradle/libs.versions.toml` add version refs and `libs.*` library aliases for:
  Ktor client (core, okhttp engine, content-negotiation, json, logging, websockets),
  kotlinx-serialization-json, kotlinx-coroutines-android.
- In `app/build.gradle.kts` add them via the aliases.
- Apply the kotlinx-serialization plugin if any @Serializable types are added later;
  add the plugin alias in `libs.versions.toml` and apply it in `app/build.gradle.kts`.
- Do NOT write any client code, domain types, or UI. Dependencies only.
- Verify: `ANDROID_HOME=~/Android/Sdk sh gradlew :app:assembleDebug` succeeds.
- Commit.

## Task 2: Typed API client core

The hand-written Ktor client. Data layer only — no UI, no Compose.

Create under `com.musa.opencodeharness.data` (create the package dir):

- A small holder for base URL + credentials + Ktor `HttpClient` construction, taking the
  password as a parameter. **Never** hardcode a password or log one.
- Auth: HTTP Basic, user literally `opencode`, password from the caller.
- A generic response envelope type modelling `{ location, data }` exactly as verified.
  This is the shape every list endpoint returns — get it right or everything breaks.
- Typed request/response models, `kotlinx.serialization`, for exactly these:
  `Health`, `Session.Info` (including the optional `parentID`), `Agent`, `Model`,
  `Command`, `FsEntry`.
- Functions for: health check, list sessions, list agents, list commands.
- No UI, no ViewModel, no proot, no foreground service.

## Task 3: Client tests against recorded real JSON

Tests that lock the verified response shapes so a schema drift breaks the build, not the app.

- First add test dependencies (JUnit; `kotlinx-coroutines-test` if needed) in this task.
- Add a JVM unit test source set under `app/src/test/kotlin/...`.
- Test 1: the `{ location, data }` envelope decodes for a real captured session list.
- Test 2: a session WITHOUT `parentID` decodes cleanly (the optional-field case — this
  is the sub-agent grouping invariant from Task 4).
- Test 3: a session WITH `parentID` decodes and the parent id survives.
- Test 4: health `{"healthy":true}` decodes.
- Use realistic JSON captured from a real server response. Do not invent shapes.
- Verify: the specific test task passes, and `assembleDebug` still succeeds.
- Commit.

## Task 4: Session tree grouping (sub-agent parent/child)

Pure logic, no I/O. This is the piece Phase 0 findings resolved.

- A small pure function: given a flat list of sessions, return a parent→children tree
  keyed on `parentID`.
- Sessions with no `parentID` are roots. A child whose parent is absent from the list
  must not crash or vanish — decide and document the behaviour (treat as root, or drop)
  with a comment saying why.
- Unit tests for: flat list of only roots; one parent with children; nested
  grandchildren; the orphan case decided above.
- No UI. This is a pure function plus tests.

## Task 5: Minimal chat UI on top of the client

Smallest possible end-to-end screen, so the slice is real.

- A ViewModel exposing state for: connecting, health OK, server unreachable, and a list
  of sessions.
- One Compose screen showing connection status and the session list, each row showing
  title and a child/parent marker driven by Task 4's tree.
- MainActivity's existing `Greeting` placeholder gets replaced by this screen. Keep
  `ComposeEmptyActivityTheme` as the theme for now — theming is a later phase.
- No streaming, no tool cards, no permissions, no media, no proot. Those are Phase 1/2
  scope in the spec, not this slice.
- Verify: `assembleDebug` succeeds.

## Task 6: Findings doc + spec corrections committed

Close the loop on what Phase 0 actually proved.

- `docs/phase0-findings.md` already exists and is written. In this task, ensure it is
  committed and that the spec file's two now-answered `[VERIFY]` items are corrected:
  the `/api/info` → `/api/health` health check, and the child-sessions route (client-side
  grouping on `parentID`, not a route). Update `docs/specs/OpenCode Mobile – Build Spec.md`
  to match the verified reality, and remove `[VERIFY]` from items the findings settled.
- Do NOT touch the arm64/proot `[VERIFY]` items — those are still open.
- Commit.
