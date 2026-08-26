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

    private final Setting<List<String>> messageList = sgGeneral.add(
        new StringListSetting.Builder()
            .name("gg-messages")
            .description("Random message sent after killing a player. Use {player} for the victim's name.")
            .defaultValue(
                "gg {player}",
                "good fight {player}",
                "better luck next time {player}"
            )
            .build()
    );

    private final Setting<Double> range = sgGeneral.add(
        new DoubleSetting.Builder()
            .name("detection-range")
            .description("Maximum distance used to detect nearby players.")
            .defaultValue(16)
            .min(1)
            .max(1024)
            .sliderMax(256)
            .build()
    );

    private final Setting<String> chatPrefix = sgGeneral.add(
        new StringSetting.Builder()
            .name("chat-prefix")
            .description("Text automatically added before outgoing chat messages. Use & for color codes.")
            .defaultValue("")
            .build()
    );

    private final Setting<String> chatSuffix = sgGeneral.add(
        new StringSetting.Builder()
            .name("chat-suffix")
            .description("Text automatically added after outgoing chat messages. Use & for color codes.")
            .defaultValue("")
            .build()
    );

    // Nearby player tracking
    private final List<String> nearbyPlayers = new ArrayList<>();
    private int scanCooldown;

    // Delayed GG message
    private int delayTicks;
    private String pendingVictimName;

    private final Random random = new Random();
    private boolean processing;

    public AutoGGModule() {
        super(
            MaceKillAddon.CATEGORY,
            "auto-gg",
            "Automatically sends a GG message after killing a nearby player."
        );
    }

    @Override
    public void onDeactivate() {
        nearbyPlayers.clear();
        scanCooldown = 0;
        delayTicks = 0;
        pendingVictimName = null;
    }

    // ==================== Tick: Player scanning + delayed message ====================

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Refresh nearby players once every second
        if (scanCooldown <= 0) {
            scanCooldown = 20;
            updateNearbyPlayers();
        }

        scanCooldown--;

        // Process delayed GG message
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

        double rangeSquared = range.get() * range.get();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;

            if (mc.player.squaredDistanceTo(player) > rangeSquared) {
                continue;
            }

            nearbyPlayers.add(player.getName().getString());
        }
    }

    // ==================== Outgoing chat modification ====================

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (processing || skipChatModify) return;
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        if (event.packet instanceof ChatMessageC2SPacket chatPacket) {
            String prefix = chatPrefix.get();
            String suffix = chatSuffix.get();

            if (prefix.isEmpty() && suffix.isEmpty()) {
                return;
            }

            String originalMessage = chatPacket.chatMessage();
            String modifiedMessage =
                parseColors(prefix) +
                originalMessage +
                parseColors(suffix);

            processing = true;

            event.cancel();
            mc.getNetworkHandler().sendChatMessage(modifiedMessage);

            processing = false;
        }
    }

    // ==================== Incoming death message detection ====================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        if (nearbyPlayers.isEmpty()) return;

        if (event.packet instanceof GameMessageS2CPacket packet) {
            String message = packet.content().getString();
            String localPlayerName = mc.player.getName().getString();

            for (String playerName : nearbyPlayers) {
                if (!message.startsWith(playerName)) {
                    continue;
                }

                String remainingMessage =
                    message.substring(playerName.length());

                if (!remainingMessage.contains(localPlayerName)) {
                    continue;
                }

                // Kill detected
                triggerKill(playerName);
                return;
            }
        }
    }

    // ==================== Kill handling ====================

    private void triggerKill(String playerName) {
        if (pendingVictimName != null) {
            return;
        }

        pendingVictimName = playerName;

        // Random delay between 200ms and 1200ms
        delayTicks = random.nextInt(21) + 4;
    }

    private void sendGGMessage(String playerName) {
        List<String> messages = messageList.get();

        String message;

        if (messages.isEmpty()) {
            message = "gg {player}";
        } else {
            message = messages.get(
                random.nextInt(messages.size())
            );
        }

        message = message.replace("{player}", playerName);

        skipChatModify = true;

        // Display kill notification
        if (mc.inGameHud != null) {
            mc.inGameHud.setTitle(
                Text.literal("§cKilled §e" + playerName)
            );
        }

        // Add local notification to chat
        if (mc.player != null) {
            mc.player.sendMessage(
                Text.literal("§e[AutoGG] §a" + message),
                false
            );
        }

        // Send GG message to public chat
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendChatMessage(message);
        }

        skipChatModify = false;
    }

    private static String parseColors(String text) {
        return text.replace('&', '§');
    }
}
