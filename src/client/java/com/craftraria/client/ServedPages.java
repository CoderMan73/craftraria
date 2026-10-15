package com.craftraria.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Installs the mod-written pages into the served directory.
 *
 * <p>The served page is ours, not the game's, so it ships in the jar and is copied into
 * {@code <instance>/craftraria/game/www} on client init. A plain "copy always" would rewrite a file
 * the live page may be reading on every dev restart, so the copy is gated by a version marker:
 * {@code assets/craftraria/www.version} is compared against {@code .pages-version} in the served
 * directory, and a mismatch re-copies every page under {@code assets/craftraria/www}. Bump that
 * version file whenever a page changes - the same version-pinning discipline the served URLs
 * themselves follow, and the reason a stale served page can never be mistaken for a broken mod.
 *
 * <p>Pages are discovered by walking the packaged directory rather than read from a hand-written
 * list, because a hardcoded list silently drifts: adding {@code index.html} to the resources while
 * the list still named only {@code probe.html} shipped a version whose window said "Not Found",
 * with nothing in the log to say why. Walking the jar cannot drift.
 */
public final class ServedPages {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private static final String PAGES_ROOT = "assets/craftraria/www";
	private static final String VERSION_RESOURCE = "assets/craftraria/www.version";
	private static final String MARKER_FILE_NAME = ".pages-version";

	private ServedPages() {
	}

	public static void install() {
		Path target = CraftrariaPaths.servedDirectory();
		String bundled = readResource(VERSION_RESOURCE);
		if (bundled == null) {
			LOGGER.warn("No bundled served-page version found; leaving {} untouched", target);
			return;
		}
		bundled = bundled.trim();

		if (bundled.equals(readFile(MARKER_FILE_NAME))) {
			return;
		}

		List<String> pages = packagedPages();
		for (String name : pages) {
			copyPage(target, name);
		}
		writeText(MARKER_FILE_NAME, bundled);
		LOGGER.info("Installed served pages (version {}) into {}: {}", bundled, target, pages);
	}

	/**
	 * Every file under {@code assets/craftraria/www} in the mod container, as paths relative to it.
	 *
	 * <p>The mod container's own root is used rather than a classloader lookup because it resolves
	 * identically in the development run (where the source-set resources are a directory) and in the
	 * packaged jar - a classloader stream cannot be enumerated at all.
	 */
	/**
	 * Every file under {@code root}, as slash-separated paths relative to it.
	 *
	 * <p>Separated from the container lookup so it can be driven against a plain directory - the
	 * probe harness points it at loom's dev resource root and the packaged jar path alike, which is
	 * what keeps the discovery honest in both environments.
	 */
	public static List<String> pageNames(Path root) {
		if (root == null || !Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(path -> !Files.isDirectory(path))
					.map(path -> root.relativize(path).toString().replace('\\', '/'))
					.filter(name -> !name.equals(MARKER_FILE_NAME))
					.filter(name -> !name.startsWith("."))
					.sorted()
					.toList();
		} catch (IOException failure) {
			LOGGER.warn("Could not list the packaged pages in {}", root, failure);
			return List.of();
		}
	}

	private static List<String> packagedPages() {
		var container = FabricLoader.getInstance().getModContainer("craftraria");
		if (container.isEmpty()) {
			LOGGER.warn("craftraria's own mod container is missing; no pages installed");
			return List.of();
		}
		Path root = container.get().findPath(PAGES_ROOT).orElse(null);
		if (root == null) {
			LOGGER.warn("Packaged pages directory {} not found; no pages installed", PAGES_ROOT);
			return List.of();
		}
		return pageNames(root);
	}

	private static void copyPage(Path target, String name) {
		try (InputStream source = ServedPages.class.getClassLoader().getResourceAsStream(PAGES_ROOT + "/" + name)) {
			if (source == null) {
				LOGGER.warn("Bundled page {} is missing from the jar; skipping", name);
				return;
			}
			Path destination = target.resolve(name);
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException failure) {
			LOGGER.warn("Could not install served page {}; the next run will retry", name, failure);
		}
	}

	private static String readResource(String resource) {
		try (InputStream source = ServedPages.class.getClassLoader().getResourceAsStream(resource)) {
			if (source == null) {
				return null;
			}
			return new String(source.readAllBytes());
		} catch (IOException failure) {
			LOGGER.warn("Could not read bundled resource {}", resource, failure);
			return null;
		}
	}

	private static String readFile(String marker) {
		Path file = CraftrariaPaths.servedDirectory().resolve(marker);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return Files.readString(file).trim();
		} catch (IOException failure) {
			LOGGER.warn("Could not read {}", file, failure);
			return null;
		}
	}

	private static void writeText(String marker, String content) {
		try {
			Files.writeString(CraftrariaPaths.servedDirectory().resolve(marker), content);
		} catch (IOException failure) {
			LOGGER.warn("Could not write {}", marker, failure);
		}
	}
}
