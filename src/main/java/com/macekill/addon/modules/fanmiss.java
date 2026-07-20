package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

/**
 * 防空刀 — 重锤反空刀，通过快速VClip闪烁防止服务器判定空刀
 */
public class fanmiss extends Module {

    // ==================== 设置 ====================
    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Double> detectRange = sgMain.add(new DoubleSetting.Builder()
            .name("范围").description("检测附近玩家的范围")
            .defaultValue(6).min(1).max(20).sliderRange(1, 20).build()
    );

    private final Setting<Double> riseHeight = sgMain.add(new DoubleSetting.Builder()
            .name("高度").description("VClip闪烁高度")
            .defaultValue(10).min(1).max(50).sliderRange(1, 50).build()
    );

    // ==================== 状态 ====================
    private PlayerEntity nearestPlayer;

    public fanmiss() {
        super(MaceKillAddon.CATEGORY, "fanmiss", "防空刀 - 防止重锤空刀，快速VClip闪烁");
    }

    @Override
    public void onDeactivate() {
        nearestPlayer = null;
    }

    // ==================== Tick ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        nearestPlayer = findNearestPlayer();
        if (nearestPlayer == null) return;

        double h = riseHeight.get();
        double startY = mc.player.getY();

        // 快速向上闪烁：10个包
        for (int i = 1; i <= 10; i++) {
            double y = startY + (h / 10.0) * i;
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), y, mc.player.getZ(),
                            false, false));
        }

        // 快速向下闪烁：10个包
        for (int i = 1; i <= 10; i++) {
            double y = startY + h - (h / 10.0) * i;
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), y, mc.player.getZ(),
                            false, false));
        }

        // 回到原位
        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), startY, mc.player.getZ(),
                        mc.player.isOnGround(), false));
    }

    // ==================== 目标查找 ====================

    private PlayerEntity findNearestPlayer() {
        if (mc.world == null || mc.player == null) return null;
        double rangeSq = detectRange.get() * detectRange.get();
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PlayerEntity p)) continue;
            if (p == mc.player || !p.isAlive()) continue;
            if (!Friends.get().shouldAttack(p)) continue;

            double distSq = mc.player.squaredDistanceTo(p);
            if (distSq > rangeSq) continue;

            if (distSq < bestDist) {
                bestDist = distSq;
                best = p;
            }
        }
        return best;
    }

    // ==================== HUD信息 ====================

    @Override
    public String getInfoString() {
        if (nearestPlayer != null && mc.player != null) {
            double dist = mc.player.distanceTo(nearestPlayer);
            return String.format("%s %.1fm", nearestPlayer.getName().getString(), dist);
        }
        return "无目标";
    }
}
