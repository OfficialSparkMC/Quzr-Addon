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
 *       tick (reset to 1 after the 5th); endpoint acceptance via
 *       {@code isEntityNotCollidingWithBlocks} (the destination box is checked, not the
 *       path); vertical moved-wrongly error discarded by
 *       {@code if (m > -0.5 || m < 0.5) m = 0.0;} (tautological); {@code onLanding()} on
 *       every positive Y delta; and {@code Entity.fall} adding {@code -heightDifference}
 *       on a descent.</li>
 *   <li>{@code MaceItem.getBonusAttackDamage}: fall-to-damage curve.</li>
 *   <li>{@code PlayerEntity.attack} + {@code LivingEntity.damage}: attack charge,
 *       {@code hurtResistantTime}, partial damage from strictly increasing hits.</li>
 *   <li>{@code LivingEntity.tryUseDeathProtector}: totem at 1 health + Absorption II.</li>
 *   <li>{@code MaceItem.postDamageEntity}: attacker fall reset after a landed smash.</li>
 * </ul>
 *
 * <p>Assumptions: unarmored target, no critical hits, no Density/other smash enchantments,
 * no Resistance effect, zero horizontal velocity, and the player stays in the same clear
 * column. Those only reduce damage; the packet/fall/totem mechanics are exact. Run with
 * {@code ./gradlew caveBurstSimulation}.
 */
public final class CaveBurstSimulation {
    private static final int TOTEMS = 24;
    private static final int MAX_SUSTAINED = 40;

    private static final double EPS = 1e-9;

    public static void main(String[] args) {
        System.out.println("CaveBurst simulation: 1.21.11 destination acceptance + padded climb + mace + totem model");
        System.out.printf(Locale.ROOT,
            "totems=%d padding=%d maxLift=%.2f packetCap(5)=%.2f packetCap(6)=%.2f%n%n",
            TOTEMS, CaveBurst.CAVE_PAD_PACKETS, CaveBurst.MAX_LIFT,
            CaveBurst.packetCap(5), CaveBurst.packetCap(6));

        Checks checks = new Checks();
        destinationScanProof(checks);
        lowCaveSustainedProof(checks);
        impossibleCaveProof(checks);
        paddingProtocolProof(checks);
        oldCycleFallacy(checks);
        plannerSweep(checks);
        sustainedDelaySweep(checks);

        System.out.println();
        checks.summary();
        if (!checks.ok()) {
            System.exit(1);
        }
    }

