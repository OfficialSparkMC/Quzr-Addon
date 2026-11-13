package com.macekill.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;

import static com.macekill.addon.MaceKillAddon.CATEGORY;

public class Hitback extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("Rotate")
        .description("Rotate to face the attacker before hitting back.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder()
        .name("Ignore Friends")
        .description("Do not hit back friends.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> onlyPlayers = sg.add(new BoolSetting.Builder()
        .name("Only Players")
        .description("Only hit back players, not mobs/entities.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> cooldown = sg.add(new IntSetting.Builder()
        .name("Cooldown")
        .description("Minimum ticks between hitbacks.")
        .defaultValue(10)
        .min(0)
        .max(200)
        .sliderMax(200)
        .build());

    private int cd;

    public Hitback() {
        super(CATEGORY, "Hitback", "Automatically hits back whoever damages you.");
    }

    @Override
    public void onActivate() {
        cd = 0;
    }

    @EventHandler
    private void onDamagePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityDamageS2CPacket packet)) return;
        if (mc.player == null || mc.world == null) return;
        if (packet.entityId() != mc.player.getId()) return;

        int attackerId = packet.sourceCauseId();
        if (attackerId <= 0) return;

        Entity attacker = mc.world.getEntityById(attackerId);
        if (attacker == null || attacker == mc.player) return;
        if (onlyPlayers.get() && !(attacker instanceof PlayerEntity)) return;
        if (ignoreFriends.get() && attacker instanceof PlayerEntity p && Friends.get().isFriend(p)) return;
        // Server drops attacks beyond survival reach; skip instead of flagging anticheat.
        if (mc.player.squaredDistanceTo(attacker) > 4.5 * 4.5) return;

        if (cd > 0) return;
        cd = cooldown.get();
        hitBack(attacker);
    }

    private void hitBack(Entity attacker) {
        if (mc.player == null || mc.getNetworkHandler() == null || attacker == null) return;
        Runnable attack = () -> mc.getNetworkHandler().sendPacket(
            PlayerInteractEntityC2SPacket.attack(attacker, mc.player.isSneaking())
        );

        if (rotate.get()) {
            double dx = attacker.getX() - mc.player.getX();
            double dy = attacker.getEyeY() - mc.player.getEyeY();
            double dz = attacker.getZ() - mc.player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
            Rotations.rotate(yaw, pitch, attack);
        } else {
            attack.run();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (cd > 0) cd--;
    }
}
