package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 潜影盒刷取 - Qazr 风格快速循环放置/挖掘潜影盒
 *
 * 逻辑：
 * 1. 激活时记录准星指向方块的上方作为操作位置
 * 2. 每 tick 高速检测：
 *    - 空气/可替换 → 切潜影盒 → 放置
 *    - 潜影盒方块 → 切镐子 → 瞬间挖掘
 * 3. 镐子优先选择耐久高于阈值的，潜影盒循环替换到槽位
 * 4. 槽位切换纯反射（Qazr 风格），无 UpdateSelectedSlot 包
 */
public class AutoShulkerBox extends Module {

    /* ==================== 潜影盒全17种变体 ==================== */



    /* ==================== 设置 ==================== */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
            .name("Shulker Slot")
            .description("Hotbar slot of the shulker box (0-8)")
            .defaultValue(0).min(0).max(8).build()
    );

    private final Setting<Integer> durabilityThreshold = sgGeneral.add(new IntSetting.Builder()
            .name("Pickaxe Durability Threshold")
            .description("Only use pickaxes with durability above this value")
            .defaultValue(1).min(1).max(10000).sliderRange(1, 5000).build()
    );

    private final Setting<Boolean> cycleBoxes = sgGeneral.add(new BoolSetting.Builder()
            .name("Cycle Shulkers")
            .description("Auto-swap the next shulker box into the slot after breaking one")
            .defaultValue(true).build()
    );

    /* ==================== 状态 ==================== */
    private BlockPos targetPos;
    private int lastPickSlot = -1;
    private boolean wasPlacing;

    public AutoShulkerBox() {
        super(MaceKillAddon.CATEGORY, "AutoShulkerBox", "Shulker box farmer");
    }

    @Override
    public void onActivate() {
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            targetPos = ((BlockHitResult) mc.hitResult).getBlockPos().above();
        }
        lastPickSlot = -1;
        wasPlacing = false;
    }

    @Override
    public void onDeactivate() {
        targetPos = null;
        lastPickSlot = -1;
        mc.gameMode.stopDestroyBlock();
        mc.options.keyUse.setDown(false);
    }

    /* ==================== Tick 高速循环 ==================== */

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (targetPos == null) return;

        BlockState state = mc.level.getBlockState(targetPos);

        if (state.isAir() || state.canBeReplaced()) {
            // —— 放置潜影盒 ——
            ensureShulkerBoxInHand();
            placeBlock();
            wasPlacing = true;
        } else if (isShulkerBoxBlock(state)) {
            // —— 挖掘潜影盒 ——
            if (wasPlacing) {
                // 刚放置完，下一个 tick 再挖（给服务端一帧确认）
                wasPlacing = false;
                return;
            }
            ensurePickaxeInHand();
            breakBlock();
            if (cycleBoxes.get()) cycleShulkerBox();
            wasPlacing = false;
        }
    }

    // ==================== 方块操作 ====================

    /**
     * 放置：在 targetPos 放置潜影盒
     * 面向下方方块顶部，发送 ServerboundUseItemOnPacket
     */
    private void placeBlock() {
        if (mc.getConnection() == null) return;
        Direction face = Direction.UP;
        BlockPos against = targetPos.below();
        Vec3 hitVec = new Vec3(against.getX() + 0.5, against.getY() + 1.0, against.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, against, false);

        mc.getConnection().send(
                new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hitResult, 0));
    }

    /**
     * 挖掘：START_DESTROY_BLOCK + STOP_DESTROY_BLOCK 同一 tick 瞬间破坏
     * Qazr 风格：额外发送 player.swingHand
     */
    private void breakBlock() {
        if (mc.getConnection() == null) return;
        BlockPos pos = targetPos;
        Direction face = bestFace(pos);

        mc.getConnection().send(
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, 0));
        mc.getConnection().send(
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, 0));
        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), true);
    }

    /** 计算最佳攻击面（距离眼睛最近的方块面） */
    private Direction bestFace(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 dir = center.subtract(eye).normalize();

        Direction best = Direction.UP;
        double bestDot = -2;
        for (Direction d : Direction.values()) {
            double dot = dir.dot(Vec3.atLowerCornerOf(d.getUnitVec3i()));
            if (dot > bestDot) { bestDot = dot; best = d; }
        }
        return best;
    }

    // ==================== 物品切换（Qazr 风格：纯反射，不发 UpdateSelectedSlot 包） ====================

    /** 确保主手持有潜影盒：优先配置槽位，否则搜快捷栏，最后从背包移动 */
    private void ensureShulkerBoxInHand() {
        if (isShulkerBox(mc.player.getMainHandItem())) return;

        int slot = hotbarSlot.get();
        if (isShulkerBox(mc.player.getInventory().getItem(slot))) {
            setSelectedSlot(slot);
            return;
        }

        // 搜快捷栏其他槽位
        for (int i = 0; i < 9; i++) {
            if (isShulkerBox(mc.player.getInventory().getItem(i))) {
                setSelectedSlot(i);
                return;
            }
        }

        // 从背包(9-35)移动到配置槽位
        for (int i = 9; i < 36; i++) {
            if (isShulkerBox(mc.player.getInventory().getItem(i))) {
                swapSlots(i, slot);
                setSelectedSlot(slot);
                return;
            }
        }
    }

    /** 确保主手持有镐子：搜快捷栏第一个耐久 >= 阈值的镐子 */
    private void ensurePickaxeInHand() {
        if (isPickaxe(mc.player.getMainHandItem().getItem())
                && getDurability(mc.player.getMainHandItem()) >= durabilityThreshold.get()) {
            return;
        }

        int threshold = durabilityThreshold.get();

        // 复用上次位置
        if (lastPickSlot >= 0 && lastPickSlot < 9) {
            ItemStack s = mc.player.getInventory().getItem(lastPickSlot);
            if (isPickaxe(s.getItem()) && getDurability(s) >= threshold) {
                setSelectedSlot(lastPickSlot);
                return;
            }
        }

        // 遍历快捷栏
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (isPickaxe(s.getItem()) && getDurability(s) >= threshold) {
                lastPickSlot = i;
                setSelectedSlot(i);
                return;
            }
        }
    }

    /**
     * 循环潜影盒：将背包中的下一个潜影盒移到配置槽位
     * 优先主背包(9-35)，其次快捷栏其他槽位
     */
    private void cycleShulkerBox() {
        int slot = hotbarSlot.get();
        Inventory inv = mc.player.getInventory();

        if (isShulkerBox(inv.getItem(slot))) return;

        for (int i = 9; i < 36; i++) {
            if (isShulkerBox(inv.getItem(i))) {
                swapSlots(i, slot);
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != slot && isShulkerBox(inv.getItem(i))) {
                swapSlots(i, slot);
                return;
            }
        }
    }

    /**
     * 背包物品交换：ClickSlotC2SPacket SWAP
     */
    private void swapSlots(int from, int to) {
        if (mc.getConnection() == null || mc.player == null) return;
        try {
            mc.gameMode.handleContainerInput(
                    mc.player.inventoryMenu.containerId,
                    from, to, ContainerInput.SWAP, mc.player);
        } catch (Exception ignored) {}
    }

    // ==================== 快捷栏切换（带服务端同步，避免幽灵方块） ====================

    private int getSelectedSlot() {
        if (mc.player == null) return -1;
        return mc.player.getInventory().getSelectedSlot();
    }

    private void setSelectedSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
        // Keep the server in sync, otherwise place/mine uses the wrong held item (ghost blocks / kick).
        if (mc.getConnection() != null) {
            mc.getConnection().send(
                new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }
    }

    // ==================== 判定 ====================

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isShulkerBoxBlock(BlockState state) {
        return state.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isPickaxe(Item item) {
        return item.getDefaultInstance().is(ItemTags.PICKAXES);
    }

    private int getDurability(ItemStack stack) {
        if (!stack.isDamageableItem()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    @Override
    public String getInfoString() {
        if (targetPos == null) return "No target";
        BlockState s = mc.level != null ? mc.level.getBlockState(targetPos) : null;
        if (s == null) return targetPos.toShortString();
        return s.isAir() ? "Placing" : "Mining";
    }
}
