package com.macekill.addon.modules.macekill;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.math.Vec3d;

public final class Movement {
    private Movement() {}

    public static void doTpTo(MinecraftClient mc, Vec3d to, double moveDistance, boolean syncClientPos) {
        if (mc == null || mc.player == null || mc.getNetworkHandler() == null || to == null) return;
        if (!(moveDistance > 0)) moveDistance = 8.0;
        Vec3d from = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double dist = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(dist / moveDistance));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            sendMovePacket(mc, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t);
        }
        if (syncClientPos) {
            mc.player.updatePosition(to.x, to.y, to.z);
        }
    }

    public static void sendMovePacket(MinecraftClient mc, double x, double y, double z) {
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
    }

    public static void sendRotations(MinecraftClient mc, int count) {
        if (mc == null || mc.player == null || mc.getNetworkHandler() == null) return;
        for (int i = 0; i < count; i++) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), false));
        }
    }

    public static void sendSlotPacket(MinecraftClient mc, int slot) {
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    public static void attackEntity(MinecraftClient mc, Entity target) {
        if (mc == null || mc.player == null || mc.getNetworkHandler() == null || target == null) return;
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
    }
}
