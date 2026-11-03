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
 * hit rebuilds its own fall. Heights must be strictly increasing to beat
 * hurtResistantTime, and each climb must fit both the contiguous cave clearance and the
 * server's per-tick movement cap.
 *
 * <p>The movement cap is measured from the position at the start of the server tick:
 * {@code displacement^2 <= 100 * q}, where {@code q = packetsSinceTick} (q resets to 1
 * after the 5th packet). That is 10 blocks for the first and sixth+ packets, up to 22.36
 * blocks for the fifth. One climb uses two packets (up, down), so a burst can fit at most
 * two strictly increasing hits before the q-reset clamps every later ascent to 10 blocks.
 * Cave Safe deliberately caps a climb at {@link #MAX_LIFT} = 8, so the normal production
 * path emits exactly one burst hit; the rest of the totems are drained by
 * {@code Sustained Drain}, one hit per cooldown window.
 */
public final class CaveBurst {
    private CaveBurst() {}

    /** Player hitbox height used by the cave clearance scan. */
    public static final double HITBOX_HEIGHT = 1.8;
    /** Keep a small gap so a float-rounded packet never clips the roof. */
    public static final double CLEARANCE_MARGIN = 0.05;
    /** Conservative per-climb cap: every packet stays under the 10-block q-reset cap. */
    public static final double MAX_LIFT = 8.0;
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
     * Maximum safe climb from a floor position with {@code clearance} blocks of air
     * between the feet and the first blocking block. Capped at {@link #MAX_LIFT} and by
     * the user's Move Step.
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
     * @param maxLift       maximum contiguous-clear climb
     * @param leadingPackets movement packets sent before the first climb (0 or 1 for a
     *                       rotation packet; fall primes are deliberately not sent)
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

            double height = Math.max(requiredFall, i == 0 ? 0.0 : previousHeight + 0.05);
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
