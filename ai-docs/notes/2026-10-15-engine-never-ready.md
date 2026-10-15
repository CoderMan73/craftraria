---
---

**SECOND BUG, same session, one milestone further.** With the browser finally isolated, the player
reached the game's own splash screen - "You will need to install Terraria to your browser with one of
the following methods", with its `Copy local assets` / `Download Assets from Steam` / `Upload Archive`
buttons - even though the mod had already staged 9,656 files (48 MB) of their real Steam Content into
OPFS.

**Cause**: the game's own content check, in its bundled `assets/index.js`:

```js
async function hasContent(){
  const s = await rootFolder.getDirectoryHandle("Content", {create:false});   // rootFolder = OPFS root
  for (const g of ["Fonts","Images","Sounds"])
    try { await s.getDirectoryHandle(g, {create:false}) } catch { return false }
  return await rootFolder.getFileHandle(".ContentExists", {create:false}), true
}
```

It requires `Content/{Fonts,Images,Sounds}` **and a `.ContentExists` file at the OPFS root**. Upstream
writes that marker at the end of every one of its own install paths (`skipOobe`, copy-local-assets,
untar, Steam download - five separate writes in the bundle) and nothing else in the pipeline does. The
mod's `ContentPacker` stages exactly the three directories and never the marker, so `hasContent()` was
false with the content sitting right there.

**Fix**: `ensureContentExists()` in the loader page writes that marker after a successful unpack, and
checks for it on the already-staged path so a run that predates it is healed without another 48 MB
unpack.

**Verification** (same probe harness): after the fix the game's own DOM reads `APPKIDS=[DIV#main.]`
instead of `[DIV#splash, DIV#main]` - that is `initialHasContent` flipping true and the install screen
being skipped - followed by `Init : 1506ms`, `MainLoop...`, and `FNA3D Driver: OpenGL`. The game's own
file browser now lists `.ContentExists` beside `Content`.

**The pattern worth keeping**: the game's contract with its storage was not inferable from the
symptom (a content-selection screen while the content was already present), and it was a *sentinel*,
not any of the 15,000 real files. When a pre-staged store is rejected, look for the marker the app
writes at the end of its own install paths before suspecting the data.

---

**ROOT CAUSE FOUND, 2026-10-15 (same day, next session).** Rinku starts its Chromium with
`--disable-web-security` **by default** - `RinkuSettings.DEFAULT_CEF_DISABLE_WEB_SECURITY` is `true`,
and `CefUtil.init()` turns it into that switch unless it is turned off first. The flag disables
cross-origin isolation outright: Chromium ignores the COOP/COEP headers this mod's server sends on
every response.

**Reproduced in the probe harness by adding the same switch**:

```
[craftraria] boot check: crossOriginIsolated=false sharedArrayBuffer=false serviceWorkerController=false
                headers={"coop":"same-origin","coep":"require-corp","corp":"cross-origin"}
...
MONO_WASM: Assert failed: SharedArrayBuffer is not enabled on this page. Please use a modern browser
and set Cross-Origin-Opener-Policy and Cross-Origin-Embedder-Policy http headers.
```

Note the headers in that line: **they are present and correct**. The server was never the problem,
which is why every server-side check passed while the real client never booted. With the switch
removed, the identical page and server report `crossOriginIsolated=true sharedArrayBuffer=true` and
boot to `initializing dotnet`.

**Fix** (in `CraftrariaClient.enableCrossOriginIsolation`, first thing the client initializer does,
because Rinku reads the switches when it starts CEF): `Rinku.getSettings().setDisableWebSecurity(false)`.
It is also the *secure* direction - the flag is a blanket "ignore the same-origin policy" switch, and
a local http://localhost origin needs none of it.

**The diagnostic that found it** (added to the page, because a session was lost to guessing): the page
now logs a boot check before anything else - `crossOriginIsolated`, `SharedArrayBuffer`, `serviceWorkerController`,
and the COOP/COEP headers it receives when it fetches its own document. In the failing run those would have read `crossOriginIsolated=false` with the headers
present, which names the browser setting rather than the server. The general rule, recorded by the
player's own suggestion: **a page that depends on a browser capability should assert it and log it,
rather than leaving the failure to be inferred from a black window.**

