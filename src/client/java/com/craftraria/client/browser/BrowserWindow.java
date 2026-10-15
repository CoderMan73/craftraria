package com.craftraria.client.browser;

import com.craftraria.client.config.CraftrariaConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The framed window that hosts the Terraria browser: geometry, chrome, hit-testing and drag math.
 *
 * <p>The surface used to fill the whole GUI viewport behind an opaque backdrop, so there was nothing
 * to grab and the 3D world was hidden. This class is the replacement window. It is moved by its
 * title bar, resized from any edge, expands to the full viewport, and leaves the world visible and
 * undimmed around itself while it is framed.
 *
 * <p>All coordinates are GUI-scaled pixels, which is the space the browser texture is blitted into.
 * The geometry is persisted per install through {@link CraftrariaConfig}; a GUI-scale change is
 * tolerated by clamping the stored rectangle into whatever viewport the screen opens with, and the
 * clamped rectangle is only written back when the player finishes a drag or toggles the mode, so a
 * drag does not write the config file every frame.
 */
public final class BrowserWindow {

    public static final int TITLE_BAR_HEIGHT = 22;
    public static final int RESIZE_BAND = 6;
    public static final int TITLE_BUTTON_SIZE = 18;
    /** Space a freshly opened framed window keeps from the edges of the viewport. */
    public static final int WINDOW_MARGIN = 8;

    /** Height of a glyph cell in Minecraft's default font, as vanilla centers its own labels on. */
    private static final int GLYPH_CELL_HEIGHT = 8;

    /** The aspect ratio the window is locked to while it is resized. */
    public static final float ASPECT_RATIO = 16f / 9f;
    /**
     * Size floors. The width derives from the height so the pair is exactly 16:9: a window at the
     * locked minimum otherwise wants a width that no 16:9 height can satisfy, and the lock would
     * quietly resize past the floor the player asked for.
     */
    private static final int MIN_HEIGHT = 200;
    private static final int MIN_WIDTH = Math.round(MIN_HEIGHT * ASPECT_RATIO);
    private static final int TITLE_BUTTON_COUNT = 4;
    private static final int TITLE_BUTTON_SPACING = 2;
    private static final int TITLE_TEXT_PADDING = 4;
    private static final int BORDER_WIDTH = 2;
    private static final int GRIP_SPACING = 3;
    private static final int GRIP_ROWS = 3;

    public static final int TITLE_BUTTON_SETTINGS = 0;
    public static final int TITLE_BUTTON_REFRESH = 1;
    public static final int TITLE_BUTTON_MODE_TOGGLE = 2;
    public static final int TITLE_BUTTON_CLOSE = 3;

    private static final int EDGE_LEFT = 1;
    private static final int EDGE_RIGHT = 2;
    private static final int EDGE_BOTTOM = 4;

    private static final int FRAME_COLOR = 0xFF252C36;
    private static final int FRAME_BORDER_COLOR = 0xFF11161D;
    private static final int TITLE_BAR_COLOR = 0xFF414B58;
    private static final int TITLE_TEXT_COLOR = 0xFFE6EDF3;
    private static final int GRIP_COLOR = 0x99FFFFFF;

    private final CraftrariaConfig config;

    private int currentScreenWidth;
    private int currentScreenHeight;
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean framed = true;

    private DragMode dragMode = DragMode.NONE;
    private int dragMask;
    private boolean dragAspectLocked;
    private int dragOffsetX;
    private int dragOffsetY;
    private int dragMouseX;
    private int dragMouseY;
    private int dragStartX;
    private int dragStartY;
    private int dragStartWidth;
    private int dragStartHeight;

    private enum DragMode {
        NONE,
        MOVE,
        RESIZE
    }

    public BrowserWindow(CraftrariaConfig config) {
        this.config = config;
    }

    /**
     * Loads the persisted window geometry and clamps it into the current viewport, falling back to a
     * centered default rectangle when nothing usable was stored.
     */
    public void restore(int screenWidth, int screenHeight) {
        currentScreenWidth = screenWidth;
        currentScreenHeight = screenHeight;
        framed = config.isWindowFramed();
        restoreGeometry();
        clampIntoViewport(screenWidth, screenHeight);
        if (!framed) {
            fillViewport(screenWidth, screenHeight);
        }
    }

