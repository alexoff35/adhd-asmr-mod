package io.github.alexoff35.adhdasmr;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import org.lwjgl.sdl.SDLScancode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

public final class AdhdAsmr implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("ADHD ASMR");
    private static final int PLAYER_WIDTH = 640;
    private static final int PLAYER_HEIGHT = 360;
    static AdhdAsmr instance;
    Playlist playlist;
    private KeyMapping toggle;
    private KeyMapping pause;
    private KeyMapping next;
    private KeyMapping previous;
    private KeyMapping cursor;
    private KeyMapping louder;
    private KeyMapping quieter;
    private PlayerPage page;
    private MCEFBrowser browser;
    private KeyMapping settingsKey;
    private boolean shown;
    private boolean failed;
    private boolean publishedVisibility;
    private boolean dirty;
    private boolean stopping;
    private String publishedVideo;

    @Override public void onInitializeClient() {
        instance = this;
        playlist = new Playlist();
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("adhd_asmr", "controls"));
        toggle = bind(category, "toggle", InputConstants.KEY_F9);
        pause = bind(category, "pause", InputConstants.KEY_F7);
        next = bind(category, "next", InputConstants.KEY_F8);
        previous = bind(category, "previous", InputConstants.KEY_F6);
        cursor = bind(category, "cursor", InputConstants.KEY_F10);
        louder = bind(category, "volume_up", InputConstants.KEY_ADD);
        quieter = bind(category, "volume_down", SDLScancode.SDL_SCANCODE_KP_MINUS);
        settingsKey = bind(category, "settings", InputConstants.KEY_F4);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            stopping = true;
            if (browser != null) browser.close();
            if (page != null) page.close();
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (showsPlayerOverlay(screen)) {
                ScreenEvents.afterExtract(screen).register((current, graphics, x, y, delta) -> {
                    if (visible(mc)) draw(graphics);
                });
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("adhd_asmr", "player"), (graphics, tick) -> {
            var mc = Minecraft.getInstance();
            if (mc.gui.screen() == null && visible(mc)) draw(graphics);
        });
    }

    private static KeyMapping bind(KeyMapping.Category category, String name, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.adhd_asmr." + name, code, category));
    }

    private void tick(Minecraft mc) {
        if (settingsKey.consumeClick() && mc.level != null && mc.gui.screen() == null) {
            mc.gui.setScreen(new SettingsScreen(null, this));
        }
        if (mc.level != null && mc.gui.screen() == null) {
            if (toggle.consumeClick()) toggle(mc);
            if (cursor.consumeClick() && visible(mc)) mc.gui.setScreen(new CursorScreen(this));
            if (shown && playlist.enabled()) {
                if (pause.consumeClick()) command("toggle", 0);
                if (next.consumeClick()) playlist.step(1);
                if (previous.consumeClick()) playlist.step(-1);
                while (louder.consumeClick()) command("volume", 5);
                while (quieter.consumeClick()) command("volume", -5);
            } else {
                while (pause.consumeClick()) { }
                while (next.consumeClick()) { }
                while (previous.consumeClick()) { }
                while (louder.consumeClick()) { }
                while (quieter.consumeClick()) { }
            }
        }
        publish(mc);
    }

    void play(String id) {
        playlist.select(id);
        if (!playlist.enabled()) playlist.setEnabled(true);
        shown = true;
        var mc = Minecraft.getInstance();
        start(mc);
        publish(mc);
    }

    void toggle(Minecraft mc) {
        if (!playlist.enabled()) return;
        setPlayerActive(!shown);
    }

    void setEnabled(boolean enabled) {
        playlist.setEnabled(enabled);
        if (!enabled) shown = false;
        publish(Minecraft.getInstance());
    }

    private void start(Minecraft mc) {
        if (page != null) return;
        failed = false;
        try {
            page = new PlayerPage();
            final String url = page.url();
            MCEFApi.getInstanceFuture().thenApplyAsync(api -> {
                if (stopping) return null;
                MCEFBrowser created = api.createBrowser(url, false);
                created.resize(PLAYER_WIDTH, PLAYER_HEIGHT);
                return created;
            }, mc::execute).whenCompleteAsync((created, error) -> {
                if (error != null) {
                    LOG.warn("Could not start embedded YouTube player", error);
                    if (page != null) page.close();
                    page = null;
                    failed = true;
                } else if (created != null && stopping) {
                    created.close();
                } else if (created != null) {
                    browser = created;
                    if (mc.gui.screen() instanceof CursorScreen) browser.setFocus(true);
                }
            }, mc::execute);
            dirty = true;
        } catch (IOException | RuntimeException | LinkageError e) {
            failed = true;
            if (page != null) page.close();
            page = null;
            LOG.warn("Could not start the YouTube player page", e);
        }
    }

    boolean visible(Minecraft mc) {
        return shown && playlist.enabled() && mc.level != null && !mc.gui.hud.isHidden()
                && (mc.gui.screen() == null || mc.gui.screen() instanceof CursorScreen
                || showsPlayerOverlay(mc.gui.screen()));
    }

    private static boolean showsPlayerOverlay(Screen screen) {
        return screen instanceof PauseScreen || screen instanceof AbstractContainerScreen
                || screen instanceof ChatScreen;
    }

    private void publish(Minecraft mc) {
        if (page == null) return;
        String id = playlist.selected();
        boolean visible = visible(mc);
        if (dirty || visible != publishedVisibility || !Objects.equals(id, publishedVideo)) {
            page.update(id, visible);
            publishedVideo = id;
            publishedVisibility = visible;
            dirty = false;
        }
    }

    private void command(String action, int value) {
        if (page != null) {
            page.command(action, value);
            dirty = true;
        }
    }

    boolean input(net.minecraft.client.input.KeyEvent event) {
        if (cursor.matches(event)) return true;
        if (toggle.matches(event)) { toggle(Minecraft.getInstance()); return true; }
        if (pause.matches(event)) { command("toggle", 0); return true; }
        if (next.matches(event)) { playlist.step(1); return true; }
        if (previous.matches(event)) { playlist.step(-1); return true; }
        if (louder.matches(event)) { command("volume", 5); return true; }
        if (quieter.matches(event)) { command("volume", -5); return true; }
        return false;
    }

    boolean closeCursor(net.minecraft.client.input.KeyEvent event) {
        return cursor.matches(event) || toggle.matches(event);
    }

    MCEFBrowser browser() { return browser; }

    void draw(GuiGraphicsExtractor graphics) {
        var box = bounds(graphics.guiWidth(), graphics.guiHeight());
        var texture = browser == null ? null : browser.getTextureView();
        if (texture != null && playlist.selected() != null) {
            graphics.blit(texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                    box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), 0f, 1f, 0f, 1f);
        } else {
            graphics.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), 0xDE141419);
            var status = net.minecraft.network.chat.Component.translatable(failed ? "adhd_asmr.failed" : playlist.selected() == null ? "adhd_asmr.empty" : "adhd_asmr.loading"); graphics.centeredText(Minecraft.getInstance().font, status,
                    box.x() + box.width() / 2, box.y() + box.height() / 2 - 4, 0xFFFFFFFF);
        }
    }

    Box bounds(int width, int height) {
        int size = Math.max(1, Math.min(Math.min(width - 16, (height - 16) * 16 / 9),
                width * playlist.scale() / 100));
        int h = Math.max(1, size * 9 / 16);
        int corner = playlist.corner();
        return new Box(corner % 2 == 0 ? 8 : width - size - 8,
                corner < 2 ? 8 : height - h - 8, size, h);
    }

    boolean playerActive() { return shown && playlist.enabled(); }

    void setPlayerActive(boolean active) {
        if (active && !playlist.enabled()) return;
        shown = active;
        var mc = Minecraft.getInstance();
        if (active) start(mc);
        publish(mc);
    }

    record Box(int x, int y, int width, int height) {
        boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
        int browserX(double px) { return (int) Math.floor((px - x) * PLAYER_WIDTH / width); }
        int browserY(double py) { return (int) Math.floor((py - y) * PLAYER_HEIGHT / height); }
    }
}
