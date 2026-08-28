package com.macekill.addon.modules;

import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.lwjgl.glfw.GLFW;

import static com.macekill.addon.MaceKillAddon.CATEGORY;

/**
 * FreecamTp - enables Meteor's Freecam and teleports your real player to the
 * freecam camera position when you right-click. Fly the camera somewhere, right
 * click, and your player is moved there.
 */
public class FreecamTp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> eyeOffset = sgGeneral.add(new DoubleSetting.Builder()
            .name("Eye Offset").description("Subtracted from the camera Y so your feet land where the camera eye is (player eye height ~1.62)")
            .defaultValue(1.62).min(0).max(3).sliderRange(0, 3).build()
    );

    private boolean freecamWasEnabled;

    public FreecamTp() {
        super(CATEGORY, "freecamtp", "Freecam navigator - fly the camera and right-click to teleport your player there");
    }

    @Override
    public void onActivate() {
        Freecam freecam = Modules.get().get(Freecam.class);
        freecamWasEnabled = freecam.isActive();
        if (!freecamWasEnabled) freecam.toggle();
    }

    @Override
    public void onDeactivate() {
        if (!freecamWasEnabled) {
            Freecam freecam = Modules.get().get(Freecam.class);
            if (freecam.isActive()) freecam.toggle();
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_2) return; // right click

        Freecam freecam = Modules.get().get(Freecam.class);
        if (mc.player == null || !freecam.isActive()) return;

        double yOff = eyeOffset.get();
        double x = freecam.pos.x;
        double y = freecam.pos.y - yOff;
        double z = freecam.pos.z;

        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, mc.player.isOnGround(), false));
        mc.player.setPos(x, y, z);

        info("Teleported to freecam position (%.1f, %.1f, %.1f)", x, y, z);
        event.cancel();
    }
}
