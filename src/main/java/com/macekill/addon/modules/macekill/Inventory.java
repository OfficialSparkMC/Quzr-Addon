package com.macekill.addon.modules.macekill;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;

public final class Inventory {
    // When the mace was moved out of the main inventory into the hotbar, this is its
    // original slot so we can move it back after the attack. -1 means no swap happened.
    private static int lastMaceSwapSlot = -1;

    private Inventory() {}

    public static int switchToMace(MinecraftClient mc) {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        int cur = getSelectedSlot(inv);
        lastMaceSwapSlot = -1;

        if (inv.getStack(cur).getItem() == Items.MACE) return cur;

        // Mace already in the hotbar -> just select it.
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() != Items.MACE) continue;
            if (i != cur) {
                setSelectedSlot(inv, i);
                Movement.sendSlotPacket(mc, i);
            }
            return cur;
        }

        // Mace somewhere else in the inventory -> move it into the selected hotbar slot.
        FindItemResult mace = InvUtils.find(Items.MACE);
        if (mace.found()) {
            int src = mace.slot();
            InvUtils.move().from(src).to(cur);
            lastMaceSwapSlot = src;
        }
        return cur;
    }

    public static void switchBack(MinecraftClient mc, int slot) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        if (lastMaceSwapSlot >= 0) {
            // Move the mace back to where it came from (swaps the original item back).
            InvUtils.move().from(slot).to(lastMaceSwapSlot);
            lastMaceSwapSlot = -1;
            return;
        }
        setSelectedSlot(inv, slot);
        Movement.sendSlotPacket(mc, slot);
    }

    public static int findTotemSlot(MinecraftClient mc) {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() != Items.TOTEM_OF_UNDYING) continue;
            return i;
        }
        return -1;
    }

    public static void ensureTotem(MinecraftClient mc) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        PlayerInventory inv = mc.player.getInventory();
        int cur = getSelectedSlot(inv);
        if (inv.getStack(cur).getItem() == Items.TOTEM_OF_UNDYING) return;
        int t = findTotemSlot(mc);
        if (t != -1) {
            setSelectedSlot(inv, t);
            Movement.sendSlotPacket(mc, t);
        }
    }

    public static int getSelectedSlot(MinecraftClient mc) {
        if (mc.player == null) return 0;
        return getSelectedSlot(mc.player.getInventory());
    }

    // NOTE: public PlayerInventory.get/setSelectedSlot() API — never reflection.
    // The old getDeclaredField("selectedSlot") lookup used the Yarn name, which does not
    // exist at runtime (intermediary mappings), so it always failed in production: reads
    // returned 0 and writes were silently dropped, breaking slot revert / silent swap.
    public static int getSelectedSlot(PlayerInventory inv) {
        if (inv == null) return 0;
        return inv.getSelectedSlot();
    }

    public static void setSelectedSlot(PlayerInventory inv, int slot) {
        if (inv == null || slot < 0 || slot > 8) return;
        inv.setSelectedSlot(slot);
    }
}
