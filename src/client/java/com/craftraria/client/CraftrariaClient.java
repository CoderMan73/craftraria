package com.craftraria.client;

import com.craftraria.client.browser.BrowserScreen;
import com.craftraria.client.browser.TerrariaBrowser;
import com.craftraria.client.browser.TerrariaStats;
import com.craftraria.client.config.CraftrariaConfig;
import com.craftraria.client.downloader.LibcurlRuntimeDownloader;
import com.craftraria.client.downloader.TerrariaBuildDownloader;
import com.craftraria.client.install.ContentChooser;
import com.craftraria.client.install.TerrariaInstallResolver;
import com.craftraria.client.install.TerrariaInstallResolver.Outcome;
import com.craftraria.client.server.LocalHttpServer;
import com.craftraria.client.ui.CraftrariaConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuSettings;
import de.keksuccino.rinku.binarydownload.RinkuDownloadListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Client wiring for the embed: the local server, the open key and the commands.
 *
 * <p>M1 scope. The game-loading half of craftlatro's equivalent (Steam detection, JFileChooser
 * fallback, auto-load, fidelity warning, saves) is deliberately absent: those arrive with M3 and M5.
 * What is here is the minimum that opens a real Chromium window on the local server, which is what
 * the M1 probes are run against.
 */