    /**
     * The reported failure: a low cave with a thick roof and an air pocket above it. The
     * old contiguous-only scan sees only the 2-block cave and refuses; the destination scan
     * accepts the pocket endpoint even though the vertical path crosses the roof.
     */
    private static void destinationScanProof(Checks checks) {
        CaveColumn column = new CaveColumn(
            new int[]{-64, -1}, // floor
            new int[]{2, 7},    // thick roof
            new int[]{21, 64}   // ceiling above the pocket (pocket is blocks 8..20)
        );
        double contiguous = column.contiguousLift();
        double lift = column.moduleLift();

        System.out.printf(Locale.ROOT,
            "low cave + thick roof + air pocket: contiguous=%.2f destination=%.2f%n",
            contiguous, lift);

        checks.check(contiguous < CaveBurst.FIRST_HIT_FALL,
            "destination: contiguous lift " + contiguous + " should be unusable");
        checks.check(CaveBurst.burstPlan(TOTEMS, contiguous, CaveBurst.CAVE_PAD_PACKETS).isEmpty(),
            "destination: old contiguous-only plan was not empty");
        checks.check(CaveBurst.sustainedDelay(contiguous) < 0,
            "destination: old contiguous-only sustained path was not blocked");
        checks.check(lift > contiguous + 10.0,
            "destination: scan did not find the air pocket (lift=" + lift + ")");
        checks.check(lift <= CaveBurst.MAX_LIFT + EPS,
            "destination: lift " + lift + " exceeds MAX_LIFT");
        checks.check(!column.hitboxClear(CaveBurst.FIRST_HIT_FALL),
            "destination: minimum lethal height should be inside the roof");
        checks.check(column.hitboxClear(lift),
            "destination: selected endpoint is not clear");

        CaveBurst.BurstPlan plan = CaveBurst.burstPlan(
            TOTEMS, lift, CaveBurst.CAVE_PAD_PACKETS);
        checks.check(plan.hits().size() == 1,
            "destination: expected one padded burst hit, got " + plan.hits().size());
        if (plan.hits().size() == 1) {
            CaveBurst.Hit hit = plan.hits().get(0);
            checks.check(Math.abs(hit.height() - lift) < 1e-9,
                "destination: first climb " + hit.height() + " is not the verified endpoint " + lift);
            checks.check(hit.upPacketIndex() == 5,
                "destination: ascent packet " + hit.upPacketIndex() + " != 5");
        }

        SimTarget target = new SimTarget(TOTEMS);
        ServerSim sim = new ServerSim(column, target);
        int pops = sim.runBurst(plan, checks, "destination");
        checks.check(sim.roofCrossings > 0,
            "destination: burst climb did not cross the roof");

        int sustained = 0;
        int delay = CaveBurst.sustainedDelay(lift);
        while (pops < TOTEMS && sustained < MAX_SUSTAINED) {
            target.tick(delay);
            pops = sim.sustainedClimb(lift, delay, checks, "destination");
            sustained++;
        }

        checks.check(sim.rejected == 0,
            "destination: " + sim.rejected + " move packet(s) rejected");
        checks.check(!sim.capViolation,
            "destination: movement cap violated");
        checks.check(!sim.collisionViolation,
            "destination: climb endpoint collided with a block");
        checks.check(!sim.groundDuringClimb,
            "destination: climb packet had onGround=true");
        checks.check(sim.fallResets > 0,
            "destination: no ascent was observed");
        checks.check(pops == TOTEMS,
            "destination: popped " + pops + "/" + TOTEMS + " after " + sustained + " sustained triggers");

        System.out.printf(Locale.ROOT,
            "  burst=1 sustained=%d pops=%d packets=%d roofCrossings=%d result=%s%n",
            sustained, pops, sim.totalPackets, sim.roofCrossings,
            pops == TOTEMS && sim.rejected == 0 ? "OK" : "FAILED");
    }

    /**
     * A seven-block-tall cave has no air pocket, so the burst cannot fit. The padded
     * sustained climb is still lethal once the mace has charged enough; prove all 24 totems
     * pop one cooldown window at a time.
     */
    private static void lowCaveSustainedProof(Checks checks) {
        CaveColumn column = new CaveColumn(
            new int[]{-64, -1},
            new int[]{7, 64}
        );
        double lift = column.moduleLift();
        int delay = CaveBurst.sustainedDelay(lift);
        CaveBurst.BurstPlan plan = CaveBurst.burstPlan(
            TOTEMS, lift, CaveBurst.CAVE_PAD_PACKETS);

        System.out.printf(Locale.ROOT,
            "low cave without pocket: contiguous=%.2f destination=%.2f delay=%d%n",
            column.contiguousLift(), lift, delay);

        checks.check(plan.isEmpty(),
            "low cave: burst should be empty, got " + plan.hits().size() + " hit(s)");
        checks.check(delay >= 22 && delay <= 60,
            "low cave: sustained delay " + delay + " outside 22..60");
        checks.check(CaveBurst.baseDamageAtTicks(delay) + CaveBurst.maceBonus(lift)
                >= CaveBurst.FIRST_HIT_DAMAGE,
            "low cave: sustained hit is not lethal after the computed charge");

        SimTarget target = new SimTarget(TOTEMS);
        ServerSim sim = new ServerSim(column, target);
        int pops = target.pops;
        int sustained = 0;
        while (pops < TOTEMS && sustained < MAX_SUSTAINED) {
            target.tick(delay);
            pops = sim.sustainedClimb(lift, delay, checks, "low cave");
            sustained++;
        }

        checks.check(sim.rejected == 0,
            "low cave: " + sim.rejected + " move packet(s) rejected");
        checks.check(!sim.capViolation,
            "low cave: movement cap violated");
        checks.check(pops == TOTEMS,
            "low cave: popped " + pops + "/" + TOTEMS + " after " + sustained + " triggers");
        System.out.printf(Locale.ROOT,
            "  sustained=%d pops=%d packets=%d result=%s%n",
            sustained, pops, sim.totalPackets, pops == TOTEMS ? "OK" : "FAILED");
    }

