package com.macekill.addon.modules.macekill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds single-burst totem height lists: up to {@link Combat#MAX_TOTEM_HITS} hits at
 * STRICTLY INCREASING heights so every mace hit in one tick deals strictly more damage
 * than the previous one (beats hurtResistantTime invuln + enemy AutoTotem re-equip).
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
    public static final int MIN_LETHAL = 6;

    /**
     * @param totemsWanted how many totems to pop (1..{@link Combat#MAX_TOTEM_HITS}); +1 kill hit is appended
     * @param maxH         pierce headroom above the target (worldTop - targetY)
     * @param mode         height shape
     * @param listHeights  raw custom list (LIST mode), may be null
     * @param base         first height (INCREMENTAL mode)
     * @param inc          added height per hit (INCREMENTAL mode, and LIST extension step)
     * @return strictly increasing heights in [MIN_LETHAL, maxH], size {@code <= totemsWanted + 1};
     *         shorter than requested when headroom cannot fit more distinct heights
     */
    public static List<Integer> build(int totemsWanted, int maxH, Mode mode,
                                      List<String> listHeights, int base, int inc) {
        int avail = Math.max(0, maxH);
        if (avail < MIN_LETHAL) return List.of();

        int want = Math.min(Math.max(1, totemsWanted), Combat.MAX_TOTEM_HITS);
        int maxKills = avail - MIN_LETHAL + 1; // distinct integer heights in [MIN_LETHAL, avail]
        int kills = Math.min(want + 1, maxKills);
        if (kills < 1) return List.of();

        List<Integer> seeds;
        switch (mode) {
            case LIST -> seeds = parseList(listHeights);
            case INCREMENTAL -> {
                seeds = new ArrayList<>(kills);
                int step = Math.max(1, inc);
                for (int i = 0; i < kills; i++) seeds.add(base + i * step);
            }
            default -> {
                // Even spread across the real headroom (same math as Combat.totemBypassHeights).
                seeds = new ArrayList<>(kills);
                int span = avail - MIN_LETHAL;
                int denom = Math.max(1, kills - 1);
                for (int i = 0; i < kills; i++) {
                    seeds.add(MIN_LETHAL + (int) Math.round((double) i * span / denom));
                }
            }
        }

        if (mode == Mode.LIST) {
            // Custom list first; if it is shorter than the burst, extend upward so the
            // burst still reaches totemsWanted+1 distinct hits (capped by headroom later).
            int step = Math.max(1, inc);
            while (seeds.size() < kills) {
                int last = seeds.isEmpty() ? MIN_LETHAL - step : seeds.get(seeds.size() - 1);
                seeds.add(last + step);
            }
        }

        // Escalation requires ascending order — sort, clamp, then push duplicates up.
        Collections.sort(seeds);
        List<Integer> out = new ArrayList<>(kills);
        int prev = MIN_LETHAL - 1;
        for (int h : seeds) {
            if (out.size() >= kills) break;
            int v = Math.max(h, MIN_LETHAL);
            v = Math.max(v, prev + 1); // strictly greater than the previous hit
            if (v > avail) break;      // no headroom left — burst is shorter (caller warns)
            out.add(v);
            prev = v;
        }
        return out;
    }

    private static List<Integer> parseList(List<String> raw) {
        List<Integer> list = new ArrayList<>();
        if (raw == null) return list;
        for (String s : raw) {
            if (s == null) continue;
            try {
                long v = Math.round(Double.parseDouble(s.trim()));
                if (v > 0 && v <= Integer.MAX_VALUE) list.add((int) v);
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }
}
