package com.macekill.addon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
        if (entity == null || MeteorClient.mc.world == null) return null;

        Camera camera = MeteorClient.mc.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getCameraPos();

        Vec3d direction = Vec3d.fromPolar(camera.getPitch(), camera.getYaw()).multiply(range);
        Vec3d targetPos = cameraPos.add(direction);

        EntityHitResult entityHitResult = ProjectileUtil.raycast(
            entity,
            cameraPos,
            targetPos,
            entity.getBoundingBox().stretch(direction).expand(1),
            filter.and(targetEntity -> !targetEntity.isSpectator()),
            range * range
        );

        if (entityHitResult != null) {
            return entityHitResult;
        }

        if (!ignoreBlocks) {
            RaycastContext context = new RaycastContext(
                cameraPos,
                targetPos,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                entity
            );

            HitResult hitResult = MeteorClient.mc.world.raycast(context);
            if (hitResult != null && hitResult.getType() != HitResult.Type.MISS) {
                return hitResult;
            }
        }

        return null;
    }
}
