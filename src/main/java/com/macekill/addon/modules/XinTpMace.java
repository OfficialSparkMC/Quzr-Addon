package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.Inventory;
import com.macekill.addon.modules.macekill.Movement;
import com.macekill.addon.modules.macekill.Targeting;
import java.util.List;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * 新TP重锤 — xintpmace
 *
 * 逻辑：
 * Tick 中找最近目标 → 预测位置 → 分步传送到目标旁 → VClip起跳 → 多高度攻击 → 返回
 * 简洁版，无破甲/图腾绕过等复杂逻辑
 */
public class XinTpMace extends Module {

    private final SettingGroup sgMain = settings.getDefaultGroup();

    private final Setting<Double> range = sgMain.add(new DoubleSetting.Builder()
            .name("Range").description("Target detection range")
            .defaultValue(20.0).min(1.0).max(200.0).sliderRange(1.0, 128.0).build());

    private final Setting<Integer> predictTicks = sgMain.add(new IntSetting.Builder()
            .name("Predict Ticks").description("Ticks to predict future position")
            .defaultValue(5).min(1).sliderMax(20).build());

    private final Setting<Double> maxStep = sgMain.add(new DoubleSetting.Builder()
            .name("Max Step").description("Max distance per teleport segment")
            .defaultValue(8.0).min(1.0).max(128.0).sliderRange(1.0, 128.0).build());

    private final Setting<Integer> cooldown = sgMain.add(new IntSetting.Builder()
            .name("Cooldown").description("Attack cooldown in ticks")
            .defaultValue(10).min(0).max(40).sliderMax(40).build());

    private final Setting<List<String>> heights = sgMain.add(new StringListSetting.Builder()
            .name("Height List").description("Heights used for VClip attacks")
            .defaultValue("10", "20", "30").build());

    private final Setting<Boolean> swingHand = sgMain.add(new BoolSetting.Builder()
            .name("Swing Hand").description("Swing hand when attacking").defaultValue(false).build());

    private final Setting<Boolean> onlyPlayers = sgMain.add(new BoolSetting.Builder()
            .name("Players Only").description("Only attack players").defaultValue(true).build());

    // 状态
    private int cooldownTicks;
    private Vec3d originalPos;

    public XinTpMace() {
        super(MaceKillAddon.CATEGORY, "xintpmace", "New TP mace - predicted teleport+multi-height VClip attack");
    }

    @Override
    public void onDeactivate() {
        cooldownTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (cooldownTicks > 0) { cooldownTicks--; return; }

        LivingEntity target = findTarget();
        if (target == null) return;

        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d predicted = Targeting.predictPosition(mc, target, true, predictTicks.get());

        int oldSlot = Inventory.switchToMace(mc);
        if (oldSlot == -1) return;

        try {
            // 传送到目标旁
            Movement.doTpTo(mc, predicted, maxStep.get(), false);

            // 多高度VClip攻击
            for (String hStr : heights.get()) {
                double h;
                try { h = Double.parseDouble(hStr.trim()); }
                catch (NumberFormatException e) { continue; }
                if (h <= 0) continue;

                // VClip 起跳
                Vec3d current = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                Vec3d jumpPos = new Vec3d(predicted.x, predicted.y + h, predicted.z);
                Movement.doTpTo(mc, jumpPos, maxStep.get(), false);

                // 下落并攻击
                Vec3d attackPos = new Vec3d(predicted.x, predicted.y + 0.5, predicted.z);
                Movement.sendMovePacket(mc, attackPos.x, attackPos.y, attackPos.z);

                if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
                Movement.attackEntity(mc, target);
            }
        } finally {
            Inventory.switchBack(mc, oldSlot);
        }

        // 返回原位
        if (originalPos != null) {
            Movement.doTpTo(mc, originalPos, maxStep.get(), false);
        }

        cooldownTicks = cooldown.get();
    }

    private LivingEntity findTarget() {
        if (mc.world == null || mc.player == null) return null;
        double rangeSq = range.get() * range.get();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le == mc.player) continue;
            double distSq = mc.player.squaredDistanceTo(le);
            if (distSq > rangeSq) continue;
            if (onlyPlayers.get() && !(le instanceof PlayerEntity)) continue;
            if (le instanceof PlayerEntity p) {
                if (p.isCreative() || p.isSpectator() || !Friends.get().shouldAttack(p)) continue;
            }
            if (distSq < bestDist) { bestDist = distSq; best = le; }
        }
        return best;
    }

    @Override
    public String getInfoString() {
        if (cooldownTicks > 0) return "CD " + cooldownTicks;
        return "Ready";
    }
}
