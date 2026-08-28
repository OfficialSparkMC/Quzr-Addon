package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.Combat;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
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
    private static Field selectedSlotField;
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
            .name("Drain Mode").description("List: use custom height list | Incremental: base + step per hit")
            .defaultValue(DrainMode.LIST).visible(totemBypass::get).build()
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
    private double lastSentY;
    private LivingEntity target;
    private int originalSlot = -1;
    private int maceSlot = -1;
    private int maceSwapBackSlot = -1;
    private boolean noMaceWarned = false;
    private List<String> bypassHeights;
    private int bypassIdx;
    private float preHealth;
    private int freeCooldown = 0;

    public TpMace() {
        super(MaceKillAddon.CATEGORY, "TpMace", "Long-range mace - TP teleport+totem bypass+silent swap");
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        target = null;
        originalPos = null;
        originalSlot = -1;
        maceSlot = -1;
        maceSwapBackSlot = -1;
        attackCount = 0;
        lastSentY = 0;
        preHealth = 0;
        freeCooldown = 0;
        bypassHeights = null;
        bypassIdx = 0;
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
        delayTicks++;
        int tickDelay = totemBypass.get() ? 0 : attackDelay.get();
        if (delayTicks < tickDelay) return;
        delayTicks = 0;

        // After an unhittable attempt we pause briefly so the player can move freely.
        if (freeCooldown > 0) {
            freeCooldown--;
            return;
        }

        target = findTarget();
        if (target == null) return;

        if (!checkAndSwapWeapon()) return;
        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        lastSentY = originalPos.y;
        attackCount = 0;
        preHealth = target.getHealth() + target.getAbsorptionAmount();

        try {
            if (totemBypass.get() && target instanceof PlayerEntity p
                    && (!detectTotem.get() || targetHasTotem(p))) {
                int totems = totemsToPop.get();
                if (detectTotem.get()) totems = Math.min(totems, countTotems(p));
                totems = Math.min(totems + 3, Combat.MAX_TOTEM_HITS);
                // AutoTotem re-equips between ticks, so the whole strictly-escalating hit list
                // MUST land in a single tick (one mace smash) or the target always survives.
                // We pierce any roof, so the only real limit is the world top. Cap the heights to the
                // headroom above the target (worldTop - targetY) so the fall never collapses onto the
                // 318 cap and every hit stays strictly greater than the last (even inside a cave, where
                // we simply go up through the ceiling).
                int maxH = (int) Math.min(Combat.MAX_TOTEM_HITS, 317 - target.getY());
                if (maxH < 8) maxH = 8;
                List<String> all = Combat.totemBypassHeights(totems, maxH);
                if (all.isEmpty()) {
                    error("Failed to build totem-bypass hit list.");
                } else if (all.size() < totems + 1) {
                    error("Can only pop ~%d totems here", all.size() - 1);
                }
                for (String hStr : all) {
                    int h = parseHeight(hStr);
                    if (h > 0) attackOnce(target, h);
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

    private void attackOnce(LivingEntity target, int height) {
        if (mc.player == null || height < 1) return;

        // Pierce straight up through any roof. Position packets are NOT collision-checked, so the
        // player teleports through solid blocks - this is exactly what makes the smash work under a
        // cave ceiling (we just go up through it). Crucially, every move stays in the TARGET's
        // column (see stepMove) so there is no giant horizontal jump mid-arc for the server to reject.
        double tx = target.getX(), ty = target.getY(), tz = target.getZ();
        double tpY = Math.min(ty + height, 318);
        double targetY = ty + 1.1;

        if (rotate.get()) {
            float yaw = getYawTo(target);
            float pitch = getPitchTo(target);
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, false, false));
        }

        // Prime the fake fall: report onGround=false (rotation-only, same position). This is what
        // makes the server accumulate fall distance - sending it with onGround=true (e.g. while
        // standing) would RESET the fall and the smash would deal no bonus damage.
        for (int i = 0; i < fallPackets.get(); i++) {
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(mc.player.getYaw(), mc.player.getPitch(), false, false));
        }

        // Up, then drop onto the target. Both legs are interpolated on EVERY axis in <= moveDistance
        // steps so each packet stays inside the server's anti-teleport cap (a single big jump gets
        // rejected, which is what broke the old code under caves).
        Vec3d start = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        stepMove(start, new Vec3d(tx, tpY, tz));
        stepMove(new Vec3d(tx, tpY, tz), new Vec3d(tx, targetY, tz));

        sendAttack(target);
    }

    // Interpolate a move from->to in steps no larger than moveDistance on any axis. Used for both the
    // vertical VClip and the horizontal teleport to the target, so no single packet exceeds the
    // server's per-packet move cap.
    private void stepMove(Vec3d from, Vec3d to) {
        double step = moveDistance.get();
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
            lastSentY = y;
        }
    }

    private void sendAttack(LivingEntity target) {
        if (silentSwap.get() && maceSlot != -1) {
            setSlotClient(maceSlot);
        }

        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

        if (swingHand.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        if (silentSwap.get()) {
            setSlotClient(originalSlot);
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
        }

        resetState();
    }

    private void resetState() {
        if (maceSwapBackSlot >= 0) {
            swapMace(maceSwapBackSlot, originalSlot);
            maceSwapBackSlot = -1;
        }
        phase = Phase.IDLE;
        delayTicks = 0;
        attackCount = 0;
        lastSentY = 0;
        target = null;
        originalPos = null;
        bypassHeights = null;
        bypassIdx = 0;
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
                    setSelectedSlot(inv, i);
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i));
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
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
        noMaceWarned = false;
        return true;
    }

    // Swap the mace (any inventory slot) into the selected hotbar slot using a real
    // click-slot SWAP, which works even when the mace is not in the hotbar.
    private void swapMace(int invSlot, int hotbarSlot) {
        if (mc.player == null) return;
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

    private int getSelectedSlot() {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            return selectedSlotField.getInt(mc.player.getInventory());
        } catch (Exception e) {
            return 0;
        }
    }

    private void setSelectedSlot(PlayerInventory inv, int slot) {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            selectedSlotField.setInt(inv, slot);
        } catch (Exception ignored) {}
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

    private int parseHeight(String s) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private enum DrainMode {
        LIST,
        INCREMENTAL
    }

    @Override
    public String getInfoString() {
        if (totemBypass.get() && target instanceof PlayerEntity) return "Totem Bypass";
        return target != null ? target.getName().getString() : "No target";
    }
}
