package com.macekill.addon.modules.macekill;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.math.Vec3d;

public final class Movement {
    private Movement() {}

    public static void doTpTo(MinecraftClient mc, Vec3d to, double moveDistance, boolean kehd) {
        Vec3d from = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double dist = from.distanceTo(to);
        int steps = (int) Math.ceil(dist / moveDistance);
        for (int i = 1; i <= steps; i++) {
            sendMovePacket(mc, from.x, from.y, from.z);
        }
        sendMovePacket(mc, to.x, to.y, to.z);
        if (kehd) {
            mc.player.updatePosition(to.x, to.y, to.z);
        }
    }

    public static void sendMovePacket(MinecraftClient mc, double x, double y, double z) {
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
    }

    public static void sendRotations(MinecraftClient mc, int count) {
        if (mc.getNetworkHandler() == null) return;
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
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
    }
}
