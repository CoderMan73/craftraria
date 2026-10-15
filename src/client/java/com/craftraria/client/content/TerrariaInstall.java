package com.craftraria.client.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Recognizes a Terraria install the player points the mod at.
 *
 * <p>Deliberately narrow: {@code Content} exists and holds the three directories the wasm build
 * actually reads ({@code Fonts}, {@code Images}, {@code Sounds}). A directory missing any of them is
 * either the wrong folder or a partial install, and serving it produces a game that loads and then
 * fails on the first asset - so it is refused here, with a message that says what was missing.
 *
 * <p>The install path itself is recorded in config/craftraria.properties by the command in M3; this
 * class only answers whether a directory is worth pointing at.
 */
public final class TerrariaInstall {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	/** The directories the wasm build reads. Order is the order of the check output. */
	private static final List<String> REQUIRED_DIRECTORIES = List.of("Fonts", "Images", "Sounds");

	private TerrariaInstall() {
	}

	/** A directory that passes every requirement of {@link #REQUIRED_DIRECTORIES}. */
	public static boolean isValidContentDirectory(Path candidate) {
		if (candidate == null) {
			return false;
		}
		return missingDirectories(candidate).isEmpty();
	}

	/**
	 * The required directories that are absent from {@code candidate}; empty means it is valid.
	 * Returned rather than a boolean so the message shown to the player can name the problem.
	 */
	public static List<String> missingDirectories(Path candidate) {
		if (!Files.isDirectory(candidate)) {
			return List.copyOf(REQUIRED_DIRECTORIES);
		}
		List<String> missing = new java.util.ArrayList<>();
		for (String required : REQUIRED_DIRECTORIES) {
			if (!Files.isDirectory(candidate.resolve(required))) {
				missing.add(required);
			}
		}
		return missing;
	}
}
