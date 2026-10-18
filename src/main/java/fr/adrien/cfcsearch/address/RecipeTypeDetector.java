package fr.adrien.cfcsearch.address;

import fr.adrien.cfcsearch.compat.JeiCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds which kinds of processing (recipe categories) can produce an item. */
public final class RecipeTypeDetector {

    /** A recipe category producing the item: its id (e.g. {@code create:pressing}) and display name. */
    public record Detected(String typeId, String title) {}

    private RecipeTypeDetector() {}

    public static List<Detected> producing(ItemStack stack) {
        if (stack.isEmpty()) return List.of();
        List<Detected> found = null;
        // JEI knows every category, including other mods' machines; vanilla recipes are the fallback.
        if (ModList.get().isLoaded("jei")) {
            try {
                found = JeiCompat.categoriesProducing(stack);
            } catch (RuntimeException | LinkageError e) {
                found = null;
            }
        }
        if (found == null) found = fromRecipeManager(stack);

        Map<String, Detected> unique = new LinkedHashMap<>();
        for (Detected d : found)
            if (!AddressBook.isExcluded(d.typeId())) unique.putIfAbsent(d.typeId(), d);
        return new ArrayList<>(unique.values());
    }

    private static List<Detected> fromRecipeManager(ItemStack stack) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        RegistryAccess registries = level.registryAccess();
        List<Detected> result = new ArrayList<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            ItemStack output;
            try {
                output = holder.value().getResultItem(registries);
            } catch (RuntimeException e) {
                continue;
            }
            if (output.isEmpty() || !ItemStack.isSameItem(output, stack)) continue;
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            if (type != null) result.add(new Detected(type.toString(), ""));
        }
        return result;
    }
}
