package com.craftraria.client.config;

import com.craftraria.client.CraftrariaPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The settings the embedded browser and its window read, persisted to
 * {@code config/craftraria.properties}.
 *
 * <p>Ported from craftlatro's proven config with the same values and the same
 * write-through-as-edited behaviour. Game-specific entries (the Terraria install path, the first-run
 * fidelity warning) arrive with the milestones that consume them, so they are deliberately not here
 * yet.
 */
public class CraftrariaConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	/**
	 * Offscreen resolution as a fraction of the framebuffer area the screen occupies.
	 *
	 * <p>1.0 renders the browser at the same resolution Minecraft itself renders at, so the game is
	 * not upscaled. Lower values shrink the Chromium surface, which is the dominant cost while the
	 * screen is open, at the price of sharpness.
	 */
	public static final double DEFAULT_BROWSER_RENDER_SCALE = 1.0;
	public static final double MIN_BROWSER_RENDER_SCALE = 0.25;
	public static final double MAX_BROWSER_RENDER_SCALE = 2.0;

	/**
	 * How often Chromium repaints the offscreen surface, in frames per second.
	 *
	 * <p>A fresh install does not use this constant: it follows Minecraft's own framerate limit so
	 * the game inside matches everything around it without configuration. This value is only the
	 * last resort for when Minecraft's limit cannot be read at all.
	 */
	public static final int DEFAULT_BROWSER_FRAME_RATE = 120;
	public static final int MIN_BROWSER_FRAME_RATE = 10;
	public static final int MAX_BROWSER_FRAME_RATE = 240;

	/** Window modes for the Terraria screen: a framed window, or the whole GUI viewport. */
	public static final String WINDOW_MODE_FRAMED = "framed";
	public static final String WINDOW_MODE_FULLSCREEN = "fullscreen";
	public static final String DEFAULT_WINDOW_MODE = WINDOW_MODE_FRAMED;

	/**
	 * Whether the world keeps running while the Terraria screen is open.
	 *
	 * <p>The screen captures all input, so a running world can hurt the player (or drain food) while
	 * they cannot react. It is on by default because that is what makes the window feel like a
	 * picture-in-picture over a living world rather than a pause, and anyone who wants the old
	 * singleplayer pause back sets it to false.
	 */
	public static final boolean DEFAULT_WINDOW_WORLD_RUNNING = true;

	/**
	 * Whether the window keeps a 16:9 aspect ratio while it is resized. Holding shift during a drag
	 * frees the ratio for that drag, which is how a player gets a non-16:9 window when they want one.
	 */
	public static final boolean DEFAULT_WINDOW_ASPECT_LOCKED = true;

	/**
	 * Whether the open key is a toggle. With it on, the key opens the Terraria window and pressing it
	 * again closes it; with it off, a second press does nothing and the window only closes on Escape
	 * or the close button.
	 */
	public static final boolean DEFAULT_OPEN_TERRARIA_TOGGLE = true;

	/**
	 * Whether the world behind a framed Terraria window is blurred, the treatment a normal Minecraft
	 * screen applies. Off leaves the world sharp and fully visible.
	 */
	public static final boolean DEFAULT_WINDOW_BACKGROUND_BLUR = false;

	private static final int WINDOW_GEOMETRY_UNSET = 0;

	private static CraftrariaConfig INSTANCE = null;

	private final Path configPath;
	private final Properties properties;

	private CraftrariaConfig() {
		this.configPath = CraftrariaPaths.configFile();
		this.properties = new Properties();
		load();
	}

	public static synchronized CraftrariaConfig getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new CraftrariaConfig();
		}
		return INSTANCE;
	}

	/**
	 * Browser surface resolution as a multiple of the size the window gives it. Below 1.0 the surface
	 * is smaller than the window and the texture is stretched back up — the dominant cost lever while
	 * the Terraria screen is open, trading sharpness for frame rate. Above 1.0 the surface is larger
	 * than the window and the game renders above the window's resolution, which is sharper when the
	 * window is small but costs proportionally more to paint; it is capped by
	 * {@code BrowserScreen#effectiveRenderScale} so it never exceeds the largest surface the window
	 * itself can be. The game only picks up a new value when the page loads.
	 */
	public double getBrowserRenderScale() {
		return parseDouble("browserRenderScale");
	}

	public void setBrowserRenderScale(double scale) {
		double clamped = clampBrowserRenderScale(scale);
		properties.setProperty("browserRenderScale", String.valueOf(clamped));
		save();
	}

	/**
	 * How often Chromium repaints the offscreen surface, in frames per second.
	 *
	 * <p>An explicit value is honoured once the player sets one; otherwise this keeps following
	 * Minecraft, so a change to Minecraft's own framerate limit carries over.
	 */
	public int getBrowserFrameRate() {
		return parseInt("browserFrameRate", initialBrowserFrameRate(), MIN_BROWSER_FRAME_RATE, MAX_BROWSER_FRAME_RATE);
	}

	/**
	 * Paint rate for an install that has not chosen one: Minecraft's own framerate limit, so the
	 * game inside the browser matches everything around it out of the box. Falls back to the
	 * display's refresh rate when Minecraft is uncapped, because the limit then carries no
	 * information about how often frames actually reach the screen.
	 */
	private int initialBrowserFrameRate() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null && minecraft.options != null) {
				int limit = minecraft.options.framerateLimit().get();
				if (limit > 0 && limit < Options.UNLIMITED_FRAMERATE_CUTOFF) {
					return limit;
				}
			}
			return displayRefreshRate();
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not read Minecraft's framerate limit; using {} fps.", DEFAULT_BROWSER_FRAME_RATE);
			return DEFAULT_BROWSER_FRAME_RATE;
		}
	}

	private int displayRefreshRate() {
		long monitor = GLFW.glfwGetPrimaryMonitor();
		if (monitor == 0L) {
			return DEFAULT_BROWSER_FRAME_RATE;
		}
		var videoMode = GLFW.glfwGetVideoMode(monitor);
		if (videoMode == null) {
			return DEFAULT_BROWSER_FRAME_RATE;
		}
		try {
			int refresh = videoMode.refreshRate();
			return refresh > 0 ? refresh : DEFAULT_BROWSER_FRAME_RATE;
		} finally {
			videoMode.free();
		}
	}

	public void setBrowserFrameRate(int frameRate) {
		properties.setProperty("browserFrameRate",
				String.valueOf(Math.clamp(frameRate, MIN_BROWSER_FRAME_RATE, MAX_BROWSER_FRAME_RATE)));
		save();
	}

	public boolean isStatsOverlayEnabled() {
		return Boolean.parseBoolean(properties.getProperty("statsOverlay", "false"));
	}

	public void setStatsOverlayEnabled(boolean enabled) {
		properties.setProperty("statsOverlay", String.valueOf(enabled));
		save();
	}

	/**
	 * Window mode of the Terraria screen. Anything unrecognised reads as the framed window, so an
	 * older or hand-edited properties file cannot lock the screen into a bad state.
	 */
	public boolean isWindowFramed() {
		return !WINDOW_MODE_FULLSCREEN.equalsIgnoreCase(properties.getProperty("windowMode", DEFAULT_WINDOW_MODE));
	}

	public void setWindowMode(String mode) {
		String normalized = WINDOW_MODE_FULLSCREEN.equalsIgnoreCase(mode) ? WINDOW_MODE_FULLSCREEN : WINDOW_MODE_FRAMED;
		properties.setProperty("windowMode", normalized);
		save();
	}

	public boolean isWindowWorldRunning() {
		return Boolean.parseBoolean(properties.getProperty("windowWorldRunning", String.valueOf(DEFAULT_WINDOW_WORLD_RUNNING)));
	}

	public void setWindowWorldRunning(boolean running) {
		properties.setProperty("windowWorldRunning", String.valueOf(running));
		save();
	}

	public boolean isWindowAspectLocked() {
		return Boolean.parseBoolean(properties.getProperty("windowAspectLocked", String.valueOf(DEFAULT_WINDOW_ASPECT_LOCKED)));
	}

	public void setWindowAspectLocked(boolean locked) {
		properties.setProperty("windowAspectLocked", String.valueOf(locked));
		save();
	}

	public boolean isWindowBackgroundBlur() {
		return Boolean.parseBoolean(properties.getProperty("windowBackgroundBlur", String.valueOf(DEFAULT_WINDOW_BACKGROUND_BLUR)));
	}

	public void setWindowBackgroundBlur(boolean blur) {
		properties.setProperty("windowBackgroundBlur", String.valueOf(blur));
		save();
	}

	/**
	 * Stored window rectangle in GUI-scaled pixels, or {@code 0} when unset. GUI-scaled coordinates
	 * depend on the GUI scale, so readers clamp the rectangle into the viewport they open with
	 * rather than trusting it verbatim.
	 */
	public int getWindowX() {
		return parseInt("windowX", WINDOW_GEOMETRY_UNSET, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	public void setWindowX(int x) {
		properties.setProperty("windowX", String.valueOf(x));
		save();
	}

	public int getWindowY() {
		return parseInt("windowY", WINDOW_GEOMETRY_UNSET, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	public void setWindowY(int y) {
		properties.setProperty("windowY", String.valueOf(y));
		save();
	}

	public boolean isOpenTerrariaToggle() {
		return Boolean.parseBoolean(properties.getProperty("openTerrariaToggle", String.valueOf(DEFAULT_OPEN_TERRARIA_TOGGLE)));
	}

	public void setOpenTerrariaToggle(boolean toggle) {
		properties.setProperty("openTerrariaToggle", String.valueOf(toggle));
		save();
	}

	public int getWindowWidth() {
		return parseInt("windowWidth", WINDOW_GEOMETRY_UNSET, WINDOW_GEOMETRY_UNSET, Integer.MAX_VALUE);
	}

	public void setWindowWidth(int width) {
		properties.setProperty("windowWidth", String.valueOf(Math.max(WINDOW_GEOMETRY_UNSET, width)));
		save();
	}

	public int getWindowHeight() {
		return parseInt("windowHeight", WINDOW_GEOMETRY_UNSET, WINDOW_GEOMETRY_UNSET, Integer.MAX_VALUE);
	}

	public void setWindowHeight(int height) {
		properties.setProperty("windowHeight", String.valueOf(Math.max(WINDOW_GEOMETRY_UNSET, height)));
		save();
	}

	/**
	 * The player's own Terraria {@code Content} directory, recorded here rather than in the game
	 * directory like craftlatro did for its exe path: Cloth Config already owns {@code config/}, and
	 * a path recorded next to a mod's other settings survives an instance rename. Unset until the
	 * mod or the player points at one.
	 */
	public String getTerrariaContentPath() {
		return properties.getProperty("terrariaContentPath", "");
	}

	public void setTerrariaContentPath(String path) {
		properties.setProperty("terrariaContentPath", path == null ? "" : path);
		save();
	}

	public static double clampBrowserRenderScale(double scale) {
		if (!Double.isFinite(scale)) {
			return DEFAULT_BROWSER_RENDER_SCALE;
		}
		return Mth.clamp(scale, MIN_BROWSER_RENDER_SCALE, MAX_BROWSER_RENDER_SCALE);
	}

	private double parseDouble(String key) {
		String raw = properties.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return DEFAULT_BROWSER_RENDER_SCALE;
		}
		try {
			return clampBrowserRenderScale(Double.parseDouble(raw.trim()));
		} catch (NumberFormatException malformed) {
			LOGGER.warn("Invalid config value for {}: {}", key, raw);
			return DEFAULT_BROWSER_RENDER_SCALE;
		}
	}

	private int parseInt(String key, int fallback, int min, int max) {
		String raw = properties.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Math.clamp(Integer.parseInt(raw.trim()), min, max);
		} catch (NumberFormatException malformed) {
			LOGGER.warn("Invalid config value for {}: {}", key, raw);
			return fallback;
		}
	}

	private void load() {
		try {
			if (Files.exists(configPath)) {
				properties.load(Files.newBufferedReader(configPath));
				LOGGER.info("Loaded config from {}", configPath);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to load config, using defaults", e);
		}
	}

	private void save() {
		try {
			Files.createDirectories(configPath.getParent());
			properties.store(Files.newBufferedWriter(configPath), "craftraria config");
			LOGGER.info("Saved config to {}", configPath);
		} catch (IOException e) {
			LOGGER.error("Failed to save config", e);
		}
	}
}
