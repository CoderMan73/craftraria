package com.craftraria.client.browser;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * The diagnostics the stats overlay and {@code /craftraria stats} read from.
 *
 * <p>Deliberately compact at M1: it reports exactly the signals that separate "the target" from
 * "what was applied" — Minecraft's frame rate against Chromium's measured repaint rate, the surface
 * Chromium is actually painting, and the values that produced both. The fuller report craftlatro
 * ships (heap, profile listing, clipboard copy) grows with the milestones that need it.
 */
public final class TerrariaStats {

	private TerrariaStats() {
	}

	public static List<String> reportLines() {
		return List.of(
				"minecraft=" + Minecraft.getInstance().getFps() + "fps",
				"chromiumPaints/s=" + TerrariaBrowser.samplePaintRate(),
				"paintSource=" + TerrariaBrowser.lastPaintWidth() + "x" + TerrariaBrowser.lastPaintHeight(),
				"renderScale=" + com.craftraria.client.config.CraftrariaConfig.getInstance().getBrowserRenderScale(),
				"targetFps=" + com.craftraria.client.config.CraftrariaConfig.getInstance().getBrowserFrameRate(),
				"windowFramed=" + com.craftraria.client.config.CraftrariaConfig.getInstance().isWindowFramed(),
				"browserParked=" + TerrariaBrowser.isParked()
		);
	}
}
