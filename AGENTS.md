# AGENTS.md - craftraria

## Agent Role

Specialist maintainer of **craftraria**. Prioritize correctness of the integration glue, keep
third-party/externally-supplied content out of the repo (see Boundaries > Never), and verify
each milestone against PLAN.md (tracked in the tracking workspace, not in this repo).

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Fabric Loader + API | 1.26.2 | Mod loader for Minecraft Java Edition |
| Java | 21 (pin at M1) | Language/runtime. Rinku 3.0.5's own build declares `java_version=25`; verify the resolved artifact's class-file version at M1 and pin the toolchain to match - do not assume 21 still works |
| Gradle | Fabric Loom 1.15.5 | Build system (`fabric_version=0.153.0+26.2`) |
| Rinku | 3.0.5-26.2 | Embedded Chromium browser via `de.keksuccino:rinku-fabric:3.0.5-26.2` (Maven: https://keksuccino.github.io/maven/) |
| Cloth Config | 26.2.x | Settings screen for `CraftrariaConfig`; hard dependency, declared in `fabric.mod.json` (Maven: https://maven.shedaniel.me/) |
| Mod Menu | 20.0.x (compile only) | Optional: exposes the settings screen as its config button. Not a runtime dependency |
| Terrarium wasm port | release 1.0 (36,314,788 bytes) | MercuryWorkshop/terraria-wasm build: engine plus recompiled game code, no assets. Downloaded at runtime by `TerrariaBuildDownloader`, unpacked to `<instance>/craftraria/game/build/`, never into the repo or the jar. Pin recorded in `ai-docs/decisions/pin-terrarium-build.md` |

## Key Commands

```bash
./gradlew genIntellij  # Generate IDE run configs (Fabric Loom)
./gradlew build        # Build the mod jar
./gradlew runClient    # Run a Minecraft client with the mod
./gradlew check        # Run tests (none yet)
./gradlew idea         # Alternative IDE config task
```

## Dev environment (Windows)

The dev machine is Windows and the tooling is not. Each of these has cost a session at least once
(proven in craftlatro; treat as already learned):

- **PowerShell 5.1 has no `&&`**: chain with `;` and test `$?`.
- **Existing files are CRLF**: anchor `edit` calls to short unique strings, not whole blocks.
- **Never replace an open file**: close every stream before `Files.move` or delete. Windows cannot
  replace a file another handle still holds open; POSIX can, which is why the code reads as if it can.
- **The AWT clipboard is unavailable in a dev launch** (headless JVM): see Gotchas.
- **CI is Linux**: `gradlew` needs its executable bit committed and
  `gradle/wrapper/gradle-wrapper.jar` must not be gitignored; both go in the release runbook
  (`docs/commits-and-releases.md`).

## Architecture

Integration glue only - the mod does **not** reimplement Terraria. Data flow at runtime:

1. The player supplies a Terraria install (Steam detection / JFileChooser / `/craftraria load <path>`); the mod validates the `Content` directory (`Fonts`, `Images`, `Sounds`) and records the path in the game dir like craftlatro's `balatroExePath`.
2. `TerrariaBuildDownloader` fetches the pinned release-1.0 `terraria-wasm-build.zip` from GitHub at runtime, verifies its size, and unpacks `_framework` into `<instance>/craftraria/game/build/`.
3. The mod packs the player's `Content` directory into a tar (`content.tar` with an mtime/size version marker) served by a minimal local HTTP server (`java com.sun.net.httpserver`) with COOP/COEP headers (`Cross-Origin-Embedder-Policy: require-corp`, `Cross-Origin-Opener-Policy: same-origin`) on `http://localhost:<port>` - mandatory for SharedArrayBuffer/OPFS.
4. The mod-written vanilla-JS page (no bundler; `sw.js` stripped) fetches and extracts the tar into OPFS, reproduces upstream's `preInit` glue (dotnet config with `pthreadPoolInitialSize: 16`, jiterpreter options), then runs `PreInit` / `Init(width, height)` / `MainLoop`.
5. Rinku v3.0.5 renders the local URL in an embedded Chromium on a flat Fabric GUI screen.
6. Minecraft keyboard/mouse input is mapped into the embedded browser view. Saves persist in the browser's OPFS (`/libsdl/tsaves`) across sessions.

Key directories:
- `src/main/java` - Common init only (`com.craftraria.CraftrariaMod`)
- `src/client/java` - All client code: `TerrariaBrowser` (screen-agnostic singleton with parked state), `BrowserScreen`, `BrowserWindow`, `LocalHttpServer`, `TerrariaBuildDownloader`, `TerrariaPaths`, the tar packer, install detection
- `src/client/resources` - `fabric.mod.json`, assets, key bindings, and the served page (vanilla JS)
- `ai-docs/` - AI field notes, decisions, lessons learned across sessions
- `docs/` - Human-facing documentation only

## Project status

Planning is complete (CONCEPT.md + RESEARCH.md + PLAN.md in the tracking workspace) and
scaffolding has landed; no implementation code exists yet. Milestone M0 (process setup:
conventions, release-only CI, AGENTS content) is the first work item, and M1 starts with the
environment probe suite (OPFS read/write through the local server, picker/keyboard-lock
availability, `_framework` load through the local server) before any game work. The boundary
between planning and implementation is that probe suite: if the probes pass, the milestones
proceed; if any fails, the affected design decision is re-opened with the probe evidence.

## Code Style and Conventions

- Java 21+ (pin settled at M1), standard Minecraft modding conventions (Fabric Loom). Write
  self-documenting code; prefer explanatory method names over inline comments. The one exception
  is non-obvious behavior (e.g. COOP/COEP header necessity, OPFS/backslash-path quirks) which the
  guidelines elsewhere already mark as a gotcha.
- Functional style where practical; avoid deeply nested Minecraft client event handlers.
- The served page is vanilla JS with no bundler; keep it that way.

## Testing

- **Framework**: none configured yet (Fabric mod; standalone probes are the named verification
  path, see the ladder below).
- **Run all**: `./gradlew check`
- **Coverage target**: none yet; there are no test sources.

### The verification ladder

Use the highest rung that actually runs, and name the rung in the claim:

1. `./gradlew build` (or `compileClientJava`) - compiles only.
2. A standalone probe compiled against the real classes - for logic that cannot run inside
   Minecraft (e.g. directory validation, tar round-trip). This is the default move when rung 1 is
   not enough.
3. The `/craftraria stats` diagnostics report, pasted by the user - this is the measurement. Ask
   for the report, not for an impression.
4. A user-driven `./gradlew runClient` - the only channel that can verify anything in the browser.
   **An agent cannot open the Terraria screen** (it needs a real input event), so every browser
   change is handed back for the user to run.

A claim of "verified" that rests on reading source is not on this ladder - craftlatro's
`module 'bit' not found` saga survived three such claims before the real cause was found.

## Commits, versions and releases

The rules will live in **[docs/commits-and-releases.md](docs/commits-and-releases.md)** (written
at M0) - the short version:

- Commits follow **Conventional Commits** (`feat:`, `fix:`, `docs:`, `style:`, `ci:`, ...). Subject
  in the imperative, body wrapped at 72 and saying *why*.
- The version is one line in `gradle.properties` (`MAJOR.MINOR.PATCH+MCVER`); the tag and the
  GitHub release are `vMAJOR.MINOR.PATCH`. `CHANGELOG.md` (Keep a Changelog) is the release
  record.
- **CI runs only on a published release**, on purpose: a push, merge or PR costs no runner time,
  so the local `clean build check` is the only gate between a commit and a tag.
- `main` is the default branch. A workflow that says `master` is a bug, not a convention.
- Pushing tags and cutting releases stays **Ask First**. Creating the remote was authorized up
  front for this project (public, CoderMan73/craftraria); that is not standing permission for
  tags or releases.

## Boundaries

### Always
- Keep externally-supplied content (Terraria code/assets, the Terrarium wasm build, the Chromium
  runtime) out of the repo and out of the mod jar; the jar contains mod classes and the served
  page only, enforced by a build-time staging check.
- Record non-obvious findings in `ai-docs/decisions/` or `ai-docs/notes/`.

### Ask First
- Pushing tags or cutting releases.
- Changing the project license or adding dependencies with non-open runtimes.
- Any decision that would make the project distribute a recompiled game build rather than the
  player's own copy (that changes the project's legal boundary and stays an explicit owner call).

### Never
- Bundle third-party game code/assets or redistributable runtimes in the jar or repo.
- Hardcode paths or credentials; keep every file the mod writes inside the Minecraft instance
  (settings in `config/`, generated data under the instance root). `TerrariaPaths` is the single
  place that resolves them.

## Context Loading

| Task | Read |
|------|------|
| Start work | PLAN.md (tracking workspace: `E:\Coding_Projects\future-projects\public/active/craftraria/PLAN.md`), ROADMAP.md, AGENTS.md, this file |
| Runtime docs | `docs/` |
| Field notes | `ai-docs/` |
| Minecraft / Fabric / Rinku / Cloth API or version questions | `E:\Coding_Projects\MCSourceCode` (`Rinku` branch `26.2.0`, `vanilla-minecraft/26.2`, `cloth-config`, `fabric-api/26.2`) **before any web search**; the web is the fallback |

The nearest sibling project, craftlatro, is the reference implementation for the shared
machinery (screen-agnostic browser singleton, local COOP/COEP server, window/notice design,
stats report). Reuse its proven classes as the porting base rather than re-deriving them.

## Gotchas

- **Never size a freshly created Rinku browser through `Rinku.createBrowser(url, transparent, width,
  height)`.** Resizing a browser inside that overload crashed craftlatro's client natively - an
  `EXCEPTION_ACCESS_VIOLATION` in `jemalloc.dll` under `AsyncResourceLeaseManager.drain` in Rinku's
  async paint path. Create the browser two-arg and resize it through the browser singleton's
  viewport path on the same frame instead.
- **Never make a surface larger than the window's own full-screen size.** Craftlatro hit
  deterministic native crashes in Rinku's async paint path (`jemalloc.dll` AV under
  `AsyncResourceLeaseManager.drain`, hs_err logs) at monitor-video-mode sizes, at sizes a
  window-sized surface had painted at safely for sessions.
