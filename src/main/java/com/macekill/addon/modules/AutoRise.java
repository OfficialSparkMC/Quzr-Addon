package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class AutoRise extends Module {

    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Double> detectRange = sgMain.add(new DoubleSetting.Builder()
            .name("Detection Range").description("Target detection range")
            .defaultValue(6.0).min(1.0).max(20.0).sliderRange(1.0, 20.0).build());

    private final Setting<Double> riseHeight = sgMain.add(new DoubleSetting.Builder()
            .name("Rise Height").description("VClip rise height")
            .defaultValue(10.0).min(1.0).max(50.0).sliderRange(1.0, 50.0).build());

    private final Setting<Boolean> sendRotationsWhenHigh = sgMain.add(new BoolSetting.Builder()
            .name("Send Rotations").description("Send junk rotation packets when height > 12")
            .defaultValue(true).build());

    public AutoRise() {
        super(MaceKillAddon.CATEGORY, "AutoRise", "Automatically rises");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        double h = riseHeight.get();

        // 如果高度>12且开启了发送旋转，先发4个垃圾旋转包
        if (sendRotationsWhenHigh.get() && h > 12.0) {
            for (int i = 0; i < 4; i++) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                        mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), false));
            }
        }

        // 找到最近的目标（可选：有目标才上升）
        LivingEntity target = findTarget();
        if (target == null) return;

        double posX = mc.player.getX();
        double posY = mc.player.getY();
        double posZ = mc.player.getZ();

        // 第一个包：上升
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                posX, posY + h, posZ, false, false));

        // 第二个包：返回原位
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                posX, posY, posZ, false, false));
    }

    private LivingEntity findTarget() {
        if (mc.world == null || mc.player == null) return null;
        double rangeSq = detectRange.get() * detectRange.get();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le == mc.player) continue;
            double distSq = mc.player.squaredDistanceTo(le);
            if (distSq > rangeSq) continue;
            if (le instanceof PlayerEntity p) {
                if (p.isCreative() || p.isSpectator() || !Friends.get().shouldAttack(p)) continue;
            }
            if (distSq < bestDist) { bestDist = distSq; best = le; }
        }
        return best;
    }

    @Override
    public String getInfoString() {
        return String.format("%.1f", riseHeight.get());
    }
}
