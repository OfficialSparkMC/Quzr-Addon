package com.macekill.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.text.Text;

/**
 * 创造模式物品工具 (照搬 Wurst InventoryUtils.setCreativeStack 逻辑)
 * 解决"幽灵物品"问题: 通过 CreativeInventoryActionC2SPacket 通知服务端
 */
public class CreativeGiveUtil {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /**
     * 把物品放进玩家背包
     * 1. 找第一个空槽
     * 2. 本地 inventory.setStack(slot, stack)
     * 3. 发送 CreativeInventoryActionC2SPacket(networkSlot, stack) 给服务端
     *
     * @return true 成功, false 背包已满
     */
    public static boolean give(ItemStack stack) {
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        int slot = mc.player.getInventory().getEmptySlot();
        if (slot < 0) {
            if (mc.player != null) {
                mc.player.sendMessage(Text.of("§c[Qazr] Inventory full, cannot give item"), false);
            }
            return false;
        }
        mc.player.getInventory().setStack(slot, stack);
        int networkSlot = slot < 9 ? slot + 36 : slot;
        mc.getNetworkHandler().sendPacket(new CreativeInventoryActionC2SPacket(networkSlot, stack));
        return true;
    }

    /** 警告消息 (只在内容变化时显示一次) */
    private static String lastError = "";
    public static void warn(String msg) {
        if (mc.player != null && !msg.equals(lastError)) {
            lastError = msg;
            mc.player.sendMessage(Text.of("§c[Qazr] " + msg), false);
        }
    }

    public static void info(String msg) {
        if (mc.player != null)
            mc.player.sendMessage(Text.of("§a[Qazr] " + msg), true);
    }

    public static void resetError() { lastError = ""; }
}
