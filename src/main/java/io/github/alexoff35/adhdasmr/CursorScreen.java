package io.github.alexoff35.adhdasmr;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;

final class CursorScreen extends Screen {
    private final AdhdAsmr mod;
    private final Set<Integer> down = new HashSet<>();
    private boolean hovered;

    CursorScreen(AdhdAsmr mod) {
        super(Component.literal("ADHD ASMR"));
        this.mod = mod;
    }

    @Override protected void init() {
        if (mod.browser() != null) mod.browser().setFocus(true);
    }

    @Override public void removed() {
        var browser = mod.browser();
        if (browser != null) {
            for (int button : down) {
                browser.onMouseReleased(new MouseButtonEvent(0, 0, new net.minecraft.client.input.MouseButtonInfo(button, 0)));
            }
            browser.onMouseMoved(-1, -1);
            browser.setFocus(false);
        }
        down.clear();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {}

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        if (!mod.visible(minecraft)) return;
        mod.draw(graphics);
        if (mod.browser() != null && box().contains(x, y)) graphics.requestCursor(mod.browser().getCursorType());
    }

    @Override public boolean keyPressed(KeyEvent event) {
        boolean close = mod.closeCursor(event);
        if (mod.input(event)) {
            if (close) onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        var player = mod.browser();
        if (player != null && mod.visible(minecraft) && box().contains(event.x(), event.y())) {
            down.add(event.button());
            player.onMouseClicked(relative(event), doubled);
        }
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        var player = mod.browser();
        if (player != null && down.remove(event.button())) player.onMouseReleased(relative(event));
        return true;
    }

    @Override public void mouseMoved(double x, double y) { move(x, y); }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        move(event.x(), event.y());
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        var box = box();
        var browser = mod.browser();
        if (browser != null && box.contains(x, y)) browser.onMouseScrolled(box.browserX(x), box.browserY(y), vertical);
        return true;
    }

    private void move(double x, double y) {
        var box = box();
        var browser = mod.browser();
        if (browser == null) return;
        boolean inside = mod.visible(minecraft) && box.contains(x, y);
        if (inside || !down.isEmpty()) browser.onMouseMoved(box.browserX(x), box.browserY(y));
        else if (hovered) browser.onMouseMoved(-1, -1);
        hovered = inside;
    }

    private MouseButtonEvent relative(MouseButtonEvent event) {
        var box = box();
        return new MouseButtonEvent(box.browserX(event.x()), box.browserY(event.y()), event.buttonInfo());
    }

    private AdhdAsmr.Box box() { return mod.bounds(width, height); }
}
