# Fetch libcurl.wasm from a verified local copy, never the CDN

Date: 2026-10-14
Status: accepted
Context: the pinned Terrarium build's boot script loads its HTTP/WebSocket helper at game start with
`libcurl.load_wasm("https://cdn.jsdelivr.net/npm/libcurl.js@0.6.20/libcurl.wasm")` — an absolute CDN
URL, called during `preInit`, before the game can start. The wasm file is not in the build artifact;
only the JS bindings are bundled inside `assets/index.js`.

AGENTS.md forbids fetching runtime code through a CDN ("Never: ... fetching ... upstream game code
through a CDN"), and PLAN.md M4 repeats it. Serving the game from a local HTTP server satisfies that
for everything except this one file, which the *page* would otherwise fetch from the internet at
every boot.

## Decision

The mod downloads `libcurl.wasm` itself, at staging time, into
`<instance>/craftraria/game/www/libs/libcurl.wasm`, and the mod-written page substitutes the local
path. The substitution is a `fetch` wrapper installed before the pinned module loads — not a patch,
not a service worker, not a modified copy of upstream's code.

The download is pinned three ways in `LibcurlRuntimeDownloader`:
- source: the `libcurl.js@0.6.20` package tarball from the package's first-party registry
  (`https://registry.npmjs.org/libcurl.js/-/libcurl.js-0.6.20.tgz`), sha256
  `c2568f431dd53ad96369d892ac900ab7e3d6abf8c2170c6b9704587dc5ea3253`;
- extracted file: sha256 `7921acf5a16b603a146798424e4dc47c6ee7c8e7f13a72e6a6fcb452295e50fe`,
  1,505,221 bytes;
- a completion marker beside the file, so a failed or partial staging is never served.

Records 2026-10-14: jsdelivr (both the `cdn.` and `fastly.` hostnames) returned 503 for this path at
pin time; the registry returned the identical tarball, and the extracted wasm is the same artifact
the CDN serves — the pin is what makes the source interchangeable, not the hostname.

## Consequences

- The player's browser makes no third-party request at game start; every byte comes from the mod's
  local server, which is already the COOP/COEP origin the game requires.
- A future upstream version that changes the libcurl version changes one downloader class: bump the
  three pins and the page's shim path stays the same.
- If upstream ever inlines a local libcurl.wasm into the build, this class can be deleted and the
  page's shim becomes a no-op that costs nothing.
