# craftraria

> A Fabric mod for Minecraft 1.26.2 that lets a player run the real Terraria from inside Minecraft. The player supplies their own Terraria `Content` directory.

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.26.2-blue)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric%201.26.2-black?logo=fabric)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=java)](https://adoptium.net/)

## Description

A Fabric mod for Minecraft 1.26.2 that lets a player run the real Terraria from inside Minecraft. The player supplies their own Terraria `Content` directory; the mod serves the [Terrarium](https://github.com/MercuryWorkshop/terraria-wasm) wasm port (release 1.0, verified at 36,314,788 bytes) plus the player's staged assets to an embedded Rinku Chromium browser, and surfaces it on a flat in-game screen with Minecraft controls mapped to the game. The game wasm build is downloaded by the player's own client at runtime from GitHub; no Re-Logic code or assets are bundled or distributed by the mod.

This is the second app in the browser-embedded family, following the same player-supplied-files -> local server -> embedded browser pattern as [craftlatro](https://github.com/CoderMan73/craftlatro).

## Table of Contents

- [Security](#security)
- [Installation](#installation)
- [Usage](#usage)
- [Contributing](#contributing)
- [License](#license)

## Installation

**Prerequisites**: Minecraft Java Edition 1.26.2, [Fabric Loader](https://fabricmc.net/) (with Fabric API), Java 21 or newer (the exact toolchain is pinned at milestone M1 after checking the resolved Rinku 3.0.5 artifact's class-file version; Rinku 3.0.5's own build declares Java 25, so 21 is not assumed). Cloth Config is a hard dependency. The Chromium runtime (~150 MB) is fetched at runtime by Rinku, and the Terraria wasm build (~135 MB uncompressed `_framework`) is fetched at runtime by the mod.

1. Install the [Fabric Loader installer](https://fabricmc.net/use/installer/) for 1.26.2 with Fabric API.
2. Download the latest `craftraria-*.jar` from the [releases page](https://github.com/CoderMan73/craftraria/releases).
3. Place the jar in your `mods/` folder and launch Minecraft 1.26.2 via the Fabric profile.
4. In-game, point the mod at your own Terraria install when prompted (see Usage) - the mod does not bundle Terraria.

## Usage

Currently in active development, tracking the milestones M0-M5 in `ROADMAP.md`. The intended v1 flow:

- The mod detects or asks for your Terraria install (Steam library scan including `libraryfolders.vdf`, a folder picker fallback, or the `/craftraria load <path>` command), and validates that it is or contains a `Content` folder with `Fonts`, `Images`, `Sounds`.
- On first launch it downloads the pinned Terrarium wasm build (release 1.0) from GitHub into the Minecraft instance and verifies its size. Nothing from the build is stored in the repo or the jar.
- The mod packs your `Content` directory into a tar, serves it with COOP/COEP headers on a local HTTP server, and the served page stages it into the browser's OPFS before the game starts.
- Open the flat in-game GUI screen to play. Minecraft keyboard/mouse input is mapped to the browser. Saves persist in the browser profile across sessions.
- The first run shows a fidelity-and-support warning that also names the Terraria version the pinned build expects when your detected install differs.

Legal: only your own purchased Terraria copy is used. Per the upstream port's README, do not publicly host the game assets without permission from Re-Logic; this is for personal use only.

## Security

No SECURITY.md is published yet. Vulnerability reports are handled per-repo by the maintainers. Note the project's core restriction: never bundle Re-Logic code/assets or redistributable runtimes in the mod jar (see [AGENTS.md](AGENTS.md) > Boundaries).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding conventions, and the pull request process.

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE) for details.

## Credits

- [craftlatro](https://github.com/CoderMan73/craftlatro) - the sibling mod whose architecture this project reuses
- Standard Readme spec - https://github.com/RichardLitt/standard-readme
- shields.io - https://shields.io/

> Guidance from `references/readme-guide.md` (standard-readme + shields.io).
