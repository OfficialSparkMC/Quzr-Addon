package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;

import java.lang.reflect.Field;

/**
 * 盾牌破碎 - 攻击时自动斧+重锤连击破盾
 * 来自 InvincibleMachineGun 的 MaceBreakerPro
 */
public class MaceBreakerPro extends Module {
    private static Field selectedSlotField;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onlyOnShield = sgGeneral.add(new BoolSetting.Builder()
            .name("仅持盾").description("仅在目标举盾时触发连击")
            .defaultValue(true).build()
    );

    private final Setting<Boolean> swordTrigger = sgGeneral.add(new BoolSetting.Builder()
            .name("剑触发").description("手持剑时也可触发连击").defaultValue(false).build()
    );

    private final Setting<Boolean> axeTrigger = sgGeneral.add(new BoolSetting.Builder()
            .name("斧触发").description("手持斧时也可触发连击").defaultValue(true).build()
    );

    private final Setting<Boolean> maceTrigger = sgGeneral.add(new BoolSetting.Builder()
            .name("重锤触发").description("手持重锤时也可触发连击").defaultValue(false).build()
    );

    private final Setting<Boolean> autoReturn = sgGeneral.add(new BoolSetting.Builder()
            .name("自动返回").description("攻击后切换回原手持物品").defaultValue(true).build()
    );

    public MaceBreakerPro() {
        super(MaceKillAddon.CATEGORY, "MaceBreakerPro", "攻击时自动斧+重锤连击破盾");
    }

    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.entity instanceof LivingEntity target)) return;

        // 仅持盾模式：跳过未举盾的目标
        if (onlyOnShield.get() && !target.isBlocking()) return;

        // 检查当前武器是否匹配触发条件
        ItemStack handItem = mc.player.getMainHandStack();
        String weaponName = handItem.getItem().toString().toLowerCase();
        boolean isSword = weaponName.contains("sword");
        boolean isAxe = weaponName.contains("_axe") || weaponName.contains("axe");
        boolean isMace = weaponName.contains("mace");

        if (!(isSword && swordTrigger.get())
                && !(isAxe && axeTrigger.get())
                && !(isMace && maceTrigger.get())) {
            return;
        }

        // 取消原始攻击事件
        event.setCancelled(true);

        // 找到斧和重锤
        FindItemResult axe = findAxe();
        FindItemResult mace = findMace();
        int originalSlot = getSelectedSlot();

        // 如果有斧：切换斧 → 攻击
        if (axe.found()) {
            switchTo(axe.slot());
            sendAttackPacket(target);
        }

        // 如果有重锤：切换重锤 → 攻击
        if (mace.found()) {
            switchTo(mace.slot());
            sendAttackPacket(target);
        }

        // 恢复原位
        if (autoReturn.get()) {
            switchTo(originalSlot);
        }

        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private FindItemResult findAxe() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            String name = stack.getItem().toString().toLowerCase();
            if (name.contains("_axe")) return new FindItemResult(i, stack.getCount());
        }
        return new FindItemResult(-1, 0);
    }

    private FindItemResult findMace() {
        return InvUtils.findInHotbar(Items.MACE);
    }

    private void switchTo(int slot) {
        setSelectedSlot(mc.player.getInventory(), slot);
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void sendAttackPacket(LivingEntity target) {
        mc.getNetworkHandler().sendPacket(
                PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
    }

    private int getSelectedSlot() {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            return selectedSlotField.getInt(mc.player.getInventory());
        } catch (Exception e) {
            return 0;
        }
    }

    private void setSelectedSlot(PlayerInventory inv, int slot) {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            selectedSlotField.setInt(inv, slot);
        } catch (Exception ignored) {}
    }
}
