package com.craftraria.client.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches the pinned Terrarium wasm build into the player's Minecraft instance.
 *
 * <p>The artifact is upstream's: MercuryWorkshop/terraria-wasm release 1.0, a May 2025 decompile of
 * Terraria recompiled to WebAssembly. This mod never bundles it. The pin below (URL, exact byte
 * count, sha256) was recorded against the published release on 2026-10-14 and is what the
 * downloader verifies before extracting a single byte: a build that does not match all three is
 * deleted and reported, never served.
 *
 * <p>Extraction goes to {@code <instance>/craftraria/game/build} - never the repo - and is guarded
 * three ways: a completion marker means a half-extracted build is never mistaken for a good one, an
 * entry allowlist means only the wasm runtime and the port's own page assets land, and path
 * normalization rejects every entry that would escape the build directory.
 */
public class TerrariaBuildDownloader {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private static final String RELEASE_TAG = "1.0";
	private static final String BUILD_URL =
			"https://github.com/MercuryWorkshop/terraria-wasm/releases/download/1.0/terraria-wasm-build.zip";
	private static final long BUILD_SIZE = 36_314_788L;
	private static final String BUILD_SHA256 =
			"2ce530eeb56c2dc5a2fe1886ddbaae576b85283f020123a8902adaba6f899d6e";

	private static final String STAGING_FILE_NAME = "build.zip.staging";
	private static final String ZIP_FILE_NAME = "build.zip";
	private static final String MARKER_FILE_NAME = ".complete";

	/**
	 * Top-level directories inside the zip that are safe to serve: the wasm runtime itself and the
	 * port's own page assets (its loader, fonts and images - not Re-Logic's, which come from the
	 * player's Content directory as content.tar).
	 */
	private static final Set<String> ALLOWED_PREFIXES = Set.of("_framework/", "assets/");

	/** Top-level files kept from the build, all belonging to the port rather than the game. */
	private static final Set<String> ALLOWED_FILES = Set.of(
			"MILESTONE", "app.ico", "backdrop.png", "AndyBold.ttf", "logo.png");

	/**
	 * Files deliberately skipped. {@code sw.js} is upstream's service worker: it caches the whole
	 * game behind a MILESTONE key and re-injects COOP/COEP on the way out, which would fight the
	 * mod's own no-store loader page and version-pinned URLs. {@code index.html} is upstream's
	 * Blazor shell; the mod writes its own page (M4) that reproduces the preInit glue the Terrarium
	 * build expects.
	 */
	private static final Set<String> EXCLUDED_FILES = Set.of("sw.js", "index.html");

	private static final List<String> REQUIRED_FILES = List.of(
			"_framework/blazor.boot.json",
			"_framework/dotnet.js");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private volatile boolean staged;

