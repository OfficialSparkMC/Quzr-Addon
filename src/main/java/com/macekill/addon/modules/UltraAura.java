package com.macekill.addon.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * UltraAura (ported from vicore) — teleports next to a target, attacks it, and teleports back.
 *
 * <p>The server type controls how the movement is sent: a single position packet
 * (Paper) or a stepped "blink" path (Spigot).
 *
 * <p>The original also skipped its own weapon switch while the addon's MaceTotemFail module
 * was active in silent-switch mode; that check is kept by module/setting name, so this file
 * has no dependency on the vicore classes.
 */
public class UltraAura extends Module {
    private static UltraAura INSTANCE;

    private final SettingGroup sgGeneral;
    private final Setting<Integer> attackDelay;
    private final Setting<Integer> targetRange;
    private final Setting<Double> steps;
    private final Setting<Boolean> lockTarget;
    private final Setting<Boolean> ignoreWater;
    private final Setting<Set<EntityType<?>>> entities;
    private final Setting<ServerType> serverType;
    private final Setting<Boolean> autoSwitch;
    private final Setting<SwapMode> swapMode;
    private final Setting<WeaponType> weapon;

    public final List<Entity> targets = new ArrayList<>();

    private int timer;
    private Vec3 startPos;
    private Vec3 targetPos;
    private Entity target;
    private boolean targetFound;
    private boolean swapped;
    private int previousSlot;
    private boolean silentSwap;

    public UltraAura() {
        super(MaceKillAddon.CATEGORY, "UltraAura", "Aura with infinite reach, closest player to the crosshair will be targetted");

        this.sgGeneral = this.settings.createGroup("Configuration");

        this.attackDelay = this.sgGeneral.add(new IntSetting.Builder()
                .name("Attack Delay")
                .description("Delay Between Attacks (on ticks)")
                .defaultValue(20)
                .sliderRange(0, 60)
                .min(0)
                .build());

        this.targetRange = this.sgGeneral.add(new IntSetting.Builder()
                .name("Distance")
                .description("Max distance to attack players")
                .defaultValue(50)
                .sliderRange(0, 150)
                .min(0)
                .build());

        this.steps = this.sgGeneral.add(new DoubleSetting.Builder()
                .name("Steps")
                .description("How much blocks it teleports each teleport.")
                .defaultValue(8.5)
                .sliderRange(1.0, 20.0)
                .min(1.0)
                .build());

        this.lockTarget = this.sgGeneral.add(new BoolSetting.Builder()
                .name("Lock Target")
                .description("Enable to lock to a player until its death, disconnect, or you turn off the module.")
                .defaultValue(false)
                .build());

        this.ignoreWater = this.sgGeneral.add(new BoolSetting.Builder()
                .name("Ignore targets on water")
                .description("Enable to ignore the player on the water, so they cant be marked as enemies.")
                .defaultValue(false)
                .build());

        this.entities = this.sgGeneral.add(new EntityTypeListSetting.Builder()
                .name("Entities")
                .description("Entities to attack")
                .onlyAttackable()
                .defaultValue(EntityTypes.PLAYER)
                .build());

        this.serverType = this.sgGeneral.add(new EnumSetting.Builder<ServerType>()
                .name("ServerType")
                .description("What type of server are you playing")
                .defaultValue(ServerType.PAPER)
                .build());

        this.autoSwitch = this.sgGeneral.add(new BoolSetting.Builder()
                .name("Switch")
                .description("Enable to auto switch silently to weapon")
                .defaultValue(false)
                .build());

        this.swapMode = this.sgGeneral.add(new EnumSetting.Builder<SwapMode>()
                .name("Swap Mode")
                .description("Type of Switch")
                .defaultValue(SwapMode.SILENT)
                .onChanged(this::onSwapModeChanged)
                .build());

        this.weapon = this.sgGeneral.add(new EnumSetting.Builder<WeaponType>()
                .name("Weapon")
                .description("Select the weapon to silent switch (Weapon must be in hotbar).")
                .defaultValue(WeaponType.MACE)
                .build());

        // The original only updated this from the setting callback, so the default SILENT
        // mode never restored the weapon until the user touched the setting.
        this.silentSwap = this.swapMode.get() == SwapMode.SILENT;

        INSTANCE = this;
    }

