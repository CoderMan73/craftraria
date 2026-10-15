# Serve the extracted build at the server root, alias the mod pages under /www/

Date: 2026-10-14
Status: accepted
Context: PLAN.md M4's mod-written page has to boot the pinned Terrarium build, whose bundled module
(`assets/index.js`) loads its runtime with `await eval('import("/_framework/dotnet.js")')` — an
absolute URL, evaluated at runtime, not configurable.

## Decision

`LocalHttpServer` is rooted at `<instance>/craftraria/game/build` (the extracted build) and the
mod-written pages are served under the `/www/` alias pointing at
`<instance>/craftraria/game/www`. Aliases are matched with the primary root checked first, so a file
present in the build can never be shadowed by an alias, and each root keeps its own traversal guard.
The loader page's URL is `http://localhost:<port>/www/index.html`.

## Alternatives considered

- **Root at `game/`, URL paths `/build/_framework/...`**: the module's absolute `/_framework/dotnet.js`
  import 404s. Would require patching the pinned game code — forbidden by AGENTS.md ("Never ...
  patching game code").
- **Copy the build next to the page**: duplicates ~140 MB of runtime into the served directory and
  breaks the single-source pin (the marker, the marker check, and the downloader all assume one
  extracted tree).
- **Symlink or mount `_framework` into the build root**: same shape as the alias with platform-specific
  failure modes (Windows symlink privileges, launcher relocation of the instance).

## Consequences

- The server's constructor takes the build directory; `CraftrariaClient.startServer` adds the alias
  right after. One extra line of wiring, no new failure mode: a missing build directory simply 404s.
- The page references its own assets relatively (`./content.tar`, `./libs/libcurl.wasm`) because it
  lives under the alias; the module's own assets resolve against the root. Verified end to end by the
  M4 probe (`ai-docs/notes/2026-10-14-m4-loader-page-probe.md`), including the page's content staging
  and the module's runtime import in one boot.
- If a future pinned build ever *does* take a configurable runtime base, this becomes unnecessary and
  the alias can be removed with one line.
