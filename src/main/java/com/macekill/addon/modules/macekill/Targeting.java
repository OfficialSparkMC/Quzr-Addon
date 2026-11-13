package com.macekill.addon.modules.macekill;

import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class Targeting {
    private Targeting() {}

    public static LivingEntity findBestTarget(Minecraft mc, double range,
                                              TargetFilter filter,
                                              SortPriority priority) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        double rangeSq = range * range;

        Vec3 lookDir = (priority == SortPriority.ANGLE)
            ? mc.player.getViewVector(1.0f) : null;
        Vec3 eyePos  = (priority == SortPriority.ANGLE)
            ? mc.player.getEyePosition() : null;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living == mc.player || !living.isAlive()) continue;

            double distSq = mc.player.distanceToSqr(living);
            if (distSq >= rangeSq) continue;

            if (!filter.accepts(living)) continue;
            if (living instanceof Player player) {
                if (player.isCreative() || player.isSpectator()
                        || !Friends.get().shouldAttack(player)) continue;
            }

            double score = scoreTarget(living, priority, distSq, lookDir, eyePos);
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private static double scoreTarget(LivingEntity entity, SortPriority priority,
                                      double distSq, Vec3 lookDir, Vec3 eyePos) {
        return switch (priority) {
            case DISTANCE -> distSq;

            case ANGLE -> {
                Vec3 targetEye = entity.getEyePosition();
                double dx = targetEye.x - eyePos.x;
                double dy = targetEye.y - eyePos.y;
                double dz = targetEye.z - eyePos.z;
                double lenSq = dx * dx + dy * dy + dz * dz;
                if (lenSq < 1.0E-8) yield -1.0;
                double invLen = 1.0 / Math.sqrt(lenSq);
                double cos = (lookDir.x * dx + lookDir.y * dy + lookDir.z * dz) * invLen;
                yield -cos;
            }

            case HEALTH -> entity.getHealth();
        };
    }

    public static Vec3 predictPosition(Minecraft mc, LivingEntity entity,
                                        boolean predict, int predictTicks) {
        if (!predict) {
            return new Vec3(entity.getX(), entity.getY(), entity.getZ());
        }
        Vec3 pos = new Vec3(entity.getX(), entity.getY(), entity.getZ());
        Vec3 vel = entity.getDeltaMovement();
        if (vel.x * vel.x + vel.y * vel.y + vel.z * vel.z < 1.0E-4) {
            return pos;
        }
        for (int i = 0; i < predictTicks; i++) {
            BlockPos next = BlockPos.containing(pos.x + vel.x, pos.y, pos.z + vel.z);
            if (!isSafeBlock(mc, next)) {
                vel = new Vec3(0.0, vel.y, 0.0);
            }
            pos = pos.add(vel.x, 0.0, vel.z);
        }
        return pos;
    }

    public static boolean isSafeBlock(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.level == null || pos == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir()
            && state.getFluidState().isEmpty()
            && !state.is(Blocks.COBWEB);
    }
}
