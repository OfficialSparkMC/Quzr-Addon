package com.macekill.addon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet teleport helpers (ported from brew-addon's {@code Movement}).
 */
public final class Movement {
    private Movement() {}

    /**
     * Teleports through every position in the list.
     *
     * @param positions      positions to send, in order
     * @param setClientSided whether the client player position is updated too
     * @param onGround       onGround flag for the movement packets
     */
    public static void teleport(List<Vec3> positions, boolean setClientSided, boolean onGround) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.getConnection() == null) return;

        for (Vec3 pos : positions) {
            int packetsRequired = calculatePackets(pos);

            sendPackets(onGround, packetsRequired); // Spam packets
            MeteorClient.mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
                pos.x, pos.y, pos.z, onGround, MeteorClient.mc.player.horizontalCollision)); // Send final packet
            if (setClientSided) MeteorClient.mc.player.setPos(pos); // Set client-sided position
        }
    }

    /**
     * Calculates the number of filler packets required to teleport to a position (10 blocks per packet).
     */
    public static int calculatePackets(Vec3 position) {
        if (MeteorClient.mc.player == null) return 0;

        double distance = MeteorClient.mc.player.position().distanceTo(position);
        return (int) Math.ceil(distance / 10.0) - 1;
    }

    /**
     * Teleports to a single position.
     */
    public static void teleport(Vec3 position, boolean setClientSided, boolean onGround) {
        List<Vec3> positions = new ArrayList<>();
        positions.add(position);
        teleport(positions, setClientSided, onGround);
    }

    /**
     * Teleports to one stage of a position list.
     */
    public static void execute(List<Vec3> positions, int stage, boolean setClientSided, boolean onGround) {
        execute(positions, stage, stage, setClientSided, onGround);
    }

    /**
     * Teleports through the positions between {@code startStage} and {@code endStage} (inclusive).
     */
    public static void execute(List<Vec3> positions, int startStage, int endStage, boolean setClientSided, boolean onGround) {
        List<Vec3> selected = new ArrayList<>();

        for (int i = startStage; i <= endStage && i < positions.size(); i++) {
            selected.add(positions.get(i));
        }

        teleport(selected, setClientSided, onGround);
    }

    /**
     * Spams filler position packets before the real teleport packet.
     */
    public static void sendPackets(boolean onGround, int packetsRequired) {
        if (MeteorClient.mc.player == null || MeteorClient.mc.getConnection() == null) return;
        if (packetsRequired >= 20) return;

        for (int i = 0; i < packetsRequired; i++) {
            MeteorClient.mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
                MeteorClient.mc.player.getX(), MeteorClient.mc.player.getY(), MeteorClient.mc.player.getZ(),
                onGround, MeteorClient.mc.player.horizontalCollision));
        }
    }
}
