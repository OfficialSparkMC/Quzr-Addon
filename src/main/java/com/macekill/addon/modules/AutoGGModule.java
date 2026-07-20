package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AutoGGModule extends Module {
    private static boolean skipChatModify;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> messageList = sgGeneral.add(new StringListSetting.Builder()
            .name("消息列表")
            .description("击杀后随机选择一条发送，{player} 将被替换为目标玩家名")
            .defaultValue("gg {player}", "ez {player}", "L + ratio {player}")
            .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("检测范围")
            .description("附近玩家的检测距离（格）")
            .defaultValue(16)
            .min(1)
            .max(1024)
            .sliderMax(256)
            .build()
    );

    private final Setting<String> chatPrefix = sgGeneral.add(new StringSetting.Builder()
            .name("聊天前缀")
            .description("发送聊天消息时在前面自动插入的文本（&代表§换色符）")
            .defaultValue("")
            .build()
    );

    private final Setting<String> chatSuffix = sgGeneral.add(new StringSetting.Builder()
            .name("聊天后缀")
            .description("发送聊天消息时在后面自动插入的文本（&代表§换色符）")
            .defaultValue("")
            .build()
    );

    // ---- 附近玩家名单 ----
    private final List<String> nearbyPlayers = new ArrayList<>();
    private int scanCooldown;

    // ---- 延时发送 ----
    private int delayTicks;
    private String pendingVictimName;

    private final Random random = new Random();
    private boolean processing;

    public AutoGGModule() {
        super(MaceKillAddon.CATEGORY, "自动GG", "击杀后自动发送消息 + 聊天前后缀");
    }

    @Override
    public void onDeactivate() {
        nearbyPlayers.clear();
        scanCooldown = 0;
        delayTicks = 0;
        pendingVictimName = null;
    }

    // ==================== Tick：扫描附近玩家 + 延时 ====================

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // 每 20 tick 刷新附近玩家名单（1 秒一次）
        if (scanCooldown <= 0) {
            scanCooldown = 20;
            updateNearbyPlayers();
        }
        scanCooldown--;

        // 延时发送中
        if (delayTicks > 0) {
            delayTicks--;
            if (delayTicks <= 0 && pendingVictimName != null) {
                sendGGMessage(pendingVictimName);
                pendingVictimName = null;
            }
        }
    }

    private void updateNearbyPlayers() {
        nearbyPlayers.clear();
        double rangeSq = range.get() * range.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (mc.player.squaredDistanceTo(player) > rangeSq) continue;
            nearbyPlayers.add(player.getName().getString());
        }
    }

    // ==================== 发包：聊天前后缀 ====================

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (processing || skipChatModify) return;
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        if (event.packet instanceof ChatMessageC2SPacket chatPacket) {
            String prefix = chatPrefix.get();
            String suffix = chatSuffix.get();
            if (prefix.isEmpty() && suffix.isEmpty()) return;

            String original = chatPacket.chatMessage();
            String modified = parseColors(prefix) + original + parseColors(suffix);

            processing = true;
            event.cancel();
            mc.getNetworkHandler().sendChatMessage(modified);
            processing = false;
        }
    }

    // ==================== 收包：匹配死亡消息 → 触发 GG ====================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        if (nearbyPlayers.isEmpty()) return;

        if (event.packet instanceof GameMessageS2CPacket packet) {
            String message = packet.content().getString();
            String myName = mc.player.getName().getString();

            // 死亡消息格式：昵称一定从第 1 个字符开始
            for (String name : nearbyPlayers) {
                if (!message.startsWith(name)) continue;

                // 检查目标名后面是否包含自己的名字
                String rest = message.substring(name.length());
                if (!rest.contains(myName)) continue;

                // 命中！触发
                triggerKill(name);
                return;
            }
        }
    }

    // ==================== 内部方法 ====================

    private void triggerKill(String playerName) {
        if (pendingVictimName != null) return;
        pendingVictimName = playerName;
        delayTicks = random.nextInt(21) + 4; // 200~1200ms
    }

    private void sendGGMessage(String playerName) {
        List<String> messages = messageList.get();
        String msg;
        if (messages.isEmpty()) {
            msg = "gg {player}";
        } else {
            msg = messages.get(random.nextInt(messages.size()));
        }
        msg = msg.replace("{player}", playerName);

        skipChatModify = true;

        // Title "击杀 [玩家]"
        if (mc.inGameHud != null) {
            mc.inGameHud.setTitle(Text.literal("§c击杀 §e" + playerName));
        }

        // 聊天栏日志
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§e[AutoGG] §a" + msg), false);
        }

        // 发送到公共聊天
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendChatMessage(msg);
        }

        skipChatModify = false;
    }

    private static String parseColors(String text) {
        return text.replace('&', '§');
    }
}
