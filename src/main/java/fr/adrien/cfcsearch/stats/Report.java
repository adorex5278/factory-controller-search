package fr.adrien.cfcsearch.stats;

import fr.adrien.cfcsearch.Lang;
import io.github.nbcss.createfactorycontroller.content.component.VirtualComponentPosition;
import io.github.nbcss.createfactorycontroller.content.component.connection.Connection;
import io.github.nbcss.createfactorycontroller.content.component.connection.LogisticsConnection;
import io.github.nbcss.createfactorycontroller.content.component.gauge.VirtualGaugeBehaviour;
import io.github.nbcss.createfactorycontroller.content.gui.widget.VirtualComponentWidget;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static fr.adrien.cfcsearch.stats.XlsxWriter.decimal;
import static fr.adrien.cfcsearch.stats.XlsxWriter.num;
import static fr.adrien.cfcsearch.stats.XlsxWriter.percent;
import static fr.adrien.cfcsearch.stats.XlsxWriter.text;
import static fr.adrien.cfcsearch.stats.XlsxWriter.wrap;

/**
 * Collects the snapshots of a CSV recording and turns them into an Excel report aimed at finding what slows the
 * factory down: a bottleneck score and a diagnosis per address, the ingredient chain of every waiting gauge traced
 * back to its probable root cause, a per-gauge summary, the timeline of the worst addresses, and the raw samples.
 */
final class Report {

    /** How many addresses get their own curve in the timeline chart. */
    private static final int TIMELINE_ADDRESSES = 6;
    /** How many addresses appear in the score chart. */
    private static final int SCORE_CHART_ADDRESSES = 15;
    /** Gauges waiting at least this share of the time get a chain analysis. */
    private static final double CHAIN_MIN_WAITING = 0.2;
    /** Longest ingredient path followed when looking for a root cause. */
    private static final int MAX_CHAIN_DEPTH = 8;

    private record Sample(String stamp, int second, String address, String family, int gauges, int waiting,
                          int promised, long maxWaitSeconds, double rate, AddressStats.Status status) {}

    static final class AddressAgg {
        final String address;
        final String family;
        /** Network prefix (prefix/type/name), "" for the default network. */
        final String network;
        int samples, slow, missing, maxGauges;
        double waitingRatioSum, promisedSum;
        long maxWait;
        double lastRate = -1;

        AddressAgg(String address, String family) {
            this.address = address;
            this.family = family;
            this.network = fr.adrien.cfcsearch.address.Network.of(address);
        }

        double waitingShare() { return samples == 0 ? 0 : waitingRatioSum / samples; }
        double slowShare() { return samples == 0 ? 0 : (double) slow / samples; }
        double missingShare() { return samples == 0 ? 0 : (double) missing / samples; }
        double avgPromised() { return samples == 0 ? 0 : promisedSum / samples; }

        /** 0–100: mostly "requests sent but not processed in time", then "waiting", then "missing ingredients". */
        double score() {
            double longWait = Math.min(1, maxWait / 600.0);
            return Math.round(100 * (0.45 * slowShare() + 0.30 * waitingShare() + 0.15 * missingShare() + 0.10 * longWait));
        }
    }

    /** An ingredient wire (source gauge → recipe gauge) as seen during the recording. */
    static final class Link {
        String source;            // "x,y" of the ingredient gauge
        int amount;
        int samples, failed;      // failed = the last request couldn't get this ingredient

        double failedShare() { return samples == 0 ? 0 : (double) failed / samples; }
    }

    static final class GaugeAgg {
        String key;
        int x, y;
        String item = "", itemId = "", mod = "", address = "", unit = "", mode = "", requestMode = "";
        /** Target converted to items (the gauge's count × its unit: stacks, buckets…). */
        long targetItems;
        int target, recipeOutput, craftBatch, timerTicks, promiseLimit;
        int samples, waiting, missing, maxPromised;
        double promisedSum;
        long minStock = Long.MAX_VALUE, maxStock = Long.MIN_VALUE, lastStock;
        boolean infinite;
        long lastRequestTick = -1;
        int requests;
        final Map<String, Link> ingredients = new LinkedHashMap<>();

        double waitingShare() { return samples == 0 ? 0 : (double) waiting / samples; }
        double missingShare() { return samples == 0 ? 0 : (double) missing / samples; }
        double avgPromised() { return samples == 0 ? 0 : promisedSum / samples; }
        boolean hasRecipe() { return !address.isBlank(); }
        String label() { return (item.isBlank() ? "?" : item) + " (" + key + ")"; }
    }

