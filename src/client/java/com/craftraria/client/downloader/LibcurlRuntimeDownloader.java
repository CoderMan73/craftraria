package com.craftraria.client.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
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
import java.util.zip.GZIPInputStream;

/**
 * Fetches the one runtime file upstream's boot script loads over the network at game start:
 * {@code libcurl.wasm}, the generic libcurl WebAssembly build the pinned port uses for its HTTP and
 * WebSocket path.
 *
 * <p>Upstream's {@code assets/index.js} fetches it from a CDN when the game boots. This project's
 * boundary forbids fetching runtime code through a CDN (see AGENTS.md Boundaries > Never), so the
 * mod downloads it once, at staging time, from the package's first-party registry, verifies the
 * tarball and the extracted file against pins recorded on 2026-10-14, and serves it from localhost.
 * The player's browser then fetches it from the mod's own server like every other served file.
 *
 * <p>Source: the npm package {@code libcurl.js@0.6.20}, whose tarball contains the same
 * {@code libcurl.wasm} the CDN URL serves; jsdelivr was 503-unavailable at pin time, the registry
 * is the authoritative origin, and the extracted file's sha256 is what the pin actually protects.
 */
public final class LibcurlRuntimeDownloader {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private static final String TARBALL_URL =
			"https://registry.npmjs.org/libcurl.js/-/libcurl.js-0.6.20.tgz";
	private static final String TARBALL_SHA256 =
			"c2568f431dd53ad96369d892ac900ab7e3d6abf8c2170c6b9704587dc5ea3253";

	/** npm tarballs keep every file under {@code package/}. */
	private static final String ENTRY_PREFIX = "package/";
	private static final String ENTRY_NAME = "libcurl.wasm";

	private static final long WASM_SIZE = 1_505_221L;
	private static final String WASM_SHA256 =
			"7921acf5a16b603a146798424e4dc47c6ee7c8e7f13a72e6a6fcb452295e50fe";

	private static final String MARKER_FILE_NAME = ".libcurl-complete";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private LibcurlRuntimeDownloader() {
	}

	/**
	 * Downloads and verifies {@code libcurl.wasm} into {@code <served>/libs/}, where the mod-written
	 * page takes it from instead of the CDN. Idempotent: a complete, pin-matching file is left alone.
	 *
	 * @param servedDirectory the served page directory ({@code game/www})
	 * @return true when {@code <served>/libs/libcurl.wasm} holds the pinned file afterwards
	 */
	public static boolean stage(Path servedDirectory) {
		Path target = servedDirectory.resolve("libs").resolve(ENTRY_NAME);
		if (isStaged(target)) {
			return true;
		}

		Path base = servedDirectory.resolve("libs");
		Path staging = base.resolve("libcurl.tgz.staging");
		try {
			Files.createDirectories(base);

			LOGGER.info("Downloading the pinned libcurl.js runtime from the npm registry");
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(TARBALL_URL))
					.header("User-Agent", "craftraria-mod")
					.GET()
					.build();
			HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(staging));
			if (response.statusCode() != 200) {
				LOGGER.error("HTTP {} downloading {}", response.statusCode(), TARBALL_URL);
				Files.deleteIfExists(staging);
				return false;
			}

			if (!TARBALL_SHA256.equals(sha256(Files.readAllBytes(staging)))) {
				LOGGER.error("The libcurl tarball did not match its pin; it has been discarded");
				Files.deleteIfExists(staging);
				return false;
			}

			extractWasm(staging, target);
			Files.deleteIfExists(staging);

			if (!isStaged(target)) {
				LOGGER.error("Extracted libcurl.wasm did not match its pin; it has been discarded");
				Files.deleteIfExists(target);
				return false;
			}

			Files.writeString(base.resolve(MARKER_FILE_NAME), String.join("\n",
					"url=" + TARBALL_URL,
					"tarballSha256=" + TARBALL_SHA256,
					"size=" + WASM_SIZE,
					"sha256=" + WASM_SHA256,
					"stagedAt=" + Instant.now(),
					""), StandardCharsets.UTF_8);

			LOGGER.info("Pinned libcurl.wasm staged at {}", target);
			return true;
		} catch (IOException | InterruptedException failure) {
			LOGGER.error("Could not stage the libcurl runtime", failure);
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** The file exists, is the pinned size and hashes to the pinned sha256. */
	public static boolean isStaged(Path target) {
		try {
			if (!Files.isRegularFile(target) || Files.size(target) != WASM_SIZE) {
				return false;
			}
			return WASM_SHA256.equals(sha256(Files.readAllBytes(target)));
		} catch (IOException failure) {
			LOGGER.warn("Could not check {}", target, failure);
			return false;
		}
	}

	private static void extractWasm(Path tarball, Path target) throws IOException {
		try (InputStream file = Files.newInputStream(tarball);
				InputStream gunzipped = new GZIPInputStream(file)) {
			// npm ships gzipped tar, not zip; the entry layout is flat, one 512-byte header each.
			byte[] header = new byte[512];
			while (readFully(gunzipped, header)) {
				if (isZeroBlock(header)) {
					break;
				}
				String name = readCString(header, 0, 100);
				long size = Long.parseLong(readCString(header, 124, 12).trim(), 8);
				if ((ENTRY_PREFIX + ENTRY_NAME).equals(name)) {
					Files.copy(new BoundedInputStream(gunzipped, size), target,
							StandardCopyOption.REPLACE_EXISTING);
					return;
				}
				skipFully(gunzipped, size + ((512 - (size % 512)) % 512));
			}
		}
		throw new IOException("libcurl.wasm is not in the pinned tarball");
	}

	private static boolean readFully(InputStream source, byte[] buffer) throws IOException {
		int read = 0;
		while (read < buffer.length) {
			int count = source.read(buffer, read, buffer.length - read);
			if (count < 0) {
				return false;
			}
			read += count;
		}
		return true;
	}

	private static void skipFully(InputStream source, long count) throws IOException {
		long remaining = count;
		while (remaining > 0) {
			long skipped = source.skip(remaining);
			if (skipped <= 0) {
				if (source.read() < 0) {
					throw new IOException("truncated tarball");
				}
				skipped = 1;
			}
			remaining -= skipped;
		}
	}

	private static boolean isZeroBlock(byte[] header) {
		for (byte b : header) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String readCString(byte[] header, int offset, int length) {
		int end = offset;
		while (end < offset + length && header[end] != 0) {
			end++;
		}
		return new String(header, offset, end - offset, StandardCharsets.UTF_8);
	}

	/** Exposes at most {@code length} bytes, so a tarball cannot stream past its owner entry. */
	private static final class BoundedInputStream extends FilterInputStream {
		private long remaining;

		BoundedInputStream(InputStream source, long length) {
			super(source);
			this.remaining = length;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int value = super.read();
			if (value >= 0) {
				remaining--;
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int count = super.read(buffer, offset, (int) Math.min(length, remaining));
			if (count > 0) {
				remaining -= count;
			}
			return count;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(super.available(), remaining);
		}
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
		}
	}
}
