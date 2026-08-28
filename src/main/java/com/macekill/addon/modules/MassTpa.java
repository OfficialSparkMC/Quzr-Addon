package com.macekill.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.macekill.addon.MaceKillAddon.CATEGORY;

public class MassTpa extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("Delay")
        .description("Ticks between each TPA request.")
        .defaultValue(20)
        .min(1)
        .max(200)
        .sliderMax(200)
        .build());

    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder()
        .name("Ignore Friends")
        .description("Do not send TPA to friends.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> sendOnEnable = sg.add(new BoolSetting.Builder()
        .name("Send On Enable")
        .description("Automatically send a TPA to everyone when the module is enabled.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> feedback = sg.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Print progress to chat.")
        .defaultValue(true)
        .build());

    private final List<String> queue = new ArrayList<>();
    private int timer;
    private boolean done;

    public MassTpa() {
        super(CATEGORY, "MassTpa", "Sends a TPA request to every player on the server.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        done = !sendOnEnable.get();
        if (sendOnEnable.get()) buildQueue();
    }

    @Override
    public void onDeactivate() {
        queue.clear();
    }

    private void buildQueue() {
        queue.clear();
        done = false;
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        Collection<PlayerListEntry> entries = mc.getNetworkHandler().getPlayerList();
        String self = mc.player.getName().getString();

        java.util.Set<String> friendNames = new java.util.HashSet<>();
        if (ignoreFriends.get() && mc.world != null) {
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (Friends.get().isFriend(p)) friendNames.add(p.getName().getString());
            }
        }

        for (PlayerListEntry entry : entries) {
            String name = entry.getProfile().name();
            if (name == null || name.isEmpty()) continue;
            if (name.equals(self)) continue;
            if (ignoreFriends.get() && friendNames.contains(name)) continue;
            queue.add(name);
        }

        if (feedback.get()) info("Queued %d TPA requests.", queue.size());
    }

    private void sendNext() {
        if (queue.isEmpty()) {
            if (!done && feedback.get()) info("Finished sending all TPA requests.");
            done = true;
            return;
        }
        String name = queue.remove(0);
        mc.getNetworkHandler().sendChatCommand("tpa " + name);
        if (feedback.get()) info("Sent TPA to %s", name);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (done) return;
        if (timer > 0) { timer--; return; }
        timer = delay.get();
        sendNext();
    }
}
