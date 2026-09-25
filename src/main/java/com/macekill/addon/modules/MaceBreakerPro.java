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

/**
 * 盾牌破碎 - 攻击时自动斧+重锤连击破盾
 * 来自 InvincibleMachineGun 的 MaceBreakerPro
 */
public class MaceBreakerPro extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onlyOnShield = sgGeneral.add(new BoolSetting.Builder()
            .name("Only Blocking").description("Only trigger the combo when the target is blocking")
            .defaultValue(true).build()
    );

    private final Setting<Boolean> swordTrigger = sgGeneral.add(new BoolSetting.Builder()
            .name("Sword Trigger").description("Also trigger the combo when holding a sword").defaultValue(false).build()
    );

    private final Setting<Boolean> axeTrigger = sgGeneral.add(new BoolSetting.Builder()
            .name("Axe Trigger").description("Also trigger the combo when holding an axe").defaultValue(true).build()
    );

    private final Setting<Boolean> maceTrigger = sgGeneral.add(new BoolSetting.Builder()
            .name("Mace Trigger").description("Also trigger the combo when holding a mace").defaultValue(false).build()
    );

    private final Setting<Boolean> autoReturn = sgGeneral.add(new BoolSetting.Builder()
            .name("Auto Return").description("Switch back to the original item after attacking").defaultValue(true).build()
    );

    public MaceBreakerPro() {
        super(MaceKillAddon.CATEGORY, "MaceBreakerPro", "Auto axe+mace combo to break shields on attack");
    }

    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.entity instanceof LivingEntity target)) return;

        // 仅持盾模式：跳过未举盾的目标
        if (onlyOnShield.get() && !target.isBlocking()) return;

        // 检查当前武器是否匹配触发条件 (1.21.11 has no SwordItem class — swords are plain Items, match by registry id)
        ItemStack handItem = mc.player.getMainHandStack();
        String swordPath = net.minecraft.registry.Registries.ITEM.getId(handItem.getItem()).getPath();
        boolean isSword = swordPath.contains("sword");
        boolean isAxe = handItem.getItem() instanceof net.minecraft.item.AxeItem;
        boolean isMace = handItem.getItem() instanceof net.minecraft.item.MaceItem;

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
            int mslot = mace.slot();
            int src = -1;
            if (mslot > 8) {
                // Mace not in hotbar -> move it into the selected slot.
                InvUtils.move().from(mslot).to(originalSlot);
                src = mslot;
                mslot = originalSlot;
            }
            switchTo(mslot);
            sendAttackPacket(target);
            if (src >= 0) {
                InvUtils.move().from(mslot).to(src);
            }
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
            if (stack.getItem() instanceof net.minecraft.item.AxeItem) return new FindItemResult(i, stack.getCount());
        }
        return new FindItemResult(-1, 0);
    }

    private FindItemResult findMace() {
        return InvUtils.find(Items.MACE);
    }

    private void switchTo(int slot) {
        if (mc.player == null || mc.getNetworkHandler() == null || slot < 0 || slot > 8) return;
        setSelectedSlot(mc.player.getInventory(), slot);
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void sendAttackPacket(LivingEntity target) {
        mc.getNetworkHandler().sendPacket(
                PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
    }

    private int getSelectedSlot() {
        if (mc.player == null) return 0;
        return mc.player.getInventory().getSelectedSlot();
    }

    private void setSelectedSlot(PlayerInventory inv, int slot) {
        if (inv == null || slot < 0 || slot > 8) return;
        inv.setSelectedSlot(slot);
    }
}
