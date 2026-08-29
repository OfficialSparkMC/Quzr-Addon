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

    // ==================== Settings Groups ====================

    private final SettingGroup sgMode = settings.createGroup("Mode");
    private final SettingGroup sgPlayer = settings.createGroup("Target");
    private final SettingGroup sgPhrases = settings.createGroup("Messages");
    private final SettingGroup sgTypo = settings.createGroup("Typos");

    // ==================== Trigger Settings ====================

    private enum TriggerMode {
        RANDOM_INTERVAL,
        WAIT_FOR_MESSAGE
    }

    private enum SendMode {
        SINGLE,
        BURST
    }

    private final Setting<TriggerMode> triggerMode = sgMode.add(
        new EnumSetting.Builder<TriggerMode>()
            .name("trigger-mode")
            .description("Choose whether messages are sent at random intervals or after receiving messages.")
            .defaultValue(TriggerMode.RANDOM_INTERVAL)
            .build()
    );

    private final Setting<Double> minIntervalSec = sgMode.add(
        new DoubleSetting.Builder()
            .name("minimum-interval")
            .description("Minimum random delay between messages, in seconds.")
            .defaultValue(5.0)
            .min(1.0)
            .max(30.0)
            .sliderRange(1.0, 30.0)
            .visible(() -> triggerMode.get() == TriggerMode.RANDOM_INTERVAL)
            .build()
    );

    private final Setting<Double> maxIntervalSec = sgMode.add(
        new DoubleSetting.Builder()
            .name("maximum-interval")
            .description("Maximum random delay between messages, in seconds.")
            .defaultValue(15.0)
            .min(1.0)
            .max(30.0)
            .sliderRange(1.0, 30.0)
            .visible(() -> triggerMode.get() == TriggerMode.RANDOM_INTERVAL)
            .build()
    );

    private final Setting<Integer> waitMsgCountMin = sgMode.add(
        new IntSetting.Builder()
            .name("minimum-message-count")
            .description("Minimum number of messages to wait for before triggering.")
            .defaultValue(2)
            .min(1)
            .max(50)
            .sliderRange(1, 20)
            .visible(() -> triggerMode.get() == TriggerMode.WAIT_FOR_MESSAGE)
            .build()
    );

    private final Setting<Integer> waitMsgCountMax = sgMode.add(
        new IntSetting.Builder()
            .name("maximum-message-count")
            .description("Maximum random number of messages to wait for.")
            .defaultValue(5)
            .min(1)
            .max(50)
            .sliderRange(1, 20)
            .visible(() -> triggerMode.get() == TriggerMode.WAIT_FOR_MESSAGE)
            .build()
    );

    // ==================== Send Settings ====================

    private final Setting<SendMode> sendMode = sgMode.add(
        new EnumSetting.Builder<SendMode>()
            .name("send-mode")
            .description("Send a single message or send multiple messages in a burst.")
            .defaultValue(SendMode.SINGLE)
            .build()
    );

    private final Setting<Integer> burstCountMin = sgMode.add(
        new IntSetting.Builder()
            .name("minimum-burst-count")
            .description("Minimum number of messages sent during a burst.")
            .defaultValue(2)
            .min(1)
            .max(20)
            .sliderRange(1, 10)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    private final Setting<Integer> burstCountMax = sgMode.add(
        new IntSetting.Builder()
            .name("maximum-burst-count")
            .description("Maximum number of messages sent during a burst.")
            .defaultValue(5)
            .min(1)
            .max(20)
            .sliderRange(1, 10)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    private final Setting<Double> burstIntervalMin = sgMode.add(
        new DoubleSetting.Builder()
            .name("minimum-burst-delay")
            .description("Minimum delay between messages in a burst, in seconds.")
            .defaultValue(0.5)
            .min(0.1)
            .max(5.0)
            .sliderRange(0.1, 5.0)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    private final Setting<Double> burstIntervalMax = sgMode.add(
        new DoubleSetting.Builder()
            .name("maximum-burst-delay")
            .description("Maximum random delay between messages in a burst, in seconds.")
            .defaultValue(1.5)
            .min(0.1)
            .max(5.0)
            .sliderRange(0.1, 5.0)
            .visible(() -> sendMode.get() == SendMode.BURST)
            .build()
    );

    // ==================== Target Settings ====================

    private enum PlayerMode {
        NEAREST,
        RANDOM,
        FIXED
    }

    private final Setting<PlayerMode> playerMode = sgPlayer.add(
        new EnumSetting.Builder<PlayerMode>()
            .name("target-selection")
            .description("Select the nearest, random, or a specific player.")
            .defaultValue(PlayerMode.NEAREST)
            .build()
    );

    private final Setting<String> fixedPlayer = sgPlayer.add(
        new StringSetting.Builder()
            .name("fixed-player")
            .description("Player name to target when fixed targeting is enabled.")
            .defaultValue("")
            .visible(() -> playerMode.get() == PlayerMode.FIXED)
            .build()
    );

    private final Setting<String> customCommand = sgPlayer.add(
        new StringSetting.Builder()
            .name("custom-command")
            .description("Leave empty for public chat. Use {player} for the target and {fuck} for the generated message.")
            .defaultValue("")
            .build()
    );

    // ==================== Message Settings ====================

    private final Setting<List<String>> phraseList = sgPhrases.add(
        new StringListSetting.Builder()
            .name("message-list")
            .description("Message templates. Use [Group Name] for group headers and {player} for the target name.")
            .defaultValue(
                "[Default]",
                "You are terrible {player}",
                "{player} you are so bad",
                "ez {player}"
            )
            .build()
    );

    // ==================== Typo Settings ====================

    private final Setting<Boolean> typoEnabled = sgTypo.add(
        new BoolSetting.Builder()
            .name("enable-typos")
            .description("Randomly alter capitalization, punctuation, and characters before sending.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Double> typoFrequency = sgTypo.add(
        new DoubleSetting.Builder()
            .name("typo-frequency")
            .description("Probability of applying typo effects to each message.")
            .defaultValue(0.3)
            .min(0.0)
            .max(1.0)
            .sliderRange(0.0, 1.0)
            .visible(typoEnabled::get)
            .build()
    );

    private final Setting<Double> typoStrength = sgTypo.add(
        new DoubleSetting.Builder()
            .name("typo-strength")
            .description("Controls how heavily the generated message is modified.")
            .defaultValue(0.5)
            .min(0.0)
            .max(1.0)
            .sliderRange(0.0, 1.0)
            .visible(typoEnabled::get)
            .build()
    );

    private final Setting<List<String>> typoTable = sgTypo.add(
        new StringListSetting.Builder()
            .name("typo-replacements")
            .description("Custom replacements using the format: original -> replacement.")
            .defaultValue(
                "ni hao -> n1 h4o",
                "hello -> he110"
            )
            .visible(typoEnabled::get)
            .build()
    );

    // ==================== Runtime State ====================

    private int tickCounter;
    private int nextTriggerTick;
    private int waitedMessageCount;
    private int requiredMessageCount;
    private int burstRemaining;
    private int burstDelayTicks;

    public AutoFuckModule() {
        super(
            MaceKillAddon.CATEGORY,
            "auto-fuck",
            "Automatically sends configured messages toward selected players."
        );
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
        waitedMessageCount = 0;
        requiredMessageCount = randomRange(
            waitMsgCountMin.get(),
            waitMsgCountMax.get()
        );
        burstRemaining = 0;
        burstDelayTicks = 0;
    }

    // ==================== Tick Handler ====================

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            return;
        }

        // Process burst messages.
        if (burstRemaining > 0) {
            if (burstDelayTicks > 0) {
                burstDelayTicks--;
                return;
            }

            sendMessage();
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

        // WAIT_FOR_MESSAGE mode is handled by onPacketReceive().
    }

    // ==================== Incoming Message Handler ====================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) {
            return;
        }

        if (triggerMode.get() != TriggerMode.WAIT_FOR_MESSAGE) {
            return;
        }

        if (!(event.packet instanceof GameMessageS2CPacket packet)) {
            return;
        }

        String localPlayerName = mc.player.getName().getString();
        String senderName = getMessageAuthor(packet);

        if (senderName.isEmpty()) {
            return;
        }

        // Ignore messages sent by ourselves.
        if (senderName.equals(localPlayerName)) {
            return;
        }

        String targetName = getTargetName();

        // Only count messages from the selected target.
        if (!targetName.isEmpty() && !senderName.equals(targetName)) {
            return;
        }

        waitedMessageCount++;

        if (waitedMessageCount >= requiredMessageCount) {
            trigger();

            waitedMessageCount = 0;
            requiredMessageCount = randomRange(
                waitMsgCountMin.get(),
                waitMsgCountMax.get()
            );
        }
    }

    // ==================== Trigger Logic ====================

    private void trigger() {
        String targetName = getTargetName();

        if (targetName.isEmpty()) {
            return;
        }

        List<String> messages = collectMessages();

        if (messages.isEmpty()) {
            return;
        }

        if (sendMode.get() == SendMode.BURST) {
            burstRemaining = randomRange(
                burstCountMin.get(),
                burstCountMax.get()
            );

            burstDelayTicks = 0;
        } else {
            sendMessage();
        }
    }

    private void sendMessage() {
        if (mc.getNetworkHandler() == null || mc.player == null) {
            return;
        }

        List<String> messages = collectMessages();

        if (messages.isEmpty()) {
            return;
        }

        String targetName = getTargetName();

        if (targetName.isEmpty()) {
            return;
        }

        // Select a random message.
        String message = messages.get(
            random.nextInt(messages.size())
        );

        message = message.replace("{player}", targetName);

        // Apply typo effects.
        if (typoEnabled.get() &&
            random.nextDouble() < typoFrequency.get()) {
            message = applyTypo(message);
        }

        // Use the custom command if configured.
        String command = customCommand.get();

        if (!command.isEmpty()) {
            message = command
                .replace("{player}", targetName)
                .replace("{fuck}", message);
        }

        // slash-prefixed messages (e.g. from custom-command "/msg {player} {fuck}") must be run as
        // commands; ClientPlayNetworkHandler.sendChatMessage only sends plain chat and would dump the
        // literal "/tell ..." into public chat. sendChatCommand runs it (without the leading slash).
        if (message.startsWith("/")) {
            String cmd = message.substring(1);
            if (!cmd.isEmpty()) {
                mc.getNetworkHandler().sendChatCommand(cmd);
            }
        } else {
            mc.getNetworkHandler().sendChatMessage(message);
        }
    }

    // ==================== Message Collection ====================

    private List<String> collectMessages() {
        List<String> result = new ArrayList<>();

        for (String line : phraseList.get()) {
            if (line.isEmpty()) {
                continue;
            }

            // Ignore group headers.
            if (line.startsWith("[") && line.endsWith("]")) {
                continue;
            }

            result.add(line);
        }

        return result;
    }

    // ==================== Target Selection ====================

    private String getTargetName() {
        return switch (playerMode.get()) {
            case NEAREST -> getNearestPlayerName();
            case RANDOM -> getRandomPlayerName();
            case FIXED -> fixedPlayer.get();
        };
    }

    private String getNearestPlayerName() {
        if (mc.world == null || mc.player == null) {
            return "";
        }

        PlayerEntity nearestPlayer = null;
        double closestDistance = Double.MAX_VALUE;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) {
                continue;
            }

            double distance =
                mc.player.squaredDistanceTo(player);

            if (distance < closestDistance) {
                closestDistance = distance;
                nearestPlayer = player;
            }
        }

        return nearestPlayer != null
            ? nearestPlayer.getName().getString()
            : "";
    }

    private String getRandomPlayerName() {
        if (mc.world == null || mc.player == null) {
            return "";
        }

        List<PlayerEntity> players = new ArrayList<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player) {
                players.add(player);
            }
        }

        if (players.isEmpty()) {
            return "";
        }

        return players
            .get(random.nextInt(players.size()))
            .getName()
            .getString();
    }

    // ==================== Message Author Detection ====================

    private String getMessageAuthor(GameMessageS2CPacket packet) {
        String message = packet.content().getString();

        // Detect standard Minecraft chat format:
        // <PlayerName> message
        if (message.startsWith("<")) {
            int closingBracket = message.indexOf(">");

            if (closingBracket > 1) {
                return message.substring(1, closingBracket);
            }
        }

        return "";
    }

    // ==================== Typo System ====================

    private String applyTypo(String text) {
        double strength = typoStrength.get();

        int operations = Math.max(
            1,
            (int) Math.ceil(
                text.length() * strength * 0.3
            )
        );

        for (int i = 0; i < operations; i++) {
            int operation = random.nextInt(3);

            text = switch (operation) {
                case 0 -> swapCaseRandom(text);
                case 1 -> swapPunctuation(text);
                case 2 -> deleteRandomChar(text);
                default -> text;
            };
        }

        // Apply custom replacement mappings.
        for (String mapping : typoTable.get()) {
            String[] parts = mapping.split("->");

            if (parts.length == 2) {
                String original = parts[0].trim();
                String replacement = parts[1].trim();

                if (random.nextDouble() < strength) {
                    text = text.replace(
                        original,
                        replacement
                    );
                }
            }
        }

        return text;
    }

    private String swapCaseRandom(String text) {
        if (text.isEmpty()) {
            return text;
        }

        int index = random.nextInt(text.length());
        char character = text.charAt(index);

        if (Character.isUpperCase(character)) {
            character = Character.toLowerCase(character);
        } else if (Character.isLowerCase(character)) {
            character = Character.toUpperCase(character);
        } else {
            return text;
        }

        return text.substring(0, index)
            + character
            + text.substring(index + 1);
    }

    private String swapPunctuation(String text) {
        if (text.isEmpty()) {
            return text;
        }

        String[] punctuation = {
            "，", "。", "！", "？",
            ",", ".", "!", "?"
        };

        String[] replacements = {
            ",", ".", "!", "?",
            "，", "。", "！", "？"
        };

        for (int i = 0; i < punctuation.length; i++) {
            if (text.contains(punctuation[i])) {
                int replacementIndex =
                    i < 4 ? i + 4 : i - 4;

                return text.replace(
                    punctuation[i],
                    replacements[replacementIndex]
                );
            }
        }

        return text;
    }

    private String deleteRandomChar(String text) {
        if (text.length() <= 1) {
            return text;
        }

        int index = random.nextInt(text.length());

        return text.substring(0, index)
            + text.substring(index + 1);
    }

    // ==================== Utility Methods ====================

    private int randomTriggerDelay() {
        double delay = minIntervalSec.get();
        double maximum = maxIntervalSec.get();

        if (maximum > delay) {
            delay += random.nextDouble() * (maximum - delay);
        }

        return (int) (delay * 20);
    }

    private int randomBurstDelayTicks() {
        double delay = burstIntervalMin.get();
        double maximum = burstIntervalMax.get();

        if (maximum > delay) {
            delay += random.nextDouble() * (maximum - delay);
        }

        return (int) (delay * 20);
    }

    private int randomRange(int min, int max) {
        if (max <= min) {
            return min;
        }

        return min + random.nextInt(max - min + 1);
    }

    @Override
    public String getInfoString() {
        String targetName = getTargetName();

        return targetName.isEmpty()
            ? "No target"
            : targetName;
    }
}
