Probes (a) OPFS and (b) picker/keyboard-lock both pass inside Rinku's Chromium, driven agent-side through plain JCEF with no Minecraft and no player run: OPFS write/read-back matched through the local server, crossOriginIsolated is true with SharedArrayBuffer available, and showDirectoryPicker / showOpenFilePicker / showSaveFilePicker / navigator.keyboard.lock all exist. Chromium 151.0.7922.34 (CEF 151.2.3, JCEF API 151.2.3.1).

**Problem**: PLAN.md M1 gates the whole milestone on these probes, and rung 4 of the verification ladder (a user opening the Terraria screen) cannot be reached from an agent. The probes therefore have to run without a player: same Chromium, no window, no Minecraft.

**Solution** (probe evidence, 2026-10-14):

The probe runs Rinku's *own* JCEF build - Keksuccino/jcef-rinku release `java-cef-2eb4ca2648bda91d1dfed81e9a37ba92e757aff9`, the commit Rinku 3.0.4-26.2's jar manifest records - with Rinku's own CEF setup copied from `CefUtil.init()` (switches `--autoplay-policy=no-user-gesture-required`,
`--disable-features=ImmersiveReadAnything`; settings `windowless_rendering_enabled`,
persistent cache path, `user_agent_product = "Rinku/2"`). It loads the mod's real bundled page
(`assets/craftraria/www/probe.html`, pulled out of the built jar) through the mod's real
`LocalHttpServer`, and the page POSTs its findings to `/probe-report`, which the probe reads back
from disk. Everything is temporary: the natives tarball is sha256-verified before use and unpacked
under the OS temp dir, never into the repo or the instance.

Probe results (from the probe page running inside that Chromium):

- `crossOriginIsolated: true`, `SharedArrayBuffer: true` - the COOP/COEP headers `LocalHttpServer`
  returns are enough for dotnet wasm's pthreads/OPFS requirements. This retires RESEARCH.md Unknown 4
  for the local-server path.
- `opfs: {"ok":true,"detail":"round-trip matched","quota":10737418472}` - `navigator.storage
  getDirectory()` writes and reads back a file served from `http://localhost:<port>`. The OPFS
  staging design (M4 extracts `content.tar` into OPFS through the local server) is unblocked.
- `showDirectoryPicker/showOpenFilePicker/showSaveFilePicker: true`, `navigator.keyboard.lock: true`
  - all of the M1 "does this exist at all" surfaces are present. **This refutes the planning
  assumption recorded in AGENTS.md and the milestones** ("design assumes no browser picker in the
  flow", "the file-picker surface ... is probed at M1"): inside Rinku's Chromium the pickers exist.
  What remains unproven is whether they *complete* under OSR with no user gesture (the probe only
  proves the functions are exposed), and that question is now optional - the mod-owned M3 flow
  (Steam detection + JFileChooser + `/craftraria load`) needs none of them.
- `userAgent: ... Rinku/2 Safari/537.36` - the port's own UA product is what ships.
- Paint frames observed: 1, `[load] end http=200` - the page loads and paints end to end.

**Why it mattered**: two working days of downstream design (M3's picker decision, M4's OPFS staging)
rested on assumptions no one had measured. The probe retires them with measurements, and its setup
is repeatable in under a minute from the pinned artifacts.

**Gotchas found while making it work** (each cost a failed run):

1. **A headless JVM must pump CEF's message loop explicitly.** JCEF's default pump rides the AWT
   event thread; a probe JVM with no AWT usage never starts the EDT, so `CefApp.N_DoMessageLoopWork()`
   must be called from the probe's wait loop or nothing ever loads (`loadEnded=false`, 0 paints, no
   timeout - just silence). In Minecraft this is invisible because Rinku/AWT is alive; a future
   Rinku-side probe harness has to carry this constraint.
2. **`client.createBrowser(url, true, false, null)` is the Swing path, not the headless one** -
   JCEF's `CefBrowserFactory` routes it to `CefBrowserOsrSwing`, which pulls in JOGL and needs a UI
   component. Rinku's browsers are plain `new CefBrowserOsr(client, url, transparent, null)` with
   `setCloseAllowed()` + `createImmediately()`; the ported `TerrariaBrowser` mirrors that and it
   works.
3. **`updateViewGeometry` needs a non-null `Point`** (`screenPoint_ = new Point(screenPoint)` NPEs on
   null) and is the only sizing API on the headless browser. Viewport defaults are 1x1.
4. **Disposing CEF after an incomplete/no report run crashes natively** (`EXCEPTION_ACCESS_VIOLATION`
   in `libcef.dll`) on some paths; the probe now tears CEF down only after a successful report and
   otherwise lets JVM exit take the helper processes. Not investigated further - out of scope for a
   probe, but it means "probe ends in a native crash" is a teardown symptom, not a browser failure.
5. `.gitignore` in this repo ignored `gradle/wrapper/gradle-wrapper.jar`; CI on Linux cannot build
   without it, so the entry is removed with a comment pointing at the release runbook.

**Probe (c) still open**: loading the pinned release-1.0 `_framework` through the local server and
reaching `Program.PreInit()`'s `mount_opfs()` - it needs M2's `TerrariaBuildDownloader` to have
staged the build, so it runs at the end of M2, not here.
