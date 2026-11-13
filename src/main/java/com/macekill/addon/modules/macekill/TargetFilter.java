package com.macekill.addon.modules.macekill;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;

public record TargetFilter(
    boolean players,
    boolean hostiles,
    boolean animals,
    boolean others
) {
    public boolean accepts(LivingEntity entity) {
        if (entity instanceof PlayerEntity) return players;
        if (entity instanceof HostileEntity) return hostiles;
        if (entity instanceof AnimalEntity) return animals;
        return others;
    }
}
