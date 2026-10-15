The player only pays the Content unpack once (verified: a second load in the same browser profile
skips it), and the dark gap that followed staging is fixed by keeping the overlay up until the
game's own UI appears. With that, the full chain runs: stage -> OPFS mount -> `PreInit` -> `Init` ->
`MainLoop` -> **FNA3D initializing an OpenGL renderer inside Rinku's Chromium**.

**Problem**: the player reported a black window after the faster unpack, asked whether the assets
were really loading, and asked for the unpack to happen only once.

**What the probes found** (same harness as the earlier notes: pinned build staged by the real
downloader, real `ContentPacker` tar, real `LocalHttpServer` with the `/www` alias, Rinku's own
Chromium 151.0.7922.34 booting the pinned module):

1. **One-time staging: PASSED.** Loading the page twice in one browser profile, the second load
   logged no fetch of `content.tar` and reported `Content already staged.` The OPFS marker written
   after a successful unpack is what makes that work, exactly as designed.
2. **The black window was a real UX gap in the page, not a failure of the game.** The pinned module
   keeps this page's `#app` element and fills it (`data-component="App"`, children `#splash` and
   `#main`), but only after the dotnet runtime's cold start. Between "content staged" and that
   moment the window showed the page background and nothing else, which reads as a broken mod. The
   page now keeps the staging overlay up - "Starting the game engine... / The first launch of the
   engine takes a moment; later ones are quick." - until `#app` has children, with a two-minute
   grace so the overlay can never trap the player.
3. **The whole chain works.** After the module mounts, clicking through its UI took the console to:
   `PreInit...` -> `Hi!` -> `calling mount_opfs` -> `mount_opfs: mounted opfs` -> **`called
   mount_opfs: 0`** -> `dotnet initialized` -> `Init...` -> **`Init : 466.21ms`** -> **`MainLoop...`**
   -> `FNA3D Driver: OpenGL` -> `OpenGL Renderer: WebKit WebGL` -> `MojoShader Profile: glsles3`.
   That is PLAN M4's `PreInit`/`Init`/`MainLoop` sequence and Terraria's own renderer coming up,
   inside a Minecraft window.

**Also observed, and not acted on**:

- The module's own UI is dark-themed (`DIV#app.dark`), which is why a plain screenshot of it looks
  like a dead black window even when it is working.
- Upstream's UI offers `BUTTON[Play]` plus its own dialogs (`Copy local assets`,
  `Download Assets from Steam`, `Upload Archive`, ...). Those exist because the port's UI is generic;
  the mod's `Content` hand-off (M3) bypasses them, but they are harmless.
- The pinned module still tries `navigator.serviceWorker.register("/sw.js")` and gets a 404 - by
  design, since the downloader excludes `sw.js`. Upstream catches it.
- Errors seen only under the probe's synthetic clicks (`Must be handling a user gesture`, one
  `NoModificationAllowedError` and one `NotFoundError` from upstream's file manager probing OPFS):
  those are consequences of dispatching untrusted events from a harness, not defects. A real click
  from the player is a user gesture.

**Bugs found while making the fix, both of which looked exactly like a hang**:

- A stray `}` left by an edit produced `Uncaught SyntaxError: Unexpected token '}'` and a page that
  never ran - the same silent-dark-window symptom. The page's inline script is now syntax-checked
  with `node --check` as part of the page edit, since the page has no other test.
- The first version of this fix reported the DOM before the cold dotnet boot had finished, which is
  what made `#app` look permanently empty. Any DOM claim about this page has to be taken after
  `dotnet initialized`, or it is a claim about a page that is still booting.
---

**THE SPLASH THAT WOULD NOT GO AWAY: a crash-truncated content.tar, 2026-10-15 (later session).**
With the browser finally isolated, the player reached the game's own UI - but the install splash
appeared, and the game's file picker crashed the client under OSR, so there was no working fallback.
Their content.tar had been written by the run that crashed with NTSTATUS 0xCFFFFFFF while packing:

- The tar was 48,183,808 bytes whose last 1024 bytes were **not** the two zero terminator blocks, and
  **no content.version existed at all** - the pack died between writing the tar and the marker.
- A complete pack of the player's real Content is **751,041,024 bytes across 15,997 files**, so the
  truncation lost ~93% of it. The reader still walked 9,656 self-consistent entries and said nothing:
  the truncation lands on a 512-byte block boundary, so every parsed entry looked fine and the
  archive simply ended.
- The page therefore unpacked a partial tree and wrote its version marker for it, and the game's own
  hasContent() correctly refused to start. The marker stood for a tree that did not exist.

**Three fixes, each closing one link in that chain:**

1. ContentPacker.pack writes to content.tar.staging and **moves it into place**, so a crash
   mid-pack can no longer publish a partial archive. Verified on the real Content: 751 MB, terminator
   intact, version file written, no staging file left behind.
2. The page's eadTar now requires the walk to **find the terminator block**; running out of blocks
   (or a header overrunning the archive) marks it truncated. A truncated pack is still unpacked -
   partial beats nothing - but the version marker is **not** written and the console says why, so the
   next launch unpacks again instead of trusting a half-tree.
3. The skip path no longer trusts the marker alone: it checks that Content/{Fonts,Images,Sounds}
   exist and re-stages if they do not. In the probe this fired as
   ersion marker matched but Content/{Fonts,Images,Sounds} is incomplete; staging again.

**Measured here**: staging real Content is bound by size, not count - 15,997 files (740 MB) at ~49
files/s in the harness, against 228 files/s for small synthetic files. The first unpack is a
multi-minute one-time cost, and the overlay says so.

**The pattern**: a crash-interrupted write left a *plausible* partial artifact, and every layer
downstream trusted it because nothing validated it. Three independent gates now have to agree before
the game may start: the archive's terminator, the mod's version marker, and the game's own directory
check.