    private final List<Sample> samples = new ArrayList<>();
    private final Map<String, AddressAgg> addresses = new LinkedHashMap<>();
    private final Map<String, GaugeAgg> gauges = new LinkedHashMap<>();
    private final String controllerName;
    private int lastSecond;

    Report(String controllerName) {
        this.controllerName = controllerName;
    }

    void addAddress(String stamp, int second, String family, AddressStats.Row row) {
        lastSecond = Math.max(lastSecond, second);
        long maxWait = row.oldestWaitTicks() / 20;
        samples.add(new Sample(stamp, second, row.address(), family, row.gauges(), row.waiting(), row.promised(),
                maxWait, row.ratePerMinute(), row.status()));
        AddressAgg a = addresses.computeIfAbsent(row.address(), k -> new AddressAgg(row.address(), family));
        a.samples++;
        a.waitingRatioSum += row.gauges() == 0 ? 0 : (double) row.waiting() / row.gauges();
        a.promisedSum += row.promised();
        a.maxGauges = Math.max(a.maxGauges, row.gauges());
        a.maxWait = Math.max(a.maxWait, maxWait);
        if (row.status() == AddressStats.Status.SLOW) a.slow++;
        if (row.status() == AddressStats.Status.MISSING) a.missing++;
        if (row.ratePerMinute() >= 0) a.lastRate = row.ratePerMinute();
    }

    private static String key(VirtualComponentPosition pos) {
        return pos.x() + "," + pos.y();
    }

    /** One snapshot of a gauge (every 10 s). */
    void addGauge(VirtualComponentPosition pos, VirtualGaugeBehaviour g, String item, String mod, String unit,
                  int second) {
        lastSecond = Math.max(lastSecond, second);
        GaugeAgg a = gauges.computeIfAbsent(key(pos), k -> new GaugeAgg());
        a.key = key(pos);
        a.x = pos.x();
        a.y = pos.y();
        a.item = item;
        a.mod = mod;
        a.address = g.recipeAddress == null ? "" : g.recipeAddress;
        a.target = g.count;
        a.itemId = g.filter == null || g.filter.isEmpty() ? ""
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(g.filter.getItem()).toString();
        a.targetItems = g.unit == null || g.filter == null ? g.count : (long) g.count * Math.max(1, g.unit.toCountMultiplier(g.filter));
        a.unit = unit;
        a.mode = g.mode == null ? "" : g.mode.name().toLowerCase();
        a.requestMode = g.requestMode == null ? "" : g.requestMode.name().toLowerCase();
        a.recipeOutput = g.recipeOutput;
        a.craftBatch = g.craftBatch;
        a.timerTicks = g.customRequestTimer;
        a.promiseLimit = g.promiseLimit;
        a.infinite = g.isInfiniteStock();
        a.samples++;
        if (!g.satisfied && !g.redstonePowered) a.waiting++;
        if (g.waitingForNetwork) a.missing++;
        a.promisedSum += g.promisedCount;
        a.maxPromised = Math.max(a.maxPromised, g.promisedCount);
        if (!a.infinite) {
            a.minStock = Math.min(a.minStock, g.stockLevel);
            a.maxStock = Math.max(a.maxStock, g.stockLevel);
            a.lastStock = g.stockLevel;
        }
        for (Connection c : g.incomingConnections()) {
            if (!(c instanceof LogisticsConnection link) || c.from == null) continue;
            Link l = a.ingredients.computeIfAbsent(key(c.from), k -> new Link());
            l.source = key(c.from);
            l.amount = link.amount;
            l.samples++;
            if (!link.success) l.failed++;
        }
    }

    /** Called every second: counts the requests each gauge sends (its last-request tick changes). */
    void observeRequests(Map<VirtualComponentPosition, VirtualComponentWidget> widgets) {
        for (Map.Entry<VirtualComponentPosition, VirtualComponentWidget> e : widgets.entrySet()) {
            if (!(e.getValue().behaviour() instanceof VirtualGaugeBehaviour g)) continue;
            GaugeAgg a = gauges.get(key(e.getKey()));
            if (a == null) continue;   // first snapshot not taken yet
            if (a.lastRequestTick >= 0 && g.lastRequestTick != a.lastRequestTick) a.requests++;
            a.lastRequestTick = g.lastRequestTick;
        }
    }

