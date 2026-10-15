package com.craftraria.client.browser;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A title-bar button with a drawn icon.
 *
 * <p>The first attempt used vanilla {@code Button}s with one-letter labels, which came back from
 * review as three unlabelled dots: at 16x16 a single glyph reads as a speck, so nothing told the
 * player something was clickable. This widget keeps the vanilla press, focus, sound, hover and
 * tooltip behaviour and replaces the label with pixel-art icons drawn at 18x18.
 *
 * <p>It extends {@code AbstractWidget} rather than {@code Button} because
 * {@code AbstractButton.extractWidgetRenderState} is final, so a Button subclass cannot draw anything
 * but a text label. {@code AbstractWidget.extractRenderState} is final but delegates to
 * {@code extractWidgetRenderState} and then pushes the tooltip, which is what makes the tooltips on
 * these buttons work.
 */
public final class TitleBarButton extends AbstractWidget {

    /** The icons the window needs, drawn as pixel art in {@link #drawIcon}. */
    public enum Icon {
        SETTINGS,
        REFRESH,
        MAXIMIZE,
        RESTORE,
        CLOSE
    }

    private static final int ICON_INSET = 3;
    private static final int ICON_SIZE = 12;
    private static final int ICON_COLOR = 0xFFE6EDF3;
    private static final int TRACK_COLOR = 0x80FFFFFF;
    private static final int KNOB_SIZE = 4;
    private static final int BACKGROUND_COLOR = 0x18FFFFFF;
    private static final int HOVER_BACKGROUND_COLOR = 0x44FFFFFF;
    private static final int PRESSED_BACKGROUND_COLOR = 0x66FFFFFF;

    private final Component description;
    private final Runnable onPress;
    private Icon icon;
    private boolean pressed;

    public TitleBarButton(int x, int y, int size, Icon icon, Component description, Runnable onPress) {
        super(x, y, size, size, Component.empty());
        this.icon = icon;
        this.description = description;
        this.onPress = onPress;
        setTooltip(Tooltip.create(description));
    }

    /** Builds the mode button with the icon and tooltip matching the current mode. */
    public static TitleBarButton ofModeToggle(int x, int y, int size, boolean framed, Runnable onPress) {
        return new TitleBarButton(x, y, size, framed ? Icon.MAXIMIZE : Icon.RESTORE,
                framed
                        ? Component.literal("Expand the Terraria window to the full screen")
                        : Component.literal("Restore the Terraria window to a movable size"),
                onPress);
    }

    /** Swaps the icon, for the mode button that shows what pressing it will do. */
    public void setIcon(Icon icon) {
        this.icon = icon;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        pressed = true;
        if (onPress != null) {
            onPress.run();
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        pressed = false;
        return super.mouseReleased(event);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        int background = pressed ? PRESSED_BACKGROUND_COLOR
                : isHoveredOrFocused() ? HOVER_BACKGROUND_COLOR
                : BACKGROUND_COLOR;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, background);
        drawIcon(graphics);
    }

    /** Icons are drawn in a 12x12 box inset from the button's top-left corner. */
    private void drawIcon(GuiGraphicsExtractor graphics) {
        int x = getX() + ICON_INSET;
        int y = getY() + ICON_INSET;
        switch (icon) {
            case SETTINGS -> drawSettings(graphics, x, y);
            case REFRESH -> drawRefresh(graphics, x, y);
            case MAXIMIZE -> drawMaximize(graphics, x, y);
            case RESTORE -> drawRestore(graphics, x, y);
            case CLOSE -> drawClose(graphics, x, y);
        }
    }