    @Override
    public void onActivate() {
        this.targetFound = true;
    }

    @Override
    public void onDeactivate() {
        this.restoreWeapon();
        this.targets.clear();
        this.target = null;
        this.targetPos = null;
        this.startPos = null;
        this.targetFound = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // TargetUtils.getList clears the list and refills it with valid targets, sorted and capped at 5.
        TargetUtils.getList(this.targets, this::isValidTarget, SortPriority.ClosestAngle, 5);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        this.timer++;

        if (this.mc.player == null || this.mc.level == null) return;

        this.target = this.target != null && this.lockTarget.get() ? this.target : this.getFirstTarget();

        if (this.target == null) {
            if (this.targetFound) {
                this.info("No Target Found");
                this.targetFound = false;
            }
            return;
        }

        if (this.timer < this.attackDelay.get()) return;
        this.smash(this.target);
    }

    /**
     * One complete UltraAura smash against a specific entity, reused by other modules
     * (Blacklist): approach packets (PAPER single position or SPIGOT stepped path),
     * optional weapon switch, the attack packet, silent-swap restore, return packets,
     * and position snap back. The ServerType / Steps / Switch / Swap Mode / Weapon
     * settings come from this module's own configuration.
     *
     * @return true when the attack packet was actually sent
     */
    public boolean smash(Entity entity) {
        if (entity == null || this.mc.player == null || this.mc.getConnection() == null) return false;

        this.target = entity;
        this.targetFound = true;
        this.updatePositions(entity);

        this.sendApproachPackets();
        this.mc.player.setPos(this.targetPos);

        if (this.autoSwitch.get() && !this.isMaceTotemFailHandlingSwitch()) {
            this.swapToWeapon();
        }

        this.mc.getConnection().send(new ServerboundAttackPacket(entity.getId()));
        this.mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), true);

        if (this.silentSwap) {
            this.restoreWeapon();
        }

        this.sendReturnPackets();
        this.mc.player.setPos(this.startPos);

        this.timer = 0;
        return true;
    }

    private boolean isValidTarget(Entity entity) {
        if (this.mc.player == null) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        if (entity == this.mc.player || entity == this.mc.getCameraEntity()) return false;
        if (living.isDeadOrDying() || !living.isAlive()) return false;
        if (!this.entities.get().contains(entity.getType())) return false;

        AABB box = entity.getBoundingBox();
        double x = Mth.clamp(this.mc.player.getX(), box.minX, box.maxX);
        double y = Mth.clamp(this.mc.player.getY(), box.minY, box.maxY);
        double z = Mth.clamp(this.mc.player.getZ(), box.minZ, box.maxZ);

        if (!PlayerUtils.isWithin(x, y, z, this.targetRange.get())) return false;

        if (entity instanceof Player player) {
            if (player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;

            if (this.ignoreWater.get()
                    && mc.level.getBlockState(player.blockPosition()).getFluidState().is(FluidTags.WATER)) {
                return false;
            }
        }

        return true;
    }

    private void sendApproachPackets() {
        if (this.serverType.get() == ServerType.PAPER) {
            this.sendTargetPosition(this.target);
        } else {
            this.sendPathToTarget();
        }
    }

    private void sendReturnPackets() {
        if (this.serverType.get() == ServerType.PAPER) {
            this.sendStartPosition();
        } else {
            this.sendPathBack();
        }
    }

    private void sendPathToTarget() {
        this.timer = 0;

        if (this.hasPositions()) {
            this.sendPath(this.startPos, this.targetPos, this.steps.get(), 4);
        }
    }

    private void sendPathBack() {
        this.sendPath(this.targetPos, this.startPos, this.steps.get(), 0);
    }

    private void updatePositions(Entity entity) {
        this.targetPos = entity.position();
        this.startPos = this.mc.player.position();
    }

    private boolean hasPositions() {
        return this.startPos != null && this.targetPos != null;
    }

    private void sendTargetPosition(Entity entity) {
        this.sendPosition(entity.getX(), entity.getY(), entity.getZ());
    }

    private void sendStartPosition() {
        this.sendPosition(this.startPos.x, this.startPos.y, this.startPos.z);
    }

    private void sendPosition(double x, double y, double z) {
        this.mc.getConnection().send(
                new ServerboundMovePlayerPacket.Pos(x, y, z, true, false));
    }

    /**
     * Sends the stepped "blink" path used by the original.
     *
     * @param from         start position
     * @param to           destination position
     * @param stepSize     maximum distance covered by one packet
     * @param stopDistance distance to stop short of the destination
     */
    private void sendPath(Vec3 from, Vec3 to, double stepSize, int stopDistance) {
        if (from == null || to == null) return;

        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        Vec3 end = from.add(delta.normalize().scale(distance - stopDistance));

        double packetCount = Math.ceil(distance / stepSize);
        for (int i = 1; i <= packetCount; i++) {
            Vec3 position = from.lerp(end, i / packetCount);
            this.sendPosition(position.x, position.y, position.z);
        }
    }

    /**
     * The original skipped its own switching when MaceTotemFail was active in
     * silent-switch mode. Both lookups are by name, so no external class is needed.
     */
    private boolean isMaceTotemFailHandlingSwitch() {
        Module maceTotemFail = Modules.get().get("MaceTotemFail");
        if (maceTotemFail == null || !maceTotemFail.isActive()) return false;

        Setting<?> silentSwitch = maceTotemFail.settings.get("Silent Switch");
        return silentSwitch != null && Boolean.TRUE.equals(silentSwitch.get());
    }

    private void swapToWeapon() {
        if (!this.autoSwitch.get() || this.mc.player == null) return;

        FindItemResult result = InvUtils.findInHotbar(this::isWeapon);
        if (!result.found()) return;

        this.previousSlot = this.mc.player.getInventory().getSelectedSlot();
        this.swapped = true;
        InvUtils.swap(result.slot(), false);
    }

    private boolean isWeapon(ItemStack stack) {
        return switch (this.weapon.get()) {
            case AXE -> stack.is(ItemTags.AXES);
            case SWORD -> stack.is(ItemTags.SWORDS);
            case MACE -> stack.getItem() instanceof MaceItem;
            case TRIDENT -> stack.getItem() instanceof TridentItem;
            case ALL -> stack.is(ItemTags.AXES)
                    || stack.is(ItemTags.SWORDS)
                    || stack.getItem() instanceof MaceItem
                    || stack.getItem() instanceof TridentItem;
            case ANY -> true;
        };
    }

    private void restoreWeapon() {
        if (this.swapped && this.autoSwitch.get()) {
            this.swapped = false;
            InvUtils.swap(this.previousSlot, false);
            this.previousSlot = -1;
        }
    }

    private void onSwapModeChanged(SwapMode mode) {
        this.silentSwap = mode == SwapMode.SILENT;
    }

    public Entity getFirstTarget() {
        return this.targets.isEmpty() ? null : this.targets.getFirst();
    }

    @Override
    public String getInfoString() {
        return this.targets.isEmpty() ? null : EntityUtils.getName(this.getFirstTarget());
    }

    public int getAttackDelay() {
        return this.attackDelay.get();
    }

    public double getSteps() {
        return this.steps.get();
    }

    public static UltraAura getInstance() {
        return INSTANCE;
    }

    public enum ServerType {
        SPIGOT,
        PAPER
    }

    public enum SwapMode {
        SILENT,
        NORMAL
    }

    public enum WeaponType {
        SWORD,
        AXE,
        MACE,
        TRIDENT,
        ALL,
        ANY
    }
}
