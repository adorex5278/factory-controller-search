package fr.adrien.cfcsearch.stats;

import fr.adrien.cfcsearch.Lang;
import fr.adrien.cfcsearch.stats.Report.AddressAgg;
import fr.adrien.cfcsearch.stats.Report.GaugeAgg;
import fr.adrien.cfcsearch.stats.Report.Link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the collected data into causes, not symptoms. Every waiting gauge is traced upstream to what really blocks
 * it, and configuration detectors look for the cheap fixes (target at 0, misspelled address, phantom promises,
 * overstock, loops…). Actions are ranked by gauges unblocked divided by the effort they take.
 */
final class Analysis {

    /** How hard a fix is: a setting (seconds), a check on site (minutes), building machines, feeding raw stock. */
    enum Category {
        CONFIG(1, "6AA84F"), CHECK(2, "E69138"), MACHINES(3, "3D85C6"), RAW(4, "CC4125");

        final int effort;
        final String color;

        Category(int effort, String color) {
            this.effort = effort;
            this.color = color;
        }

        String label() {
            return Lang.t("analysis.category." + name().toLowerCase(Locale.ROOT));
        }

        String effortLabel() {
            return Lang.t("analysis.effort." + name().toLowerCase(Locale.ROOT));
        }
    }

    enum Kind {
        TARGET_ZERO(Category.CONFIG), NO_INGREDIENTS(Category.CONFIG), LOOP(Category.CONFIG),
        ADDRESS_TYPO(Category.CONFIG), PHANTOM_PROMISES(Category.CONFIG), OVERSTOCK(Category.CONFIG),
        DUPLICATE_GAUGE(Category.CHECK), HUGE_TARGET(Category.CHECK), SHARED_MACHINE(Category.CHECK),
        NO_RETURN(Category.CHECK), TOO_DEEP(Category.CHECK),
        SLOW_PROCESSING(Category.MACHINES), RAW_SHORTAGE(Category.RAW);

        final Category category;

        Kind(Category category) {
            this.category = category;
        }
    }

    /** Why one gauge waits: {@code subject} groups gauges sharing the same cause. */
    record Cause(Kind kind, String subject, String text) {}

    record Finding(Kind kind, String subject, List<GaugeAgg> gauges, double avgWaiting, String action) {
        /** Gauges unblocked per unit of effort; findings that unblock nothing directly stay listed at the end. */
        double priority() {
            return (gauges.isEmpty() ? 0.1 : gauges.size()) / (double) kind.category.effort;
        }
    }

    private static final double SLOW_SHARE = 0.25;
    private static final int MAX_DEPTH = 8;

    private final Map<String, GaugeAgg> gauges;
    private final Map<String, AddressAgg> addresses;
    private final Map<String, Cause> causes = new HashMap<>();

    Analysis(Map<String, GaugeAgg> gauges, Map<String, AddressAgg> addresses) {
        this.gauges = gauges;
        this.addresses = addresses;
    }

    // ── Per-gauge root cause ──────────────────────────────────────────────

    Cause rootCause(GaugeAgg gauge) {
        return causes.computeIfAbsent(gauge.key, k -> trace(gauge));
    }

