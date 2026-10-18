package fr.adrien.cfcsearch.mixin;

import fr.adrien.cfcsearch.Lang;

import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import fr.adrien.cfcsearch.search.SearchQuery;
import fr.adrien.cfcsearch.address.Network;
import fr.adrien.cfcsearch.compat.ExtraAreas;
import fr.adrien.cfcsearch.stats.Tips;
import fr.adrien.cfcsearch.stats.AddressStats;
import fr.adrien.cfcsearch.stats.Clickable;
import fr.adrien.cfcsearch.stats.CsvRecorder;
import io.github.nbcss.createfactorycontroller.content.block.FactoryControllerMenu;
import io.github.nbcss.createfactorycontroller.content.component.VirtualComponentPosition;
import io.github.nbcss.createfactorycontroller.content.gui.screen.controller.FactoryControllerScreen;
import io.github.nbcss.createfactorycontroller.content.gui.widget.VirtualComponentWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Adds to the Factory Controller screen, top-right of the canvas next to the settings button:
 * <ul>
 *   <li>a search field (JEI-style prefixes, see {@link SearchQuery}): matching components are outlined, the others
 *       dimmed; Enter / Shift+Enter centres the view on the next / previous match; Ctrl+F focuses, Escape leaves,
 *       right-click clears;</li>
 *   <li>a "Stats" toggle showing throughput per destination address (see {@link AddressStats}); clicking a row
 *       searches for that address.</li>
 * </ul>
 */
@Mixin(FactoryControllerScreen.class)
public abstract class FactoryControllerScreenMixin extends AbstractSimiContainerScreen<FactoryControllerMenu> {

    @Unique private static final int CELL = 16;               // FactoryControllerScreen.CANVAS_COMPONENT_SIZE
    @Unique private static final int BOX_W = 80;
    /** Distance from the frame's right edge to the settings button (CANVAS_SIDE_PADDING + SETTINGS_BTN_W + 8). */
    @Unique private static final int SETTINGS_RIGHT_OFFSET = 9 + 23 + 8;
    /** Settings button top relative to topPos (CANVAS_TOP_PADDING + 8). */
    @Unique private static final int SETTINGS_TOP_OFFSET = 16 + 8;
    @Unique private static final long CACHE_MS = 500;
    @Unique private static final int STATS_BTN_W = 28;
    @Unique private static final int STATS_W = 250;
    @Unique private static final int ROW_H = 11;
    /** Space kept free above the canvas bottom for the collapsible player inventory tab. */
    @Unique private static final int INVENTORY_RESERVE = 62;
    @Unique private static final int SAMPLE_EVERY_TICKS = 20;
    /** Kept across screen openings so the last search / panel state survive reopening a controller. */
    @Unique private static String cfcsearch$lastQuery = "";
    @Unique private static boolean cfcsearch$statsOpen = false;
    /** Network shown in the stats panel (null = all, "" = default network without prefix). */
    @Unique @Nullable private static String cfcsearch$netFilter = null;

    @Shadow @Final private Map<VirtualComponentPosition, VirtualComponentWidget> componentWidgets;
    @Shadow private double viewX;
    @Shadow private double viewY;
    @Shadow @Nullable private EditBox nameBox;

    @Shadow private void clampView() { throw new AssertionError(); }
    @Shadow private void commitName() { throw new AssertionError(); }

    @Unique @Nullable private EditBox cfcsearch$box;
    @Unique private int cfcsearch$cursor = -1;
    @Unique private List<VirtualComponentPosition> cfcsearch$matches = List.of();
    @Unique private String cfcsearch$cachedQuery = null;
    @Unique private long cfcsearch$cachedAt = 0;
    @Unique private int cfcsearch$tickCounter = 0;
    /** What the stats panel drew last frame, so clicks hit what was shown. */
    @Unique private List<Clickable> cfcsearch$clickables = new ArrayList<>();
    @Unique @Nullable private Rect2i cfcsearch$panelRect = null;
    @Unique private List<Rect2i> cfcsearch$areas = List.of();
    @Unique private int cfcsearch$statsScroll = 0;
    @Unique private int cfcsearch$statsMaxScroll = 0;
    /** Hover help collected while drawing: area → lang key (the last one under the cursor wins). */
    @Unique private final List<Rect2i> cfcsearch$tipAreas = new ArrayList<>();
    @Unique private final List<String> cfcsearch$tipKeys = new ArrayList<>();
    /** Real cursor position this frame (render's own mouse arguments are hidden while over our panels). */
    @Unique private int cfcsearch$mouseX, cfcsearch$mouseY;

