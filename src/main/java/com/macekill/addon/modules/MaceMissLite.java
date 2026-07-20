package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.Combat;
import com.macekill.addon.modules.macekill.Inventory;
import com.macekill.addon.modules.macekill.Movement;
import com.macekill.addon.modules.macekill.SortPriority;
import com.macekill.addon.modules.macekill.Targeting;
import java.util.*;
import java.util.function.Predicate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * 重锤导弹精简版 — MaceMissLite
 *
 * 核心特性：
 * - 强制忽略按键：按住跳过当前目标
 * - dv1/dv2 双偏移值控制攻击角度
 * - 破甲模式 + 击杀模式双路攻击流程
 * - 预测位置/碰撞箱
 * - 毫秒计时器
 * - 渲染方块框
 */
public class MaceMissLite extends Module {

    /* ========== MSTimer — 毫秒级计时器 ========== */
    private static class MSTimer {
        private long lastMS;
        boolean hasPassed(long ms) {
            return System.currentTimeMillis() - lastMS >= ms;
        }
        void reset() { lastMS = System.currentTimeMillis(); }
    }

    /* ========== 设置 ========== */
    private final SettingGroup sgMain = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("目标");
    private final SettingGroup sgArmor = settings.createGroup("破甲");

    // 通用
    private final Setting<Double> range = sgMain.add(new DoubleSetting.Builder()
            .name("范围").description("检测范围")
            .defaultValue(20.0).min(1.0).max(200.0).sliderRange(1.0, 128.0).build());

    private final Setting<Double> moveDistance = sgMain.add(new DoubleSetting.Builder()
            .name("移动步长").description("每个移动包的最大距离")
            .defaultValue(8.0).min(1.0).max(128.0).sliderRange(1.0, 128.0).build());

    private final Setting<List<String>> killHeights = sgMain.add(new StringListSetting.Builder()
            .name("击杀高度").description("击杀攻击使用的高度列表")
            .defaultValue("10", "20", "30").build());

    private final Setting<Integer> attackDelay = sgMain.add(new IntSetting.Builder()
            .name("攻击延迟").description("攻击间隔(tick)")
            .defaultValue(10).min(0).max(40).sliderMax(40).build());

    private final Setting<Boolean> predict = sgMain.add(new BoolSetting.Builder()
            .name("预测位置").description("预测目标未来位置").defaultValue(true).build());

    private final Setting<Integer> predictTicks = sgMain.add(new IntSetting.Builder()
            .name("预测tick").description("预测tick数")
            .defaultValue(5).min(1).sliderMax(20).visible(predict::get).build());

    // dv偏移
    private final Setting<Double> dv1 = sgMain.add(new DoubleSetting.Builder()
            .name("偏移dv1").description("第一次攻击的水平偏移")
            .defaultValue(0.0).min(0).max(10).sliderMax(5).build());

    private final Setting<Double> dv2 = sgMain.add(new DoubleSetting.Builder()
            .name("偏移dv2").description("第二次攻击的水平偏移")
            .defaultValue(0.0).min(0).max(10).sliderMax(5).build());

    private final Setting<Boolean> swingHand = sgMain.add(new BoolSetting.Builder()
            .name("挥手").description("攻击时挥手").defaultValue(false).build());

    // 强制忽略
    private final Setting<Boolean> forceIgnore = sgMain.add(new BoolSetting.Builder()
            .name("强制忽略").description("启用以跳过当前目标").defaultValue(false).build());

    // 目标
    private final Setting<Boolean> targetPlayers = sgTarget.add(new BoolSetting.Builder()
            .name("玩家").description("攻击玩家").defaultValue(true).build());

    private final Setting<Boolean> targetHostiles = sgTarget.add(new BoolSetting.Builder()
            .name("敌对生物").description("攻击敌对生物").defaultValue(true).build());

    private final Setting<Boolean> targetAnimals = sgTarget.add(new BoolSetting.Builder()
            .name("动物").description("攻击动物").defaultValue(true).build());

    private final Setting<SortPriority> sortPriority = sgTarget.add(new EnumSetting.Builder<SortPriority>()
            .name("排序方式").description("目标排序方式")
            .defaultValue(SortPriority.DISTANCE).build());

    // 破甲
    private final Setting<Boolean> destroyArmor = sgArmor.add(new BoolSetting.Builder()
            .name("破甲").description("先破甲再击杀").defaultValue(false).build());

    private final Setting<Integer> ignoreValue = sgArmor.add(new IntSetting.Builder()
            .name("破甲阈值").description("目标剩余护甲≤此值时改用击杀")
            .defaultValue(0).min(0).max(4).sliderMax(4)
            .visible(destroyArmor::get).build());

    private final Setting<List<String>> destroyHeights = sgArmor.add(new StringListSetting.Builder()
            .name("破甲高度").description("破甲攻击使用的高度")
            .defaultValue("30", "60").visible(destroyArmor::get).build());

    // 渲染
    private final Setting<Boolean> renderBox = sgMain.add(new BoolSetting.Builder()
            .name("渲染方块").description("渲染目标方块框").defaultValue(true).build());

    private final Setting<SettingColor> boxColor = sgMain.add(new ColorSetting.Builder()
            .name("方块颜色").description("渲染颜色")
            .defaultValue(new SettingColor(255, 0, 0, 100)).build());

    /* ========== 状态 ========== */
    private final MSTimer attackTimer = new MSTimer();
    private LivingEntity currentTarget;
    private int attackIndex;
    private int delayTicks;
    private Phase phase = Phase.IDLE;
    private Vec3d originalPos;

