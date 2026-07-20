package com.macekill.addon.modules.macekill;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class Combat {
    private Combat() {}

    public static boolean executeAttack(MinecraftClient mc, Config config,
                                        LivingEntity target, Vec3d targetPos) {
        if (target == null || mc.getNetworkHandler() == null) {
            return true;
        }

        List<String> rawHeights;
        boolean armorDestroyed;
        if (config.enableArmorDestroy() && needsArmorDestroy(target, config.ignoreArmorValue())) {
            rawHeights = config.destroyHeights();
            armorDestroyed = false;
        } else {
            rawHeights = config.killHeights();
            armorDestroyed = true;
        }

        List<Double> heights = parseHeights(rawHeights);
        if (heights.isEmpty()) {
            heights.add(20.0);
        }

        int oldSlot = Inventory.switchToMace(mc);
        if (oldSlot == -1) {
            return armorDestroyed;
        }

        try {
            Vec3d basePos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

            for (double h : heights) {
                BlockPos vclipHole = findVclipHole(mc,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), h);
                Vec3d vclipPos = Vec3d.ofBottomCenter(vclipHole);

                Movement.doTpTo(mc, vclipPos, config.moveDistance(), config.kehd());
                Movement.sendMovePacket(mc, targetPos.x, targetPos.y + 0.5, targetPos.z);
                Movement.sendMovePacket(mc, basePos.x, basePos.y, basePos.z);

                if (config.swingHand()) {
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                Movement.attackEntity(mc, target);
            }
        } finally {
            if (config.autoTotem()) {
                int totemSlot = Inventory.findTotemSlot(mc);
                if (totemSlot != -1) {
                    Inventory.setSelectedSlot(mc.player.getInventory(), totemSlot);
                    Movement.sendSlotPacket(mc, totemSlot);
                } else {
                    Inventory.switchBack(mc, oldSlot);
                }
            } else {
                Inventory.switchBack(mc, oldSlot);
            }
        }
        return armorDestroyed;
    }

    public static BlockPos findVclipHole(MinecraftClient mc,
                                         double x, double y, double z, double vclip) {
        BlockPos base = BlockPos.ofFloored(x, y, z);
        if (vclip <= 0.0) {
            return base;
        }

        int bx = base.getX();
        int by = base.getY();
        int bz = base.getZ();
        int targetY = by + (int) vclip;
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int cy = targetY; cy >= by; cy--) {
            mutable.set(bx, cy, bz);
            if (!Targeting.isSafeBlock(mc, mutable)) continue;
            mutable.set(bx, cy + 1, bz);
            if (!Targeting.isSafeBlock(mc, mutable)) continue;
            return new BlockPos(bx, cy, bz);
        }
        return base;
    }

    public static boolean needsArmorDestroy(LivingEntity entity, int ignoreArmorValue) {
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        int count = 0;
        for (int i = 36; i <= 39; i++) {
            if (player.getInventory().getStack(i).isEmpty()) continue;
            count++;
        }
        return count > ignoreArmorValue;
    }

    public static List<Double> parseHeights(List<String> raw) {
        List<Double> list = new ArrayList<>();
        for (String s : raw) {
            try {
                double v = Double.parseDouble(s.trim());
                if (v > 0.0) {
                    list.add(v);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return list;
    }
}
