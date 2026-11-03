package com.macekill.addon.modules.macekill;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure planner for the MaceAttect Cave Safe path.
 *
 * <p><b>Server fact (1.21.11):</b> {@code ServerPlayNetworkHandler.onPlayerMove} calls
 * {@code player.onLanding()} whenever a movement packet's Y delta is positive
 * ({@code boolean bl2 = m > 0.0; ... if (bl2) player.onLanding();}). {@code onLanding()}
 * zeroes {@code fallDistance}. Therefore an up/down cycle cannot stack fall: the next
 * ascent wipes everything the previous descent accumulated. The old "stack N cycles"
 * idea is a no-op on 1.21.11 servers with movement checks enabled.
 *
 * <p>What does work is one clear climb per hit: ascent (fall reset), descent to the start
 * (fall = climb height), attack. Every hit is independent; {@code MaceItem.postDamageEntity}
 * resets the attacker's fall after each landed smash, which is harmless because the next
 * hit rebuilds its own fall.
 *
 * <p><b>Why a cave roof does not stop the climb:</b> the server accepts a movement packet
 * when the <em>destination</em> box is clear ({@code isEntityNotCollidingWithBlocks}), not
 * when the whole path is clear. Vertical moved-wrongly error is discarded by the
 * tautological {@code if (m > -0.5 || m < 0.5) m = 0.0;}, so a climb may pass through a
 * roof and finish in an air pocket above it. Cave Safe therefore scans destinations from
 * {@link #MAX_LIFT} downwards, preferring the highest collision-clear endpoint. Contiguous
 * clearance is only the fallback when no higher endpoint exists.
 *
 * <p>The movement cap is measured from the position at the start of the server tick:
 * {@code displacement^2 <= 100 * q}, where {@code q = packetsSinceTick} (q resets to 1
 * after the 5th packet). That is 10 blocks for the first and sixth+ packets, up to 22.36
 * blocks for the fifth. Cave Safe sends {@link #CAVE_PAD_PACKETS} no-op movement packets
 * first so every climb's ascent is packet 5. One climb uses two further packets (up,
 * down); the down packet returns to the tick-start position, so its displacement is zero.
 * The padding leaves only one ascent above 10 blocks per tick, so the production burst is
 * exactly one hit; {@code Sustained Drain} repeats the same padded climb one cooldown
 * window at a time for the rest of the totems.
 */
public final class CaveBurst {
    private CaveBurst() {}

    /** Player hitbox height used by the cave clearance scan. */
    public static final double HITBOX_HEIGHT = 1.8;
    /** Keep a small gap so a float-rounded packet never clips the roof. */
    public static final double CLEARANCE_MARGIN = 0.05;
    /**
     * Highest cave destination. The fifth movement packet of a server tick allows
     * {@code sqrt(500) = 22.36} blocks; 22.0 leaves velocity/rounding headroom while
     * matching the proven MaceTotemFail destination bound.
     */
    public static final double MAX_LIFT = 22.0;
    /**
     * No-op movement packets sent before every cave climb. They make the ascent packet 5
     * of the tick, which raises the server displacement cap to 22.36 blocks. The last one
     * may carry the rotation.
     */
    public static final int CAVE_PAD_PACKETS = 4;
    public static final int MAX_HITS = 198;

    /** Player + mace attack damage before the mace fall bonus. */
    public static final double MACE_BASE_DAMAGE = 6.0;
    /** Mace attack speed: 4.0 - 3.4 = 0.6 attacks/s -> 33.33 ticks per full charge. */
    public static final double ATTACK_COOLDOWN_TICKS = 20.0 / 0.6;
    /** Base damage multiplier immediately after an attack (0.2 + progress^2 * 0.8). */
    public static final double LOW_BASE_DAMAGE = MACE_BASE_DAMAGE * 0.2;

    /** Total damage needed to pop a full-health totem with a little margin. */
    public static final double FIRST_HIT_DAMAGE = 20.5;
    /** Raw damage increase needed to pop the next totem through hurtResistantTime. */
    public static final double TOTEM_DAMAGE_STEP = 9.5;
    /** Post-totem pool: 1 health + Absorption II (8). */
    public static final double TOTEM_POOL = 9.0;

    /** Fall needed for the first hit when the attack cooldown is low (~20.5 damage). */
    public static final double FIRST_HIT_FALL =
        fallForDamage(FIRST_HIT_DAMAGE, LOW_BASE_DAMAGE);
    /** Fall needed for a fully charged mace to reach {@link #FIRST_HIT_DAMAGE}. */
    public static final double MIN_LETHAL_FALL =
        fallForDamage(FIRST_HIT_DAMAGE, MACE_BASE_DAMAGE);

    /** A single independent climb+hit. */
    public record Hit(double height, int upPacketIndex, double damage) {}

    public record BurstPlan(List<Hit> hits) {
        public boolean isEmpty() { return hits.isEmpty(); }
    }

    /** Mace smash bonus for a server fall distance. */
    public static double maceBonus(double fall) {
        if (fall <= 3.0) return 4.0 * fall;
        if (fall <= 8.0) return 12.0 + 2.0 * (fall - 3.0);
        return 22.0 + fall - 8.0;
    }

    public static double damageForFall(double fall, double baseDamage) {
        return baseDamage + maceBonus(fall);
    }

    /** Inverse of {@link #damageForFall}; clamp at 0 for impossible damage. */
    public static double fallForDamage(double damage, double baseDamage) {
        double bonus = damage - baseDamage;
        if (bonus <= 12.0) return Math.max(0.0, bonus / 4.0);
        if (bonus <= 22.0) return 3.0 + (bonus - 12.0) / 2.0;
        return 8.0 + (bonus - 22.0);
    }

    /**
     * Maximum climb inside a fully contiguous clear column. Kept as the fallback for low
     * caves where no higher air pocket is reachable. Capped at {@link #MAX_LIFT} and by
     * the supplied step cap.
     */
    public static double effectiveLift(double clearance, double moveStep) {
        double stepCap = Math.min(MAX_LIFT, Math.max(1.0, moveStep));
        double clearCap = clearance - HITBOX_HEIGHT - CLEARANCE_MARGIN;
        return Math.max(0.0, Math.min(stepCap, clearCap));
    }

    /**
     * Server displacement cap (squared) for the n-th movement packet in a tick.
     * Packet indices are 1-based and include rotation/no-op move packets.
     */
    public static double packetCapSquared(int packetIndex) {
        int q = Math.max(1, packetIndex);
        if (q > 5) q = 1; // ServerPlayNetworkHandler resets q to 1 after 5 packets.
        return 100.0 * q;
    }

    public static double packetCap(int packetIndex) {
        return Math.sqrt(packetCapSquared(packetIndex));
    }

    /** Smallest whole-block cave headroom that permits a climb of {@code fall} blocks. */
    public static int headroomFor(double fall) {
        return (int) Math.ceil(fall + HITBOX_HEIGHT + CLEARANCE_MARGIN);
    }

    /**
     * Plans independent one-climb hits for a burst.
     *
     * @param totemsWanted  totems the caller wants to pop (+ a small kill margin)
     * @param maxLift       highest <em>verified clear</em> cave destination (the 22-block
     *                      destination scan, or the contiguous fallback)
     * @param leadingPackets movement packets sent before the first climb. Production uses
     *                       {@link #CAVE_PAD_PACKETS}; the first ascent is then packet 5
     * @return hits whose ascent fits the packet cap and whose damage escalation pops one
     *         totem per hit; empty when the ceiling is below {@link #FIRST_HIT_FALL}
     */
    public static BurstPlan burstPlan(int totemsWanted, double maxLift, int leadingPackets) {
        int wanted = Math.min(Math.max(1, totemsWanted) + 2, MAX_HITS);
        List<Hit> hits = new ArrayList<>();
        double previousHeight = Double.NaN;
        double previousWorstDamage = 0.0;

        for (int i = 0; i < wanted; i++) {
            double requiredFall;
            if (i == 0) {
                requiredFall = FIRST_HIT_FALL;
            } else {
                // The first hit may land at a full charge (base 6) while every follow-up
                // hit is at the post-attack low charge (base 1.2). Plan against the worst
                // case so the second hit still clears the post-pop pool.
                double requiredDamage = previousWorstDamage + TOTEM_DAMAGE_STEP;
                requiredFall = fallForDamage(requiredDamage, LOW_BASE_DAMAGE);
            }

            int upIndex = Math.max(1, leadingPackets) + 2 * i + 1;
            double cap = Math.min(maxLift, packetCap(upIndex));
            if (requiredFall > cap + 1e-9) break;

            // The first climb uses the full verified destination, not just the minimum
            // lethal fall. caveLift() guarantees that exact endpoint is collision-clear;
            // a minimum-height climb could sit inside a thick roof below an air pocket.
            // Later climbs use the minimum escalation height (with production padding the
            // second ascent is capped at 10 blocks, so it cannot fit anyway).
            double height = (i == 0)
                ? cap
                : Math.max(requiredFall, previousHeight + 0.05);
            if (height > cap + 1e-9) break;

            hits.add(new Hit(height, upIndex, damageForFall(height, LOW_BASE_DAMAGE)));

            // Worst-case raw damage of this hit for planning the next one: the first hit
            // can be fully charged, later hits are always at the low post-attack charge.
            double base = (i == 0) ? MACE_BASE_DAMAGE : LOW_BASE_DAMAGE;
            previousWorstDamage = damageForFall(height, base);
            previousHeight = height;
        }
        return new BurstPlan(List.copyOf(hits));
    }

    /**
     * Delay between sustained cave hits. A sustained hit only has to be lethal on its
     * own (hurt invulnerability has expired), so the delay is chosen to give enough
     * attack-charge damage for the available fall. Returns -1 when even a fully charged
     * mace cannot reach {@link #FIRST_HIT_DAMAGE} with this lift.
     */
    public static int sustainedDelay(double maxLift) {
        double bonus = maceBonus(Math.max(0.0, maxLift));
        double neededBase = FIRST_HIT_DAMAGE - bonus;
        if (neededBase > MACE_BASE_DAMAGE) return -1;
        if (neededBase <= LOW_BASE_DAMAGE) return 22; // still wait past hurt invulnerability

        double modifier = neededBase / MACE_BASE_DAMAGE;
        double progressSq = (modifier - 0.2) / 0.8;
        if (progressSq <= 0.0) return 22;
        double progress = Math.sqrt(Math.min(1.0, progressSq));
        int ticks = (int) Math.ceil(progress * ATTACK_COOLDOWN_TICKS - 0.5);
        return Math.max(22, Math.min(60, ticks));
    }

    /** Base attack damage after waiting {@code ticks} since the previous attack. */
    public static double baseDamageAtTicks(int ticks) {
        double progress = Math.min(1.0, Math.max(0.0, (ticks + 0.5) / ATTACK_COOLDOWN_TICKS));
        return MACE_BASE_DAMAGE * (0.2 + progress * progress * 0.8);
    }
}