    private Cause trace(GaugeAgg gauge) {
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        GaugeAgg current = gauge;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            seen.add(current.key);
            GaugeAgg worst = null;
            double worstRatio = Double.MAX_VALUE;
            for (Link l : current.ingredients.values()) {
                GaugeAgg ing = gauges.get(l.source);
                if (ing == null || !Report.isShort(ing, l)) continue;
                double ratio = (double) ing.lastStock / Math.max(1, l.amount) - l.failedShare() * 10;
                if (ratio < worstRatio) {
                    worstRatio = ratio;
                    worst = ing;
                }
            }
            if (worst == null) {
                if (current.hasRecipe() && current.ingredients.isEmpty())
                    return with(path, Kind.NO_INGREDIENTS, current.address, Lang.t("analysis.cause.no_ingredients", current.address));
                if (current.hasRecipe()) {
                    AddressAgg a = addresses.get(current.address);
                    if (a != null && a.slowShare() >= SLOW_SHARE)
                        return with(path, Kind.SLOW_PROCESSING, current.address,
                                Lang.t("analysis.cause.slow", current.address, pct(a.slowShare())));
                    return with(path, Kind.NO_RETURN, current.address, Lang.t("analysis.cause.no_return", current.address));
                }
                return with(path, Kind.RAW_SHORTAGE, current.label(), Lang.t("analysis.cause.raw", current.label()));
            }
            path.add(worst.label());
            if (worst.hasRecipe() && worst.targetItems == 0 && !worst.infinite)
                return with(path, Kind.TARGET_ZERO, worst.label(), Lang.t("analysis.cause.target_zero", worst.label()));
            if (!worst.hasRecipe())
                return with(path, Kind.RAW_SHORTAGE, worst.label(), Lang.t("analysis.cause.raw", worst.label()));
            if (seen.contains(worst.key))
                return with(path, Kind.LOOP, worst.label(), Lang.t("analysis.cause.loop", worst.label()));
            current = worst;
        }
        return with(path, Kind.TOO_DEEP, current.label(), Lang.t("analysis.cause.too_deep"));
    }

    private static Cause with(List<String> path, Kind kind, String subject, String text) {
        return new Cause(kind, subject, path.isEmpty() ? text : String.join(" ← ", path) + " : " + text);
    }

    // ── Findings ──────────────────────────────────────────────────────────

    List<GaugeAgg> waitingRecipeGauges(double minShare) {
        List<GaugeAgg> list = new ArrayList<>(gauges.values().stream()
                .filter(g -> g.hasRecipe() && g.waitingShare() >= minShare).toList());
        list.sort(Comparator.comparingDouble(GaugeAgg::waitingShare).reversed().thenComparing(g -> g.address));
        return list;
    }

    List<Finding> findings(double chainMinWaiting) {
        List<Finding> out = new ArrayList<>();

        // 1. Root causes of the waiting gauges, grouped
        Map<String, List<GaugeAgg>> grouped = new LinkedHashMap<>();
        Map<String, Cause> first = new LinkedHashMap<>();
        for (GaugeAgg g : waitingRecipeGauges(chainMinWaiting)) {
            Cause c = rootCause(g);
            String k = c.kind() + "|" + c.subject();
            grouped.computeIfAbsent(k, x -> new ArrayList<>()).add(g);
            first.putIfAbsent(k, c);
        }
        long noReturn = first.values().stream().filter(c -> c.kind() == Kind.NO_RETURN).count();
        for (Map.Entry<String, List<GaugeAgg>> e : grouped.entrySet()) {
            Cause c = first.get(e.getKey());
            List<GaugeAgg> list = e.getValue();
            String n = String.valueOf(list.size());
            String action = switch (c.kind()) {
                case TARGET_ZERO -> Lang.t("analysis.action.target_zero", c.subject(), n);
                case NO_INGREDIENTS -> Lang.t("analysis.action.no_ingredients", c.subject(), n);
                case LOOP -> Lang.t("analysis.action.loop_cause", c.subject(), n);
                case NO_RETURN -> Lang.t("analysis.action.no_return", c.subject(), n)
                        + (noReturn >= 3 ? " " + Lang.t("analysis.action.no_return_common", noReturn) : "");
                case SLOW_PROCESSING -> Lang.t("analysis.action.slow", c.subject(),
                        pct(addresses.containsKey(c.subject()) ? addresses.get(c.subject()).slowShare() : 0), n);
                case RAW_SHORTAGE -> Lang.t("analysis.action.raw", c.subject(), n);
                default -> Lang.t("analysis.action.too_deep", c.subject(), n);
            };
            out.add(new Finding(c.kind(), c.subject(), list, avgWaiting(list), action));
        }

        // 2. Configuration detectors
        detectTypos(out);
        detectPhantomPromises(out);
        detectOverstock(out);
        detectLoops(out);
        detectDuplicates(out);
        detectHugeTargets(out);
        detectSharedMachines(out);

        out.sort(Comparator.comparingDouble(Finding::priority).reversed()
                .thenComparingInt(f -> f.kind().category.effort)
                .thenComparing(Comparator.comparingDouble(Finding::avgWaiting).reversed()));
        return out;
    }

    private void detectTypos(List<Finding> out) {
        Map<String, List<GaugeAgg>> byAddress = new LinkedHashMap<>();
        for (GaugeAgg g : gauges.values())
            if (g.hasRecipe()) byAddress.computeIfAbsent(g.address.trim(), k -> new ArrayList<>()).add(g);
        List<String> names = new ArrayList<>(byAddress.keySet());
        for (int i = 0; i < names.size(); i++)
            for (int j = i + 1; j < names.size(); j++) {
                String a = names.get(i), b = names.get(j);
                if (!looksLikeTypo(a, b)) continue;
                List<GaugeAgg> ga = byAddress.get(a), gb = byAddress.get(b);
                // The less used spelling is probably the mistake
                List<GaugeAgg> affected = ga.size() <= gb.size() ? ga : gb;
                out.add(new Finding(Kind.ADDRESS_TYPO, a + " / " + b, affected, avgWaiting(affected),
                        Lang.t("analysis.action.typo", a, ga.size(), b, gb.size())));
            }
    }

    /** Close spellings (edit distance ≤ 2), but not deliberate variants like "PRESS A" / "PRESS B" or "MIX 2". */
    static boolean looksLikeTypo(String a, String b) {
        String x = a.toUpperCase(Locale.ROOT).trim(), y = b.toUpperCase(Locale.ROOT).trim();
        if (x.equals(y)) return true;   // only differing by case / spaces
        // mine/traitement/smelting vs usine/traitement/smelting: two networks, each with its own machine
        if (!fr.adrien.cfcsearch.address.Network.of(x).equals(fr.adrien.cfcsearch.address.Network.of(y))) return false;
        if (Math.min(x.length(), y.length()) < 4 || levenshtein(x, y) > 2) return false;
        String[] wx = x.split("[\\s/]+"), wy = y.split("[\\s/]+");
        if (wx.length == wy.length) {
            for (int i = 0; i < wx.length; i++) {
                if (wx[i].equals(wy[i])) continue;
                boolean variant = (wx[i].length() <= 2 && wy[i].length() <= 2)
                        || (wx[i].matches("\\d+") && wy[i].matches("\\d+"))
                        || wx[i].replaceAll("\\d", "").equals(wy[i].replaceAll("\\d", ""));   // USINE1 / USINE2
                if (variant) return false;
            }
        } else {
            // "MIXING" vs "MIXING 2": an extra short suffix is a variant, not a typo
            String longer = x.length() > y.length() ? x : y, shorter = longer == x ? y : x;
            if (longer.startsWith(shorter) && longer.substring(shorter.length()).trim().length() <= 2) return false;
        }
        return x.replaceAll("[\\d\\s]", "").length() >= 4;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private void detectPhantomPromises(List<Finding> out) {
        for (GaugeAgg g : gauges.values()) {
            if (!g.hasRecipe() || g.waitingShare() < 0.5) continue;
            long perRequest = Math.max(1, (long) g.recipeOutput * Math.max(1, g.craftBatch));
            if (g.avgPromised() < 10.0 * perRequest) continue;
            boolean starved = g.ingredients.values().stream()
                    .anyMatch(l -> gauges.containsKey(l.source) && Report.isShort(gauges.get(l.source), l));
            if (!starved) continue;
            out.add(new Finding(Kind.PHANTOM_PROMISES, g.label(), List.of(g), g.waitingShare(),
                    Lang.t("analysis.action.phantom", g.label(), dec(g.avgPromised()), perRequest)));
        }
    }

    private void detectOverstock(List<Finding> out) {
        for (GaugeAgg g : gauges.values()) {
            if (!g.hasRecipe() || g.infinite || g.targetItems <= 0 || g.lastStock < 5L * g.targetItems) continue;
            // Who else waits for the same ingredients this overstocked gauge keeps consuming?
            Set<String> mine = g.ingredients.keySet();
            List<GaugeAgg> blocked = new ArrayList<>();
            for (GaugeAgg other : gauges.values())
                if (other != g && other.hasRecipe() && other.waitingShare() >= 0.5
                        && other.ingredients.keySet().stream().anyMatch(mine::contains)) blocked.add(other);
            List<String> ing = new ArrayList<>();
            for (String s : mine) ing.add(gauges.containsKey(s) ? gauges.get(s).item : s);
            out.add(new Finding(Kind.OVERSTOCK, g.label(), blocked, avgWaiting(blocked),
                    Lang.t("analysis.action.overstock", g.label(), g.lastStock, g.targetItems,
                            g.lastStock / Math.max(1, g.targetItems), String.join(", ", ing), blocked.size())));
        }
    }

    /** Recipe gauges that (indirectly) consume each other: strongly connected components of the ingredient graph. */
    private void detectLoops(List<Finding> out) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        List<List<String>> components = new ArrayList<>();
        for (String start : gauges.keySet())
            if (!index.containsKey(start)) strongConnect(start, index, low, stack, onStack, counter, components);
        for (List<String> comp : components) {
            boolean selfLoop = comp.size() == 1 && gauges.get(comp.getFirst()).ingredients.containsKey(comp.getFirst());
            if (comp.size() < 2 && !selfLoop) continue;
            List<GaugeAgg> members = new ArrayList<>();
            for (String k : comp) members.add(gauges.get(k));
            if (members.stream().noneMatch(m -> m.waitingShare() >= 0.5)) continue;
            List<String> labels = new ArrayList<>();
            for (GaugeAgg m : members) labels.add(m.label());
            out.add(new Finding(Kind.LOOP, String.join(" ↔ ", labels), members, avgWaiting(members),
                    Lang.t("analysis.action.loop", String.join(" → ", labels))));
        }
    }

    private void strongConnect(String v, Map<String, Integer> index, Map<String, Integer> low, Deque<String> stack,
                               Set<String> onStack, int[] counter, List<List<String>> components) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        GaugeAgg g = gauges.get(v);
        if (g != null && g.hasRecipe())
            for (String w : g.ingredients.keySet()) {
                if (!gauges.containsKey(w) || !gauges.get(w).hasRecipe()) continue;
                if (!index.containsKey(w)) {
                    strongConnect(w, index, low, stack, onStack, counter, components);
                    low.put(v, Math.min(low.get(v), low.get(w)));
                } else if (onStack.contains(w)) {
                    low.put(v, Math.min(low.get(v), index.get(w)));
                }
            }
        if (low.get(v).equals(index.get(v))) {
            List<String> comp = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.add(w);
            } while (!w.equals(v));
            components.add(comp);
        }
    }

    private void detectDuplicates(List<Finding> out) {
        Map<String, List<GaugeAgg>> byItem = new LinkedHashMap<>();
        for (GaugeAgg g : gauges.values())
            if (g.hasRecipe() && !g.itemId.isBlank()) byItem.computeIfAbsent(g.itemId, k -> new ArrayList<>()).add(g);
        for (List<GaugeAgg> list : byItem.values()) {
            if (list.size() < 2 || list.stream().noneMatch(g -> g.waitingShare() >= 0.5)) continue;
            List<String> where = new ArrayList<>();
            for (GaugeAgg g : list) where.add(g.key + " → " + g.address);
            out.add(new Finding(Kind.DUPLICATE_GAUGE, list.getFirst().item, list, avgWaiting(list),
                    Lang.t("analysis.action.duplicate", list.getFirst().item, list.size(), String.join(" ; ", where))));
        }
    }

    private void detectHugeTargets(List<Finding> out) {
        for (GaugeAgg g : gauges.values()) {
            if (!g.hasRecipe() || g.targetItems < 4096 || g.waitingShare() < 0.8) continue;
            List<GaugeAgg> sameAddress = new ArrayList<>();
            for (GaugeAgg other : gauges.values())
                if (other != g && g.address.equals(other.address) && other.waitingShare() >= 0.5) sameAddress.add(other);
            out.add(new Finding(Kind.HUGE_TARGET, g.label(), sameAddress, avgWaiting(sameAddress),
                    Lang.t("analysis.action.huge_target", g.label(), g.targetItems, g.address, sameAddress.size())));
        }
    }

    private void detectSharedMachines(List<Finding> out) {
        Map<String, List<GaugeAgg>> byAddress = new LinkedHashMap<>();
        for (GaugeAgg g : gauges.values())
            if (g.hasRecipe()) byAddress.computeIfAbsent(g.address, k -> new ArrayList<>()).add(g);
        for (Map.Entry<String, List<GaugeAgg>> e : byAddress.entrySet()) {
            List<GaugeAgg> list = e.getValue();
            Set<String> items = new LinkedHashSet<>();
            for (GaugeAgg g : list) items.add(g.item);
            if (items.size() < 3) continue;
            // An ingredient used by several of these recipes = the same input competing for one machine
            Map<String, Integer> uses = new HashMap<>();
            for (GaugeAgg g : list) for (String s : g.ingredients.keySet()) uses.merge(s, 1, Integer::sum);
            List<String> shared = new ArrayList<>();
            uses.forEach((s, n) -> {
                if (n >= 2) shared.add(gauges.containsKey(s) ? gauges.get(s).item : s);
            });
            List<GaugeAgg> waiting = list.stream().filter(g -> g.waitingShare() >= 0.5).toList();
            if (shared.isEmpty() || waiting.size() < 2) continue;
            out.add(new Finding(Kind.SHARED_MACHINE, e.getKey(), waiting, avgWaiting(waiting),
                    Lang.t("analysis.action.shared", e.getKey(), items.size(), String.join(", ", items), String.join(", ", shared))));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static double avgWaiting(List<GaugeAgg> list) {
        return list.stream().mapToDouble(GaugeAgg::waitingShare).average().orElse(0);
    }

    static String pct(double share) {
        return Math.round(share * 100) + "%";
    }

    static String dec(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
