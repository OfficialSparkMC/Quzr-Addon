package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.Combat;
import com.macekill.addon.modules.macekill.Config;
import com.macekill.addon.modules.macekill.Inventory;
import com.macekill.addon.modules.macekill.Movement;
import com.macekill.addon.modules.macekill.SortPriority;
import com.macekill.addon.modules.macekill.TargetFilter;
import com.macekill.addon.modules.macekill.Targeting;
import java.util.ArrayList;
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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

public class MaceKillModule extends Module {
    private final SettingGroup sgGeneral;
    private final SettingGroup sgTarget;
    private final SettingGroup sgKill;
    private final SettingGroup sgDestroy;
    private final SettingGroup sgTotem;

    private final Setting<Double> range;
    private final Setting<Double> moveDistance;
    private final Setting<Boolean> swingHand;
    private final Setting<Boolean> requireFullCooldown;
    private final Setting<Integer> teleportDelay;
    private final Setting<Boolean> spamRotations;
    private final Setting<Boolean> autoTotem;
    private final Setting<Boolean> syncClientPos;
    private final Setting<Boolean> predict;
    private final Setting<Integer> predictTicks;
    private final Setting<Boolean> enableArmorDestroy;
    private final Setting<Integer> ignoreArmorValue;
    private final Setting<List<String>> destroyHeights;
    private final Setting<List<String>> killHeights;
    private final Setting<Boolean> bypassTotem;
    private final Setting<Boolean> detectTotem;
    private final Setting<DrainMode> drainMode;
    private final Setting<List<String>> drainHeights;
    private final Setting<Integer> baseDrainHeight;
    private final Setting<Integer> heightIncrement;
    private final Setting<Integer> totemAttacks;

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
    private LivingEntity drainTarget;
    private int totemHits;