    /** Reloads the windowed rectangle from the config, falling back to a centred default. */
    private void restoreGeometry() {
        width = config.getWindowWidth();
        height = config.getWindowHeight();
        if (width <= 0 || height <= 0) {
            applyDefaultRect();
        } else {
            x = config.getWindowX();
            y = config.getWindowY();
        }
    }

    private void applyDefaultRect() {
        applyDefaultRect(currentScreenWidth, currentScreenHeight);
    }

    private void applyDefaultRect(int screenWidth, int screenHeight) {
        int usableWidth = Math.max(1, screenWidth - WINDOW_MARGIN * 2);
        int usableHeight = Math.max(1, screenHeight - WINDOW_MARGIN * 2);
        width = Mth.clamp((int) Math.round(usableWidth * 0.82), MIN_WIDTH, usableWidth);
        height = Mth.clamp((int) Math.round(usableHeight * 0.82), MIN_HEIGHT, usableHeight);
        x = (screenWidth - width) / 2;
        y = (screenHeight - height) / 2;
    }

    /**
     * Fullscreen covers the whole viewport. The title bar still gets its own strip at the top, so
     * the window keeps its controls, and the browser fills every remaining pixel: a framed window
     * reads as a window over the world, a fullscreen one as the game itself.
     */
    private void fillViewport(int screenWidth, int screenHeight) {
        x = 0;
        y = 0;
        width = Math.max(MIN_WIDTH, screenWidth);
        height = Math.max(MIN_HEIGHT, screenHeight);
    }

    /** Keeps the window reachable when the GUI scale or the game window shrank underneath it. */
    public void clampIntoViewport(int screenWidth, int screenHeight) {
        width = fitSize(width, MIN_WIDTH, screenWidth);
        height = fitSize(height, MIN_HEIGHT, screenHeight);
        x = Mth.clamp(x, 0, Math.max(0, screenWidth - width));
        y = Mth.clamp(y, 0, Math.max(0, screenHeight - height));
    }

    /**
     * Clamps a size to the space there is for it. The floor only applies when there is room for it,
     * because a viewport smaller than the floor is real (small GUI scale, small game window) and
     * honouring the floor there would push the window off the screen.
     */
    private static int fitSize(int size, int floor, int available) {
        int limit = Math.max(1, available);
        return Mth.clamp(size, Math.min(floor, limit), limit);
    }

    /** Re-applies the current mode to a new viewport size: reclamp a framed window, re-fill a fullscreen one. */
    public void applyViewport(int screenWidth, int screenHeight) {
        currentScreenWidth = screenWidth;
        currentScreenHeight = screenHeight;
        if (framed) {
            clampIntoViewport(screenWidth, screenHeight);
        } else {
            fillViewport(screenWidth, screenHeight);
        }
    }

    public boolean isFramed() {
        return framed;
    }

    /**
     * Where the browser surface sits. A framed window insets it by the border ring; a fullscreen one
     * has no ring, so the browser starts at the raw window edge and only the title bar takes a strip
     * off the top.
     */
    public int interiorX() {
        return framed ? x + BORDER_WIDTH : x;
    }

    public int interiorY() {
        return y + TITLE_BAR_HEIGHT;
    }

    public int interiorWidth() {
        return framed ? width - BORDER_WIDTH * 2 : width;
    }

    public int interiorHeight() {
        return height - TITLE_BAR_HEIGHT - (framed ? BORDER_WIDTH : 0);
    }

    /**
     * Whether a point is on the title bar, in either mode. The bar is drawn in both, so double-clicking
     * it toggles the mode from fullscreen too; only moving needs {@link #beginMove} to gate on framed.
     */
    public boolean isOverTitleBar(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + TITLE_BAR_HEIGHT;
    }

    /**
     * Edge band a point can grab for resizing, or 0. The title bar is the move handle, so the top
     * edge is never a resize edge; that keeps moving and resizing from overlapping.
     */
    public int resizeMaskAt(double mouseX, double mouseY) {
        if (!framed) {
            return 0;
        }
        int mask = 0;
        if (mouseX >= x && mouseX < x + RESIZE_BAND) {
            mask |= EDGE_LEFT;
        } else if (mouseX >= x + width - RESIZE_BAND && mouseX < x + width) {
            mask |= EDGE_RIGHT;
        }
        if (mouseY >= y + height - RESIZE_BAND && mouseY < y + height) {
            mask |= EDGE_BOTTOM;
        }
        return mask;
    }

    public boolean isResizeMask(int mask) {
        return mask != 0;
    }