@Environment(EnvType.CLIENT)
public class CraftrariaClient implements ClientModInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

	public static final String KEY_OPEN_TERRARIA = "key.craftraria.open_terraria";

	/**
	 * A category of our own so the binding is not buried in Miscellaneous. Registered through
	 * {@link KeyMapping.Category#register} because it also appends the category to the controls
	 * screen's sort order, which the plain constructor does not.
	 */
	public static final KeyMapping.Category CRAFTRARIA_CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("craftraria", "controls"));

	private static final long STATS_LOG_INTERVAL_MILLIS = 10_000L;

	private static KeyMapping openTerrariaKey;
	private static KeyMapping openTerrariaKeyStatic;
	private static LocalHttpServer server;
	private static TerrariaBuildDownloader buildDownloader;
	private static boolean warnedServerUnavailable;
	private static long lastStatsLogMillis;

	@Override
	public void onInitializeClient() {
		enableCrossOriginIsolation();

		openTerrariaKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				KEY_OPEN_TERRARIA,
				InputConstants.KEY_B,
				CRAFTRARIA_CATEGORY
		));
		openTerrariaKeyStatic = openTerrariaKey;

		ServedPages.install();
		startServer();
		stageBuild();

		ClientTickEvents.START_CLIENT_TICK.register(CraftrariaClient::onClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> onClientStopping());
		registerCommands();
	}

	/**
	 * Fetches the pinned wasm build in the background.
	 *
	 * <p>First launch downloads ~36 MB, so this happens on its own daemon thread while the player
	 * keeps playing: the browser window refuses to open until {@link #staged} is set, with a status
	 * line saying the build is still on its way. Verified-size-or-nothing means a corrupted or
	 * truncated download is discarded rather than served.
	 */
	private static void stageBuild() {
		buildDownloader = new TerrariaBuildDownloader();
		Thread staging = new Thread(() -> {
			boolean staged = buildDownloader.stage(CraftrariaPaths.buildDirectory());
			if (staged) {
				// The page fetches this from our server instead of the CDN upstream would call;
				// staging it here means that substitution never depends on a third party being up.
				LibcurlRuntimeDownloader.stage(CraftrariaPaths.servedDirectory());
			}
		}, "craftraria-build-staging");
		staging.setDaemon(true);
		staging.start();
	}

	/**
	 * Serves the extracted wasm build on localhost and aliases the mod-written pages under
	 * {@code /www/}. The split is forced by the pinned module itself: its boot script imports its
	 * runtime by absolute URL ({@code import("/_framework/dotnet.js")}), so the build has to sit at
	 * the server's root, while the mod's own page and its packed content live one directory away
	 * under the alias. Started at client init rather than on first open so the port is claimable
	 * before any Chromium work happens, and so a port collision is reported once instead of at the
	 * worst moment.
	 */
	private static void startServer() {
		server = new LocalHttpServer(CraftrariaPaths.buildDirectory());
		server.addAlias("/www", CraftrariaPaths.servedDirectory());
		if (!server.start()) {
			LOGGER.error("Could not start the local game server; the Terraria window has nothing to load.");
		}
	}

	private static void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			dispatcher.register(ClientCommands.literal("craftraria")
					.then(ClientCommands.literal("open").executes(context -> {
						openBrowserIfReady();
						return 1;
					}))
					.then(ClientCommands.literal("load")
							.then(ClientCommands.argument("path", StringArgumentType.greedyString())
									.suggests((context, builder) -> {
										String recorded = CraftrariaConfig.getInstance().getTerrariaContentPath();
										if (!recorded.isBlank()) {
											builder.suggest(recorded);
										}
										return builder.buildFuture();
									})
									.executes(context -> {
										Outcome outcome = TerrariaInstallResolver.record(
												Path.of(StringArgumentType.getString(context, "path")));
										sendStatus(outcome.message());
										return outcome.success() ? 1 : 0;
									})))
					.then(ClientCommands.literal("pick").executes(context -> {
						ContentChooser.choose(directory -> {
							if (directory == null) {
								return;
							}
							Outcome outcome = TerrariaInstallResolver.record(directory);
							sendStatus(outcome.message());
						});
						sendStatus("Choose your Terraria Content folder in the dialog.");
						return 1;
					}))
					.then(ClientCommands.literal("reload").executes(context -> {
						handleReload();
						return 1;
					}))
					.then(ClientCommands.literal("stats").executes(context -> {
						toggleStatsOverlay();
						return 1;
					}))
					.then(ClientCommands.literal("config").executes(context -> {
						Minecraft client = Minecraft.getInstance();
						client.gui.setScreen(CraftrariaConfigScreen.create(client.gui.screen()));
						return 1;
					}))
			);
		});
	}

	/**
	 * Re-stages the served content for a live page.
	 *
	 * <p>Steam's Content set changes rarely, and the served page caches aggressively, so a directory
	 * the player swapped while the game was open is only visible after the page is dropped. The
	 * reload therefore refreshes the recorded install (auto-detect when unset), repacks
	 * {@code content.tar} and reattaches a fresh session - the game restarts from its opening menu,
	 * which is what the reload button on the title bar does too.
	 */
	private static void handleReload() {
		Path content = TerrariaInstallResolver.recordedContentDirectory();
		if (content == null) {
			Outcome resolved = TerrariaInstallResolver.resolve();
			sendStatus(resolved.message());
			if (!resolved.success()) {
				return;
			}
		} else {
			TerrariaInstallResolver.record(content);
		}
		TerrariaBrowser.reloadPage();
		sendStatus("Terraria reloaded.");
	}

	private static void toggleStatsOverlay() {
		CraftrariaConfig config = CraftrariaConfig.getInstance();
		boolean enabled = !config.isStatsOverlayEnabled();
		config.setStatsOverlayEnabled(enabled);
		String report = String.join(" | ", TerrariaStats.reportLines());
		LOGGER.info("craftraria stats {}: {}", enabled ? "overlay on" : "overlay off", report);
		sendStatus("craftraria stats " + (enabled ? "overlay on" : "overlay off")
				+ " - report logged to the console.");
	}

	private static void logPeriodicStats(Minecraft client) {
		if (!CraftrariaConfig.getInstance().isStatsOverlayEnabled() || client.gui.screen() == null) {
			lastStatsLogMillis = 0;
			return;
		}
		long now = System.currentTimeMillis();
		if (lastStatsLogMillis == 0) {
			lastStatsLogMillis = now;
			return;
		}
		if (now - lastStatsLogMillis < STATS_LOG_INTERVAL_MILLIS) {
			return;
		}
		lastStatsLogMillis = now;
		LOGGER.info("{}", TerrariaStats.reportLines());
	}

	private static void onClientTick(Minecraft client) {
		// Consume once per press rather than reading isDown every tick: holding the key used to
		// rebuild the screen on every tick in craftlatro, resetting the stored window geometry.
		if (openTerrariaKey.consumeClick()) {
			openBrowserIfReady();
		}

		logPeriodicStats(client);
	}

	/**
	 * Whether a key event is the bound open key. Compared through the KeyMapping rather than a
	 * hardcoded key so the screen sees whatever the player rebound it to.
	 */
	public static boolean isOpenTerrariaKey(KeyEvent event) {
		return openTerrariaKeyStatic != null && openTerrariaKeyStatic.matches(event);
	}

	/** True when a press of the open key should close the Terraria window instead of opening it. */
	public static boolean isOpenTerrariaToggleEnabled() {
		return CraftrariaConfig.getInstance().isOpenTerrariaToggle();
	}

	/** Whether a mouse event is the bound open key, for a binding rebound onto a mouse button. */
	public static boolean isOpenTerrariaMouseKey(MouseButtonEvent event) {
		return openTerrariaKeyStatic != null && openTerrariaKeyStatic.matchesMouse(event);
	}

	/**
	 * Turns off the one Rinku default that makes this mod impossible to run.
	 *
	 * <p>Rinku starts its Chromium with {@code --disable-web-security} by default
	 * ({@code RinkuSettings.DEFAULT_CEF_DISABLE_WEB_SECURITY} is true). That flag disables
	 * cross-origin isolation outright: Chromium ignores the {@code Cross-Origin-Opener-Policy} and
	 * {@code Cross-Origin-Embedder-Policy} headers this mod's server sends on every response, so
	 * {@code self.crossOriginIsolated} stays false, {@code SharedArrayBuffer} never appears, and the
	 * pinned port's own boot takes its "not crossoriginisolated: using service worker" fallback -
	 * which cannot work here, because the service worker it would use is deliberately excluded from
	 * the build. The observed symptom was a window that stayed on "Starting the game engine..."
	 * forever while the game itself reported the real reason to the console.
	 *
	 * <p>The server's headers, the OPFS staging, and the COOP/COEP design are all correct; this
	 * setting was the only thing standing between them and the browser. Turning it off is also the
	 * *secure* direction - the flag is a broad "ignore the same-origin policy" switch, and nothing
	 * this mod does with a local http://localhost origin needs it.
	 *
	 * <p>It runs first in this initializer because the switches are read when Rinku starts CEF, and
	 * Rinku initializes during the client's resource-reload phase, after mod initialization.
	 */
	private static void enableCrossOriginIsolation() {
		try {
			RinkuSettings settings = Rinku.getSettings();
			if (settings.isDisableWebSecurity()) {
				settings.setDisableWebSecurity(false);
				LOGGER.info("Turned off Rinku's --disable-web-security default: cross-origin isolation "
						+ "(COOP/COEP) is required for the Terrarium wasm build.");
			}
		} catch (RuntimeException failure) {
			// Not fatal by itself: the page's own boot self-check reports what is missing, and the
			// browser screen refuses to show a window that cannot work.
			LOGGER.warn("Could not turn off Rinku's --disable-web-security default; the embedded "
					+ "browser will not be cross-origin isolated.", failure);
		}
	}

	/**
	 * Whether the embedded browser can actually come up, and why not when it cannot.
	 *
	 * <p>Rinku downloads its own Chromium (JCEF) on first launch and publishes it into
	 * {@code <instance>/run/rinku-libraries}. That install step has failed on Windows with an
	 * {@code AccessDeniedException} on its own staging move (a transient lock, typically antivirus
	 * scanning the freshly extracted ~190 MB). When it fails, Rinku stays uninitialized, and asking
	 * it to initialize anyway is what crashed the whole client this session: opening the screen
	 * started a CEF startup with no installation to load. So the client refuses to open the screen at
	 * all in that state, with the retry that actually works in the message.
	 *
	 * <p>A download still in progress is not a failure: the screen opens and waits, because Rinku's
	 * own flow shows the downloader progress and finishes the initialization itself.
	 *
	 * @return true when the screen should open
	 */
	private static boolean isChromiumAvailable() {
		if (Rinku.isInitialized()) {
			return true;
		}
		if (RinkuDownloadListener.INSTANCE.isFailed()) {
			sendStatus("The embedded browser failed to install. Delete run/rinku-libraries and restart the "
					+ "game; if it keeps happening, a virus scanner locking that folder is the usual cause.");
			LOGGER.error("Rinku's JCEF install failed; the Terraria window will not open.");
			return false;
		}
		return true;
	}

	private static void openBrowserIfReady() {
		Minecraft client = Minecraft.getInstance();
		if (buildDownloader == null || !buildDownloader.isStaged()) {
			sendStatus("The game build is still downloading; the window will open once it is ready.");
			return;
		}
		if (!isChromiumAvailable()) {
			return;
		}
		if (TerrariaInstallResolver.recordedContentDirectory() == null) {
			Outcome resolved = TerrariaInstallResolver.resolve();
			if (!resolved.success()) {
				sendPickPrompt(resolved.message());
				return;
			}
			sendStatus(resolved.message());
		}
		if (server == null || !server.isRunning()) {
			if (!warnedServerUnavailable) {
				warnedServerUnavailable = true;
				LOGGER.error("Local game server is not running; cannot open the Terraria window.");
			}
			sendStatus("The local game server is not running; see the log for details.");
			return;
		}
		client.gui.setScreen(new BrowserScreen(server.getBaseUrl() + "www/index.html"));
	}

	private static void sendStatus(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal(message));
		}
	}

	/**
	 * Explains why the window did not open and offers a click that runs {@code /craftraria pick}.
	 * Ported from craftlatro's chat link because it was the single most-used affordance there: a
	 * player who never found the command otherwise had no way back into the game.
	 */
	private static void sendPickPrompt(String reason) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		Component link = Component.literal("[Click here to select your Terraria Content folder]")
				.withStyle(style -> style
						.withClickEvent(new ClickEvent.RunCommand("/craftraria pick"))
						.withHoverEvent(new HoverEvent.ShowText(
								Component.literal("Open the folder selector for your Terraria install")))
						.withUnderlined(true)
						.withBold(true));
		client.player.sendSystemMessage(Component.literal(reason + " ").append(link));
	}

	private void onClientStopping() {
		TerrariaBrowser.release();
		if (server != null) {
			server.stop();
			server = null;
		}
	}
}