- **Rinku pumps paint frames for every live browser on the Minecraft render thread regardless of
  screen state.** A browser left open while no screen shows costs frames; park it (mute + 5 fps +
  shrink) rather than closing it.
- **Ship at most one browser-affecting change per user verification run**, and say exactly what to
  check. Batching forced craftlatro to validate several unverified behaviors in one user run, and
  the first fix was refuted by the next run.
- **The game sizes its canvas once, at page load** (upstream takes `screen.width/height` at Init)
  and never follows a browser resize; craftlatro hit the same behavior with love.js. Offer a
  reload strip when the window settles somewhere substantially different, both buttons the
  player's - a reload restarts the game.
- **When the user reports a shipped fix still broken, suspect a stale cache or runtime layer before
  re-diagnosing the original bug.** Craftlatro's `440f734` reflex: the fix was correct on disk and
  love.js's IndexedDB package cache kept serving the old archive. Here the cache layers are
  `sw.js` (stripped), OPFS, and dotnet wasm caching; check what is actually loaded first. Version
  every served URL and set `no-store` on the loader page.
- **Path separators are a known upstream hazard**: issue #17 notes Terraria requests assets with
  backslash paths (`Images\UI\X`) while emscripten splits only on `/`. Probe tar extraction early
  (M1) and record the outcome.