    boolean isEmpty() {
        return samples.isEmpty() && gauges.isEmpty();
    }

    private double minutes() {
        return Math.max(1.0 / 6, lastSecond / 60.0);
    }

    // ── Diagnosis ─────────────────────────────────────────────────────────

    private static String diagnose(AddressAgg a) {
        List<String> parts = new ArrayList<>();
        if (a.slowShare() >= 0.25) parts.add(Lang.t("report.diag.slow"));
        if (a.missingShare() >= 0.25) parts.add(Lang.t("report.diag.missing"));
        if (a.waitingShare() >= 0.5 && a.avgPromised() >= 0.5 && a.slowShare() < 0.25) parts.add(Lang.t("report.diag.no_return"));
        if (a.waitingShare() >= 0.5 && a.avgPromised() < 0.5) parts.add(Lang.t("report.diag.idle"));
        if (parts.isEmpty() && a.waitingShare() >= 0.2) parts.add(Lang.t("report.diag.watch"));
        return parts.isEmpty() ? Lang.t("report.diag.ok") : String.join(" / ", parts);
    }

    private static String diagnose(GaugeAgg g) {
        if (g.waitingShare() < 0.5) return Lang.t("report.diag.ok");
        if (g.missingShare() >= 0.25) return Lang.t("report.gauge.missing");
        if (g.avgPromised() > 0) return Lang.t("report.gauge.slow");
        return Lang.t("report.gauge.idle");
    }

    /** Whether an ingredient looks like it's holding its consumer back. */
    static boolean isShort(GaugeAgg ingredient, Link link) {
        if (ingredient.infinite) return false;
        return link.failedShare() >= 0.25 || ingredient.lastStock < link.amount
                || (ingredient.waitingShare() >= 0.5 && ingredient.minStock < link.amount * 2L);
    }

    private static String ingredientVerdict(GaugeAgg ingredient, Link link) {
        if (ingredient == null) return Lang.t("report.chain.unknown_source");
        if (!isShort(ingredient, link)) return Lang.t("report.chain.ingredient_ok");
        if (ingredient.hasRecipe() && ingredient.targetItems == 0 && !ingredient.infinite)
            return Lang.t("report.chain.ingredient_target_zero");
        return ingredient.hasRecipe()
                ? Lang.t("report.chain.ingredient_short_produced", ingredient.address)
                : Lang.t("report.chain.ingredient_short_raw");
    }

    private Analysis analysis() {
        return new Analysis(gauges, addresses);
    }

    // ── Workbook ──────────────────────────────────────────────────────────

