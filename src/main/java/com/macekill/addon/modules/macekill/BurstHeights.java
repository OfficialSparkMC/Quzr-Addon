package com.macekill.addon.modules.macekill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds single-burst totem height lists: up to {@link Combat#MAX_TOTEM_HITS} hits at
 * STRICTLY INCREASING heights so every mace hit in one tick deals strictly more damage
 * than the previous one (beats hurtResistantTime invuln + enemy AutoTotem re-equip).
 *
 * Heights are fractional doubles: mace bonus scales monotonically with fall distance and
 * the server tracks it as a float, so even a 0.25-block separation deals strictly more
 * damage. Small separations (Height Step 0.5/0.25) pack a 198-hit burst into a ~50-block
 * ascent instead of ~200 — fewer packets per hit AND feasible under low cave roofs.
 *
 * The ceiling used here is the PIERCE ceiling (world top above the target), not the
 * VClip clearance to the nearest roof — so bursts keep working inside caves / under a
 * roof, where the smash teleports straight up through the ceiling (see TpMace /
 * MaceAttect stepMoveDown, which skips block-intersecting steps).
 */
public final class BurstHeights {
    private BurstHeights() {}

    public enum Mode {
        /** Even spread across [MIN_LETHAL, maxH] (previous default behaviour). */
        SPREAD,
        /** Start from the custom height list, extend upward to fill the burst. */
        LIST,
        /** base + i * increment. */
        INCREMENTAL
    }

    /** ~24 mace damage — safely lethal for a 20 HP target. */
    public static final double MIN_LETHAL = 6.0;

    /**
     * @param totemsWanted how many totems to pop (1..{@link Combat#MAX_TOTEM_HITS}); +1 kill hit is appended
     * @param maxH         pierce headroom above the target (worldTop - targetY)
     * @param mode         height shape
     * @param listHeights  raw custom list (LIST mode), may be null
     * @param base         first height (INCREMENTAL mode)
     * @param inc          added height per hit (INCREMENTAL mode, and LIST extension step)
     * @param step         minimum separation between consecutive heights (Height Step setting)
     * @return strictly increasing heights in [MIN_LETHAL, maxH], size {@code <= totemsWanted + 1};
     *         shorter than requested when headroom cannot fit more distinct heights
     */
    public static List<Double> build(int totemsWanted, double maxH, Mode mode,
                                     List<String> listHeights, double base, double inc, double step) {
        double avail = Math.max(0, maxH);
        if (avail < MIN_LETHAL) return List.of();
        double sep = Math.max(0.05, step);

        int want = Math.min(Math.max(1, totemsWanted), Combat.MAX_TOTEM_HITS);
        int maxKills = (int) Math.floor((avail - MIN_LETHAL) / sep) + 1;
        int kills = Math.min(want + 1, maxKills);
        if (kills < 1) return List.of();

        List<Double> seeds;
        switch (mode) {
            case LIST -> seeds = parseList(listHeights);
            case INCREMENTAL -> {
                seeds = new ArrayList<>(kills);
                double s = Math.max(0.05, inc);
                for (int i = 0; i < kills; i++) seeds.add(base + i * s);
            }
            default -> {
                // Even spread across the real headroom (same math as Combat.totemBypassHeights).
                seeds = new ArrayList<>(kills);
                double span = avail - MIN_LETHAL;
                int denom = Math.max(1, kills - 1);
                for (int i = 0; i < kills; i++) {
                    seeds.add(MIN_LETHAL + (double) i * span / denom);
                }
            }
        }

        if (mode == Mode.LIST) {
            // Custom list first; if it is shorter than the burst, extend upward so the
            // burst still reaches totemsWanted+1 distinct hits (capped by headroom later).
            double s = Math.max(sep, inc);
            while (seeds.size() < kills) {
                double last = seeds.isEmpty() ? MIN_LETHAL - s : seeds.get(seeds.size() - 1);
                seeds.add(last + s);
            }
        }

        // Escalation requires ascending order — sort, clamp, then push too-close hits up.
        Collections.sort(seeds);
        List<Double> out = new ArrayList<>(kills);
        double prev = MIN_LETHAL - sep;
        for (double h : seeds) {
            if (out.size() >= kills) break;
            double v = Math.max(h, MIN_LETHAL);
            v = Math.max(v, prev + sep); // strictly greater fall than the previous hit
            if (v > avail + 1e-9) break; // no headroom left — burst is shorter (caller warns)
            out.add(v);
            prev = v;
        }
        return out;
    }

    private static List<Double> parseList(List<String> raw) {
        List<Double> list = new ArrayList<>();
        if (raw == null) return list;
        for (String s : raw) {
            if (s == null) continue;
            try {
                double v = Double.parseDouble(s.trim());
                if (v > 0) list.add(v);
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }
}