    private FactoryControllerScreenMixin(FactoryControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // ── Layout ────────────────────────────────────────────────────────────

    @Inject(method = "init", at = @At("TAIL"))
    private void cfcsearch$init(CallbackInfo ci) {
        String value = cfcsearch$box != null ? cfcsearch$box.getValue() : cfcsearch$lastQuery;
        boolean focused = cfcsearch$box != null && cfcsearch$box.isFocused();
        if (cfcsearch$box != null) removeWidget(cfcsearch$box);

        // Inside the canvas, top-right, just left of the settings button (the title bar is too crowded).
        int x = leftPos + imageWidth - SETTINGS_RIGHT_OFFSET - 8 - BOX_W;
        int y = topPos + SETTINGS_TOP_OFFSET + 3;
        EditBox box = new EditBox(font, x, y, BOX_W, 10, Component.literal("Search"));
        box.setBordered(false);
        box.setMaxLength(64);
        box.setTextColor(0xFFFFFF);
        box.setHint(Component.literal(Lang.t("search.hint")).withStyle(ChatFormatting.GRAY));
        box.setValue(value);
        box.setResponder(text -> {
            cfcsearch$lastQuery = text;
            cfcsearch$cursor = -1;
            cfcsearch$cachedQuery = null;
        });
        box.setFocused(focused);
        cfcsearch$box = box;
        addWidget(box);
    }

    @Unique private int cfcsearch$statsBtnX() { return cfcsearch$box == null ? 0 : cfcsearch$box.getX() - 6 - STATS_BTN_W; }
    @Unique private int cfcsearch$statsBtnY() { return cfcsearch$box == null ? 0 : cfcsearch$box.getY() - 2; }
    @Unique private int cfcsearch$panelX() {
        if (cfcsearch$box == null) return 0;
        return Math.max(leftPos + 12, cfcsearch$box.getX() + cfcsearch$box.getWidth() + 3 - STATS_W);
    }
    @Unique private int cfcsearch$panelY() { return cfcsearch$box == null ? 0 : cfcsearch$box.getY() + 24; }

    // ── Sampling ──────────────────────────────────────────────────────────

    @Inject(method = "containerTick", at = @At("TAIL"))
    private void cfcsearch$sample(CallbackInfo ci) {
        if (++cfcsearch$tickCounter % SAMPLE_EVERY_TICKS != 0) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        AddressStats.sample(menu.controllerPos, componentWidgets, level.getGameTime());
        CsvRecorder.tick(menu.controllerPos, componentWidgets, level.getGameTime());
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cfcsearch$captureMouseX(int mouseX) {
        cfcsearch$mouseX = mouseX;
        return mouseX;
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int cfcsearch$hideMouseY(int mouseY) {
        cfcsearch$mouseY = mouseY;
        return cfcsearch$overOurAreas(cfcsearch$mouseX, mouseY) ? -10000 : mouseY;
    }

    @Unique
    private boolean cfcsearch$overOurAreas(double x, double y) {
        for (Rect2i r : cfcsearch$areas)
            if (x >= r.getX() && x < r.getX() + r.getWidth() && y >= r.getY() && y < r.getY() + r.getHeight())
                return true;
        return false;
    }

    /** Runs while the canvas pose (world coordinates, scissored to the canvas) is still active. */
    @Inject(method = "renderBoard",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", ordinal = 1))
    private void cfcsearch$renderHighlights(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                            boolean inOverlay, CallbackInfo ci) {
        String raw = cfcsearch$rawQuery();
        if (raw.isEmpty()) return;
        List<VirtualComponentPosition> matches = cfcsearch$matches(raw);
        VirtualComponentPosition current = cfcsearch$cursor >= 0 && cfcsearch$cursor < matches.size()
                ? matches.get(cfcsearch$cursor) : null;

        // Pulsing gold outline for matches, green on the one Enter jumped to.
        float pulse = 0.6f + 0.4f * (float) Math.sin(Util.getMillis() / 150.0);
        int gold = ((int) (0xFF * pulse) << 24) | 0xFFD700;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);   // above item icons and labels
        for (VirtualComponentPosition pos : componentWidgets.keySet()) {
            int x0 = pos.x() * CELL, y0 = pos.y() * CELL;
            if (!matches.contains(pos)) {
                graphics.fill(x0, y0, x0 + CELL, y0 + CELL, 0xB0101010);
                continue;
            }
            int color = pos.equals(current) ? 0xFF55FF55 : gold;
            graphics.fill(x0, y0, x0 + CELL, y0 + 1, color);
            graphics.fill(x0, y0 + CELL - 1, x0 + CELL, y0 + CELL, color);
            graphics.fill(x0, y0, x0 + 1, y0 + CELL, color);
            graphics.fill(x0 + CELL - 1, y0, x0 + CELL, y0 + CELL, color);
        }
        graphics.pose().popPose();
    }

    @Inject(method = "renderBoard", at = @At("TAIL"))
    private void cfcsearch$renderOverlay(GuiGraphics graphics, int boardMouseX, int boardMouseY, float partialTick,
                                         boolean inOverlay, CallbackInfo ci) {
        EditBox box = cfcsearch$box;
        cfcsearch$tipAreas.clear();
        cfcsearch$tipKeys.clear();
        if (box == null || inOverlay) return;
        int mouseX = cfcsearch$mouseX, mouseY = cfcsearch$mouseY;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);

        // Search field
        int x = box.getX(), y = box.getY();
        graphics.fill(x - 3, y - 2, x + box.getWidth() + 3, y + box.getHeight(),
                box.isFocused() ? 0xEE202020 : 0xAA202020);
        graphics.flush();   // otherwise the batched background is drawn over the text
        box.render(graphics, mouseX, mouseY, partialTick);
        cfcsearch$tip(x - 3, y - 2, box.getWidth() + 6, box.getHeight() + 2, "tip.search");

        String raw = cfcsearch$rawQuery();
        if (!raw.isEmpty()) {
            int count = cfcsearch$matches(raw).size();
            String label = count == 0 ? "0" : (cfcsearch$cursor >= 0 ? (cfcsearch$cursor + 1) + "/" : "") + count;
            int lx = x + box.getWidth() + 3 - font.width(label);
            int ly = y + box.getHeight() + 2;
            graphics.fill(lx - 3, ly - 1, x + box.getWidth() + 3, ly + font.lineHeight, 0xAA202020);
            graphics.flush();
            graphics.drawString(font, label, lx, ly, count == 0 ? 0xFF5555 : 0xFFD700, false);
        }

        // Stats toggle
        int bx = cfcsearch$statsBtnX(), by = cfcsearch$statsBtnY();
        boolean hover = mouseX >= bx && mouseX < bx + STATS_BTN_W && mouseY >= by && mouseY < by + 12;
        graphics.fill(bx, by, bx + STATS_BTN_W, by + 12,
                cfcsearch$statsOpen ? 0xEE3A5A2A : hover ? 0xEE404040 : 0xAA202020);
        graphics.flush();
        graphics.drawCenteredString(font, Lang.t("stats.button"), bx + STATS_BTN_W / 2, by + 2,
                cfcsearch$statsOpen ? 0x9EFF7F : 0xFFFFFF);
        cfcsearch$tip(bx, by, STATS_BTN_W, 12, "tip.stats_button");

        cfcsearch$clickables.clear();
        cfcsearch$panelRect = null;
        if (cfcsearch$statsOpen) cfcsearch$renderStats(graphics, mouseX, mouseY);
        graphics.pose().popPose();

        // Keep JEI's ingredient list and bookmarks out of the search bar and the stats panel.
        List<Rect2i> areas = new ArrayList<>();
        areas.add(new Rect2i(bx, by, box.getX() + box.getWidth() + 3 - bx, 26));
        if (cfcsearch$panelRect != null) areas.add(cfcsearch$panelRect);
        cfcsearch$areas = areas;
        ExtraAreas.set(this, areas);
    }

    @Unique
    private void cfcsearch$renderStats(GuiGraphics graphics, int mouseX, int mouseY) {
        var level = Minecraft.getInstance().level;
        long now = level == null ? 0 : level.getGameTime();
        List<AddressStats.Row> allRows = AddressStats.rows(menu.controllerPos, now);
        List<String> networks = new ArrayList<>();
        for (AddressStats.Row r : allRows) {
            String n = Network.of(r.address());
            if (!networks.contains(n)) networks.add(n);
        }
        networks.sort(Comparator.naturalOrder());
        if (cfcsearch$netFilter != null && !networks.contains(cfcsearch$netFilter)) cfcsearch$netFilter = null;
        String netFilter = networks.size() > 1 ? cfcsearch$netFilter : null;
        List<AddressStats.Row> rows = netFilter == null ? allRows
                : allRows.stream().filter(r -> Network.of(r.address()).equals(netFilter)).toList();
        boolean recording = CsvRecorder.isRecording(menu.controllerPos);

        int px = cfcsearch$panelX(), py = cfcsearch$panelY();
        int rowsTop = py + 26;
        int maxBottom = topPos + imageHeight - 9 - INVENTORY_RESERVE;
        int footerH = 3 * ROW_H + 8;
        int fit = Math.max(1, (maxBottom - footerH - rowsTop) / ROW_H - 1);
        cfcsearch$statsMaxScroll = Math.max(0, rows.size() - fit);
        cfcsearch$statsScroll = Math.min(cfcsearch$statsScroll, cfcsearch$statsMaxScroll);
        int first = cfcsearch$statsScroll;
        int shown = Math.min(rows.size() - first, fit);
        int footerY = rowsTop + Math.max(1, shown) * ROW_H + (rows.size() > fit ? ROW_H : 0) + 2;
        int height = footerY - py + 3 * ROW_H + 6;
        cfcsearch$panelRect = new Rect2i(px, py, STATS_W, height);
        graphics.fill(px, py, px + STATS_W, py + height, 0xE0181818);
        graphics.fill(px, py, px + STATS_W, py + 1, 0xFF7A6A4A);
        graphics.flush();

        int tx = px + 4;
        // "?" = how it works (hover only)
        int qx = px + STATS_W - 15, qy = py + 2;
        boolean qHover = mouseX >= qx && mouseX < qx + 11 && mouseY >= qy && mouseY < qy + 11;
        graphics.fill(qx, qy, qx + 11, qy + 11, qHover ? 0xEE6A5A3A : 0xEE303030);
        graphics.flush();
        graphics.drawCenteredString(font, "?", qx + 6, qy + 2, 0xFFD700);
        cfcsearch$tip(qx, qy, 11, 11, "tip.help");
        int titleEnd = qx;
        if (networks.size() > 1) {
            String netName = netFilter == null ? Lang.t("stats.network.all")
                    : netFilter.isEmpty() ? Lang.t("stats.network.default") : netFilter;
            String netLabel = cfcsearch$ellipsize(Lang.t("stats.network", netName), 110);
            int nw = font.width(netLabel) + 6;
            titleEnd = qx - 3 - nw;
            cfcsearch$chip(graphics, mouseX, mouseY, qx - 3 - nw, qy, nw, netLabel, () -> {
                int i = cfcsearch$netFilter == null ? -1 : networks.indexOf(cfcsearch$netFilter);
                cfcsearch$netFilter = i + 1 >= networks.size() ? null : networks.get(i + 1);
                cfcsearch$statsScroll = 0;
            });
            cfcsearch$tip(qx - 3 - nw, qy, nw, 11, "tip.stats_network");
        }
        cfcsearch$fit(graphics, Lang.t("stats.title"), tx, py + 3, titleEnd - tx - 4, 0xFFD700);
        int hy = py + 15;
        String[] cols = {"address", "status", "waiting", "max", "rate"};
        int[] colX = {0, 92, 150, 188, 222};
        int[] colW = {90, 56, 36, 32, 24};
        for (int c = 0; c < cols.length; c++) {
            String label = Lang.t("stats.col." + cols[c]);
            boolean over = mouseX >= tx + colX[c] && mouseX < tx + colX[c] + colW[c] && mouseY >= hy - 1 && mouseY < hy + 9;
            cfcsearch$fit(graphics, label, tx + colX[c], hy, colW[c] - 3, over ? 0xFFFFFF : 0x999999);
            cfcsearch$tip(tx + colX[c], hy - 1, colW[c], 10, "tip.col." + cols[c]);
        }

        if (rows.isEmpty())
            cfcsearch$fit(graphics, Lang.t("stats.empty"), tx, rowsTop, STATS_W - 8, 0x999999);
        for (int i = 0; i < shown; i++) {
            AddressStats.Row row = rows.get(first + i);
            int ry = rowsTop + i * ROW_H;
            if (mouseX >= px && mouseX < px + STATS_W && mouseY >= ry - 1 && mouseY < ry + ROW_H - 1) {
                graphics.fill(px + 1, ry - 1, px + STATS_W - 1, ry + ROW_H - 1, 0x40FFFFFF);
                graphics.flush();
            }
            cfcsearch$drawFit(graphics, netFilter == null ? row.address() : Network.local(row.address()), tx, ry, 86, 0xFFFFFF);
            cfcsearch$drawFit(graphics, row.status().label(), tx + 92, ry, 55, row.status().color);
            cfcsearch$tip(tx + 92, ry - 1, 56, ROW_H, "tip.status." + row.status().name().toLowerCase(java.util.Locale.ROOT));
            cfcsearch$drawFit(graphics, row.waiting() + "/" + row.gauges(), tx + 150, ry, 35, 0xDDDDDD);
            cfcsearch$drawFit(graphics, row.waiting() == 0 ? "-" : AddressStats.formatTicks(row.oldestWaitTicks()),
                    tx + 188, ry, 31, 0xDDDDDD);
            String rate = row.ratePerMinute() < 0 ? "…" : String.format("%.0f", row.ratePerMinute());
            cfcsearch$drawFit(graphics, rate, tx + 222, ry, 22, 0xDDDDDD);
            String address = row.address();
            cfcsearch$clickables.add(new Clickable(px, ry - 1, STATS_W, ROW_H, () -> {
                if (cfcsearch$box == null) return;
                cfcsearch$box.setValue(">" + address);
                cfcsearch$jump(1);
            }));
        }
        if (rows.size() > fit)
            cfcsearch$fit(graphics, Lang.t("stats.scroll", first + 1, first + shown, rows.size()), tx,
                    rowsTop + shown * ROW_H, STATS_W - 8, 0x999999);

        graphics.fill(px + 2, footerY - 2, px + STATS_W - 2, footerY - 1, 0x40FFFFFF);
        graphics.flush();
        cfcsearch$fit(graphics, Lang.t("stats.footer"), tx, footerY, STATS_W - 8, 0x777777);
        cfcsearch$tip(tx, footerY - 1, Math.min(STATS_W - 8, font.width(Lang.t("stats.footer"))), 10, "tip.footer");

        // CSV export: one snapshot every 10 s for the chosen duration
        int cy = footerY + ROW_H + 1;
        if (recording) {
            String status = CsvRecorder.status();
            cfcsearch$fit(graphics, status == null ? "" : status, tx, cy + 2, STATS_W - 60, 0xFF5555);
            cfcsearch$chip(graphics, mouseX, mouseY, px + STATS_W - 50, cy, 46, Lang.t("csv.stop"), CsvRecorder::stop);
            cfcsearch$tip(px + STATS_W - 50, cy, 46, 11, "tip.csv_stop");
        } else {
            String label = Lang.t("csv.label");
            int labelW = Math.min(font.width(label), 60);
            cfcsearch$fit(graphics, label, tx, cy + 2, labelW, 0xBBBBBB);
            int cx = tx + labelW + 4;
            cfcsearch$tip(tx, cy, cx - tx, 11, "tip.csv");
            String name = menu.controllerDisplayName().getString();
            long[] durations = {60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L};
            String[] labels = {"1 min", "5 min", "15 min", "30 min", "1 h"};
            int total = 3 * (labels.length - 1);
            for (String l : labels) total += font.width(l) + 6;
            boolean squeeze = total > px + STATS_W - 4 - cx;   // wide font: equal chips that fill the row
            int equal = (px + STATS_W - 4 - cx - 3 * (labels.length - 1)) / labels.length;
            for (int i = 0; i < durations.length; i++) {
                long duration = durations[i];
                int w = squeeze ? equal : font.width(labels[i]) + 6;
                cfcsearch$chip(graphics, mouseX, mouseY, cx, cy, w, labels[i],
                        () -> CsvRecorder.start(menu.controllerPos, name, duration));
                cfcsearch$tip(cx, cy, w, 11, "tip.csv");
                cx += w + 3;
            }
        }
        String open = Lang.t("csv.open_folder");
        String ai = Lang.t("ai.button");
        int openW = font.width(open) + 6, aiW = font.width(ai) + 6;
        int avail = STATS_W - 8 - 6;
        if (openW + aiW > avail) {   // long translation / wide font: share the row
            openW = Math.max(30, avail * openW / (openW + aiW));
            aiW = avail - openW;
        }
        cfcsearch$tip(tx, cy + ROW_H + 2, openW, 11, "tip.open_folder");
        cfcsearch$chip(graphics, mouseX, mouseY, tx, cy + ROW_H + 2, openW, open, () -> {
            try {
                java.nio.file.Path dir = CsvRecorder.folder();
                CsvRecorder.showFolder();
                Util.getPlatform().openFile(dir.toFile());
            } catch (java.io.IOException ignored) {
                // no usable folder yet: the player has just been asked to pick one
            }
        });
        // Everything collected → clipboard, then the AI chat opens (paste with Ctrl+V)
        cfcsearch$tip(px + STATS_W - 4 - aiW, cy + ROW_H + 2, aiW, 11, "tip.ai");
        cfcsearch$chip(graphics, mouseX, mouseY, px + STATS_W - 4 - aiW, cy + ROW_H + 2, aiW, ai, () -> {
            var lvl = Minecraft.getInstance().level;
            CsvRecorder.analyseWithAi(menu.controllerPos, menu.controllerDisplayName().getString(), componentWidgets,
                    lvl == null ? 0 : lvl.getGameTime());
        });
    }

    @Unique
    private void cfcsearch$chip(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, String label,
                                Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 11;
        graphics.fill(x, y, x + w, y + 11, hover ? 0xEE505050 : 0xEE303030);
        graphics.flush();
        cfcsearch$fit(graphics, label, x + 3, y + 2, w - 5, 0xFFFFFF);
        cfcsearch$clickables.add(new Clickable(x, y, w, 11, action));
    }

    @Unique
    private void cfcsearch$tip(int x, int y, int w, int h, String key) {
        cfcsearch$tipAreas.add(new Rect2i(x, y, w, h));
        cfcsearch$tipKeys.add(key);
    }

    /** Drawn last, above everything (including the inventory tab and JEI). */
    @Inject(method = "render", at = @At("TAIL"))
    private void cfcsearch$renderTips(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        int mx = cfcsearch$mouseX, my = cfcsearch$mouseY;
        for (int i = cfcsearch$tipAreas.size() - 1; i >= 0; i--) {
            Rect2i r = cfcsearch$tipAreas.get(i);
            if (mx < r.getX() || mx >= r.getX() + r.getWidth() || my < r.getY() || my >= r.getY() + r.getHeight()) continue;
            Tips.render(graphics, font, cfcsearch$tipKeys.get(i), mx, my);
            return;
        }
    }

    /** Longest text shown in a stats cell; longer texts are cut with "…". */
    @Unique private static final int CELL_MAX_CHARS = 16;
    /** Text is shrunk to fit its column, but never below this scale (then it is cut instead). */
    @Unique private static final float CELL_MIN_SCALE = 0.6f;

    /** Draws a stats cell: capped at 16 characters, then shrunk to fit the column width, vertically centred. */
    @Unique
    private void cfcsearch$drawFit(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        if (text.codePointCount(0, text.length()) > CELL_MAX_CHARS)
            text = text.substring(0, text.offsetByCodePoints(0, CELL_MAX_CHARS - 1)) + "…";
        cfcsearch$fit(graphics, text, x, y, maxWidth, color);
    }

    /**
     * Draws text within maxWidth whatever the font (GUI scale, Unicode font, long translations): shrunk to fit,
     * never below {@link #CELL_MIN_SCALE}, then cut with "…".
     */
    @Unique
    private void cfcsearch$fit(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) return;
        int width = font.width(text);
        if (width <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        float scale = (float) maxWidth / width;
        if (scale < CELL_MIN_SCALE) {
            scale = CELL_MIN_SCALE;
            text = cfcsearch$ellipsize(text, (int) (maxWidth / scale));
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y + (1 - scale) * (font.lineHeight - 1) / 2f, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    @Unique
    private String cfcsearch$ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String dots = "…";
        return font.plainSubstrByWidth(text, maxWidth - font.width(dots)) + dots;
    }

    // ── Input ─────────────────────────────────────────────────────────────

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cfcsearch$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        EditBox box = cfcsearch$box;
        if (box == null) return;

        int bx = cfcsearch$statsBtnX(), by = cfcsearch$statsBtnY();
        if (mouseX >= bx && mouseX < bx + STATS_BTN_W && mouseY >= by && mouseY < by + 12) {
            cfcsearch$statsOpen = !cfcsearch$statsOpen;
            box.setFocused(false);
            cir.setReturnValue(true);
            return;
        }

        if (cfcsearch$statsOpen && cfcsearch$panelRect != null) {
            Rect2i r = cfcsearch$panelRect;
            if (mouseX >= r.getX() && mouseX < r.getX() + r.getWidth()
                    && mouseY >= r.getY() && mouseY < r.getY() + r.getHeight()) {
                for (Clickable c : List.copyOf(cfcsearch$clickables))
                    if (c.contains(mouseX, mouseY)) {
                        c.action().run();
                        break;
                    }
                box.setFocused(false);
                cir.setReturnValue(true);
                return;
            }
        }

        boolean over = mouseX >= box.getX() - 3 && mouseX < box.getX() + box.getWidth() + 3
                && mouseY >= box.getY() - 2 && mouseY < box.getY() + box.getHeight();
        if (!over) {
            box.setFocused(false);
            return;
        }
        if (nameBox != null && nameBox.isFocused()) commitName();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) box.setValue("");
        box.setFocused(true);
        box.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void cfcsearch$mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!cfcsearch$overOurAreas(mouseX, mouseY)) return;
        if (cfcsearch$panelRect != null && mouseY >= cfcsearch$panelRect.getY())
            cfcsearch$statsScroll = Math.max(0, Math.min(cfcsearch$statsMaxScroll,
                    cfcsearch$statsScroll - (int) Math.signum(scrollY) * 2));
        cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cfcsearch$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        EditBox box = cfcsearch$box;
        if (box == null) return;
        if (!box.isFocused()) {
            boolean renaming = nameBox != null && nameBox.isFocused();
            if (!renaming && keyCode == GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
                box.setFocused(true);
                cir.setReturnValue(true);
            }
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            box.setFocused(false);
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            cfcsearch$jump(Screen.hasShiftDown() ? -1 : 1);
        } else {
            box.keyPressed(keyCode, scanCode, modifiers);
        }
        // Swallow every key while typing so movement keys don't pan and E doesn't close the screen.
        cir.setReturnValue(true);
    }

