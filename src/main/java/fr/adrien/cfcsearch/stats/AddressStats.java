package fr.adrien.cfcsearch.stats;

import fr.adrien.cfcsearch.Lang;

import io.github.nbcss.createfactorycontroller.content.component.VirtualComponentPosition;
import io.github.nbcss.createfactorycontroller.content.component.gauge.VirtualGaugeBehaviour;
import io.github.nbcss.createfactorycontroller.content.gui.widget.VirtualComponentWidget;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side throughput statistics per destination address, built from the gauge state the controller
 * already syncs (stock, open promises, satisfied flags). Sampled while a controller screen is open and
 * kept for the game session, per controller.
 */
public final class AddressStats {

    /** A gauge waiting this long with requests in flight counts as "too slow". */
    public static final long SLOW_TICKS = 3 * 60 * 20;
    /** Gaps longer than this between samples (screen closed) aren't counted as observed time. */
    private static final long MAX_GAP_TICKS = 100;
    /** Minimum observed time before a production rate is shown. */
    public static final long MIN_RATE_TICKS = 30 * 20;

    public enum Status {
        SLOW("slow", 0xFF5555),
        MISSING("missing", 0xFFAA33),
        WAITING("waiting", 0xFFFF66),
        OK("ok", 0x66FF66);

        private final String key;
        public final int color;

        Status(String key, int color) {
            this.key = key;
            this.color = color;
        }

        public String label() {
            return Lang.t("status." + key);
        }
    }

    public record Row(String address, int gauges, int waiting, int promised, long oldestWaitTicks,
                      long observedTicks, double ratePerMinute, Status status) {}

    private static final class Track {
        String address = "";
        long lastTick = -1;
        int lastStock;
        long produced;
        long observed;
        long waitingSince = -1;
        int promised;
        boolean missing;
    }

    private static final Map<BlockPos, Map<VirtualComponentPosition, Track>> DATA = new HashMap<>();

    private AddressStats() {}

    public static void sample(BlockPos controller, Map<VirtualComponentPosition, VirtualComponentWidget> widgets,
                              long now) {
        Map<VirtualComponentPosition, Track> tracks = DATA.computeIfAbsent(controller, k -> new HashMap<>());
        tracks.keySet().retainAll(widgets.keySet());
        for (Map.Entry<VirtualComponentPosition, VirtualComponentWidget> entry : widgets.entrySet()) {
            if (!(entry.getValue().behaviour() instanceof VirtualGaugeBehaviour gauge)
                    || gauge.recipeAddress == null || gauge.recipeAddress.isBlank() || gauge.isInfiniteStock()) {
                tracks.remove(entry.getKey());
                continue;
            }
            Track t = tracks.computeIfAbsent(entry.getKey(), k -> new Track());
            if (!t.address.equals(gauge.recipeAddress)) {
                // Re-addressed gauge: its history belonged to the old address.
                Track fresh = new Track();
                fresh.address = gauge.recipeAddress;
                tracks.put(entry.getKey(), fresh);
                t = fresh;
            }
            boolean continuous = t.lastTick >= 0 && now - t.lastTick <= MAX_GAP_TICKS && now >= t.lastTick;
            if (continuous) {
                t.observed += now - t.lastTick;
                int delta = gauge.stockLevel - t.lastStock;
                if (delta > 0) t.produced += delta;
            }
            t.lastTick = now;
            t.lastStock = gauge.stockLevel;
            t.promised = gauge.promisedCount;
            t.missing = gauge.waitingForNetwork;
            boolean waiting = !gauge.satisfied && !gauge.redstonePowered;
            if (!waiting) t.waitingSince = -1;
            else if (t.waitingSince < 0 || !continuous) t.waitingSince = now;
        }
    }

    /** One row per address, worst first. */
    public static List<Row> rows(BlockPos controller, long now) {
        Map<VirtualComponentPosition, Track> tracks = DATA.getOrDefault(controller, Map.of());
        Map<String, List<Track>> byAddress = new LinkedHashMap<>();
        for (Track t : tracks.values()) byAddress.computeIfAbsent(t.address, k -> new ArrayList<>()).add(t);

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Track>> e : byAddress.entrySet()) {
            int waiting = 0, promised = 0;
            long oldest = 0, observed = 0, produced = 0;
            boolean slow = false, missing = false;
            for (Track t : e.getValue()) {
                promised += t.promised;
                observed = Math.max(observed, t.observed);
                produced += t.produced;
                if (t.waitingSince >= 0) {
                    waiting++;
                    long waited = now - t.waitingSince;
                    oldest = Math.max(oldest, waited);
                    if (t.promised > 0 && waited >= SLOW_TICKS) slow = true;
                    if (t.missing) missing = true;
                }
            }
            Status status = slow ? Status.SLOW : missing ? Status.MISSING : waiting > 0 ? Status.WAITING : Status.OK;
            double rate = observed >= MIN_RATE_TICKS ? produced * 1200.0 / observed : -1;
            rows.add(new Row(e.getKey(), e.getValue().size(), waiting, promised, oldest, observed, rate, status));
        }
        rows.sort(Comparator.comparingInt((Row r) -> r.status().ordinal())
                .thenComparing(Comparator.comparingLong(Row::oldestWaitTicks).reversed())
                .thenComparing(Row::address));
        return rows;
    }

    public static String formatTicks(long ticks) {
        long seconds = ticks / 20;
        return seconds >= 3600
                ? String.format("%dh%02d", seconds / 3600, (seconds % 3600) / 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