    /**
     * Starts a move. A fullscreen window fills the viewport and has nowhere to go, so this is a no-op
     * there: the title bar still answers, it just does not drag.
     */
    public void beginMove(double mouseX, double mouseY) {
        if (!framed) {
            return;
        }
        dragMode = DragMode.MOVE;
        dragOffsetX = (int) Math.round(mouseX) - x;
        dragOffsetY = (int) Math.round(mouseY) - y;
    }

    public void beginResize(int mask, double mouseX, double mouseY) {
        dragMode = DragMode.RESIZE;
        dragMask = mask;
        dragMouseX = (int) Math.round(mouseX);
        dragMouseY = (int) Math.round(mouseY);
        dragStartX = x;
        dragStartY = y;
        dragStartWidth = width;
        dragStartHeight = height;
        // Captured at drag start, so toggling the setting mid-drag cannot jolt the window.
        dragAspectLocked = config.isWindowAspectLocked();
    }

    /**
     * Applies a drag. While the aspect ratio is locked the rectangle is constrained to 16:9, and
     * holding shift frees it for that drag only.
     *
     * @param shiftHeld whether the player is holding shift, which suspends the aspect lock
     */
    public void dragTo(double mouseX, double mouseY, int screenWidth, int screenHeight, boolean shiftHeld) {
        if (dragMode == DragMode.MOVE) {
            x = Mth.clamp((int) Math.round(mouseX) - dragOffsetX, 0, Math.max(0, screenWidth - width));
            y = Mth.clamp((int) Math.round(mouseY) - dragOffsetY, 0, Math.max(0, screenHeight - height));
            return;
        }
        if (dragMode != DragMode.RESIZE) {
            return;
        }

        int mouseDeltaX = (int) Math.round(mouseX) - dragMouseX;
        int mouseDeltaY = (int) Math.round(mouseY) - dragMouseY;
        if ((dragMask & EDGE_RIGHT) != 0) {
            width = fitSize(dragStartWidth + mouseDeltaX, MIN_WIDTH, screenWidth - dragStartX);
        }
        if ((dragMask & EDGE_BOTTOM) != 0) {
            height = fitSize(dragStartHeight + mouseDeltaY, MIN_HEIGHT, screenHeight - dragStartY);
        }

        if (dragAspectLocked && !shiftHeld) {
            // The edges actually being dragged decide which axis drives; the other follows at 16:9.
            // Both are fitted to the viewport, and the second clamp re-derives the dragged axis so the
            // pair satisfies the ratio or hits a limit together.
            boolean widthDriven = (dragMask & (EDGE_LEFT | EDGE_RIGHT)) != 0;
            int maxWidth = screenWidth - x;
            int maxHeight = screenHeight - y;
            if (widthDriven) {
                height = fitSize(Math.round(width / ASPECT_RATIO), MIN_HEIGHT, maxHeight);
                width = fitSize(Math.round(height * ASPECT_RATIO), MIN_WIDTH, maxWidth);
            } else {
                width = fitSize(Math.round(height * ASPECT_RATIO), MIN_WIDTH, maxWidth);
                height = fitSize(Math.round(width / ASPECT_RATIO), MIN_HEIGHT, maxHeight);
            }
        }

        if ((dragMask & EDGE_LEFT) != 0) {
            // The right edge stays put, so a wider window moves the left edge outwards.
            width = fitSize(width, MIN_WIDTH, dragStartWidth + dragStartX);
            x = dragStartX + (dragStartWidth - width);
        }
    }

    /** Finishes a drag and persists the window so the next open restores where it was left. */
    public void endDrag() {
        if (dragMode == DragMode.NONE) {
            return;
        }
        dragMode = DragMode.NONE;
        dragMask = 0;
        saveGeometry();
    }

    public boolean isDragging() {
        return dragMode != DragMode.NONE;
    }

    /** Switches between the persisted framed window and the full-viewport window. */
    public void toggleFramed(int screenWidth, int screenHeight) {
        currentScreenWidth = screenWidth;
        currentScreenHeight = screenHeight;
        framed = !framed;
        if (framed) {
            // Restore the windowed rectangle the player left behind, not the fullscreen one that
            // replaced it, so double-clicking out of fullscreen brings the same window back.
            restoreGeometry();
            clampIntoViewport(screenWidth, screenHeight);
            saveGeometry();
        } else {
            // Not saved: going fullscreen must not overwrite the windowed rectangle in the config.
            fillViewport(screenWidth, screenHeight);
        }
        config.setWindowMode(framed ? CraftrariaConfig.WINDOW_MODE_FRAMED : CraftrariaConfig.WINDOW_MODE_FULLSCREEN);
    }

