package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.utils.Attack;
import com.macekill.addon.utils.HitResults;
import com.macekill.addon.utils.Movement;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("Debug")
        .description("Debugging shit")
        .defaultValue(false)
        .build()
    );

    public InfiniteReach() {
        super(MaceKillAddon.CATEGORY, "inf-reach", "Lets you interact with blocks and entities far away.");
    }

    private double getTargetDistance(Vec3d target) {
        if (mc.player == null) return 0;
        return mc.player.getEyePos().distanceTo(target);
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

        double distance = getTargetDistance(target.getEntityPos());
        if (distance <= VANILLA_REACH || distance > entityReach.get()) return false;

        if (debug.get()) {
            info("Attacking " + target.getName().getString() + " at distance " + distance);
        }
        Attack.attack(target, false, 0, false);
        return true;
    }

    private void interact() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        HitResult hitResult = HitResults.getCrosshairTarget(mc.player, blockReach.get(), false, entity -> true);
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos target = blockHit.getBlockPos();
            if (getTargetDistance(Vec3d.ofCenter(target)) <= VANILLA_REACH) return;

            Vec3d hitPos = Vec3d.ofCenter(target);
            BlockHitResult blockHitResult = new BlockHitResult(hitPos, blockHit.getSide(), target, false);
            Block block = mc.world.getBlockState(blockHitResult.getBlockPos()).getBlock();

            Vec3d originalPos = mc.player.getEntityPos();

            if (debug.get()) {
                info("Interacting with " + block.getName().getString() + " at distance " + getTargetDistance(hitPos));
            }
            Movement.teleport(target.toCenterPos().add(0, 2, 0), false, false);
            mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, blockHitResult, 0));
            Movement.teleport(originalPos, true, false);
        }
    }

    private void breakBlock() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        HitResult hitResult = HitResults.getCrosshairTarget(mc.player, blockReach.get(), false, entity -> true);
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos target = blockHit.getBlockPos();
            if (getTargetDistance(Vec3d.ofCenter(target)) <= VANILLA_REACH) return;

            if (currentBreakingBlock == null || !currentBreakingBlock.equals(target)) {
                resetBreaking();
                currentBreakingBlock = target;
                currentBreakingDirection = blockHit.getSide();
                currentBlockState = mc.world.getBlockState(target);
                breakProgress = 0.0;
                hasStartedBreaking = false;
            }

            if (!hasStartedBreaking) {
                Vec3d originalPos = mc.player.getEntityPos();

                Movement.teleport(target.toCenterPos().add(0, 2, 0), false, false);
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, target, currentBreakingDirection));
                Movement.teleport(originalPos, true, true);
                if (debug.get()) {
                    info("Sent startBreaking packet for " + mc.world.getBlockState(target).getBlock().getName().getString()
                        + " at distance " + getTargetDistance(Vec3d.ofCenter(target)));
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
            double score = (double) mc.player.getInventory().getStack(i).getMiningSpeedMultiplier(currentBlockState);
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
        if (currentBreakingBlock == null || mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        Vec3d originalPos = mc.player.getEntityPos();

        Movement.teleport(currentBreakingBlock.toCenterPos().add(0, 2, 0), false, false);
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, currentBreakingBlock, currentBreakingDirection));
        Movement.teleport(originalPos, true, false);
        if (debug.get()) {
            info("Sent stopBreaking packet for " + mc.world.getBlockState(currentBreakingBlock).getBlock().getName().getString()
                + " at distance " + getTargetDistance(Vec3d.ofCenter(currentBreakingBlock)));
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
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        boolean attackKeyIsPressed = mc.options.attackKey.isPressed();
        boolean interactKeyIsPressed = mc.options.useKey.isPressed();

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
