package com.craftraria.client.browser;

import com.craftraria.client.config.CraftrariaConfig;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.browser.CefPaintEvent;
import org.cef.handler.CefDisplayHandler;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns the single Chromium browser that shows the Terraria page.
 *
 * <p>The browser deliberately outlives {@link BrowserScreen}. Closing the screen only detaches it;
 * the page keeps running so reopening the game is instant instead of re-staging the assets. While
 * no screen is showing, the surface is parked: audio is muted, the paint rate is cut to a trickle
 * and the surface is shrunk, because Rinku pumps pending paint frames for every live browser on the
 * Minecraft render thread, and an unshown full-rate surface costs frames and makes noise while
 * playing.
 *
 * <p>Ported from craftlatro's {@code BalatroBrowser}; the sizing rules it encodes are the proven
 * ones: never create the browser through {@code Rinku.createBrowser(url, transparent, width,
 * height)}, and never let the surface exceed the window's own full-screen size. Both crashed the
 * client natively in craftlatro before the rules existed.
 */
public final class TerrariaBrowser {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");
	private static final int PARKED_WIDTH = 320;
	private static final int PARKED_HEIGHT = 180;
	private static final int PARKED_FRAME_RATE = 5;
	private static final int SAMPLE_WINDOW_MILLIS = 1000;
	private static final int SAMPLE_HISTORY = 15;
	private static final int MINECRAFT_FPS_SAMPLES = 3;
	private static final long FRAME_RATE_SYNC_INTERVAL_MILLIS = 1000L;

	private static final AtomicInteger paintsInWindow = new AtomicInteger();
	private static final int[] rateHistory = new int[SAMPLE_HISTORY];
	private static final int[] minecraftFpsSamples = new int[MINECRAFT_FPS_SAMPLES];
	private static int historyCount;
	private static int historyCursor;
	private static int minecraftFpsSampleCount;
	private static int minecraftFpsCursor;
	private static int lastPaintRate;
	private static long windowStartMillis;
	private static long lastFrameRateSyncMillis;
	private static long lastPaintMillis;
	private static double averagePaintIntervalMillis;
	private static volatile int lastPaintWidth;
	private static volatile int lastPaintHeight;

	private static final Consumer<CefPaintEvent> PAINT_COUNTER = event -> {
		if (event == null || event.getPopup()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastPaintMillis != 0) {
			long interval = now - lastPaintMillis;
			if (interval > 0) {
				if (averagePaintIntervalMillis == 0) {
					averagePaintIntervalMillis = interval;
				} else {
					// Exponential average: recent frames matter more, and one hitch cannot dominate.
					averagePaintIntervalMillis += (interval - averagePaintIntervalMillis) * 0.1;
				}
			}
		}
		lastPaintMillis = now;
		paintsInWindow.incrementAndGet();
		lastPaintWidth = event.getWidth();
		lastPaintHeight = event.getHeight();
	};

	private static RinkuBrowser browser;
	private static String activeUrl;
	private static boolean parked;
	private static int appliedFrameRate;
	private static boolean audioMuted;
	/** Guards the title reporter: acquire() re-runs on every rebind, the client is long-lived. */
	private static boolean titleReporterAttached;

	private TerrariaBrowser() {
	}

	/**
	 * Creates the browser for {@code url}, or attaches to the live one.
	 *
	 * <p>The caller sizes the browser through {@link #showViewport} on the same frame, which is what
	 * pins the page's resolution: the page reads its viewport once, while it loads, so the pin has
	 * to land before the game's canvas is created. The browser is deliberately created at Rinku's
	 * own default size rather than through {@code Rinku.createBrowser(url, transparent, width,
	 * height)}: that overload resizes before the caller can attach anything, and resizing a freshly
	 * created browser this way crashed craftlatro's client natively inside Rinku's async paint path.
	 *
	 * @return the live browser for {@code url}, or {@code null} when Chromium is not ready yet
	 */
	public static synchronized RinkuBrowser acquire(String url) {
		if (browser == null || !browser.isValid()) {
			releaseBrowser();
			if (!Rinku.isInitialized()) {
				return null;
			}
			LOGGER.info("Creating Terraria browser for {}", url);
			RinkuBrowser created = Rinku.createBrowser(url, false);
			created.useBrowserControls(false);
			browser = created;
			activeUrl = url;
			parked = false;
			appliedFrameRate = 0;
			audioMuted = false;
			paintsInWindow.set(0);
			windowStartMillis = 0;
			addPaintCounter(browser);
			attachTitleReporter();
			return browser;
		}
		if (!url.equals(activeUrl)) {
			LOGGER.info("Terraria page changed; reloading browser from {}", url);
			browser.loadURL(url);
			activeUrl = url;
		}
		return browser;
	}

	/** Applies the on-screen resolution, the audio state and the effective paint rate. */
	public static synchronized void showViewport(int width, int height) {
		if (width <= 0 || height <= 0) {
			return;
		}
		parked = false;
		if (browser == null || !browser.isValid()) {
			return;
		}
		applyFrameRateNow();
		if (audioMuted) {
			browser.setAudioMuted(false);
			audioMuted = false;
		}
		browser.resize(width, height);
	}

