package com.craftraria.client.install;

import com.craftraria.client.CraftrariaPaths;
import com.craftraria.client.config.CraftrariaConfig;
import com.craftraria.client.content.ContentPacker;
import com.craftraria.client.content.TerrariaInstall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolves, records and stages the player's Terraria {@code Content} directory.
 *
 * <p>Three ways in, all ending in the same place: the path is recorded in config and the directory
 * is packed into {@code content.tar} for the page to fetch.
 * <ul>
 *   <li>Auto-detection, from Steam's default, library-folder and registry locations.</li>
 *   <li>The native folder picker, for a Steam-less or non-standard install.</li>
 *   <li>{@code /craftraria load <path>}, for hand-typed paths and scripted setups.</li>
 * </ul>
 * A directory that has not passed {@link TerrariaInstall} is never recorded: a wrong path that
 * looks valid until the game tries to load its first font is the failure mode this guards.
 */
public final class TerrariaInstallResolver {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private TerrariaInstallResolver() {
	}

	/** Result of a resolve or attempt: whether it worked, and what to tell the player. */
	public record Outcome(boolean success, String message) {
		static Outcome ok(String message) {
			return new Outcome(true, message);
		}

		static Outcome failure(String message) {
			return new Outcome(false, message);
		}
	}

	/**
	 * The recorded install path if it is still a valid Content directory, otherwise null. Validity is
	 * re-checked on read: the player may have moved or uninstalled the game between sessions, and a
	 * recorded path that no longer validates is reported as absent rather than trusted.
	 */
	public static Path recordedContentDirectory() {
		CraftrariaConfig config = CraftrariaConfig.getInstance();
		String recorded = config.getTerrariaContentPath();
		if (recorded.isBlank()) {
			return null;
		}
		Path candidate = Path.of(recorded);
		if (!TerrariaInstall.isValidContentDirectory(candidate)) {
			LOGGER.warn("Recorded Terraria Content path {} is no longer valid", recorded);
			return null;
		}
		return candidate;
	}

	/** Steam auto-detection, then the picker, then the recorded path: the first one that validates. */
	public static Outcome resolve() {
		Path recorded = recordedContentDirectory();
		if (recorded != null) {
			return Outcome.ok("Using the recorded Terraria Content at " + recorded);
		}

		Path detected = SteamContentFinder.findContentDirectory();
		if (detected != null) {
			return record(detected);
		}

		return Outcome.failure("No Terraria install was found. Use /craftraria pick or /craftraria load <path>.");
	}

	/** Validates, records and repacks a directory the player supplied. */
	public static Outcome record(Path candidate) {
		List<String> missing = TerrariaInstall.missingDirectories(candidate);
		if (!missing.isEmpty()) {
			return Outcome.failure("That directory is missing " + String.join(", ", missing)
					+ " - point at the Terraria Content folder.");
		}

		Path normalized = candidate.toAbsolutePath().normalize();
		CraftrariaConfig.getInstance().setTerrariaContentPath(normalized.toString());
		repack(normalized);
		return Outcome.ok("Terraria Content set to " + normalized
				+ (packedMarker() == null ? " (packing failed; see the log)" : ""));
	}

	/**
	 * Packs the recorded Content directory into {@code game/www/content.tar}, replacing the previous
	 * copy and its version marker so the page re-extracts on next load. Best-effort: a pack failure
	 * is logged and reported by {@link #record}, not thrown into the command thread.
	 */
	public static void repack(Path contentDirectory) {
		Path target = CraftrariaPaths.servedDirectory().resolve("content.tar");
		ContentPacker.pack(contentDirectory, target);
	}

	public static String packedMarker() {
		try {
			Path marker = CraftrariaPaths.servedDirectory().resolve("content.version");
			return java.nio.file.Files.exists(marker)
					? java.nio.file.Files.readString(marker).trim()
					: null;
		} catch (java.io.IOException failure) {
			LOGGER.warn("Could not read the packed content marker", failure);
			return null;
		}
	}
}
