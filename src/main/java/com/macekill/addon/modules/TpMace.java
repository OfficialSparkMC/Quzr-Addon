package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
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
            .name("Attack Height").description("Fall height used for the attack")
            .defaultValue(30).min(1).max(170).sliderRange(1, 170)
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
    private LivingEntity target;
    private int originalSlot = -1;
    private int maceSlot = -1;

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
        attackCount = 0;
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
        if (delayTicks < attackDelay.get()) return;
        delayTicks = 0;

        target = findTarget();
        if (target == null) return;

        if (!checkAndSwapWeapon()) return;
        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        attackCount = 0;

        if (totemBypass.get() && target instanceof PlayerEntity p
                && (!detectTotem.get() || targetHasTotem(p))) {
            // 图腾绕过：1 tick 内完成全部消耗高度 + 击杀
            for (String hStr : getDrainHeights()) {
                int h = parseHeight(hStr);
                if (h > 0) attackOnce(target, h);
            }
        }
        doAttack(target, getAttackHeight(), true);
    }

    private void tickDelay() {
        delayTicks++;
        if (delayTicks < 3) return;
        finishAttack();
    }

    private void tickReturnDelay() {
        delayTicks++;
        if (delayTicks >= 2) {
            finishReturn();
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
        if (mc.player == null) return;

        Vec3d tpPos = new Vec3d(target.getX(), target.getY() + height, target.getZ());

        if (rotate.get()) {
            float yaw = getYawTo(target);
            float pitch = getPitchTo(target);
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), false));
        }

        // VClip：模拟升空
        sendVClipPackets(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()), tpPos);

        // 模拟掉落
        sendExploitPackets(tpPos);

        // 攻击
        sendAttack(target);
    }

    private void sendVClipPackets(Vec3d from, Vec3d to) {
        double step = moveDistance.get();
        double totalDist = to.y - from.y;
        int steps = (int) Math.ceil(Math.abs(totalDist) / step);
        double stepY = totalDist / steps;

        for (int i = 1; i <= steps; i++) {
            double y = from.y + stepY * i;
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(from.x, y, from.z, false, false));
        }
    }

    private void sendExploitPackets(Vec3d from) {
        double step = moveDistance.get();
        double startY = from.y;
        double targetY = target.getY() + 1.1;

        for (double y = startY; y > targetY + step; y -= step) {
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(from.x, y, from.z, false, false));
        }

        // 最终位置
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(from.x, targetY, from.z, false, false));
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
        if (returnPos.get() && originalPos != null) {
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(originalPos.x, originalPos.y, originalPos.z,
                            mc.player.isOnGround(), false));
            delayTicks = 0;
            phase = Phase.RETURN_DELAY;
            return;
        }
        resetState();
    }

    private void finishReturn() {
        // 返回原位的最后一步
        if (originalPos != null) {
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(originalPos.x, originalPos.y + 0.5, originalPos.z,
                            true, false));
        }
        resetState();
    }

    private void resetState() {
        phase = Phase.IDLE;
        delayTicks = 0;
        attackCount = 0;
        target = null;
        originalPos = null;
    }

    // ==================== 武器切换 ====================

    private boolean checkAndSwapWeapon() {
        originalSlot = getSelectedSlot();

        if (!autoSwitch.get()) return true;

        FindItemResult mace = InvUtils.findInHotbar(Items.MACE);
        if (!mace.found()) return false;

        maceSlot = mace.slot();
        if (maceSlot == originalSlot) return true;

        if (!silentSwap.get()) {
            setSelectedSlot(mc.player.getInventory(), maceSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(maceSlot));
        }

        return true;
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
        if (maxPower.get()) {
            return getMaxHeightAbovePlayer(target);
        }
        return fallHeight.get();
    }

    /**
     * 从 IMG 移植：从目标头顶向上扫描，找到安全可用的最大高度
     */
    private int getMaxHeightAbovePlayer(LivingEntity target) {
        BlockPos targetPos = target.getBlockPos();
        int maxH = maxPower.get() ? 20 : fallHeight.get();

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
        return player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            || player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
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
