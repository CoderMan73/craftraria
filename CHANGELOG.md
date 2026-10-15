# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `docs/PROJECT_CONTEXT.md` (the single onboarding file) records that a run right after
  `gradlew clean` needs `--no-configuration-cache` once: Loom's `build/loom-cache` argFile is gone
  while the configuration cache still points at it, and the reuse then fails with
  `could not open build/loom-cache/argFiles/runClient`.
- The loader page mirrors the browser console into the window title (throttled), and
  `TerrariaBrowser` logs every title change: the pinned module narrates its own boot
  ("initializing dotnet", "loading libcurl", "mount_opfs: mounted opfs", `Init :`), and from
  Minecraft a stalled engine and a slow one used to look identical. Registered through
  `Rinku.getClient()`, which chains handlers - `browser.getClient()` would have replaced Rinku's own
  display handler, since a `RinkuBrowser` is built on Rinku's underlying JCEF handle.
- Readiness now also accepts the module's own mount marker (`#app[data-component="App"]`), and a page
  whose engine never reports ready says so in the title and uncovers the window rather than holding
  the player behind the overlay indefinitely.
- Initial scaffolding and foundation files (README.md, LICENSE, AGENTS.md, .gitignore, ROADMAP.md, CHANGELOG.md).
- `ai-docs/` for AI field notes, decisions, and lessons learned across agent sessions.
- `.agents/skills/` for repo-local skills.
- `docs/` reserved for human-facing documentation.
- `docs/commits-and-releases.md`: Conventional Commits, one-line `gradle.properties` versioning, the
  release runbook with local `clean build check` as the only gate, and the Linux-CI facts (gradlew
  executable bit, never gitignore `gradle-wrapper.jar`).
- Release-only GitHub Actions workflow (`.github/workflows/release.yml`): builds the tagged source
  and attaches the jar on a published release; pushes, merges and PRs cost no runner time.
- `ai-docs/notes/2026-10-14-rinku-toolchain-probe.md`: Rinku 3.0.5-26.2 is not published (public
  maven tops out at 3.0.4-26.2, class-file 69 = Java 25), so the toolchain is pinned to Java 25 and
  the Rinku coordinate is 3.0.4-26.2 until 3.0.5 ships.
- Fabric scaffold (Loom 1.15.5, loader 0.19.5, Fabric API 0.161.0+26.2, Cloth Config 26.2.155,
  Mod Menu 20.0.3, Rinku 3.0.4-26.2) with `compileClientJava` green on JDK 25, and the wrapper's
  executable bit and jar committed while `.gitignore` no longer hides `gradle-wrapper.jar`.
- craftlatro's embed classes ported and retargeted: `TerrariaBrowser` (parked singleton),
  `BrowserScreen`, `BrowserWindow`, `TitleBarButton`, `LocalHttpServer` (COOP/COEP, traversal guard,
  no-store on the loader page), `CraftrariaConfig`, `CraftrariaConfigScreen`, `CraftrariaPaths`,
  `CraftrariaModMenuApi`; commands `/craftraria open|stats|config` and the `B` open-key binding.
- `ServedPages`: installs the mod-written page from the jar into `game/www/` behind a version marker.
- M1 probe page (`assets/craftraria/www/probe.html`) and `/probe-report`, the mod server's only
  write endpoint, so a probe run's findings survive a screenshot.
