package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
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
    private static final Set<Block> SHULKER_BOX_BLOCKS = Set.of(
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX
    );

    private static final Set<Item> SHULKER_BOX_ITEMS = Set.of(
            Items.SHULKER_BOX,
            Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX,
            Items.MAGENTA_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX,
            Items.PINK_SHULKER_BOX, Items.GRAY_SHULKER_BOX,
            Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX,
            Items.PURPLE_SHULKER_BOX, Items.BLUE_SHULKER_BOX,
            Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX,
            Items.RED_SHULKER_BOX, Items.BLACK_SHULKER_BOX
    );

    private static final Set<Item> PICKAXES = Set.of(
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
            Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE
    );

    /* ==================== 反射 ==================== */
    private static Field selectedSlotField;

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
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            targetPos = ((BlockHitResult) mc.crosshairTarget).getBlockPos().up();
        }
        lastPickSlot = -1;
        wasPlacing = false;
    }

    @Override
    public void onDeactivate() {
        targetPos = null;
        lastPickSlot = -1;
        mc.interactionManager.cancelBlockBreaking();
        mc.options.useKey.setPressed(false);
    }

    /* ==================== Tick 高速循环 ==================== */

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (targetPos == null) return;

        BlockState state = mc.world.getBlockState(targetPos);

        if (state.isAir() || state.isReplaceable()) {
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
     * 面向下方方块顶部，发送 PlayerInteractBlockC2SPacket
     */
    private void placeBlock() {
        if (mc.getNetworkHandler() == null) return;
        Direction face = Direction.UP;
        BlockPos against = targetPos.down();
        Vec3d hitVec = new Vec3d(against.getX() + 0.5, against.getY() + 1.0, against.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, against, false);

        mc.getNetworkHandler().sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
    }

    /**
     * 挖掘：START_DESTROY_BLOCK + STOP_DESTROY_BLOCK 同一 tick 瞬间破坏
     * Qazr 风格：额外发送 player.swingHand
     */
    private void breakBlock() {
        if (mc.getNetworkHandler() == null) return;
        BlockPos pos = targetPos;
        Direction face = bestFace(pos);

        mc.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, face, 0));
        mc.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, face, 0));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** 计算最佳攻击面（距离眼睛最近的方块面） */
    private Direction bestFace(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d dir = center.subtract(eye).normalize();

        Direction best = Direction.UP;
        double bestDot = -2;
        for (Direction d : Direction.values()) {
            double dot = dir.dotProduct(Vec3d.of(d.getVector()));
            if (dot > bestDot) { bestDot = dot; best = d; }
        }
        return best;
    }

    // ==================== 物品切换（Qazr 风格：纯反射，不发 UpdateSelectedSlot 包） ====================

    /** 确保主手持有潜影盒：优先配置槽位，否则搜快捷栏，最后从背包移动 */
    private void ensureShulkerBoxInHand() {
        if (isShulkerBox(mc.player.getMainHandStack())) return;

        int slot = hotbarSlot.get();
        if (isShulkerBox(mc.player.getInventory().getStack(slot))) {
            setSelectedSlot(slot);
            return;
        }

        // 搜快捷栏其他槽位
        for (int i = 0; i < 9; i++) {
            if (isShulkerBox(mc.player.getInventory().getStack(i))) {
                setSelectedSlot(i);
                return;
            }
        }

        // 从背包(9-35)移动到配置槽位
        for (int i = 9; i < 36; i++) {
            if (isShulkerBox(mc.player.getInventory().getStack(i))) {
                swapSlots(i, slot);
                setSelectedSlot(slot);
                return;
            }
        }
    }

    /** 确保主手持有镐子：搜快捷栏第一个耐久 >= 阈值的镐子 */
    private void ensurePickaxeInHand() {
        if (isPickaxe(mc.player.getMainHandStack().getItem())
                && getDurability(mc.player.getMainHandStack()) >= durabilityThreshold.get()) {
            return;
        }

        int threshold = durabilityThreshold.get();

        // 复用上次位置
        if (lastPickSlot >= 0 && lastPickSlot < 9) {
            ItemStack s = mc.player.getInventory().getStack(lastPickSlot);
            if (isPickaxe(s.getItem()) && getDurability(s) >= threshold) {
                setSelectedSlot(lastPickSlot);
                return;
            }
        }

        // 遍历快捷栏
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
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
        PlayerInventory inv = mc.player.getInventory();

        if (isShulkerBox(inv.getStack(slot))) return;

        for (int i = 9; i < 36; i++) {
            if (isShulkerBox(inv.getStack(i))) {
                swapSlots(i, slot);
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != slot && isShulkerBox(inv.getStack(i))) {
                swapSlots(i, slot);
                return;
            }
        }
    }

    /**
     * 背包物品交换：ClickSlotC2SPacket SWAP
     */
    private void swapSlots(int from, int to) {
        if (mc.getNetworkHandler() == null || mc.player == null) return;
        try {
            mc.interactionManager.clickSlot(
                    mc.player.playerScreenHandler.syncId,
                    from, to, SlotActionType.SWAP, mc.player);
        } catch (Exception ignored) {}
    }

    // ==================== 反射（Qazr 风格：纯 setInt，无 UpdateSelectedSlot） ====================

    private int getSelectedSlot() {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            return selectedSlotField.getInt(mc.player.getInventory());
        } catch (Exception e) {
            return -1;
        }
    }

    private void setSelectedSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            selectedSlotField.setInt(mc.player.getInventory(), slot);
        } catch (Exception ignored) {}
        // Keep the server in sync, otherwise place/mine uses the wrong held item (ghost blocks / kick).
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
        }
    }

    // ==================== 判定 ====================

    private boolean isShulkerBox(ItemStack stack) {
        return SHULKER_BOX_ITEMS.contains(stack.getItem());
    }

    private boolean isShulkerBoxBlock(BlockState state) {
        return SHULKER_BOX_BLOCKS.contains(state.getBlock());
    }

    private boolean isPickaxe(Item item) {
        return PICKAXES.contains(item);
    }

    private int getDurability(ItemStack stack) {
        if (!stack.isDamageable()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamage();
    }

    @Override
    public String getInfoString() {
        if (targetPos == null) return "No target";
        BlockState s = mc.world != null ? mc.world.getBlockState(targetPos) : null;
        if (s == null) return targetPos.toShortString();
        return s.isAir() ? "Placing" : "Mining";
    }
}