    public MaceKillModule() {
        super(MaceKillAddon.CATEGORY, "macemiss", "Teleport next to target and VClip jump attack");
        this.sgGeneral = this.settings.getDefaultGroup();
        this.sgTarget = this.settings.createGroup("Targeting");
        this.sgKill = this.settings.createGroup("Kill Heights");
        this.sgDestroy = this.settings.createGroup("Armor Break Heights");
        this.sgTotem = this.settings.createGroup("Totem Bypass");

        this.range = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("Range")
            .description("Distance to detect nearby entities")
            .defaultValue(20.0)
            .min(1.0)
            .max(200.0)
            .sliderRange(1.0, 128.0)
            .build());
        this.moveDistance = this.sgGeneral.add(new DoubleSetting.Builder()
            .name("Move Step")
            .description("Max distance per move packet")
            .defaultValue(20.0)
            .min(1.0)
            .max(128.0)
            .sliderRange(1.0, 128.0)
            .build());
        this.swingHand = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Swing Hand")
            .description("Swing hand client-side on attack")
            .defaultValue(false)
            .build());
        this.requireFullCooldown = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Require Full Cooldown")
            .description("Only attack when weapon cooldown is full")
            .defaultValue(false)
            .build());
        this.teleportDelay = this.sgGeneral.add(new IntSetting.Builder()
            .name("Teleport Delay")
            .description("Ticks to wait after teleport, 0 = no delay")
            .defaultValue(5)
            .range(0, 20)
            .build());
        this.spamRotations = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Spam Rotations")
            .description("Send 4 rotation packets before the first teleport")
            .defaultValue(false)
            .build());
        this.autoTotem = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Auto Totem")
            .description("Auto-switch back to a totem after attacking")
            .defaultValue(false)
            .build());
        this.syncClientPos = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Sync Client Position")
            .description("Also update the client-side position")
            .defaultValue(false)
            .build());
        this.predict = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Predict Position")
            .description("Predict target position from velocity")
            .defaultValue(true)
            .build());
        this.predictTicks = this.sgGeneral.add(new IntSetting.Builder()
            .name("Predict Ticks")
            .description("Ticks to predict ahead")
            .defaultValue(5)
            .min(1)
            .sliderMax(20)
            .visible(() -> this.predict.get())
            .build());
        this.enableArmorDestroy = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Armor Break")
            .description("Attack with break heights until target armor is low")
            .defaultValue(false)
            .build());
        this.ignoreArmorValue = this.sgGeneral.add(new IntSetting.Builder()
            .name("Armor Threshold")
            .description("Switch to kill heights when target armor is at or below this")
            .defaultValue(0)
            .min(0)
            .max(4)
            .sliderMax(4)
            .visible(() -> this.enableArmorDestroy.get())
            .build());
        this.targetPlayers = this.sgTarget.add(new BoolSetting.Builder()
            .name("Players")
            .description("Target players")
            .defaultValue(true)
            .build());
        this.targetHostiles = this.sgTarget.add(new BoolSetting.Builder()
            .name("Hostile Mobs")
            .description("Target hostile mobs")
            .defaultValue(true)
            .build());
        this.targetAnimals = this.sgTarget.add(new BoolSetting.Builder()
            .name("Animals")
            .description("Target animals")
            .defaultValue(true)
            .build());
        this.targetOthers = this.sgTarget.add(new BoolSetting.Builder()
            .name("Other Entities")
            .description("Target other entities")
            .defaultValue(false)
            .build());
        this.sortPriority = this.sgTarget.add(new EnumSetting.Builder<SortPriority>()
            .name("Priority")
            .description("Nearest / crosshair angle / lowest health")
            .defaultValue(SortPriority.DISTANCE)
            .build());
        this.destroyHeights = this.sgDestroy.add(new StringListSetting.Builder()
            .name("Armor Break Heights")
            .description("Heights used for armor breaking")
            .defaultValue("30", "60")
            .visible(() -> this.enableArmorDestroy.get())
            .build());
        this.killHeights = this.sgKill.add(new StringListSetting.Builder()
            .name("Kill Heights")
            .description("Heights used for kills")
            .defaultValue("10", "20", "30")
            .build());
        this.bypassTotem = this.sgTotem.add(new BoolSetting.Builder()
            .name("Totem Bypass")
            .description("Drain totems with multi-height hits, then kill at full height")
            .defaultValue(false)
            .build());
        this.detectTotem = this.sgTotem.add(new BoolSetting.Builder()
            .name("Detect Totem")
            .description("Only drain if the target is actually holding a totem")
            .defaultValue(true)
            .visible(this.bypassTotem::get)
            .build());
        this.drainMode = this.sgTotem.add(new EnumSetting.Builder<DrainMode>()
            .name("Drain Mode")
            .description("List: use custom height list | Incremental: base + step per hit")
            .defaultValue(DrainMode.LIST)
            .visible(this.bypassTotem::get)
            .build());
        this.drainHeights = this.sgTotem.add(new StringListSetting.Builder()
            .name("Drain Heights")
            .description("Heights used to drain totems before the kill")
            .defaultValue("4", "8", "12", "16")
            .visible(() -> this.bypassTotem.get() && this.drainMode.get() == DrainMode.LIST)
            .build());
        this.baseDrainHeight = this.sgTotem.add(new IntSetting.Builder()
            .name("Base Drain Height")
            .description("Starting height for incremental drain attacks")
            .defaultValue(4)
            .min(1)
            .max(50)
            .sliderMax(50)
            .visible(() -> this.bypassTotem.get() && this.drainMode.get() == DrainMode.INCREMENTAL)
            .build());
        this.heightIncrement = this.sgTotem.add(new IntSetting.Builder()
            .name("Height Increment")
            .description("Added height per drain attack")
            .defaultValue(4)
            .min(1)
            .max(20)
            .sliderMax(20)
            .visible(() -> this.bypassTotem.get() && this.drainMode.get() == DrainMode.INCREMENTAL)
            .build());
        this.totemAttacks = this.sgTotem.add(new IntSetting.Builder()
            .name("Totem Attacks")
            .description("Number of drain attacks before the kill")
            .defaultValue(4)
            .range(1, 15)
            .sliderMax(15)
            .visible(this.bypassTotem::get)
            .build());

        this.phase = Phase.IDLE;
    }

    @Override
    public void onDeactivate() {
        this.phase = Phase.IDLE;
        this.delayTicks = 0;
        this.target = null;
        this.drainTarget = null;
        this.totemHits = 0;
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
                if (this.requireFullCooldown.get() && !this.bypassTotem.get()
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
        if (this.target != this.drainTarget) {
            this.drainTarget = this.target;
            this.totemHits = 0;
        }
        this.originalPos = new Vec3d(this.mc.player.getX(), this.mc.player.getY(), this.mc.player.getZ());
        this.targetPos = Targeting.predictPosition(
            this.mc, this.target, this.predict.get(), this.predictTicks.get());
        if (this.spamRotations.get()) {
            Movement.sendRotations(this.mc, 4);
        }
        Movement.doTpTo(this.mc, this.targetPos, this.moveDistance.get(), this.syncClientPos.get());
        int delay = this.bypassTotem.get() ? 0 : this.teleportDelay.get();
        if (delay > 0) {
            this.phase = Phase.START_DELAY;
            this.delayTicks = delay;
        } else {
            this.executeAndReturn();
        }
    }

    private void tickStartDelay() {
        Movement.doTpTo(this.mc, this.targetPos, this.moveDistance.get(), this.syncClientPos.get());
        if (this.syncClientPos.get()) {
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
        int delay = this.bypassTotem.get() ? 0 : this.teleportDelay.get();
        if (delay > 0) {
            this.phase = Phase.RETURN_DELAY;
            this.delayTicks = delay;
        } else {
            this.phase = Phase.IDLE;
            this.target = null;
        }
    }

    private void executeAttack() {
        if (isBypassingTotem()) {
            int totems = countTotems((PlayerEntity) this.target);
            List<String> allHeights = new ArrayList<>();
            allHeights.addAll(getDrainHeights());
            for (int i = 0; i < totems + 1; i++) {
                allHeights.add("170");
            }
            Config config = new Config(
                this.moveDistance.get(),
                this.swingHand.get(),
                this.autoTotem.get(),
                this.syncClientPos.get(),
                false,
                this.ignoreArmorValue.get(),
                this.destroyHeights.get(),
                allHeights
            );
            Combat.executeAttack(this.mc, config, this.target, this.targetPos);
            this.totemHits = 0;
            return;
        }
        Config config = new Config(
            this.moveDistance.get(),
            this.swingHand.get(),
            this.autoTotem.get(),
            this.syncClientPos.get(),
            this.enableArmorDestroy.get(),
            this.ignoreArmorValue.get(),
            this.destroyHeights.get(),
            this.killHeights.get()
        );
        Combat.executeAttack(this.mc, config, this.target, this.targetPos);
        this.totemHits = 0;
    }

    private boolean isBypassingTotem() {
        if (!this.bypassTotem.get() || !(this.target instanceof PlayerEntity)) {
            return false;
        }
        if (this.detectTotem.get() && !targetHasTotem((PlayerEntity) this.target)) {
            return false;
        }
        return true;
    }

    private boolean targetHasTotem(PlayerEntity player) {
        return countTotems(player) > 0;
    }

    private int countTotems(PlayerEntity player) {
        int n = 0;
        if (player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) n++;
        if (player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) n++;
        return n;
    }

    private List<String> getDrainHeights() {
        if (this.drainMode.get() == DrainMode.INCREMENTAL) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < this.totemAttacks.get(); i++) {
                list.add(String.valueOf(this.baseDrainHeight.get() + i * this.heightIncrement.get()));
            }
            return list;
        }
        return this.drainHeights.get();
    }

    private void doReturn() {
        if (this.originalPos == null) {
            return;
        }
        Movement.doTpTo(this.mc, this.originalPos, this.moveDistance.get(), this.syncClientPos.get());
    }

    @Override
    public String getInfoString() {
        if (this.bypassTotem.get()) {
            return "Totem Bypass";
        }
        int count = Combat.parseHeights(this.killHeights.get()).size();
        if (count == 0) return "Not configured";
        String priority = switch (this.sortPriority.get()) {
            case DISTANCE -> "Dist";
            case ANGLE -> "Angle";
            case HEALTH -> "HP";
        };
        return count + " hits | " + priority;
    }

    private enum Phase {
        IDLE,
        START_DELAY,
        RETURN_DELAY
    }

    private enum DrainMode {
        LIST,
        INCREMENTAL
    }
}
