package fr.adrien.cfcsearch.address;

import fr.adrien.cfcsearch.Lang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The editable address book, stored in {@code config/cfc_search-adresses.json}:
 * <ul>
 *   <li>{@code types}: recipe category id (as JEI names it, e.g. {@code create:pressing}) → suggested address</li>
 *   <li>{@code carnet}: categories shown in the gauge settings screen, each a map display name → address</li>
 * </ul>
 * Recipe categories seen for the first time are added automatically ({@code traitement/<nom>} in French).
 */
public final class AddressBook {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "cfc_search-adresses.json";

    /** Recipe categories that aren't a way of producing an item (fuel lists, info pages, tag listings…). */
    private static final Set<String> EXCLUDED = Set.of(
            "minecraft:fuel", "minecraft:compostable", "minecraft:anvil", "minecraft:grindstone",
            "create:mystery_conversion");

    private static AddressBook instance;

    private final Map<String, String> types = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> carnet = new LinkedHashMap<>();
    private long loadedModified = -1;

    private AddressBook() {}

    /** The book, reloaded from disk when the file was edited since the last read. */
    public static synchronized AddressBook get() {
        if (instance == null) instance = new AddressBook();
        instance.reloadIfChanged();
        return instance;
    }

    public Map<String, Map<String, String>> categories() {
        return carnet;
    }

    public static boolean isExcluded(String typeId) {
        if (EXCLUDED.contains(typeId)) return true;
        String path = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        return typeId.startsWith("jei:") || path.contains("information") || path.contains("tag");
    }

    /** Address for a recipe category, creating {@code traitement/<slug>} (and a carnet entry) if new. */
    public synchronized String addressFor(String typeId, String title) {
        String known = types.get(typeId);
        if (known != null) return known;
        String address = prefix() + slug(title.isBlank() ? typeId.substring(typeId.indexOf(':') + 1) : title);
        types.put(typeId, address);
        Map<String, String> processing = carnet.computeIfAbsent(Lang.t("book.processing"), k -> new LinkedHashMap<>());
        if (!processing.containsValue(address)) processing.put(title.isBlank() ? address : title, address);
        save();
        return address;
    }

    /** Display name of an address in the carnet, or null if it isn't listed. */
    @Nullable
    public String nameOf(String address) {
        for (Map<String, String> entries : carnet.values())
            for (Map.Entry<String, String> e : entries.entrySet())
                if (e.getValue().equals(address)) return e.getKey();
        return null;
    }

    public static String slug(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "autre" : s;
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private void reloadIfChanged() {
        Path path = file();
        try {
            if (!Files.exists(path)) {
                if (loadedModified == -1) {
                    fillDefaults();
                    save();
                }
                return;
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (modified == loadedModified) return;
            read(Files.readString(path, StandardCharsets.UTF_8));
            loadedModified = modified;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[cfc_search] Impossible de lire {} : {}", path, e.toString());
            if (types.isEmpty()) fillDefaults();
            loadedModified = 0;
        }
    }

    private void read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        types.clear();
        carnet.clear();
        if (root.has("types"))
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("types").entrySet())
                types.put(e.getKey(), e.getValue().getAsString());
        if (root.has("carnet"))
            for (Map.Entry<String, JsonElement> category : root.getAsJsonObject("carnet").entrySet()) {
                Map<String, String> entries = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : category.getValue().getAsJsonObject().entrySet())
                    entries.put(e.getKey(), e.getValue().getAsString());
                carnet.put(category.getKey(), entries);
            }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("_aide", "types : identifiant de categorie de recette (comme JEI le nomme) -> adresse proposee. "
                + "carnet : categories affichees a cote du reglage d'une jauge (nom affiche -> adresse). "
                + "Modifie librement : le fichier est relu a la prochaine ouverture de l'ecran.");
        JsonObject typesJson = new JsonObject();
        types.forEach(typesJson::addProperty);
        root.add("types", typesJson);
        JsonObject carnetJson = new JsonObject();
        carnet.forEach((name, entries) -> {
            JsonObject obj = new JsonObject();
            entries.forEach(obj::addProperty);
            carnetJson.add(name, obj);
        });
        root.add("carnet", carnetJson);
        Path path = file();
        try {
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
            loadedModified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            LOGGER.warn("[cfc_search] Impossible d'ecrire {} : {}", path, e.toString());
        }
    }

    private void fillDefaults() {
        types.clear();
        carnet.clear();
        Map<String, String> processing = new LinkedHashMap<>();
        carnet.put(Lang.t("book.processing"), processing);
        carnet.put(Lang.t("book.storage"), new LinkedHashMap<>());
        // Create (JEI category ids, plus the recipe type ids used when JEI isn't there), then vanilla
        seed(processing, "pressing", "create:pressing");
        seed(processing, "crushing", "create:crushing");
        seed(processing, "milling", "create:milling");
        seed(processing, "mixing", "create:mixing", "create:automatic_shapeless");
        seed(processing, "compacting", "create:packing", "create:compacting", "create:automatic_packing");
        seed(processing, "sawing", "create:sawing", "create:cutting");
        seed(processing, "deploying", "create:deploying");
        seed(processing, "filling", "create:spout_filling", "create:filling");
        seed(processing, "draining", "create:draining", "create:emptying");
        seed(processing, "washing", "create:fan_washing", "create:splashing");
        seed(processing, "smoking_fan", "create:fan_smoking");
        seed(processing, "blasting_fan", "create:fan_blasting");
        seed(processing, "haunting", "create:fan_haunting", "create:haunting");
        seed(processing, "sequenced", "create:sequenced_assembly");
        seed(processing, "mechanical_crafting", "create:mechanical_crafting", "create:automatic_shaped");
        seed(processing, "application", "create:item_application");
        seed(processing, "polishing", "create:sandpaper_polishing");
        seed(processing, "brewing", "minecraft:brewing", "create:automatic_brewing");
        seed(processing, "crafting", "minecraft:crafting");
        seed(processing, "smelting", "minecraft:smelting");
        seed(processing, "blasting", "minecraft:blasting");
        seed(processing, "smoking", "minecraft:smoking");
        seed(processing, "campfire", "minecraft:campfire_cooking");
        seed(processing, "stonecutting", "minecraft:stonecutting");
        seed(processing, "smithing", "minecraft:smithing");
    }

    /**
     * Names and addresses are generated in the game language when the file is created ({@code traitement/pressage}
     * in French, {@code processing/pressing} in English…): players are expected to keep one language.
     */
    private static String prefix() {
        return slug(Lang.t("book.prefix")) + "/";
    }

    private void seed(Map<String, String> processing, String typeKey, String... typeIds) {
        String name = Lang.t("type." + typeKey);
        String address = prefix() + slug(name);
        processing.put(name, address);
        for (String id : typeIds) types.put(id, address);
    }
}
