# Blank Canvas: JCEF OSR WebGL Not Enabled

## Date
2026-10-17

## Status
Unresolved — blocked on user verification

## Context
The Terraria wasm build boots fully inside Rinku's embedded Chromium browser — the
game's `Init` runs, resolution changes to 1920x1014, world names appear in the document
title (observed via the title-reporter mirror), and Chromium paint callbacks fire at
53-119 fps (sampled by `TerrariaBrowser`). But the browser surface is blank: the user
sees the BrowserScreen's title bar and frame, the stats overlay, but nothing inside the
browser interior.

## Investigation

### What is NOT the problem
- `--disable-web-security` is correctly turned off by `CraftrariaClient.enableCrossOriginIsolation()`
  (sets `RinkuSettings.cefDisableWebSecurity=false` in-memory during `onInitializeClient`,
  before Rinku's lazy init via `MixinGui`). The game's JS engine runs, OPFS staging works,
  and the dotnet WebAssembly runtime initializes — all of which require SharedArrayBuffer.
- The texture pipeline is correct: `RinkuRenderer` creates a `GlTexture` via
  `RenderSystem.getDevice().createTexture()`, uploads paint frames via `glTexImage2D`,
  and `RinkuDirectTexture` bridges it to Minecraft's `TextureManager`. `BrowserScreen.extractRenderState`
  calls `browser.isTextureReady()` and blits with `RenderPipelines.GUI_TEXTURED`. The stats
  overlay rendering proves `extractRenderState` is being called.
- The server sends COOP/COEP headers on all responses. The `index.html` boot self-check
  was intended to log the isolation state (not visible without the Minecraft log).
- async content packing is fixed (moved to background thread).

### Root cause (hypothesis)
**JCEF offscreen rendering does not enable GPU/WebGL by default.**

In JCEF/Cef, when `windowless_rendering_enabled = true` (OSR mode), GPU acceleration
and WebGL are **disabled by default**. They must be enabled via command-line switches
such as `--enable-gpu`. Rinku 3.0.4's `CefUtil.init()` does NOT add any GPU-related
switches — it only adds:
- `--autoplay-policy=no-user-gesture-required`
- `--disable-features=ImmersiveReadAnything`
- `--disable-web-security` (configurable, turned off by Craftraria)
- `--enable-widevine-cdm` (configurable)

**No `--enable-gpu` switch is passed.**

Evidence from the CEF community:
- CEF Forum: "WebGL requires GPU acceleration which is disabled by default in current
  versions of cefclient when off-screen rendering is enabled (pass '--enable-gpu'
  command line flag to enable)."
- The cefclient sample is run with `cefclient.exe --off-screen-rendering-enabled --enable-gpu`
  to verify WebGL works in OSR.
- Without GPU, WebGL context creation fails silently — the canvas remains transparent.
  JavaScript, WebAssembly, and the game engine still run (explaining world names in
  the title and 53-119 fps paint callbacks), but the canvas never visibly renders.

### Why the game boots but canvas is blank
The Terrarium wasm build (Emscripten/dotnet) boots via WebAssembly regardless of WebGL
status. The dotnet runtime and game logic run. `document.title` is updated with world
names. CEF fires `onPaint` callbacks for the transparent/blank canvas at the configured
frame rate. But the canvas content is all-alpha (transparent), so the texture uploaded
to Minecraft is blank, and the player sees only the `BACKDROP_COLOR` (dark blue).

## Solution

Add GPU/WebGL-enabling CEF switches via a **Fabric Mixin** into Rinku's
`CefUtil.init()`. Since Rinku 3.0.4 is a Maven dependency (not built from source),
we cannot modify its source directly. A Mixin can inject the additional switches
before `CefApp.startup()` is called.

### Switches to add
- `--enable-gpu` — enables GPU process for WebGL rendering
- `--ignore-gpu-blocklist` — prevents GPU from being blacklisted by CEF's software fallback
- `--enable-unsafe-swiftshader` — enables SwiftShader as fallback if hardware GPU fails
- `--in-process-gpu` — runs GPU process in-process (more reliable for embedded OSR)

### Implementation plan
1. Add `mixins.craftraria.json` to `src/main/resources/` with a mixin targeting
   `de.keksuccino.rinku.util.CefUtil`
2. Create `CefUtilMixin.java` in `src/client/java/` that injects into `init()`:
   - At `@Inject(method="init", at=@At("HEAD"))` — too early, switches not built yet
   - At `@Inject(method="init", at=@At("RETURN"))` — too late, CefApp already started
   - **Better**: Use `@ModifyVariable` or `@Redirect` to add switches to the
     `cefSwitchesList` ArrayList right before `CefApp.startup()` is called
   - Or: Use `@Inject` at `@At("CONSTANT") "CefApp.startup"` — inject before the
     startup call, append switches to the list
3. Add the mixin config to `fabric.mod.json`

### Alternative approaches considered
- **Modify Rinku source in MCSourceCode**: Won't affect the Maven-published artifact
  used at runtime. Only works for local builds.
- **Fork and publish custom Rinku**: Too heavyweight for M1 scope.
- **Call `CefApp.startup()` ourselves with extra switches**: Rinku's `CefUtil.init()`
  checks the return value and fails if CEF is already started.
- **Use `CefApp.addAppHandler()`**: Must be called before `CefApp` initializes;
  `onBeforeCommandLineProcessing` in the handler could add switches, but requires
  the handler to be registered before Rinku calls `CefApp.getInstance()`.

### Notes for the next agent
- The Mixin target class is `de.keksuccino.rinku.util.CefUtil` (fully qualified)
- The method is `init()` (public static, no args)
- The key injection point is the `cefSwitchesList` ArrayList right before
  `CefApp.startup(cefSwitches)` is called (line 115 of CefUtil.java)
- The switch array is `String[] cefSwitches = cefSwitchesList.toArray(String[]::new)`
  at line 113, followed by `if (!CefApp.startup(cefSwitches))` at line 115
- Consider using `@Redirect` to intercept the `toArray` call and modify the result,
  or `@Inject` with `@At("HEAD")` of the `CefApp.startup` call site
- The AGENTS.md notes that `Rinku.VERSION` in the source is "3.0.5" while the
  published Maven version is "3.0.4-26.2" — the class/method signatures should
  match but double-check against the published jar
- Record the outcome in this file: whether `--enable-gpu` resolved the blank canvas

## References
- `src/client/java/com/craftraria/client/CraftrariaClient.java:304` — enableCrossOriginIsolation()
- `src/client/java/com/craftraria/client/browser/BrowserScreen.java:452` — texture blit
- `src/client/java/com/craftraria/client/browser/TerrariaBrowser.java:111` — browser creation
- `src/client/resources/assets/craftraria/www/index.html:106` — boot self-check
- `MCSourceCode/Rinku/common/src/main/java/de/keksuccino/rinku/util/CefUtil.java:104-117` — CEF init
- `MCSourceCode/Rinku/common/src/main/java/de/keksuccino/rinku/RinkuRenderer.java` — texture upload
