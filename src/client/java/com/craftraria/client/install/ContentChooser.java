package com.craftraria.client.install;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

/**
 * Lets the player point the mod at their own Terraria {@code Content} directory with a native
 * folder picker.
 *
 * <p>The JFileChooser path exists because auto-detection cannot see every install (Steam not
 * installed, a GOG install, a portable folder). It is invoked on the AWT thread and returns the
 * selection through a callback, because the caller is on Minecraft's thread and must not block.
 *
 * <p>The picker is a Minecraft-side fallback, not the browser's: the mod also refuses to run in a
 * headless JVM, where there is no display to draw a dialog on - a headless launch is a dedicated
 * server, where the mod does not run at all.
 */
public final class ContentChooser {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	private ContentChooser() {
	}

	@FunctionalInterface
	public interface SelectionCallback {
		void selected(Path directory);
	}

	/**
	 * Opens a directory-only picker and reports the chosen folder through {@code callback} (on the
	 * AWT thread, for good or ill: callers do not touch Minecraft state from it). Does nothing when
	 * there is no display, and reports null through the callback when the player cancels.
	 */
	public static void choose(SelectionCallback callback) {
		if (GraphicsEnvironment.isHeadless()) {
			LOGGER.warn("No display available; cannot open the Terraria Content picker");
			callback.selected(null);
			return;
		}
		SwingUtilities.invokeLater(() -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("Select your Terraria Content folder");
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			chooser.setAcceptAllFileFilterUsed(false);
			chooser.setFileFilter(new FileNameExtensionFilter("Terraria Content folder", "Fonts", "Images", "Sounds"));
			int result = chooser.showOpenDialog(null);
			if (result == JFileChooser.APPROVE_OPTION) {
				callback.selected(chooser.getSelectedFile().toPath());
			} else {
				callback.selected(null);
			}
		});
	}
}
