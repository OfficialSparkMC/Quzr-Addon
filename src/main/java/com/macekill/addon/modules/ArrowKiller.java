package com.macekill.addon.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerMoveC2SPacket;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.EggItem;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ExperienceBottleItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.LingeringPotionItem;
import net.minecraft.item.SnowballItem;
import net.minecraft.item.SplashPotionItem;
import net.minecraft.item.TridentItem;
import net.minecraft.item.WindChargeItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * ArrowKiller (ported from vicore).
 *
 * <p>Teleports the player onto the arrow's flight path (above the target), fires the
 * bow/trident from there, and teleports back. The server accepts the use packet at the
 * teleported position, so the projectile is spawned at the target instead of at the
 * player's real position.
 *
 * <p>The teleport path deliberately uses no-ground movement packets. Meteor's
 * {@code AntiPop} strips the one {@code onGround=true} prime packet, so the server never
 * registers a landing; without AntiPop the accumulated fall distance can hurt on the
 * next real movement packet (see {@code onActivate} warning).
 */
public class ArrowKiller extends Module {
    /** Meteor tag used on the synthetic movement packets. */
    private static final int MOVE_PACKET_TAG = 1337;

    private final SettingGroup sgGeneral;
    private final SettingGroup sgTarget;
    private final SettingGroup sgTeleport;
    private final SettingGroup sgBow;
    private final SettingGroup sgPrediction;

    private final Setting<List<Item>> affectedProjectiles;
    private final Setting<Integer> interactAmount;
    private final Setting<Boolean> showLogs;
    private final Setting<Set<EntityType<?>>> entities;
    public final Setting<Boolean> ignoreFriends;
    private final Setting<Integer> noGroundPackets;
    private final Setting<Double> firstDamage;
    private final Setting<Double> secondDamage;
    private final Setting<Double> thirdDamage;
    private final Setting<Double> maxTeleportDistance;
    private final Setting<Double> aboveTargetOffset;
    private final Setting<Double> horizontalOffset;
    private final Setting<Double> yOffsetFirst;
    private final Setting<Integer> refreshRate;
    private final Setting<Boolean> bowMachinegun;
    private final Setting<Integer> chargeTicks;
    private final Setting<Boolean> predictMovement;
    private final Setting<Integer> predictTicks;
    private final Setting<Boolean> renderPredictBoxes;

    private HeightStage stage;
    private int refreshTimer;
    private boolean bowCharging;
    private int bowChargeTicks;
    private boolean previousBow;
    private Entity target;
    private final List<Vec3d> predictedPositions;
    private boolean processingPacket;
    private boolean replayingAction;

