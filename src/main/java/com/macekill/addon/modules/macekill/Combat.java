package com.macekill.addon.modules.macekill;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
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

                Movement.doTpTo(mc, vclipPos, config.moveDistance(), config.syncClientPos());
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
            if (!vclipSafe(mc, mutable)) continue;
            mutable.set(bx, cy + 1, bz);
            if (!vclipSafe(mc, mutable)) continue;
            return new BlockPos(bx, cy, bz);
        }
        return base;
    }

    // A vclip destination must be an air block (or leaves: we can teleport straight
    // through foliage to reach clear sky above a tree canopy), with no fluid/cobweb.
    private static boolean vclipSafe(MinecraftClient mc, BlockPos pos) {
        if (mc.world == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() && state.getFluidState().isEmpty() && !state.isOf(Blocks.COBWEB)) {
            return true;
        }
        return state.getBlock() instanceof LeavesBlock;
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

    // Builds the totem-bypass height list: drain heights first, then (totemCount + 1)
    // kill hits at STRICTLY INCREASING heights. Each successive mace hit deals strictly
    // more damage, which bypasses the server's hurtResistantTime invulnerability check
    // (amount <= lastDamage is ignored), letting every held totem pop in a single tick.
    public static final int MAX_TOTEM_HITS = 24;
    public static List<String> totemBypassHeights(List<String> drainHeights, int totemCount, int baseHeight, int step) {
        List<String> all = new ArrayList<>(drainHeights);
        int maxDrain = 0;
        for (String s : drainHeights) {
            try {
                int v = Integer.parseInt(s.trim());
                if (v > maxDrain) maxDrain = v;
            } catch (NumberFormatException ignored) {
            }
        }
        int start = Math.max(baseHeight, maxDrain + step);
        int kills = totemCount + 1;
        for (int i = 0; i < kills; i++) {
            all.add(String.valueOf(start + i * step));
        }
        return all;
    }
}
