package fr.adrien.cfcsearch.mixin;

import fr.adrien.cfcsearch.Lang;

import com.simibubi.create.content.logistics.AddressEditBox;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import fr.adrien.cfcsearch.address.AddressBook;
import fr.adrien.cfcsearch.address.BookLine;
import fr.adrien.cfcsearch.address.Network;
import fr.adrien.cfcsearch.address.RecipeTypeDetector;
import fr.adrien.cfcsearch.compat.ExtraAreas;
import fr.adrien.cfcsearch.stats.Tips;
import io.github.nbcss.createfactorycontroller.content.block.FactoryControllerMenu;
import io.github.nbcss.createfactorycontroller.content.component.gauge.VirtualGaugeBehaviour;
import io.github.nbcss.createfactorycontroller.content.gui.screen.recipe.ConfigureRecipeScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds an address book beside the gauge settings panel: first the processing types that can produce the
 * gauge's item (JEI categories, or vanilla recipe types without JEI) with their {@code traitement/...} address,
 * then the categories from {@code config/cfc_search-adresses.json}. Clicking an entry fills the address field.
 */
@Mixin(ConfigureRecipeScreen.class)
public abstract class ConfigureRecipeScreenMixin extends AbstractSimiContainerScreen<FactoryControllerMenu> {

    @Unique private static final int PANEL_W = 200, PANEL_H = 184;   // ConfigureRecipeScreen.PANEL_W / PANEL_H
    @Unique private static final int BOOK_W = 140;
    @Unique private static final int LINE_H = 10;
    /** Title row + network prefix row. */
    @Unique private static final int HEADER_H = 28;
    @Unique private static final int NET_Y = 15;
    /** Widest the "Network:" label may get (long translation / wide font), the selector keeps the rest. */
    @Unique private static final int NET_LABEL_MAX = 50;
    /** Stops above the factory gauge model drawn past the panel's bottom-right corner. */
    @Unique private static final int BOOK_H = PANEL_H - 44;

    @Shadow private AddressEditBox addressBox;
    @Shadow int panelX;
    @Shadow int panelY;

    @Shadow private VirtualGaugeBehaviour getBehaviour() { throw new AssertionError(); }

    @Unique private List<BookLine> cfcsearch$lines = List.of();
    @Unique private ItemStack cfcsearch$builtFor = null;
    @Unique private int cfcsearch$scroll = 0;
    /** Last address put in the clipboard, for the "copied" feedback. */
    @Unique @Nullable private String cfcsearch$copied;
    @Unique private long cfcsearch$copiedAt;

    /** Left edge of the "Copy" chip drawn on the hovered book line. */
    @Unique
    private int cfcsearch$copyX() {
        int w = Math.max(font.width(Lang.t("book.copy")), font.width(Lang.t("book.copied"))) + 6;
        return cfcsearch$bookX() + BOOK_W - 2 - w;
    }
    /** Network prefix of this Factory Controller: book addresses become prefix/type/name. */
    @Unique @Nullable private EditBox cfcsearch$net;