    private enum Phase { IDLE, DELAY }

    public MaceMissLite() {
        super(MaceKillAddon.CATEGORY, "MaceMissLite", "重锤导弹精简版 - 双偏移+强制忽略+破甲");
    }

    @Override
    public void onDeactivate() {
        currentTarget = null;
        attackIndex = 0;
        delayTicks = 0;
        phase = Phase.IDLE;
    }

    /* ========== Tick ========== */

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (phase) {
            case IDLE -> {
                if (delayTicks > 0) { delayTicks--; return; }
                tickIdle();
            }
            case DELAY -> {
                delayTicks--;
                if (delayTicks <= 0) {
                    phase = Phase.IDLE;
                    attackIndex = 0;
                }
            }
        }
    }

    private void tickIdle() {
        updateTarget();
        if (currentTarget == null) return;
        if (isAboutToBeIgnored()) {
            currentTarget = null;
            return;
        }
        doAura();
        delayTicks = attackDelay.get();
        phase = Phase.DELAY;
    }

    // ========== 目标更新 ==========

    private void updateTarget() {
        List<LivingEntity> validTargets = new ArrayList<>();
        double rangeSq = range.get() * range.get();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le == mc.player) continue;
            if (mc.player.squaredDistanceTo(le) > rangeSq) continue;
            if (!targetCheck(le)) continue;
            validTargets.add(le);
        }

        if (validTargets.isEmpty()) { currentTarget = null; return; }

        validTargets.sort((a, b) -> switch (sortPriority.get()) {
            case DISTANCE ->
                Double.compare(mc.player.squaredDistanceTo(a), mc.player.squaredDistanceTo(b));
            case HEALTH ->
                Float.compare(a.getHealth(), b.getHealth());
            case ANGLE -> {
                Vec3d look = mc.player.getRotationVec(1.0f);
                Vec3d eye = mc.player.getEyePos();
                double aDot = look.dotProduct(a.getEyePos().subtract(eye).normalize());
                double bDot = look.dotProduct(b.getEyePos().subtract(eye).normalize());
                yield Double.compare(bDot, aDot);
            }
        });
        currentTarget = validTargets.get(0);
    }

    private boolean targetCheck(LivingEntity entity) {
        if (entity instanceof PlayerEntity p) {
            if (p.isCreative() || p.isSpectator() || !Friends.get().shouldAttack(p)) return false;
            return targetPlayers.get();
        }
        if (entity instanceof HostileEntity) return targetHostiles.get();
        if (entity instanceof AnimalEntity) return targetAnimals.get();
        return false;
    }

    // ========== 攻击流程 ==========

    private void doAura() {
        if (currentTarget == null || mc.getNetworkHandler() == null) return;

        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        if (destroyArmor.get() && currentTarget instanceof PlayerEntity p && !isNaked(p)) {
            doDestroyArmor();
        } else {
            doTpAura(killHeights.get());
        }
    }

    private void doDestroyArmor() {
        doTpAura(destroyHeights.get());
    }

    private void doTpAura(List<String> rawHeights) {
        List<Double> heights = Combat.parseHeights(rawHeights);
        if (heights.isEmpty()) heights.add(20.0);

        int oldSlot = Inventory.switchToMace(mc);
        if (oldSlot == -1) return;

        try {
            Vec3d targetPos = Targeting.predictPosition(mc, currentTarget, predict.get(), predictTicks.get());
            Vec3d basePos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

            for (int i = 0; i < heights.size(); i++) {
                double h = heights.get(i);
                // 应用 dv 偏移
                if (i == 0 && dv1.get() > 0) targetPos = applyDv(targetPos, dv1.get());
                if (i == 1 && dv2.get() > 0) targetPos = applyDv(targetPos, dv2.get());

                BlockPos vclipHole = Combat.findVclipHole(mc,
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), h);
                Vec3d vclipPos = Vec3d.ofBottomCenter(vclipHole);

                Movement.doTpTo(mc, vclipPos, moveDistance.get(), false);
                Movement.sendMovePacket(mc, targetPos.x, targetPos.y + 0.5, targetPos.z);
                Movement.sendMovePacket(mc, basePos.x, basePos.y, basePos.z);

                if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
                Movement.attackEntity(mc, currentTarget);
            }
        } finally {
            Inventory.switchBack(mc, oldSlot);
        }
    }

    private Vec3d applyDv(Vec3d pos, double offset) {
        double angle = Math.random() * Math.PI * 2;
        return new Vec3d(
                pos.x + Math.cos(angle) * offset,
                pos.y,
                pos.z + Math.sin(angle) * offset);
    }

    // ========== 判定 ==========

    private boolean isAboutToBeIgnored() {
        return forceIgnore.get();
    }

    private boolean isNaked(PlayerEntity player) {
        return Combat.needsArmorDestroy(player, ignoreValue.get()) == false;
        // needsArmorDestroy returns count > ignoreValue → 需要破甲
        // 所以 !needsArmorDestroy = 护甲 <= ignoreValue = 已经破甲 → isNaked
    }

    // ========== 渲染 ==========

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderBox.get() || currentTarget == null) return;
        Box box = currentTarget.getBoundingBox();
        event.renderer.box(box, boxColor.get(), boxColor.get(), ShapeMode.Both, 0);
    }

    @Override
    public String getInfoString() {
        if (currentTarget == null) return "无目标";
        if (phase == Phase.DELAY) return "CD " + delayTicks;
        String name = currentTarget instanceof PlayerEntity p ? p.getName().getString()
                : currentTarget.getType().getName().getString();
        return name;
    }
}