    void write(Path file) throws IOException {
        XlsxWriter xlsx = new XlsxWriter();

        Analysis analysis = analysis();
        List<Analysis.Finding> findings = analysis.findings(CHAIN_MIN_WAITING);

        // 0. Overview: what to fix first, ranked by gauges unblocked per unit of effort
        XlsxWriter.Sheet overview = xlsx.sheet(Lang.t("report.sheet.overview")).noFilter()
                .widths(9, 13, 16, 30, 12, 11, 80, 60)
                .header(Lang.t("report.overview.col.priority"), Lang.t("report.overview.col.type"),
                        Lang.t("report.overview.col.effort"), Lang.t("report.overview.col.target"),
                        Lang.t("report.overview.col.unblocks"), Lang.t("report.col.waiting_share"),
                        Lang.t("report.overview.col.action"), Lang.t("report.overview.col.gauges"))
                .headerHelp(Lang.t("help.overview.priority"), Lang.t("help.overview.type"), Lang.t("help.overview.effort"),
                        Lang.t("help.overview.target"), Lang.t("help.overview.unblocks"), Lang.t("help.overview.waiting"),
                        Lang.t("help.overview.action"), Lang.t("help.overview.gauges"));
        List<String> colors = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            Analysis.Finding f = findings.get(i);
            List<String> names = new ArrayList<>();
            for (GaugeAgg b : f.gauges()) names.add(b.label());
            overview.row(num(i + 1), text(f.kind().category.label()), text(f.kind().category.effortLabel()),
                    wrap(f.subject()), num(f.gauges().size()), percent(f.avgWaiting()), wrap(f.action()),
                    wrap(String.join(", ", names)));
            colors.add(f.kind().category.color);
        }
        if (findings.isEmpty()) overview.row(text(""), text(""), text(""), text(""), text(""), text(""), text(Lang.t("report.chain.none")));
        overview.row();
        overview.row(text(""), new XlsxWriter.Cell(Lang.t("report.overview.kpis"), XlsxWriter.STYLE_HEADER));
        long recipeGauges = gauges.values().stream().filter(GaugeAgg::hasRecipe).count();
        long longWaiting = gauges.values().stream().filter(g -> g.hasRecipe() && g.waitingShare() >= 0.5).count();
        overview.row(text(""), text(Lang.t("report.kpi.duration")), decimal(lastSecond / 60.0));
        if (lastSecond < 600) overview.row(text(""), wrap(Lang.t("report.kpi.short")));
        overview.row(text(""), text(Lang.t("report.kpi.gauges")), num(gauges.size()));
        overview.row(text(""), text(Lang.t("report.kpi.recipe_gauges")), num(recipeGauges));
        overview.row(text(""), text(Lang.t("report.kpi.waiting_gauges")), num(longWaiting));
        overview.row(text(""), text(Lang.t("report.kpi.addresses")), num(addresses.size()));
        overview.row(text(""), text(Lang.t("report.kpi.slow_addresses")),
                num(addresses.values().stream().filter(a -> a.slowShare() >= 0.25).count()));
        // One line per network when the controller drives several (prefix/type/name addresses)
        Map<String, List<AddressAgg>> byNetwork = new java.util.TreeMap<>();
        for (AddressAgg a : addresses.values()) byNetwork.computeIfAbsent(a.network, k -> new ArrayList<>()).add(a);
        if (byNetwork.size() > 1) {
            overview.row();
            overview.row(text(""), new XlsxWriter.Cell(Lang.t("report.networks"), XlsxWriter.STYLE_HEADER),
                    new XlsxWriter.Cell(Lang.t("report.kpi.addresses"), XlsxWriter.STYLE_HEADER),
                    new XlsxWriter.Cell(Lang.t("report.kpi.slow_addresses"), XlsxWriter.STYLE_HEADER),
                    new XlsxWriter.Cell(Lang.t("report.kpi.waiting_gauges"), XlsxWriter.STYLE_HEADER));
            for (Map.Entry<String, List<AddressAgg>> e : byNetwork.entrySet()) {
                String net = e.getKey();
                long slowA = e.getValue().stream().filter(a -> a.slowShare() >= 0.25).count();
                long waitG = gauges.values().stream().filter(g -> g.hasRecipe() && g.waitingShare() >= 0.5
                        && fr.adrien.cfcsearch.address.Network.of(g.address).equals(net)).count();
                overview.row(text(""), text(net.isEmpty() ? Lang.t("stats.network.default") : net),
                        num(e.getValue().size()), num(slowA), num(waitG));
            }
        }
        overview.row(text(""), wrap(Lang.t("report.kpi.legend")));
        int chartRows = Math.min(findings.size(), 12);
        if (chartRows > 0)
            overview.chart(new XlsxWriter.Chart(false, Lang.t("report.chart.unblocks"), overview.name(),
                    4, 2, chartRows + 1, List.of(5), 9, 1, 18, 24, colors.subList(0, chartRows)));

        // How it works
        XlsxWriter.Sheet help = xlsx.sheet(Lang.t("report.sheet.help")).noFilter().widths(26, 110)
                .header(Lang.t("help.col.topic"), Lang.t("help.col.explanation"));
        for (String k : List.of("principle", "measure", "waiting", "in_progress", "failed", "slow", "root", "categories",
                "networks", "overview", "summary", "chain", "gauges", "timeline", "samples", "ai", "limits"))
            help.row(text(Lang.t("help.topic." + k)), wrap(Lang.t("help.text." + k)));

