package com.macekill.addon.utils;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Safety net for every module that sends its own {@link ServerboundMovePlayerPacket} (teleports, mace
 * combat, the whole macekill.Movement helper, etc.).
 *
 * <p>Two separate things changed between 1.21.11 and 26.3 that this addon relied on:
 *
 * <p>1) Vanilla/Paper 26.3 kick with "Invalid move player packet received" if a movement packet carries
 * NaN/Infinity or an absurd coordinate. We still guard against that.
 *
 * <p>2) 26.3's tick-batching netcode only accepts one real "batch" of movement packets between two
 * {@code ServerboundClientTickEndPacket} (named {@code client_tick_end}) signals; the server uses that
 * packet to know a client tick ended and resets its movement-packet counter. Several modules here burst
 * several {@code ServerboundMovePlayerPacket}s in a single real tick (teleport ladders, totem-swap
 * position spam, Mace combat repositioning, SpearKill's packet replay, etc.). On 26.3 that burst alone
 * can trip the same "Invalid move player packet received" kick, with no bad numbers involved at all.
 * So after every move packet this addon's own code sends and that passes the NaN/range check, we also
 * send a {@code client_tick_end} right behind it, which tells the server to reset its per-tick counter
 * before the next packet arrives.
 */
public class PacketGuard {
    private static final double LIMIT = 3.0E7;
    private int logged = 0;

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof ServerboundMovePlayerPacket p)) return;

        if (!isValid(p)) {
            event.setCancelled(true);
            if (logged++ < 5) {
                MaceKillAddon.LOG.warn("Blocked an invalid move packet (x={}, y={}, z={}, yaw={}, pitch={}). Sent by:",
                    p.getX(Double.NaN), p.getY(Double.NaN), p.getZ(Double.NaN), p.getYRot(Float.NaN), p.getXRot(Float.NaN),
                    new Throwable("move packet origin"));
            }
            return;
        }

        // The 26.3 server (ServerGamePacketListenerImpl.handleMovePlayer) disconnects with "Invalid move player
        // packet received" when a SECOND position packet arrives before a client_tick_end resets its
        // receivedPositionThisTick flag. This runs before the packet is actually written, so the tick-end
        // goes out first and every position packet starts from a clean flag.
        if (p.hasPosition() && MeteorClient.mc.getConnection() != null) {
            MeteorClient.mc.getConnection().send(ServerboundClientTickEndPacket.INSTANCE);
        }
    }

    private static boolean isValid(ServerboundMovePlayerPacket p) {
        if (p.hasPosition()) {
            double x = p.getX(0), y = p.getY(0), z = p.getZ(0);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
            if (Math.abs(x) > LIMIT || Math.abs(z) > LIMIT || Math.abs(y) > LIMIT) return false;
        }
        if (p.hasRotation()) {
            if (!Float.isFinite(p.getYRot(0f)) || !Float.isFinite(p.getXRot(0f))) return false;
        }
        return true;
    }
}
