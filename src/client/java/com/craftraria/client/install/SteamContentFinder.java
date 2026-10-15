package com.craftraria.client.install;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the player's Terraria {@code Content} directory on a Steam install.
 *
 * <p>Three sources, in order, because Steam installs are all three: the well-known default Steam
 * location, the {@code libraryfolders.vdf} libraries a player has added, and the registry key that
 * records where Steam itself was installed. Nothing here assumes a drive letter.
 *
 * <p>Every candidate is validated with {@link com.craftraria.client.content.TerrariaInstall} before
 * it is returned, so a half-installed game or the wrong folder never becomes the recorded path.
 */
public final class SteamContentFinder {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private static final String TERRARIA_APP_ID = "105600";
	private static final String INSTALL_DIR_NAME = "Terraria";

	private static final String[] DEFAULT_STEAM_ROOTS = {
			"C:\\Program Files (x86)\\Steam",
			"C:\\Program Files\\Steam",
	};

	private SteamContentFinder() {
	}

	/**
	 * The first valid Terraria {@code Content} directory found, or null when nothing on this machine
	 * passes validation. The null case is normal (Steam not installed, game not installed) and is why
	 * the picker and {@code /craftraria load} exist.
	 */
	public static Path findContentDirectory() {
		return findContentDirectory(steamRoots());
	}

	/**
	 * The first valid Terraria {@code Content} directory under the given Steam roots. Every root's
	 * {@code steamapps/common/Terraria/Content} is checked, and a root's {@code
	 * libraryfolders.vdf} contributes its libraries' content paths too, so a game installed on
	 * another drive is found from any root that knows about it.
	 *
	 * <p>Public because the probe harness drives it with synthetic roots: Steam's real layout is
	 * recreated in a temp directory and parsed here, which is what makes the VDF handling and the
	 * validation gate verifiable without a Steam install on the test machine.
	 */
	public static Path findContentDirectory(List<Path> steamRoots) {
		List<Path> roots = new ArrayList<>(steamRoots);
		for (Path steamRoot : steamRoots) {
			roots.addAll(libraryRootsFrom(steamRoot));
		}
		for (Path candidate : contentCandidates(roots)) {
			if (com.craftraria.client.content.TerrariaInstall.isValidContentDirectory(candidate)) {
				LOGGER.info("Found a Terraria Content directory at {}", candidate);
				return candidate;
			}
		}
		LOGGER.info("No validated Terraria Content directory found in the given Steam roots");
		return null;
	}

	/** Content-directory candidates for the given Steam roots, most likely first. */
	private static List<Path> contentCandidates(List<Path> steamRoots) {
		List<Path> candidates = new ArrayList<>();
		for (Path steamRoot : steamRoots) {
			candidates.add(steamRoot.resolve("steamapps/common").resolve(INSTALL_DIR_NAME).resolve("Content"));
		}
		return candidates;
	}

	/**
	 * Steam roots: the two default locations plus whatever the registry recorded. The registry is
	 * read with {@code reg} rather than JNA so no extra dependency rides along; failure to read it
	 * (non-Windows, missing reg) is silent, because the defaults usually suffice.
	 */
	private static List<Path> steamRoots() {
		List<Path> roots = new ArrayList<>();
		for (String path : DEFAULT_STEAM_ROOTS) {
			roots.add(Paths.get(path));
		}
		roots.addAll(libraryRoots());
		return roots;
	}

	/**
	 * Every Steam library reachable from the given Steam root: the root itself plus every path its
	 * {@code libraryfolders.vdf} lists. A library is a Steam install folder, so each one's {@code
	 * steamapps/common} is also searched.
	 */
	private static List<Path> libraryRootsFrom(Path steamRoot) {
		List<Path> roots = new ArrayList<>();
		Path libraryFile = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
		if (Files.isRegularFile(libraryFile)) {
			roots.add(steamRoot);
			roots.addAll(parseLibraryFolders(libraryFile));
		}
		return roots;
	}

	/** All Steam roots the machine could have: defaults plus the registry-recorded one. */
	private static List<Path> libraryRoots() {
		List<Path> roots = new ArrayList<>();
		for (Path steamRoot : allSteamInstallRoots()) {
			roots.addAll(libraryRootsFrom(steamRoot));
		}
		return roots;
	}

	private static List<Path> allSteamInstallRoots() {
		List<Path> roots = new ArrayList<>();
		for (String path : DEFAULT_STEAM_ROOTS) {
			roots.add(Paths.get(path));
		}
		Path registryRoot = registrySteamPath();
		if (registryRoot != null) {
			roots.add(registryRoot);
		}
		return roots;
	}

	/**
	 * Parses the {@code "path"} entries out of a {@code libraryfolders.vdf}. Public because the probe
	 * harness verifies the escape handling directly, against a vdf the mod did not write.
	 *
	 * <p>The file is Valve's key-values format; the paths appear as {@code "path" "D:\\SteamLibrary"}
	 * lines, one per library, and escapes are doubled backslashes.
	 */
	public static List<Path> parseLibraryFolders(Path vdfFile) {
		List<Path> libraries = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(vdfFile)) {
				String trimmed = line.trim();
				if (trimmed.toLowerCase(Locale.ROOT).contains("\"path\"")) {
					String[] parts = trimmed.split("\"");
					if (parts.length >= 4) {
						String raw = parts[3].replace("\\\\", "\\");
						if (!raw.isBlank()) {
							libraries.add(Paths.get(raw));
						}
					}
				}
			}
		} catch (IOException failure) {
			LOGGER.debug("Could not parse {}", vdfFile, failure);
		}
		return libraries;
	}

	/** Steam's own registry entry, or null when the registry is unreachable. */
	private static Path registrySteamPath() {
		try {
			Process process = new ProcessBuilder(
					"reg", "query",
					"HKCU\\Software\\Valve\\Steam",
					"/v", "SteamPath")
					.redirectErrorStream(true)
					.start();
			String output = new String(process.getInputStream().readAllBytes());
			process.waitFor();
			for (String line : output.split("\\R")) {
				String trimmed = line.trim();
				if (trimmed.toLowerCase(Locale.ROOT).startsWith("steampath")) {
					String value = trimmed.substring(trimmed.indexOf("REG_SZ") + 6).trim();
					if (!value.isBlank()) {
						return Paths.get(value.replace("/", "\\"));
					}
				}
			}
		} catch (IOException | InterruptedException failure) {
			Thread.currentThread().interrupt();
			LOGGER.debug("Could not read SteamPath from the registry", failure);
		}
		return null;
	}
}
