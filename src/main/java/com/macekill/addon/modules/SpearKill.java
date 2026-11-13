package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * 长矛杀戮 - Blink/Lunge 攻击模块
 * 来自 InvincibleMachineGun 的 SpearKill
 *
 * 【Blink 模式】：冻结移动包 → 接近目标 → 一次性释放所有包（瞬移效果）
 * 【Lunge 模式】：利用长矛蓄力冲刺攻击
 *
 * 工作原理：
 * 1. Blink：玩家正常移动但发包被拦截，存在本地队列中；
 *    当到达指定距离时，一次性发送所有积攒的移动包，
 *    服务端看到的是瞬间移动，实现"闪现"到目标面前。
 * 2. Lunge：长矛蓄力满后，先瞬移到目标上方（FromAbove），
 *    然后释放蓄力，利用动量冲刺穿入目标完成攻击。
 *
 * 支持全部 7 种长矛变体：木 / 石 / 铜 / 铁 / 金 / 钻石 / 下界合金
 */
public class SpearKill extends Module {

    // ==================== 长矛全7种变体 ====================
    private static final Set<Item> SPEARS = Set.of(
            Items.WOODEN_SPEAR, Items.STONE_SPEAR, Items.COPPER_SPEAR,
            Items.IRON_SPEAR, Items.GOLDEN_SPEAR,
            Items.DIAMOND_SPEAR, Items.NETHERITE_SPEAR
    );

    // ==================== 枚举 ====================
    private enum Mode { Blink, Lunge }
    private enum LungeMode { Normal, FromAbove, Auto }
    public enum TargetListMode { Off, Whitelist }

    // ==================== 设置组 ====================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlink = settings.createGroup("Blink");
    private final SettingGroup sgLunge = settings.createGroup("Lunge");
    private final SettingGroup sgTarget = settings.createGroup("Target");