The sections below describe the earlier hunt, kept because the ruled-out list is what made the
eventual cause easy to accept: the persistent profile, the OPFS lock, and a service worker were all
checked and cleared, and none of them were it.

---

The player's engine never reported ready in a real client (109 seconds, then closed), while every
probe of the same page boots to `MainLoop...` in seconds. Ruled out by experiment: the persistent
browser profile, a leftover CEF process holding the OPFS lock, and service-worker interference. The
mod now narrates the browser's own boot into the game log, which is what the next run will read.

**Symptom** (player, 2026-10-15): with the overlay fix in place, the window showed "Starting the
game engine..." and the note "The first launch of the engine takes a moment; later ones are quick."
for 1 minute 49 seconds. Nothing else happened, and the player closed it, reasonably unable to tell
a slow engine from a stalled one. The mod-side log showed the browser created, paint rates settling
at ~118fps, and nothing else.

**What the probe keeps proving** (same harness as the earlier notes): the page stages, the module
boots, `mount_opfs` succeeds, `Init : 653ms`, `MainLoop...`, FNA3D initializes. Also proven: a
second, separate JVM reusing the *same* CEF profile boots fine, so a warm profile is not the cause.

**Ruled out, with evidence**:

- *Persistent CEF profile* (`C:\Users\Lati\AppData\Local\Rinku\cef-cache`). The probe was pointed at
  a fixed cache dir and launched twice as separate JVMs; both booted the engine fully.
- *A leftover CEF process holding the OPFS lock* (which would hang `mount_opfs`, and
  `mount_opfs` runs before `#app` is filled, so it would present exactly as this). No
  `jcef_helper.exe` remains; every surviving `chrome.exe` is the player's own Helium browser.
- *Service-worker interference*. The profile's Service Worker database does hold registration
  entries for `http://localhost:18080/`, but `sw.js` is never served (the downloader excludes it), so
  each registration 404s and upstream catches it. The entries are failed attempts, not a live worker.

**The API trap worth recording**: the first attempt to observe the page's state used
`browser.getClient().addDisplayHandler(...)`. That would have **replaced the display handler Rinku
itself installs**: a `RinkuBrowser` is constructed with Rinku's underlying JCEF handle
(`super(client.getHandle(), ...)`), so `getClient()` returns that raw client, on which JCEF
stores exactly one display handler. The correct registration is `Rinku.getClient().addDisplayHandler(...)`
- Rinku's own `RinkuClient` keeps a list of handlers and chains every event to each of them. Two
paths, one silent difference, and the wrong one breaks Rinku rather than the mod.

**What now observes it**: the page mirrors the console into the window title (throttled to 4/s), and
`TerrariaBrowser` registers a display handler on Rinku's client that logs every title change at INFO.
So the next real run's log will contain, in the browser's own words, how far the engine got:
`terraria console: initializing dotnet`, `loading libcurl`, `PreInit...`, `mount_opfs: mounted opfs`,
`dotnet initialized`, `Init :`, or, if it truly stalls, the last line it reached. Readiness is now
detected from the module's own mount marker (`#app[data-component="App"]`) as well as its children,
and if nothing is ready after two minutes the page says so in the title and uncovers the window
instead of trapping the player behind the overlay.

**What is still unexplained**: the difference between Rinku's browser in the real client and the
plain `CefBrowserOsr` the probes use. The probes mirror Rinku's CEF settings (same switches, windowless
rendering, cache path, user-agent product) and its creation sequence (construct, `setCloseAllowed`,
`createImmediately`, resize), so the remaining candidates are things the probes do not reproduce:
Rinku's own render loop driving the message pump, Rinku's registered request/load handlers
interacting with the build's asset requests, or the viewport size at page load. The next run's
log will distinguish them: if the log shows `initializing dotnet` and then nothing, the runtime is
stalling; if it shows no console at all, the module's own loading is stalling.