    public ArrowKiller() {
        super(MaceKillAddon.CATEGORY, "ArrowKiller", "Teleports arrows to the target");

        this.sgGeneral = this.settings.getDefaultGroup();
        this.sgTarget = this.settings.createGroup("Target Options");
        this.sgTeleport = this.settings.createGroup("Teleport Options");
        this.sgBow = this.settings.createGroup("Bow Options");
        this.sgPrediction = this.settings.createGroup("Prediction Options");

        this.affectedProjectiles = this.sgGeneral.add(new ItemListSetting.Builder()
                .name("Affected Projectiles")
                .description("Which items to apply the teleport.")
                .defaultValue(Items.BOW, Items.TRIDENT)
                .filter(item -> item == Items.BOW || item == Items.TRIDENT)
                .build());

        this.interactAmount = this.sgGeneral.add(new IntSetting.Builder()
                .name("Interact Amount")
                .description("How much time it tries to interact (so the server doesn't skip it)")
                .defaultValue(1)
                .min(0)
                .sliderMax(15)
                .build());

        this.showLogs = this.sgGeneral.add(new BoolSetting.Builder()
                .name("Show Logs")
                .description("Shows logs of the heights in chat")
                .defaultValue(false)
                .build());

        this.entities = this.sgTarget.add(new EntityTypeListSetting.Builder()
                .name("Entities")
                .description("Entities to target.")
                .onlyAttackable()
                .defaultValue(EntityType.PLAYER)
                .build());

        this.ignoreFriends = this.sgTarget.add(new BoolSetting.Builder()
                .name("Ignore Friends")
                .defaultValue(false)
                .build());

        this.noGroundPackets = this.sgTeleport.add(new IntSetting.Builder()
                .name("No Ground Packets")
                .description("How many times the server is told you are not on ground")
                .defaultValue(4)
                .min(0)
                .sliderMax(20)
                .build());

        this.firstDamage = this.sgTeleport.add(new DoubleSetting.Builder()
                .name("First Damage")
                .description("The first damage it will use when you start holding the bow")
                .defaultValue(10.0)
                .sliderMax(300.0)
                .build());

        this.secondDamage = this.sgTeleport.add(new DoubleSetting.Builder()
                .name("Second Damage")
                .description("The second damage after the First one is used")
                .defaultValue(13.0)
                .sliderMax(300.0)
                .build());

        this.thirdDamage = this.sgTeleport.add(new DoubleSetting.Builder()
                .name("Third Damage")
                .description("The third damage after the Second one is used")
                .defaultValue(16.0)
                .sliderMax(300.0)
                .build());

        this.maxTeleportDistance = this.sgTeleport.add(new DoubleSetting.Builder()
                .name("Max teleport distance")
                .description("If the enemy is more than this distancce, you will not tp to him")
                .defaultValue(60.0)
                .sliderRange(10.0, 120.0)
                .build());

        this.aboveTargetOffset = this.sgTeleport.add(new DoubleSetting.Builder()
                .name("Above target offset")
                .description("Offset you this much above the target position")
                .defaultValue(0.01)
                .min(0.0)
                .sliderMax(10.0)
                .build());

        this.horizontalOffset = this.sgTeleport.add(new DoubleSetting.Builder()
                .name("Horizontal Offset")
                .description("Offset you this much from the start position.")
                .defaultValue(0.05)
                .min(0.0)
                .sliderMax(0.99)
                .build());

        this.yOffsetFirst = this.sgTeleport.add(new DoubleSetting.Builder()
                .name("Y Offset First")
                .description("Offset you this much from the start position.")
                .defaultValue(0.0)
                .sliderMax(0.99)
                .build());

        this.refreshRate = this.sgTeleport.add(new IntSetting.Builder()
                .name("Refresh Rate")
                .description("How much ticks to wait if you don't charge the bow to go to First Damage.")
                .defaultValue(10)
                .sliderRange(0, 30)
                .min(0)
                .build());

        this.bowMachinegun = this.sgBow.add(new BoolSetting.Builder()
                .name("Bow Machinegun")
                .description("Automatically fires arrows while holding bow.")
                .defaultValue(true)
                .build());

        this.chargeTicks = this.sgBow.add(new IntSetting.Builder()
                .name("charge-ticks")
                .description("Ticks to charge bow before firing.")
                .defaultValue(4)
                .sliderRange(4, 60)
                .visible(this.bowMachinegun::get)
                .build());

        this.predictMovement = this.sgPrediction.add(new BoolSetting.Builder()
                .name("Predict movement")
                .description("Tries to predict the target's position according to their movement")
                .defaultValue(false)
                .build());

        this.predictTicks = this.sgPrediction.add(new IntSetting.Builder()
                .name("Predict Ticks")
                .description("How much ticks further will try to predict targets position")
                .defaultValue(1)
                .visible(this.predictMovement::get)
                .build());

        this.renderPredictBoxes = this.sgPrediction.add(new BoolSetting.Builder()
                .name("Render predict boxes")
                .description("Shows boxes where it 'predicts' the position")
                .defaultValue(false)
                .visible(this.predictMovement::get)
                .build());

        this.stage = HeightStage.First;
        this.predictedPositions = new ArrayList<>();
    }

