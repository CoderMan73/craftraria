package com.craftraria.client.ui;

import com.craftraria.client.config.CraftrariaConfig;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Cloth Config screen that edits {@link CraftrariaConfig}.
 *
 * <p>The values already persisted to {@code config/craftraria.properties}; this class
 * is only the presentation layer, so every entry writes straight through to the config object instead
 * of staging values for a later save.
 *
 * <p><b>Applying a change while the Terraria screen is not open.</b> Where a value is only consumed
 * from the screen's own render loop, nothing applies it mid-session, so it takes effect the next
 * time the Terraria window opens. The tooltips say so, because silently doing nothing until a reopen
 * otherwise looks like a broken setting. Values the browser itself reads (stats overlay, render scale,
 * repaint rate) do apply to an open window immediately.
 *
 * <p><b>Game-content changes.</b> They are deliberately not editable here: pointing the mod at the
 * player's own Terraria install will be {@code /craftraria load <path>} plus Steam detection, which
 * drops and rebuilds the browser session on its own, and the settings on this screen never
 * invalidate the running page.
 *
 * <p>The two categories follow the split the project is heading towards: everything that belongs to
 * the embedded browser sits under Browser — subdivided into General and Performance, because the
 * perf levers are the ones a player tunes last and does not want scrolling past presentation to
 * reach — and everything that belongs to the Terraria integration sits under Craftraria, which holds
 * the installation instructions and the fidelity disclaimer rather than any settings.
 */
public final class CraftrariaConfigScreen {

    /**
     * Tooltips are wrapped to this width before being handed to Cloth Config.
     *
     * <p>Cloth Config wraps entry tooltips to the screen width and positions them at the mouse, so an
     * unwrapped sentence renders as a single full-width band that covers the row being hovered and
     * makes the control underneath unreadable. Pre-wrapping to a tooltip width instead is what makes
     * it read as a tooltip box beside the cursor.
     */
    private static final int TOOLTIP_WIDTH = 220;

    private CraftrariaConfigScreen() {
    }

    /** Window presentation, mirroring the two modes {@link com.craftraria.client.browser.BrowserWindow} has. */
    private enum WindowMode {
        FRAMED,
        FULLSCREEN
    }

    public static Screen create(Screen parent) {
        CraftrariaConfig config = CraftrariaConfig.getInstance();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.craftraria.title"))
                // Entries save themselves as they are edited; there is no staged save to confirm.
                .setDoesConfirmSave(false);
        ConfigEntryBuilder entries = builder.entryBuilder();

        addBrowserCategory(builder, entries, config);
        addCraftrariaCategory(builder, entries, config);

        return builder.build();
    }

    private static void addBrowserCategory(ConfigBuilder builder, ConfigEntryBuilder entries, CraftrariaConfig config) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.craftraria.category.browser"));
        category.setDescription(new Component[]{
                Component.translatable("config.craftraria.category.browser.description"),
        });

        SubCategoryBuilder general = entries.startSubCategory(
                Component.translatable("config.craftraria.subcategory.general"))
                .setExpanded(true);
        general.add(windowModeEntry(entries, config));
        general.add(backgroundBlurEntry(entries, config));
        general.add(aspectLockedEntry(entries, config));
        general.add(pauseOnOpenEntry(entries, config));
        general.add(openToggleEntry(entries, config));
        category.addEntry(general.build());

