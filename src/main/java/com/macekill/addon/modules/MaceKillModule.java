package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.Combat;
import com.macekill.addon.modules.macekill.Config;
import com.macekill.addon.modules.macekill.Inventory;
import com.macekill.addon.modules.macekill.Movement;
import com.macekill.addon.modules.macekill.SortPriority;
import com.macekill.addon.modules.macekill.TargetFilter;
import com.macekill.addon.modules.macekill.Targeting;
import java.util.List;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

public class MaceKillModule extends Module {
    private final SettingGroup sgGeneral;
    private final SettingGroup sgTarget;
    private final SettingGroup sgKill;
    private final SettingGroup sgDestory;

    private final Setting<Double> range;
    private final Setting<Double> moveDistance;
    private final Setting<Boolean> swingHand;
    private final Setting<Boolean> requireFullCooldown;
    private final Setting<Integer> teleportDelay;
    private final Setting<Boolean> spamRotations;
    private final Setting<Boolean> autoTotem;
    private final Setting<Boolean> kehd;
    private final Setting<Boolean> predict;
    private final Setting<Integer> predictTicks;
    private final Setting<Boolean> enableArmorDestroy;
    private final Setting<Integer> ignoreArmorValue;
    private final Setting<List<String>> destroyHeights;
    private final Setting<List<String>> killHeights;

    private final Setting<Boolean> targetPlayers;
    private final Setting<Boolean> targetHostiles;
    private final Setting<Boolean> targetAnimals;
    private final Setting<Boolean> targetOthers;
    private final Setting<SortPriority> sortPriority;

    private Phase phase;
    private int delayTicks;
    private Vec3d originalPos;
    private Vec3d targetPos;
    private LivingEntity target;