- **The supported Terraria version is narrow and unverified**: release 1.0 is a May 2025 decompile
  of a pre-1.4.5 game, and the only breakage evidence (issue #15) is thin and unanswered. The M3
  version experiment tests the pinned build against the player's actual content version; treat
  mismatch as test-then-warn, not as a hard block.
- **The AWT clipboard is unavailable in a dev launch** (headless JVM). Use `Window.handle()` +
  `glfwSetClipboardString` for anything clipboard-related.
- COOP/COEP headers on the local server are mandatory (SharedArrayBuffer/OPFS do not run
  otherwise); the file-picker surface (`showDirectoryPicker`, `showOpenFilePicker`,
  `navigator.keyboard.lock`) is probed at M1 - design assumes no browser picker in the flow.

## Local Skills

This project may define repo-local skills in `.agents/skills/`. When starting a task, scan
this folder for skills whose descriptions match the current work.

- **Discovery**: Check `.agents/skills/` at task start.
- **When to use**: Any skill whose description matches the task at hand.
- **When to suggest**: Recurring, repeatable processes worth standardizing.
- **When NOT to suggest**: One-off decisions, single-use observations (use `ai-docs/`).

Skills under CoderManSkills are invoked by the user; an agent only receives their content when the
user runs them or is told to read the file mid-run. Guidance an agent must always see therefore
belongs in this file (or `ROADMAP.md` / `docs/` / `ai-docs/`), not in a skill. Repo-local skills
under `.agents/skills/` are the exception: they are read from disk directly, so they do reach an
agent unprompted.

## ai-docs

This project uses an `ai-docs/` subdirectory for field notes, architectural decisions, and
lessons learned. See `ai-docs/README.md` for guidelines on recording knowledge between
agent sessions.

> Guidance from `references/agents-guide.md`.
