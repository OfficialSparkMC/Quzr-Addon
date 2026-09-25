package com.macekill.addon.modules.macekill;

import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class Targeting {
    private Targeting() {}

    public static LivingEntity findBestTarget(MinecraftClient mc, double range,
                                              TargetFilter filter,
                                              SortPriority priority) {
        if (mc.world == null || mc.player == null) {
            return null;
        }
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        double rangeSq = range * range;

        Vec3d lookDir = (priority == SortPriority.ANGLE)
            ? mc.player.getRotationVec(1.0f) : null;
        Vec3d eyePos  = (priority == SortPriority.ANGLE)
            ? mc.player.getEyePos() : null;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living == mc.player || !living.isAlive()) continue;

            double distSq = mc.player.squaredDistanceTo(living);
            if (distSq >= rangeSq) continue;

            if (!filter.accepts(living)) continue;
            if (living instanceof PlayerEntity player) {
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
                                      double distSq, Vec3d lookDir, Vec3d eyePos) {
        return switch (priority) {
            case DISTANCE -> distSq;

            case ANGLE -> {
                Vec3d targetEye = entity.getEyePos();
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

    public static Vec3d predictPosition(MinecraftClient mc, LivingEntity entity,
                                        boolean predict, int predictTicks) {
        if (!predict) {
            return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        }
        Vec3d pos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        Vec3d vel = entity.getVelocity();
        if (vel.x * vel.x + vel.y * vel.y + vel.z * vel.z < 1.0E-4) {
            return pos;
        }
        for (int i = 0; i < predictTicks; i++) {
            BlockPos next = BlockPos.ofFloored(pos.x + vel.x, pos.y, pos.z + vel.z);
            if (!isSafeBlock(mc, next)) {
                vel = new Vec3d(0.0, vel.y, 0.0);
            }
            pos = pos.add(vel.x, 0.0, vel.z);
        }
        return pos;
    }

    public static boolean isSafeBlock(MinecraftClient mc, BlockPos pos) {
        if (mc == null || mc.world == null || pos == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        return state.isAir()
            && state.getFluidState().isEmpty()
            && !state.isOf(Blocks.COBWEB);
    }
}