        SubCategoryBuilder performance = entries.startSubCategory(
                Component.translatable("config.craftraria.subcategory.performance"))
                .setExpanded(true);
        performance.add(renderScaleEntry(entries, config));
        performance.add(frameRateEntry(entries, config));
        performance.add(statsOverlayEntry(entries, config));
        category.addEntry(performance.build());
    }

    private static void addCraftrariaCategory(ConfigBuilder builder, ConfigEntryBuilder entries, CraftrariaConfig config) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.craftraria.category.craftraria"));
        category.setDescription(new Component[]{
                Component.translatable("config.craftraria.category.craftraria.description"),
        });

		// Nothing here is tunable yet: the Terraria Content hand-off (Steam detection, JFileChooser
		// fallback, /craftraria load) lands with the M3 milestone, and the first-run fidelity warning
		// with M5. The settings themselves all live under Browser.
        category.addEntry(entries.startTextDescription(Component.translatable("config.craftraria.gameContent"))
                .setColor(0xFFB0B0B0)
                .build());

        category.addEntry(entries.startTextDescription(Component.translatable("config.craftraria.runtime"))
                .setColor(0xFFB0B0B0)
                .build());
    }

    private static AbstractConfigListEntry windowModeEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startEnumSelector(
                        Component.translatable("config.craftraria.windowMode"),
                        WindowMode.class,
                        currentWindowMode(config))
                .setEnumNameProvider(mode -> Component.translatable("config.craftraria.windowMode." + labelSuffix(mode)))
                .setDefaultValue(currentWindowMode(config))
                .setSaveConsumer(newWindowMode(config))
                .setTooltip(tooltip("config.craftraria.windowMode.tooltip"))
                .build();
    }

    private static AbstractConfigListEntry backgroundBlurEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startBooleanToggle(
                        Component.translatable("config.craftraria.backgroundBlur"),
                        config.isWindowBackgroundBlur())
                .setDefaultValue(config.isWindowBackgroundBlur())
                .setSaveConsumer(config::setWindowBackgroundBlur)
                .setTooltip(tooltip("config.craftraria.backgroundBlur.tooltip"))
                .build();
    }

    private static AbstractConfigListEntry aspectLockedEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startBooleanToggle(
                        Component.translatable("config.craftraria.aspectLocked"),
                        config.isWindowAspectLocked())
                .setDefaultValue(config.isWindowAspectLocked())
                .setSaveConsumer(config::setWindowAspectLocked)
                .setTooltip(tooltip("config.craftraria.aspectLocked.tooltip"))
                .build();
    }

    private static AbstractConfigListEntry renderScaleEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startIntSlider(
                        Component.translatable("config.craftraria.renderScale"),
                        renderScalePercent(config),
                        (int) Math.round(CraftrariaConfig.MIN_BROWSER_RENDER_SCALE * 100),
                        (int) Math.round(CraftrariaConfig.MAX_BROWSER_RENDER_SCALE * 100))
                .setDefaultValue(renderScalePercent(config))
                .setSaveConsumer(percent -> config.setBrowserRenderScale(percent / 100.0))
                .setTooltip(tooltip("config.craftraria.renderScale.tooltip"))
                .build();
    }

    private static AbstractConfigListEntry frameRateEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startIntSlider(
                        Component.translatable("config.craftraria.frameRate"),
                        config.getBrowserFrameRate(),
                        CraftrariaConfig.MIN_BROWSER_FRAME_RATE,
                        CraftrariaConfig.MAX_BROWSER_FRAME_RATE)
                .setDefaultValue(config.getBrowserFrameRate())
                .setSaveConsumer(config::setBrowserFrameRate)
                .setTooltip(tooltip("config.craftraria.frameRate.tooltip"))
                .build();
    }

    private static AbstractConfigListEntry statsOverlayEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startBooleanToggle(
                        Component.translatable("config.craftraria.statsOverlay"),
                        config.isStatsOverlayEnabled())
                .setDefaultValue(config.isStatsOverlayEnabled())
                .setSaveConsumer(config::setStatsOverlayEnabled)
                .setTooltip(tooltip("config.craftraria.statsOverlay.tooltip"))
                .build();
    }

    /**
     * The inverse of the stored {@code windowWorldRunning}: the screen pauses the world unless the
     * player asked to keep it running, so the entry is phrased as the pause rather than the run.
     */
    private static AbstractConfigListEntry pauseOnOpenEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startBooleanToggle(
                        Component.translatable("config.craftraria.pauseOnOpen"),
                        !config.isWindowWorldRunning())
                .setDefaultValue(!config.isWindowWorldRunning())
                .setSaveConsumer(pause -> config.setWindowWorldRunning(!pause))
                .build();
    }

    private static AbstractConfigListEntry openToggleEntry(ConfigEntryBuilder entries, CraftrariaConfig config) {
        return entries.startBooleanToggle(
                        Component.translatable("config.craftraria.openToggle"),
                        config.isOpenTerrariaToggle())
                .setDefaultValue(config.isOpenTerrariaToggle())
                .setSaveConsumer(config::setOpenTerrariaToggle)
                .setTooltip(tooltip("config.craftraria.openToggle.tooltip"))
                .build();
    }

    /**
     * A tooltip as pre-wrapped lines, so Cloth Config renders a narrow tooltip box instead of a
     * full-width band that covers the hovered row.
     */
    private static Component[] tooltip(String translationKey) {
        return wrap(Component.translatable(translationKey), TOOLTIP_WIDTH);
    }

    /**
     * Splits a component into lines no wider than {@code maxWidth} pixels.
     *
     * <p>Cloth Config re-splits every line it is given to the screen width, so a line that already
     * fits is passed through unchanged and the tooltip ends up as wide as its longest line rather
     * than as wide as the screen.
     */
    private static Component[] wrap(Component message, int maxWidth) {
        Font font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        for (FormattedCharSequence sequence : font.split(message, maxWidth)) {
            StringBuilder text = new StringBuilder();
            sequence.accept((index, style, codePoint) -> {
                text.appendCodePoint(codePoint);
                return true;
            });
            lines.add(Component.literal(text.toString()));
        }
        return lines.toArray(Component[]::new);
    }

    private static WindowMode currentWindowMode(CraftrariaConfig config) {
        return config.isWindowFramed() ? WindowMode.FRAMED : WindowMode.FULLSCREEN;
    }

    private static Consumer<WindowMode> newWindowMode(CraftrariaConfig config) {
        return mode -> config.setWindowMode(mode == WindowMode.FRAMED
                ? CraftrariaConfig.WINDOW_MODE_FRAMED
                : CraftrariaConfig.WINDOW_MODE_FULLSCREEN);
    }

    private static String labelSuffix(Enum<?> mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static int renderScalePercent(CraftrariaConfig config) {
        return (int) Math.round(config.getBrowserRenderScale() * 100);
    }
}
