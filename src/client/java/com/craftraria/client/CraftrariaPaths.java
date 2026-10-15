package com.craftraria.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every file path the mod owns, in one place.
 *
 * <p>Minecraft's convention is that a mod keeps its settings under the instance's config directory and
 * its generated data under the instance root, never under the player's home directory: players move
 * instances between launchers, back them up, and delete them, and a mod that writes to {@code ~}
 * survives none of that. Cloth Config reads and writes {@code config/} for exactly this reason.
 *
 * <p>Unlike craftlatro, this project never wrote to a home directory in the first place, so there is
 * no legacy migration here: every path below is instance-local from the first commit.
 */
public final class CraftrariaPaths {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private static final String CONFIG_FILE_NAME = "craftraria.properties";
	private static final String MOD_DIRECTORY_NAME = "craftraria";
	private static final String SAVES_DIRECTORY_NAME = "saves";
	private static final String GAME_DIRECTORY_NAME = "game";
	private static final String WWW_DIRECTORY_NAME = "www";
	private static final String BUILD_DIRECTORY_NAME = "build";

	private CraftrariaPaths() {
	}

	/** {@code config/craftraria.properties}, in the instance's config directory like Cloth Config uses. */
	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
	}

	/** {@code config/craftraria/saves}, where save exports, import staging and backups live. */
	public static Path savesDirectory() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(MOD_DIRECTORY_NAME)
				.resolve(SAVES_DIRECTORY_NAME);
	}

	/** {@code <instance>/craftraria/game}, the wasm build and everything served to the browser. */
	public static Path gameDirectory() {
		return FabricLoader.getInstance().getGameDir()
				.resolve(MOD_DIRECTORY_NAME)
				.resolve(GAME_DIRECTORY_NAME);
	}

	/**
	 * {@code <instance>/craftraria/game/build}, where the pinned wasm build is unpacked. Nothing from
	 * here is ever copied into the jar; the build is fetched at runtime and verified by size.
	 */
	public static Path buildDirectory() {
		Path target = gameDirectory().resolve(BUILD_DIRECTORY_NAME);
		createIfAbsent(target);
		return target;
	}

	/**
	 * {@code <instance>/craftraria/game/www}, where the mod-written page and the packaged
	 * {@code content.tar} live. The local HTTP server is rooted one level up at {@link
	 * #gameDirectory()}, so this is a directory under the served root rather than the served root
	 * itself: the loader page lives beside the extracted build ({@code ../build/_framework}) instead
	 * of beside a copy of it, and one traversal guard covers both.
	 */
	public static Path servedDirectory() {
		Path target = gameDirectory().resolve(WWW_DIRECTORY_NAME);
		createIfAbsent(target);
		return target;
	}

	private static void createIfAbsent(Path directory) {
		try {
			Files.createDirectories(directory);
		} catch (IOException failure) {
			LOGGER.warn("Could not create {}; it will be retried on next use", directory, failure);
		}
	}
}
