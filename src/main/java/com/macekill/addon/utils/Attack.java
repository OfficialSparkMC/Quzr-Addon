package com.macekill.addon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.phys.Vec3;

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
        if (MeteorClient.mc.player == null || MeteorClient.mc.getConnection() == null) return;

        Vec3 originalPos = MeteorClient.mc.player.position();
        Vec3 targetPos = target.position().add(0, 0.2, 0);

        List<Vec3> positions = List.of(
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
        MeteorClient.mc.getConnection().send(
            new ServerboundAttackPacket(target.getId()));
    }

    private static void equipMace() {
        if (isHoldingMace()) return;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = MeteorClient.mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.MACE) {
                InvUtils.swap(i, false);
                return;
            }
        }
    }

    private static boolean isHoldingMace() {
        if (MeteorClient.mc.player == null) return false;
        return MeteorClient.mc.player.getMainHandItem().getItem() == Items.MACE
            || MeteorClient.mc.player.getOffhandItem().getItem() == Items.MACE;
    }
}
