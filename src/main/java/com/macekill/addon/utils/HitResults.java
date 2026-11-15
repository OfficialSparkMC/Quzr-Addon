package com.macekill.addon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.function.Predicate;

/**
 * Crosshair raycasts with a custom reach (ported from brew-addon's {@code HitResults}).
 */
public final class HitResults {
    private HitResults() {}

    /**
     * Raycasts from the camera along the crosshair, up to {@code range} blocks.
     *
     * @param entity      entity used for the raycast (usually the client player)
     * @param range       maximum distance
     * @param ignoreBlocks if true, block collisions are ignored (entities only)
     * @param filter      additional entity filter
     */
    public static HitResult getCrosshairTarget(Entity entity, double range, boolean ignoreBlocks, Predicate<Entity> filter) {
        if (entity == null || MeteorClient.mc.level == null) return null;

        Camera camera = MeteorClient.mc.gameRenderer.mainCamera();
        Vec3 cameraPos = camera.position();

        Vec3 direction = Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(range);
        Vec3 targetPos = cameraPos.add(direction);

        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(
            entity,
            cameraPos,
            targetPos,
            entity.getBoundingBox().expandTowards(direction).inflate(1),
            filter.and(targetEntity -> !targetEntity.isSpectator()),
            range * range
        );

        if (entityHitResult != null) {
            return entityHitResult;
        }

        if (!ignoreBlocks) {
            ClipContext context = new ClipContext(
                cameraPos,
                targetPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                entity
            );

            HitResult hitResult = MeteorClient.mc.level.clip(context);
            if (hitResult != null && hitResult.getType() != HitResult.Type.MISS) {
                return hitResult;
            }
        }

        return null;
    }
}
