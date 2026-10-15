The Content unpack went from 64 files/s to a measured 228 files/s on a 2,000-file tree, in Rinku's
own Chromium, through the same server and page the player runs. Two much-hyped "fast paths" were
measured and rejected: a sync-access-handle worker was *slower* than plain awaited writes, and the
~50 ms-per-operation figure that started the investigation was an artifact of the probe harness.

**Problem**: first launch unpacked 9,656 files at 64 files/s (151 s), with a progress bar that
updated four times a second. The player asked for a way to speed it up.

**Measurements** (synthetic 2,000-file tree, `ContentPacker` pack, real `LocalHttpServer`, page
loaded in Rinku's JCEF build 151.0.7922.34, same windowless setup as the M1 probe):

| Path | Files/s | Notes |
|---|---|---|
| Original page (4 directory lookups re-resolved per file, serial) | 64 (player's run) | the baseline; the probe reproduced it as 42/s |
| Main thread + directory cache, serial | 42 | the cache alone: ~2x |
| Worker + `createSyncAccessHandle` + `flush()` per file | 29 | **rejected** |
| Worker, `flush()` removed | 34 | **rejected** |
| **Main thread + directory cache + 8 concurrent lanes** | **228** | shipped |

**Why concurrency won**: OPFS operations are asynchronous round-trips, so a serial loop spends the
whole latency of each with nothing else in flight; eight in flight turn it into a pipeline (~5x).
The worker's sync handles were pricier per operation than awaited ones in this Chromium, so the
worker lost *and* cost an extra file plus a message protocol. Phased timing (ms per 2,000 files,
worker path) was getFileHandle 11.9s, createSyncAccessHandle 18.1s, write 11.3s, close 15.4s,
`flush` 11.7s - which is what identified `flush()` as pure overhead, since `close()` already commits.

**The trap worth recording**: the first worker measurement read 5 files/s, not 29. The probe's
message loop was pumped once per 20 ms, and every OPFS round-trip needs a pump turn, so operations
serialized into pump-sized chunks. Pumping 20x per iteration took the same code from 5 to 29 files/s
- a 6x swing from the harness alone. **Any browser measurement in this project must state its pump
rate**, and a slow number is a harness symptom before it is a code symptom. (The real game pumps at
60-120 fps, which is also why the probe's absolute numbers understate it: the player's original 64/s
serial beat the probe's 42/s serial by 1.5x on the same work.)

**Bugs found on the way**, each of which looked like a hang:

- `ContentPacker.pack` wrote its version marker without creating the parent directory, so a pack
  into a not-yet-created served directory threw, logged a warning, and returned `null` - which the
  probe ignored. The page then 404'd on `content.tar` and showed the error only in the note text,
  while the title stayed on "Fetching...". Fixed by creating the parent in `pack`; the probe now
  asserts the return.
- A swallowed staging exception is invisible: the page's error path wrote only the note text, so a
  "frozen" title was indistinguishable from a slow unpack. The catch now `console.error`s the reason,
  which is also how the probe sees it.
- Two broken refactors (`resolveDir` undefined, a dropped `return`) each presented as a total
  silence with 18 paints; the console line is what named both.

**Current state**: `index.html` stages on the main thread with 8 lanes and a promise-valued
directory cache; `www.version` is 3 so the run instance re-installs it. The boot chain still reaches
`Program.PreInit()` afterwards (M4 probe re-run). The page prints its own rate to the console
(`[craftraria] staged 2000 files (0.1 MB) in 8.8s at 228 files/s`), so the player's run reports the
real number instead of an extrapolation.