- `ai-docs/notes/2026-10-14-jcef-environment-probes.md`: probes (a) and (b) pass inside Rinku's own
  Chromium, driven agent-side with no Minecraft and no player run - OPFS round-trip through the local
  server, cross-origin isolation, SharedArrayBuffer, and showDirectoryPicker / showOpenFilePicker /
  navigator.keyboard.lock all present (the milestone's "no browser picker" assumption was wrong).
- M2 `TerrariaBuildDownloader`: the pinned release-1.0 wasm build (URL, 36,314,788 bytes, sha256),
  verified before extraction, extracted into `<instance>/craftraria/game/build` with a completion
  marker, `sw.js` and upstream's `index.html` deliberately excluded, and path-normalized entries so
  no archive entry can escape the directory.
- M2 `ContentPacker` + `TerrariaInstall`: the player's Content directory packed into a dependency-free
  ustar `content.tar` with an mtime/size version marker, and install validation that requires
  `Fonts`, `Images`, `Sounds`.
- M2 probe (temp harness): staging, tar round-trip, `_framework` served and `sw.js` absent - 18/18.
- M3 `SteamContentFinder` (default roots, `libraryfolders.vdf` libraries, SteamPath registry) and
  `ContentChooser` (JFileChooser folder picker), wired into `TerrariaInstallResolver` and the
  `/craftraria load <path>` / `/craftraria pick` / `/craftraria reload` commands with path completion
  and the clickable pick link.
- M3 probe (temp harness): synthetic Steam layout, escaped-backslash vdf parsing, install-precedence
  and validation gating - 7/7.
- M4 `ai-docs/decisions/serve-build-at-server-root.md`: the server roots at the extracted build and
  aliases `/www/`, because the pinned module imports `/ _framework/dotnet.js` by absolute URL.
- M4 `ai-docs/decisions/local-libcurl-runtime.md` + `LibcurlRuntimeDownloader`: the one file upstream
  fetches from a CDN at boot is downloaded once from the package's first-party registry, pinned by
  tarball and extracted-file sha256, and served locally; the page substitutes it with a `fetch`
  wrapper instead of patching game code.
- M4 mod-written loader page (`assets/craftraria/www/index.html`): version-marker-gated OPFS staging
  of `content.tar` under `Content/`, the CDN-avoiding fetch shim, then the pinned module's boot. The
  staging overlay now stays up ("Starting the game engine...") until the game's own UI has mounted,
  instead of uncovering the dark gap during the dotnet runtime's cold start; a two-minute grace means
  the overlay can never trap the player.
- M4 probe (temp harness): the full chain runs to `Init : 466ms`, `MainLoop...` and FNA3D's OpenGL
  renderer inside Rinku's Chromium, with `mount_opfs: mounted opfs` / `called mount_opfs: 0` on the
  way. One-time staging verified: a second load in the same browser profile skips the unpack entirely
  (`Content already staged.`). `ai-docs/notes/2026-10-15-loader-page-one-time-staging.md`.
- M4 probe (temp harness): the full chain reaches `Program.PreInit()` - OPFS staging, local libcurl,
  `PreInit...`, and the game's own `Hi!` inside Rinku's Chromium, with `sw.js` 404ing by design.
- `docs/PROJECT_CONTEXT.md`, the single onboarding file.

### Changed
- `LocalHttpServer` gained `addAlias` (primary root first, aliases never shadow it) plus the
  `/probe-report` endpoint; the served root is now the build directory with `/www/` aliased.
- The browser window opens on `/www/index.html` and refuses to open until the build is staged and a
  valid Content directory is recorded, saying which one it is waiting for.
- The loader page's OPFS unpack stages on the main thread with 8 concurrent lanes and a
  promise-valued directory cache, replacing the serial one-path-per-file version (measured 64 files/s
  on the player's 9,656 files to 228 files/s on a 2,000-file tree in the same harness; a
  sync-access-handle worker was tried and measured *slower*, and did not ship -
  `ai-docs/notes/2026-10-14-opfs-staging-rate.md`). The page reports its own rate to the console,
  and a failed stage now names its reason there instead of only in the note text.

### Fixed
- `start(int)` recorded the requested port rather than the port actually bound (a port-0 bind handed
  out a URL for a port nothing listened on); it now reads the address back.
- **The embedded browser never booted in a real client.** Rinku starts its Chromium with
  `--disable-web-security` by default (`RinkuSettings.DEFAULT_CEF_DISABLE_WEB_SECURITY` is true),
  which disables cross-origin isolation: Chromium then ignores this mod's COOP/COEP headers, so
  `crossOriginIsolated` and `SharedArrayBuffer` are false and the Terrarium build's dotnet runtime
  aborts with `Assert failed: SharedArrayBuffer is not enabled on this page` - while the server, the
  headers and the OPFS staging were all correct the whole time. `CraftrariaClient` now turns that
  setting off before Rinku starts CEF (which is also the *secure* direction), and the loader page
  logs a boot check naming the isolation state, the SharedArrayBuffer availability and the headers it
  received, so this class of failure can never again look like a black window.
  `ai-docs/notes/2026-10-15-engine-never-ready.md`
- **The game showed its "install Terraria" splash with the player's Content already staged.**
  Upstream's `hasContent()` requires a `.ContentExists` marker at the OPFS root in addition to
  `Content/{Fonts,Images,Sounds}` - a sentinel it writes at the end of each of its own install paths
  and nothing else does. The loader page now writes it after a successful unpack and checks for it on
  the already-staged path, so   the game skips the install screen and opens on its play view. Verified
  by the game's own DOM turning from `[DIV#splash, DIV#main]` into `[DIV#main]`, then `Init :`,
  `MainLoop...` and FNA3D initializing.
- **A crash mid-pack left a plausible-but-partial `content.tar`.** The pack wrote the archive directly,
  so the `NTSTATUS` crash that interrupted it left 48 MB of a 751 MB archive with no version file -
  and because the truncation lands on a block boundary, the page's reader walked 9,656
  self-consistent entries and reported success, wrote its version marker, and the game then correctly
  refused to start on directories that were never written. `ContentPacker` now publishes atomically
  (staging file + move), the page's `readTar` requires the terminator block and refuses the version
  marker on a truncated archive while saying why, and the skip path verifies `Content/{Fonts,Images,
  Sounds}` before trusting the marker at all. Verified on the player's real Content: 751 MB across
  15,997 files, terminator intact. `ai-docs/notes/2026-10-15-loader-page-one-time-staging.md`
- `ContentPacker.pack` wrote its version marker without creating the parent directory, so packing
  into a not-yet-created served directory threw and returned null with only a warning - the page then
  404'd on `content.tar` while the title sat on "Fetching...". The packer now creates its parent and
  the probe harness asserts the pack.
- The libcurl tarball extractor read it as zip when npm ships gzipped tar, which yielded no entries
  and no error; it is a bounded ustar read now.
- The browser window no longer opens while Rinku's JCEF install has failed (it aborted the client
  with `NTSTATUS 0xCFFFFFFF`, no hs_err, on the first keypress). `CraftrariaClient` checks
  `RinkuDownloadListener.isFailed()` before opening and `BrowserScreen` closes itself on
  `onInit(false)`, both with the delete-`run/rinku-libraries` retry named in the message
  (`ai-docs/notes/2026-10-14-rinku-jcef-install-crash.md`).
- The window opened on "Not Found" because `ServedPages` installed from a hardcoded page list that
  never mentioned the M4 loader page, while the version marker still read "installed" - so
  `www/index.html` was never copied and the server had nothing to answer with. Pages are now
  discovered by walking the packaged directory (`ServedPages.pageNames`), which cannot drift, and the
  probe harness drives that helper over loom's dev resource root to prove both pages are found
  (7/7, `ai-docs/notes/2026-10-14-served-pages-discovery-bug.md`).
- `.gitignore` now covers `run/` (Loom dev output: the dev world, config, and Rinku's own ~190 MB
  JCEF install), which was untracked but unignored.
- Recorded the `clean`/configuration-cache interaction in full in `AGENTS.md` Gotchas and the release
  runbook, including that two build-script workarounds were measured not to work and must not be
  retried, and the rule that a verification pass may never hand back a post-`clean` state.

> Guidance from `references/changelog-guide.md` (Keep a Changelog). Replace scaffolding
> notes with per-milestone entries as work lands.
