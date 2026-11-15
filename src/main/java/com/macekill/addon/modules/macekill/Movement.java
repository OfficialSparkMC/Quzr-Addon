package com.macekill.addon.modules.macekill;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.phys.Vec3;

public final class Movement {
    private Movement() {}

    public static void doTpTo(Minecraft mc, Vec3 to, double moveDistance, boolean syncClientPos) {
        if (mc == null || mc.player == null || mc.getConnection() == null || to == null) return;
        if (!(moveDistance > 0)) moveDistance = 8.0;
        Vec3 from = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        double dist = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(dist / moveDistance));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            sendMovePacket(mc, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t);
        }
        if (syncClientPos) {
            mc.player.setPos(to.x, to.y, to.z);
        }
    }

    public static void sendMovePacket(Minecraft mc, double x, double y, double z) {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, false));
    }

    public static void sendRotations(Minecraft mc, int count) {
        if (mc == null || mc.player == null || mc.getConnection() == null) return;
        for (int i = 0; i < count; i++) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), false));
        }
    }

    public static void sendSlotPacket(Minecraft mc, int slot) {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    public static void attackEntity(Minecraft mc, Entity target) {
        if (mc == null || mc.player == null || mc.getConnection() == null || target == null) return;
        mc.getConnection().send(new ServerboundAttackPacket(target.getId()));
    }
}
