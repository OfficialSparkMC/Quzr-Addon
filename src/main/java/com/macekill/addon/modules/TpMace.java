package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.BurstHeights;
import com.macekill.addon.modules.macekill.Combat;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 百米重锤 - 融合 MaceDMGPlus + TpAura + XTpaura
 * 核心功能：VClip搜索安全起跳位置 → TP到目标上方 → 模拟掉落 → 攻击
 * 支持图腾绕过、静默切换、空气检测、最大伤害钳制
 */
public class TpMace extends Module {
    private static Field entityIdField;

    // ==================== 设置组 ====================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgExploit = settings.createGroup("Attack");
    private final SettingGroup sgTotem = settings.createGroup("Totem Bypass");
    private final SettingGroup sgTarget = settings.createGroup("Target");

    // ---- 通用 ----
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("Range").description("Distance to search for entities")
            .defaultValue(20).min(1).max(200).sliderRange(1, 128).build()
    );

    private final Setting<Double> moveDistance = sgGeneral.add(new DoubleSetting.Builder()
            .name("Move Step").description("Max distance per movement packet")
            .defaultValue(8).min(1).max(128).sliderRange(1, 128).build()
    );

    private final Setting<Integer> fallPackets = sgExploit.add(new IntSetting.Builder()
            .name("Fall Packets").description("Rotation-only packets (onGround=false) sent before each hit to prime the fake fall distance. Required to fake large falls on vanilla/strict servers; harmless on Paper. 0 = off.")
            .defaultValue(4).min(0).max(17).sliderMax(17).build()
    );

    private final Setting<Integer> attackDelay = sgGeneral.add(new IntSetting.Builder()
            .name("Attack Delay").description("Attack interval in ticks")
            .defaultValue(10).min(0).max(40).sliderMax(40).build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
            .name("Auto Switch").description("Auto-switch to mace").defaultValue(true).build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("Rotate").description("Face target when attacking").defaultValue(true).build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
            .name("Swing Hand").description("Swing hand client-side on attack").defaultValue(false).build()
    );

    private final Setting<Boolean> returnPos = sgGeneral.add(new BoolSetting.Builder()
            .name("Return to Start").description("Return to original position after attack").defaultValue(false).build()
    );

    // ---- 攻击 ----
    private final Setting<Boolean> maxPower = sgExploit.add(new BoolSetting.Builder()
            .name("Max Damage").description("Use max safe height (170) when on, set height when off")
            .defaultValue(true).build()
    );

    private final Setting<Integer> fallHeight = sgExploit.add(new IntSetting.Builder()
            .name("Attack Height").description("Fall height (mace damage) used for the attack. Used directly when Max Damage is off; used as the search cap when Max Damage is on")
            .defaultValue(170).min(1).max(170).sliderRange(1, 170)
            .visible(() -> !maxPower.get()).build()
    );

    private final Setting<Boolean> airCheck = sgExploit.add(new BoolSetting.Builder()
            .name("Air Check").description("Only attack if there is enough air above the target")
            .defaultValue(true).build()
    );

    private final Setting<Boolean> silentSwap = sgExploit.add(new BoolSetting.Builder()
            .name("Silent Swap").description("Swap to mace without sending a slot packet").defaultValue(false).build()
    );

    // ---- 图腾绕过 ----
    private final Setting<Boolean> totemBypass = sgTotem.add(new BoolSetting.Builder()
            .name("Totem Bypass").description("Drain totems with multi-height hits, then kill at full height (instant, 1 tick)")
            .defaultValue(false).build()
    );

    private final Setting<Boolean> detectTotem = sgTotem.add(new BoolSetting.Builder()
            .name("Detect Totem").description("Only drain if the target is actually holding a totem")
            .defaultValue(true).visible(totemBypass::get).build()
    );

    private final Setting<DrainMode> drainMode = sgTotem.add(new EnumSetting.Builder<DrainMode>()
            .name("Drain Mode").description("Spread: even heights to pop N totems | List: custom list, extended upward | Incremental: base + step per hit")
            .defaultValue(DrainMode.SPREAD).visible(totemBypass::get).build()
    );

    private final Setting<List<String>> drainHeights = sgTotem.add(new StringListSetting.Builder()
            .name("Drain Heights").description("Heights used to drain totems before the kill")
            .defaultValue("4", "8", "12", "16")
            .visible(() -> totemBypass.get() && drainMode.get() == DrainMode.LIST).build()
    );

    private final Setting<Integer> baseDrainHeight = sgTotem.add(new IntSetting.Builder()
            .name("Base Drain Height").description("Starting height for incremental drain attacks")
            .defaultValue(4).min(1).max(50).sliderMax(50)
            .visible(() -> totemBypass.get() && drainMode.get() == DrainMode.INCREMENTAL).build()
    );

    private final Setting<Integer> heightIncrement = sgTotem.add(new IntSetting.Builder()
            .name("Height Increment").description("Added height per drain attack")
            .defaultValue(4).min(1).max(20).sliderMax(20)
            .visible(() -> totemBypass.get() && drainMode.get() == DrainMode.INCREMENTAL).build()
    );

    private final Setting<Integer> totemAttacks = sgTotem.add(new IntSetting.Builder()
            .name("Totem Attacks").description("Number of drain attacks before the kill")
            .defaultValue(4).min(1).max(15).sliderMax(15)
            .visible(totemBypass::get).build()
    );

    private final Setting<Integer> totemsToPop = sgTotem.add(new IntSetting.Builder()
            .name("Totems To Pop").description("How many totems to pop (1-198)")
            .defaultValue(24).min(1).max(198).sliderMax(198)
            .visible(totemBypass::get).build()
    );

    private final Setting<Double> heightStep = sgTotem.add(new DoubleSetting.Builder()
            .name("Height Step").description("Minimum separation between burst heights in blocks. 1.0 = classic 1-block steps; 0.5/0.25 packs big bursts under low cave roofs with far fewer packets")
            .defaultValue(1.0).min(0.25).max(2.0).sliderRange(0.25, 2.0)
            .visible(totemBypass::get).build()
    );

    private final Setting<Boolean> sustainedDrain = sgTotem.add(new BoolSetting.Builder()
            .name("Sustained Drain").description("After a burst that couldn't finish stacked totems (server damage cap / dropped packets), keep popping one per 22 ticks until the target dies or Max Sustained Hits runs out. No escalation needed — each hit only must be lethal on its own")
            .defaultValue(true).visible(totemBypass::get).build()
    );

    private final Setting<Integer> maxSustainedHits = sgTotem.add(new IntSetting.Builder()
            .name("Max Sustained Hits").description("Cap for sustained follow-up hits after the burst")
            .defaultValue(40).min(1).max(200).sliderMax(200)
            .visible(() -> totemBypass.get() && sustainedDrain.get()).build()
    );

    private final Setting<Boolean> singleTick = sgTotem.add(new BoolSetting.Builder()
            .name("Single Tick").description("Totem bypass always fires the whole hit list in one mace smash (required to beat AutoTotem, which re-equips between ticks)")
            .defaultValue(true).visible(() -> false).build()
    );

    private final Setting<Integer> bypassHitsPerTick = sgTotem.add(new IntSetting.Builder()
            .name("Hits Per Tick").description("Unused - totem bypass is always a single smash")
            .defaultValue(1).min(1).max(20).sliderMax(20)
            .visible(() -> false).build()
    );

    // ---- 目标 ----
    private enum ListMode { Off, Whitelist, Blacklist }

    private final Setting<Boolean> players = sgTarget.add(new BoolSetting.Builder()
            .name("Players").description("Attack players").defaultValue(true).build()
    );
    private final Setting<Boolean> entities = sgTarget.add(new BoolSetting.Builder()
            .name("Entities").description("Attack living entities").defaultValue(false).build()
    );
    private final Setting<Boolean> throughWalls = sgTarget.add(new BoolSetting.Builder()
            .name("Through Walls").description("Attack through walls").defaultValue(false).build()
    );
    private final Setting<Boolean> ignoreNamed = sgTarget.add(new BoolSetting.Builder()
            .name("Ignore Named").description("Ignore entities with custom names").defaultValue(false).build()
    );
    private final Setting<ListMode> listMode = sgTarget.add(new EnumSetting.Builder<ListMode>()
            .name("List Mode").description("Whitelist or blacklist").defaultValue(ListMode.Off).build()
    );
    private final Setting<String> playerList = sgTarget.add(new StringSetting.Builder()
            .name("Player List").description("Comma separated").defaultValue("")
            .visible(() -> listMode.get() != ListMode.Off).build()
    );

    // ==================== 状态 ====================
    private enum Phase { IDLE, DELAY, RETURN_DELAY }
    private Phase phase = Phase.IDLE;
    private int delayTicks;
    private int attackCount;
    private Vec3d originalPos;
    private double lastSentX, lastSentY, lastSentZ;
    private LivingEntity target;
    private int originalSlot = -1;
    private int maceSlot = -1;
    private int maceSwapBackSlot = -1;
    private int silentRevertSlot = -1;
    private boolean noMaceWarned = false;
    private List<String> bypassHeights;
    private int bypassIdx;
    private float preHealth;
    private int freeCooldown = 0;
    private boolean firstAttack = true;
    // Sustained-drain chain: after a burst that couldn't finish stacked totems, keep
    // popping one per invuln window. Survives resetState (cleared only on deactivate,
    // target change, expiry, or exhaustion).
    private LivingEntity sustainTarget;
    private int sustainLeft;
    private int sustainCooldown;
    private long sustainArmedAt;
    private static final int SUSTAIN_DELAY = 22;
    private static final long SUSTAIN_TTL_NANOS = 120_000_000_000L;

    public TpMace() {
        super(MaceKillAddon.CATEGORY, "TpMace", "Long-range mace - TP teleport+totem bypass+silent swap");
    }

    @Override
    public void onActivate() {
        // Skip the initial attackDelay on the very first target so the smash fires immediately
        // after enabling, instead of waiting ~0.5s.
        firstAttack = true;
    }

    @Override
    public void onDeactivate() {
        // Restore a swapped-in mace before clearing state, otherwise the inventory is left rearranged.
        if (maceSwapBackSlot >= 0 && originalSlot >= 0 && mc.player != null && mc.interactionManager != null) {
            InvUtils.move().from(originalSlot).to(maceSwapBackSlot);
            maceSwapBackSlot = -1;
        }
        // Revert a pending silent-swap server selection so the slot stays in sync.
        revertSilentSwap();
        phase = Phase.IDLE;
        target = null;
        originalPos = null;
        originalSlot = -1;
        maceSlot = -1;
        silentRevertSlot = -1;
        attackCount = 0;
        lastSentX = 0;
        lastSentY = 0;
        lastSentZ = 0;
        preHealth = 0;
        freeCooldown = 0;
        bypassHeights = null;
        bypassIdx = 0;
        sustainTarget = null;
        sustainLeft = 0;
        sustainCooldown = 0;
        sustainArmedAt = 0;
    }

    // ==================== Tick ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (phase) {
            case IDLE -> tickIdle();
            case DELAY -> tickDelay();
            case RETURN_DELAY -> tickReturnDelay();
        }
    }

    private void tickIdle() {
        int tickDelay = totemBypass.get() ? 0 : attackDelay.get();
        if (!firstAttack) {
            delayTicks++;
            if (delayTicks < tickDelay) return;
        }
        delayTicks = 0;
        firstAttack = false;

        // After an unhittable attempt we pause briefly so the player can move freely.
        if (freeCooldown > 0) {
            freeCooldown--;
            return;
        }

        // Sustained-drain pacing: one single per 22 ticks (past hurt invuln).
        if (sustainCooldown > 0) {
            sustainCooldown--;
            return;
        }

        target = findTarget();
        if (target == null) return;
        // Chain belongs to one target for 120s max — a new target (or stale chain)
        // starts over with a full burst instead of lone singles.
        if (target != sustainTarget || !sustainedDrain.get()
                || System.nanoTime() - sustainArmedAt > SUSTAIN_TTL_NANOS) {
            sustainTarget = null;
            sustainLeft = 0;
        }

        if (!checkAndSwapWeapon()) return;
        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        lastSentY = originalPos.y;
        attackCount = 0;
        preHealth = target.getHealth() + target.getAbsorptionAmount();

        try {
            if (totemBypass.get() && target instanceof PlayerEntity p
                    && (!detectTotem.get() || targetHasTotem(p))) {
                // Sustained chain: the burst already ran on this target and it survived
                // (stacked totems past a damage cap / dropped packets) — one lethal single
                // per invuln window, no escalation needed.
                if (sustainedDrain.get() && target == sustainTarget && sustainLeft > 0) {
                    sustainedSingle(p);
                    return;
                }
                int totems = totemsToPop.get();
                if (detectTotem.get()) totems = Math.min(totems, countTotems(p));
                totems = Math.min(totems + 3, Combat.MAX_TOTEM_HITS);
                // AutoTotem re-equips between ticks, so the whole strictly-escalating hit list
                // MUST land in a single tick (one mace smash) or the target always survives.
                // We pierce any roof (world top is the only limit), so this works inside caves:
                // the ascent/descent steps jump past ceiling blocks instead of landing in them.
                int maxH = Math.min(Combat.MAX_TOTEM_HITS,
                    Combat.worldTop(mc) - (int) Math.floor(target.getY()) - 1);
                if (maxH < 8) maxH = 8;
                List<Double> all = BurstHeights.build(totems, maxH, burstMode(),
                    drainHeights.get(), baseDrainHeight.get(), heightIncrement.get(), heightStep.get());
                if (all.isEmpty()) {
                    error("Failed to build totem-bypass hit list.");
                } else if (all.size() < totems + 1) {
                    error("Can only pop ~%d totems here", all.size() - 1);
                }
                warnBurstPackets(all);
                checkMaceDurability(all.size());
                // One column scan for the whole burst (per-hit rescans cost ~1M block
                // lookups for a 198-burst and always return the same column mid-tick).
                Vec3d burstColumn = all.isEmpty() ? null
                    : findDropColumn(target, all.get(all.size() - 1));
                boolean first = true;
                for (double h : all) {
                    attackOnce(target, h, first, burstColumn);
                    first = false;
                }
                // Silent-swap fix: revert the server slot IMMEDIATELY after the burst.
                // The old code waited until resetState() after the 3-tick RETURN_DELAY, leaving
                // the server on the mace while the client showed the original item.
                revertSilentSwap();
                // Arm the sustained chain so stacked totems the burst couldn't finish
                // (server damage cap, dropped packets) keep popping one per 22 ticks.
                if (sustainedDrain.get() && target.isAlive() && maxSustainedHits.get() > 0) {
                    sustainTarget = target;
                    sustainLeft = maxSustainedHits.get();
                    sustainArmedAt = System.nanoTime();
                }
                finishAttack();
                return;
            }
            doAttack(target, getAttackHeight(), true);
        } catch (Exception e) {
            // Never leave the player stranded up in the sky if something goes wrong mid-attack.
            if (originalPos != null && (returnPos.get() || lastSentY > originalPos.y + 3)) {
                returnToStart();
            } else {
                resetState();
            }
        }
    }

    private void tickDelay() {
        delayTicks++;
        if (delayTicks < 3) return;
        finishAttack();
    }

    private void tickReturnDelay() {
        delayTicks++;
        if (delayTicks >= 3) {
            returnToStart();
        }
    }

    // ==================== 攻击核心 ====================

    private void doAttack(LivingEntity target, int height, boolean isFinal) {
        attackOnce(target, height);
        if (isFinal) {
            finishAttack();
        } else {
            delayTicks = 0;
            phase = Phase.DELAY;
        }
    }

    private void attackOnce(LivingEntity target, double height) {
        attackOnce(target, height, true, null);
    }

    // primeFall: when true, send the rotation-only "stay" packets that seed the fake fall distance.
    // Only the FIRST hit of a totem-bypass burst needs this - re-sending it on every hit wastes the
    // per-tick move-packet budget and causes later hits to be dropped (so only the first totem pops).
    private void attackOnce(LivingEntity target, double height, boolean primeFall) {
        attackOnce(target, height, primeFall, null);
    }

    // column: pre-scanned drop column for bursts (null = scan now). The world does not change
    // mid-tick, so one scan serves the whole burst and saves ~1M block lookups on 198-bursts.
    private void attackOnce(LivingEntity target, double height, boolean primeFall, Vec3d column) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null || height < 1) return;

        // Pierce straight up through any roof. Position packets are NOT collision-checked, so the
        // player teleports through solid blocks. The drop runs in a column whose ATTACK position
        // has a free hitbox within reach - if the final packet landed inside a ceiling block
        // (e.g. the old fixed ty+1.1 in a 2-high tunnel), the server resets fall distance and
        // the smash deals no bonus. See findDropColumn.
        double ty = target.getY();
        double tpY = Math.min(ty + height, Combat.worldTop(mc) - 1);
        Vec3d drop = column != null ? column : findDropColumn(target, height);
        double tx = drop.x, tz = drop.z, attackY = drop.y;

        if (rotate.get()) {
            float yaw = getYawTo(target);
            float pitch = getPitchTo(target);
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, false, false));
        }

        // Prime the fake fall: report onGround=false (rotation-only, same position). This is what
        // makes the server accumulate fall distance - sending it with onGround=true (e.g. while
        // standing) would RESET the fall and the smash would deal no bonus damage. Only done when
        // primeFall is set (first hit of a burst) to save packets.
        if (primeFall) {
            for (int i = 0; i < fallPackets.get(); i++) {
                mc.getNetworkHandler().sendPacket(
                        new PlayerMoveC2SPacket.LookAndOnGround(mc.player.getYaw(), mc.player.getPitch(), false, false));
            }
        }

        // Up, then drop onto the target.
        // - The ASCENT is interpolated in <= moveDistance steps so it stays inside the server's
        //   anti-teleport cap (a single big jump can be rejected) and because fall distance is 0
        //   while rising, intersecting a block on the way up does not matter.
        // - The DESCENT is stepped down in <= moveDistance increments. This is the critical
        //   cave/roof fix: a single huge down jump exceeds the server's per-packet move cap and gets
        //   rubberbanded (player stranded up top), while naively stepping the drop would place the
        //   player *inside* the ceiling block at intermediate packets, which makes the server reset
        //   fall distance. So we step, and whenever a step would land inside a block we jump straight
        //   past it to the first clear Y below (a thin skip, well under the move cap). No sampled
        //   position is ever inside a block and no single packet exceeds the cap, so the full fall
        //   height is preserved even under a solid ceiling.
        Vec3d start = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        stepMove(start, new Vec3d(tx, tpY, tz));
        stepMoveDown(tx, tpY, attackY, tz);
        lastSentX = tx;
        lastSentY = attackY;
        lastSentZ = tz;

        sendAttack(target);
    }

    // Interpolate a move from->to in steps no larger than moveDistance on any axis. Used for both the
    // vertical VClip and the horizontal teleport to the target, so no single packet exceeds the
    // server's per-packet move cap.
    private void stepMove(Vec3d from, Vec3d to) {
        double step = Math.min(moveDistance.get(), 99);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist / step));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.x + dx * t;
            double y = from.y + dy * t;
            double z = from.z + dz * t;
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
            lastSentX = x;
            lastSentY = y;
            lastSentZ = z;
        }
    }

    // Step DOWN from (x, fromY, z) to (x, toY, z) in <= moveDistance increments, picked by
    // ROOF SIZE: thin roofs (clear within one step below) are jumped past so no packet lands
    // inside a block (landing inside resets the server's fall and kills the smash bonus);
    // thick rock is passed THROUGH in-cap steps instead. The old code allowed one up-to-100
    // block skip packet here, which trips "moved too quickly": the server snaps you back and
    // every later burst hit cascades into no-fall weak hits (bursts stalling partway). Every
    // packet below stays in-cap; intermediate in-rock positions never tick, so vanilla-safe.
    private void stepMoveDown(double x, double fromY, double toY, double z) {
        double step = Math.min(moveDistance.get(), 99);
        double y = fromY;
        int guard = 0;
        while (y > toY + 1e-6 && guard++ < 10000) {
            double next = y - step;
            if (next < toY) next = toY;
            if (hitboxBlocked(x, next, z)) {
                double clear = firstClearBelow(x, next, z, toY);
                if (clear > toY && (y - clear) <= step + 1e-6) {
                    y = clear; // thin roof: skip past it, still one in-cap packet
                } else {
                    y = next; // thick rock: straight through, in-cap (vanilla-safe)
                }
            } else {
                y = next;
            }
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
            lastSentX = x;
            lastSentY = y;
            lastSentZ = z;
            if (Math.abs(y - toY) < 1e-6) break;
        }
    }

    private boolean hitboxBlocked(double x, double y, double z) {
        int bx0 = (int) Math.floor(x - 0.3);
        int bx1 = (int) Math.floor(x + 0.3);
        int bz0 = (int) Math.floor(z - 0.3);
        int bz1 = (int) Math.floor(z + 0.3);
        int by0 = (int) Math.floor(y);
        int by1 = (int) Math.floor(y + 1.8);
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int by = by0; by <= by1; by++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    if (!mc.world.getBlockState(new BlockPos(bx, by, bz)).isAir()) return true;
                }
            }
        }
        return false;
    }

    private double firstClearBelow(double x, double y, double z, double floorY) {
        double yy = y;
        while (yy > floorY) {
            if (!hitboxBlocked(x, yy, z)) return yy;
            yy -= 0.5;
        }
        return floorY;
    }

    // Find where to drop onto the target. The returned Y is the ATTACK position, chosen so
    // the final packet never lands inside a block: two-phase search —
    // (1) open sky fast path: nearest fully-clear column (no ceiling skips on the way down);
    // (2) cave path: nearest column with a FREE hitbox position within attack reach (2.9
    // blocks of the target center). Phase 2 is what makes 2-high tunnels work — the old
    // fixed ty+1.1 put the head inside the ceiling, the server reset the fall, and every
    // hit dealt no bonus. Falls back to the target column if nothing is free (best effort).
    private Vec3d findDropColumn(LivingEntity target, double height) {
        double ty = target.getY();
        int topY = (int) Math.floor(Math.min(ty + height, Combat.worldTop(mc) - 1));
        int botY = (int) Math.floor(ty + 1);
        int cx = (int) Math.floor(target.getX());
        int cz = (int) Math.floor(target.getZ());

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;        // target column checked as fallback
                if (Math.hypot(dx, dz) > 2.5) continue;  // keep within attack reach
                int x = cx + dx, z = cz + dz;
                boolean clear = true;
                for (int y = botY; y <= topY; y++) {
                    if (!mc.world.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        clear = false;
                        break;
                    }
                }
                if (clear) return new Vec3d(x + 0.5, findAttackY(x + 0.5, z + 0.5, target), z + 0.5);
            }
        }
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.hypot(dx, dz) > 2.5) continue;
                int x = cx + dx, z = cz + dz;
                double ay = findAttackY(x + 0.5, z + 0.5, target);
                if (ay < 0) continue; // no free in-reach spot in this column
                // Target's own column first, then nearest; higher attack Y wins ties
                // (shorter descent, closer to the drop path).
                double score = Math.hypot(dx, dz) * 10 - ay * 0.001;
                if (score < bestScore) { bestScore = score; best = new Vec3d(x + 0.5, ay, z + 0.5); }
            }
        }
        if (best != null) return best;
        return new Vec3d(target.getX(), ty + 1.1, target.getZ());
    }

    // Highest free-hitbox Y within attack reach of the target, or -1 if none.
    // Scans top-down so the winner is the highest valid spot (shortest descent).
    private double findAttackY(double x, double z, LivingEntity target) {
        double cx = target.getX(), cy = target.getY() + target.getHeight() * 0.5, cz = target.getZ();
        for (double y = target.getY() + 2.5; y >= target.getY() - 1.0; y -= 0.5) {
            if (hitboxBlocked(x, y, z)) continue;
            double dx = x - cx, dy = (y + 0.9) - cy, dz = z - cz;
            if (dx * dx + dy * dy + dz * dz > 2.9 * 2.9) continue;
            return y;
        }
        return -1;
    }

    private void sendAttack(LivingEntity target) {
        // The mace selection (client vs server) is handled in checkAndSwapWeapon; the attack always
        // uses whatever the SERVER currently has selected, so we just send the hit here.
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

        if (swingHand.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void finishAttack() {
        if (originalPos == null) {
            resetState();
            return;
        }
        // Always pass through the return phase so we can check whether the hit actually landed.
        // If it didn't (unhittable target) we force a return and let the player move freely.
        delayTicks = 0;
        phase = Phase.RETURN_DELAY;
    }

    // Return to the original position by descending in stepped packets (a single huge vertical
    // jump is often ignored by the server, which leaves the player stranded up in the sky).
    private void returnToStart() {
        if (originalPos == null) {
            resetState();
            return;
        }

        // Did the smash actually deal damage? If not, the target was unhittable - get back to the
        // start and pause briefly so the player can move freely instead of being yanked again.
        boolean damaged = false;
        if (target != null) {
            float cur = target.getHealth() + target.getAbsorptionAmount();
            damaged = !target.isAlive() || cur < preHealth - 0.5f;
        }
        boolean doReturn = originalPos != null && (!damaged || returnPos.get());
        if (!damaged) {
            doReturn = originalPos != null;
            freeCooldown = 40; // ~2s of free movement after an unhittable attempt
        }

        if (doReturn) {
            double fromY = lastSentY;
            double step = moveDistance.get();
            double total = originalPos.y - fromY;
            int steps = (int) Math.ceil(Math.abs(total) / step);
            if (steps < 1) steps = 1;

            for (int i = 1; i <= steps; i++) {
                double y = fromY + total * i / steps;
                mc.getNetworkHandler().sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(originalPos.x, y, originalPos.z, false, false));
            }

            // Final landing packet.
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(originalPos.x, originalPos.y, originalPos.z, true, false));

            // Snap the local client back as well, in case the server forced a teleport during the attack.
            if (mc.player != null) {
                mc.player.setPosition(originalPos.x, originalPos.y, originalPos.z);
            }
        } else if (mc.player != null) {
            // We did not return (e.g. a successful hit with Return-to-Start off). The position packets
            // moved the SERVER to the smash spot but the client was never updated, so client and server
            // disagree - which makes block break/place (and every interaction) fail. Snap the client to
            // the server's final position so they stay in sync.
            mc.player.setPosition(lastSentX, lastSentY, lastSentZ);
        }

        resetState();
    }

    private void resetState() {
        // Safety net — the burst path already reverts immediately after the last hit.
        revertSilentSwap();
        if (maceSwapBackSlot >= 0 && mc.player != null && mc.interactionManager != null) {
            swapMace(maceSwapBackSlot, originalSlot);
            maceSwapBackSlot = -1;
        }
        phase = Phase.IDLE;
        delayTicks = 0;
        attackCount = 0;
        lastSentX = 0;
        lastSentY = 0;
        lastSentZ = 0;
        target = null;
        originalPos = null;
        originalSlot = -1;
        maceSlot = -1;
        silentRevertSlot = -1;
        bypassHeights = null;
        bypassIdx = 0;
    }

    // Silent-swap revert, extracted so the burst reverts IMMEDIATELY after the last hit
    // instead of waiting for resetState() after the 3-tick RETURN_DELAY (during which the
    // server held the mace while the client showed the original item).
    private void revertSilentSwap() {
        if (silentRevertSlot >= 0 && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(silentRevertSlot));
            silentRevertSlot = -1;
        }
    }

    private BurstHeights.Mode burstMode() {
        if (drainMode.get() == DrainMode.LIST) return BurstHeights.Mode.LIST;
        if (drainMode.get() == DrainMode.INCREMENTAL) return BurstHeights.Mode.INCREMENTAL;
        return BurstHeights.Mode.SPREAD;
    }

    // A landed hit costs 1 mace durability (vanilla) — a 198-hit burst eats ~198 of it.
    // Warn before the burst instead of snapping the mace mid-fight (Unbreaking, e.g. via
    // ItemGiver, divides the loss; Detect Totem + a sane Totems To Pop shrinks the burst).
    private void checkMaceDurability(int hits) {
        if (hits < 10) return;
        int left = maceDurabilityLeft();
        if (left == Integer.MAX_VALUE) return;
        if (left < hits) {
            error("Mace has %d durability left but the burst needs ~%d hits — it may break! Lower Totems To Pop / enable Detect Totem, or add Unbreaking via ItemGiver.", left, hits);
        }
    }

    // Remaining durability of the mace we are smashing with (MAX_VALUE if unassessable).
    private int maceDurabilityLeft() {
        if (mc.player == null) return Integer.MAX_VALUE;
        PlayerInventory inv = mc.player.getInventory();
        ItemStack mace = null;
        if (maceSlot >= 0 && maceSlot < inv.size() && inv.getStack(maceSlot).isOf(Items.MACE)) {
            mace = inv.getStack(maceSlot);
        } else if (inv.getStack(inv.getSelectedSlot()).isOf(Items.MACE)) {
            mace = inv.getStack(inv.getSelectedSlot());
        } else {
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.MACE)) { mace = inv.getStack(i); break; }
            }
        }
        if (mace == null || !mace.isDamageable()) return Integer.MAX_VALUE;
        return mace.getMaxDamage() - mace.getDamage();
    }

    // Sustained drain: one full-height lethal single per invuln window for stacked-totem
    // targets the burst couldn't finish. No escalation needed — each hit only must be
    // lethal on its own, so server damage caps and dropped packets can't stall it.
    private void sustainedSingle(PlayerEntity p) {
        if (maceDurabilityLeft() < 3) {
            error("Mace almost broken — stopping sustained drain.");
            sustainTarget = null;
            sustainLeft = 0;
            finishAttack();
            return;
        }
        attackOnce(target, getAttackHeight(), true);
        revertSilentSwap();
        sustainLeft--;
        sustainCooldown = SUSTAIN_DELAY;
        if (sustainLeft <= 0 || !target.isAlive()) sustainTarget = null;
        info("Sustained drain: %d left (%s)", Math.max(0, sustainLeft), p.getName().getString());
        finishAttack();
    }

    // A 198-hit burst is thousands of move packets in ONE tick — only feasible on servers
    // without packet limits. Estimate and warn so users raise Move Step (fewer steps per
    // ascent) instead of getting kicked for spam.
    private void warnBurstPackets(List<Double> heights) {
        if (heights.size() < 10) return;
        double step = Math.max(1.0, Math.min(moveDistance.get(), 99));
        long total = 0;
        for (double h : heights) total += 2L * (long) Math.ceil(h / step) + 2;
        if (total > 1500) warning("Burst of %d hits ≈ %d packets in one tick — raise Move Step or expect a kick", heights.size(), total);
    }

    // ==================== 武器切换 ====================

    private boolean checkAndSwapWeapon() {
        originalSlot = getSelectedSlot();
        maceSwapBackSlot = -1;

        if (!autoSwitch.get()) return true;

        PlayerInventory inv = mc.player.getInventory();

        // 1) Already holding the mace in the selected hotbar slot.
        if (inv.getStack(originalSlot).isOf(Items.MACE)) {
            maceSlot = originalSlot;
            noMaceWarned = false;
            return true;
        }

        // 2) Mace already in the hotbar -> select it.
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.MACE)) {
                maceSlot = i;
                noMaceWarned = false;
                if (i != originalSlot) {
                    // Always tell the SERVER to select the mace so the smash actually uses it.
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i));
                    if (silentSwap.get()) {
                        // Silent: keep the CLIENT view on the original slot, and remember to revert
                        // the server selection afterwards so the swap is never visible (and the player's
                        // real selected item stays in sync, so block break/place keeps working).
                        silentRevertSlot = originalSlot;
                    } else {
                        setSelectedSlot(inv, i);
                    }
                }
                return true;
            }
        }

        // 3) Mace anywhere else (main inventory / offhand / armor) -> SWAP into the selected slot.
        int src = -1;
        for (int i = 9; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.MACE)) {
                src = i;
                break;
            }
        }
        if (src == -1) {
            if (!noMaceWarned) {
                error("No mace found in inventory, attack cancelled.");
                noMaceWarned = true;
            }
            return false;
        }

        swapMace(src, originalSlot);
        maceSlot = originalSlot;
        maceSwapBackSlot = src;
        // Make sure the server selects the slot that now holds the mace.
        if (mc.getNetworkHandler() == null) return false;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
        noMaceWarned = false;
        return true;
    }

    // Swap the mace (any inventory slot) into the selected hotbar slot using a real click-slot SWAP,
    // which works even when the mace is not in the hotbar.
    private void swapMace(int invSlot, int hotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (hotbarSlot < 0 || hotbarSlot > 8) return;
        if (invSlot < 0 || invSlot >= mc.player.getInventory().size()) return;
        int screenSlot = invSlot;
        if (invSlot >= 36 && invSlot <= 39) screenSlot = invSlot - 31; // armor -> 5..8
        else if (invSlot == 40) screenSlot = 45; // offhand
        mc.interactionManager.clickSlot(
                mc.player.playerScreenHandler.syncId, screenSlot, hotbarSlot, SlotActionType.SWAP, mc.player);
    }

    // ==================== 目标查找 ====================

    private LivingEntity findTarget() {
        if (mc.world == null || mc.player == null) return null;
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;
            if (le == mc.player) continue;
            if (!entityCheck(le)) continue;

            double dist = mc.player.squaredDistanceTo(le);
            if (dist < bestDist) {
                bestDist = dist;
                best = le;
            }
        }
        return best;
    }

    private boolean entityCheck(Entity entity) {
        if (!(entity instanceof LivingEntity le) || le.isDead()) return false;
        if (entity == mc.player) return false;

        double dist = mc.player.distanceTo(entity);
        if (dist > range.get()) return false;

        if (!throughWalls.get() && !mc.player.canSee(entity)) return false;

        if (entity instanceof PlayerEntity player) {
            if (!players.get()) return false;
            if (player.isSpectator()) return false;
            if (Friends.get().isFriend(player)) return false;
            if (!Friends.get().shouldAttack(player)) return false;

            // 名单过滤
            if (listMode.get() != ListMode.Off) {
                List<String> list = parsePlayerList();
                String name = player.getName().getString();
                if (listMode.get() == ListMode.Whitelist && !list.contains(name)) return false;
                if (listMode.get() == ListMode.Blacklist && list.contains(name)) return false;
            }
        } else {
            if (!entities.get()) return false;
        }

        if (ignoreNamed.get() && entity.hasCustomName()) return false;

        return true;
    }

    private List<String> parsePlayerList() {
        List<String> list = new ArrayList<>();
        for (String s : playerList.get().split(",")) {
            String trim = s.trim();
            if (!trim.isEmpty()) list.add(trim);
        }
        return list;
    }

    // ==================== 高度计算 ====================

    private int getAttackHeight() {
        // Pierce straight up through any roof: the full configured height gives the mace a complete
        // fall distance even in a cave, so both mobs and players die in a single hit. Totem bypass
        // stays player-only (handled in tickIdle).
        if (maxPower.get()) return 170;
        return Math.min(fallHeight.get(), 170);
    }

    /**
     * 从 IMG 移植：从目标头顶向上扫描，找到安全可用的最大高度
     */
    private int getMaxHeightAbovePlayer(LivingEntity target) {
        BlockPos targetPos = target.getBlockPos();
        int maxH = maxPower.get() ? 170 : fallHeight.get();

        for (int yOffset = maxH; yOffset > 0; yOffset--) {
            BlockPos pos = new BlockPos(targetPos.getX(), targetPos.getY() + yOffset, targetPos.getZ());
            BlockPos posAbove = pos.up();

            // 检查两个连续的空气方块
            boolean safe = mc.world.getBlockState(pos).isAir()
                    && mc.world.getBlockState(posAbove).isAir();
            if (safe) {
                return yOffset;
            }
        }
        return 0;
    }

    // ==================== 旋转 ====================
    // 在 1.21.11 内部实体 ID 从 0 开始

    private float getYawTo(Entity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private float getPitchTo(Entity target) {
        double dx = target.getX() - mc.player.getX();
        double dy = target.getEyeY() - mc.player.getEyeY();
        double dz = target.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, dist));
    }

    // ==================== 反射 ====================

    // Public inventory API — the old getDeclaredField("selectedSlot") reflection used the
    // Yarn name, which does not exist at runtime (intermediary), so reads returned 0 and
    // silent-swap reverts sent the wrong slot. See macekill.Inventory.
    private int getSelectedSlot() {
        if (mc.player == null) return 0;
        return mc.player.getInventory().getSelectedSlot();
    }

    private void setSelectedSlot(PlayerInventory inv, int slot) {
        if (inv == null || slot < 0 || slot > 8) return;
        inv.setSelectedSlot(slot);
    }

    private void setSlotClient(int slot) {
        setSelectedSlot(mc.player.getInventory(), slot);
    }

    private boolean targetHasTotem(PlayerEntity player) {
        return countTotems(player) > 0;
    }

    private int countTotems(PlayerEntity player) {
        int n = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) n++;
        }
        return n;
    }

    private List<String> getDrainHeights() {
        if (drainMode.get() == DrainMode.INCREMENTAL) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < totemAttacks.get(); i++) {
                list.add(String.valueOf(baseDrainHeight.get() + i * heightIncrement.get()));
            }
            return list;
        }
        return drainHeights.get();
    }

    private enum DrainMode {
        SPREAD,
        LIST,
        INCREMENTAL
    }

    @Override
    public String getInfoString() {
        if (totemBypass.get() && target instanceof PlayerEntity) return "Totem Bypass";
        return target != null ? target.getName().getString() : "No target";
    }
}
