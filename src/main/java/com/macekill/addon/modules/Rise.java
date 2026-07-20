package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class Rise extends Module {

    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Integer> height = sgMain.add(new IntSetting.Builder()
            .name("高度").description("VClip上升高度")
            .defaultValue(10).min(1).max(50).build());

    private final Setting<Integer> delay = sgMain.add(new IntSetting.Builder()
            .name("延迟").description("每次上升间隔(tick)")
            .defaultValue(20).min(1).max(100).build());

    private final Setting<Boolean> bypass = sgMain.add(new BoolSetting.Builder()
            .name("绕过").description("启用绕过模式")
            .defaultValue(true).build());

    private int delayTicks;

    public Rise() {
        super(MaceKillAddon.CATEGORY, "Rise", "垂直上升");
    }

    @Override
    public void onDeactivate() {
        delayTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (delayTicks > 0) { delayTicks--; return; }

        if (!bypass.get() && delayTicks > 0) return;

        double posX = mc.player.getX();
        double posY = mc.player.getY();
        double posZ = mc.player.getZ();

        // 每tick发送10组上下抖动包
        for (int i = 0; i < 10; i++) {
            // 上升包
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    posX, posY + height.get(), posZ, false, false));

            // 回落包
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    posX, posY, posZ, false, false));
        }

        delayTicks = delay.get();
    }

    @Override
    public String getInfoString() {
        if (delayTicks > 0) return "CD " + delayTicks;
        return "+" + height.get();
    }
}