    /**
     * A three-block-tall sealed cave cannot produce the 4.25-block fall a fully charged
     * mace needs. The module must refuse instead of sending a climb the server rejects.
     */
    private static void impossibleCaveProof(Checks checks) {
        CaveColumn column = new CaveColumn(
            new int[]{-64, -1},
            new int[]{3, 64}
        );
        double lift = column.moduleLift();
        int delay = CaveBurst.sustainedDelay(lift);
        CaveBurst.BurstPlan plan = CaveBurst.burstPlan(
            TOTEMS, lift, CaveBurst.CAVE_PAD_PACKETS);

        System.out.printf(Locale.ROOT,
            "sealed 3-high cave: lift=%.2f delay=%d burst=%d%n",
            lift, delay, plan.hits().size());

        checks.check(lift < CaveBurst.MIN_LETHAL_FALL,
            "impossible: lift " + lift + " should be below the full-charge minimum");
        checks.check(plan.isEmpty(),
            "impossible: burst should be empty");
        checks.check(delay < 0,
            "impossible: sustained delay should be -1, got " + delay);

        SimTarget target = new SimTarget(TOTEMS);
        ServerSim sim = new ServerSim(column, target);
        checks.check(sim.totalPackets == 0 && sim.rejected == 0,
            "impossible: module sent packets despite refusing");
    }

    /**
     * Proves the packet ordering: four no-op packets, ascent at index 5 (22.36-block cap),
     * descent at index 6, attack back at the start position. Also shows that an 18-block
     * climb without padding is rejected by the 10-block first-packet cap.
     */
    private static void paddingProtocolProof(Checks checks) {
        CaveColumn column = new CaveColumn(new int[]{-64, -1});
        double lift = CaveBurst.MAX_LIFT;
        SimTarget target = new SimTarget(1);
        ServerSim sim = new ServerSim(column, target);

        sim.beginTick();
        int pad = sim.pad(CaveBurst.CAVE_PAD_PACKETS);
        int up = sim.move(lift, false, true);
        int down = sim.move(sim.startY, false, false);
        int popped = sim.attack(0);

        System.out.printf(Locale.ROOT,
            "padding protocol: pad=%d up=%d down=%d fall=%.2f popped=%d%n",
            pad, up, down, sim.lastAttackFall, popped);

        checks.check(pad == 4,
            "padding: last no-op packet was " + pad);
        checks.check(up == 5,
            "padding: ascent packet was " + up + ", expected 5");
        checks.check(CaveBurst.MAX_LIFT <= CaveBurst.packetCap(5) + EPS,
            "padding: MAX_LIFT exceeds the packet-5 cap");
        checks.check(CaveBurst.MAX_LIFT > CaveBurst.packetCap(1),
            "padding: MAX_LIFT does not require the packet-5 cap");
        checks.check(Math.abs(sim.lastUpDisplacement - lift) < 1e-9,
            "padding: ascent displacement " + sim.lastUpDisplacement + " != " + lift);
        checks.check(down == 6,
            "padding: descent packet was " + down + ", expected 6");
        checks.check(Math.abs(sim.lastAttackFall - lift) < 1e-6,
            "padding: attack fall " + sim.lastAttackFall + " != climb " + lift);
        checks.check(Math.abs(sim.playerY - sim.startY) < EPS,
            "padding: attack was not sent from the start position");
        checks.check(popped == 1,
            "padding: climb+attack did not pop the totem");
        checks.check(sim.rejected == 0,
            "padding: padded climb was rejected");

        // Negative control: the same 18-block climb as packet 1 is over the 10-block cap.
        ServerSim bare = new ServerSim(column, new SimTarget(1));
        bare.beginTick();
        int bareUp = bare.move(18.0, false, false);
        checks.check(bareUp == 1 && bare.rejected == 1 && bare.capViolation,
            "padding: unpadded 18-block climb was not rejected by the first-packet cap");
    }

