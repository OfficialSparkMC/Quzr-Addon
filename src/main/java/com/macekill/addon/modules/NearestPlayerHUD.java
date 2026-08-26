package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 最近玩家HUD — 在屏幕上显示最近玩家的名字和距离
 */
public class NearestPlayerHUD extends Module {

    // ==================== 设置 ====================
    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Double> range = sgMain.add(new DoubleSetting.Builder()
            .name("Range").description("Detection range")
            .defaultValue(100).min(1).max(500).sliderRange(1, 500).build()
    );

    public NearestPlayerHUD() {
        super(MaceKillAddon.CATEGORY, "NearestPlayerHUD", "Shows nearest player info");
    }

    // ==================== HUD信息 ====================

    @Override
    public String getInfoString() {
        if (mc.player == null || mc.world == null) return "None";

        double rangeSq = range.get() * range.get();
        PlayerEntity nearest = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PlayerEntity p)) continue;
            if (p == mc.player || !p.isAlive()) continue;

            double distSq = mc.player.squaredDistanceTo(p);
            if (distSq > rangeSq) continue;

            if (distSq < bestDist) {
                bestDist = distSq;
                nearest = p;
            }
        }

        if (nearest == null) return "None";

        double dist = mc.player.distanceTo(nearest);
        return String.format("%s %.1f", nearest.getName().getString(), dist);
    }
}
