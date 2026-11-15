package com.macekill.addon.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IServerboundMovePlayerPacket;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

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
    private final List<Vec3> predictedPositions;
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
                .defaultValue(EntityTypes.PLAYER)
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
        if (!this.predictMovement.get() || this.target == null || this.mc.level == null) return;

        this.predictedPositions.clear();

        for (Entity entity : this.mc.level.entitiesForRendering()) {
            if (entity instanceof Player player && player.equals(this.target)) {
                this.addPredictedPosition(player);
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!this.predictMovement.get() || !this.renderPredictBoxes.get()) return;
        if (this.predictedPositions.isEmpty() || this.mc.player == null) return;

        for (Vec3 position : this.predictedPositions) {
            event.renderer.box(
                    this.mc.player.getDimensions(this.mc.player.getPose()).makeBoundingBox(position),
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

        if (!this.bowMachinegun.get() || this.mc.player == null || this.mc.gameMode == null) {
            return;
        }

        boolean holdingBow = this.mc.player.getMainHandItem().getItem() instanceof BowItem
                || this.mc.player.getOffhandItem().getItem() instanceof BowItem;

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
        if (this.mc.player == null || this.mc.level == null || this.mc.getConnection() == null) return;

        if (event.packet instanceof ServerboundUseItemPacket useItem) {
            ItemStack stack = useItem.hand() == InteractionHand.MAIN_HAND
                    ? this.mc.player.getMainHandItem()
                    : this.mc.player.getOffhandItem();
            Item item = stack.getItem();

            if (this.isAffectedProjectile(item)) {
                if (this.processingPacket) return;

                this.processingPacket = true;
                event.setCancelled(true);

                try {
                    this.teleportAndUse(useItem.hand());
                } finally {
                    this.processingPacket = false;
                }
            } else if (item instanceof BowItem
                    && this.affectedProjectiles.get().contains(Items.BOW)) {
                this.bowCharging = true;
            }
        }

        if (event.packet instanceof ServerboundPlayerActionPacket action) {
            if (this.replayingAction) return;
            if (action.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) return;

            Item mainHand = this.mc.player.getMainHandItem().getItem();
            Item offHand = this.mc.player.getOffhandItem().getItem();

            if (!this.isChargedWeapon(mainHand) && !this.isChargedWeapon(offHand)) return;

            this.bowCharging = false;
            event.setCancelled(true);
            this.teleportAndRelease(action.getAction());
        }
    }

    private Entity findTarget() {
        if (this.mc.level == null || this.mc.player == null) return null;

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : this.mc.level.entitiesForRendering()) {
            if (!this.isValidTarget(entity)) continue;
            if (entity instanceof Player player
                    && Friends.get().isFriend(player)
                    && this.ignoreFriends.get()) {
                continue;
            }

            double distance = entity.distanceToSqr(this.mc.player);

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

    private void addPredictedPosition(Player player) {
        if (Friends.get().isFriend(player) && this.ignoreFriends.get()) return;

        Vec3 predicted = this.predictPosition(player);

        if (predicted != null) {
            this.predictedPositions.add(predicted);
        }
    }

    /**
     * Extrapolates the last client-side movement for the configured number of ticks.
     */
    private Vec3 predictPosition(Player player) {
        Vec3 current = player.position();
        Vec3 delta = current.subtract(player.oldPosition());

        if (player.onGround()) {
            delta = new Vec3(delta.x, 0.0, delta.z);
        }

        return current.add(delta.scale(this.predictTicks.get()));
    }

    private void releaseBow() {
        this.mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ZERO,
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

        return (item instanceof EnderpearlItem && selected.contains(Items.ENDER_PEARL))
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
        Entity source = this.mc.player.isPassenger() ? this.mc.player.getVehicle() : this.mc.player;

        return entity.isAlive() && source.distanceTo(entity) <= this.maxTeleportDistance.get();
    }

    /**
     * Teleports to the target, uses the item, then teleports back to the saved position.
     */
    private void teleportAndUse(InteractionHand hand) {
        if (this.target == null || !this.isWithinRange(this.target)) return;

        Entity source = this.mc.player.isPassenger() ? this.mc.player.getVehicle() : this.mc.player;
        Vec3 start = source.position();

        if (this.mc.player.distanceToSqr(this.target)
                > this.maxTeleportDistance.get() * this.maxTeleportDistance.get()) {
            return;
        }

        AABB box = this.target.getBoundingBox();
        Vec3 targetCenter = new Vec3(box.getCenter().x, box.maxY, box.getCenter().z);
        double height = this.currentHeight();

        Vec3 predicted = this.predictedOrTarget(targetCenter);
        Vec3 from = start.add(0.0, height, 0.0);
        Vec3 to = predicted.add(0.0, height, 0.0);
        Vec3 above = predicted.add(0.0, this.aboveTargetOffset.get(), 0.0);
        Vec3 safeStart = this.findSafeStart(start);

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

        float yaw = this.mc.player.getYRot();

        for (int i = 0; i < this.interactAmount.get(); i++) {
            this.mc.getConnection().send(
                    new ServerboundUseItemPacket(hand, 0, yaw, 90.0f));
        }

        this.teleport(source, to);
        this.teleport(source, from);
        this.teleport(source, start);
        this.teleport(source, safeStart);

        source.setPos(safeStart);
    }

    /**
     * Teleports, replays the released action, then teleports back.
     */
    private void teleportAndRelease(ServerboundPlayerActionPacket.Action action) {
        if (this.target == null || !this.isWithinRange(this.target)) return;

        Entity source = this.mc.player.isPassenger() ? this.mc.player.getVehicle() : this.mc.player;
        Vec3 start = source.position();

        if (this.mc.player.distanceToSqr(this.target)
                > this.maxTeleportDistance.get() * this.maxTeleportDistance.get()) {
            return;
        }

        AABB box = this.target.getBoundingBox();
        Vec3 targetCenter = new Vec3(box.getCenter().x, box.maxY, box.getCenter().z);
        double height = this.currentHeight();

        Vec3 predicted = this.predictedOrTarget(targetCenter);
        Vec3 from = start.add(0.0, height, 0.0);
        Vec3 to = predicted.add(0.0, height, 0.0);
        Vec3 above = predicted.add(0.0, this.aboveTargetOffset.get(), 0.0);
        Vec3 safeStart = this.findSafeStart(start);

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

    private Vec3 predictedOrTarget(Vec3 fallback) {
        if (this.predictMovement.get() && !this.predictedPositions.isEmpty()) {
            Vec3 predicted = this.predictedPositions.getFirst();
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
                this.mc.getConnection().send(
                        new ServerboundMovePlayerPacket.StatusOnly(true, this.mc.player.horizontalCollision));
            } else {
                this.mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(source));
            }
        }
    }

    private void teleport(Entity entity, Vec3 position) {
        if (entity == this.mc.player) {
            ServerboundMovePlayerPacket.Pos packet =
                    new ServerboundMovePlayerPacket.Pos(
                            position.x,
                            position.y,
                            position.z,
                            false,
                            this.mc.player.horizontalCollision);

            ((IServerboundMovePlayerPacket) packet).meteor$setTag(MOVE_PACKET_TAG);
            this.mc.getConnection().send(packet);
        } else {
            this.mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(entity));
        }

        entity.setPos(position);
    }

    /** Replays the action without re-entering this module. */
    private void replayAction(ServerboundPlayerActionPacket.Action action) {
        this.replayingAction = true;
        this.mc.getConnection().send(
                new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN, 0));
        this.replayingAction = false;
    }

    /**
     * Returns {@code true} when the given point is obstructed by lava, a collision
     * shape, or a collidable entity.
     */
    private boolean isObstructed(Vec3 point) {
        if (this.mc.level == null) return true;
        if (!this.mc.level.hasChunkAt(BlockPos.containing(point))) return true;

        Entity source = this.mc.player.isPassenger() ? this.mc.player.getVehicle() : this.mc.player;
        AABB box = source.getBoundingBox().move(
                point.x - source.getX(),
                point.y - source.getY(),
                point.z - source.getZ());

        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = this.mc.level.getBlockState(pos);

            if (state.is(Blocks.LAVA)) return true;
            if (!state.getCollisionShape(this.mc.level, pos).isEmpty()) return true;
        }

        for (Entity entity : this.mc.level.getEntities(source, box)) {
            if (entity.canBeCollidedWith(entity)) return true;
        }

        return false;
    }

    /** True when every point is clear. */
    private boolean arePointsClear(Vec3... points) {
        for (Vec3 point : points) {
            if (this.isObstructed(point)) return false;
        }

        return true;
    }

    /** True when the line between both points is clear. */
    private boolean isLineClear(Vec3 from, Vec3 to) {
        if (this.isObstructed(from) || this.isObstructed(to)) return false;

        int steps = Math.max(10, (int) (from.distanceTo(to) * 2.5));

        for (int i = 1; i < steps; i++) {
            Vec3 point = from.lerp(to, (double) i / steps);

            if (this.isObstructed(point)) return false;
        }

        return true;
    }

    /** Picks a nearby non-obstructed start position. */
    private Vec3 findSafeStart(Vec3 start) {
        double horizontal = this.horizontalOffset.get();
        double vertical = this.yOffsetFirst.get();

        List<Vec3> candidates = new ArrayList<>(List.of(
                start.add(horizontal, vertical, 0.0),
                start.add(-horizontal, vertical, 0.0),
                start.add(0.0, vertical, horizontal),
                start.add(0.0, vertical, -horizontal),
                start.add(horizontal, vertical, horizontal),
                start.add(-horizontal, vertical, -horizontal),
                start.add(-horizontal, vertical, horizontal),
                start.add(horizontal, vertical, -horizontal)));

        Collections.shuffle(candidates);

        for (Vec3 candidate : candidates) {
            if (!this.isObstructed(candidate)) return candidate;
        }

        Vec3 center = start.add(0.0, vertical, 0.0);

        if (!this.isObstructed(center)) return center;

        return start;
    }

    public enum HeightStage {
        First,
        Second,
        Third
    }
}
