package fr.adrien.cfcsearch.stats;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * {@code config/cfc_search.properties}: which AI chat the "Analyse with AI" button opens ({@code ai_url}) and,
 * optionally, where exports are written ({@code export_dir}, empty = the game folder).
 */
public final class AiConfig {

    private static final String DEFAULT_URL = "https://claude.ai/new";
    private static final String COMMENT = "Factory Controller Search\n"
            + "ai_url: page opened by the AI button (e.g. https://claude.ai/new, https://chatgpt.com, "
            + "https://chat.mistral.ai, https://gemini.google.com)\n"
            + "export_dir: folder for CSV / Excel / AI exports (empty = <game folder>/cfc_search_exports)";

    private AiConfig() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("cfc_search.properties");
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = file();
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(r);
                }
            }
        } catch (IOException ignored) {
            // defaults below
        }
        boolean missing = !props.containsKey("ai_url") || !props.containsKey("export_dir");
        props.putIfAbsent("ai_url", DEFAULT_URL);
        props.putIfAbsent("export_dir", "");
        if (missing) store(props);
        return props;
    }

    private static void store(Properties props) {
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            props.store(w, COMMENT);
        } catch (IOException ignored) {
            // read-only config folder: the values simply aren't remembered
        }
    }

    public static String url() {
        String url = load().getProperty("ai_url", DEFAULT_URL).trim();
        return url.startsWith("https://") ? url : DEFAULT_URL;
    }

    /** Export folder chosen by the player, or "" for the default. */
    public static String exportDir() {
        return load().getProperty("export_dir", "").trim();
    }

    public static void setExportDir(String dir) {
        Properties props = load();
        props.setProperty("export_dir", dir);
        store(props);
    }
}
