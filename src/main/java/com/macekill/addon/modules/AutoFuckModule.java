package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AutoFuckModule extends Module {
    private final Random random = new Random();

    // ==================== 设置组 ====================
    private final SettingGroup sgMode = settings.createGroup("模式");
    private final SettingGroup sgPlayer = settings.createGroup("目标玩家");
    private final SettingGroup sgPhrases = settings.createGroup("语录");
    private final SettingGroup sgTypo = settings.createGroup("错字");

    // ---- 模式 ----
    private enum TriggerMode { RANDOM_INTERVAL, WAIT_FOR_MESSAGE }
    private enum SendMode { SINGLE, BURST }

    private final Setting<TriggerMode> triggerMode = sgMode.add(new EnumSetting.Builder<TriggerMode>()
            .name("触发模式")
            .description("随机间隔 / 等待对方消息")
            .defaultValue(TriggerMode.RANDOM_INTERVAL)
            .build()
    );

    private final Setting<Double> minIntervalSec = sgMode.add(new DoubleSetting.Builder()
            .name("最小间隔")
            .description("随机发送的最小间隔（秒）")
            .defaultValue(5.0).min(1.0).max(30.0).sliderRange(1.0, 30.0)
            .visible(() -> triggerMode.get() == TriggerMode.RANDOM_INTERVAL)
            .build()
    );

    private final Setting<Double> maxIntervalSec = sgMode.add(new DoubleSetting.Builder()
            .name("最大间隔")
            .description("随机发送的最大间隔（秒）")
            .defaultValue(15.0).min(1.0).max(30.0).sliderRange(1.0, 30.0)
            .visible(() -> triggerMode.get() == TriggerMode.RANDOM_INTERVAL)
            .build()
    );

    private final Setting<Integer> waitMsgCountMin = sgMode.add(new IntSetting.Builder()
            .name("等待消息数(最小)")
            .description("等待对方发多少条消息后触发")
            .defaultValue(2).min(1).max(50).sliderRange(1, 20)
            .visible(() -> triggerMode.get() == TriggerMode.WAIT_FOR_MESSAGE)
            .build()
    );

    private final Setting<Integer> waitMsgCountMax = sgMode.add(new IntSetting.Builder()
            .name("等待消息数(最大)")
            .description("随机最大等待消息数（等于最小值则固定）")
            .defaultValue(5).min(1).max(50).sliderRange(1, 20)
            .visible(() -> triggerMode.get() == TriggerMode.WAIT_FOR_MESSAGE)
            .build()
    );

    private final Setting<SendMode> sendMode = sgMode.add(new EnumSetting.Builder<SendMode>()
            .name("发送模式")
            .description("单发 / 连发")
            .defaultValue(SendMode.SINGLE)
            .build()
    );

    private final Setting<Integer> burstCountMin = sgMode.add(new IntSetting.Builder()
            .name("连发数量(最小)")
            .defaultValue(2).min(1).max(20).sliderRange(1, 10)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    private final Setting<Integer> burstCountMax = sgMode.add(new IntSetting.Builder()
            .name("连发数量(最大)")
            .defaultValue(5).min(1).max(20).sliderRange(1, 10)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    private final Setting<Double> burstIntervalMin = sgMode.add(new DoubleSetting.Builder()
            .name("连发间隔(最小)")
            .description("连发每条之间的间隔（秒）")
            .defaultValue(0.5).min(0.1).max(5.0).sliderRange(0.1, 5.0)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    private final Setting<Double> burstIntervalMax = sgMode.add(new DoubleSetting.Builder()
            .name("连发间隔(最大)")
            .description("连发每条之间的随机最大间隔（秒）")
            .defaultValue(1.5).min(0.1).max(5.0).sliderRange(0.1, 5.0)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    // ---- 目标玩家 ----
    private enum PlayerMode { NEAREST, RANDOM, FIXED }

    private final Setting<PlayerMode> playerMode = sgPlayer.add(new EnumSetting.Builder<PlayerMode>()
            .name("目标选择")
            .description("最近玩家 / 随机玩家 / 固定玩家")
            .defaultValue(PlayerMode.NEAREST)
            .build()
    );

    private final Setting<String> fixedPlayer = sgPlayer.add(new StringSetting.Builder()
            .name("固定玩家名")
            .defaultValue("")
            .visible(() -> playerMode.get() == PlayerMode.FIXED)
            .build()
    );

    private final Setting<String> customCommand = sgPlayer.add(new StringSetting.Builder()
            .name("自定义指令")
            .description("为空则公聊发送，填写则用指令发送。{player}=目标名 {fuck}=骂人文本")
            .defaultValue("")
            .build()
    );

    // ---- 语录 ----
    private final Setting<List<String>> phraseList = sgPhrases.add(new StringListSetting.Builder()
            .name("语录列表")
            .description("右键列表打开编辑界面。用 [组名] 开头建立分组，{player} = 目标玩家名")
            .defaultValue("[默认]", "你菜得扣脚 {player}", "{player} 你好菜啊", "ez {player}")
            .build()
    );

    // ---- 错字 ----
    private final Setting<Boolean> typoEnabled = sgTypo.add(new BoolSetting.Builder()
            .name("启用错字")
            .description("发送时随机打乱字母大小写、标点符号、随机删除字符")
            .defaultValue(false)
            .build()
    );

    private final Setting<Double> typoFrequency = sgTypo.add(new DoubleSetting.Builder()
            .name("错字频率")
            .description("每条消息触发错字的概率（0=从不, 1=总是）")
            .defaultValue(0.3).min(0.0).max(1.0).sliderRange(0.0, 1.0)
            .visible(typoEnabled::get)
            .build()
    );

    private final Setting<Double> typoStrength = sgTypo.add(new DoubleSetting.Builder()
            .name("错字强度")
            .description("错字程度（0=几乎不变, 1=面目全非）")
            .defaultValue(0.5).min(0.0).max(1.0).sliderRange(0.0, 1.0)
            .visible(typoEnabled::get)
            .build()
    );

    private final Setting<List<String>> typoTable = sgTypo.add(new StringListSetting.Builder()
            .name("错字替换表")
            .description("格式：原词 -> 替换词（每行一条映射）")
            .defaultValue("ni hao -> n1 h4o", "hello -> he110")
            .visible(typoEnabled::get)
            .build()
    );

    // ==================== 运行时状态 ====================
    private int tickCounter;
    private int nextTriggerTick;
    private int waitedMsgCount;
    private int needMsgCount;
    private int burstRemaining;
    private int burstDelayTicks;

    public AutoFuckModule() {
        super(MaceKillAddon.CATEGORY, "AutoFuck", "自动骂人");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        tickCounter = 0;
        nextTriggerTick = randomTriggerDelay();
        waitedMsgCount = 0;
        needMsgCount = randomRange(waitMsgCountMin.get(), waitMsgCountMax.get());
        burstRemaining = 0;
        burstDelayTicks = 0;
    }

    // ==================== Tick ====================

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // 连发延迟中
        if (burstRemaining > 0) {
            if (burstDelayTicks > 0) {
                burstDelayTicks--;
                return;
            }
            doSend();
            burstRemaining--;
            if (burstRemaining > 0) {
                burstDelayTicks = randomBurstDelayTicks();
            }
            return;
        }

        tickCounter++;

        if (triggerMode.get() == TriggerMode.RANDOM_INTERVAL) {
            if (tickCounter >= nextTriggerTick) {
                trigger();
                tickCounter = 0;
                nextTriggerTick = randomTriggerDelay();
            }
        }
        // WAIT_FOR_MESSAGE 模式在 onPacketReceive 中触发
    }

    // ==================== 接收消息 ====================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        if (triggerMode.get() != TriggerMode.WAIT_FOR_MESSAGE) return;
        if (!(event.packet instanceof GameMessageS2CPacket packet)) return;

        String myName = mc.player.getName().getString();
        String sender = getMessageAuthor(packet);
        if (sender.isEmpty()) return;

        // 自己的消息不计数
        if (sender.equals(myName)) return;

        // 检查是否为目标的发言
        String target = getTargetName();
        if (!target.isEmpty() && !sender.equals(target)) return;

        waitedMsgCount++;
        if (waitedMsgCount >= needMsgCount) {
            trigger();
            waitedMsgCount = 0;
            needMsgCount = randomRange(waitMsgCountMin.get(), waitMsgCountMax.get());
        }
    }

    // ==================== 触发逻辑 ====================

    private void trigger() {
        String target = getTargetName();
        if (target.isEmpty()) return;

        List<String> phrases = collectPhrases();
        if (phrases.isEmpty()) return;

        if (sendMode.get() == SendMode.BURST) {
            burstRemaining = randomRange(burstCountMin.get(), burstCountMax.get());
            burstDelayTicks = 0;
        } else {
            doSend();
        }
    }

    private void doSend() {
        if (mc.getNetworkHandler() == null || mc.player == null) return;

        List<String> phrases = collectPhrases();
        if (phrases.isEmpty()) return;

        String target = getTargetName();
        if (target.isEmpty()) return;

        // 随机选一句
        String msg = phrases.get(random.nextInt(phrases.size()));
        msg = msg.replace("{player}", target);

        // 错字处理
        if (typoEnabled.get() && random.nextDouble() < typoFrequency.get()) {
            msg = applyTypo(msg);
        }

        String cmd = customCommand.get();
        if (!cmd.isEmpty()) {
            msg = cmd.replace("{player}", target).replace("{fuck}", msg);
        }

        mc.getNetworkHandler().sendChatMessage(msg);
    }

    // ==================== 语录收集 ====================

    private List<String> collectPhrases() {
        List<String> result = new ArrayList<>();
        for (String line : phraseList.get()) {
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) continue; // 跳过组名
            result.add(line);
        }
        return result;
    }

    // ==================== 目标获取 ====================

    private String getTargetName() {
        return switch (playerMode.get()) {
            case NEAREST -> getNearestPlayerName();
            case RANDOM -> getRandomPlayerName();
            case FIXED -> fixedPlayer.get();
        };
    }

    private String getNearestPlayerName() {
        if (mc.world == null || mc.player == null) return "";
        PlayerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest != null ? nearest.getName().getString() : "";
    }

    private String getRandomPlayerName() {
        if (mc.world == null || mc.player == null) return "";
        List<PlayerEntity> others = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player) others.add(p);
        }
        if (others.isEmpty()) return "";
        return others.get(random.nextInt(others.size())).getName().getString();
    }

    // ==================== 消息作者提取 ====================

    private String getMessageAuthor(GameMessageS2CPacket packet) {
        String text = packet.content().getString();
        // Minecraft 聊天格式通常以昵称开头，如 "<playerName> message"
        // 检测格式：<昵称> 后续
        if (text.startsWith("<")) {
            int end = text.indexOf(">");
            if (end > 1) return text.substring(1, end);
        }
        return "";
    }

    // ==================== 错字系统 ====================

    private String applyTypo(String text) {
        double strength = typoStrength.get();
        int ops = Math.max(1, (int) Math.ceil(text.length() * strength * 0.3));

        for (int i = 0; i < ops; i++) {
            int r = random.nextInt(3);
            text = switch (r) {
                case 0 -> swapCaseRandom(text);
                case 1 -> swapPunctuation(text);
                case 2 -> deleteRandomChar(text);
                default -> text;
            };
        }

        // 应用自定义替换表
        for (String mapping : typoTable.get()) {
            String[] parts = mapping.split("->");
            if (parts.length == 2) {
                String from = parts[0].trim();
                String to = parts[1].trim();
                if (random.nextDouble() < strength) {
                    text = text.replace(from, to);
                }
            }
        }

        return text;
    }

    private String swapCaseRandom(String text) {
        if (text.isEmpty()) return text;
        int idx = random.nextInt(text.length());
        char c = text.charAt(idx);
        if (Character.isUpperCase(c)) c = Character.toLowerCase(c);
        else if (Character.isLowerCase(c)) c = Character.toUpperCase(c);
        else return text;
        return text.substring(0, idx) + c + text.substring(idx + 1);
    }

    private String swapPunctuation(String text) {
        if (text.isEmpty()) return text;
        String[] targets = {"，", "。", "！", "？", ",", ".", "!", "?"};
        String[] replacements = {",", ".", "!", "?", "，", "。", "！", "？"};
        for (int i = 0; i < targets.length; i++) {
            if (text.contains(targets[i])) {
                int pair = i < 4 ? i + 4 : i - 4;
                text = text.replace(targets[i], replacements[pair]);
                return text;
            }
        }
        return text;
    }

    private String deleteRandomChar(String text) {
        if (text.length() <= 1) return text;
        int idx = random.nextInt(text.length());
        return text.substring(0, idx) + text.substring(idx + 1);
    }

    // ==================== 工具方法 ====================

    private int randomTriggerDelay() {
        double sec = minIntervalSec.get();
        double max = maxIntervalSec.get();
        if (max > sec) sec += random.nextDouble() * (max - sec);
        return (int) (sec * 20);
    }

    private int randomBurstDelayTicks() {
        double sec = burstIntervalMin.get();
        double max = burstIntervalMax.get();
        if (max > sec) sec += random.nextDouble() * (max - sec);
        return (int) (sec * 20);
    }

    private int randomRange(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }

    @Override
    public String getInfoString() {
        String target = getTargetName();
        return target.isEmpty() ? "无目标" : target;
    }
}
