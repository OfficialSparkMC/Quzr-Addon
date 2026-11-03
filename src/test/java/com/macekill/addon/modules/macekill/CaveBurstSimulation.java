package com.macekill.addon.modules.macekill;

import java.util.Locale;

/**
 * Deterministic proof harness for the MaceAttect Cave Safe path.
 *
 * <p>This is a model of the 1.21.11 server, not a Minecraft client test. It mirrors the
 * authoritative code paths:
 * <ul>
 *   <li>{@code ServerPlayNetworkHandler.onPlayerMove}: displacement cap
 *       {@code p - velocity^2 <= 100 * q} where {@code q} is the packet count since the
 *       tick (reset to 1 after the 5th), {@code onLanding()} on every positive Y delta,
 *       and {@code Entity.fall} adding {@code -heightDifference} on a descent.</li>
 *   <li>{@code MaceItem.getBonusAttackDamage}: fall-to-damage curve.</li>
 *   <li>{@code PlayerEntity.attack} + {@code LivingEntity.damage}: attack charge,
 *       {@code hurtResistantTime}, partial damage from strictly increasing hits.</li>
 *   <li>{@code LivingEntity.tryUseDeathProtector}: totem at 1 health + Absorption II.</li>
 *   <li>{@code MaceItem.postDamageEntity}: attacker fall reset after a landed smash.</li>
 * </ul>
 *
 * <p>Assumptions: unarmored target, no critical hits, no Density/other smash enchantments,
 * no Resistance effect, and the player stays in the same clear column. Those only reduce
 * damage; the packet/fall/totem mechanics are exact. Run with
 * {@code ./gradlew caveBurstSimulation}.
 */
public final class CaveBurstSimulation {
    private static final double MOVE_STEP = 8.0;
    private static final boolean ROTATE = true;
    private static final int TOTEMS = 24;
    private static final int MAX_SUSTAINED = 40;

    private static final double EPS = 1e-9;

    public static void main(String[] args) {
        System.out.println("CaveBurst simulation: 1.21.11 cave climb + mace + totem model");
        System.out.printf(Locale.ROOT,
            "moveStep=%.1f rotate=%s totems=%d maxSustained=%d%n%n",
            MOVE_STEP, ROTATE, TOTEMS, MAX_SUSTAINED);

        Checks checks = new Checks();
        clearanceSweep(checks);
        oldCycleFallacy(checks);
        plannerCapacity(checks);

        System.out.println();
        checks.summary();
        if (!checks.ok()) {
            System.exit(1);
        }
    }

    /**
     * Exercises every cave height that can matter: impossible (too low for a lethal hit),
     * sustained-only (burst starts at low charge and needs more fall), and burst+sustained.
     */
    private static void clearanceSweep(Checks checks) {
        System.out.println("clearance  lift   burst  delay  sustained  pops  packets  result");
        System.out.println("---------  -----  -----  -----  ---------  ----  -------  ------");

        for (int clearance = 2; clearance <= 32; clearance++) {
            double lift = CaveBurst.effectiveLift(clearance, MOVE_STEP);
            int delay = CaveBurst.sustainedDelay(lift);
            CaveBurst.BurstPlan plan = CaveBurst.burstPlan(TOTEMS, lift, ROTATE ? 1 : 0);
            SimTarget target = new SimTarget(TOTEMS);
            ServerSim sim = new ServerSim(clearance, target);

            boolean possible = delay >= 0;
            int sustained = 0;
            String result;

            if (!possible) {
                // No full-charge hit can reach the totem pool; the module must refuse.
                checks.check(plan.isEmpty(),
                    "clearance " + clearance + ": impossible cave produced a burst plan");
                checks.check(sim.rejected == 0, "clearance " + clearance + ": impossible cave sent packets");
                result = "IMPOSSIBLE (needs " + CaveBurst.headroomFor(CaveBurst.MIN_LETHAL_FALL)
                    + "+ headroom)";
            } else {
                int pops = sim.runBurst(plan, ROTATE, checks);
                while (pops < TOTEMS && sustained < MAX_SUSTAINED) {
                    // The chain waits delay ticks, then the user triggers one more smash.
                    target.tick(delay);
                    sim.beginTick();
                    if (ROTATE) {
                        sim.move(sim.startY, false);
                    }
                    int up = sim.move(sim.startY + lift, false);
                    int down = sim.move(sim.startY, false);
                    checks.check(down == up + 1, "clearance " + clearance + ": bad sustained packet order");
                    int popped = sim.attack(delay);
                    checks.check(popped == 1,
                        "clearance " + clearance + ": sustained hit popped " + popped + " totems");
                    sustained++;
                    pops = target.pops;
                }

                checks.check(sim.rejected == 0,
                    "clearance " + clearance + ": " + sim.rejected + " move packet(s) rejected");
                checks.check(!sim.capViolation,
                    "clearance " + clearance + ": movement cap violated");
                checks.check(!sim.collisionViolation,
                    "clearance " + clearance + ": climb hitbox entered a block");
                checks.check(!sim.groundDuringClimb,
                    "clearance " + clearance + ": climb packet had onGround=true");
                checks.check(sim.fallResets > 0,
                    "clearance " + clearance + ": no ascent was observed");
                checks.check(pops == TOTEMS,
                    "clearance " + clearance + ": popped " + pops + "/" + TOTEMS
                        + " after " + sustained + " sustained triggers");
                result = "OK (burst=" + plan.hits().size() + " + sustained=" + sustained + ")";
            }

            System.out.printf(Locale.ROOT,
                "%9d  %5.2f  %5d  %5d  %9d  %4d  %7d  %s%n",
                clearance, lift, plan.hits().size(), delay, sustained,
                target.pops, sim.totalPackets, result);
        }
    }