    /**
     * A circular arrow: 24 pixels around a circle of radius 4.5, with the top pixel group skipped so
     * the ring has a gap, and a small arrowhead sitting in that gap pointing back along the ring.
     */
    private void drawRefresh(GuiGraphicsExtractor graphics, int x, int y) {
        int centreX = x + ICON_SIZE / 2;
        int centreY = y + ICON_SIZE / 2;
        double radius = (ICON_SIZE - 3) / 2.0;
        for (int index = 0; index < 24; index++) {
            double angle = 2 * Math.PI * index / 24 - Math.PI / 2;
            // Skip the group near the top of the ring, which is where the arrowhead goes.
            int stepFromTop = Math.floorMod(index - 0, 24);
            if (stepFromTop <= 1 || stepFromTop >= 23) {
                continue;
            }
            int pixelX = centreX + (int) Math.round(Math.cos(angle) * radius);
            int pixelY = centreY + (int) Math.round(Math.sin(angle) * radius);
            graphics.fill(pixelX, pixelY, pixelX + 1, pixelY + 1, ICON_COLOR);
        }
            int headY = centreY - (int) radius;
            graphics.fill(centreX - 1, headY - 2, centreX + 2, headY, ICON_COLOR);
            graphics.fill(centreX, headY - 3, centreX + 1, headY - 1, ICON_COLOR);
    }

    /** Three sliders, the usual "settings" glyph, so the button reads as configuration. */
    private void drawSettings(GuiGraphicsExtractor graphics, int x, int y) {
        for (int track = 0; track < 3; track++) {
            int trackY = y + 2 + track * 4;
            graphics.fill(x, trackY, x + ICON_SIZE, trackY + 1, TRACK_COLOR);
        }
        drawKnob(graphics, x + 1, y);
        drawKnob(graphics, x + ICON_SIZE - KNOB_SIZE - 1, y + 4);
        drawKnob(graphics, x + 3, y + 8);
    }

    /** A window with an emphasised title edge, for expanding to the full viewport. */
    private void drawMaximize(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + ICON_SIZE, y + 2, ICON_COLOR);
        graphics.fill(x, y, x + 1, y + ICON_SIZE, ICON_COLOR);
        graphics.fill(x + ICON_SIZE - 1, y, x + ICON_SIZE, y + ICON_SIZE, ICON_COLOR);
        graphics.fill(x, y + ICON_SIZE - 1, x + ICON_SIZE, y + ICON_SIZE, ICON_COLOR);
    }

    /**
     * Two overlapping windows, for restoring the windowed size. The back window's sides run into the
     * front window's top edge, which is what makes it read as two windows rather than two boxes.
     */
    private void drawRestore(GuiGraphicsExtractor graphics, int x, int y) {
        int backX = x + 5;
        int backY = y + 1;
        graphics.fill(backX, backY, backX + 6, backY + 1, ICON_COLOR);
        graphics.fill(backX, backY, backX + 1, backY + 6, ICON_COLOR);
        graphics.fill(backX + 5, backY, backX + 6, backY + 6, ICON_COLOR);

        int frontX = x + 1;
        int frontY = y + 6;
        graphics.fill(frontX, frontY, frontX + 8, frontY + 1, ICON_COLOR);
        graphics.fill(frontX, frontY, frontX + 1, frontY + 6, ICON_COLOR);
        graphics.fill(frontX + 7, frontY, frontX + 8, frontY + 6, ICON_COLOR);
        graphics.fill(frontX, frontY + 5, frontX + 8, frontY + 6, ICON_COLOR);
    }

    /** A cross, drawn with single-pixel diagonal strokes so it stays crisp. */
    private void drawClose(GuiGraphicsExtractor graphics, int x, int y) {
        for (int step = 0; step < ICON_SIZE; step++) {
            graphics.fill(x + step, y + step, x + step + 1, y + step + 1, ICON_COLOR);
            graphics.fill(x + ICON_SIZE - 1 - step, y + step, x + ICON_SIZE - step, y + step + 1, ICON_COLOR);
        }
    }

    private void drawKnob(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + KNOB_SIZE, y + KNOB_SIZE, ICON_COLOR);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, description);
    }
}
