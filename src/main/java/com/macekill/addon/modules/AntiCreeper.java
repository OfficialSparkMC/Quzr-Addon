package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.utils.Attack;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;

/**
 * AntiCreeper (ported from brew-addon 1.21.8).
 * Automatically kills creepers either when their fuse lights up or right after they spawn.
 */
public class AntiCreeper extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum KillMode {
        OnExplode("onExplode"),
        OnSpawn("onSpawn");

        private final String description;

        KillMode(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return this.description;
        }
    }

    private final Setting<KillMode> mode = sgGeneral.add(new EnumSetting.Builder<KillMode>()
        .name("kill-mode")
        .description("When to kill creepers.")
        .defaultValue(KillMode.OnExplode)
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Max distance creepers will get attacked from.")
        .defaultValue(4)
        .build()
    );

    public AntiCreeper() {
        super(MaceKillAddon.CATEGORY, "b-anti-creeper", "Automatically kills creepers.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mode.get() == KillMode.OnSpawn) return;
        if (mc.world == null || mc.player == null) return;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof CreeperEntity creeper)) continue;
            if (creeper.getFuseSpeed() > 0 && mc.player.squaredDistanceTo(creeper) <= range.get() * range.get()) {
                Attack.attack(creeper, true, 10, true);
            }
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (mode.get() == KillMode.OnExplode) return;
        if (mc.player == null) return;

        if (event.entity instanceof CreeperEntity && mc.player.squaredDistanceTo(event.entity) <= range.get() * range.get()) {
            Attack.attack(event.entity, true, 10, true);
        }
    }
}