    /**
     * The old implementation stacked several up/down cycles before one attack, assuming the
     * falls added together. On 1.21.11 each ascent calls {@code onLanding()} first, so the
     * fall at the attack is only the last climb. Demonstrate both halves of that claim.
     */
    private static void oldCycleFallacy(Checks checks) {
        final int clearance = 6;
        double lift = CaveBurst.effectiveLift(clearance, MOVE_STEP);
        SimTarget target = new SimTarget(1);
        ServerSim sim = new ServerSim(clearance, target);

        sim.beginTick();
        checks.check(Math.abs(sim.fallDistance) < EPS, "old-cycle: fall did not start at 0");
        for (int cycle = 0; cycle < 3; cycle++) {
            sim.move(sim.startY + lift, false);
            checks.check(Math.abs(sim.fallDistance) < EPS,
                "old-cycle: ascent " + (cycle + 1) + " did not reset fall");
            sim.move(sim.startY, false);
            checks.check(Math.abs(sim.fallDistance - lift) < 1e-6,
                "old-cycle: descent " + (cycle + 1) + " did not add exactly one lift");
        }
        int popped = sim.attack(0);

        double naiveFall = 3.0 * lift;
        double naiveDamage = CaveBurst.LOW_BASE_DAMAGE + CaveBurst.maceBonus(naiveFall);
        double actualDamage = sim.lastDamage;

        checks.check(Math.abs(sim.lastAttackFall - lift) < 1e-6,
            "old-cycle: stacked fall was " + sim.lastAttackFall + ", expected one lift " + lift);
        checks.check(popped == 0,
            "old-cycle: 3 stacked cycles should not pop a totem at this height");
        checks.check(naiveDamage >= CaveBurst.FIRST_HIT_DAMAGE,
            "old-cycle: the naive stacked model should have looked lethal");
        checks.check(actualDamage < CaveBurst.FIRST_HIT_DAMAGE,
            "old-cycle: actual one-lift damage should be non-lethal");

        System.out.printf(Locale.ROOT, "%nold 3x stacked cycles: lift=%.2f actualFall=%.2f "
                + "actualDamage=%.2f naiveFall=%.2f naiveDamage=%.2f popped=%d%n",
            lift, sim.lastAttackFall, actualDamage, naiveFall, naiveDamage, popped);
    }