    // ---- 通用 ----
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
            .name("Mode").description("Blink=blink, Lunge=lunge")
            .defaultValue(Mode.Blink).build()
    );

    private final Setting<Double> maxRange = sgGeneral.add(new DoubleSetting.Builder()
            .name("Range").description("Max distance to search for targets")
            .defaultValue(50).min(1).max(200).sliderRange(1, 128).build()
    );

    private final Setting<Boolean> noFall = sgGeneral.add(new BoolSetting.Builder()
            .name("NoFall").description("Temporarily enable NoFall when attacking").defaultValue(true).build()
    );

    // ---- Blink ----
    private final Setting<Double> flushRange = sgBlink.add(new DoubleSetting.Builder()
            .name("Flush Distance").description("Release all queued packets at this distance from the target (teleport)")
            .defaultValue(2.0).min(0.5).max(10).sliderRange(0.5, 10)
            .visible(() -> mode.get() == Mode.Blink).build()
    );

    private final Setting<Double> maxFlushRange = sgBlink.add(new DoubleSetting.Builder()
            .name("Max Blink Distance").description("Force release beyond this distance even if flush distance not reached")
            .defaultValue(25).min(5).max(100).sliderRange(5, 100)
            .visible(() -> mode.get() == Mode.Blink).build()
    );

    private final Setting<Double> blinkDistanceBoost = sgBlink.add(new DoubleSetting.Builder()
            .name("Distance Boost").description("Extra blink distance").defaultValue(2)
            .min(0).max(10).sliderRange(0, 10)
            .visible(() -> mode.get() == Mode.Blink).build()
    );

    // ---- Lunge ----
    private final Setting<LungeMode> lungeMode = sgLunge.add(new EnumSetting.Builder<LungeMode>()
            .name("Lunge Direction").description("Normal=front, FromAbove=top, Auto=top preferred")
            .defaultValue(LungeMode.FromAbove)
            .visible(() -> mode.get() == Mode.Lunge).build()
    );

    private final Setting<Double> aboveHeight = sgLunge.add(new DoubleSetting.Builder()
            .name("Above Height").description("How high above the target to start the lunge")
            .defaultValue(10).min(1).max(50).sliderRange(1, 50)
            .visible(() -> mode.get() == Mode.Lunge && lungeMode.get() != LungeMode.Normal)
            .build()
    );

    private final Setting<Double> lungeStrength = sgLunge.add(new DoubleSetting.Builder()
            .name("Lunge Strength").description("Lunge power multiplier").defaultValue(1.5)
            .min(0.5).max(5).sliderRange(0.5, 5)
            .visible(() -> mode.get() == Mode.Lunge).build()
    );

    private final Setting<Boolean> stop = sgLunge.add(new BoolSetting.Builder()
            .name("Pause Before Hit").description("Brief pause in front of the target before lunging, looks more natural")
            .defaultValue(true)
            .visible(() -> mode.get() == Mode.Lunge).build()
    );

    private final Setting<Double> stopDistance = sgLunge.add(new DoubleSetting.Builder()
            .name("Stop Distance").description("Distance from target at which to pause").defaultValue(3)
            .min(0.5).max(10).sliderRange(0.5, 10)
            .visible(() -> mode.get() == Mode.Lunge && stop.get()).build()
    );

    // ---- 目标 ----
    private final Setting<TargetListMode> targetListMode = sgTarget.add(new EnumSetting.Builder<TargetListMode>()
            .name("List Mode").defaultValue(TargetListMode.Off).build()
    );

    private final Setting<String> targetList = sgTarget.add(new StringSetting.Builder()
            .name("Target List").description("Comma separated").defaultValue("")
            .visible(() -> targetListMode.get() != TargetListMode.Off).build()
    );

    private final Setting<Boolean> ignoreFriends = sgTarget.add(new BoolSetting.Builder()
            .name("Ignore Friends").defaultValue(true).build()
    );

    // ==================== 状态 ====================
    private final List<Packet<?>> packetQueue = new ArrayList<>();
    private boolean isBlinking;
    private boolean isFlushing;
    private Vec3d startPos;
    private boolean wasCharging;
    private double lastTargetDistance;
    private Entity currentTarget;

    public SpearKill() {
        super(MaceKillAddon.CATEGORY, "SpearKill", "Spear kill - Blink/Lunge attack");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        mc.options.useKey.setPressed(false);
        if (isBlinking) flushPackets();
        resetState();
    }

    private void resetState() {
        isBlinking = false;
        isFlushing = false;
        wasCharging = false;
        lastTargetDistance = Double.MAX_VALUE;
        currentTarget = null;
        synchronized (packetQueue) { packetQueue.clear(); }
    }

    // ==================== Tick ====================

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (mode.get() == Mode.Blink) {
            tickBlink();
        } else {
            tickLunge();
        }
    }

    // ========== Blink 逻辑 ==========
    //
    // Blink 模式工作流程：
    // 1. 找到目标，开始拦截所有 PlayerMoveC2SPacket（本地积攒，不让服务端知道你在移动）
    // 2. 本地正常走向目标，到达 释放距离(flushRange) 后触发释放
    // 3. 切换到长矛 → 蓄力 → 蓄满后一次性 flush 所有积攒的移动包
    //    服务端瞬间收到所有包，看到的是"瞬移"到目标面前
    // 4. 发送 PlayerInteractEntityC2SPacket.attack 完成攻击
    //    同时释放蓄力触发长矛冲刺，造成额外伤害
    // 5. 如果超出 最大闪现距离(maxFlushRange) 仍未接近目标 → 取消并 flush 包

    private void tickBlink() {
        if (!isBlinking) {
            currentTarget = findTarget();
            if (currentTarget == null) return;
            startBlink();
        }

        lastTargetDistance = mc.player.squaredDistanceTo(currentTarget);

        double dist = Math.sqrt(lastTargetDistance);
        double flushDist = flushRange.get();
        double maxDist = maxFlushRange.get() + blinkDistanceBoost.get();

        if (dist <= flushDist) {
            // 进入释放距离，切换到长矛并蓄力
            if (!isFlushing) {
                switchToSpear();
                mc.options.useKey.setPressed(true);
                isFlushing = true;
            }

            // 蓄力满 → 释放所有积攒包 + 攻击
            if (mc.player.getItemUseTime() >= getSpearChargeTicks()) {
                mc.options.useKey.setPressed(false);
                flushPackets();   // 释放积攒的移动包 → 服务端看到瞬移
                attackTarget();   // 发送攻击包
                resetState();
            }
        } else if (dist > maxDist && startPos != null) {
            // 超出最大闪现范围 → 取消本次闪现
            mc.options.useKey.setPressed(false);
            flushPackets();
            resetState();
        }
    }

    private void startBlink() {
        isBlinking = true;
        startPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        lastTargetDistance = mc.player.squaredDistanceTo(currentTarget);
        synchronized (packetQueue) { packetQueue.clear(); }
    }

    private void flushPackets() {
        if (mc.getNetworkHandler() == null) return;

        // ★ 必须先在发送任何包之前关闭 Blink，
        //    否则 onSendPacket 会把发出去的包重新捕获回队列，导致 ConcurrentModificationException 崩溃
        isBlinking = false;
        isFlushing = false;

        // 面向目标
        if (currentTarget != null) {
            float yaw = getYawTo(currentTarget);
            float pitch = getPitchTo(currentTarget);
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), false));
        }

        // 一次性释放所有积攒的移动包 → 服务端看到的是瞬间传送
        synchronized (packetQueue) {
            for (Packet<?> p : packetQueue) {
                mc.getNetworkHandler().sendPacket(p);
            }
            packetQueue.clear();
        }
    }

    private void attackTarget() {
        if (mc.getNetworkHandler() == null || currentTarget == null) return;
        mc.getNetworkHandler().sendPacket(
                PlayerInteractEntityC2SPacket.attack(currentTarget, mc.player.isSneaking()));
    }

    // ========== Lunge 逻辑 ==========
    //
    // Lunge 模式工作流程：
    // 1. 找到目标，切换到长矛
    // 2. 面向目标旋转
    // 3. FromAbove 模式：VClip 瞬移到目标上方，视角垂直朝下(pitch=-90)
    //    Normal 模式：在目标正面停止(可选)
    // 4. 蓄力长矛
    // 5. 蓄力满后 release → 长矛冲刺穿入目标造成伤害
    //    - FromAbove：从头顶俯冲而下，利用重力+冲刺最大化伤害
    //    - Normal：水平冲刺穿过目标
    // 6. 冲刺强度(lungeStrength) 通过设置额外的移动包来增加冲刺距离

    private void tickLunge() {
        currentTarget = findTarget();
        if (currentTarget == null) return;

        if (!isUsingSpear()) {
            switchToSpear();
            return;
        }

        boolean useAbove = lungeMode.get() == LungeMode.FromAbove
                || (lungeMode.get() == LungeMode.Auto);

        if (useAbove && !wasCharging) {
            Vec3d abovePos = findAbovePos(currentTarget);
            if (abovePos != null && mc.player.squaredDistanceTo(currentTarget) > 2) {
                // VClip 瞬移到目标上方
                for (double y = mc.player.getY(); y < abovePos.y; y += 8) {
                    mc.getNetworkHandler().sendPacket(
                            new PlayerMoveC2SPacket.PositionAndOnGround(abovePos.x,
                                    Math.min(y + 8, abovePos.y), abovePos.z, false, false));
                }
            }
            // 视角垂直朝下 → 让长矛冲刺方向朝下穿过目标
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(getYawTo(currentTarget), -90.0f, false, false));
        } else {
            // Normal 模式：水平面向目标
            rotateToTarget(currentTarget);
        }

        // 可选：在目标面前停顿（更自然）
        if (stop.get()) {
            double dist = mc.player.squaredDistanceTo(currentTarget);
            if (dist > stopDistance.get() * stopDistance.get()) return;
        }

        // 开始蓄力长矛
        if (!mc.options.useKey.isPressed()) {
            mc.options.useKey.setPressed(true);
            wasCharging = true;
        }

        // 蓄力满 → 释放冲刺 + 发送额外移动包增强冲刺距离
        if (mc.player.getItemUseTime() >= getSpearChargeTicks()) {
            mc.options.useKey.setPressed(false);

            // 额外冲刺移动包 → 增强长矛冲刺效果
            double strength = lungeStrength.get() - 1.0;
            if (strength > 0 && currentTarget != null) {
                Vec3d dir = new Vec3d(
                        currentTarget.getX() - mc.player.getX(),
                        0,
                        currentTarget.getZ() - mc.player.getZ()
                ).normalize().multiply(strength * 2);
                Vec3d through = new Vec3d(
                        currentTarget.getX() + dir.x,
                        currentTarget.getY(),
                        currentTarget.getZ() + dir.z);
                mc.getNetworkHandler().sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(through.x, through.y, through.z, false, false));
            }

            resetState();
        }
    }

    private Vec3d findAbovePos(Entity target) {
        double h = aboveHeight.get();
        BlockPos targetPos = target.getBlockPos();

        // 从设定高度向下扫描，找到第一个头脚都是空气的安全位置
        for (int yOff = (int) h; yOff > 0; yOff--) {
            BlockPos pos = new BlockPos(targetPos.getX(), targetPos.getY() + yOff, targetPos.getZ());
            if (isSafePosition(pos)) {
                return new Vec3d(target.getX(), pos.getY(), target.getZ());
            }
        }
        return null;
    }

    private boolean isSafePosition(BlockPos pos) {
        if (mc.world == null) return false;
        return mc.world.getBlockState(pos).isAir()
                && mc.world.getBlockState(pos.up()).isAir();
    }

    // ==================== 发包拦截 ====================

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!isBlinking) return;
        if (event.packet instanceof PlayerMoveC2SPacket movePacket) {
            synchronized (packetQueue) {
                // Cap the queue so a fleeing target can't OOM us or get us kicked for packet spam on flush.
                if (packetQueue.size() >= 500) {
                    flushPackets();
                    resetState();
                    event.cancel();
                    return;
                }
                packetQueue.add(movePacket);
            }
            event.cancel();
        }
    }

    // ==================== 目标查找 ====================

    private Entity findTarget() {
        if (mc.world == null || mc.player == null) return null;

        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        double range = maxRange.get();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;
            if (e == mc.player) continue;
            if (!isValidTarget(e)) continue;

            double dist = mc.player.squaredDistanceTo(e);
            if (dist < range * range && dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    private boolean isValidTarget(Entity entity) {
        if (!(entity instanceof LivingEntity le)) return false;
        if (le.isDead()) return false;

        if (entity instanceof PlayerEntity player) {
            if (player.isSpectator()) return false;
            if (ignoreFriends.get() && Friends.get().isFriend(player)) return false;
        }

        // 名单过滤
        if (targetListMode.get() == TargetListMode.Whitelist && entity instanceof PlayerEntity p) {
            Set<String> names = parseNameList();
            if (!names.isEmpty() && !names.contains(p.getName().getString())) return false;
        }

        return true;
    }

    private Set<String> parseNameList() {
        Set<String> set = new HashSet<>();
        for (String s : targetList.get().split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    // ==================== 工具 ====================

    private boolean isUsingSpear() {
        ItemStack stack = mc.player.getMainHandStack();
        return SPEARS.contains(stack.getItem());
    }

    private void switchToSpear() {
        if (isUsingSpear()) return;
        for (int i = 0; i < 9; i++) {
            if (SPEARS.contains(mc.player.getInventory().getStack(i).getItem())) {
                setSelectedSlot(i);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i));
                return;
            }
        }
    }

    private void setSelectedSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    private int getSpearChargeTicks() {
        // 长矛满蓄力大约需要 10 tick（20 tick/秒 × 0.5秒）
        return 10;
    }

    private void rotateToTarget(Entity target) {
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(getYawTo(target), getPitchTo(target),
                        mc.player.isOnGround(), false));
    }

    private float getYawTo(Entity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private float getPitchTo(Entity target) {
        double dx = target.getX() - mc.player.getX();
        double dy = target.getEyeY() - mc.player.getEyeY();
        double dz = target.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, dist));
    }

    @Override
    public String getInfoString() {
        return mode.get() == Mode.Blink
                ? (isBlinking ? "Blinking" : "Ready")
                : "Lunge";
    }
}
