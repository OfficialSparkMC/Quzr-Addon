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
        .description("Reject a request only if its message contains one of these exact phrases. Matching depends solely on this list (no hidden tpa/teleport check). Use the full phrase your server sends, e.g. 'wants to be teleported to you'.")
        .defaultValue("wants to be teleported to you")
        .build());

    private final Setting<Boolean> rejectAll = sg.add(new BoolSetting.Builder()
        .name("reject-all")
        .description("Reject every TPA request, ignoring the keyword list")
        .defaultValue(false)
        .build());

    private final Setting<String> denyCommand = sg.add(new StringSetting.Builder()
        .name("deny-command")
        .description("Command sent to deny the request. Without the leading slash (e.g. 'tpdeny' or 'tpa deny'). Use {player} for the requester name extracted from the message.")
        .defaultValue("tpdeny")
        .build());

    private long lastDeny = 0;

    public AutoTpaReject() {
        super(MaceKillAddon.CATEGORY, "auto-tpa-reject", "Auto-rejects TPA requests matching keywords");
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        String raw = event.getMessage().getString();
        String stripped = stripCodes(raw);
        String text = stripped.toLowerCase();
        if (text.isEmpty()) return;

        // Matching depends ONLY on the keyword list (or reject-all). There is no hidden
        // "contains tpa/teleport" gate - a message only triggers if it matches a keyword.
        boolean match = rejectAll.get();
        String matchedKw = "";
        if (!match) {
            for (String kw : keywords.get()) {
                if (!kw.isEmpty() && text.contains(kw.toLowerCase())) {
                    match = true;
                    matchedKw = kw;
                    break;
                }
            }
        }
        if (!match) return;

        // Cooldown so the server's deny-confirmation message can't re-trigger us.
        long now = System.currentTimeMillis();
        if (now - lastDeny < 1500) return;
        lastDeny = now;

        // Extract the requester name (text before the matched keyword) for {player} substitution.
        String player = "";
        if (!matchedKw.isEmpty()) {
            int idx = text.indexOf(matchedKw.toLowerCase());
            if (idx > 0) player = stripped.substring(0, idx).trim();
        }

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