    /**
     * Planner-only capacity check: a 20-block lift can fit two strictly increasing hits
     * under the packet cap. Production caps a cave climb at {@link CaveBurst#MAX_LIFT}, so
     * this is not reachable in game; it proves the second-hit escalation and packet-index
     * accounting are correct if the cap is ever raised.
     */
    private static void plannerCapacity(Checks checks) {
        double maxLift = 20.0;
        CaveBurst.BurstPlan plan = CaveBurst.burstPlan(TOTEMS, maxLift, 1);
        checks.check(plan.hits().size() == 2,
            "capacity: expected 2 hits, got " + plan.hits().size());

        if (plan.hits().size() == 2) {
            CaveBurst.Hit first = plan.hits().get(0);
            CaveBurst.Hit second = plan.hits().get(1);
            checks.check(first.upPacketIndex() == 2, "capacity: first up index " + first.upPacketIndex());
            checks.check(second.upPacketIndex() == 4, "capacity: second up index " + second.upPacketIndex());
            checks.check(Math.abs(first.height() - CaveBurst.FIRST_HIT_FALL) < 1e-9,
                "capacity: first height " + first.height());
            checks.check(second.height() > first.height(),
                "capacity: heights are not strictly increasing");
            checks.check(second.height() <= maxLift,
                "capacity: second height exceeds lift");

            int clearance = (int) Math.ceil(second.height() + CaveBurst.HITBOX_HEIGHT + 1.0);
            SimTarget target = new SimTarget(TOTEMS);
            ServerSim sim = new ServerSim(clearance, target);
            int pops = sim.runBurst(plan, true, checks);
            checks.check(sim.rejected == 0, "capacity: move packet rejected");
            checks.check(!sim.capViolation, "capacity: movement cap violated");
            checks.check(!sim.collisionViolation, "capacity: hitbox entered a block");
            checks.check(pops == 2, "capacity: expected 2 totem pops, got " + pops);

            System.out.printf(Locale.ROOT, "%nplanner capacity (maxLift=%.1f): %d hits, "
                    + "heights %.2f/%.2f, damage %.2f/%.2f, pops=%d, packets=%d%n",
                maxLift, plan.hits().size(), first.height(), second.height(),
                first.damage(), second.damage(), pops, sim.totalPackets);
        }
    }

    private static final class Checks {
        private int passed;
        private int failed;

        void check(boolean condition, String message) {
            if (condition) {
                passed++;
            } else {
                failed++;
                System.out.println("  FAIL " + message);
            }
        }

        boolean ok() {
            return failed == 0;
        }

        void summary() {
            System.out.println("checks: " + passed + " passed, " + failed + " failed");
            if (failed == 0) {
                System.out.println("ALL CHECKS PASSED");
            } else {
                System.out.println("SIMULATION FAILED");
            }
        }
    }

    /**
     * Minimal server player: position at the start of the tick, movement packet count,
     * fall distance, and the last attack result.
     */
    private static final class ServerSim {
        final double clearance;
        final double startY = 0.0;
        final SimTarget target;

        double playerY = startY;
        double fallDistance;
        int packetsThisTick;
        int totalPackets;
        int rejected;
        int fallResets;
        boolean capViolation;
        boolean collisionViolation;
        boolean groundDuringClimb;

        double lastAttackFall;
        double lastDamage;

        ServerSim(int clearance, SimTarget target) {
            this.clearance = clearance;
            this.target = target;
        }

        void beginTick() {
            // The previous climb ended with the down packet, so the tick-start position is
            // the same column. The module's real client may have moved a little; horizontal
            // drift is far below the 10-block cap (vertical cap is 8).
            if (Math.abs(playerY - startY) > EPS) {
                throw new IllegalStateException("tick did not start at the column: " + playerY);
            }
            packetsThisTick = 0;
        }

        /** Sends one movement packet and returns its 1-based index in this server tick. */
        int move(double y, boolean onGround) {
            int index = ++packetsThisTick;
            totalPackets++;
            if (onGround) {
                groundDuringClimb = true;
            }

            // ServerPlayNetworkHandler.onPlayerMove: q = packets since tick start, then
            // q = 1 once more than five arrived. p = squared delta from the tick start.
            int q = index <= 5 ? index : 1;
            double dy = y - startY;
            if (dy * dy > 100.0 * q + EPS) {
                capViolation = true;
                rejected++;
                playerY = startY; // requestTeleport back to the tick-start position
                return index;
            }
            if (!hitboxClear(y)) {
                collisionViolation = true;
                rejected++;
                playerY = startY; // moved wrongly -> requestTeleport
                return index;
            }

            double delta = y - playerY;
            playerY = y;
            if (delta > 0.0) {
                // boolean bl2 = m > 0; ... if (bl2) player.onLanding();
                fallDistance = 0.0;
                fallResets++;
            } else if (delta < 0.0) {
                // Entity.fall: fallDistance -= (float) heightDifference;
                fallDistance -= delta;
            }
            return index;
        }