    @Override
    public void onActivate() {
        this.stage = HeightStage.First;
        this.refreshTimer = 0;
        this.predictedPositions.clear();
        this.previousBow = false;
        this.bowCharging = false;
        this.bowChargeTicks = 0;

        Module antiPop = Modules.get().get("AntiPop");
        if (antiPop == null || !antiPop.isActive()) {
            this.warning("ArrowKiller teleports accumulate fall distance — enable AntiPop to avoid fall damage.");
        }
    }

    @Override
    public void onDeactivate() {
        this.stage = HeightStage.First;
        this.refreshTimer = 0;
        this.predictedPositions.clear();
        this.previousBow = false;
        this.bowCharging = false;
        this.bowChargeTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!this.predictMovement.get() || this.target == null || this.mc.world == null) return;

        this.predictedPositions.clear();

        for (Entity entity : this.mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && player.equals(this.target)) {
                this.addPredictedPosition(player);
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!this.predictMovement.get() || !this.renderPredictBoxes.get()) return;
        if (this.predictedPositions.isEmpty() || this.mc.player == null) return;

        for (Vec3d position : this.predictedPositions) {
            event.renderer.box(
                    this.mc.player.getDimensions(this.mc.player.getPose()).getBoxAt(position),
                    new Color(143, 85, 34, 110),
                    new Color(143, 85, 34, 255),
                    ShapeMode.Both,
                    0);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        this.refreshTimer++;
        this.target = this.findTarget();

        if (!this.bowMachinegun.get() || this.mc.player == null || this.mc.interactionManager == null) {
            return;
        }

        boolean holdingBow = this.mc.player.getMainHandStack().getItem() instanceof BowItem
                || this.mc.player.getOffHandStack().getItem() instanceof BowItem;

        if (holdingBow && !this.previousBow) {
            this.bowChargeTicks = 0;
        }

        // If the bow was not charged for a while, drop back to the first height.
        if (holdingBow && !this.bowCharging && this.refreshTimer >= this.refreshRate.get()
                && this.stage != HeightStage.First) {
            this.advanceStage(true);
            this.refreshTimer = 0;
        }

        if (this.bowCharging && holdingBow) {
            this.bowChargeTicks++;

            if (this.bowChargeTicks >= this.chargeTicks.get()) {
                this.releaseBow();
                this.bowChargeTicks = 0;
            }
        } else {
            this.bowChargeTicks = 0;
        }

        this.previousBow = holdingBow;
    }

    @EventHandler(priority = 200)
    private void onPacketSend(PacketEvent.Send event) {
        if (this.mc.player == null || this.mc.world == null || this.mc.getNetworkHandler() == null) return;

        if (event.packet instanceof PlayerInteractItemC2SPacket useItem) {
            ItemStack stack = useItem.getHand() == Hand.MAIN_HAND
                    ? this.mc.player.getMainHandStack()
                    : this.mc.player.getOffHandStack();
            Item item = stack.getItem();

            if (this.isAffectedProjectile(item)) {
                if (this.processingPacket) return;

                this.processingPacket = true;
                event.cancel();

                try {
                    this.teleportAndUse(useItem.getHand());
                } finally {
                    this.processingPacket = false;
                }
            } else if (item instanceof BowItem
                    && this.affectedProjectiles.get().contains(Items.BOW)) {
                this.bowCharging = true;
            }
        }

        if (event.packet instanceof PlayerActionC2SPacket action) {
            if (this.replayingAction) return;
            if (action.getAction() != PlayerActionC2SPacket.Action.RELEASE_USE_ITEM) return;

            Item mainHand = this.mc.player.getMainHandStack().getItem();
            Item offHand = this.mc.player.getOffHandStack().getItem();

            if (!this.isChargedWeapon(mainHand) && !this.isChargedWeapon(offHand)) return;

            this.bowCharging = false;
            event.cancel();
            this.teleportAndRelease(action.getAction());
        }
    }