        // 1. Summary per address, worst first
        List<AddressAgg> ranked = new ArrayList<>(addresses.values());
        ranked.sort(Comparator.comparingDouble(AddressAgg::score).reversed().thenComparing(a -> a.address));
        XlsxWriter.Sheet summary = xlsx.sheet(Lang.t("report.sheet.summary"))
                .widths(22, 14, 16, 9, 9, 13, 13, 15, 12, 12, 11, 70)
                .header(Lang.t("csv.col.address"), Lang.t("csv.col.network"), Lang.t("csv.col.family"), Lang.t("csv.col.gauges"),
                        Lang.t("report.col.score"), Lang.t("report.col.waiting_share"), Lang.t("report.col.slow_share"),
                        Lang.t("report.col.missing_share"), Lang.t("report.col.avg_in_progress"),
                        Lang.t("csv.col.max_wait_s"), Lang.t("csv.col.rate_per_min"), Lang.t("report.col.diagnosis"))
                .headerHelp(Lang.t("help.col.address"), Lang.t("help.col.network"), Lang.t("help.col.family"),
                        Lang.t("help.col.gauges"), Lang.t("help.col.score"), Lang.t("help.col.waiting"), Lang.t("help.col.slow"),
                        Lang.t("help.col.missing"), Lang.t("help.col.in_progress"), Lang.t("help.col.max_wait"),
                        Lang.t("help.col.rate"), Lang.t("help.col.diagnosis"));
        for (AddressAgg a : ranked)
            summary.row(text(a.address), text(a.network.isEmpty() ? Lang.t("stats.network.default") : a.network),
                    text(a.family), num(a.maxGauges), num(a.score()), percent(a.waitingShare()),
                    percent(a.slowShare()), percent(a.missingShare()), decimal(a.avgPromised()), num(a.maxWait),
                    a.lastRate < 0 ? text("") : decimal(a.lastRate), wrap(diagnose(a)));

        // 2. Chain: every waiting recipe gauge, its ingredients, and the probable root cause
        List<GaugeAgg> waitingGauges = analysis.waitingRecipeGauges(CHAIN_MIN_WAITING);
        XlsxWriter.Sheet chain = xlsx.sheet(Lang.t("report.sheet.chain"))
                .widths(30, 18, 11, 11, 30, 10, 11, 11, 11, 13, 18, 70)
                .header(Lang.t("report.chain.col.gauge"), Lang.t("csv.col.address"), Lang.t("report.col.waiting_share"),
                        Lang.t("report.col.requests_per_min"), Lang.t("report.chain.col.ingredient"),
                        Lang.t("report.chain.col.per_request"), Lang.t("report.col.last_stock"),
                        Lang.t("report.col.min_stock"), Lang.t("report.col.waiting_share"),
                        Lang.t("report.chain.col.failed_share"), Lang.t("report.chain.col.produced_at"),
                        Lang.t("report.chain.col.verdict"))
                .headerHelp(Lang.t("help.chain.gauge"), Lang.t("help.col.address"), Lang.t("help.col.waiting"),
                        Lang.t("help.col.requests"), Lang.t("help.chain.ingredient"), Lang.t("help.chain.per_request"),
                        Lang.t("help.col.end_stock"), Lang.t("help.col.min_stock"), Lang.t("help.chain.ingredient_waiting"),
                        Lang.t("help.chain.failed"), Lang.t("help.chain.produced_at"), Lang.t("help.chain.verdict"));
        double minutes = minutes();
        for (GaugeAgg g : waitingGauges) {
            chain.row(text(g.label()), text(g.address), percent(g.waitingShare()), decimal(g.requests / minutes),
                    text(""), text(""), text(""), text(""), text(""), text(""), text(""),
                    wrap(Lang.t("report.chain.root", analysis.rootCause(g).text())));
            if (g.ingredients.isEmpty())
                chain.row(text(""), text(""), text(""), text(""), text(Lang.t("report.chain.no_ingredient")));
            for (Link l : g.ingredients.values()) {
                GaugeAgg ing = gauges.get(l.source);
                boolean noStock = ing == null || ing.infinite || ing.minStock == Long.MAX_VALUE;
                chain.row(text(""), text(""), text(""), text(""),
                        text("← " + (ing == null ? "? (" + l.source + ")" : ing.label())), num(l.amount),
                        noStock ? text(ing != null && ing.infinite ? Lang.t("csv.infinite") : "") : num(ing.lastStock),
                        noStock ? text("") : num(ing.minStock),
                        ing == null ? text("") : percent(ing.waitingShare()), percent(l.failedShare()),
                        text(ing == null || !ing.hasRecipe() ? Lang.t("report.chain.raw") : ing.address),
                        wrap(ingredientVerdict(ing, l)));
            }
        }
        if (waitingGauges.isEmpty()) chain.row(text(Lang.t("report.chain.none")));