    /**
     * The old implementation stacked several up/down cycles before one attack, assuming the
     * falls added together. On 1.21.11 each ascent calls {@code onLanding()} first, so the
     * fall at the attack is only the last climb. Demonstrate both halves of that claim.
     */
    private static void oldCycleFallacy(Checks checks) {
        CaveColumn column = new CaveColumn(
            new int[]{-64, -1},
            new int[]{6, 64}
        );
        double lift = column.moduleLift();
        SimTarget target = new SimTarget(1);
        ServerSim sim = new ServerSim(column, target);

        sim.beginTick();
        checks.check(Math.abs(sim.fallDistance) < EPS, "old-cycle: fall did not start at 0");
        for (int cycle = 0; cycle < 3; cycle++) {
            sim.move(lift, false, true);
            checks.check(Math.abs(sim.fallDistance) < EPS,
                "old-cycle: ascent " + (cycle + 1) + " did not reset fall");
            sim.move(sim.startY, false, false);
            checks.check(Math.abs(sim.fallDistance - lift) < 1e-6,
                "old-cycle: descent " + (cycle + 1) + " did not add exactly one lift");
        }
        int popped = sim.attack(0);

        double naiveFall = 3.0 * lift;
        double naiveDamage = CaveBurst.LOW_BASE_DAMAGE + CaveBurst.maceBonus(naiveFall);
        double actualDamage = sim.lastDamage;

        checks.check(Math.abs(sim.lastAttackFall - lift) < 1e-6,
            "old-cycle: stacked fall was " + sim.lastAttackFall + ", expected one lift " + lift);
        checks.check(sim.fallResets == 3,
            "old-cycle: expected 3 ascent resets, got " + sim.fallResets);
        checks.check(popped == 0,
            "old-cycle: 3 stacked cycles should not pop a totem at this height");
        checks.check(naiveDamage >= CaveBurst.FIRST_HIT_DAMAGE,
            "old-cycle: the naive stacked model should have looked lethal");
        checks.check(actualDamage < CaveBurst.FIRST_HIT_DAMAGE,
            "old-cycle: actual one-lift damage should be non-lethal");

        System.out.printf(Locale.ROOT,
            "old 3x stacked cycles: lift=%.2f actualFall=%.2f actualDamage=%.2f "
                + "naiveFall=%.2f naiveDamage=%.2f popped=%d%n",
            lift, sim.lastAttackFall, actualDamage, naiveFall, naiveDamage, popped);
    }

    /**
     * With four padding packets only the packet-5 ascent can exceed 10 blocks; every later
     * ascent is q-reset to 10. Sweep the whole usable lift range and prove the planner never
     * emits a second hit, and that a non-empty plan always climbs the full verified
     * destination (never a minimum height that could sit inside a roof).
     */
    private static void plannerSweep(Checks checks) {
        System.out.println();
        System.out.println("planner sweep (4 padding packets)");
        for (int step = 80; step <= 440; step++) {
            double lift = step * 0.05;
            CaveBurst.BurstPlan plan = CaveBurst.burstPlan(
                TOTEMS, lift, CaveBurst.CAVE_PAD_PACKETS);

            if (lift < CaveBurst.FIRST_HIT_FALL - EPS) {
                checks.check(plan.isEmpty(),
                    "sweep: lift " + lift + " should have no burst");
                continue;
            }

            checks.check(plan.hits().size() == 1,
                "sweep: lift " + lift + " produced " + plan.hits().size() + " hits");
            if (plan.hits().isEmpty()) continue;

            CaveBurst.Hit hit = plan.hits().get(0);
            checks.check(Math.abs(hit.height() - lift) < 1e-6,
                "sweep: lift " + lift + " climbed minimum height " + hit.height());
            checks.check(hit.upPacketIndex() == 5,
                "sweep: lift " + lift + " ascent packet " + hit.upPacketIndex());
            checks.check(hit.height() <= CaveBurst.MAX_LIFT + EPS,
                "sweep: lift " + lift + " exceeds MAX_LIFT");
        }

        double[] samples = {4.0, 6.6, 6.65, 7.2, 12.0, 22.0};
        for (double lift : samples) {
            CaveBurst.BurstPlan plan = CaveBurst.burstPlan(
                TOTEMS, lift, CaveBurst.CAVE_PAD_PACKETS);
            System.out.printf(Locale.ROOT, "  lift=%5.2f hits=%d height=%.2f%n",
                lift, plan.hits().size(),
                plan.hits().isEmpty() ? 0.0 : plan.hits().get(0).height());
        }
    }