	/**
	 * Re-checks the paint rate once a second against Minecraft's observed frame rate.
	 *
	 * <p>Chromium painting faster than Minecraft composites is waste: Rinku keeps only the newest
	 * frame, so anything painted between two Minecraft frames is discarded without ever being shown.
	 * The paint rate is therefore capped to what Minecraft is actually displaying, which also eases
	 * the CPU the browser takes away from it. The cap uses the highest of the last few readings so a
	 * single hitch cannot stall the game inside.
	 */
	public static synchronized void observeFrameRate(int minecraftFps) {
		if (minecraftFps <= 0) {
			return;
		}
		minecraftFpsSamples[minecraftFpsCursor] = minecraftFps;
		minecraftFpsCursor = (minecraftFpsCursor + 1) % MINECRAFT_FPS_SAMPLES;
		minecraftFpsSampleCount = Math.min(minecraftFpsSampleCount + 1, MINECRAFT_FPS_SAMPLES);

		long now = System.currentTimeMillis();
		if (lastFrameRateSyncMillis != 0 && now - lastFrameRateSyncMillis < FRAME_RATE_SYNC_INTERVAL_MILLIS) {
			return;
		}
		lastFrameRateSyncMillis = now;
		applyFrameRateNow();
	}

	private static final int MIN_RUNTIME_FRAME_RATE = 60;

	private static int effectiveFrameRate() {
		int configured = CraftrariaConfig.getInstance().getBrowserFrameRate();
		int minecraftCeiling = highestRecentMinecraftFps();
		if (minecraftCeiling <= 0) {
			return configured;
		}
		return Math.max(MIN_RUNTIME_FRAME_RATE, Math.min(configured, minecraftCeiling));
	}

	private static int highestRecentMinecraftFps() {
		if (minecraftFpsSampleCount == 0) {
			return 0;
		}
		int highest = 0;
		for (int index = 0; index < minecraftFpsSampleCount; index++) {
			highest = Math.max(highest, minecraftFpsSamples[index]);
		}
		return highest;
	}

	private static void applyFrameRateNow() {
		if (browser == null || !browser.isValid()) {
			return;
		}
		int frameRate = effectiveFrameRate();
		if (frameRate == appliedFrameRate) {
			return;
		}
		try {
			browser.setWindowlessFrameRate(frameRate);
			appliedFrameRate = frameRate;
			LOGGER.info("Terraria browser paint rate set to {} fps", frameRate);
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not set the Terraria browser paint rate.", failure);
		}
	}

	/** Keeps the page session alive but silent and cheap while no screen is rendering it. */
	public static synchronized void park() {
		if (browser == null || !browser.isValid() || parked) {
			return;
		}
		try {
			browser.setAudioMuted(true);
			audioMuted = true;
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not mute the Terraria browser while parked.", failure);
		}
		try {
			browser.setWindowlessFrameRate(PARKED_FRAME_RATE);
			appliedFrameRate = PARKED_FRAME_RATE;
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not slow the parked Terraria browser.", failure);
		}
		browser.resize(PARKED_WIDTH, PARKED_HEIGHT);
		parked = true;
	}

	/** Advances the paint-rate sample and returns the observed repaints per second. */
	public static synchronized int samplePaintRate() {
		long now = System.currentTimeMillis();
		if (windowStartMillis == 0) {
			windowStartMillis = now;
			return lastPaintRate;
		}
		if (now - windowStartMillis < SAMPLE_WINDOW_MILLIS) {
			return lastPaintRate;
		}
		long elapsed = Math.max(1L, now - windowStartMillis);
		int paints = paintsInWindow.getAndSet(0);
		lastPaintRate = (int) Math.round(paints * 1000.0 / elapsed);
		windowStartMillis = now;
		rateHistory[historyCursor] = lastPaintRate;
		historyCursor = (historyCursor + 1) % SAMPLE_HISTORY;
		historyCount = Math.min(historyCount + 1, SAMPLE_HISTORY);
		return lastPaintRate;
	}

	/** Lowest repaint rate seen over the retained sample history, in paints per second. */
	public static synchronized int lowestPaintRate() {
		if (historyCount == 0) {
			return lastPaintRate;
		}
		int lowest = Integer.MAX_VALUE;
		for (int index = 0; index < historyCount; index++) {
			lowest = Math.min(lowest, rateHistory[index]);
		}
		return lowest;
	}

	/** Highest repaint rate seen over the retained sample history, in paints per second. */
	public static synchronized int highestPaintRate() {
		if (historyCount == 0) {
			return lastPaintRate;
		}
		int highest = 0;
		for (int index = 0; index < historyCount; index++) {
			highest = Math.max(highest, rateHistory[index]);
		}
		return highest;
	}

	public static synchronized int sampleHistorySeconds() {
		return historyCount;
	}

