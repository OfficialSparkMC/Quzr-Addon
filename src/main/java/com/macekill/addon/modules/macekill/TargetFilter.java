package com.macekill.addon.modules.macekill;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;

public record TargetFilter(
    boolean players,
    boolean hostiles,
    boolean animals,
    boolean others
) {
    public boolean accepts(LivingEntity entity) {
        if (entity instanceof Player) return players;
        if (entity instanceof Monster) return hostiles;
        if (entity instanceof Animal) return animals;
        return others;
    }
}
