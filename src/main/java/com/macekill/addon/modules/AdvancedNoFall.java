package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * AdvancedNoFall - takes 0 fall damage without touching mace-smash damage.
 *
 * How it stays smash-safe: it triggers ONLY on CLIENT-side fall state
 * (player.fallDistance). Packet smashes (TpMace / MaceAttect / macemiss / aura)
 * move the SERVER via packets but never move the client, so client fallDistance
 * stays ~0 and this module stays completely idle while the fake fall for the
 * mace bonus accrues server-side. It only fires when YOU are really falling
 * (stranded sky-high by Rise / Speed / a return trip / freecam TP ...), spoofing
 * onGround=true every tick so the server can never accumulate a lethal fall.
 *
 * Does NOT save from the void (void damage is not fall damage) and, like any
 * onGround spoof, can flag strict anticheats — test-server first.
 */
public class AdvancedNoFall extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> fallThreshold = sg.add(new DoubleSetting.Builder()
        .name("Fall Threshold").description("Client fall distance (blocks) that triggers the spoof")
        .defaultValue(3.0).min(1.0).max(20.0).sliderRange(1.0, 10.0).build());

    private final Setting<Boolean> fastFall = sg.add(new BoolSetting.Builder()
        .name("Fast-Fall Trigger").description("Also trigger on fast downward velocity (catches lag spikes, slight flag risk)")
        .defaultValue(false).build());

    private final Setting<Integer> packets = sg.add(new IntSetting.Builder()
        .name("Packets").description("onGround packets sent per tick while falling (redundancy vs packet loss)")
        .defaultValue(1).min(1).max(5).sliderMax(5).build());

    private final Setting<Boolean> pauseOnElytra = sg.add(new BoolSetting.Builder()
        .name("Pause on Elytra").description("Stay idle while gliding")
        .defaultValue(true).build());

    private boolean wasFalling;

    public AdvancedNoFall() {
        super(MaceKillAddon.CATEGORY, "AdvancedNoFall", "Zero fall damage, never touches mace-smash fall");
    }

    @Override
    public void onDeactivate() {
        wasFalling = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        // No damage possible, or states where spoofing hurts more than it helps.
        if (mc.player.isCreative() || mc.player.isSpectator() || mc.player.hasVehicle()) {
            wasFalling = false;
            return;
        }
        if (mc.player.isOnGround()) {
            wasFalling = false;
            return;
        }
        if (pauseOnElytra.get() && mc.player.isGliding()) return;

        boolean trigger = mc.player.fallDistance > fallThreshold.get();
        if (!trigger && fastFall.get()) {
            trigger = mc.player.getVelocity().y < -0.7;
        }
        if (!trigger) {
            wasFalling = false;
            return;
        }
        wasFalling = true;

        // Reset the SERVER's accumulated fall every tick — landing can never be lethal.
        // Same X/Y/Z, only onGround flips, so this never creates fall distance of its own
        // and never interferes with in-flight smash packets.
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        for (int i = 0; i < packets.get(); i++) {
            mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, true, false));
        }
    }

    @Override
    public String getInfoString() {
        if (wasFalling && mc.player != null) return String.format("Falling %.1f", mc.player.fallDistance);
        return "Ready";
    }
}
