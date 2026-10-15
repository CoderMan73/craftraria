# PROJECT_CONTEXT

One file to get from zero to running. Everything here is also elsewhere; this is the shortest true
path.

## What this is

**craftraria** runs the genuine Terraria inside Minecraft Java Edition. Minecraft is the frame;
Terraria is the content. The mod fetches the player's own Terraria install and the Terrarium wasm
build (a recompile of the game's code, **not** shipped by this mod), serves them from a local HTTP
server with cross-origin isolation headers, and displays the game in an embedded Chromium browser
(Rinku) over a flat GUI screen. The player's own files are the source of every asset.

## Commands

```bash
JAVA_HOME=C:/Users/<you>/.jdks/loom-ea-25-loom+1-11   # any real JDK 25; PATH's java is 1.8
./gradlew build          # build the jar (Linux CI does the same on a release)
./gradlew runClient      # run a Minecraft client with the mod loaded
./gradlew idea           # IDE project configuration
```

**After `gradlew clean`, run `./gradlew runClient --no-configuration-cache` once.** Loom writes the
client's argument file into `build/loom-cache`, the configuration cache still points at it, and the
reuse then fails with `could not open build/loom-cache/argFiles/runClient` (a Java launcher error
that names no culprit). The one flagless re-run rebuilds the cache and everything works again.

Release/tag/release ceremony lives in [commits-and-releases.md](commits-and-releases.md).

## In-game

| Key / command | What it does |
|---|---|
| `B` (rebindable) | opens the browser screen; with the open-key toggle on, `B` again closes it |
| `/craftraria open` | same, for when the key is rebound away |
| `/craftraria pick` | native folder picker for the Terraria `Content` directory (auto-detected from Steam when possible) |
| `/craftraria load <path>` | point the mod at a `Content` directory by hand; the recorded path is suggested by tab completion |
| `/craftraria reload` | re-stage the served content and restart the game page |
| `/craftraria stats` | toggles the stats overlay; the report goes to the log every ~10s while open |
| `/craftraria config` | opens the settings screen (also the Mod Menu button) |
| Escape | closes the browser screen |

The screen is framed by default (title bar with close, reload, and mode buttons), remembers its size
and position, and keeps the world behind it running. While the browser screen is closed, the browser
session stays alive at a trickle rate and stays muted — reopen is instant. Opening before the pinned
build has finished downloading, or with no Content directory recorded, says so instead of failing
silently — the first run takes a moment for the ~40 MB of downloads.

## Where things live at runtime

Everything under the Minecraft **instance**, nothing under `~` ([TerrariaPaths](../src/client/java/com/craftraria/client/CraftrariaPaths.java)
resolves it all in one place):

| Path | Contents |
|---|---|
| `config/craftraria.properties` | the settings (Cloth Config's neighborhood, same rules) |
| `<instance>/craftraria/game/build/` | the pinned wasm build, unpacked at runtime; never in the repo |
| `<instance>/craftraria/game/www/` | the mod-written page, the packed `content.tar`, the local libcurl runtime |
| `/libsdl/tsaves` (OPFS) | **Terraria saves**, inside the browser's own storage; reload the page instead of restarting the client |

The local HTTP server is rooted at `game/build` and aliases `/www/` to the mod-written pages, because
the pinned module imports its runtime by absolute URL. First launch downloads and verifies the
~40 MB of pinned artifacts (build + libcurl runtime); nothing is re-fetched until the pins change.

## The verification ladder (never claim more than you ran)

1. `./gradlew build` - compiles only.
2. A standalone probe compiled against the real classes - the default move for logic that cannot run
   inside Minecraft. The server probe is the worked example:
   `javac -cp build/libs/craftraria-0.1.0+26.2.jar ServerProbe.java` then run it with slf4j on the
   classpath; it starts the real `LocalHttpServer` on an ephemeral port and asserts headers,
   traversal guarding, and the `/probe-report` round trip.
3. The `/craftraria stats` report, pasted by the user.
4. A user-driven `./gradlew runClient` - the only channel that reaches the browser; **an agent
   cannot open the Terraria screen** (it needs a real input event), so browser changes are handed
   back with exactly one thing to check.

The environment probes that prove the embedding works at all are recorded in
[ai-docs/notes/2026-10-14-jcef-environment-probes.md](../ai-docs/notes/2026-10-14-jcef-environment-probes.md)
(OPFS works, cross-origin isolation works, Chromium is 151), and the boot probe in
[ai-docs/notes/2026-10-14-m4-loader-page-probe.md](../ai-docs/notes/2026-10-14-m4-loader-page-probe.md)
(the mod-written page OPFS-stages the player's Content, boots the pinned module, and reaches
`Program.PreInit()`). Read both before touching the browser. All of them run agent-side: none of them
needs a player.

## Conventions

- Conventional Commits; version is one line in `gradle.properties`; releases are documented and
  tagged per [commits-and-releases.md](commits-and-releases.md).
- Java 25, standard Fabric modding. The served page is vanilla JS with no bundler.
- Non-obvious behavior goes in comments; decisions and field notes go in `ai-docs/`.
- Third-party game code, the wasm build, and the Chromium runtime never enter the repo or the jar.
