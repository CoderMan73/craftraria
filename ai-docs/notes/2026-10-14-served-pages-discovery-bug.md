The window opened on "Not Found" because `ServedPages` installed from a hand-written list of pages
that never mentioned the M4 loader page, and its version marker still read "installed" - so
`www/index.html` was never copied into the served directory and the server had nothing to answer
with. Discovery by directory walk now makes the list un-drift-able.

**Symptom** (user run, 2026-10-14): Rinku initialized correctly for the first time
(`[RINKU] Successfully initialized!`), the browser was created, the paint rate settled ~116 fps - the
whole embed stack worked - but the window body said `Not Found`, which is `LocalHttpServer`'s own
404 body, for `http://localhost:18080/www/index.html`.

**Cause**: `run/craftraria/game/www` held `probe.html`, `content.tar` (48 MB, the player's real
Content, correctly packed) and `libs/libcurl.wasm` - everything except `index.html`. `ServedPages`
installed only the names in its `BUNDLED_PAGES` list, and when I added the M4 loader page to
`assets/craftraria/www/` I never added it to that list. The `.pages-version` marker matched, so the
install short-circuited and logged nothing - a silently-incomplete install with a clean-looking
green log.

**Fix**: `ServedPages.pageNames(Path)` walks the packaged directory and installs everything it
finds; `packagedPages()` resolves the mod container's `assets/craftraria/www` (the dev run's client
resource root or the jar, whichever applies) and hands it to the walker. Version file bumped 1 -> 2
so an already-staged instance re-installs on next launch. The mod container root is used instead of a
classloader stream because a stream cannot be enumerated.

**Verification**: `ServedPagesProbe` (temp harness) drives the real `pageNames` over loom's dev
resource root - output `dev pages: [index.html, probe.html]`, 7/7 pass, including a jar-style nested
layout, the marker/hidden-file skip, and a missing directory returning empty rather than throwing.

**Why it matters**: it is the third time in this project that a hardcoded enumeration of things that
ship in the jar drifted from the jar (after the Cloth/Mod Menu coordinate probes and the libcurl
extractor). The general rule now recorded: anything that lists packaged files must be derived from
the package. It also hides badly - nothing in the log said a page was missing, because the marker
gate was designed to be silent on success and the list was believed to be the definition of success.
