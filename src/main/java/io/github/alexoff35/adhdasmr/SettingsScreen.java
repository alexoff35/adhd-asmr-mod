package io.github.alexoff35.adhdasmr;

import com.mojang.blaze3d.platform.NativeImage;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class SettingsScreen extends Screen {
    private final Screen parent;
    private final AdhdAsmr mod;
    private final Map<String, Preview> previews = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private EditBox input;
    private Button fetch, previous, next, clear;
    private Button player;
    private final List<Button> playRows = new ArrayList<>();
    private final List<Button> copyRows = new ArrayList<>();
    private final List<Button> removeRows = new ArrayList<>();
    private List<String> queue = List.of();
    private int page, left, leftWidth, right, rightWidth, limit;
    private boolean fetching, closed;
    private String draft = "";
    private Component status = Component.empty();

    SettingsScreen(Screen parent, AdhdAsmr mod) {
        super(Component.literal("ADHD ASMR"));
        this.parent = parent;
        this.mod = mod;
    }

    static Component tr(String key, Object... args) {
        return Component.translatable("adhd_asmr." + key, args);
    }

    @Override protected void init() {
        closed = false;
        left = 10;
        leftWidth = width / 2 - 20;
        right = width / 2 + 10;
        rightWidth = width - right - 10;

        settingRow(30, () -> {
            mod.setEnabled(!mod.playlist.enabled());
            player.active = mod.playlist.enabled();
        });
        player = settingRow(54, () -> mod.setPlayerActive(!mod.playerActive()));
        player.active = mod.playlist.enabled();
        addRenderableWidget(new AbstractSliderButton(left, 80, leftWidth, 20,
                Component.empty(), (mod.playlist.scale() - 15) / 40.0) {
            @Override protected void updateMessage() {}
            @Override protected void applyValue() { mod.playlist.setScale(15 + (int)Math.round(value * 40)); }
        });
        settingRow(104, () -> mod.playlist.nextCorner());

        int half = (rightWidth - 4) / 2;
        button("shuffle", right, 30, half, () -> { mod.playlist.shuffle(); updateRows(); });
        fetch = button("fetch", right + half + 4, 30, half, this::fetchQueue);
        fetch.active = !fetching;
        int addWidth = 22;
        input = new EditBox(font, right, 54, rightWidth - addWidth - 4, 20, tr("url"));
        input.setHint(tr("url"));
        input.setMaxLength(512);
        input.setValue(draft);
        input.setResponder(value -> draft = value);
        addRenderableWidget(input);
        iconButton("add", "+", right + rightWidth - addWidth, 54, this::addVideo);

        playRows.clear();
        copyRows.clear();
        removeRows.clear();
        limit = Math.max(0, (height - 144) / 30);
        int copyWidth = 22;
        int removeWidth = 22;
        for (int i = 0; i < limit; i++) {
            final int slot = i;
            int y = 89 + i * 30;
            int removeX = right + rightWidth - removeWidth;
            int copyX = removeX - copyWidth - 3;
            playRows.add(addRenderableWidget(Button.builder(Component.empty(), b -> {
                mod.play(rowId(slot));
                rebuildWidgets();
            }).bounds(right, y, copyX - right - 3, 27).build()));
            copyRows.add(iconButton("copy", "", copyX, y + 3, () -> {
                minecraft.keyboardHandler.setClipboard(Playlist.url(rowId(slot)));
                status = tr("copied");
            }));
            removeRows.add(iconButton("remove", "×", removeX, y + 3, () -> {
                mod.playlist.remove(rowId(slot)); updateRows();
            }));
        }
        int y = height - 48;
        previous = addRenderableWidget(Button.builder(Component.literal("‹"), b -> { page--; updateRows(); }).bounds(right, y, 20, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("›"), b -> { page++; updateRows(); }).bounds(right + 23, y, 20, 20).build());
        clear = button("clear", right + 47, y, rightWidth - 47, () -> {
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
                if (yes) { mod.playlist.clear(); page = 0; }
                minecraft.gui.setScreen(this);
            }, tr("clear"), tr("confirm_clear")));
        });
        button("reset", width / 2 - 122, height - 24, 120, () -> {
            mod.playlist.resetSettings();
            mod.setPlayerActive(false);
            rebuildWidgets();
        });
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 + 2, height - 24, 120, 20).build());
        updateRows();
    }

    private Button settingRow(int y, Runnable action) {
        return addRenderableWidget(Button.builder(Component.empty(), button -> action.run())
                .bounds(left, y, leftWidth, 20).build());
    }

    private void settingText(GuiGraphicsExtractor g, int y, Component label, Component value, int color) {
        int maxLabelWidth = Math.max(1, leftWidth - font.width(value) - 17);
        g.text(font, font.plainSubstrByWidth(label.getString(), maxLabelWidth), left + 7, y + 6, color);
        g.text(font, value, left + leftWidth - font.width(value) - 7, y + 6, color);
    }
    private String rowId(int slot) { return queue.get(page * limit + slot); }

    private void updateRows() {
        queue = mod.playlist.videos();
        int last = limit == 0 ? 0 : Math.max(0, (queue.size() - 1) / limit);
        page = Math.clamp(page, 0, last);
        previous.active = page > 0;
        next.active = page < last;
        clear.active = !queue.isEmpty();
        for (int i = 0; i < limit; i++) {
            boolean exists = page * limit + i < queue.size();
            playRows.get(i).visible = exists;
            copyRows.get(i).visible = exists;
            removeRows.get(i).visible = exists;
            if (exists) {
                String id = rowId(i);
                playRows.get(i).setTooltip(Tooltip.create(Component.literal(Playlist.url(id))));
                requestPreview(id);
            }
        }
    }

    private void addVideo() {
        if (!mod.playlist.add(input.getValue())) { status = tr("invalid"); return; }
        input.setValue("");
        status = Component.empty();
        if (limit > 0) page = (mod.playlist.videos().size() - 1) / limit;
        updateRows();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (input.isFocused() && (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER)) { addVideo(); return true; }
        return super.keyPressed(event);
    }

    private void fetchQueue() {
        fetching = true;
        fetch.active = false;
        status = tr("loading");
        mod.playlist.download().whenComplete((ids, error) -> minecraft.execute(() -> {
            fetching = false;
            if (error == null) mod.playlist.replaceRemote(ids);
            status = tr(error == null ? "updated" : "error");
            fetch.active = true;
            updateRows();
        }));
    }

    private void requestPreview(String id) {
        if (!requested.add(id)) return;
        CompletableFuture.supplyAsync(() -> {
            String title = null;
            byte[] pixels = null;
            try {
                String query = URLEncoder.encode(Playlist.url(id), StandardCharsets.UTF_8);
                title = JsonParser.parseString(new String(download("https://www.youtube.com/oembed?format=json&url=" + query, 32768),
                        StandardCharsets.UTF_8)).getAsJsonObject().get("title").getAsString();
            } catch (Exception ignored) {}
            try {
                byte[] jpeg = download("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", 262144);
                var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
                if (image != null) {
                    var png = new java.io.ByteArrayOutputStream();
                    if (javax.imageio.ImageIO.write(image, "png", png)) pixels = png.toByteArray();
                }
            } catch (Exception ignored) {}
            return new LoadedPreview(title, pixels);
        }).thenAccept(item -> minecraft.execute(() -> {
            if (closed) return;
            DynamicTexture texture = null;
            if (item.pixels() != null) {
                try {
                    texture = new DynamicTexture(() -> "ADHD ASMR preview", NativeImage.read(item.pixels()));
                } catch (java.io.IOException | RuntimeException ignored) {}
            }
            previews.put(id, new Preview(item.title(), texture));
        }));
    }

    private static byte[] download(String url, int limit) throws java.io.IOException {
        var connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(3500);
        connection.setReadTimeout(3500);
        try (var stream = connection.getInputStream()) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new java.io.IOException("Response too large");
            return bytes;
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        super.extractRenderState(g, x, y, delta);
        g.text(font, tr("settings"), left, 13, 0xFFFFFFFF);
        g.text(font, tr("queue"), right, 13, 0xFFFFFFFF);
        g.fill(width / 2, 9, width / 2 + 1, height - 29, 0x885C667A);
        settingText(g, 30, tr("mod"), Component.translatable(mod.playlist.enabled() ? "gui.yes" : "gui.no"), 0xFFFFFFFF);
        settingText(g, 54, tr("player"), Component.translatable(mod.playerActive() ? "gui.yes" : "gui.no"),
                player.active ? 0xFFFFFFFF : 0xFFAAAAAA);
        settingText(g, 80, tr("scale_label"), Component.literal(mod.playlist.scale() + "%"), 0xFFFFFFFF);
        settingText(g, 104, tr("corner_label"), tr("c" + mod.playlist.corner()), 0xFFFFFFFF);
        g.text(font, font.plainSubstrByWidth(status.getString(), rightWidth), right, 77, 0xFFCCD1DD);
        if (queue.isEmpty()) g.text(font, tr("empty"), right, 94, 0xFFCCD1DD);
        for (int i = 0; i < limit && page * limit + i < queue.size(); i++) {
            drawClipboard(g, copyRows.get(i));
            String id = rowId(i);
            int top = 89 + i * 30;
            var preview = previews.get(id);
            var texture = preview == null ? null : preview.texture();
            g.fill(right + 3, top + 3, right + 35, top + 24, 0xFF252831);
            if (texture != null) g.blit(texture.getTextureView(), com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR),
                    right + 3, top + 3, right + 35, top + 24, 0, 1, 0, 1);
            int available = playRows.get(i).getWidth() - 43;
            String name = preview != null && preview.title() != null ? preview.title() : tr("video").getString();
            if (id.equals(mod.playlist.selected())) name = "▶ " + name;
            g.text(font, font.plainSubstrByWidth(name, available), right + 39, top + 4, 0xFFFFFFFF);
            g.text(font, font.plainSubstrByWidth(Playlist.url(id), available), right + 39, top + 15, 0xFFABB3C4);
        }
    }

    @Override public void removed() {
        mod.playlist.saveSettings();
        closed = true;
        for (Preview preview : previews.values()) if (preview.texture() != null) preview.texture().close();
        previews.clear();
        requested.clear();
    }



    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    private static void drawClipboard(GuiGraphicsExtractor g, Button button) {
        int x = button.getX() + (button.getWidth() - 10) / 2;
        int y = button.getY() + (button.getHeight() - 12) / 2;
        int ink = 0xFFFFFFFF;
        g.fill(x, y + 2, x + 1, y + 12, ink);
        g.fill(x + 9, y + 2, x + 10, y + 12, ink);
        g.fill(x + 1, y + 2, x + 9, y + 3, ink);
        g.fill(x + 1, y + 11, x + 9, y + 12, ink);
        g.fill(x + 3, y, x + 7, y + 4, ink);
        g.fill(x + 3, y + 6, x + 7, y + 7, ink);
        g.fill(x + 3, y + 8, x + 7, y + 9, ink);
    }

    private Button iconButton(String key, String icon, int x, int y, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(icon), b -> action.run())
                .bounds(x, y, 22, 20).tooltip(Tooltip.create(tr(key))).build());
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(tr(key), b -> action.run()).bounds(x, y, width, 20).build());
    }

    private record Preview(String title, DynamicTexture texture) {}
    private record LoadedPreview(String title, byte[] pixels) {}
}