	/**
	 * Downloads, verifies and extracts the pinned build if it is not already complete.
	 *
	 * @return true when {@code buildDirectory} holds a verified, complete build afterwards
	 */
	public boolean stage(Path buildDirectory) {
		if (isStaged(buildDirectory)) {
			LOGGER.info("Pinned Terrarium build {} is already staged", RELEASE_TAG);
			staged = true;
			return true;
		}

		Path gameDirectory = buildDirectory.getParent();
		Path stagingFile = gameDirectory.resolve(STAGING_FILE_NAME);
		try {
			Files.createDirectories(gameDirectory);

			LOGGER.info("Downloading the pinned Terrarium build {} ({} bytes)", RELEASE_TAG, BUILD_SIZE);
			if (!download(stagingFile)) {
				return false;
			}
			if (!verify(stagingFile)) {
				Files.deleteIfExists(stagingFile);
				LOGGER.error("Downloaded build did not match the pinned size or sha256; it has been discarded");
				return false;
			}

			// A previous failed extraction would leave a half-populated build directory behind.
			deleteDirectory(buildDirectory);
			Files.createDirectories(buildDirectory);
			extract(stagingFile, buildDirectory);

			Path marker = buildDirectory.resolve(MARKER_FILE_NAME);
			Files.writeString(marker, markerContents(), StandardCharsets.UTF_8);
			Files.move(stagingFile, gameDirectory.resolve(ZIP_FILE_NAME),
					StandardCopyOption.REPLACE_EXISTING);

			if (!isStaged(buildDirectory)) {
				LOGGER.error("Extraction finished but the build failed its own completeness check");
				return false;
			}

			LOGGER.info("Pinned Terrarium build {} staged in {}", RELEASE_TAG, buildDirectory);
			staged = true;
			return true;
		} catch (IOException | InterruptedException failure) {
			LOGGER.error("Could not stage the pinned Terrarium build", failure);
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public boolean isStaged() {
		return staged;
	}

	/**
	 * A staged build is a complete one: the marker exists and records this exact pin, and the
	 * runtime files the mod-written page loads are present.
	 */
	public static boolean isStaged(Path buildDirectory) {
		Path marker = buildDirectory.resolve(MARKER_FILE_NAME);
		if (!Files.isRegularFile(marker)) {
			return false;
		}
		try {
			String contents = Files.readString(marker, StandardCharsets.UTF_8);
			if (!BUILD_SHA256.equals(markerField(contents, "sha256"))) {
				return false;
			}
			for (String required : REQUIRED_FILES) {
				Path file = buildDirectory.resolve(required);
				if (!Files.isRegularFile(file) || size(file) == 0) {
					return false;
				}
			}
			return true;
		} catch (IOException failure) {
			LOGGER.warn("Could not read the staged build marker {}", marker, failure);
			return false;
		}
	}

	private boolean download(Path stagingFile) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BUILD_URL))
				.header("User-Agent", "craftraria-mod")
				.GET()
				.build();
		HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(stagingFile));
		if (response.statusCode() != 200) {
			LOGGER.error("HTTP {} downloading the pinned build from {}", response.statusCode(), BUILD_URL);
			Files.deleteIfExists(stagingFile);
			return false;
		}
		return size(stagingFile) == BUILD_SIZE;
	}

	private boolean verify(Path file) throws IOException {
		if (size(file) != BUILD_SIZE) {
			LOGGER.error("Pinned build size mismatch: expected {} bytes, got {}", BUILD_SIZE, size(file));
			return false;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(Files.readAllBytes(file));
			String hex = HexFormat.of().formatHex(hash);
			if (!BUILD_SHA256.equals(hex)) {
				LOGGER.error("Pinned build sha256 mismatch: expected {}, got {}", BUILD_SHA256, hex);
				return false;
			}
			return true;
		} catch (NoSuchAlgorithmException impossible) {
			// SHA-256 is required of every JVM; treat its absence as an unusable build.
			LOGGER.error("SHA-256 is unavailable in this JVM", impossible);
			return false;
		}
	}

	/**
	 * Extracts the allowed entries into {@code buildDirectory}, refusing anything that would escape
	 * it. Allowlisting plus path normalization is the zip-slip guard; no archive is trusted because
	 * it arrived over HTTPS.
	 */
	private void extract(Path zipFile, Path buildDirectory) throws IOException {
		Path normalizedRoot = buildDirectory.toAbsolutePath().normalize();
		int extracted = 0;
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();

				if (EXCLUDED_FILES.contains(name)) {
					continue;
				}
				boolean allowed = ALLOWED_FILES.contains(name) || isUnderAllowedPrefix(name);
				if (!allowed) {
					continue;
				}

				Path target = normalizedRoot.resolve(name).normalize().toAbsolutePath();
				if (!target.startsWith(normalizedRoot)) {
					LOGGER.warn("Skipping build entry outside the target directory: {}", name);
					continue;
				}

				if (entry.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				Files.createDirectories(target.getParent());
				try (InputStream source = zip.getInputStream(entry)) {
					Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				}
				extracted++;
			}
		}
		LOGGER.info("Extracted {} build files into {}", extracted, buildDirectory);
	}

	private static boolean isUnderAllowedPrefix(String name) {
		for (String prefix : ALLOWED_PREFIXES) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private String markerContents() {
		return String.join("\n",
				"release=" + RELEASE_TAG,
				"url=" + BUILD_URL,
				"size=" + BUILD_SIZE,
				"sha256=" + BUILD_SHA256,
				"extractedAt=" + Instant.now(),
				"") ;
	}

	private static String markerField(String contents, String key) {
		for (String line : contents.split("\n")) {
			if (line.startsWith(key + "=")) {
				return line.substring(key.length() + 1).trim();
			}
		}
		return null;
	}

	private static long size(Path file) throws IOException {
		return Files.size(file);
	}

	private static void deleteDirectory(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			walk.sorted(java.util.Comparator.reverseOrder())
					.forEach(path -> {
						try {
							Files.deleteIfExists(path);
						} catch (IOException failure) {
							throw new java.io.UncheckedIOException(failure);
						}
					});
		}
	}
}
