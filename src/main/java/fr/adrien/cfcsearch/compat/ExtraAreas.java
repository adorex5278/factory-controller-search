package fr.adrien.cfcsearch.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Screen areas covered by this addon's panels, so JEI keeps its ingredient list and bookmarks out of them.
 * Updated by the screens every frame; no JEI classes here so it loads fine without JEI.
 */
public final class ExtraAreas {

    @Nullable private static Screen owner;
    private static List<Rect2i> areas = List.of();

    private ExtraAreas() {}

    public static void set(Screen screen, List<Rect2i> rects) {
        owner = screen;
        areas = List.copyOf(rects);
    }

    public static List<Rect2i> current() {
        Screen screen = Minecraft.getInstance().screen;
        return screen != null && screen == owner ? areas : List.of();
    }
}
