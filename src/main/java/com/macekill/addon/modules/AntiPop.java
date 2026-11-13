package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AntiPop (ported from vicore).
 *
 * <p>Strips the {@code onGround} flag from every outgoing movement packet so the
 * server never sees the player touch the ground (avoids fall damage / landing effects).
 *
 * <p>1.21.11 port note: {@code PlayerMoveC2SPacket}'s fields are {@code final}, so instead
 * of mutating the packet through a mixin accessor (as the original did), the packet is
 * rebuilt with the same position/rotation/flags and {@code onGround = false}.
 */
public class AntiPop extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> logOnEffect = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-effect")
        .description("Logs every stripped packet through module notifications.")
        .defaultValue(false)
        .build());

    public AntiPop() {
        super(MaceKillAddon.CATEGORY, "AntiPop", "Prevents you from touching ground (to avoid taking fall damage)");
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;
        if (!packet.isOnGround()) return;
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        PlayerMoveC2SPacket stripped = stripOnGround(packet);

        event.setCancelled(true);
        event.sendSilently(stripped);

        if (logOnEffect.get()) info("onGround=%s", stripped.isOnGround());
    }

    private PlayerMoveC2SPacket stripOnGround(PlayerMoveC2SPacket packet) {
        double x = packet.getX(mc.player.getX());
        double y = packet.getY(mc.player.getY());
        double z = packet.getZ(mc.player.getZ());
        float yaw = packet.getYaw(mc.player.getYaw());
        float pitch = packet.getPitch(mc.player.getPitch());
        boolean horizontalCollision = packet.horizontalCollision();

        if (packet.changesPosition() && packet.changesLook()) {
            return new PlayerMoveC2SPacket.Full(x, y, z, yaw, pitch, false, horizontalCollision);
        }
        if (packet.changesPosition()) {
            return new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, horizontalCollision);
        }
        if (packet.changesLook()) {
            return new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, false, horizontalCollision);
        }
        return new PlayerMoveC2SPacket.OnGroundOnly(false, horizontalCollision);
    }
}