        // 3. Timeline of waiting gauges for the worst addresses
        List<String> top = ranked.stream().limit(TIMELINE_ADDRESSES).map(a -> a.address).toList();
        TreeMap<Integer, Map<String, Integer>> byTime = new TreeMap<>();
        for (Sample s : samples)
            if (top.contains(s.address())) byTime.computeIfAbsent(s.second(), k -> new LinkedHashMap<>()).put(s.address(), s.waiting());
        XlsxWriter.Sheet timeline = xlsx.sheet(Lang.t("report.sheet.timeline"));
        String[] header = new String[top.size() + 1];
        header[0] = Lang.t("csv.col.second");
        for (int i = 0; i < top.size(); i++) header[i + 1] = top.get(i);
        timeline.header(header);
        int[] widths = new int[header.length];
        java.util.Arrays.fill(widths, 16);
        timeline.widths(widths);
        for (Map.Entry<Integer, Map<String, Integer>> e : byTime.entrySet()) {
            XlsxWriter.Cell[] cells = new XlsxWriter.Cell[top.size() + 1];
            cells[0] = num(e.getKey());
            for (int i = 0; i < top.size(); i++) cells[i + 1] = num(e.getValue().getOrDefault(top.get(i), 0));
            timeline.row(cells);
        }

        // 4. Per gauge, the ones that wait the most first, with all their settings
        List<GaugeAgg> gaugeList = new ArrayList<>(gauges.values());
        gaugeList.sort(Comparator.comparingDouble(GaugeAgg::waitingShare).reversed());
        XlsxWriter.Sheet gaugeSheet = xlsx.sheet(Lang.t("report.sheet.gauges"))
                .widths(6, 6, 26, 16, 20, 9, 10, 13, 15, 12, 11, 11, 11, 11, 10, 12, 9, 9, 11, 11, 50, 45)
                .header(Lang.t("csv.col.x"), Lang.t("csv.col.y"), Lang.t("csv.col.item"), Lang.t("csv.col.mod"),
                        Lang.t("csv.col.address"), Lang.t("csv.col.target"), Lang.t("csv.col.unit"),
                        Lang.t("report.col.waiting_share"), Lang.t("report.col.missing_share"),
                        Lang.t("report.col.avg_in_progress"), Lang.t("report.col.max_in_progress"),
                        Lang.t("report.col.min_stock"), Lang.t("report.col.max_stock"), Lang.t("report.col.last_stock"),
                        Lang.t("report.col.requests_per_min"), Lang.t("report.col.mode"),
                        Lang.t("report.col.recipe_output"), Lang.t("report.col.craft_batch"),
                        Lang.t("report.col.timer_s"), Lang.t("report.col.promise_limit"),
                        Lang.t("report.col.ingredients"), Lang.t("report.col.diagnosis"));
        for (GaugeAgg g : gaugeList) {
            boolean noStock = g.infinite || g.minStock == Long.MAX_VALUE;
            List<String> ings = new ArrayList<>();
            for (Link l : g.ingredients.values()) {
                GaugeAgg ing = gauges.get(l.source);
                ings.add(l.amount + "× " + (ing == null ? "? (" + l.source + ")" : ing.item));
            }
            gaugeSheet.row(num(g.x), num(g.y), text(g.item), text(g.mod), text(g.address), num(g.target), text(g.unit),
                    percent(g.waitingShare()), percent(g.missingShare()), decimal(g.avgPromised()), num(g.maxPromised),
                    noStock ? text(Lang.t("csv.infinite")) : num(g.minStock),
                    noStock ? text(Lang.t("csv.infinite")) : num(g.maxStock),
                    noStock ? text(Lang.t("csv.infinite")) : num(g.lastStock),
                    decimal(g.requests / minutes), text(g.mode + (g.requestMode.isBlank() ? "" : " / " + g.requestMode)),
                    num(g.recipeOutput), num(g.craftBatch),
                    g.timerTicks > 0 ? decimal(g.timerTicks / 20.0) : text(""),
                    g.promiseLimit > 0 ? num(g.promiseLimit) : text(""),
                    wrap(String.join(", ", ings)), wrap(diagnose(g)));
        }

        // 5. Raw samples per address, for pivot tables and filters
        XlsxWriter.Sheet raw = xlsx.sheet(Lang.t("report.sheet.samples"))
                .widths(20, 9, 22, 16, 9, 11, 11, 13, 12, 18)
                .header(Lang.t("csv.col.time"), Lang.t("csv.col.second"), Lang.t("csv.col.address"),
                        Lang.t("csv.col.family"), Lang.t("csv.col.gauges"), Lang.t("csv.col.waiting"),
                        Lang.t("csv.col.in_progress"), Lang.t("csv.col.max_wait_s"), Lang.t("csv.col.rate_per_min"),
                        Lang.t("csv.col.status"));
        for (Sample s : samples)
            raw.row(text(s.stamp()), num(s.second()), text(s.address()), text(s.family()), num(s.gauges()),
                    num(s.waiting()), num(s.promised()), num(s.maxWaitSeconds()),
                    s.rate() < 0 ? text("") : decimal(s.rate()), text(s.status().label()));

