package fr.adrien.cfcsearch.stats;

import fr.adrien.cfcsearch.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2i;

import java.util.ArrayList;
import java.util.List;

/**
 * Hover help texts: first line of the lang entry is the gold title, "\n" separates paragraphs, long lines wrap.
 * Everything is measured with the game's own font in GUI units, so GUI scale, the Unicode font and long
 * translations only change how the text is wrapped, never let it leave the screen.
 */
public final class Tips {

    private static final int WIDTH = 230;
    /** Line height used by vanilla tooltips. */
    private static final int LINE_H = 10;

    private Tips() {}

    public static List<FormattedCharSequence> lines(Font font, String key) {
        return lines(font, key, WIDTH);
    }

    private static List<FormattedCharSequence> lines(Font font, String key, int width) {
        String[] parts = Lang.t(key).split("\n");
        List<FormattedCharSequence> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            Component c = Component.literal(parts[i]).withStyle(i == 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY);
            if (parts[i].isEmpty()) out.add(FormattedCharSequence.EMPTY);
            else out.addAll(font.split(c, width));
        }
        return out;
    }

    /**
     * Draws a help tooltip that stays clear of the screen edges: a long text is wrapped wider (up to most of the
     * screen width) until it fits the height with a comfortable margin, and only then scaled down.
     */
    public static void render(GuiGraphics graphics, Font font, String key, int mouseX, int mouseY) {
        int screenW = graphics.guiWidth(), screenH = graphics.guiHeight();
        int marginY = Math.max(16, screenH / 12), marginX = Math.max(8, screenW / 40);
        int room = screenH - 2 * marginY;
        int maxWidth = Math.max(WIDTH, (int) (screenW * 0.8f) - 2 * marginX);

        List<FormattedCharSequence> lines = lines(font, key, WIDTH);
        for (int width = WIDTH + 40; height(lines) > room && width <= maxWidth + 39; width += 40)
            lines = lines(font, key, Math.min(width, maxWidth));

        float scale = Math.min(1f, (float) room / height(lines));
        float sw = screenW / scale, sh = screenH / scale;
        int mx = (int) (mouseX / scale), my = (int) (mouseY / scale);
        int mX = (int) (marginX / scale), mY = (int) (marginY / scale);
        graphics.pose().pushPose();
        if (scale < 1f) graphics.pose().scale(scale, scale, 1);
        graphics.renderTooltip(font, lines, (w, h, px, py, tw, th) -> {
            int x = px + 12;
            if (x + tw + mX > sw) x = Math.max(px - 16 - tw, mX);
            int y = Math.max(mY, Math.min(py - 12, (int) sh - th - mY));
            return new Vector2i(x, y);
        }, mx, my);
        graphics.pose().popPose();
    }

    /** Tooltip box height: lines, the gap under the title and the frame. */
    private static int height(List<FormattedCharSequence> lines) {
        return lines.size() * LINE_H + 2 + 8;
    }
}
