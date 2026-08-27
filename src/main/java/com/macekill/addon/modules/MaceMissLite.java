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
import net.minecraft.entity.player.PlayerInventory;
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
    private final SettingGroup sgTarget = settings.createGroup("Target");
    private final SettingGroup sgArmor = settings.createGroup("Armor Break");
    private final SettingGroup sgTotem = settings.createGroup("Totem Bypass");

    // 通用
    private final Setting<Double> range = sgMain.add(new DoubleSetting.Builder()
            .name("Range").description("Detection range")
            .defaultValue(20.0).min(1.0).max(200.0).sliderRange(1.0, 128.0).build());

    private final Setting<Double> moveDistance = sgMain.add(new DoubleSetting.Builder()
            .name("Move Step").description("Max distance per move packet")
            .defaultValue(8.0).min(1.0).max(128.0).sliderRange(1.0, 128.0).build());

    private final Setting<List<String>> killHeights = sgMain.add(new StringListSetting.Builder()
            .name("Kill Heights").description("Heights used for kill attacks")
            .defaultValue("10", "20", "30").build());

    private final Setting<Integer> attackDelay = sgMain.add(new IntSetting.Builder()
            .name("Attack Delay").description("Attack interval in ticks")
            .defaultValue(10).min(0).max(40).sliderMax(40).build());

    private final Setting<Boolean> predict = sgMain.add(new BoolSetting.Builder()
            .name("Predict Position").description("Predict target's future position").defaultValue(true).build());

    private final Setting<Integer> predictTicks = sgMain.add(new IntSetting.Builder()
            .name("Predict Ticks").description("Ticks to predict ahead")
            .defaultValue(5).min(1).sliderMax(20).visible(predict::get).build());

    // dv偏移
    private final Setting<Double> dv1 = sgMain.add(new DoubleSetting.Builder()
            .name("Attack 1 Offset").description("Horizontal offset for the first attack")
            .defaultValue(0.0).min(0).max(10).sliderMax(5).build());

    private final Setting<Double> dv2 = sgMain.add(new DoubleSetting.Builder()
            .name("Attack 2 Offset").description("Horizontal offset for the second attack")
            .defaultValue(0.0).min(0).max(10).sliderMax(5).build());

    private final Setting<Boolean> swingHand = sgMain.add(new BoolSetting.Builder()
            .name("Swing Hand").description("Swing hand when attacking").defaultValue(false).build());

    // 强制忽略
    private final Setting<Boolean> forceIgnore = sgMain.add(new BoolSetting.Builder()
            .name("Force Skip").description("Enable to skip the current target").defaultValue(false).build());

    // 目标
    private final Setting<Boolean> targetPlayers = sgTarget.add(new BoolSetting.Builder()
            .name("Players").description("Attack players").defaultValue(true).build());

    private final Setting<Boolean> targetHostiles = sgTarget.add(new BoolSetting.Builder()
            .name("Hostile Mobs").description("Attack hostile mobs").defaultValue(true).build());

    private final Setting<Boolean> targetAnimals = sgTarget.add(new BoolSetting.Builder()
            .name("Animals").description("Attack animals").defaultValue(true).build());

    private final Setting<SortPriority> sortPriority = sgTarget.add(new EnumSetting.Builder<SortPriority>()
            .name("Sort Priority").description("How targets are sorted")
            .defaultValue(SortPriority.DISTANCE).build());

    private final Setting<Boolean> autoAttackEntity = sgTarget.add(new BoolSetting.Builder()
            .name("Auto Attack Entities").description("Attack any living entity, ignoring the target filters above")
            .defaultValue(false).build());

    // 破甲
    private final Setting<Boolean> destroyArmor = sgArmor.add(new BoolSetting.Builder()
            .name("Armor Break").description("Break armor before killing").defaultValue(false).build());

    private final Setting<Integer> ignoreValue = sgArmor.add(new IntSetting.Builder()
            .name("Armor Threshold").description("Switch to kill when target armor is at or below this")
            .defaultValue(0).min(0).max(4).sliderMax(4)
            .visible(destroyArmor::get).build());

    private final Setting<List<String>> destroyHeights = sgArmor.add(new StringListSetting.Builder()
            .name("Armor Break Heights").description("Heights used for armor-break attacks")
            .defaultValue("30", "60").visible(destroyArmor::get).build());

    private final Setting<Boolean> bypassTotem = sgTotem.add(new BoolSetting.Builder()
            .name("Totem Bypass").description("Drain totems with multi-height hits, then kill at full height")
            .defaultValue(false).build());

    private final Setting<Boolean> detectTotem = sgTotem.add(new BoolSetting.Builder()
            .name("Detect Totem").description("Only drain if the target is actually holding a totem")
            .defaultValue(true).visible(bypassTotem::get).build());

    private final Setting<DrainMode> drainMode = sgTotem.add(new EnumSetting.Builder<DrainMode>()
            .name("Drain Mode").description("List: use custom height list | Incremental: base + step per hit")
            .defaultValue(DrainMode.LIST).visible(bypassTotem::get).build());

    private final Setting<List<String>> drainHeights = sgTotem.add(new StringListSetting.Builder()
            .name("Drain Heights").description("Heights used to drain totems before the kill")
            .defaultValue("4", "8", "12", "16")
            .visible(() -> bypassTotem.get() && drainMode.get() == DrainMode.LIST).build());

    private final Setting<Integer> baseDrainHeight = sgTotem.add(new IntSetting.Builder()
            .name("Base Drain Height").description("Starting height for incremental drain attacks")
            .defaultValue(4).min(1).max(50).sliderMax(50)
            .visible(() -> bypassTotem.get() && drainMode.get() == DrainMode.INCREMENTAL).build());

    private final Setting<Integer> heightIncrement = sgTotem.add(new IntSetting.Builder()
            .name("Height Increment").description("Added height per drain attack")
            .defaultValue(4).min(1).max(20).sliderMax(20)
            .visible(() -> bypassTotem.get() && drainMode.get() == DrainMode.INCREMENTAL).build());

    private final Setting<Integer> totemAttacks = sgTotem.add(new IntSetting.Builder()
            .name("Totem Attacks").description("Number of drain attacks before the kill")
            .defaultValue(4).min(1).max(15).sliderMax(15)
            .visible(bypassTotem::get).build());

    private final Setting<Integer> totemsToPop = sgTotem.add(new IntSetting.Builder()
            .name("Totems To Pop").description("How many totems to pop (1-198). Hits are fired across ticks so every totem pops reliably")
            .defaultValue(24).min(1).max(198).sliderMax(198)
            .visible(bypassTotem::get).build());

    private final Setting<Boolean> singleTick = sgTotem.add(new BoolSetting.Builder()
            .name("Single Tick").description("Fire every escalating hit in one tick (one mace smash). Turn OFF to spread across ticks if your server only lets 1 totem pop per tick")
            .defaultValue(true).visible(bypassTotem::get).build());

    private final Setting<Integer> bypassHitsPerTick = sgTotem.add(new IntSetting.Builder()
            .name("Hits Per Tick").description("Bypass hits fired each tick when Single Tick is OFF. 1 = 1 totem/tick. Raise it if your server lets multiple mace hits land per tick")
            .defaultValue(1).min(1).max(20).sliderMax(20)
            .visible(() -> bypassTotem.get() && !singleTick.get()).build());

    // 渲染
    private final Setting<Boolean> renderBox = sgMain.add(new BoolSetting.Builder()
            .name("Render Box").description("Render box around the target").defaultValue(true).build());

    private final Setting<SettingColor> boxColor = sgMain.add(new ColorSetting.Builder()
            .name("Box Color").description("Render color")
            .defaultValue(new SettingColor(255, 0, 0, 100)).build());

    /* ========== 状态 ========== */
    private final MSTimer attackTimer = new MSTimer();
    private LivingEntity currentTarget;
    private int attackIndex;
    private int delayTicks;
    private Phase phase = Phase.IDLE;
    private Vec3d originalPos;
    private Combat.BypassRunner bypassRunner;

    private enum Phase { IDLE, DELAY }

    public MaceMissLite() {
        super(MaceKillAddon.CATEGORY, "MaceMissLite", "Lite mace missile - dual offsets+force skip+armor break");
    }

    @Override
    public void onDeactivate() {
        currentTarget = null;
        attackIndex = 0;
        delayTicks = 0;
        phase = Phase.IDLE;
        bypassRunner = null;
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
        delayTicks = this.bypassTotem.get() ? 0 : attackDelay.get();
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
        if (autoAttackEntity.get()) return true;
        if (entity instanceof HostileEntity) return targetHostiles.get();
        if (entity instanceof AnimalEntity) return targetAnimals.get();
        return false;
    }

    // ========== 攻击流程 ==========

    private void doAura() {
        if (currentTarget == null || mc.getNetworkHandler() == null) return;

        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        if (bypassTotem.get() && currentTarget instanceof PlayerEntity p
                && (!detectTotem.get() || targetHasTotem(p))) {
            int totems = totemsToPop.get();
            if (detectTotem.get()) totems = Math.min(totems, countTotems(p));
            totems = Math.min(totems, Combat.MAX_TOTEM_HITS);
            List<String> all = Combat.totemBypassHeights(getDrainHeights(), totems, 170, 3);
            if (singleTick.get()) {
                // One mace smash: every escalating hit in a single tick.
                doTpAura(all);
                return;
            }
            if (bypassRunner == null) {
                bypassRunner = new Combat.BypassRunner(Combat.parseHeights(all));
            }
            List<String> slice = new ArrayList<>();
            for (double d : bypassRunner.next(bypassHitsPerTick.get())) slice.add(String.valueOf(d));
            doTpAura(slice);
            if (!bypassRunner.hasMore()) bypassRunner = null;
            return;
        }

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

    // ========== 渲染 ==========

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderBox.get() || currentTarget == null) return;
        Box box = currentTarget.getBoundingBox();
        event.renderer.box(box, boxColor.get(), boxColor.get(), ShapeMode.Both, 0);
    }

    @Override
    public String getInfoString() {
        if (currentTarget == null) return "No target";
        if (phase == Phase.DELAY && bypassTotem.get()) {
            return "Totem Bypass";
        }
        if (phase == Phase.DELAY) return "CD " + delayTicks;
        String name = currentTarget instanceof PlayerEntity p ? p.getName().getString()
                : currentTarget.getType().getName().getString();
        return name;
    }

    private enum DrainMode {
        LIST,
        INCREMENTAL
    }
}
