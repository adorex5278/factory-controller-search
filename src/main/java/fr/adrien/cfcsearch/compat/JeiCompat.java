package fr.adrien.cfcsearch.compat;

import fr.adrien.cfcsearch.address.RecipeTypeDetector.Detected;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Only loaded when JEI is installed (callers check {@code ModList.isLoaded("jei")} first). */
public final class JeiCompat {

    @Nullable static IJeiRuntime runtime;

    private JeiCompat() {}

    /** Recipe categories with a recipe that outputs {@code stack}, or null while JEI isn't ready. */
    @Nullable
    public static List<Detected> categoriesProducing(ItemStack stack) {
        IJeiRuntime rt = runtime;
        if (rt == null) return null;
        IFocus<ItemStack> focus = rt.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack);
        return rt.getRecipeManager().createRecipeCategoryLookup()
                .limitFocus(List.of(focus))
                .get()
                .map(category -> new Detected(category.getRecipeType().getUid().toString(),
                        category.getTitle().getString()))
                .toList();
    }
}
