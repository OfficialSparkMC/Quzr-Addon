package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

public class AutoTpaReject extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<List<String>> keywords = sg.add(new StringListSetting.Builder()
        .name("keywords")
        .description("Reject TPA requests whose message contains any of these keywords (a player name, or a phrase like 'has request tpa')")
        .defaultValue("has request tpa")
        .build());

    private final Setting<Boolean> rejectAll = sg.add(new BoolSetting.Builder()
        .name("reject-all")
        .description("Reject every TPA request, ignoring the keyword list")
        .defaultValue(false)
        .build());

    private final Setting<String> denyCommand = sg.add(new StringSetting.Builder()
        .name("deny-command")
        .description("Command sent to deny the request (without the leading slash, e.g. 'tpdeny' or 'tpa deny')")
        .defaultValue("tpdeny")
        .build());

    private long lastDeny = 0;

    public AutoTpaReject() {
        super(MaceKillAddon.CATEGORY, "auto-tpa-reject", "Auto-rejects TPA requests matching keywords");
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        String text = stripCodes(event.getMessage().getString()).toLowerCase();
        if (text.isEmpty()) return;

        // Only consider teleport-related messages to avoid false positives.
        if (!text.contains("teleport") && !text.contains("tpa")) return;

        boolean match = rejectAll.get();
        if (!match) {
            for (String kw : keywords.get()) {
                if (!kw.isEmpty() && text.contains(kw.toLowerCase())) {
                    match = true;
                    break;
                }
            }
        }
        if (!match) return;

        // Cooldown so the server's deny-confirmation message can't re-trigger us.
        long now = System.currentTimeMillis();
        if (now - lastDeny < 1500) return;
        lastDeny = now;

        mc.getNetworkHandler().sendChatCommand(denyCommand.get());
        ChatUtils.sendMsg(Text.literal("§c[AutoTpaReject] §fDenied TPA request."));
    }

    private static String stripCodes(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)\u00a7[0-9a-fk-or]", "");
    }
}
