package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

public class MaceDMG extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> fakeHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("伪造高度")
        .description("MaceDMG发送的高空位置高度(sqrt值,如√500≈22.36)")
        .defaultValue(22.36)
        .min(1.0).max(50.0).sliderMax(35.0)
        .build()
    );

    private final Setting<Integer> attackInterval = sgGeneral.add(new IntSetting.Builder()
        .name("攻击间隔")
        .description("攻击间隔(毫秒, 最小25ms)")
        .defaultValue(55)
        .min(25).max(2000).sliderMax(500)
        .build()
    );

    private final Setting<Boolean> globalDetection = sgGeneral.add(new BoolSetting.Builder()
        .name("全局攻击检测")
        .description("ON:拦截所有攻击事件自动触发 | OFF:仅玩家手动左键攻击时触发")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("自动切换重锤")
        .description("背包中有重锤时自动切换到主手")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> normalPackets = sgGeneral.add(new IntSetting.Builder()
        .name("正常包数量")
        .description("高空包之前发送的正常位置包数量(参考Wurst:4)")
        .defaultValue(4)
        .min(1).max(10).sliderMax(8)
        .build()
    );

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("聊天信息")
        .description("在聊天栏显示触发信息")
        .defaultValue(false)
        .build()
    );

    // ---- 内部状态 ----
    private long lastAttackTime;
    private float lastCooldown;

    public MaceDMG() {
        super(MaceKillAddon.CATEGORY, "平地重锤",
            "手持重锤攻击时伪造高度打出满伤害.\n" +
            "支持全局攻击检测(自动劫持攻击事件)或手动触发.");
    }

    @Override
    public void onActivate() {
        lastAttackTime = 0;
        lastCooldown = 0;
        if (chatInfo.get()) info("§a平地重锤已启动! 伪造高度=" + String.format("%.1f", fakeHeight.get()) + " 全局检测=" + (globalDetection.get() ? "ON" : "OFF"));
    }

    @Override
    public void onDeactivate() {
        if (chatInfo.get()) info("§c平地重锤已关闭!");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;

        if (player == null || mc.world == null) return;
        if (mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) return;

        // 自动切换重锤
        if (autoSwitch.get() && !isHoldingMace(player)) {
            switchToMace(mc);
        }

        if (!isHoldingMace(player)) return;

        // 攻击检测
        boolean shouldAttack = false;

        if (globalDetection.get()) {
            // 全局模式: 检测攻击冷却是否刚刚重置(任何来源的攻击)
            float currentCooldown = player.getAttackCooldownProgress(0);
            if (lastCooldown > 0.5f && currentCooldown < 0.01f) {
                shouldAttack = true;
            }
            lastCooldown = currentCooldown;
        } else {
            // 手动模式: 仅当玩家按下攻击键
            if (mc.options.attackKey.wasPressed()) {
                shouldAttack = true;
            }
        }

        if (!shouldAttack) return;

        // 频率控制(毫秒间隔)
        long now = System.currentTimeMillis();
        if (lastAttackTime == 0) lastAttackTime = now;
        long elapsed = now - lastAttackTime;
        if (elapsed < attackInterval.get()) return;
        lastAttackTime = now;

        performMaceDMG(mc);
    }

    private void performMaceDMG(MinecraftClient mc) {
        PlayerEntity player = mc.player;
        double height = fakeHeight.get();

        // 5包攻击序列: 4正常(onGround=true) + 1高空(onGround=false) + fallDistance归零
        for (int i = 0; i < normalPackets.get(); i++) {
            sendFakeY(mc, 0);
        }
        sendFakeY_air(mc, Math.sqrt(height));
        mc.player.fallDistance = 0;

        if (chatInfo.get()) {
            info("§b触发MaceDMG | height=" + String.format("%.1f", Math.sqrt(height)));
        }
    }

    private void sendFakeY(MinecraftClient mc, double offset) {
        mc.player.networkHandler.sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(),
                true, mc.player.horizontalCollision
            )
        );
    }

    /** 高空包: onGround=false, 让服务器记录坠落距离用于重锤伤害计算 */
    private void sendFakeY_air(MinecraftClient mc, double offset) {
        mc.player.networkHandler.sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(),
                false, mc.player.horizontalCollision
            )
        );
    }

    private boolean isHoldingMace(PlayerEntity player) {
        return player.getMainHandStack().isOf(Items.MACE)
            || player.getOffHandStack().isOf(Items.MACE);
    }

    private void switchToMace(MinecraftClient mc) {
        PlayerEntity player = mc.player;
        if (player.getMainHandStack().isOf(Items.MACE)) return;
        if (player.getOffHandStack().isOf(Items.MACE)) return;

        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(Items.MACE)) {
                setSelectedSlot(player, i);
                return;
            }
        }

        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getStack(i).isOf(Items.MACE)) {
                int syncId = player.playerScreenHandler.syncId;
                int selected = getSelectedSlot(player);
                mc.interactionManager.clickSlot(syncId, i, selected, net.minecraft.screen.slot.SlotActionType.SWAP, player);
                return;
            }
        }
    }

    private void setSelectedSlot(PlayerEntity player, int slot) {
        try {
            java.lang.reflect.Field f = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            f.setInt(player.getInventory(), slot);
        } catch (Exception ignored) {}
    }

    private int getSelectedSlot(PlayerEntity player) {
        try {
            java.lang.reflect.Field f = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            return f.getInt(player.getInventory());
        } catch (Exception ignored) {}
        return 0;
    }

    private void info(String msg) {
        ChatUtils.sendMsg(Text.literal("§8[§b平地重锤§8] §f" + msg));
    }
}
