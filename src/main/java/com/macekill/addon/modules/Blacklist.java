package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Blacklist — automatically smashes every blacklisted player inside the scan range
 * with the UltraAura smash code (approach packets, weapon switch, attack, return).
 *
 * <p>The scan only runs while the module is enabled, once per {@code Attack Delay}
 * ticks: it looks for a living, non-spectator, non-creative player within
 * {@code Scan Distance} (default 60 blocks) whose name (case-insensitive) is in the
 * {@code Names} list, and skips Meteor friends while {@code Ignore Friends} is on.
 * The hit itself is {@link UltraAura#smash}, so the movement style (PAPER single
 * position vs SPIGOT stepped path), the weapon switch, and the silent swap come
 * entirely from the UltraAura module's settings — this module has no duplicated
 * combat logic.
 *
 * <p>{@code Names} and {@code Scan Distance} are plain module settings, so changes
 * made through the {@code .blacklist} command persist in Meteor's module save.
 */
public class Blacklist extends Module {
    private static Blacklist INSTANCE;

    /** Upper bound accepted by {@code .blacklist dis} (the slider only shows 0-128). */
    public static final int MAX_DISTANCE = 256;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> names = sgGeneral.add(new StringListSetting.Builder()
        .name("Names")
        .description("Blacklisted player names (case-insensitive). Manage with .blacklist add/remove <player>.")
        .defaultValue()
        .build()
    );

    private final Setting<Integer> distance = sgGeneral.add(new IntSetting.Builder()
        .name("Scan Distance")
        .description("Radius in blocks where blacklisted players get smashed (.blacklist dis <value>).")
        .defaultValue(60)
        .min(0)
        .max(MAX_DISTANCE)
        .sliderRange(0, 128)
        .build()
    );

    private final Setting<Integer> attackDelay = sgGeneral.add(new IntSetting.Builder()
        .name("Attack Delay")
        .description("Ticks between smashes. Low values add packets very fast.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("Ignore Friends")
        .description("Do not attack players on the Meteor friends list even when blacklisted.")
        .defaultValue(true)
        .build()
    );

    private int timer;

    public Blacklist() {
        super(MaceKillAddon.CATEGORY, "Blacklist", "Auto-smashes blacklisted players within Scan Distance using the UltraAura smash code. Manage the list with .blacklist");
        INSTANCE = this;
    }

    @Override
    public void onActivate() {
        // First smash fires immediately after enabling (timer counts up to the delay).
        this.timer = this.attackDelay.get();

        if (this.names.get().isEmpty()) {
            this.info("Watching nothing yet — add a name with .blacklist add <player>.");
        } else {
            this.info("Watching %d name(s) within %d blocks.", this.names.get().size(), this.distance.get());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        this.timer++;

        if (this.timer < this.attackDelay.get()) return;
        if (this.mc.player == null || this.mc.world == null || this.mc.getNetworkHandler() == null) return;

        PlayerEntity victim = this.findBlacklisted();
        if (victim == null) return;

        UltraAura ultra = Modules.get().get(UltraAura.class);
        if (ultra == null) {
            this.error("UltraAura module missing — Blacklist cannot smash.");
            return;
        }

        if (ultra.smash(victim)) {
            this.info("Blacklist: smashed %s (%.1f blocks away).",
                victim.getName().getString(), this.mc.player.distanceTo(victim));
            this.timer = 0;
        }
    }

    /** First blacklisted, attackable, in-range player, or null. */
    private PlayerEntity findBlacklisted() {
        List<String> recorded = this.names.get();
        if (recorded.isEmpty()) return null;

        for (PlayerEntity player : this.mc.world.getPlayers()) {
            if (player == this.mc.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;

            String name = player.getName().getString();
            boolean blacklisted = false;
            for (String recordedName : recorded) {
                if (recordedName.equalsIgnoreCase(name)) {
                    blacklisted = true;
                    break;
                }
            }
            if (!blacklisted) continue;

            if (this.ignoreFriends.get() && !Friends.get().shouldAttack(player)) continue;
            if (!this.withinScanRange(player)) continue;

            return player;
        }
        return null;
    }

    /** Same box-clamped distance concept as UltraAura's isValidTarget. */
    private boolean withinScanRange(PlayerEntity player) {
        Box box = player.getBoundingBox();
        double x = MathHelper.clamp(this.mc.player.getX(), box.minX, box.maxX);
        double y = MathHelper.clamp(this.mc.player.getY(), box.minY, box.maxY);
        double z = MathHelper.clamp(this.mc.player.getZ(), box.minZ, box.maxZ);
        return PlayerUtils.isWithin(x, y, z, this.distance.get());
    }

    // ==================== Command API (.blacklist) ====================

    /** Current entries as a copy. */
    public List<String> nameSnapshot() {
        return new ArrayList<>(this.names.get());
    }

    /**
     * Adds a name (case-insensitive duplicate check; the entry is stored with an online
     * player's exact spelling whenever one matches). Returns the stored name, or null
     * when it is already blacklisted.
     */
    public String addName(String raw) {
        String request = raw == null ? "" : raw.trim();
        if (request.isEmpty()) return null;

        String name = this.canonical(request);
        List<String> updated = new ArrayList<>(this.names.get());
        for (String entry : updated) {
            if (entry.equalsIgnoreCase(name)) return null;
        }
        updated.add(name);
        return this.names.set(updated) ? name : null;
    }

    /** Removes by case-insensitive match. Returns the removed entry, or null when absent. */
    public String removeName(String raw) {
        String request = raw == null ? "" : raw.trim();
        if (request.isEmpty()) return null;

        List<String> updated = new ArrayList<>(this.names.get());
        String removed = null;
        for (String entry : updated) {
            if (entry.equalsIgnoreCase(request)) {
                removed = entry;
                break;
            }
        }
        if (removed == null) return null;
        updated.remove(removed);
        return this.names.set(updated) ? removed : null;
    }

    public int getDistance() {
        return this.distance.get();
    }

    /** Called by .blacklist dis; clamped by the setting's builder (0..MAX_DISTANCE). */
    public void setDistance(int value) {
        this.distance.set(MathHelper.clamp(value, 0, MAX_DISTANCE));
    }

    /** Normalizes to an online player's spelling when one matches (ignoring case). */
    private String canonical(String request) {
        if (this.mc != null && this.mc.world != null) {
            for (PlayerEntity player : this.mc.world.getPlayers()) {
                String name = player.getName().getString();
                if (name.equalsIgnoreCase(request)) return name;
            }
        }
        return request;
    }

    public static Blacklist get() {
        return INSTANCE;
    }

    @Override
    public String getInfoString() {
        List<String> recorded = this.names.get();
        return recorded.isEmpty() ? null : recorded.size() + " names";
    }
}