    private void saveGeometry() {
        config.setWindowX(x);
        config.setWindowY(y);
        config.setWindowWidth(width);
        config.setWindowHeight(height);
    }

    public void render(GuiGraphicsExtractor graphics, Font font, Component title) {
        if (framed) {
            renderFrame(graphics);
        }
        renderTitleBar(graphics, font, title);
    }

    /**
     * The framed chrome: a filled body under the border ring. The browser surface covers the body
     * entirely except for the ring itself, so the world shows nowhere inside a framed window.
     */
    private void renderFrame(GuiGraphicsExtractor graphics) {
        graphics.fill(x, y, x + width, y + height, FRAME_COLOR);

        // A 2px border ring, drawn as four bands so the ring survives the title bar being painted on
        // top of it only inside its own bounds. Anything drawn inside the ring is then covered by the
        // browser surface, so chrome that has to stay visible is either outside the ring or drawn
        // after the surface (see renderResizeGrip).
        graphics.fill(x, y, x + width, y + BORDER_WIDTH, FRAME_BORDER_COLOR);
        graphics.fill(x, y, x + BORDER_WIDTH, y + height, FRAME_BORDER_COLOR);
        graphics.fill(x + width - BORDER_WIDTH, y, x + width, y + height, FRAME_BORDER_COLOR);
        graphics.fill(x, y + height - BORDER_WIDTH, x + width, y + height, FRAME_BORDER_COLOR);
    }

    /**
     * The title bar, lighter than the frame so it reads as a bar rather than a plain rectangle. Drawn
     * in both modes: a fullscreen window keeps the bar as its only chrome, pinned to the top edge of
     * the viewport with no border around it.
     */
    private void renderTitleBar(GuiGraphicsExtractor graphics, Font font, Component title) {
        int frameLeft = framed ? x + BORDER_WIDTH : x;
        int ringRight = framed ? BORDER_WIDTH : 0;
        int barTop = framed ? y + BORDER_WIDTH : y;
        int barHeight = TITLE_BAR_HEIGHT - (framed ? BORDER_WIDTH : 0);
        graphics.fill(frameLeft, barTop, x + width - ringRight, y + TITLE_BAR_HEIGHT, TITLE_BAR_COLOR);
        graphics.fill(frameLeft, y + TITLE_BAR_HEIGHT - 1, x + width - ringRight, y + TITLE_BAR_HEIGHT, FRAME_BORDER_COLOR);

        // Centered on the glyph cell rather than on lineHeight, which is what vanilla does for button
        // labels: lineHeight is a pixel taller than the cell, so centering on it leaves the text
        // sitting visibly low in the bar.
        int titleY = barTop + (barHeight - GLYPH_CELL_HEIGHT) / 2;
        // Clip against the left-most button, so the title never runs underneath it.
        int titleLimit = titleButtonX(TITLE_BUTTON_SETTINGS) - TITLE_TEXT_PADDING;
        int available = titleLimit - (frameLeft + TITLE_TEXT_PADDING);
        if (available > font.width(title)) {
            graphics.text(font, title, frameLeft + TITLE_TEXT_PADDING, titleY, TITLE_TEXT_COLOR);
        }
    }

    /**
     * The resize grip, drawn after the browser surface rather than inside {@link #render}, because the
     * surface is opaque and would otherwise hide it.
     */
    public void renderResizeGrip(GuiGraphicsExtractor graphics) {
        for (int row = 0; row < GRIP_ROWS; row++) {
            int gripY = y + height - BORDER_WIDTH - 3 - row * GRIP_SPACING;
            for (int column = 0; column <= row; column++) {
                int gripX = x + width - BORDER_WIDTH - 3 - column * GRIP_SPACING;
                graphics.fill(gripX, gripY, gripX + 2, gripY + 2, GRIP_COLOR);
            }
        }
    }

    public int titleButtonX(int index) {
        int right = x + width - TITLE_BUTTON_SPACING - TITLE_BUTTON_SIZE;
        return right - (TITLE_BUTTON_COUNT - 1 - index) * (TITLE_BUTTON_SIZE + TITLE_BUTTON_SPACING);
    }

    public int titleButtonY() {
        return y + (TITLE_BAR_HEIGHT - TITLE_BUTTON_SIZE) / 2;
    }
}
