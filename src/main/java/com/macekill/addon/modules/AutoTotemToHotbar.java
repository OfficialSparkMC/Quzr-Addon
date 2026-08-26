package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;

public class AutoTotemToHotbar extends Module {

    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Integer> slot = sgMain.add(new IntSetting.Builder()
            .name("Slot").description("Hotbar slot to move the totem to")
            .defaultValue(7).min(0).max(8).build());

    public AutoTotemToHotbar() {
        super(MaceKillAddon.CATEGORY, "AutoTotemToHotbar", "Moves a totem to a hotbar slot");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        // 检查副手是否已有图腾
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(Items.TOTEM_OF_UNDYING)) return;

        // 先在快捷栏找图腾
        FindItemResult hotbarResult = InvUtils.findInHotbar(Items.TOTEM_OF_UNDYING);
        if (hotbarResult.found()) {
            // 图腾已在快捷栏中，移动到目标槽位
            if (hotbarResult.slot() != slot.get()) {
                InvUtils.move().from(hotbarResult.slot()).to(slot.get());
            }
            return;
        }

        // 在背包中找图腾
        FindItemResult invResult = InvUtils.find(Items.TOTEM_OF_UNDYING);
        if (invResult.found()) {
            // 移动到目标槽位
            InvUtils.move().from(invResult.slot()).to(slot.get());
        }
    }
}
