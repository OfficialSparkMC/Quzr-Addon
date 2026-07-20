package com.macekill.addon.modules.macekill;

import java.lang.reflect.Field;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;

public final class Inventory {
    private static Field selectedSlotField;

    private Inventory() {}

    public static int switchToMace(MinecraftClient mc) {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() != Items.MACE) continue;
            int cur = getSelectedSlot(inv);
            if (i != cur) {
                setSelectedSlot(inv, i);
                Movement.sendSlotPacket(mc, i);
            }
            return cur;
        }
        return -1;
    }

    public static void switchBack(MinecraftClient mc, int slot) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
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

    public static int getSelectedSlot(PlayerInventory inv) {
        try {
            ensureField();
            return selectedSlotField.getInt(inv);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setSelectedSlot(PlayerInventory inv, int slot) {
        try {
            ensureField();
            selectedSlotField.setInt(inv, slot);
        } catch (Exception ignored) {
        }
    }

    private static void ensureField() throws NoSuchFieldException {
        if (selectedSlotField == null) {
            selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
            selectedSlotField.setAccessible(true);
        }
    }
}