        // Charts on the summary sheet, to the right of the table
        int scoreRows = Math.min(ranked.size(), SCORE_CHART_ADDRESSES);
        if (scoreRows > 0)
            summary.chart(new XlsxWriter.Chart(false, Lang.t("report.chart.score", controllerName), summary.name(),
                    1, 2, scoreRows + 1, List.of(4), 12, 1, 22, 22));
        if (!byTime.isEmpty() && !top.isEmpty()) {
            List<Integer> cols = new ArrayList<>();
            for (int i = 0; i < top.size(); i++) cols.add(i + 2);
            summary.chart(new XlsxWriter.Chart(true, Lang.t("report.chart.timeline"), timeline.name(),
                    1, 2, byTime.size() + 1, cols, 12, 23, 22, 44));
        }

        xlsx.write(file);
    }

    // ── AI export ─────────────────────────────────────────────────────────

    /** Upper bound of the text handed to the AI (big factories): sections are cut beyond it, with a note. */
    private static final int AI_MAX_CHARS = 900_000;

    /** Everything collected, as Markdown + semicolon tables, preceded by instructions for the AI. */
    String aiText() {
        StringBuilder sb = new StringBuilder();
        sb.append(Lang.t("ai.prompt")).append("\n\n");
        sb.append("## ").append(Lang.t("ai.section.context")).append("\n");
        sb.append("- controller: ").append(controllerName).append("\n");
        sb.append("- duration_min: ").append(String.format(java.util.Locale.ROOT, "%.1f", lastSecond / 60.0)).append("\n");
        sb.append("- snapshot_every_s: 10 (requests counted every 1 s)\n");
        sb.append("- gauges: ").append(gauges.size()).append(", addresses: ").append(addresses.size()).append("\n\n");

        Analysis analysis = analysis();
        sb.append("## ").append(Lang.t("ai.section.rules")).append("\n```\npriority;category;effort;kind;target;gauges_unblocked;avg_waiting;action;blocked_gauges\n");
        List<Analysis.Finding> findings = analysis.findings(CHAIN_MIN_WAITING);
        for (int i = 0; i < findings.size(); i++) {
            Analysis.Finding f = findings.get(i);
            List<String> names = new ArrayList<>();
            for (GaugeAgg b : f.gauges()) names.add(b.label());
            sb.append(i + 1).append(';').append(f.kind().category).append(';').append(f.kind().category.effort).append(';')
                    .append(f.kind()).append(';').append(csv(f.subject())).append(';').append(f.gauges().size()).append(';')
                    .append(pct(f.avgWaiting())).append(';').append(csv(f.action())).append(';')
                    .append(csv(String.join(", ", names))).append('\n');
        }
        sb.append("```\n\n");

        sb.append("## ").append(Lang.t("ai.section.addresses")).append("\n```\n")
                .append("address;network;family;gauges;score;waiting;slow;missing_ingredients;avg_in_progress;max_wait_s;stock_growth_per_min\n");
        List<AddressAgg> ranked = new ArrayList<>(addresses.values());
        ranked.sort(Comparator.comparingDouble(AddressAgg::score).reversed());
        for (AddressAgg a : ranked)
            sb.append(csv(a.address)).append(';').append(csv(a.network)).append(';').append(csv(a.family)).append(';').append(a.maxGauges).append(';')
                    .append((long) a.score()).append(';').append(pct(a.waitingShare())).append(';').append(pct(a.slowShare()))
                    .append(';').append(pct(a.missingShare())).append(';').append(dec(a.avgPromised())).append(';')
                    .append(a.maxWait).append(';').append(a.lastRate < 0 ? "" : dec(a.lastRate)).append('\n');
        sb.append("```\n\n");

        sb.append("## ").append(Lang.t("ai.section.chain")).append("\n```\n")
                .append("gauge;address;waiting;requests_per_min;root_cause | ingredient;per_request;end_stock;min_stock;ingredient_waiting;failed_requests;produced_at\n");
        double minutes = minutes();
        for (GaugeAgg g : analysis.waitingRecipeGauges(CHAIN_MIN_WAITING)) {
            sb.append(csv(g.label())).append(';').append(csv(g.address)).append(';').append(pct(g.waitingShare())).append(';')
                    .append(dec(g.requests / minutes)).append(';').append(csv(analysis.rootCause(g).text())).append('\n');
            for (Link l : g.ingredients.values()) {
                GaugeAgg ing = gauges.get(l.source);
                sb.append("  <- ").append(csv(ing == null ? "? (" + l.source + ")" : ing.label())).append(';').append(l.amount).append(';')
                        .append(ing == null || ing.infinite ? "inf" : String.valueOf(ing.lastStock)).append(';')
                        .append(ing == null || ing.infinite || ing.minStock == Long.MAX_VALUE ? "" : String.valueOf(ing.minStock)).append(';')
                        .append(ing == null ? "" : pct(ing.waitingShare())).append(';').append(pct(l.failedShare())).append(';')
                        .append(ing == null || !ing.hasRecipe() ? "raw" : csv(ing.address)).append('\n');
            }
            if (sb.length() > AI_MAX_CHARS / 2) {
                sb.append("... (truncated)\n");
                break;
            }
        }
        sb.append("```\n\n");

        sb.append("## ").append(Lang.t("ai.section.gauges")).append("\n```\n")
                .append("pos;item;item_id;mod;address;target;unit;target_items;waiting;missing_ingredients;avg_in_progress;max_in_progress;min_stock;max_stock;end_stock;requests_per_min;mode;recipe_output;batch;timer_s;request_limit;ingredients\n");
        List<GaugeAgg> all = new ArrayList<>(gauges.values());
        all.sort(Comparator.comparingDouble(GaugeAgg::waitingShare).reversed());
        for (GaugeAgg g : all) {
            boolean noStock = g.infinite || g.minStock == Long.MAX_VALUE;
            List<String> ings = new ArrayList<>();
            for (Link l : g.ingredients.values()) {
                GaugeAgg ing = gauges.get(l.source);
                ings.add(l.amount + "x " + (ing == null ? "?(" + l.source + ")" : ing.item + "(" + ing.key + ")"));
            }
            sb.append(g.key).append(';').append(csv(g.item)).append(';').append(csv(g.itemId)).append(';').append(csv(g.mod)).append(';')
                    .append(csv(g.address)).append(';').append(g.target).append(';').append(csv(g.unit)).append(';').append(g.targetItems)
                    .append(';').append(pct(g.waitingShare())).append(';')
                    .append(pct(g.missingShare())).append(';').append(dec(g.avgPromised())).append(';').append(g.maxPromised).append(';')
                    .append(noStock ? "inf" : String.valueOf(g.minStock)).append(';').append(noStock ? "inf" : String.valueOf(g.maxStock)).append(';')
                    .append(noStock ? "inf" : String.valueOf(g.lastStock)).append(';').append(dec(g.requests / minutes)).append(';')
                    .append(csv(g.mode + "/" + g.requestMode)).append(';').append(g.recipeOutput).append(';').append(g.craftBatch).append(';')
                    .append(g.timerTicks > 0 ? dec(g.timerTicks / 20.0) : "").append(';').append(g.promiseLimit > 0 ? String.valueOf(g.promiseLimit) : "")
                    .append(';').append(csv(String.join(", ", ings))).append('\n');
            if (sb.length() > AI_MAX_CHARS * 0.85) {
                sb.append("... (truncated)\n");
                break;
            }
        }
        sb.append("```\n\n");

        // Waiting gauges per address over time, one compact series per address
        sb.append("## ").append(Lang.t("ai.section.timeline")).append("\n```\n");
        Map<String, StringBuilder> series = new LinkedHashMap<>();
        for (Sample s : samples) {
            StringBuilder line = series.computeIfAbsent(s.address(), k -> new StringBuilder());
            if (!line.isEmpty()) line.append(',');
            line.append(s.waiting()).append('/').append(s.gauges()).append(s.promised() > 0 ? "p" + s.promised() : "");
        }
        for (Map.Entry<String, StringBuilder> e : series.entrySet()) {
            if (sb.length() + e.getValue().length() > AI_MAX_CHARS) {
                sb.append("... (truncated)\n");
                break;
            }
            sb.append(csv(e.getKey())).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("```\n").append(Lang.t("ai.timeline_legend")).append("\n");
        return sb.toString();
    }

    private static String pct(double share) {
        return Math.round(share * 100) + "%";
    }

    private static String dec(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static String csv(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ');
    }
}
