package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class AutoTpaReject extends Module {
    // The ONLY phrase that triggers a deny: the message must END with this, preceded by the
    // requester name. Nothing else (no other keywords, no reject-all) will ever match.
    private static final String KEYWORD = "wants to be teleported to you";

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> denyCommand = sg.add(new StringSetting.Builder()
        .name("deny-command")
        .description("Command sent to deny the request. Without the leading slash (e.g. 'tpdeny' or 'tpa deny'). Use {player} for the requester name extracted from the message.")
        .defaultValue("tpdeny")
        .build());

    private long lastDeny = 0;

    public AutoTpaReject() {
        super(MaceKillAddon.CATEGORY, "auto-tpa-reject", "Auto-rejects TPA requests that exactly end with '" + KEYWORD + "'");
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        String raw = event.getMessage().getString();
        String stripped = stripCodes(raw);
        String text = stripped.toLowerCase();
        if (text.isEmpty()) return;

        // Strict, single-phrase match: the message must END with KEYWORD, with the requester name
        // as the only prefix - so "{player} wants to be teleported to you to them" does NOT match,
        // and no other phrase will ever trigger a deny.
        String lowerKw = KEYWORD.toLowerCase();
        int prefixLen = text.endsWith(lowerKw) ? text.length() - lowerKw.length() : -1;
        if (prefixLen <= 0) return;

        // Cooldown so the server's deny-confirmation message can't re-trigger us.
        long now = System.currentTimeMillis();
        if (now - lastDeny < 1500) return;
        lastDeny = now;

        // Extract the requester name (text before the keyword) for {player} substitution.
        String player = stripped.substring(0, prefixLen).trim();

        String cmd = denyCommand.get();
        cmd = cmd.replace("{player}", player);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (!cmd.isEmpty()) mc.getNetworkHandler().sendChatCommand(cmd);

        ChatUtils.sendMsg(Text.literal("§c[AutoTpaReject] §fDenied TPA request"
                + (player.isEmpty() ? "." : " from " + player + ".")));
    }

    private static String stripCodes(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)\u00a7[0-9a-fk-or]", "");
    }
}
