package com.craftraria.client.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

/**
 * Packs the player's own Terraria {@code Content} directory into a plain POSIX tar for the browser
 * to fetch and unpack into OPFS.
 *
 * <p>Hand-rolled rather than pulled from a dependency: the tar is written once per content change,
 * read once per page load, and the header format it needs is a two-page section of POSIX. The
 * round-trip probe in the probe harness reads archives this class writes, so the format is verified
 * against real extraction, not against self-consistency.
 *
 * <p>Only regular files and directories are packed. Symlinks are skipped on purpose: the player's
 * Content directory is not a trusted tar source, and a symlink entry would otherwise reproduce the
 * whole filesystem subtree inside OPFS.
 */
public final class ContentPacker {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private static final int BLOCK_SIZE = 512;
	private static final String VERSION_FILE_NAME = "content.version";

	private ContentPacker() {
	}

	/**
	 * Packs {@code contentDirectory} into {@code targetTar} and writes its version marker beside it.
	 *
	 * <p>The marker is what the page compares against its OPFS copy: the file count, total bytes and
	 * the newest mtime in the tree. If any of the three move, the repack the next launch produces is
	 * a different marker, and the page re-extracts. That is the same mtime/size discipline the build
	 * marker uses, and it is what keeps a stale OPFS copy from being served as current.
	 *
	 * @return the written marker contents, or null when packing failed
	 */
	public static String pack(Path contentDirectory, Path targetTar) {
		List<Path> files = collect(contentDirectory);
		if (files.isEmpty()) {
			LOGGER.warn("Nothing to pack from {}", contentDirectory);
			return null;
		}

		Path root = contentDirectory.toAbsolutePath().normalize();
		long totalBytes = 0;
		long newestMtime = 0;
		try {
			// The caller's served directory usually exists, but a pack that silently fails because
			// its parent does not is a failure nobody sees: the marker write below throws first, and
			// the caller only learns about it from a null return it may not check.
			Files.createDirectories(targetTar.getParent());
			for (Path file : files) {
				totalBytes += Files.size(file);
				FileTime time = Files.getLastModifiedTime(file);
				long millis = time.toMillis();
				if (millis > newestMtime) {
					newestMtime = millis;
				}
			}

			// Pack to a staging file and move it into place. A crash mid-pack used to leave a partial
			// content.tar behind, which then unpacked "successfully" into half a tree - the page
			// could not tell, and the game's own hasContent() failed on directories that were never
			// written. With an atomic publish the served archive is either the previous good one or
			// the new complete one, never half of either.
			Path staging = targetTar.resolveSibling(targetTar.getFileName() + ".staging");
			try (OutputStream out = Files.newOutputStream(staging)) {
				for (Path file : files) {
					writeEntry(out, root.relativize(file).toString().replace('\\', '/'), file);
				}
				// Two zero blocks terminate the archive.
				out.write(new byte[BLOCK_SIZE * 2]);
			}
			Files.move(staging, targetTar, StandardCopyOption.REPLACE_EXISTING);

			String marker = String.join("\n",
					"files=" + files.size(),
					"bytes=" + totalBytes,
					"mtime=" + newestMtime,
					"");
			Files.writeString(targetTar.resolveSibling(VERSION_FILE_NAME), marker, StandardCharsets.UTF_8);
			LOGGER.info("Packed {} files ({} bytes) into {}", files.size(), totalBytes, targetTar);
			return marker;
		} catch (IOException failure) {
			LOGGER.error("Could not pack {} into {}", contentDirectory, targetTar, failure);
			return null;
		}
	}

	/** Files and directories under {@code directory}, parents before children. */
	private static List<Path> collect(Path directory) {
		List<Path> found = new ArrayList<>();
		try (var walk = Files.walk(directory)) {
			walk.filter(path -> !path.equals(directory))
					.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()))
					.forEach(found::add);
		} catch (IOException failure) {
			LOGGER.warn("Could not walk {}", directory, failure);
		}
		return found;
	}

	private static void writeEntry(OutputStream out, String relativeName, Path file) throws IOException {
		byte[] content = Files.readAllBytes(file);

		byte[] header = new byte[BLOCK_SIZE];
		writeString(header, 0, 100, relativeName);
		writeOctal(header, 100, 8, 0_644);          // mode
		writeOctal(header, 108, 8, 0);              // uid
		writeOctal(header, 116, 8, 0);              // gid
		writeOctal(header, 124, 12, content.length);
		writeOctal(header, 136, 12, Files.getLastModifiedTime(file).toMillis() / 1000L);
		// checksum field is written as spaces until the sum is computed
		for (int i = 148; i < 156; i++) {
			header[i] = ' ';
		}
		header[156] = '0';                          // regular file
		writeString(header, 257, 6, "ustar");
		header[263] = '0';
		header[264] = '0';

		int checksum = 0;
		for (byte b : header) {
			checksum += b & 0xFF;
		}
		writeOctal(header, 148, 7, checksum);
		header[154] = 0;
		header[155] = ' ';

		out.write(header);
		out.write(content);
		int padding = (BLOCK_SIZE - (content.length % BLOCK_SIZE)) % BLOCK_SIZE;
		out.write(new byte[padding]);
	}

	private static void writeString(byte[] header, int offset, int length, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		int count = Math.min(bytes.length, length);
		System.arraycopy(bytes, 0, header, offset, count);
	}

	/** Writes an octal field followed by a NUL, as ustar requires. */
	private static void writeOctal(byte[] header, int offset, int length, long value) {
		String octal = Long.toOctalString(value);
		if (octal.length() > length - 1) {
			throw new IllegalArgumentException("value " + value + " does not fit an octal-" + length + " tar field");
		}
		for (int i = 0; i < length - octal.length() - 1; i++) {
			header[offset + i] = '0';
		}
		byte[] digits = octal.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(digits, 0, header, offset + length - octal.length() - 1, digits.length);
		header[offset + length - 1] = 0;
	}
}
