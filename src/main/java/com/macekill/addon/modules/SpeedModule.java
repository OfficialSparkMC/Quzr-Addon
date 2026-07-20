package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class SpeedModule extends Module {

    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Integer> riseHeight = sgMain.add(new IntSetting.Builder()
            .name("上升高度").description("每次循环的上升高度")
            .defaultValue(5).min(1).max(20).build());

    private final Setting<Integer> cycles = sgMain.add(new IntSetting.Builder()
            .name("循环次数").description("上升循环次数")
            .defaultValue(10).min(1).max(50).build());

    public SpeedModule() {
        super(MaceKillAddon.CATEGORY, "Speed", "循环上升");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        double posX = mc.player.getX();
        double posY = mc.player.getY();
        double posZ = mc.player.getZ();
        int h = riseHeight.get();
        int n = cycles.get();

        // 从0到cycles（含）循环，每级上升riseHeight * i
        for (int i = 0; i <= n; i++) {
            double targetY = posY + h * i;
            for (int j = 0; j < 3; j++) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        posX, targetY, posZ, false, false));
            }
        }
    }

    @Override
    public String getInfoString() {
        return riseHeight.get() + "x" + cycles.get();
    }
}