	/** Rolling average gap between repaints, or 0 when nothing has been measured yet. */
	public static synchronized double averagePaintIntervalMillis() {
		return averagePaintIntervalMillis;
	}

	public static synchronized boolean isParked() {
		return parked;
	}

	/** Most recent paint rate handed to Chromium, or 0 when nothing has been applied yet. */
	public static synchronized int appliedFrameRate() {
		return appliedFrameRate;
	}

	public static synchronized int lastPaintWidth() {
		return lastPaintWidth;
	}

	public static synchronized int lastPaintHeight() {
		return lastPaintHeight;
	}

	/** True when a browser session exists and has not been closed. */
	public static synchronized boolean isAvailable() {
		return browser != null && browser.isValid();
	}

	/** Drops the current session. The next {@link #acquire(String)} starts a fresh page. */
	public static synchronized void release() {
		releaseBrowser();
	}

	/**
	 * Runs a script in the page, for the few things the page has to be asked to do — nothing in the
	 * M1 embed needs this yet, but the OPFS save store (M5) will, and Chromium will not hand it over
	 * to the JVM any other way.
	 */
	public static synchronized void executeScript(String script) {
		if (browser == null || !browser.isValid()) {
			return;
		}
		try {
			browser.executeJavaScript(script, "", 1);
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not run a script in the page.", failure);
		}
	}

	/**
	 * Reloads the page inside the live session, which is how the game is told to start again after
	 * its served content changed or its resolution setting moved. Cheaper than {@link #release}: the
	 * session, the texture and the audio state all survive.
	 */
	public static synchronized void reloadPage() {
		if (browser == null || !browser.isValid()) {
			return;
		}
		browser.reload();
	}

	private static void addPaintCounter(RinkuBrowser target) {
		try {
			if (target instanceof CefBrowserOsr osrBrowser) {
				osrBrowser.addOnPaintListener(PAINT_COUNTER);
			}
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not observe Terraria browser repaints; the stats overlay will read 0.", failure);
		}
	}

	/**
	 * Logs what the page says about itself, in the page's own words.
	 *
	 * <p>The loader page reports its staging progress through the window title, and the pinned
	 * module narrates its engine boot to the browser console, which the page mirrors into the same
	 * title. From Minecraft's side a stalled engine and a slow one are otherwise the same dark
	 * window, which is exactly the state that cost a debugging session - so this is where those words
	 * reach the game log.
	 *
	 * <p>Registered on {@link Rinku#getClient()}, not on the browser's own client: a RinkuBrowser is
	 * constructed with Rinku's underlying JCEF handle, so {@code browser.getClient()} would replace
	 * the display handler Rinku itself installed there, while Rinku's client keeps a list of
	 * handlers and chains every event to each of them.
	 */
	private static void attachTitleReporter() {
		if (titleReporterAttached) {
			return;
		}
		try {
			Rinku.getClient().addDisplayHandler(new CefDisplayHandler() {
				@Override
				public void onTitleChange(CefBrowser view, String title) {
					if (title != null && !title.isBlank()) {
						LOGGER.info("Terraria page: {}", title);
					}
				}

				@Override
				public void onAddressChange(CefBrowser view, CefFrame frame, String url) {
				}

				@Override
				public void onFullscreenModeChange(CefBrowser view, boolean fullscreen) {
				}

				@Override
				public boolean onTooltip(CefBrowser view, String text) {
					return false;
				}

				@Override
				public void onStatusMessage(CefBrowser view, String value) {
				}

				@Override
				public boolean onConsoleMessage(CefBrowser view, CefSettings.LogSeverity level, String message,
						String source, int line) {
					return false;
				}

				@Override
				public boolean onCursorChange(CefBrowser view, int cursorType) {
					return false;
				}
			});
			titleReporterAttached = true;
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not observe the Terraria window's title; the page's progress will not be logged.",
					failure);
		}
	}

	private static void releaseBrowser() {
		if (browser != null) {
			if (browser.isValid() && Rinku.isInitialized()) {
				try {
					if (browser instanceof CefBrowserOsr osrBrowser) {
						osrBrowser.removeOnPaintListener(PAINT_COUNTER);
					}
				} catch (RuntimeException ignored) {
					// The listener is a no-op counter; a failed removal is harmless.
				}
				try {
					browser.close();
				} catch (RuntimeException failure) {
					LOGGER.warn("Failed to close the Terraria browser cleanly.", failure);
				}
			}
			browser = null;
		}
		activeUrl = null;
		parked = false;
		appliedFrameRate = 0;
		audioMuted = false;
		paintsInWindow.set(0);
		lastPaintRate = 0;
		lastPaintWidth = 0;
		lastPaintHeight = 0;
		windowStartMillis = 0;
		lastPaintMillis = 0;
		averagePaintIntervalMillis = 0;
		historyCount = 0;
		historyCursor = 0;
		minecraftFpsSampleCount = 0;
		minecraftFpsCursor = 0;
		lastFrameRateSyncMillis = 0;
	}
}
