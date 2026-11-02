package com.macekill.addon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Teleport-attack helper (ported from brew-addon's {@code Attack}).
 */
public final class Attack {
    private Attack() {}

    /**
     * Teleports to the target, optionally adds a mace fall, attacks, and teleports back.
     *
     * @param target        entity to attack
     * @param useMace       add the up-then-down mace path when a mace is held
     * @param attackHeight  height used for the mace fall
     * @param autoEquipMace put a hotbar mace in hand first
     */
    public static void attack(Entity target, boolean useMace, int attackHeight, boolean autoEquipMace) {
        if (target == null || !target.isAlive()) return;
        if (MeteorClient.mc.player == null || MeteorClient.mc.getNetworkHandler() == null) return;

        Vec3d originalPos = MeteorClient.mc.player.getEntityPos();
        Vec3d targetPos = target.getEntityPos().add(0, 0.2, 0);

        List<Vec3d> positions = List.of(
            targetPos,
            targetPos.add(0, attackHeight, 0),
            targetPos,
            originalPos
        );

        if (autoEquipMace) {
            equipMace();
        }

        // brew-addon queued these through CompletableFuture.runAsync, which mutated the client
        // player off-thread. Packets and client position changes run in order on the client
        // thread here; the resulting packet sequence is identical.
        if (useMace && isHoldingMace()) {
            Movement.execute(positions, 0, 2, true, false);
            sendAttack(target);
            Movement.execute(positions, 3, true, false);
        } else {
            Movement.execute(positions, 0, true, false);
            sendAttack(target);
            Movement.execute(positions, 3, true, false);
        }
    }

    private static void sendAttack(Entity target) {
        MeteorClient.mc.getNetworkHandler().sendPacket(
            PlayerInteractEntityC2SPacket.attack(target, MeteorClient.mc.player.isSneaking()));
    }

    private static void equipMace() {
        if (isHoldingMace()) return;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = MeteorClient.mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.MACE) {
                InvUtils.swap(i, false);
                return;
            }
        }
    }

    private static boolean isHoldingMace() {
        if (MeteorClient.mc.player == null) return false;
        return MeteorClient.mc.player.getMainHandStack().getItem() == Items.MACE
            || MeteorClient.mc.player.getOffHandStack().getItem() == Items.MACE;
    }
}
