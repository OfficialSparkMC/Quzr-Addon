package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Anti-air miss module.
 * Uses rapid vertical position packets to attempt to prevent hammer attacks
 * from being registered as misses.
 */
public class AntiAirMiss extends Module {

    // ==================== Settings ====================

    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Double> detectionRange = sgMain.add(
        new DoubleSetting.Builder()
            .name("detection-range")
            .description("Maximum distance for detecting nearby players.")
            .defaultValue(6)
            .min(1)
            .max(20)
            .sliderRange(1, 20)
            .build()
    );

    private final Setting<Double> verticalOffset = sgMain.add(
        new DoubleSetting.Builder()
            .name("vertical-offset")
            .description("Maximum vertical distance used for the position packet sequence.")
            .defaultValue(10)
            .min(1)
            .max(50)
            .sliderRange(1, 50)
            .build()
    );

    // ==================== State ====================

    private Player targetPlayer;

    public AntiAirMiss() {
        super(
            MaceKillAddon.CATEGORY,
            "anti-air-miss",
            "Attempts to prevent hammer attacks from missing by sending rapid vertical position updates."
        );
    }

    @Override
    public void onDeactivate() {
        targetPlayer = null;
    }

    // ==================== Tick ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null ||
            mc.level == null ||
            mc.getConnection() == null) {
            return;
        }

        targetPlayer = findNearestPlayer();

        if (targetPlayer == null) {
            return;
        }

        double offset = verticalOffset.get();
        double originalY = mc.player.getY();

        // Send rapid upward position updates.
        for (int step = 1; step <= 10; step++) {
            double y = originalY + (offset / 10.0) * step;

            mc.getConnection().send(
                new ServerboundMovePlayerPacket.Pos(
                    mc.player.getX(),
                    y,
                    mc.player.getZ(),
                    false,
                    false
                )
            );
        }

        // Send rapid downward position updates.
        for (int step = 1; step <= 10; step++) {
            double y = originalY + offset - (offset / 10.0) * step;

            mc.getConnection().send(
                new ServerboundMovePlayerPacket.Pos(
                    mc.player.getX(),
                    y,
                    mc.player.getZ(),
                    false,
                    false
                )
            );
        }

        // Restore the original position.
        mc.getConnection().send(
            new ServerboundMovePlayerPacket.Pos(
                mc.player.getX(),
                originalY,
                mc.player.getZ(),
                mc.player.onGround(),
                false
            )
        );
    }

    // ==================== Target Selection ====================

    private Player findNearestPlayer() {
        if (mc.level == null || mc.player == null) {
            return null;
        }

        double rangeSquared =
            detectionRange.get() * detectionRange.get();

        Player closestPlayer = null;
        double closestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player)) {
                continue;
            }

            if (player == mc.player || !player.isAlive()) {
                continue;
            }

            if (!Friends.get().shouldAttack(player)) {
                continue;
            }

            double distanceSquared =
                mc.player.distanceToSqr(player);

            if (distanceSquared > rangeSquared) {
                continue;
            }

            if (distanceSquared < closestDistanceSquared) {
                closestDistanceSquared = distanceSquared;
                closestPlayer = player;
            }
        }

        return closestPlayer;
    }

    // ==================== HUD Information ====================

    @Override
    public String getInfoString() {
        if (targetPlayer != null && mc.player != null) {
            double distance = mc.player.distanceTo(targetPlayer);

            return String.format(
                "%s %.1fm",
                targetPlayer.getName().getString(),
                distance
            );
        }

        return "No target";
    }
}