    /** Every sustained delay must be long enough for its own fall, and stay in 22..60. */
    private static void sustainedDelaySweep(Checks checks) {
        double[] lifts = {22.0, 18.0, 12.0, 8.0, 6.7, 5.2, 4.6};
        System.out.println();
        System.out.println("lift   delay  chargeBase  totalDamage  lethal");
        System.out.println("-----  -----  ----------  -----------  ------");

        for (double lift : lifts) {
            int delay = CaveBurst.sustainedDelay(lift);
            double base = CaveBurst.baseDamageAtTicks(delay);
            double damage = base + CaveBurst.maceBonus(lift);
            boolean lethal = damage >= CaveBurst.FIRST_HIT_DAMAGE;

            checks.check(delay >= 22 && delay <= 60,
                "delay: lift " + lift + " produced " + delay);
            checks.check(lethal,
                "delay: lift " + lift + " damage " + damage + " is not lethal");

            System.out.printf(Locale.ROOT,
                "%5.2f  %5d  %10.2f  %11.2f  %s%n",
                lift, delay, base, damage, lethal ? "yes" : "NO");
        }

        checks.check(CaveBurst.sustainedDelay(4.2) < 0,
            "delay: 4.2-block lift should be impossible for a full-charge mace");
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
     * Block column for the model. Solid blocks are full cubes addressed by integer Y; the
     * player is always at the same X/Z, so the 0.6-wide column is constant.
     */
    private static final class CaveColumn {
        private final int[][] solidRanges;

        CaveColumn(int[]... solidRanges) {
            this.solidRanges = solidRanges;
        }

        boolean solid(int y) {
            for (int[] range : solidRanges) {
                if (y >= range[0] && y <= range[1]) return true;
            }
            return false;
        }

        /** Destination box check, matching isEntityNotCollidingWithBlocks' contracted box. */
        boolean hitboxClear(double feetY) {
            double minY = feetY + 1.0E-5;
            double maxY = feetY + CaveBurst.HITBOX_HEIGHT - 1.0E-5;
            int y0 = (int) Math.floor(minY);
            int y1 = (int) Math.floor(maxY);
            for (int y = y0; y <= y1; y++) {
                if (solid(y)) return false;
            }
            return true;
        }

        /** Highest collision-clear destination, scanned down from MAX_LIFT in 0.05 steps. */
        double highestDestination() {
            for (double lift = CaveBurst.MAX_LIFT; lift > 0.01; lift -= 0.05) {
                if (hitboxClear(lift)) return lift;
            }
            return 0.0;
        }

        /** Contiguous air blocks above the start (Y=0 in this model). */
        int contiguousClearance() {
            int y = 0;
            while (y < 512 && !solid(y)) y++;
            return y;
        }

        /** Module fallback: contiguous clearance capped and shrunk to a clear endpoint. */
        double contiguousLift() {
            double lift = CaveBurst.effectiveLift(
                contiguousClearance(), CaveBurst.MAX_LIFT);
            while (lift > 0.01 && !hitboxClear(lift)) {
                lift -= 0.05;
            }
            return Math.max(0.0, lift);
        }

        /** Mirrors MaceAttect.caveLift(): destination primary, contiguous fallback. */
        double moduleLift() {
            return Math.max(highestDestination(), contiguousLift());
        }

        /** Number of solid blocks whose volume lies between two feet positions. */
        int solidBlocksCrossed(double fromY, double toY) {
            int y0 = (int) Math.floor(Math.min(fromY, toY));
            int y1 = (int) Math.floor(Math.max(fromY, toY) - 1.0E-9);
            int count = 0;
            for (int y = y0; y <= y1; y++) {
                if (solid(y)) count++;
            }
            return count;
        }
    }

    /**
     * Minimal server player: tick-start position, movement packet count, fall distance, and
     * the last attack result. Every synthetic packet in a climb belongs to one server tick.
     */
    private static final class ServerSim {
        final CaveColumn column;
        final double startY = 0.0;
        final SimTarget target;

        double playerY = startY;
        double fallDistance;
        int packetsThisTick;
        int totalPackets;
        int rejected;
        int fallResets;
        int roofCrossings;
        boolean capViolation;
        boolean collisionViolation;
        boolean groundDuringClimb;

        double lastUpDisplacement;
        double lastAttackFall;
        double lastDamage;

        ServerSim(CaveColumn column, SimTarget target) {
            this.column = column;
            this.target = target;
        }

        void beginTick() {
            // The previous climb ended with the down packet, so the tick-start position is
            // the same column.
            if (Math.abs(playerY - startY) > EPS) {
                throw new IllegalStateException("tick did not start at the column: " + playerY);
            }
            packetsThisTick = 0;
        }

        /** Sends one movement packet and returns its 1-based index in this server tick. */
        int move(double y, boolean onGround, boolean trackPath) {
            int index = ++packetsThisTick;
            totalPackets++;
            if (onGround) {
                groundDuringClimb = true;
            }

            double packetStartY = playerY;

            // ServerPlayNetworkHandler.onPlayerMove: q = packets since tick start, then
            // q = 1 once more than five arrived. p = squared delta from the tick start.
            int q = index <= 5 ? index : 1;
            double dy = y - startY;
            if (dy * dy > 100.0 * q + EPS) {
                capViolation = true;
                rejected++;
                playerY = packetStartY; // requestTeleport back to the packet-start position
                return index;
            }
            // Endpoint acceptance only. The vertical path is deliberately NOT checked: the
            // server checks the destination box and discards vertical moved-wrongly error.
            if (!column.hitboxClear(y)) {
                collisionViolation = true;
                rejected++;
                playerY = packetStartY;
                return index;
            }

            if (trackPath) {
                roofCrossings += column.solidBlocksCrossed(packetStartY, y);
            }

            double delta = y - packetStartY;
            if (index == 5) {
                lastUpDisplacement = y - startY;
            }
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

        /** CAVE_PAD_PACKETS no-op movement packets (OnGroundOnly(false, false)). */
        int pad(int count) {
            int index = 0;
            for (int i = 0; i < count; i++) {
                index = move(startY, false, false);
            }
            return index;
        }

        /** Sends the attack packet, applies damage, and mirrors MaceItem.postDamageEntity. */
        int attack(int ticksSinceLastAttack) {
            if (Math.abs(playerY - startY) > EPS) {
                throw new IllegalStateException("attack was sent away from the target: " + playerY);
            }
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

        int runBurst(CaveBurst.BurstPlan plan, Checks checks, String label) {
            if (plan.isEmpty()) return target.pops;

            beginTick();
            int pad = pad(CaveBurst.CAVE_PAD_PACKETS);
            checks.check(pad == CaveBurst.CAVE_PAD_PACKETS,
                label + ": burst padding ended at packet " + pad);
            for (CaveBurst.Hit hit : plan.hits()) {
                int up = move(startY + hit.height(), false, true);
                checks.check(up == hit.upPacketIndex(),
                    label + ": ascent packet " + up + " != planned " + hit.upPacketIndex());
                checks.check(Math.abs(fallDistance) < EPS,
                    label + ": ascent at " + hit.height() + " did not reset fall");
                int down = move(startY, false, false);
                checks.check(down == up + 1,
                    label + ": descent packet " + down + " != " + (up + 1));
                checks.check(Math.abs(fallDistance - hit.height()) < 1e-6,
                    label + ": fall " + fallDistance + " != climb " + hit.height()
                        + " after descent");
                int popped = attack(0);
                checks.check(popped == 1,
                    label + ": hit at " + hit.height() + " popped " + popped + " totems");
            }
            return target.pops;
        }

        /** One independent padded sustained climb in a fresh server tick. */
        int sustainedClimb(double lift, int ticksSinceLastAttack, Checks checks, String label) {
            beginTick();
            int pad = pad(CaveBurst.CAVE_PAD_PACKETS);
            checks.check(pad == CaveBurst.CAVE_PAD_PACKETS,
                label + ": sustained padding ended at packet " + pad);
            int up = move(startY + lift, false, true);
            checks.check(up == 5,
                label + ": sustained ascent packet " + up + " != 5");
            checks.check(Math.abs(fallDistance) < EPS,
                label + ": sustained ascent did not reset fall");
            int down = move(startY, false, false);
            checks.check(down == 6,
                label + ": sustained descent packet " + down + " != 6");
            checks.check(Math.abs(fallDistance - lift) < 1e-6,
                label + ": sustained fall " + fallDistance + " != climb " + lift);
            int popped = attack(ticksSinceLastAttack);
            checks.check(popped == 1,
                label + ": sustained hit popped " + popped + " totems");
            return target.pops;
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
