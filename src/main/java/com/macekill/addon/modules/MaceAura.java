package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 重锤光环 — MaceAura
 *
 * 核心特性：
 * - 位置缓存 (Map<BlockPos, Boolean>) 避免重复计算
 * - 图腾绕过：多次小高度攻击消耗图腾后再用完整高度击杀
 * - 偏移攻击：水平/垂直偏移，不精确站在目标头顶
 * - 高度递增：每次攻击递增高度，生成不同落点
 * - 垃圾包：每个高度发送多个移动包扰乱反作弊
 * - BlockPos.Mutable：减少 GC 压力
 */
public class MaceAura extends Module {
    private static Field selectedSlotField;

    /* ========== 设置 ========== */
    private final SettingGroup sgMain = settings.getDefaultGroup();
    private final SettingGroup sgTotem = settings.createGroup("图腾绕过");
    private final SettingGroup sgOffset = settings.createGroup("偏移");

    private final Setting<Double> range = sgMain.add(new DoubleSetting.Builder()
            .name("范围").description("目标检测范围")
            .defaultValue(20.0).min(1.0).max(200.0).sliderRange(1.0, 128.0).build());

    private final Setting<Boolean> onlyPlayers = sgMain.add(new BoolSetting.Builder()
            .name("仅玩家").description("仅攻击玩家").defaultValue(true).build());

    private final Setting<Boolean> swingArm = sgMain.add(new BoolSetting.Builder()
            .name("挥手").description("攻击时挥手").defaultValue(false).build());

    private final Setting<Integer> fallHeight = sgMain.add(new IntSetting.Builder()
            .name("攻击高度").description("基础下落高度")
            .defaultValue(30).min(1).max(170).sliderRange(1, 170).build());

    private final Setting<Integer> spamPackets = sgMain.add(new IntSetting.Builder()
            .name("垃圾包").description("每个高度发送的额外移动包")
            .defaultValue(2).min(0).max(20).sliderMax(10).build());

    private final Setting<Integer> attackDelay = sgMain.add(new IntSetting.Builder()
            .name("攻击延迟").description("攻击间隔(tick)")
            .defaultValue(10).min(0).max(40).sliderMax(40).build());

    private final Setting<Boolean> useOffset = sgOffset.add(new BoolSetting.Builder()
            .name("启用偏移").description("不精确到目标正上方").defaultValue(false).build());

    private final Setting<Double> horizontalOffset = sgOffset.add(new DoubleSetting.Builder()
            .name("水平偏移").description("水平方向偏移量")
            .defaultValue(1.5).min(0).max(10).sliderMax(5)
            .visible(useOffset::get).build());

    private final Setting<Double> yOffset = sgOffset.add(new DoubleSetting.Builder()
            .name("高度偏移").description("垂直高度额外偏移")
            .defaultValue(0.5).min(0).max(5).sliderMax(5)
            .visible(useOffset::get).build());

    private final Setting<Boolean> bypassTotem = sgTotem.add(new BoolSetting.Builder()
            .name("图腾绕过").description("先用小高度消耗图腾").defaultValue(false).build());

    private final Setting<Integer> attackCount = sgTotem.add(new IntSetting.Builder()
            .name("图腾攻击次数").description("消耗图腾的攻击次数")
            .defaultValue(3).min(1).max(10).sliderMax(10)
            .visible(bypassTotem::get).build());

    private final Setting<Integer> heightIncrement = sgTotem.add(new IntSetting.Builder()
            .name("高度增量").description("每次图腾攻击增加的高度")
            .defaultValue(2).min(1).max(10).sliderMax(10)
            .visible(bypassTotem::get).build());

    /* ========== 状态 ========== */
    private final Map<BlockPos, Boolean> positionCache = new HashMap<>();
    private final BlockPos.Mutable mutablePos = new BlockPos.Mutable();
    private int remainingCooldown;
    private int totemAttackIndex;

    public MaceAura() {
        super(MaceKillAddon.CATEGORY, "MaceAura", "重锤光环 - 带图腾绕过+偏移+位置缓存的自动攻击");
    }

    @Override
    public void onActivate() {
        remainingCooldown = 0;
        totemAttackIndex = 0;
        positionCache.clear();
    }

    @Override
    public void onDeactivate() {
        positionCache.clear();
    }

    /* ========== Tick ========== */

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (remainingCooldown > 0) { remainingCooldown--; return; }

        LivingEntity target = findNearestTarget();
        if (target == null) return;

        boolean killed = executeAttack(target);
        if (killed) {
            totemAttackIndex = 0;
            positionCache.clear();
        }