    public MaceKillModule() {
        super(MaceKillAddon.CATEGORY, "macemiss", "瞬移到目标旁，VClip起跳攻击");
        this.sgGeneral = this.settings.getDefaultGroup();
        this.sgTarget = this.settings.createGroup("目标选择");
        this.sgKill = this.settings.createGroup("击杀高度");
        this.sgDestory = this.settings.createGroup("破甲高度");

        this.range = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("范围")
            .description("检测周围生物的距离")
            .defaultValue(20.0)
            .min(1.0)
            .max(200.0)
            .sliderRange(1.0, 128.0)
            .build());
        this.moveDistance = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("移动步长")
            .description("每个移动包的最大距离")
            .defaultValue(20.0)
            .min(1.0)
            .max(128.0)
            .sliderRange(1.0, 128.0)
            .build());
        this.swingHand = this.sgGeneral.add(new BoolSetting.Builder()
            .name("挥手")
            .description("攻击时客户端挥手")
            .defaultValue(false)
            .build());
        this.requireFullCooldown = this.sgGeneral.add(new BoolSetting.Builder()
            .name("需要满冷却")
            .description("攻击需要满冷却才执行")
            .defaultValue(false)
            .build());
        this.teleportDelay = this.sgGeneral.add(new IntSetting.Builder()
            .name("传送延迟")
            .description("传送后等待的tick数，0为无延迟")
            .defaultValue(5)
            .range(0, 20)
            .build());
        this.spamRotations = this.sgGeneral.add(new BoolSetting.Builder()
            .name("垃圾旋转包")
            .description("在第一次传送前发送4个旋转包")
            .defaultValue(false)
            .build());
        this.autoTotem = this.sgGeneral.add(new BoolSetting.Builder()
            .name("自动切图腾")
            .description("攻击结束后自动换回图腾")
            .defaultValue(false)
            .build());
        this.kehd = this.sgGeneral.add(new BoolSetting.Builder()
            .name("kehud")
            .description("同时更新客户端位置")
            .defaultValue(false)
            .build());
        this.predict = this.sgGeneral.add(new BoolSetting.Builder()
            .name("预测位置")
            .description("根据目标速度预测其位置")
            .defaultValue(true)
            .build());
        this.predictTicks = this.sgGeneral.add(new IntSetting.Builder()
            .name("预测tick")
            .description("预测的tick数")
            .defaultValue(5)
            .min(1)
            .sliderMax(20)
            .visible(() -> this.predict.get())
            .build());
        this.enableArmorDestroy = this.sgGeneral.add(new BoolSetting.Builder()
            .name("启用破甲")
            .description("先使用破甲高度攻击直到目标护甲不足")
            .defaultValue(false)
            .build());
        this.ignoreArmorValue = this.sgGeneral.add(new IntSetting.Builder()
            .name("破甲阈值")
            .description("目标剩余护甲≤此值时改用击杀高度")
            .defaultValue(0)
            .min(0)
            .max(4)
            .sliderMax(4)
            .visible(() -> this.enableArmorDestroy.get())
            .build());
        this.targetPlayers = this.sgTarget.add(new BoolSetting.Builder()
            .name("玩家")
            .description("以玩家为目标")
            .defaultValue(true)
            .build());
        this.targetHostiles = this.sgTarget.add(new BoolSetting.Builder()
            .name("敌对生物")
            .description("以敌对生物为目标")
            .defaultValue(true)
            .build());
        this.targetAnimals = this.sgTarget.add(new BoolSetting.Builder()
            .name("动物")
            .description("以动物为目标")
            .defaultValue(true)
            .build());
        this.targetOthers = this.sgTarget.add(new BoolSetting.Builder()
            .name("其他生物")
            .description("以其他生物为目标")
            .defaultValue(false)
            .build());
        this.sortPriority = this.sgTarget.add(new EnumSetting.Builder<SortPriority>()
            .name("优先攻击")
            .description("距离最近/最接近准星/血量最低")
            .defaultValue(SortPriority.DISTANCE)
            .build());
        this.destroyHeights = this.sgDestory.add(new StringListSetting.Builder()
            .name("破甲高度列表")
            .description("破坏护甲时使用的高度")
            .defaultValue("30", "60")
            .visible(() -> this.enableArmorDestroy.get())
            .build());
        this.killHeights = this.sgKill.add(new StringListSetting.Builder()
            .name("击杀高度列表")
            .description("击杀时使用的高度")
            .defaultValue("10", "20", "30")
            .build());

        this.phase = Phase.IDLE;
    }

    @Override
    public void onDeactivate() {
        this.phase = Phase.IDLE;
        this.delayTicks = 0;
        this.target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null) {
            this.phase = Phase.IDLE;
            return;
        }
        if (this.autoTotem.get()) {
            Inventory.ensureTotem(this.mc);
        }
        switch (this.phase) {
            case IDLE -> {
                if (this.requireFullCooldown.get()
                        && this.mc.player.getAttackCooldownProgress(0.0f) < 1.0f) {
                    return;
                }
                this.tickIdle();
            }
            case START_DELAY -> this.tickStartDelay();
            case RETURN_DELAY -> this.tickReturnDelay();
        }
    }

    private void tickIdle() {
        TargetFilter filter = new TargetFilter(
            this.targetPlayers.get(),
            this.targetHostiles.get(),
            this.targetAnimals.get(),
            this.targetOthers.get()
        );
        this.target = Targeting.findBestTarget(
            this.mc, this.range.get(), filter, this.sortPriority.get());
        if (this.target == null) {
            return;
        }
        this.originalPos = new Vec3d(this.mc.player.getX(), this.mc.player.getY(), this.mc.player.getZ());
        this.targetPos = Targeting.predictPosition(
            this.mc, this.target, this.predict.get(), this.predictTicks.get());
        if (this.spamRotations.get()) {
            Movement.sendRotations(this.mc, 4);
        }
        Movement.doTpTo(this.mc, this.targetPos, this.moveDistance.get(), this.kehd.get());
        int delay = this.teleportDelay.get();
        if (delay > 0) {
            this.phase = Phase.START_DELAY;
            this.delayTicks = delay;
        } else {
            this.executeAndReturn();
        }
    }

    private void tickStartDelay() {
        Movement.doTpTo(this.mc, this.targetPos, this.moveDistance.get(), this.kehd.get());
        if (this.kehd.get()) {
            this.mc.player.updatePosition(this.targetPos.x, this.targetPos.y, this.targetPos.z);
        }
        this.delayTicks--;
        if (this.delayTicks <= 0) {
            if (this.target == null || !this.target.isAlive()
                    || this.mc.player.squaredDistanceTo(this.target) > this.range.get() * this.range.get()) {
                this.doReturn();
            } else {
                this.executeAndReturn();
            }
        }
    }

    private void tickReturnDelay() {
        this.doReturn();
        this.delayTicks--;
        if (this.delayTicks <= 0) {
            this.phase = Phase.IDLE;
            this.target = null;
        }
    }

    private void executeAndReturn() {
        this.executeAttack();
        this.doReturn();
        int delay = this.teleportDelay.get();
        if (delay > 0) {
            this.phase = Phase.RETURN_DELAY;
            this.delayTicks = delay;
        } else {
            this.phase = Phase.IDLE;
            this.target = null;
        }
    }

    private void executeAttack() {
        Config config = new Config(
            this.moveDistance.get(),
            this.swingHand.get(),
            this.autoTotem.get(),
            this.kehd.get(),
            this.enableArmorDestroy.get(),
            this.ignoreArmorValue.get(),
            this.destroyHeights.get(),
            this.killHeights.get()
        );
        Combat.executeAttack(this.mc, config, this.target, this.targetPos);
    }

    private void doReturn() {
        if (this.originalPos == null) {
            return;
        }
        Movement.doTpTo(this.mc, this.originalPos, this.moveDistance.get(), this.kehd.get());
    }

    @Override
    public String getInfoString() {
        int count = Combat.parseHeights(this.killHeights.get()).size();
        if (count == 0) return "待配置";
        String priority = switch (this.sortPriority.get()) {
            case DISTANCE -> "距离";
            case ANGLE -> "角度";
            case HEALTH -> "血量";
        };
        return count + "次 | " + priority;
    }

    private enum Phase {
        IDLE,
        START_DELAY,
        RETURN_DELAY
    }
}
