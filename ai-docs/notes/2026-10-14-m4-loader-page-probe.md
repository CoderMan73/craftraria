M4's loader page reaches `Program.PreInit()` inside Rinku's Chromium: content.tar unpacks into OPFS, the pinned module boots through the mod's own server, and the game logs its own greeting upstream of Init.

**Problem**: M4 needed a mod-written page (no bundler, vanilla JS) that reproduces upstream's preInit glue and runs PreInit / Init / MainLoop, and everything before that glue — the player's Content in OPFS, the pinned runtime reachable — had to work through the mod's own local server. PLAN's probe (c) is the gate: load release 1.0's `_framework` unmodified through the local server and reach `Program.PreInit()`'s `mount_opfs()` call.

**Solution** (probe evidence, 2026-10-14, same harness as the M1 note):

A synthetic instance — pinned build staged by `TerrariaBuildDownloader`, libcurl runtime staged by
`LibcurlRuntimeDownloader`, a fake Content packed by `ContentPacker`, the mod's own pages installed
from the built jar — is served by the real `LocalHttpServer` (rooted at the extracted build, with
`/www/` aliased to the mod-written pages) and loaded by Rinku's JCEF build
(chromium 151.0.7922.34, `--autoplay-policy=no-user-gesture-required`, windowless OSR, manual
message-loop pump as in the M1 probe).

Observed, in order (page titles carry the staging state; console lines are the page's own):

1. `[title] Terraria - Reading your Terraria Content...` → `Fetching...` → `Unpacking your Terraria
   Content into browser storage...` → `Starting Terraria...` — `content.tar` fetched from the local
   server and extracted into OPFS under `Content/`, with the version marker written beside it so a
   repeat visit skips the unpack.
2. `[console] initializing dotnet` then `loading libcurl` — the pinned module booted, and its CDN
   fetch of `libcurl.wasm` was served from the mod's local copy with **no console error**.
3. `[console] PreInit...` — the milestone's gate: `Program.PreInit()` ran, which is where the game
   mounts its OPFS filesystem.
4. `[console] Hi!` — the game's own C# program talking, upstream of any Init.
5. `[console] Registration failed with TypeError: ... sw.js ... 404` — **as designed**: the pinned
   build's service worker is never extracted (`EXCLUDED_FILES` in the downloader), so upstream's
   `navigator.serviceWorker.register("/sw.js")` gets a 404, upstream catches it and boots anyway.
   This is the "sw.js stripped" PLan line observed end to end.

Not observed, by design: `Init` / `MainLoop` (upstream's splash needs a Play click — a real input
event, so it is rung 4) and anything the game renders afterwards.

**Design decisions this forced** (all recorded as decisions):

- **The server roots at the extracted build, not at the mod's page directory.** The pinned module
  imports its runtime by absolute URL — `await eval('import("/_framework/dotnet.js")')` inside
  `assets/index.js` — so the build must sit at the server root. The mod's pages are aliased under
  `/www/` by `LocalHttpServer.addAlias`, which resolves the primary root first so a build file can
  never hide behind an alias. `AI-DECISIONS/serve-build-at-server-root.md`.
- **`libcurl.wasm` comes from a verified local copy, never the CDN.** Upstream fetches it from
  `cdn.jsdelivr.net` at boot; the project boundary forbids fetching runtime code through a CDN. The
  mod downloads the `libcurl.js@0.6.20` tarball from the package's first-party registry (jsdelivr
  returned 503 at pin time), verifies the tarball and extracted file against pins, and the page
  substitutes the local path with a `fetch` wrapper installed before the module loads. The game code
  that runs is byte-for-byte the pinned release's. `AI-DECISIONS/local-libcurl-runtime.md`.
- **Release 1.0 passes no jiterpreter option.** PLAN M4 mentions "jiterpreter options"; the string
  does not appear in the pinned `assets/index.js` at all. The module hardcodes its own config
  (`dotnet.withConfig({pthreadPoolInitialSize:16})`) and the mod's page does not override it.

**M5's page-to-Java channel is available without Rinku-specific API**: `RinkuBrowser` exposes no JS
binding, but `CefClient.addMessageRouter(CefMessageRouter)` exists in the pinned JCEF, and the live
browser's client is reachable as `browser.getClient()`. A message router gives the page
`window.cefQuery`, which is what a future save-tick or file transfer needs.

**Gotchas found while making it work**:

1. npm ships gzipped **tar**, not zip: the libcurl extractor's `ZipInputStream` yielded zero entries
   (no error, no file — the tamest possible failure). A minimal ustar reader now extracts
   `package/libcurl.wasm`, bounded so a malformed tarball cannot stream past its entry.
2. The page's OPFS marker read needs `{create:false}` on a file handle that may not exist and must
   not be created by asking for it — the create flag belongs only on the write path.
3. `addAlias` prefixes are matched against the *unstripped* request path; matching them against the
   filesystem path silently 404s the alias.
