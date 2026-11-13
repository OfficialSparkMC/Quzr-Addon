package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.utils.Attack;
import com.macekill.addon.utils.HitResults;
import com.macekill.addon.utils.Movement;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * InfiniteReach (ported from brew-addon 1.21.8).
 * Lets you attack entities, interact with blocks and break blocks far beyond vanilla reach.
 * Entity reach and block reach are configured independently.
 */
public class InfiniteReach extends Module {
    private static final double VANILLA_REACH = 4.5;

    private boolean attackKeyWasPressed = false;
    private boolean interactKeyWasPressed = false;

    private BlockPos currentBreakingBlock = null;
    private Direction currentBreakingDirection = null;
    private BlockState currentBlockState = null;
    private double breakProgress = 0.0;
    private boolean hasStartedBreaking = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> entities = sgGeneral.add(new BoolSetting.Builder()
        .name("entities")
        .description("Attack entities beyond vanilla reach.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> entityReach = sgGeneral.add(new IntSetting.Builder()
        .name("entity-reach")
        .description("Maximum distance for attacking entities.")
        .defaultValue(210)
        .min(5)
        .sliderMax(210)
        .visible(entities::get)
        .build()
    );

    private final Setting<Boolean> blocks = sgGeneral.add(new BoolSetting.Builder()
        .name("blocks")
        .description("Interact with and break blocks beyond vanilla reach.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> blockReach = sgGeneral.add(new IntSetting.Builder()
        .name("block-reach")
        .description("Maximum distance for interacting with and breaking blocks.")
        .defaultValue(210)
        .min(5)
        .sliderMax(210)
        .visible(blocks::get)
        .build()
    );

    private final Setting<Boolean> hoverBox = sgGeneral.add(new BoolSetting.Builder()
        .name("hover-box")
        .description("Draw a box around the far block under your crosshair while blocks is on.")
        .defaultValue(true)
        .visible(blocks::get)
        .build()
    );

    private final Setting<SettingColor> hoverColor = sgGeneral.add(new ColorSetting.Builder()
        .name("hover-color")
        .description("Side color of the hover box (the outline uses the same color at full alpha).")
        .defaultValue(new SettingColor(0, 255, 0, 60))
        .visible(() -> blocks.get() && hoverBox.get())
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("Debug")
        .description("Debugging shit")
        .defaultValue(false)
        .build()
    );

    public InfiniteReach() {
        super(MaceKillAddon.CATEGORY, "inf-reach", "Lets you interact with blocks and entities far away.");
    }

    private double getTargetDistance(Vec3 target) {
        if (mc.player == null) return 0;
        return mc.player.getEyePosition().distanceTo(target);
    }

    /**
     * Handles one attack-key press: entities first (if enabled), then blocks (if enabled).
     */
    private void onAttackClick() {
        if (entities.get() && attackEntity()) return;
        if (blocks.get()) breakBlock();
    }

    private boolean attackEntity() {
        if (mc.player == null) return false;

        HitResult hitResult = HitResults.getCrosshairTarget(mc.player, entityReach.get(), true, entity -> true);
        if (!(hitResult instanceof EntityHitResult entityHit)) return false;

        Entity target = entityHit.getEntity();
        if (target == null || target instanceof ItemEntity) return false;

        double distance = getTargetDistance(target.position());
        if (distance <= VANILLA_REACH || distance > entityReach.get()) return false;

        if (debug.get()) {
            info("Attacking " + target.getName().getString() + " at distance " + distance);
        }
        Attack.attack(target, false, 0, false);
        return true;
    }

    private void interact() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        HitResult hitResult = HitResults.getCrosshairTarget(mc.player, blockReach.get(), false, entity -> true);
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos target = blockHit.getBlockPos();
            if (getTargetDistance(Vec3.atCenterOf(target)) <= VANILLA_REACH) return;

            Vec3 hitPos = Vec3.atCenterOf(target);
            BlockHitResult blockHitResult = new BlockHitResult(hitPos, blockHit.getDirection(), target, false);
            Block block = mc.level.getBlockState(blockHitResult.getBlockPos()).getBlock();

            Vec3 originalPos = mc.player.position();

            if (debug.get()) {
                info("Interacting with " + block.getName().getString() + " at distance " + getTargetDistance(hitPos));
            }
            Movement.teleport(Vec3.atCenterOf(target).add(0, 2, 0), false, false);
            mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, blockHitResult, 0));
            Movement.teleport(originalPos, true, false);
        }
    }

    private void breakBlock() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        HitResult hitResult = HitResults.getCrosshairTarget(mc.player, blockReach.get(), false, entity -> true);
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos target = blockHit.getBlockPos();
            if (getTargetDistance(Vec3.atCenterOf(target)) <= VANILLA_REACH) return;

            if (currentBreakingBlock == null || !currentBreakingBlock.equals(target)) {
                resetBreaking();
                currentBreakingBlock = target;
                currentBreakingDirection = blockHit.getDirection();
                currentBlockState = mc.level.getBlockState(target);
                breakProgress = 0.0;
                hasStartedBreaking = false;
            }

            if (!hasStartedBreaking) {
                Vec3 originalPos = mc.player.position();

                Movement.teleport(Vec3.atCenterOf(target).add(0, 2, 0), false, false);
                mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, currentBreakingDirection));
                Movement.teleport(originalPos, true, true);
                if (debug.get()) {
                    info("Sent startBreaking packet for " + mc.level.getBlockState(target).getBlock().getName().getString()
                        + " at distance " + getTargetDistance(Vec3.atCenterOf(target)));
                }

                hasStartedBreaking = true;
            }
        }
    }

    private void updateBreakProgress() {
        if (!blocks.get() || currentBreakingBlock == null || currentBlockState == null || mc.player == null) return;

        double bestScore = -1.0;
        int bestSlot = -1;

        for (int i = 0; i < 9; i++) {
            double score = (double) mc.player.getInventory().getItem(i).getDestroySpeed(currentBlockState);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        int slot = bestSlot != -1 ? bestSlot : mc.player.getInventory().getSelectedSlot();
        double breakDelta = BlockUtils.getBreakDelta(slot, currentBlockState);
        breakProgress += breakDelta;

        if (breakProgress >= 1.0) {
            finishBreaking();
        }
    }

    private void finishBreaking() {
        if (currentBreakingBlock == null || mc.player == null || mc.level == null || mc.getConnection() == null) return;

        Vec3 originalPos = mc.player.position();

        Movement.teleport(Vec3.atCenterOf(currentBreakingBlock).add(0, 2, 0), false, false);
        mc.getConnection().send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, currentBreakingBlock, currentBreakingDirection));
        Movement.teleport(originalPos, true, false);
        if (debug.get()) {
            info("Sent stopBreaking packet for " + mc.level.getBlockState(currentBreakingBlock).getBlock().getName().getString()
                + " at distance " + getTargetDistance(Vec3.atCenterOf(currentBreakingBlock)));
        }

        resetBreaking();
    }

    private void resetBreaking() {
        currentBreakingBlock = null;
        currentBreakingDirection = null;
        currentBlockState = null;
        breakProgress = 0.0;
        hasStartedBreaking = false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!blocks.get() || !hoverBox.get() || mc.player == null || mc.level == null) return;

        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 start = camera.position();
        Vec3 end = start.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot())
            .scale(blockReach.get()));

        HitResult hit = mc.level.clip(new ClipContext(
            start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = blockHit.getBlockPos();
        // Only highlight blocks the module can actually act on (beyond vanilla reach).
        if (getTargetDistance(blockHit.getLocation()) <= VANILLA_REACH) return;

        Color side = hoverColor.get();
        Color line = new Color(side.r, side.g, side.b, 255);
        event.renderer.box(pos, side, line, ShapeMode.Both, 0);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        boolean attackKeyIsPressed = mc.options.keyAttack.isDown();
        boolean interactKeyIsPressed = mc.options.keyUse.isDown();

        if (attackKeyIsPressed && !attackKeyWasPressed) {
            onAttackClick();
        }

        if (!blocks.get() && hasStartedBreaking) {
            resetBreaking();
        } else if (attackKeyIsPressed && hasStartedBreaking) {
            updateBreakProgress();
        }

        if (!attackKeyIsPressed && hasStartedBreaking) {
            resetBreaking();
        }

        if (interactKeyIsPressed && !interactKeyWasPressed && blocks.get()) {
            interact();
        }

        attackKeyWasPressed = attackKeyIsPressed;
        interactKeyWasPressed = interactKeyIsPressed;
    }

    @Override
    public void onDeactivate() {
        resetBreaking();
        super.onDeactivate();
    }
}
