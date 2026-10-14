# craftraria Roadmap

## Project Goals

- Run the genuine Terraria (1-to-1 behavior: menus, world generation, and a save round-trip) from
  inside Minecraft Java Edition, by composing the proven browser-embedded pattern (Rinku + local
  COOP/COEP server + player-supplied files + mod-written served page) with the Terrarium wasm port
  - not by reimplementing Terraria.
- The player supplies their own Terraria `Content` directory; the mod packs it into a tar, serves
  it with the runtime build to an embedded Chromium browser, and stages it into OPFS from the
  served page.
- Never bundle or distribute Re-Logic code/assets; ship only the integration glue (build
  downloader, tar/server, in-game screen, input mapping), with a build-time check enforcing it.

## Project Non-Goals

- Reimplementing Terraria in Java (no JVM runtime path exists for the game; rejected path).
- Bundling Terraria code/assets, the wasm build, or the Chromium runtime in the mod jar.
- Java Edition is the only target (not Bedrock/C++ add-ons).
- Multiplayer and tModLoader (untested in the port).
- Publishing a recompiled/rebuild of the game for players - that changes the legal boundary; if a
  rebuild is ever genuinely required it is an explicit owner decision evaluated by building once
  locally.

## Shipped

- Scaffolding (2026-10-14): repo initialized at `E:\Coding_Projects\craftraria` (public remote
  CoderMan73/craftraria, branch `main`), foundation files, GPL-3.0, and the `src/main` +
  `src/client` layout. No implementation code yet.

## Current Objectives

- [ ] Process setup (M0, before any code)

  Clone the craftlatro conventions: AGENTS.md (done at scaffolding), `docs/commits-and-releases.md`,
  CHANGELOG convention, conventional commits, release-only CI workflow, `ai-docs/` structure. Port
  the proven gotchas so they are not re-derived (fresh-browser sizing, surface-size crash
  boundary, park-don't-close, one browser-affecting change per user run).
- [ ] Scaffolding, Rinku integration, and environment probes (M1)

  Fabric 1.26.2 mod with Loom, Rinku 3.0.5-26.2 resolved, Java toolchain pinned after checking the
  artifact's class-file version. Port craftlatro's core embed classes (`TerrariaBrowser`,
  `BrowserScreen`, `BrowserWindow`, `LocalHttpServer` with COOP/COEP as-is). **Probe first**:
  OPFS write/read through the server; `showDirectoryPicker`/`showOpenFilePicker`/
  `navigator.keyboard.lock` availability inside Rinku's Chromium; load release 1.0's `_framework`
  unmodified through the local server and reach `Program.PreInit()`'s `mount_opfs()` call. Results
  recorded in `ai-docs/notes/`.
- [ ] Build downloader and serving pipeline (M2)

  `TerrariaBuildDownloader` (pinned release 1.0, size verified at 36,314,788 bytes, unzip to
  `<instance>/craftraria/game/build/`), `_framework` served unmodified through `LocalHttpServer`
  (version-pinned URLs, `no-store` on the loader page, `sw.js` stripped), and `TerrariaPaths` as
  the single instance-local path resolver.
- [ ] Player-supplied Content handoff (M3)

  Install detection (Steam library scan + `libraryfolders.vdf`, JFileChooser fallback with
  headless guard, `/craftraria load <path>`), `Content` validation (`Fonts`, `Images`, `Sounds`),
  version experiment against the pinned build run **first**, version-mismatch UX with the
  pre-filled Steam depot-rollback command and SteamDB IDs, tar packaging with a version marker,
  and standalone validation/round-trip probes. Probe the backslash-path extraction hazard early.
- [ ] Game launch, screen, input, and performance (M4)

  Served page `PreInit` / `Init(width, height)` / `MainLoop`; Minecraft keyboard/mouse mapping;
  keyboard-first input with `navigator.keyboard.lock` deciding the key-swallow preamble; gamepad
  decided from the M1 probes; paint-rate cap and render scale from day one; `/craftraria stats`
  diagnostics report and fps/memory measurement.
- [ ] Saves, warnings, packaging, and release guardrails (M5)

  OPFS save persistence (`/libsdl/tsaves`) with an export/import decision recorded in
  `ai-docs/decisions/`; first-run fidelity warning covering the supported version, upstream open
  issues (#17/#13/#16), no multiplayer, no tModLoader; build-time staging check that fails if any
  Re-Logic binary or asset is staged; release runbook with local `clean build check` as the only
  gate.

## Planned

- M5 release guardrails include the release process (tags/releases stay Ask First); see PLAN.md
  for the detailed breakdown of each milestone.

## Out of Scope

- In-world 3D / computer-block display surface - flat GUI screen only (CONCEPT.md resolved
  decisions).
- Minecraft Bedrock / add-on integration.
- Multiplayer and tModLoader (untested in the port).
- Gamepad support - out of scope for v1 unless the M1 probes say otherwise.
- Any browser picker in the asset flow (fetch-based staging by design).
- Rebuilding/distributing the game - owner decision only (see Non-Goals).

## Aspirational Goals

- Gamepad-first support if the M1/M4 probes make JCEF gamepad viable.
- A third app in the browser-embedded family reusing this project's machinery.

> Guidance from `references/roadmap-guide.md`.