        /** Sends the attack packet, applies damage, and mirrors MaceItem.postDamageEntity. */
        int attack(int ticksSinceLastAttack) {
            double base = CaveBurst.baseDamageAtTicks(ticksSinceLastAttack);
            double fall = (float) fallDistance;
            double damage = base + CaveBurst.maceBonus(fall);
            lastAttackFall = fall;
            lastDamage = damage;

            int before = target.pops;
            boolean landed = target.hurt(damage);
            if (landed) {
                fallDistance = 0.0; // MaceItem.postDamageEntity -> attacker.onLanding()
            }
            return target.pops - before;
        }

        int runBurst(CaveBurst.BurstPlan plan, boolean rotate, Checks checks) {
            beginTick();
            // runCaveBurst only sends the leading rotation when it has a burst hit; the
            // empty-plan branch just arms Sustained Drain.
            if (rotate && !plan.isEmpty()) {
                int rotationIndex = move(startY, false);
                checks.check(rotationIndex == 1, "burst: rotation was packet " + rotationIndex);
            }
            for (CaveBurst.Hit hit : plan.hits()) {
                int up = move(startY + hit.height(), false);
                checks.check(up == hit.upPacketIndex(),
                    "burst: ascent packet " + up + " != planned " + hit.upPacketIndex());
                checks.check(Math.abs(fallDistance) < EPS,
                    "burst: ascent at " + hit.height() + " did not reset fall");
                int down = move(startY, false);
                checks.check(down == up + 1, "burst: descent packet " + down + " != " + (up + 1));
                checks.check(Math.abs(fallDistance - hit.height()) < 1e-6,
                    "burst: fall " + fallDistance + " != climb " + hit.height()
                        + " after descent");
                int popped = attack(0);
                checks.check(popped == 1,
                    "burst: hit at " + hit.height() + " popped " + popped + " totems");
            }
            return target.pops;
        }

        /** Hitbox is the 0.6 x 1.8 player box; the column has {@code clearance} air blocks. */
        boolean hitboxClear(double feetY) {
            int topBlock = (int) Math.floor(feetY + CaveBurst.HITBOX_HEIGHT - 1e-9);
            return topBlock < clearance;
        }
    }

    /** Server target: health, absorption, hurt invulnerability, totems, totem regen. */
    private static final class SimTarget {
        double health = 20.0;
        double absorption;
        int totems;
        boolean alive = true;
        int pops;
        double lastDamageTaken;
        int timeUntilRegen;
        int hurtTime;
        int regenTicks;

        SimTarget(int totems) {
            this.totems = totems;
        }

        void tick(int amount) {
            for (int i = 0; i < amount; i++) {
                if (timeUntilRegen > 0) timeUntilRegen--;
                if (hurtTime > 0) hurtTime--;
                if (regenTicks > 0) {
                    regenTicks--;
                    // Regeneration II heals one health every 25 ticks.
                    if (regenTicks % 25 == 0 && health > 0.0) {
                        health = Math.min(20.0, health + 1.0);
                    }
                }
            }
        }

        /** LivingEntity.damage + tryUseDeathProtector. Returns whether damage was applied. */
        boolean hurt(double amount) {
            if (!alive) {
                return false;
            }

            if (timeUntilRegen > 10) {
                if (amount <= lastDamageTaken) {
                    return false;
                }
                double applied = amount - lastDamageTaken;
                lastDamageTaken = amount;
                applyDamage(applied);
            } else {
                lastDamageTaken = amount;
                timeUntilRegen = 20;
                hurtTime = 10;
                applyDamage(amount);
            }

            if (health <= 0.0) {
                if (totems > 0) {
                    totems--;
                    pops++;
                    health = 1.0;
                    absorption = 8.0; // Absorption II
                    regenTicks = 100; // Regeneration II, 5 seconds
                } else {
                    alive = false;
                }
            }
            return true;
        }

        /** Absorption soaks damage first; the rest hits health. */
        void applyDamage(double amount) {
            if (amount <= absorption) {
                absorption -= amount;
            } else {
                double rest = amount - absorption;
                absorption = 0.0;
                health -= rest;
            }
        }
    }
}
