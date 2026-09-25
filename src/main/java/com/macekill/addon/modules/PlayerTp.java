package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.Movement;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * PlayerTp - one-shot teleport to the nearest player.
 *
 * Enable (or press its bind) -> stepped packet travel to the nearest
 * attackable player inside Range -> client + server moved -> auto disables.
 * Named teleports are handled by the ".tp &lt;player&gt;" chat command
 * (see com.macekill.addon.commands.TpCommand), which reuses teleportTo().
 */
public class PlayerTp extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("Range").description("Max distance to search for a player")
        .defaultValue(100.0).min(1.0).max(1000.0).sliderRange(1.0, 500.0).build());

    private final Setting<Double> moveDistance = sg.add(new DoubleSetting.Builder()
        .name("Move Step").description("Max distance per movement packet (raise for long TPs, lowers packet count)")
        .defaultValue(8.0).min(1.0).max(128.0).sliderRange(1.0, 128.0).build());

    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder()
        .name("Ignore Friends").description("Never teleport to friends")
        .defaultValue(true).build());

    private String lastTarget = "";

    public PlayerTp() {
        super(MaceKillAddon.CATEGORY, "PlayerTp", "One-shot teleport to the nearest player (.tp <player> for names)");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }
        PlayerEntity target = findNearest();
        if (target == null) {
            error("No player in range.");
            toggle();
            return;
        }
        teleportTo(new Vec3d(target.getX(), target.getY(), target.getZ()));
        lastTarget = target.getName().getString();
        info("Teleported to %s", lastTarget);
        toggle();
    }

    /** Nearest attackable player inside Range, or null. */
    private PlayerEntity findNearest() {
        if (mc.world == null || mc.player == null) return null;
        double rangeSq = range.get() * range.get();
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            if (ignoreFriends.get() && !Friends.get().shouldAttack(p)) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d > rangeSq || d >= bestDist) continue;
            bestDist = d;
            best = p;
        }
        return best;
    }

    /**
     * Stepped packet travel to dest (through walls/ceilings — position packets are not
     * collision-checked). Updates the client too so both sides stay in sync.
     * Safe to call from the .tp command (client thread).
     */
    public static void teleportTo(Vec3d dest) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null || dest == null) return;
        PlayerTp m = Modules.get().get(PlayerTp.class);
        double step = m != null ? Math.max(1.0, m.moveDistance.get()) : 8.0;
        Movement.doTpTo(mc, dest, step, true);
    }

    @Override
    public String getInfoString() {
        return lastTarget.isEmpty() ? "Ready" : lastTarget;
    }
}