    private Entity findTarget() {
        if (this.mc.world == null || this.mc.player == null) return null;

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : this.mc.world.getEntities()) {
            if (!this.isValidTarget(entity)) continue;
            if (entity instanceof PlayerEntity player
                    && Friends.get().isFriend(player)
                    && this.ignoreFriends.get()) {
                continue;
            }

            double distance = entity.squaredDistanceTo(this.mc.player);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }

        return best;
    }

    private boolean isValidTarget(Entity entity) {
        return this.entities.get().contains(entity.getType())
                && entity.isAlive()
                && entity.isAttackable()
                && !entity.isInvulnerable()
                && entity != this.mc.player;
    }

    private void addPredictedPosition(PlayerEntity player) {
        if (Friends.get().isFriend(player) && this.ignoreFriends.get()) return;

        Vec3d predicted = this.predictPosition(player);

        if (predicted != null) {
            this.predictedPositions.add(predicted);
        }
    }

    /**
     * Extrapolates the last client-side movement for the configured number of ticks.
     */
    private Vec3d predictPosition(PlayerEntity player) {
        Vec3d current = player.getEntityPos();
        Vec3d delta = current.subtract(player.getLastRenderPos());

        if (player.isOnGround()) {
            delta = new Vec3d(delta.x, 0.0, delta.z);
        }

        return current.add(delta.multiply(this.predictTicks.get()));
    }

    private void releaseBow() {
        this.mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN,
                Direction.DOWN,
                this.mc.player.getInventory().getSelectedSlot()));

        this.advanceStage(false);
        this.refreshTimer = 0;
    }

    private void advanceStage(boolean resetToFirst) {
        if (resetToFirst) {
            this.stage = HeightStage.First;
        } else {
            this.stage = switch (this.stage) {
                case First -> HeightStage.Second;
                case Second -> HeightStage.Third;
                case Third -> HeightStage.First;
            };
        }

        if (this.showLogs.get()) {
            this.info("ChangedHeigh: " + this.stage);
        }
    }

    private double currentHeight() {
        return switch (this.stage) {
            case First -> this.firstDamage.get();
            case Second -> this.secondDamage.get();
            case Third -> this.thirdDamage.get();
        };
    }

    /**
     * The item list filter only allows bows and tridents, so these entries can normally
     * never be selected; the original behavior is kept in case the setting is loaded
     * from an old config.
     */
    private boolean isAffectedProjectile(Item item) {
        List<Item> selected = this.affectedProjectiles.get();

        return (item instanceof EnderPearlItem && selected.contains(Items.ENDER_PEARL))
                || (item instanceof SplashPotionItem && selected.contains(Items.SPLASH_POTION))
                || (item instanceof LingeringPotionItem && selected.contains(Items.LINGERING_POTION))
                || (item instanceof ExperienceBottleItem && selected.contains(Items.EXPERIENCE_BOTTLE))
                || (item instanceof SnowballItem && selected.contains(Items.SNOWBALL))
                || (item instanceof WindChargeItem && selected.contains(Items.WIND_CHARGE))
                || (item instanceof EggItem && selected.contains(Items.EGG));
    }

    private boolean isChargedWeapon(Item item) {
        return (item instanceof BowItem && this.affectedProjectiles.get().contains(Items.BOW))
                || (item instanceof TridentItem && this.affectedProjectiles.get().contains(Items.TRIDENT));
    }

    private boolean isWithinRange(Entity entity) {
        Entity source = this.mc.player.hasVehicle() ? this.mc.player.getVehicle() : this.mc.player;

        return entity.isAlive() && source.distanceTo(entity) <= this.maxTeleportDistance.get();
    }

    /**
     * Teleports to the target, uses the item, then teleports back to the saved position.
     */
    private void teleportAndUse(Hand hand) {
        if (this.target == null || !this.isWithinRange(this.target)) return;

        Entity source = this.mc.player.hasVehicle() ? this.mc.player.getVehicle() : this.mc.player;
        Vec3d start = source.getEntityPos();

        if (this.mc.player.squaredDistanceTo(this.target)
                > this.maxTeleportDistance.get() * this.maxTeleportDistance.get()) {
            return;
        }

        Box box = this.target.getBoundingBox();
        Vec3d targetCenter = new Vec3d(box.getCenter().x, box.maxY, box.getCenter().z);
        double height = this.currentHeight();

        Vec3d predicted = this.predictedOrTarget(targetCenter);
        Vec3d from = start.add(0.0, height, 0.0);
        Vec3d to = predicted.add(0.0, height, 0.0);
        Vec3d above = predicted.add(0.0, this.aboveTargetOffset.get(), 0.0);
        Vec3d safeStart = this.findSafeStart(start);

        if (!this.arePointsClear(from, to, above, to, from, safeStart)) {
            if (this.chatFeedback) this.error("One of the teleports are blocked.");
            return;
        }

        if (!this.isLineClear(from, to) && this.chatFeedback) {
            this.error("Vertical path blocked between clip positions.");
        }

        this.sendNoGroundPackets(source);
        this.teleport(source, from);
        this.teleport(source, to);
        this.teleport(source, above);

        float yaw = this.mc.player.getYaw();

        for (int i = 0; i < this.interactAmount.get(); i++) {
            this.mc.getNetworkHandler().sendPacket(
                    new PlayerInteractItemC2SPacket(hand, 0, yaw, 90.0f));
        }

        this.teleport(source, to);
        this.teleport(source, from);
        this.teleport(source, start);
        this.teleport(source, safeStart);

        source.setPosition(safeStart);
    }

    /**
     * Teleports, replays the released action, then teleports back.
     */
    private void teleportAndRelease(PlayerActionC2SPacket.Action action) {
        if (this.target == null || !this.isWithinRange(this.target)) return;

        Entity source = this.mc.player.hasVehicle() ? this.mc.player.getVehicle() : this.mc.player;
        Vec3d start = source.getEntityPos();

        if (this.mc.player.squaredDistanceTo(this.target)
                > this.maxTeleportDistance.get() * this.maxTeleportDistance.get()) {
            return;
        }

        Box box = this.target.getBoundingBox();
        Vec3d targetCenter = new Vec3d(box.getCenter().x, box.maxY, box.getCenter().z);
        double height = this.currentHeight();

        Vec3d predicted = this.predictedOrTarget(targetCenter);
        Vec3d from = start.add(0.0, height, 0.0);
        Vec3d to = predicted.add(0.0, height, 0.0);
        Vec3d above = predicted.add(0.0, this.aboveTargetOffset.get(), 0.0);
        Vec3d safeStart = this.findSafeStart(start);

        if (!this.arePointsClear(from, to, above, to, from, safeStart)) {
            if (this.chatFeedback) this.error("One of the teleports are blocked.");
            this.replayAction(action);
            return;
        }

        this.sendNoGroundPackets(source);
        this.teleport(source, from);
        this.teleport(source, to);
        this.teleport(source, above);
        this.replayAction(action);
        this.teleport(source, to);
        this.teleport(source, from);
        this.teleport(source, start);
        this.teleport(source, safeStart);
    }

    private Vec3d predictedOrTarget(Vec3d fallback) {
        if (this.predictMovement.get() && !this.predictedPositions.isEmpty()) {
            Vec3d predicted = this.predictedPositions.getFirst();
            this.predictedPositions.clear();
            return predicted;
        }

        return fallback;
    }

    private void sendNoGroundPackets(Entity source) {
        for (int i = 0; i < this.noGroundPackets.get(); i++) {
            if (source == this.mc.player) {
                // AntiPop rewrites this to onGround=false; the original keeps the prime
                // packet onGround=true to reset any previous fall before the teleport.
                this.mc.getNetworkHandler().sendPacket(
                        new PlayerMoveC2SPacket.OnGroundOnly(true, this.mc.player.horizontalCollision));
            } else {
                this.mc.getNetworkHandler().sendPacket(VehicleMoveC2SPacket.fromVehicle(source));
            }
        }
    }

    private void teleport(Entity entity, Vec3d position) {
        if (entity == this.mc.player) {
            PlayerMoveC2SPacket.PositionAndOnGround packet =
                    new PlayerMoveC2SPacket.PositionAndOnGround(
                            position.x,
                            position.y,
                            position.z,
                            false,
                            this.mc.player.horizontalCollision);

            ((IPlayerMoveC2SPacket) packet).meteor$setTag(MOVE_PACKET_TAG);
            this.mc.getNetworkHandler().sendPacket(packet);
        } else {
            this.mc.getNetworkHandler().sendPacket(VehicleMoveC2SPacket.fromVehicle(entity));
        }

        entity.setPosition(position);
    }

    /** Replays the action without re-entering this module. */
    private void replayAction(PlayerActionC2SPacket.Action action) {
        this.replayingAction = true;
        this.mc.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(action, BlockPos.ORIGIN, Direction.DOWN, 0));
        this.replayingAction = false;
    }

    /**
     * Returns {@code true} when the given point is obstructed by lava, a collision
     * shape, or a collidable entity.
     */
    private boolean isObstructed(Vec3d point) {
        if (this.mc.world == null) return true;
        if (!this.mc.world.isPosLoaded(BlockPos.ofFloored(point))) return true;

        Entity source = this.mc.player.hasVehicle() ? this.mc.player.getVehicle() : this.mc.player;
        Box box = source.getBoundingBox().offset(
                point.x - source.getX(),
                point.y - source.getY(),
                point.z - source.getZ());

        for (BlockPos pos : BlockPos.iterate(
                BlockPos.ofFloored(box.minX, box.minY, box.minZ),
                BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = this.mc.world.getBlockState(pos);

            if (state.isOf(Blocks.LAVA)) return true;
            if (!state.getCollisionShape(this.mc.world, pos).isEmpty()) return true;
        }

        for (Entity entity : this.mc.world.getOtherEntities(source, box)) {
            if (entity.isCollidable(entity)) return true;
        }

        return false;
    }

    /** True when every point is clear. */
    private boolean arePointsClear(Vec3d... points) {
        for (Vec3d point : points) {
            if (this.isObstructed(point)) return false;
        }

        return true;
    }

    /** True when the line between both points is clear. */
    private boolean isLineClear(Vec3d from, Vec3d to) {
        if (this.isObstructed(from) || this.isObstructed(to)) return false;

        int steps = Math.max(10, (int) (from.distanceTo(to) * 2.5));

        for (int i = 1; i < steps; i++) {
            Vec3d point = from.lerp(to, (double) i / steps);

            if (this.isObstructed(point)) return false;
        }

        return true;
    }

    /** Picks a nearby non-obstructed start position. */
    private Vec3d findSafeStart(Vec3d start) {
        double horizontal = this.horizontalOffset.get();
        double vertical = this.yOffsetFirst.get();

        List<Vec3d> candidates = new ArrayList<>(List.of(
                start.add(horizontal, vertical, 0.0),
                start.add(-horizontal, vertical, 0.0),
                start.add(0.0, vertical, horizontal),
                start.add(0.0, vertical, -horizontal),
                start.add(horizontal, vertical, horizontal),
                start.add(-horizontal, vertical, -horizontal),
                start.add(-horizontal, vertical, horizontal),
                start.add(horizontal, vertical, -horizontal)));

        Collections.shuffle(candidates);

        for (Vec3d candidate : candidates) {
            if (!this.isObstructed(candidate)) return candidate;
        }

        Vec3d center = start.add(0.0, vertical, 0.0);

        if (!this.isObstructed(center)) return center;

        return start;
    }

    public enum HeightStage {
        First,
        Second,
        Third
    }
}