    // FactoryControllerScreen doesn't override charTyped, so the mixin adds the override.
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (cfcsearch$box != null && cfcsearch$box.isFocused())
            return cfcsearch$box.charTyped(codePoint, modifiers);
        return super.charTyped(codePoint, modifiers);
    }

    // ── Search logic ──────────────────────────────────────────────────────

    @Unique
    private void cfcsearch$jump(int direction) {
        String raw = cfcsearch$rawQuery();
        if (raw.isEmpty()) return;
        List<VirtualComponentPosition> matches = cfcsearch$matches(raw);
        if (matches.isEmpty()) return;
        cfcsearch$cursor = cfcsearch$cursor < 0 && direction < 0
                ? matches.size() - 1
                : Math.floorMod(cfcsearch$cursor + direction, matches.size());
        VirtualComponentPosition target = matches.get(cfcsearch$cursor);
        viewX = target.x() * CELL + CELL / 2.0;
        viewY = target.y() * CELL + CELL / 2.0;
        clampView();
    }

    @Unique
    private String cfcsearch$rawQuery() {
        return cfcsearch$box == null ? "" : cfcsearch$box.getValue().trim();
    }

    /** Matching positions sorted top-to-bottom, left-to-right; cached because tooltips are rebuilt to match. */
    @Unique
    private List<VirtualComponentPosition> cfcsearch$matches(String raw) {
        long now = Util.getMillis();
        if (raw.equals(cfcsearch$cachedQuery) && now - cfcsearch$cachedAt < CACHE_MS) return cfcsearch$matches;

        SearchQuery query = SearchQuery.parse(raw);
        List<VirtualComponentPosition> result = new ArrayList<>();
        if (!query.isEmpty())
            for (Map.Entry<VirtualComponentPosition, VirtualComponentWidget> entry : componentWidgets.entrySet())
                if (query.matches(entry.getValue(), menu)) result.add(entry.getKey());
        result.sort(Comparator.comparingInt(VirtualComponentPosition::y).thenComparingInt(VirtualComponentPosition::x));

        // Keep the cursor on the same component if the list shifted under it.
        if (cfcsearch$cursor >= 0 && cfcsearch$cursor < cfcsearch$matches.size())
            cfcsearch$cursor = result.indexOf(cfcsearch$matches.get(cfcsearch$cursor));
        cfcsearch$matches = result;
        cfcsearch$cachedQuery = raw;
        cfcsearch$cachedAt = now;
        return result;
    }
}
