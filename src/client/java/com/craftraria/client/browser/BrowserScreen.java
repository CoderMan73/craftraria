package com.craftraria.client.browser;

import com.craftraria.client.CraftrariaClient;
import com.craftraria.client.config.CraftrariaConfig;
import com.craftraria.client.ui.CraftrariaConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import de.keksuccino.rinku.listeners.RinkuInitListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The screen that shows Terraria through the embedded Chromium browser.
 *
 * <p>The viewport is a movable, resizable window ({@link BrowserWindow}) rather than a fullscreen
 * panel. In framed mode the world keeps rendering around the window and no backdrop covers it, so
 * the 3D world is visible at the same time as the game. While the window is fullscreen the old
 * behaviour is kept: an opaque backdrop over the whole GUI viewport.
 *
 * <p>The surface follows the window live, but the page cannot: the game sizes its canvas once, at
 * page load, so a window that ends up larger than the page was loaded at has that canvas stretched
 * over it. Growth therefore reloads the page once the size settles — see {@link #reloadIfOutgrown}
 * and craftlatro's {@code ai-docs/notes/2026-10-13-lovejs-canvas-sizes-at-page-load.md}, whose
 * finding applies to the Terrarium build exactly as it did to love.js.
 */
public class BrowserScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");

    private static long lastTextureNotReadyLog = 0;

    public static final Component TITLE = Component.literal("Terraria");
    /** How much the window may move away from the size the page was loaded at before it is reported. */
    private static final double SIZE_CHANGE_TOLERANCE = 0.10;
    /** How long the size must hold still before the notice appears, so a drag reports once at the end. */
    private static final long NOTICE_SETTLE_MILLIS = 400;
    private static final int NOTICE_HEIGHT = 20;
    private static final int NOTICE_BUTTON_HEIGHT = 16;
    private static final int NOTICE_BUTTON_PADDING = 8;
    private static final int NOTICE_BUTTON_GAP = 4;
    private static final int NOTICE_STACK_PADDING = 3;
    private static final int NOTICE_MESSAGE_PADDING = 4;
    private static final int NOTICE_BAR_COLOR = 0xF20B0E12;
    private static final int NOTICE_TEXT_COLOR = 0xFFE6EDF3;
    private static final int NOTICE_BUTTON_COLOR = 0xFF2D3742;
    private static final int NOTICE_BUTTON_HOVER_COLOR = 0xFF3D4A58;
    private static final int NOTICE_BUTTON_BORDER_COLOR = 0xFF5A6675;
    private static final Component NOTICE_RELOAD_LABEL = Component.translatable("craftraria.notice.reload");
    private static final Component NOTICE_IGNORE_LABEL = Component.translatable("craftraria.notice.ignore");
    private static final int GLYPH_CELL_HEIGHT = 8;
    private static final int BACKDROP_COLOR = 0xFF0E1116;
    private static final int LABEL_COLOR = 0xFFE6EDF3;
    private static final int LOADING_BAR_HEIGHT = 2;
    private static final int LOADING_BAR_TRACK_COLOR = 0x55000000;
    private static final int LOADING_BAR_FILL_COLOR = 0xFF3BA8FF;

    private final String startUrl;
    private final BrowserWindow window;

    private RinkuBrowser browser;
    private boolean initScheduled = false;
    private boolean browserButtonDown = false;
    private int pagePixelWidth;
    private int pagePixelHeight;
    private boolean resolutionNoticeShowing;
    private boolean resolutionNoticeIgnored;
    private int noticeCandidateWidth;
    private int noticeCandidateHeight;
    private long noticeSettleAt;
    private double hoveredMouseX = -1;
    private double hoveredMouseY = -1;
    private int browserPixelWidth;
    private int browserPixelHeight;
    private int appliedPixelWidth;
    private int appliedPixelHeight;
    private boolean titleButtonsLabelFramed = true;
    private TitleBarButton modeButton;
    private TitleBarButton closeButton;
    private TitleBarButton refreshButton;
    private TitleBarButton settingsButton;

    public BrowserScreen(String startUrl) {
        super(TITLE);
        this.startUrl = startUrl;
        this.window = new BrowserWindow(CraftrariaConfig.getInstance());
    }

    @Override
    protected void init() {
        super.init();

        window.restore(screenWidth(), screenHeight());

        modeButton = TitleBarButton.ofModeToggle(
                window.titleButtonX(BrowserWindow.TITLE_BUTTON_MODE_TOGGLE), window.titleButtonY(),
                BrowserWindow.TITLE_BUTTON_SIZE, window.isFramed(),
                () -> window.toggleFramed(screenWidth(), screenHeight()));
        addRenderableWidget(modeButton);

        closeButton = new TitleBarButton(
                window.titleButtonX(BrowserWindow.TITLE_BUTTON_CLOSE), window.titleButtonY(),
                BrowserWindow.TITLE_BUTTON_SIZE, TitleBarButton.Icon.CLOSE,
                Component.literal("Close the Terraria window"), () -> onClose());
        addRenderableWidget(closeButton);

        refreshButton = new TitleBarButton(
                window.titleButtonX(BrowserWindow.TITLE_BUTTON_REFRESH), window.titleButtonY(),
                BrowserWindow.TITLE_BUTTON_SIZE, TitleBarButton.Icon.REFRESH,
                Component.literal("Restart Terraria"), () -> rebind());
        addRenderableWidget(refreshButton);

        settingsButton = new TitleBarButton(
                window.titleButtonX(BrowserWindow.TITLE_BUTTON_SETTINGS), window.titleButtonY(),
                BrowserWindow.TITLE_BUTTON_SIZE, TitleBarButton.Icon.SETTINGS,
                Component.literal("Craftraria settings"), () -> openSettings());
        addRenderableWidget(settingsButton);

        createBrowserIfNotReady();
    }

    /**
     * The world keeps rendering behind the framed window either way; this only decides whether it
     * keeps ticking. A screen owns all input while it is open, so a running world can hurt the
     * player while they cannot react, which is why the default keeps the usual singleplayer pause.
     */
    @Override
    public boolean isPauseScreen() {
        return !CraftrariaConfig.getInstance().isWindowWorldRunning();
    }

    private void createBrowserIfNotReady() {
        if (browser != null) {
            return;
        }

        if (Rinku.isInitialized()) {
            attachBrowser();
        } else if (Rinku.isInitializationAllowed() && !initScheduled) {
            initScheduled = true;
            Rinku.scheduleForInit(new RinkuInitListener() {
                @Override
                public void onInit(boolean successful) {
                    if (successful) {
                        minecraft.execute(() -> {
                            if (minecraft.gui.screen() instanceof BrowserScreen && browser == null) {
                                attachBrowser();
                            }
                        });
                    } else {
                        minecraft.execute(BrowserScreen.this::onRinkuInitFailed);
                    }
                }
            });
        }
    }

    /**
     * Closes the screen when Chromium could not come up, instead of leaving a dead window open.
     *
     * <p>Rinku's own JCEF installer is what fails here - its download and publish step is what
     * crashed the client once on Windows - and a screen with no browser behind it paints nothing
     * useful and hides the game behind it. The message names the retry that actually works, because
     * the usual cause is a lock on the installer's own staging directory: delete
     * {@code <instance>/run/rinku-libraries} and launch again, and the installer re-extracts.
     */
    private void onRinkuInitFailed() {
        LOGGER.error("Rinku could not initialize; the embedded browser is unavailable this session. "
                + "Delete run/rinku-libraries and restart the game to retry the JCEF install.");
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(
                    "The embedded browser could not start. Delete run/rinku-libraries and restart the game."));
        }
        minecraft.gui.setScreen(null);
    }

    private void attachBrowser() {
        browser = TerrariaBrowser.acquire(startUrl);
        if (browser == null) {
            return;
        }
        browserButtonDown = false;
        appliedPixelWidth = 0;
        appliedPixelHeight = 0;
        resizeBrowser();
        // The page is loading at the size the surface has right now, which is the resolution the game
        // picks its canvas at.
        pagePixelWidth = appliedPixelWidth;
        pagePixelHeight = appliedPixelHeight;
    }

    /**
     * Drops the current page session and reattaches. Used when the served content has been replaced,
     * since the old page can still be holding the OPFS-staged assets it was started with.
     */
    public void rebind() {
        TerrariaBrowser.release();
        browser = null;
        browserButtonDown = false;
        initScheduled = false;
        appliedPixelWidth = 0;
        appliedPixelHeight = 0;
        resolutionNoticeShowing = false;
        resolutionNoticeIgnored = false;
        noticeCandidateWidth = 0;
        noticeCandidateHeight = 0;
        noticeSettleAt = 0;
        createBrowserIfNotReady();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        window.applyViewport(screenWidth(), screenHeight());
    }

    private int screenScale() {
        return minecraft.getWindow().getGuiScale();
    }

    private double renderScale() {
        return CraftrariaConfig.getInstance().getBrowserRenderScale();
    }

    /**
     * The configured render scale, held back when it would paint above the largest surface the window
     * can ever be: the whole viewport minus the title bar, in pixels.
     *
     * <p>Above 100% the game renders at a higher resolution than the window and is downscaled, which
     * is the point — a small window gets a full-screen-quality render. But the surface is then larger
     * than the window itself, and a surface larger than the window is the one configuration that has
     * crashed the client: it happened twice, deterministically, when the surface was pinned above the
     * window's own size (see {@code ai-docs/notes/2026-10-13-lovejs-canvas-sizes-at-page-load.md}).
     * Everything at or below the window's own size has run safely, so the ceiling is where the window
     * tops out, and the scale is brought back uniformly to keep the aspect.
     */
    private double effectiveRenderScale() {
        double wanted = renderScale();
        if (wanted <= 1.0) {
            return wanted;
        }
        double ceilingWidth = screenWidth() * screenScale();
        double ceilingHeight = (screenHeight() - BrowserWindow.TITLE_BAR_HEIGHT) * screenScale();
        double limitByWidth = ceilingWidth / Math.max(1.0, window.interiorWidth() * screenScale());
        double limitByHeight = ceilingHeight / Math.max(1.0, window.interiorHeight() * screenScale());
        return Math.max(1.0, Math.min(wanted, Math.min(limitByWidth, limitByHeight)));
    }

    /**
     * The logical size of the screen, read straight from the window. Screen fields only refresh when
     * the screen itself is rebuilt, which never happened when the player resized the game window, so
     * that is why the Terraria surface used to stay stuck at whatever size it started at.
     */
    private int screenWidth() {
        return minecraft.getWindow().getGuiScaledWidth();
    }

    private int screenHeight() {
        return minecraft.getWindow().getGuiScaledHeight();
    }

    private int browserPixelWidth() {
        return Math.max(1, (int) Math.round(window.interiorWidth() * screenScale() * effectiveRenderScale()));
    }

    private int browserPixelHeight() {
        return Math.max(1, (int) Math.round(window.interiorHeight() * screenScale() * effectiveRenderScale()));
    }

    private void resizeBrowser() {
        if (browser == null) {
            return;
        }
        browserPixelWidth = browserPixelWidth();
        browserPixelHeight = browserPixelHeight();
        if (browserPixelWidth == appliedPixelWidth && browserPixelHeight == appliedPixelHeight) {
            return;
        }
        appliedPixelWidth = browserPixelWidth;
        appliedPixelHeight = browserPixelHeight;
        TerrariaBrowser.showViewport(browserPixelWidth, browserPixelHeight);
    }

    /**
     * Re-checks the surface size every frame so window drags, resizes and GUI-scale changes land
     * live. The settings the player can flip while this screen is open (background blur, aspect
     * lock) are read where they are used, not staged here.
     */
    private void syncViewport() {
        if (browser == null) {
            return;
        }
        window.applyViewport(screenWidth(), screenHeight());
        if (browserPixelWidth() != appliedPixelWidth || browserPixelHeight() != appliedPixelHeight) {
            resizeBrowser();
        }
        updateResolutionNotice();
    }

    /**
     * Shows the resolution notice once the window size has settled somewhere substantially different
     * from the size the game was loaded at.
     *
     * <p>The game sizes its canvas once, at page load, and never follows a later resize, so a surface
     * that ends up a different size leaves that canvas stretched (grown: blurry) or wasted (shrunk:
     * rendering above the window's resolution). A reload is the only thing that re-creates the
     * canvas, so the player is told rather than having it done for them: reloading restarts the game
     * at the Terraria menu, which is a fine price when asked and an unpleasant one when imposed on
     * every drag. Both directions count, the threshold is 10% on either axis, and the size has to hold
     * still for a moment so a drag reports once, at its end.
     */
    private void updateResolutionNotice() {
        boolean substantiallyDifferent = isSubstantiallyDifferentSize(
                appliedPixelWidth, appliedPixelHeight, pagePixelWidth, pagePixelHeight);
        if (!substantiallyDifferent) {
            // The window is back to roughly the size the page was loaded at, so there is nothing
            // left to offer: a later change starts a fresh offer.
            resolutionNoticeShowing = false;
            resolutionNoticeIgnored = false;
            noticeCandidateWidth = 0;
            noticeCandidateHeight = 0;
            noticeSettleAt = 0;
            return;
        }
        if (appliedPixelWidth != noticeCandidateWidth || appliedPixelHeight != noticeCandidateHeight) {
            noticeCandidateWidth = appliedPixelWidth;
            noticeCandidateHeight = appliedPixelHeight;
            noticeSettleAt = System.currentTimeMillis() + NOTICE_SETTLE_MILLIS;
            return;
        }
        if (noticeSettleAt == 0 || System.currentTimeMillis() < noticeSettleAt || resolutionNoticeIgnored) {
            return;
        }
        resolutionNoticeShowing = true;
    }

    private static boolean isSubstantiallyDifferentSize(int width, int height, int referenceWidth, int referenceHeight) {
        return width > referenceWidth * (1 + SIZE_CHANGE_TOLERANCE)
                || width < referenceWidth * (1 - SIZE_CHANGE_TOLERANCE)
                || height > referenceHeight * (1 + SIZE_CHANGE_TOLERANCE)
                || height < referenceHeight * (1 - SIZE_CHANGE_TOLERANCE);
    }

    /**
     * Reloads the page at the resolution the window currently has, which is the only way the game
     * re-creates its canvas. Costs the running game, exactly like the refresh button.
     */
    private void reloadPageAtCurrentSize() {
        if (browser == null) {
            return;
        }
        pagePixelWidth = appliedPixelWidth;
        pagePixelHeight = appliedPixelHeight;
        resolutionNoticeShowing = false;
        resolutionNoticeIgnored = false;
        noticeCandidateWidth = 0;
        noticeCandidateHeight = 0;
        noticeSettleAt = 0;
        LOGGER.info("Reloading the page at {}x{} after the window changed size.", pagePixelWidth, pagePixelHeight);
        browser.reload();
    }

    /** Dismisses the offer for good, until the window comes back to the size the page was loaded at. */
    private void dismissResolutionNotice() {
        resolutionNoticeShowing = false;
        resolutionNoticeIgnored = true;
    }

    private int toBrowserX(double guiX) {
        return (int) Math.round((guiX - window.interiorX()) * browserPixelWidth / (double) Math.max(1, window.interiorWidth()));
    }

    private int toBrowserY(double guiY) {
        return (int) Math.round((guiY - window.interiorY()) * browserPixelHeight / (double) Math.max(1, window.interiorHeight()));
    }

    private boolean isInBrowserBounds(double x, double y) {
        int bx = window.interiorX();
        int by = window.interiorY();
        return x >= bx && x < bx + window.interiorWidth() && y >= by && y < by + window.interiorHeight();
    }

    /**
     * Decides whether the world behind a framed window is blurred.
     *
     * <p>Minecraft's own screen background blurs the world and lays the in-world menu texture over
     * it. With the toggle off, none of that is drawn and the world renders sharp; with it on, the
     * vanilla treatment is used unchanged. This runs from the Screen render wrapper before
     * {@link #extractRenderState}, which is the only place it can be decided.
     *
     * <p>A strength slider was tried and removed: {@code GuiGraphicsExtractor.blurBeforeThisStratum}
     * is a single binary render-pass boundary with no radius, so there was nothing to scale and the
     * slider could only ever have been a blurred/unblurred switch with an extra misleading number.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partial) {
        // Nothing is layered behind the window at all, so none of this is drawn: either the world
        // should render sharp, or a fullscreen window covers the viewport and the treatment would be
        // invisible under the opaque backdrop. Screen.extractBackground would otherwise blur the world
        // and lay the in-world menu texture over it.
        if (!window.isFramed() || !CraftrariaConfig.getInstance().isWindowBackgroundBlur()) {
            // Reproduced because Screen.extractBackground ends with it, and skipping it would drop
            // subtitles while the window is open.
            minecraft.gui.hud.extractDeferredSubtitles();
            return;
        }

        super.extractBackground(guiGraphics, mouseX, mouseY, partial);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partial) {
        syncViewport();
        TerrariaBrowser.samplePaintRate();
        TerrariaBrowser.observeFrameRate(minecraft.getFps());

        if (!window.isFramed()) {
            // A fullscreen window leaves nothing of the world to show, so keep the whole GUI
            // viewport covered while the page loads or the texture is repainting.
            guiGraphics.fill(0, 0, screenWidth(), screenHeight(), BACKDROP_COLOR);
        }

        window.render(guiGraphics, minecraft.font, TITLE);
        updateTitleButtons();

        int interiorX = window.interiorX();
        int interiorY = window.interiorY();
        int interiorWidth = window.interiorWidth();
        int interiorHeight = window.interiorHeight();

        // Chromium renders the whole viewport opaque, so the interior is covered rather than left
        // transparent while the page loads; the world stays visible everywhere else.
        guiGraphics.fill(interiorX, interiorY, interiorX + interiorWidth, interiorY + interiorHeight, BACKDROP_COLOR);

        if (browser != null && browser.isTextureReady()) {
            Identifier texture = browser.getTextureIdentifier();
            if (texture != null) {
                guiGraphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        texture,
                        interiorX, interiorY,
                        0f, 0f,
                        interiorWidth, interiorHeight,
                        interiorWidth, interiorHeight
                );
            } else {
                LOGGER.warn("Browser texture ready but identifier is null; interior={}x{} pos={}",
                        interiorWidth, interiorHeight, interiorX + "," + interiorY);
            }
        } else if (browser != null) {
            long now = System.currentTimeMillis();
            if (now - lastTextureNotReadyLog > 5000) {
                lastTextureNotReadyLog = now;
                LOGGER.warn("Browser texture not ready; interior={}x{} pos={} browser={}",
                        interiorWidth, interiorHeight, interiorX + "," + interiorY, browser.isTextureReady());
            }
        }

        if (window.isFramed()) {
            // After the surface, because the surface is opaque and would cover it.
            window.renderResizeGrip(guiGraphics);
        }

        renderLoadingIndicator(guiGraphics);
        renderResolutionNotice(guiGraphics);
        renderStatsOverlay(guiGraphics, partial);

        // Last, and on purpose: Screen.extractRenderState renders the renderables list, so widgets are
        // drawn here to sit on top of the window chrome and the opaque browser surface. Calling it
        // first paints the title bar and the surface over the buttons, which is why they were
        // clickable but invisible.
        super.extractRenderState(guiGraphics, mouseX, mouseY, partial);
    }

    /** Either shift key, so a resize drag can break the aspect lock from whichever hand is free. */
    private boolean isShiftHeld() {
        Window window = minecraft.getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private void updateTitleButtons() {
        modeButton.setX(window.titleButtonX(BrowserWindow.TITLE_BUTTON_MODE_TOGGLE));
        modeButton.setY(window.titleButtonY());
        closeButton.setX(window.titleButtonX(BrowserWindow.TITLE_BUTTON_CLOSE));
        closeButton.setY(window.titleButtonY());
        refreshButton.setX(window.titleButtonX(BrowserWindow.TITLE_BUTTON_REFRESH));
        refreshButton.setY(window.titleButtonY());
        settingsButton.setX(window.titleButtonX(BrowserWindow.TITLE_BUTTON_SETTINGS));
        settingsButton.setY(window.titleButtonY());

        if (window.isFramed() != titleButtonsLabelFramed) {
            titleButtonsLabelFramed = window.isFramed();
            modeButton.setIcon(window.isFramed() ? TitleBarButton.Icon.MAXIMIZE : TitleBarButton.Icon.RESTORE);
            modeButton.setTooltip(Tooltip.create(modeButtonTooltip()));
        }
    }

    /**
     * The settings live in a Cloth Config screen rather than behind more chat commands, and the
     * Terraria window is where a player is when they want to change them.
     */
    private void openSettings() {
        minecraft.gui.setScreen(CraftrariaConfigScreen.create(this));
    }

    private Component modeButtonTooltip() {
        return Component.literal(window.isFramed()
                ? "Expand the Terraria window to the full screen"
                : "Restore the Terraria window to a movable size");
    }

    private void renderStatsOverlay(GuiGraphicsExtractor guiGraphics, float partial) {
        if (!CraftrariaConfig.getInstance().isStatsOverlayEnabled()) {
            return;
        }

        List<String> lines = TerrariaStats.reportLines();
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, minecraft.font.width(line));
        }

        int x = window.interiorX();
        int y = window.interiorY();
        int lineHeight = minecraft.font.lineHeight + 1;
        int panelHeight = lines.size() * lineHeight + 2;
        guiGraphics.fill(x - 2, y - 2, x + widest + 2, y + panelHeight, 0xA0000000);

        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.text(minecraft.font, lines.get(index), x, y + index * lineHeight, LABEL_COLOR);
        }
    }

    /**
     * The strip along the top of the window offering a reload, shown now that the game's canvas no
     * longer matches the window. It lives in the window rather than in the page, and it covers the
     * top of the browser surface while it is up: everything it covers answers to the notice, so no
     * click under it reaches the game.
     */
    private void renderResolutionNotice(GuiGraphicsExtractor graphics) {
        if (!resolutionNoticeShowing) {
            return;
        }

        int x = window.interiorX();
        int y = window.interiorY();
        int width = window.interiorWidth();
        graphics.fill(x, y, x + width, y + noticeBarHeight(), NOTICE_BAR_COLOR);

        List<FormattedCharSequence> lines = noticeMessageLines();
        Font font = minecraft.font;
        if (noticeSharesRowWithButtons()) {
            graphics.text(font, lines.get(0), x + 4, y + (NOTICE_HEIGHT - GLYPH_CELL_HEIGHT) / 2, NOTICE_TEXT_COLOR);
        } else {
            // Too narrow for both on one row, so the message takes rows of its own above the
            // buttons. Wrapping is what keeps the explanation present at the smallest window size.
            int lineY = y + NOTICE_STACK_PADDING;
            for (FormattedCharSequence line : lines) {
                graphics.text(font, line, x + 4, lineY, NOTICE_TEXT_COLOR);
                lineY += font.lineHeight;
            }
        }

        renderNoticeButton(graphics, true, NOTICE_RELOAD_LABEL);
        renderNoticeButton(graphics, false, NOTICE_IGNORE_LABEL);
    }

    private void renderNoticeButton(GuiGraphicsExtractor graphics, boolean reload, Component label) {
        int x = noticeButtonX(reload);
        int y = noticeButtonY();
        int width = noticeButtonWidth(label);
        int right = x + width;
        int bottom = y + NOTICE_BUTTON_HEIGHT;
        boolean hovered = isOverNoticeButton(hoveredMouseX, hoveredMouseY, reload);
        graphics.fill(x, y, right, bottom, hovered ? NOTICE_BUTTON_HOVER_COLOR : NOTICE_BUTTON_COLOR);
        graphics.fill(x, y, right, y + 1, NOTICE_BUTTON_BORDER_COLOR);
        graphics.fill(x, bottom - 1, right, bottom, NOTICE_BUTTON_BORDER_COLOR);
        Font font = minecraft.font;
        graphics.text(font, label, x + (width - font.width(label)) / 2,
                y + (NOTICE_BUTTON_HEIGHT - GLYPH_CELL_HEIGHT) / 2, NOTICE_TEXT_COLOR);
    }

    /** Whether a point is anywhere inside the notice bar, button or not. */
    private boolean isOverNotice(double mouseX, double mouseY) {
        if (!resolutionNoticeShowing) {
            return false;
        }
        return mouseX >= window.interiorX() && mouseX < window.interiorX() + window.interiorWidth()
                && mouseY >= window.interiorY() && mouseY < window.interiorY() + noticeBarHeight();
    }

    private boolean isOverNoticeButton(double mouseX, double mouseY, boolean reload) {
        int y = noticeButtonY();
        int x = noticeButtonX(reload);
        return mouseX >= x && mouseX < x + noticeButtonWidth(noticeLabel(reload))
                && mouseY >= y && mouseY < y + NOTICE_BUTTON_HEIGHT;
    }

    /**
     * The message laid out for the window's current width: one line while the buttons fit beside it,
     * and wrapped onto as many rows as it needs above them once the window is too narrow for both.
     * The explanation is never what gets dropped for space — two buttons with nothing saying what
     * they do is the one outcome the layout has to avoid.
     */
    private List<FormattedCharSequence> noticeMessageLines() {
        Component message = Component.translatable("craftraria.notice.resolutionChanged");
        if (noticeSharesRowWithButtons()) {
            return List.of(message.getVisualOrderText());
        }
        return minecraft.font.split(message, window.interiorWidth() - NOTICE_MESSAGE_PADDING * 2);
    }

    private boolean noticeSharesRowWithButtons() {
        Component message = Component.translatable("craftraria.notice.resolutionChanged");
        return minecraft.font.width(message) + NOTICE_BUTTON_GAP + noticeButtonsWidth()
                <= window.interiorWidth() - NOTICE_MESSAGE_PADDING;
    }

    private int noticeButtonsWidth() {
        return noticeButtonWidth(NOTICE_RELOAD_LABEL) + NOTICE_BUTTON_GAP + noticeButtonWidth(NOTICE_IGNORE_LABEL);
    }

    /** Height of the notice: a single row, or the message's rows with the buttons underneath. */
    private int noticeBarHeight() {
        if (noticeSharesRowWithButtons()) {
            return NOTICE_HEIGHT;
        }
        return noticeMessageLines().size() * minecraft.font.lineHeight
                + NOTICE_STACK_PADDING * 2 + NOTICE_BUTTON_HEIGHT;
    }

    private Component noticeLabel(boolean reload) {
        return reload ? NOTICE_RELOAD_LABEL : NOTICE_IGNORE_LABEL;
    }

    private int noticeButtonX(boolean reload) {
        int ignoreWidth = noticeButtonWidth(NOTICE_IGNORE_LABEL);
        int ignoreX = window.interiorX() + window.interiorWidth() - ignoreWidth - 2;
        return reload
                ? ignoreX - noticeButtonWidth(NOTICE_RELOAD_LABEL) - NOTICE_BUTTON_GAP
                : ignoreX;
    }

    /** Width from the label plus even padding on both sides, so no label is ever cramped. */
    private int noticeButtonWidth(Component label) {
        return minecraft.font.width(label) + NOTICE_BUTTON_PADDING * 2;
    }

    private int noticeButtonY() {
        if (noticeSharesRowWithButtons()) {
            return window.interiorY() + (NOTICE_HEIGHT - NOTICE_BUTTON_HEIGHT) / 2;
        }
        return window.interiorY() + NOTICE_STACK_PADDING + noticeMessageLines().size() * minecraft.font.lineHeight
                + NOTICE_STACK_PADDING;
    }

    private void renderLoadingIndicator(GuiGraphicsExtractor guiGraphics) {
        if (browser == null || !browser.isLoading()) {
            return;
        }

        int bx = window.interiorX();
        int by = window.interiorY();
        int bw = window.interiorWidth();
        guiGraphics.fill(bx, by, bx + bw, by + LOADING_BAR_HEIGHT, LOADING_BAR_TRACK_COLOR);

        int segmentWidth = Math.max(20, bw / 4);
        int travelRange = bw + segmentWidth;
        int animatedOffset = (int) ((Util.getMillis() / 6L) % travelRange) - segmentWidth;
        int segmentStart = Math.max(bx, bx + animatedOffset);
        int segmentEnd = Math.min(bx + bw, bx + animatedOffset + segmentWidth);
        if (segmentEnd > segmentStart) {
            guiGraphics.fill(segmentStart, by, segmentEnd, by + LOADING_BAR_HEIGHT, LOADING_BAR_FILL_COLOR);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        boolean handled = super.mouseClicked(event, isDoubleClick);
        if (handled) {
            return true;
        }

        // Same reason as the keyboard half in keyPressed: the open key rebound onto a mouse button
        // closes the window here, because a screen being open means no click ever reaches the tick
        // handler. Checked before the browser press so the button is not also pressed in the page.
        if (CraftrariaClient.isOpenTerrariaToggleEnabled() && CraftrariaClient.isOpenTerrariaMouseKey(event)) {
            onClose();
            return true;
        }

        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (isDoubleClick && window.isOverTitleBar(event.x(), event.y())) {
                window.toggleFramed(screenWidth(), screenHeight());
                return true;
            }
            int resizeMask = window.resizeMaskAt(event.x(), event.y());
            if (window.isResizeMask(resizeMask)) {
                window.beginResize(resizeMask, event.x(), event.y());
                return true;
            }
            if (window.isOverTitleBar(event.x(), event.y())) {
                window.beginMove(event.x(), event.y());
                return true;
            }
        }

        // The notice bar covers the top of the window and answers every click inside it, so the
        // game never sees a press under it.
        if (isOverNotice(event.x(), event.y())) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && isOverNoticeButton(event.x(), event.y(), true)) {
                reloadPageAtCurrentSize();
            } else {
                dismissResolutionNotice();
            }
            return true;
        }

        if (browser == null || !isInBrowserBounds(event.x(), event.y())) {
            return false;
        }

        browser.sendMousePress(toBrowserX(event.x()), toBrowserY(event.y()), event.button());
        browser.setFocus(true);
        browserButtonDown = true;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (window.isDragging()) {
            // Shift suspends the aspect lock for this drag, so a player can always break 16:9.
            window.dragTo(event.x(), event.y(), screenWidth(), screenHeight(), isShiftHeld());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        window.endDrag();
        if (browserButtonDown && browser != null) {
            browser.sendMouseRelease(toBrowserX(event.x()), toBrowserY(event.y()), event.button());
            browserButtonDown = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        hoveredMouseX = mouseX;
        hoveredMouseY = mouseY;
        if (browser != null && !window.isDragging() && !isOverNotice(mouseX, mouseY) && isInBrowserBounds(mouseX, mouseY)) {
            browser.sendMouseMove(toBrowserX(mouseX), toBrowserY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        boolean handled = super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (handled) {
            return true;
        }

        // Scrolling the notice bar does not scroll the game underneath it, same as clicking it.
        if (isOverNotice(mouseX, mouseY)) {
            return true;
        }

        if (browser == null || !isInBrowserBounds(mouseX, mouseY)) {
            return false;
        }

        browser.sendMouseWheel(toBrowserX(mouseX), toBrowserY(mouseY), scrollY, 0);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }

        if (super.keyPressed(event)) {
            return true;
        }

        // The open key closing this screen has to be handled here rather than polled from a tick:
        // Minecraft's key handler only dispatches KeyMapping.set and KeyMapping.click when no screen
        // is open, so a press while this screen is showing never reaches the tick handler at all.
        // Handling it here also keeps the key out of the page, so the button is not pressed in
        // Terraria by the same keystroke that closes the window.
        if (CraftrariaClient.isOpenTerrariaToggleEnabled() && CraftrariaClient.isOpenTerrariaKey(event)) {
            onClose();
            return true;
        }

        if (browser != null && isFocused()) {
            browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
            browser.setFocus(true);
            return true;
        }

        return false;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (browser != null) {
            browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (super.charTyped(event)) {
            return true;
        }
        if (browser != null) {
            browser.sendKeyTyped((char) event.codepoint(), 0);
            browser.setFocus(true);
            return true;
        }

        return false;
    }

    /** One-line snapshot of what the overlay shows, for the log and the chat command. */
    public static String statsSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        return "minecraft=" + minecraft.getFps() + "fps"
                + " chromiumPaints/s=" + TerrariaBrowser.samplePaintRate()
                + " paintSource=" + TerrariaBrowser.lastPaintWidth() + "x" + TerrariaBrowser.lastPaintHeight()
                + " renderScale=" + CraftrariaConfig.getInstance().getBrowserRenderScale()
                + " targetFps=" + CraftrariaConfig.getInstance().getBrowserFrameRate()
                + " window=" + CraftrariaConfig.getInstance().isWindowFramed();
    }

    @Override
    public void tick() {
        super.tick();
        createBrowserIfNotReady();
    }

    /**
     * Detaches the browser without destroying it. The game page keeps running so the next open is
     * instant, at a small offscreen size so no frames are spent on an unseen page.
     */
    @Override
    public void onClose() {
        TerrariaBrowser.park();
        super.onClose();
    }
}