    private ConfigureRecipeScreenMixin(FactoryControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // ── Layout ────────────────────────────────────────────────────────────

    @Unique
    private int cfcsearch$bookX() {
        int right = panelX + PANEL_W + 6;
        return right + BOOK_W <= width - 4 ? right : Math.max(4, panelX - 6 - BOOK_W);
    }

    @Unique
    private int cfcsearch$visibleLines() {
        return (BOOK_H - HEADER_H - 4) / LINE_H;
    }

    @Unique
    private void cfcsearch$rebuildIfNeeded() {
        VirtualGaugeBehaviour gauge = getBehaviour();
        ItemStack item = gauge == null ? ItemStack.EMPTY : gauge.filter;
        if (cfcsearch$builtFor != null && ItemStack.isSameItemSameComponents(cfcsearch$builtFor, item)) return;
        cfcsearch$builtFor = item.copy();
        cfcsearch$scroll = 0;

        AddressBook book = AddressBook.get();
        List<BookLine> lines = new ArrayList<>();
        lines.add(new BookLine(Lang.t("book.suggested"), null, true, 0xFFD700));
        List<RecipeTypeDetector.Detected> detected = RecipeTypeDetector.producing(item);
        if (item.isEmpty()) {
            lines.add(new BookLine(Lang.t("book.no_item"), null, false, 0x888888));
        } else if (detected.isEmpty()) {
            lines.add(new BookLine(Lang.t("book.no_recipe"), null, false, 0x888888));
        }
        for (RecipeTypeDetector.Detected d : detected) {
            String address = book.addressFor(d.typeId(), d.title());
            String name = book.nameOf(address);
            lines.add(new BookLine(name != null ? name : (d.title().isBlank() ? d.typeId() : d.title()), address, false, 0x9EFF7F));
        }
        for (Map.Entry<String, Map<String, String>> category : book.categories().entrySet()) {
            if (category.getValue().isEmpty()) continue;
            lines.add(new BookLine(category.getKey(), null, true, 0xFFD700));
            for (Map.Entry<String, String> entry : category.getValue().entrySet())
                lines.add(new BookLine(entry.getKey(), entry.getValue(), false, 0xFFFFFF));
        }
        cfcsearch$lines = lines;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void cfcsearch$init(CallbackInfo ci) {
        String value = cfcsearch$net != null ? cfcsearch$net.getValue() : "";
        boolean focused = cfcsearch$net != null && cfcsearch$net.isFocused();
        EditBox box = new EditBox(font, 0, 0, 60, 9, Component.literal("Network"));
        box.setBordered(false);
        box.setMaxLength(40);
        box.setTextColor(0x9EFF7F);
        box.setHint(Component.literal(Lang.t("book.network_new_hint")).withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(value);
        box.setFocused(focused);
        cfcsearch$net = box;
    }

    /** Network selected for this controller ("" = none, the default). */
    @Unique
    private String cfcsearch$prefix() {
        return Network.prefixFor(menu.controllerPos);
    }

    /** What clicking a book line writes in the address field. */
    @Unique
    private String cfcsearch$full(String address) {
        return Network.apply(cfcsearch$prefix(), address);
    }

    /** "" (none) followed by every known network. */
    @Unique
    private List<String> cfcsearch$choices() {
        List<String> choices = new ArrayList<>();
        choices.add("");
        choices.addAll(Network.known());
        return choices;
    }

    /** Network row: [label] [◀ selector ▶ | new-name field] [−] [+]; returns {selX, selW, minusX (or -1), plusX}. */
    @Unique
    private int[] cfcsearch$netLayout() {
        int x = cfcsearch$bookX();
        int plusX = x + BOOK_W - 4 - 11;
        boolean creating = cfcsearch$net != null && cfcsearch$net.isFocused();
        int minusX = !creating && !cfcsearch$prefix().isEmpty() ? plusX - 13 : -1;
        int selX = x + 4 + Math.min(NET_LABEL_MAX, font.width(Lang.t("book.network"))) + 4;
        int selEnd = (minusX >= 0 ? minusX : plusX) - 2;
        return new int[]{selX, selEnd - selX, minusX, plusX};
    }

    @Unique
    private void cfcsearch$smallChip(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, String label, int color) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 11;
        graphics.fill(x, y, x + w, y + 11, hover ? 0xEE505050 : 0xEE303030);
        graphics.flush();
        graphics.drawCenteredString(font, label, x + w / 2 + 1, y + 2, color);
    }

    @Unique
    private void cfcsearch$confirmNew() {
        EditBox net = cfcsearch$net;
        if (net == null) return;
        String name = Network.clean(net.getValue());
        if (!name.isEmpty()) {
            Network.create(name);
            Network.setPrefix(menu.controllerPos, name);
        }
        net.setValue("");
        net.setFocused(false);
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void cfcsearch$renderBook(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        cfcsearch$rebuildIfNeeded();
        int x = cfcsearch$bookX(), y = panelY;
        // Keep JEI's ingredient list and bookmarks out of the address book.
        ExtraAreas.set(this, List.of(new Rect2i(x, y, BOOK_W, BOOK_H)));
        graphics.fill(x, y, x + BOOK_W, y + BOOK_H, 0xE0181818);
        graphics.fill(x, y, x + BOOK_W, y + 1, 0xFF7A6A4A);
        graphics.flush();
        graphics.drawString(font, cfcsearch$ellipsize(Lang.t("book.title"), BOOK_W - 32), x + 4, y + 4, 0xFFD700, false);
        int qx = x + BOOK_W - 24, qy = y + 2;
        boolean qHover = mouseX >= qx && mouseX < qx + 11 && mouseY >= qy && mouseY < qy + 11;
        graphics.fill(qx, qy, qx + 11, qy + 11, qHover ? 0xEE6A5A3A : 0xEE303030);
        graphics.flush();
        graphics.drawCenteredString(font, "?", qx + 6, qy + 2, 0xFFD700);
        EditBox net = cfcsearch$net;
        if (net != null) {
            int[] l = cfcsearch$netLayout();
            int ry = y + NET_Y - 1;
            graphics.drawString(font, cfcsearch$ellipsize(Lang.t("book.network"), NET_LABEL_MAX), x + 4, ry + 2, 0xBBBBBB, false);
            if (net.isFocused()) {
                // typing the name of a new network
                net.setX(l[0] + 2);
                net.setY(ry + 2);
                net.setWidth(l[1] - 4);
                graphics.fill(l[0], ry, l[0] + l[1], ry + 11, 0xEE303030);
                graphics.flush();
                net.render(graphics, mouseX, mouseY, partialTick);
            } else {
                String prefix = cfcsearch$prefix();
                String name = prefix.isEmpty() ? Lang.t("book.network_none") : prefix;
                boolean hover = mouseX >= l[0] && mouseX < l[0] + l[1] && mouseY >= ry && mouseY < ry + 11;
                graphics.fill(l[0], ry, l[0] + l[1], ry + 11, hover ? 0xEE505050 : 0xEE303030);
                graphics.flush();
                graphics.drawString(font, "◀", l[0] + 2, ry + 2, 0x999999, false);
                graphics.drawString(font, "▶", l[0] + l[1] - 2 - font.width("▶"), ry + 2, 0x999999, false);
                String shown = cfcsearch$ellipsize(name, l[1] - 4 - 2 * font.width("◀ "));
                graphics.drawCenteredString(font, shown, l[0] + l[1] / 2, ry + 2, prefix.isEmpty() ? 0x999999 : 0x9EFF7F);
                if (l[2] >= 0) cfcsearch$smallChip(graphics, mouseX, mouseY, l[2], ry, 11, "-", 0xFF7777);
            }
            cfcsearch$smallChip(graphics, mouseX, mouseY, l[3], ry, 11, "+", 0x9EFF7F);
        }

        String current = addressBox == null ? "" : addressBox.getValue();
        int top = y + HEADER_H;
        int visible = cfcsearch$visibleLines();
        graphics.enableScissor(x, top, x + BOOK_W, y + BOOK_H - 2);
        for (int i = 0; i < visible && i + cfcsearch$scroll < cfcsearch$lines.size(); i++) {
            BookLine line = cfcsearch$lines.get(i + cfcsearch$scroll);
            int ly = top + i * LINE_H;
            boolean hover = line.address() != null && mouseX >= x && mouseX < x + BOOK_W
                    && mouseY >= ly && mouseY < ly + LINE_H;
            boolean selected = line.address() != null && cfcsearch$full(line.address()).equals(current);
            if (hover || selected) {
                graphics.fill(x + 1, ly, x + BOOK_W - 1, ly + LINE_H, selected ? 0x5055AA55 : 0x40FFFFFF);
                graphics.flush();
            }
            String text = line.header() ? line.label() : "  " + line.label();
            int textW = BOOK_W - 8;
            if (hover) {
                // "Copy" chip: puts the full address in the clipboard, to paste on the Frogport
                boolean justCopied = cfcsearch$full(line.address()).equals(cfcsearch$copied)
                        && Util.getMillis() - cfcsearch$copiedAt < 1500;
                String label = Lang.t(justCopied ? "book.copied" : "book.copy");
                int cx = cfcsearch$copyX();
                boolean over = mouseX >= cx;
                graphics.fill(cx, ly, x + BOOK_W - 2, ly + LINE_H, over ? 0xEE6A5A3A : 0xEE303030);
                graphics.flush();
                graphics.drawString(font, label, cx + 3, ly + 1, justCopied ? 0x9EFF7F : 0xFFD700, false);
                textW = cx - x - 6;
            }
            graphics.drawString(font, cfcsearch$ellipsize(text, textW), x + 4, ly + 1, line.color(), false);
        }
        graphics.disableScissor();
        if (cfcsearch$lines.size() > visible) {
            String more = (cfcsearch$scroll + visible < cfcsearch$lines.size()) ? "▼" : "▲";
            graphics.drawString(font, more, x + BOOK_W - 10, y + 4, 0x999999, false);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void cfcsearch$renderTooltip(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        int x = cfcsearch$bookX();
        if (mouseX >= x && mouseX < x + BOOK_W && mouseY >= panelY && mouseY < panelY + HEADER_H) {
            String key = "tip.book";
            if (mouseY >= panelY + NET_Y - 1) {
                int[] l = cfcsearch$netLayout();
                key = mouseX >= l[3] ? "tip.network_add" : l[2] >= 0 && mouseX >= l[2] ? "tip.network_remove" : "tip.network";
            }
            Tips.render(graphics, font, key, mouseX, mouseY);
            return;
        }
        BookLine line = cfcsearch$lineAt(mouseX, mouseY);
        if (line != null && line.address() != null && mouseX >= cfcsearch$copyX())
            Tips.render(graphics, font, "tip.book_copy", mouseX, mouseY);
        else if (line != null && line.address() != null)
            graphics.renderTooltip(font, Component.literal(cfcsearch$full(line.address())), mouseX, mouseY);
        else if (line != null && line.header() && line.label().equals(Lang.t("book.suggested")))
            Tips.render(graphics, font, "tip.book_suggested", mouseX, mouseY);
    }

    @Unique
    private String cfcsearch$ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }

    // ── Input ─────────────────────────────────────────────────────────────

    @Unique
    @Nullable
    private BookLine cfcsearch$lineAt(double mouseX, double mouseY) {
        int x = cfcsearch$bookX();
        int top = panelY + HEADER_H;
        if (mouseX < x || mouseX >= x + BOOK_W || mouseY < top || mouseY >= panelY + BOOK_H - 2) return null;
        int index = (int) ((mouseY - top) / LINE_H);
        if (index >= cfcsearch$visibleLines()) return null;
        index += cfcsearch$scroll;
        return index >= 0 && index < cfcsearch$lines.size() ? cfcsearch$lines.get(index) : null;
    }

    @Unique
    private boolean cfcsearch$overBook(double mouseX, double mouseY) {
        int x = cfcsearch$bookX();
        return mouseX >= x && mouseX < x + BOOK_W && mouseY >= panelY && mouseY < panelY + BOOK_H;
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cfcsearch$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        EditBox net = cfcsearch$net;
        int ry = panelY + NET_Y - 1;
        boolean onRow = net != null && mouseY >= ry && mouseY < ry + 11 && cfcsearch$overBook(mouseX, mouseY);
        if (net != null && onRow) {
            int[] l = cfcsearch$netLayout();
            if (mouseX >= l[3] && mouseX < l[3] + 11) {                       // "+": new network / confirm
                if (net.isFocused()) cfcsearch$confirmNew();
                else {
                    net.setValue("");
                    net.setFocused(true);
                    if (addressBox != null) addressBox.setFocused(false);
                }
            } else if (net.isFocused()) {
                if (mouseX < l[0] || mouseX >= l[0] + l[1]) net.setFocused(false);
            } else if (l[2] >= 0 && mouseX >= l[2] && mouseX < l[2] + 11) {   // "−": forget this network
                Network.remove(cfcsearch$prefix());
            } else if (mouseX >= l[0] && mouseX < l[0] + l[1]) {              // selector: left = next, right = previous
                List<String> choices = cfcsearch$choices();
                int i = Math.max(0, choices.indexOf(cfcsearch$prefix()));
                i = (i + (button == 1 ? choices.size() - 1 : 1)) % choices.size();
                Network.setPrefix(menu.controllerPos, choices.get(i));
            }
            cir.setReturnValue(true);
            return;
        }
        if (net != null && net.isFocused()) net.setFocused(false);
        if (!cfcsearch$overBook(mouseX, mouseY)) return;
        BookLine line = cfcsearch$lineAt(mouseX, mouseY);
        if (line != null && line.address() != null && (button == 1 || mouseX >= cfcsearch$copyX())) {
            // copy chip or right-click: address to the clipboard (for the Frogport)
            cfcsearch$copied = cfcsearch$full(line.address());
            cfcsearch$copiedAt = Util.getMillis();
            Minecraft.getInstance().keyboardHandler.setClipboard(cfcsearch$copied);
        } else if (line != null && line.address() != null && addressBox != null) {
            addressBox.setValue(cfcsearch$full(line.address()));
            addressBox.moveCursorToStart(false);
        }
        cir.setReturnValue(true);
    }

    /** While the network field is focused it gets every key (so E doesn't close the screen). */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cfcsearch$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        EditBox net = cfcsearch$net;
        if (net == null || !net.isFocused()) return;
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
            cfcsearch$confirmNew();
        else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            net.setValue("");
            net.setFocused(false);
        } else
            net.keyPressed(keyCode, scanCode, modifiers);
        cir.setReturnValue(true);
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void cfcsearch$charTyped(char c, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        EditBox net = cfcsearch$net;
        if (net == null || !net.isFocused()) return;
        if (c != '/' || !net.getValue().isEmpty()) net.charTyped(c, modifiers);
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void cfcsearch$mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!cfcsearch$overBook(mouseX, mouseY)) return;
        int max = Math.max(0, cfcsearch$lines.size() - cfcsearch$visibleLines());
        cfcsearch$scroll = Math.max(0, Math.min(max, cfcsearch$scroll - (int) Math.signum(scrollY) * 3));
        cir.setReturnValue(true);
    }
}
