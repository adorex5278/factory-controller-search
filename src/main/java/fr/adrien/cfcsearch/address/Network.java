package fr.adrien.cfcsearch.address;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.Map;

/**
 * Several logistics networks, each with its own processing machines, are told apart by a prefix in front of the
 * address: {@code network/type/name} (e.g. {@code mine/traitement/smelting}). The default network has no prefix
 * ({@code traitement/smelting}). The prefix chosen for each Factory Controller is kept in
 * {@code config/cfc_search-reseaux.json}.
 */
public final class Network {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "cfc_search-reseaux.json";
    private static Map<String, String> prefixes;
    /** Networks the player created with "+" (listed in the selector even before a controller uses them). */
    private static final List<String> created = new ArrayList<>();

    private Network() {}

    /** Network part of an address: everything before {@code type/name}, "" for the default network. */
    public static String of(String address) {
        if (address == null) return "";
        String a = address.trim();
        int last = a.lastIndexOf('/');
        if (last <= 0) return "";
        int type = a.lastIndexOf('/', last - 1);
        return type <= 0 ? "" : a.substring(0, type);
    }

    /** The address without its network prefix ({@code type/name}). */
    public static String local(String address) {
        if (address == null) return "";
        String network = of(address);
        String a = address.trim();
        return network.isEmpty() ? a : a.substring(network.length() + 1);
    }

    /** Puts a book address (type/name) in the given network; an address that already has one is left alone. */
    public static String apply(String prefix, String address) {
        String p = clean(prefix);
        if (p.isEmpty() || address == null || address.isEmpty() || !of(address).isEmpty()) return address;
        return p + "/" + address;
    }

    public static String clean(String prefix) {
        return prefix == null ? "" : prefix.trim().replaceAll("^/+|/+$", "");
    }

    // ── Prefix per controller ─────────────────────────────────────────────

    public static synchronized String prefixFor(BlockPos controller) {
        return prefixes().getOrDefault(key(controller), "");
    }

    public static synchronized void setPrefix(BlockPos controller, String prefix) {
        String p = clean(prefix);
        String old = prefixes().put(key(controller), p);
        if (p.isEmpty()) prefixes().remove(key(controller));
        if (!p.equals(old == null ? "" : old)) save();
    }

    /** Every known network, sorted: the ones created with "+" and the ones a controller uses. */
    public static synchronized List<String> known() {
        prefixes();
        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(created);
        all.addAll(prefixes.values());
        all.remove("");
        return new ArrayList<>(all);
    }

    public static synchronized void create(String name) {
        String n = clean(name);
        prefixes();
        if (n.isEmpty() || created.stream().anyMatch(n::equalsIgnoreCase)) return;
        created.add(n);
        save();
    }

    /** Forgets a network: removed from the list and from every controller that had it selected. */
    public static synchronized void remove(String name) {
        prefixes();
        created.removeIf(name::equalsIgnoreCase);
        prefixes.values().removeIf(name::equalsIgnoreCase);
        save();
    }

    private static String key(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        String dim = level == null ? "?" : level.dimension().location().toString();
        return dim + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private static Map<String, String> prefixes() {
        if (prefixes != null) return prefixes;
        prefixes = new LinkedHashMap<>();
        Path path = file();
        if (Files.exists(path)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject map = root.has("controleurs") ? root.getAsJsonObject("controleurs") : new JsonObject();
                for (Map.Entry<String, JsonElement> e : map.entrySet()) prefixes.put(e.getKey(), e.getValue().getAsString());
                if (root.has("reseaux"))
                    for (JsonElement e : root.getAsJsonArray("reseaux")) created.add(e.getAsString());
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[cfc_search] Impossible de lire {} : {}", path, e.toString());
            }
        }
        return prefixes;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("_aide", "reseaux : reseaux crees avec +. controleurs : reseau choisi par Factory Controller "
                + "(dimension x,y,z -> prefixe). Les adresses proposees deviennent prefixe/type/nom.");
        JsonArray list = new JsonArray();
        created.forEach(list::add);
        root.add("reseaux", list);
        JsonObject map = new JsonObject();
        prefixes.forEach(map::addProperty);
        root.add("controleurs", map);
        try {
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[cfc_search] Impossible d'ecrire {} : {}", file(), e.toString());
        }
    }
}