        remainingCooldown = attackDelay.get();
    }

    // ========== 目标查找 ==========

    private LivingEntity findNearestTarget() {
        if (mc.world == null || mc.player == null) return null;
        double rangeSq = range.get() * range.get();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le == mc.player) continue;
            double distSq = mc.player.squaredDistanceTo(le);
            if (distSq > rangeSq) continue;
            if (onlyPlayers.get() && !(le instanceof PlayerEntity)) continue;
            if (le instanceof PlayerEntity p) {
                if (p.isCreative() || p.isSpectator() || !Friends.get().shouldAttack(p)) continue;
            }
            if (distSq < bestDist) { bestDist = distSq; best = le; }
        }
        return best;
    }

    // ========== 攻击执行 ==========

    private boolean executeAttack(LivingEntity target) {
        if (mc.getNetworkHandler() == null) return false;

        // 切换到重锤
        int oldSlot = switchToMace();
        if (oldSlot == -1) return false;

        try {
            Vec3d originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

            if (bypassTotem.get() && target instanceof PlayerEntity) {
                // 图腾绕过：多轮递增高度攻击
                return executeTotemBypass(target, originalPos);
            } else {
                // 正常击杀
                return executeKill(target, originalPos, fallHeight.get());
            }
        } finally {
            switchBack(oldSlot);
        }
    }

    private boolean executeTotemBypass(LivingEntity target, Vec3d originalPos) {
        int count = attackCount.get();
        int increment = heightIncrement.get();
        int baseHeight = fallHeight.get();

        for (int i = totemAttackIndex; i < count; i++) {
            int h = baseHeight + i * increment;
            if (!doSingleAttack(target, originalPos, h)) continue;

            totemAttackIndex++;
            if (totemAttackIndex >= count) {
                totemAttackIndex = 0;
                return true; // 图腾消耗完毕，返回true继续下一轮完整击杀
            }
            return false;
        }
        return false;
    }

    private boolean executeKill(LivingEntity target, Vec3d originalPos, int height) {
        return doSingleAttack(target, originalPos, height);
    }

    private boolean doSingleAttack(LivingEntity target, Vec3d originalPos, int height) {
        if (mc.getNetworkHandler() == null) return false;

        Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
        if (useOffset.get()) {
            targetPos = getOffset(targetPos);
        }

        // 计算安全起跳高度
        int safeHeight = getMaxHeightAbovePlayer(targetPos, height);
        Vec3d jumpPos = new Vec3d(targetPos.x, targetPos.y + safeHeight, targetPos.z);

        // 升空 + 发垃圾包
        sendMovePacketWithSpam(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()), jumpPos);

        // 下落
        Vec3d attackPos = new Vec3d(targetPos.x, targetPos.y + 0.5, targetPos.z);
        sendMovePacketWithSpam(jumpPos, attackPos);

        if (swingArm.get()) mc.player.swingHand(Hand.MAIN_HAND);
        attackEntity(target);

        return true;
    }

    // ========== 位置计算 ==========

    /** 获取偏移后的位置 */
    private Vec3d getOffset(Vec3d pos) {
        double hOff = horizontalOffset.get();
        double yOff = yOffset.get();
        // 随机水平方向
        double angle = Math.random() * Math.PI * 2;
        return new Vec3d(
                pos.x + Math.cos(angle) * hOff,
                pos.y + yOff,
                pos.z + Math.sin(angle) * hOff);
    }

    /** 计算目标上方最大安全高度 */
    private int getMaxHeightAbovePlayer(Vec3d targetPos, int maxHeight) {
        int bx = (int) Math.floor(targetPos.x);
        int bz = (int) Math.floor(targetPos.z);
        int by = (int) Math.floor(targetPos.y);

        for (int h = maxHeight; h >= 1; h--) {
            int cy = by + h;
            mutablePos.set(bx, cy, bz);
            if (isSafePosition(mutablePos)) {
                mutablePos.set(bx, cy + 1, bz);
                if (isSafePosition(mutablePos)) {
                    return h;
                }
            }
        }
        return maxHeight; // 兜底
    }

    private boolean isSafePosition(BlockPos pos) {
        return positionCache.computeIfAbsent(pos.toImmutable(), p -> {
            BlockState state = mc.world.getBlockState(p);
            return state.isAir()
                    && state.getFluidState().isEmpty()
                    && !state.isOf(Blocks.COBWEB);
        });
    }

    // ========== 发包 ==========

    private void sendMovePacketWithSpam(Vec3d from, Vec3d to) {
        double dist = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(dist / 8.0));
        double stepX = (to.x - from.x) / steps;
        double stepY = (to.y - from.y) / steps;
        double stepZ = (to.z - from.z) / steps;

        int spam = spamPackets.get();
        for (int i = 1; i <= steps; i++) {
            double x = from.x + stepX * i;
            double y = from.y + stepY * i;
            double z = from.z + stepZ * i;
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
            // 发垃圾包
            for (int s = 0; s < spam; s++) {
                mc.getNetworkHandler().sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
            }
        }
    }

    private void attackEntity(Entity target) {
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(
                PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
    }

    // ========== 物品切换 ==========

    private int switchToMace() {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() != Items.MACE) continue;
            int cur = getSelectedSlot();
            if (i != cur) {
                setSelectedSlot(i);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i));
            }
            return cur;
        }
        return -1;
    }

    private void switchBack(int slot) {
        if (slot < 0 || slot >= 9) return;
        setSelectedSlot(slot);
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private int getSelectedSlot() {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            return selectedSlotField.getInt(mc.player.getInventory());
        } catch (Exception e) { return 0; }
    }

    private void setSelectedSlot(int slot) {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            selectedSlotField.setInt(mc.player.getInventory(), slot);
        } catch (Exception ignored) {}
    }

    @Override
    public String getInfoString() {
        if (remainingCooldown > 0) return "CD " + remainingCooldown;
        return bypassTotem.get() ? "图腾绕过" : "就绪";
    }
}
