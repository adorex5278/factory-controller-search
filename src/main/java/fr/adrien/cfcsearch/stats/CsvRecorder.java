package fr.adrien.cfcsearch.stats;

import fr.adrien.cfcsearch.Lang;
import fr.adrien.cfcsearch.address.Network;

import com.mojang.logging.LogUtils;
import fr.adrien.cfcsearch.address.AddressBook;
import io.github.nbcss.createfactorycontroller.content.component.VirtualComponentPosition;
import io.github.nbcss.createfactorycontroller.content.component.gauge.VirtualGaugeBehaviour;
import io.github.nbcss.createfactorycontroller.content.gui.widget.VirtualComponentWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Records what the Factory Controller shows into two CSV files, one snapshot every {@link #INTERVAL_MS}.
 * Headers and values follow the game language, separators the computer's regional settings (UTF-8 with BOM):
 * <ul>
 *   <li>{@code *_jauges.csv}: one line per gauge per snapshot;</li>
 *   <li>{@code *_familles.csv}: one line per destination address per snapshot, with its family
 *       (the part before the first '/').</li>
 * </ul>
 * Snapshots are only taken while that controller's screen is open (that's when the data is synced);
 * lines are appended as they're taken so nothing is lost if the game stops.
 */
public final class CsvRecorder {

    public static final long INTERVAL_MS = 10_000;
    public static final String FOLDER = "cfc_search_exports";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String BOM = "\uFEFF";

    /** Field separator and decimal mark Excel expects on this computer: ';' and ',' in French, ',' and '.' in English. */
    private static char separator = ';';
    private static char decimal = ',';

    private static final class Recording {
        BlockPos controller;
        String controllerName;
        long startMs;
        long endMs;
        long nextSampleMs;
        int samples;
        Path gaugesFile;
        Path familiesFile;
        Path reportFile;
        Report report;
    }

    @Nullable private static Recording active;
    /** Last finished recording, kept for "Analyse with AI" (controller → report). */
    @Nullable private static BlockPos lastController;
    @Nullable private static Report lastReport;

    private CsvRecorder() {}

    public static boolean isRecording() {
        return active != null;
    }

    public static boolean isRecording(BlockPos controller) {
        return active != null && active.controller.equals(controller);
    }

    private static Path folder;
    private static volatile boolean asking;

    /**
     * Where exports go, first one that can be written:
     * <ol>
     *   <li>{@code export_dir} from {@code config/cfc_search.properties} (set by the player, or by the dialog below);</li>
     *   <li>{@code <game directory>/cfc_search_exports}: the game directory is whatever the launcher passed as
     *       {@code --gameDir} (instance folder for Modrinth / Prism / CurseForge / ATLauncher…, {@code .minecraft} or
     *       the profile's folder for the vanilla launcher), made absolute as some launchers pass a relative one;</li>
     *   <li>{@code <home>/cfc_search_exports};</li>
     *   <li>last resort: a folder picker asks the player, the choice is saved as {@code export_dir}.</li>
     * </ol>
     * @throws IOException while waiting for the player's choice
     */
    public static synchronized Path folder() throws IOException {
        if (folder != null) return folder;
        List<Path> candidates = new ArrayList<>();
        String chosen = AiConfig.exportDir();
        if (!chosen.isEmpty()) {
            try {
                candidates.add(Path.of(chosen).toAbsolutePath().normalize());
            } catch (RuntimeException e) {
                LOGGER.warn("[cfc_search] export_dir invalide : {}", chosen);
            }
        }
        try {
            candidates.add(Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve(FOLDER));
        } catch (RuntimeException ignored) {
            // no game directory: use the fallbacks
        }
        try {
            candidates.add(Path.of(System.getProperty("user.home", "")).toAbsolutePath().resolve(FOLDER));
        } catch (RuntimeException ignored) {
            // no home folder
        }
        for (Path dir : candidates) {
            if (writable(dir)) {
                if (dir != candidates.getFirst()) LOGGER.warn("[cfc_search] Exports dans {}", dir);
                return folder = dir;
            }
        }
        askPlayer();
        throw new IOException(Lang.t("csv.folder_wait"));
    }

