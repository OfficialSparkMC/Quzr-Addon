package com.macekill.addon.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.chat.Component;

/**
 * 创造模式物品工具 (照搬 Wurst InventoryUtils.setCreativeStack 逻辑)
 * 解决"幽灵物品"问题: 通过 ServerboundSetCreativeModeSlotPacket 通知服务端
 */
public class CreativeGiveUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    /**
     * 把物品放进玩家背包
     * 1. 找第一个空槽
     * 2. 本地 inventory.setItem(slot, stack)
     * 3. 发送 ServerboundSetCreativeModeSlotPacket(networkSlot, stack) 给服务端
     *
     * @return true 成功, false 背包已满
     */
    public static boolean give(ItemStack stack) {
        if (mc.player == null || mc.getConnection() == null) return false;
        int slot = mc.player.getInventory().getFreeSlot();
        if (slot < 0) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§c[Qazr] Inventory full, cannot give item"));
            }
            return false;
        }
        mc.player.getInventory().setItem(slot, stack);
        int networkSlot = slot < 9 ? slot + 36 : slot;
        mc.getConnection().send(new ServerboundSetCreativeModeSlotPacket(networkSlot, stack));
        return true;
    }

    /** 警告消息 (只在内容变化时显示一次) */
    private static String lastError = "";
    public static void warn(String msg) {
        if (mc.player != null && !msg.equals(lastError)) {
            lastError = msg;
            mc.player.sendSystemMessage(Component.literal("§c[Qazr] " + msg));
        }
    }

    public static void info(String msg) {
        if (mc.player != null)
            mc.player.sendSystemMessage(Component.literal("§a[Qazr] " + msg));
    }

    public static void resetError() { lastError = ""; }
}