    private static boolean writable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".write", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[cfc_search] Dossier d'export {} inutilisable : {}", dir, e.toString());
            return false;
        }
    }

    /** Opens a folder picker (system dialog); the chosen folder is remembered in the config. */
    private static void askPlayer() {
        if (asking) return;
        asking = true;
        String title = Lang.t("csv.folder_title");
        String home = System.getProperty("user.home", "");
        chat(Component.literal(Lang.t("csv.folder_ask")).withStyle(ChatFormatting.GOLD));
        Runnable dialog = () -> {
            String picked = null;
            try {
                picked = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog(title, home);
            } catch (Throwable e) {
                LOGGER.warn("[cfc_search] Selecteur de dossier indisponible : {}", e.toString());
            }
            String result = picked;
            Minecraft.getInstance().execute(() -> folderPicked(result));
        };
        // macOS only allows windows on the main thread; elsewhere the dialog must not freeze the game
        if (Util.getPlatform() == Util.OS.OSX) dialog.run();
        else {
            Thread thread = new Thread(dialog, "cfc_search folder picker");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void folderPicked(@Nullable String picked) {
        asking = false;
        Path dir = null;
        try {
            if (picked != null && !picked.isBlank()) dir = Path.of(picked).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            // invalid path: handled below
        }
        if (dir != null && writable(dir)) {
            synchronized (CsvRecorder.class) {
                folder = dir;
            }
            AiConfig.setExportDir(dir.toString());
            chat(Component.literal(Lang.t("csv.folder_set")).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(dir.toString()).withStyle(ChatFormatting.AQUA)));
        } else {
            chat(Component.literal(Lang.t("csv.folder_none")).withStyle(ChatFormatting.RED));
        }
    }

    /** File-name part from the controller name: ASCII only (slug) and short enough for Windows' 260-char paths. */
    private static String baseName(String controllerName) {
        String slug = AddressBook.slug(controllerName.isBlank() ? "controleur" : controllerName);
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
    }

    /** Chat line with the export folder: click = copy its path (when the system can't open folders itself). */
    public static void showFolder() {
        Path dir;
        try {
            dir = folder();
        } catch (IOException e) {
            return;   // the player is being asked
        }
        chat(Component.literal(Lang.t("csv.folder")).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(dir.toString()).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, dir.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(Lang.t("csv.copy_path")))))));
    }

    public static void start(BlockPos controller, String controllerName, long durationMs) {
        if (active != null) stop();
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            String base = baseName(controllerName) + "_" + LocalDateTime.now().format(FILE_STAMP);
            // Excel reads CSV with the computer's regional settings, not the game language.
            java.util.Locale locale = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT);
            decimal = java.text.DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
            separator = decimal == ',' ? ';' : ',';
            Recording r = new Recording();
            r.controller = controller.immutable();
            r.controllerName = controllerName;
            r.startMs = Util.getMillis();
            r.endMs = r.startMs + durationMs;
            r.nextSampleMs = r.startMs;
            r.gaugesFile = dir.resolve(base + "_" + Lang.t("csv.file.gauges") + ".csv");
            r.familiesFile = dir.resolve(base + "_" + Lang.t("csv.file.families") + ".csv");
            r.reportFile = dir.resolve(base + "_" + Lang.t("csv.file.report") + ".xlsx");
            r.report = new Report(controllerName);
            Files.writeString(r.gaugesFile, BOM + header("time", "second", "controller", "x", "y",
                    "item_id", "item", "mod", "address", "network", "family", "type", "target", "unit", "stock",
                    "in_progress", "satisfied", "waiting", "missing_ingredients", "redstone"), StandardCharsets.UTF_8);
            Files.writeString(r.familiesFile, BOM + header("time", "second", "controller", "network", "family", "address",
                    "gauges", "waiting", "in_progress", "max_wait_s", "rate_per_min", "status"), StandardCharsets.UTF_8);
            active = r;
            chat(Component.literal(Lang.t("csv.started"))
                    .withStyle(ChatFormatting.GRAY));
        } catch (IOException e) {
            LOGGER.warn("[cfc_search] Export CSV impossible : {}", e.toString());
            chat(Component.literal(Lang.t("csv.failed", e.getMessage()))
                    .withStyle(ChatFormatting.RED));
        }
    }

    public static void stop() {
        Recording r = active;
        active = null;
        if (r == null) return;
        MutableComponent msg = Component.literal(Lang.t("csv.done", r.samples))
                .withStyle(ChatFormatting.GRAY);
        // The Excel report (charts + diagnosis) is built from the snapshots kept in memory.
        boolean report = false;
        if (r.report != null && !r.report.isEmpty()) {
            try {
                r.report.write(r.reportFile);
                report = true;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[cfc_search] Rapport Excel impossible : {}", e.toString());
            }
        }
        if (r.report != null && !r.report.isEmpty()) {
            lastController = r.controller;
            lastReport = r.report;
        }
        if (report) msg.append(fileLink(r.reportFile)).append(Component.literal(" · ").withStyle(ChatFormatting.GRAY));
        msg.append(fileLink(r.gaugesFile)).append(Component.literal(" · ").withStyle(ChatFormatting.GRAY))
                .append(fileLink(r.familiesFile));
        chat(msg);
    }

    // ── AI analysis ───────────────────────────────────────────────────────

    /**
     * Copies everything collected (last recording of this controller, or a snapshot taken right now) to the
     * clipboard as a ready-to-paste prompt, saves it next to the reports, and opens the AI chat in the browser.
     */
    public static void analyseWithAi(BlockPos controller, String controllerName,
                                     Map<VirtualComponentPosition, VirtualComponentWidget> widgets, long gameTime) {
        Report report;
        boolean fromRecording = lastReport != null && controller.equals(lastController);
        if (fromRecording) {
            report = lastReport;
        } else {
            report = new Report(controllerName);
            fillReport(report, widgets, gameTime, controller, LocalDateTime.now().format(STAMP), 0);
        }
        String text = report.aiText();
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        Path file = null;
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            file = dir.resolve(baseName(controllerName) + "_"
                    + LocalDateTime.now().format(FILE_STAMP) + "_" + Lang.t("csv.file.ai") + ".txt");
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[cfc_search] Export IA impossible : {}", e.toString());
        }
        String url = AiConfig.url();
        try {
            Util.getPlatform().openUri(java.net.URI.create(url));
        } catch (RuntimeException e) {
            LOGGER.warn("[cfc_search] Ouverture de {} impossible : {}", url, e.toString());
        }
        MutableComponent msg = Component.literal(Lang.t(fromRecording ? "ai.copied.recording" : "ai.copied.snapshot",
                text.length() / 1000)).withStyle(ChatFormatting.GRAY);
        if (file != null) msg.append(fileLink(file));
        chat(msg);
    }

    /** Feeds one snapshot of the board into a report (used by recordings and by the instant AI analysis). */
    private static void fillReport(Report report, Map<VirtualComponentPosition, VirtualComponentWidget> widgets,
                                   long gameTime, BlockPos controller, String stamp, int second) {
        for (Map.Entry<VirtualComponentPosition, VirtualComponentWidget> e : widgets.entrySet()) {
            if (!(e.getValue().behaviour() instanceof VirtualGaugeBehaviour g)) continue;
            String[] im = itemAndMod(g.filter);
            report.addGauge(e.getKey(), g, im[0], im[1], g.unit == null ? "" : g.unit.label().getString(), second);
        }
        for (AddressStats.Row row : AddressStats.rows(controller, gameTime))
            report.addAddress(stamp, second, family(row.address()), row);
    }

    private static String[] itemAndMod(ItemStack item) {
        if (item.isEmpty()) return new String[]{"", ""};
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item.getItem());
        String mod = ModList.get().getModContainerById(key.getNamespace())
                .map(c -> c.getModInfo().getDisplayName()).orElse(key.getNamespace());
        return new String[]{item.getHoverName().getString(), mod};
    }

    /** Ends a recording whose time is up even if its controller screen isn't open. */
    public static void checkEnd() {
        if (active != null && Util.getMillis() >= active.endMs) stop();
    }

    /** Called about once a second while a controller screen is open. */
    public static void tick(BlockPos controller, Map<VirtualComponentPosition, VirtualComponentWidget> widgets,
                            long gameTime) {
        Recording r = active;
        if (r == null || !r.controller.equals(controller)) return;
        long now = Util.getMillis();
        if (now >= r.endMs) {
            stop();
            return;
        }
        // Requests are counted every second (a gauge can send several between two 10 s snapshots).
        r.report.observeRequests(widgets);
        if (now < r.nextSampleMs) return;
        // Stay on the 10 s grid after a pause (screen closed) instead of catching up in a burst.
        while (r.nextSampleMs <= now) r.nextSampleMs += INTERVAL_MS;
        try {
            writeSnapshot(r, widgets, gameTime, now);
            r.samples++;
        } catch (IOException e) {
            LOGGER.warn("[cfc_search] Ecriture CSV impossible : {}", e.toString());
            stop();
        }
    }

    /** "● REC 02:31 / 15:00 · 15 relevés", or null when not recording. */
    @Nullable
    public static String status() {
        Recording r = active;
        if (r == null) return null;
        long now = Util.getMillis();
        return Lang.t("csv.status", mmss(now - r.startMs), mmss(r.endMs - r.startMs), r.samples);
    }

    // ── Writing ───────────────────────────────────────────────────────────

    private static void writeSnapshot(Recording r, Map<VirtualComponentPosition, VirtualComponentWidget> widgets,
                                      long gameTime, long now) throws IOException {
        String stamp = LocalDateTime.now().format(STAMP);
        String second = String.valueOf((now - r.startMs) / 1000);

        List<Map.Entry<VirtualComponentPosition, VirtualComponentWidget>> entries = new ArrayList<>(widgets.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<VirtualComponentPosition, VirtualComponentWidget> e) -> e.getKey().y())
                .thenComparing(e -> e.getKey().x()));
        StringBuilder gauges = new StringBuilder();
        for (Map.Entry<VirtualComponentPosition, VirtualComponentWidget> e : entries) {
            if (!(e.getValue().behaviour() instanceof VirtualGaugeBehaviour g)) continue;
            ItemStack item = g.filter;
            String id = "", name = "", mod = "";
            if (!item.isEmpty()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item.getItem());
                id = key.toString();
                name = item.getHoverName().getString();
                mod = ModList.get().getModContainerById(key.getNamespace())
                        .map(c -> c.getModInfo().getDisplayName()).orElse(key.getNamespace());
            }
            String address = g.recipeAddress == null ? "" : g.recipeAddress;
            String unit = g.unit == null ? "" : g.unit.label().getString();
            r.report.addGauge(e.getKey(), g, name, mod, unit, (int) ((now - r.startMs) / 1000));
            gauges.append(line(stamp, second, r.controllerName, String.valueOf(e.getKey().x()), String.valueOf(e.getKey().y()),
                    id, name, mod, address, Network.of(address), family(address), type(address), String.valueOf(g.count),
                    g.unit == null ? "" : g.unit.label().getString(), g.isInfiniteStock() ? Lang.t("csv.infinite") : String.valueOf(g.stockLevel),
                    String.valueOf(g.promisedCount), yesNo(g.satisfied), yesNo(!g.satisfied && !g.redstonePowered),
                    yesNo(g.waitingForNetwork), yesNo(g.redstonePowered)));
        }
        Files.writeString(r.gaugesFile, gauges, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        StringBuilder families = new StringBuilder();
        for (AddressStats.Row row : AddressStats.rows(r.controller, gameTime)) {
            r.report.addAddress(stamp, (int) ((now - r.startMs) / 1000), family(row.address()), row);
            families.append(line(stamp, second, r.controllerName, Network.of(row.address()), family(row.address()), row.address(),
                    String.valueOf(row.gauges()), String.valueOf(row.waiting()), String.valueOf(row.promised()),
                    String.valueOf(row.oldestWaitTicks() / 20),
                    row.ratePerMinute() < 0 ? "" : String.format(java.util.Locale.ROOT, "%.1f", row.ratePerMinute()).replace('.', decimal),
                    row.status().label()));
        }
        Files.writeString(r.familiesFile, families, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String family(String address) {
        address = Network.local(address);
        int slash = address.indexOf('/');
        return slash > 0 ? address.substring(0, slash) : address;
    }

    private static String type(String address) {
        address = Network.local(address);
        int slash = address.indexOf('/');
        return slash > 0 ? address.substring(slash + 1) : "";
    }

    private static String yesNo(boolean value) {
        return Lang.t(value ? "csv.yes" : "csv.no");
    }

    private static String header(String... keys) {
        String[] names = new String[keys.length];
        for (int i = 0; i < keys.length; i++) names[i] = Lang.t("csv.col." + keys[i]);
        return line(names);
    }

    private static String line(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(separator);
            String f = fields[i] == null ? "" : fields[i];
            if (f.indexOf(separator) >= 0 || f.contains("\"") || f.contains("\n"))
                f = "\"" + f.replace("\"", "\"\"") + "\"";
            sb.append(f);
        }
        return sb.append("\r\n").toString();
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format("%dh%02d", s / 3600, (s % 3600) / 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    private static Component fileLink(Path file) {
        return Component.literal(file.getFileName().toString()).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.toAbsolutePath().toString()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(Lang.t("csv.open_file")))));
    }

    private static void chat(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(message, false);
    }
}
