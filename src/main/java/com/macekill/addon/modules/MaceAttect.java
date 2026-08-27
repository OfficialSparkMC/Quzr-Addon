package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.modules.macekill.Combat;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * MaceAttect - all of TpMace's smash features, but instead of auto-TP to a target it
 * triggers the mace totem-bypass smash on the specific entity YOU hit (or that another
 * module sends an attack packet to). The smash is performed locally (vertical VClip from
 * your current position onto the hit entity) - no long-range teleport.
 */
public class MaceAttect extends Module {
    private static Field selectedSlotField;
    private static Field pktEntityIdField;
    private static Field pktTypeField;
    private static Object pktAttackType;
    private static Field attackEventEntityField;

    // ==================== 设置组 ====================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgExploit = settings.createGroup("Attack");
    private final SettingGroup sgTotem = settings.createGroup("Totem Bypass");
    private final SettingGroup sgTarget = settings.createGroup("Target");

    // ---- 通用 ----
    private final Setting<Double> moveDistance = sgGeneral.add(new DoubleSetting.Builder()
            .name("Move Step").description("Max distance per movement packet")
            .defaultValue(8).min(1).max(128).sliderRange(1, 128).build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
            .name("Auto Switch").description("Auto-switch to mace").defaultValue(true).build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("Rotate").description("Face target when attacking").defaultValue(true).build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
            .name("Swing Hand").description("Swing hand client-side on attack").defaultValue(false).build()
    );

    private final Setting<Boolean> returnPos = sgGeneral.add(new BoolSetting.Builder()
            .name("Return to Start").description("Return to where you were standing when you hit")
            .defaultValue(true).build()
    );

    // ---- 攻击 ----
    private final Setting<Boolean> maxPower = sgExploit.add(new BoolSetting.Builder()
            .name("Max Damage").description("Use max safe height (170) when on, set height when off")
            .defaultValue(true).build()
    );

    private final Setting<Integer> fallHeight = sgExploit.add(new IntSetting.Builder()
            .name("Attack Height").description("Fall height (mace damage) used for the attack")
            .defaultValue(170).min(1).max(170).sliderRange(1, 170)
            .visible(() -> !maxPower.get()).build()
    );

    private final Setting<Boolean> silentSwap = sgExploit.add(new BoolSetting.Builder()
            .name("Silent Swap").description("Swap to mace without sending a slot packet").defaultValue(false).build()
    );

    // ---- 图腾绕过 ----
    private final Setting<Boolean> totemBypass = sgTotem.add(new BoolSetting.Builder()
            .name("Totem Bypass").description("Drain totems with multi-height hits, then kill at full height")
            .defaultValue(false).build()
    );

    private final Setting<Boolean> detectTotem = sgTotem.add(new BoolSetting.Builder()
            .name("Detect Totem").description("Only drain if the target is actually holding a totem")
            .defaultValue(true).visible(totemBypass::get).build()
    );

    private final Setting<DrainMode> drainMode = sgTotem.add(new EnumSetting.Builder<DrainMode>()
            .name("Drain Mode").description("List: use custom height list | Incremental: base + step per hit")
            .defaultValue(DrainMode.LIST).visible(totemBypass::get).build()
    );

    private final Setting<List<String>> drainHeights = sgTotem.add(new StringListSetting.Builder()
            .name("Drain Heights").description("Heights used to drain totems before the kill")
            .defaultValue("4", "8", "12", "16")
            .visible(() -> totemBypass.get() && drainMode.get() == DrainMode.LIST).build()
    );

    private final Setting<Integer> baseDrainHeight = sgTotem.add(new IntSetting.Builder()
            .name("Base Drain Height").description("Starting height for incremental drain attacks")
            .defaultValue(4).min(1).max(50).sliderMax(50)
            .visible(() -> totemBypass.get() && drainMode.get() == DrainMode.INCREMENTAL).build()
    );

    private final Setting<Integer> heightIncrement = sgTotem.add(new IntSetting.Builder()
            .name("Height Increment").description("Added height per drain attack")
            .defaultValue(4).min(1).max(20).sliderMax(20)
            .visible(() -> totemBypass.get() && drainMode.get() == DrainMode.INCREMENTAL).build()
    );

    private final Setting<Integer> totemAttacks = sgTotem.add(new IntSetting.Builder()
            .name("Totem Attacks").description("Number of drain attacks before the kill")
            .defaultValue(4).min(1).max(15).sliderMax(15)
            .visible(totemBypass::get).build()
    );

    private final Setting<Integer> totemsToPop = sgTotem.add(new IntSetting.Builder()
            .name("Totems To Pop").description("How many totems to pop (1-198)")
            .defaultValue(24).min(1).max(198).sliderMax(198)
            .visible(totemBypass::get).build()
    );

    private final Setting<Boolean> singleTick = sgTotem.add(new BoolSetting.Builder()
            .name("Single Tick").description("Fire every escalating hit in one tick (one mace smash). Turn OFF to spread across ticks if your server only lets 1 totem pop per tick")
            .defaultValue(true).visible(totemBypass::get).build()
    );

    private final Setting<Integer> bypassHitsPerTick = sgTotem.add(new IntSetting.Builder()
            .name("Hits Per Tick").description("Bypass hits fired each tick when Single Tick is OFF. 1 = 1 totem/tick")
            .defaultValue(1).min(1).max(20).sliderMax(20)
            .visible(() -> totemBypass.get() && !singleTick.get()).build()
    );

    // ---- 目标过滤 ----
    private final Setting<Boolean> players = sgTarget.add(new BoolSetting.Builder()
            .name("Players").description("Smash players").defaultValue(true).build()
    );
    private final Setting<Boolean> entities = sgTarget.add(new BoolSetting.Builder()
            .name("Entities").description("Smash living entities").defaultValue(false).build()
    );
    private final Setting<Boolean> throughWalls = sgTarget.add(new BoolSetting.Builder()
            .name("Through Walls").description("Require line of sight to smash").defaultValue(false).build()
    );
    private final Setting<Boolean> ignoreNamed = sgTarget.add(new BoolSetting.Builder()
            .name("Ignore Named").description("Ignore entities with custom names").defaultValue(false).build()
    );
    private final Setting<ListMode> listMode = sgTarget.add(new EnumSetting.Builder<ListMode>()
            .name("List Mode").description("Whitelist or blacklist").defaultValue(ListMode.Off).build()
    );
    private final Setting<String> playerList = sgTarget.add(new StringSetting.Builder()
            .name("Player List").description("Comma separated").defaultValue("")
            .visible(() -> listMode.get() != ListMode.Off).build()
    );

    // ==================== 状态 ====================
    private enum Phase { IDLE, SMASH, RETURN_DELAY }
    private enum ListMode { Off, Whitelist, Blacklist }
    private enum DrainMode { LIST, INCREMENTAL }

    private Phase phase = Phase.IDLE;
    private int delayTicks;
    private int attackCount;
    private Vec3d originalPos;
    private LivingEntity target;
    private LivingEntity pendingTarget;
    private int originalSlot = -1;
    private int maceSlot = -1;
    private int maceSwapBackSlot = -1;
    private boolean noMaceWarned = false;
    private List<String> bypassHeights;
    private int bypassIdx;

    public MaceAttect() {
        super(MaceKillAddon.CATEGORY, "MaceAttect", "Mace totem-bypass smash triggered by your hit / an attack packet on a specific entity (no auto-TP)");
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        target = null;
        pendingTarget = null;
        originalPos = null;
        originalSlot = -1;
        maceSlot = -1;
        maceSwapBackSlot = -1;
        attackCount = 0;
        bypassHeights = null;
        bypassIdx = 0;
    }

    // ==================== 触发 ====================

    /** Other modules can call this to make MaceAttect smash an entity. */
    public static void request(LivingEntity target) {
        MaceAttect m = Modules.get().get(MaceAttect.class);
        if (m != null && m.isActive() && m.phase == Phase.IDLE) {
            m.pendingTarget = target;
        }
    }

    // Manual hit: Meteor posts AttackEntityEvent whenever the player attacks an entity.
    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        if (phase != Phase.IDLE) return;
        LivingEntity le = getAttackEventEntity(event);
        if (le == null) return;
        if (!hitCheck(le)) return;
        pendingTarget = le;
    }

    // Injection: any module that sends an attack packet on an entity triggers the smash too.
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (phase != Phase.IDLE) return;
        if (!(event.packet instanceof PlayerInteractEntityC2SPacket pkt)) return;
        if (!isAttackPacket(pkt)) return;
        Entity e = getPacketEntity(pkt);
        if (!(e instanceof LivingEntity le)) return;
        if (!hitCheck(le)) return;
        pendingTarget = le;
    }

    // PlayerInteractEntityC2SPacket has no public type/getter on the client, so detect the
    // ATTACK variant via reflection on the private fields. Covers both your manual hit and
    // any attack packet another module sends (injection).
    private boolean isAttackPacket(PlayerInteractEntityC2SPacket pkt) {
        try {
            if (pktTypeField == null) {
                pktTypeField = PlayerInteractEntityC2SPacket.class.getDeclaredField("type");
                pktTypeField.setAccessible(true);
            }
            if (pktAttackType == null) {
                Field atk = PlayerInteractEntityC2SPacket.class.getDeclaredField("ATTACK");
                atk.setAccessible(true);
                pktAttackType = atk.get(null);
            }
            return pktAttackType != null && pktTypeField.get(pkt) == pktAttackType;
        } catch (Exception e) {
            return false;
        }
    }

    private Entity getPacketEntity(PlayerInteractEntityC2SPacket pkt) {
        try {
            if (pktEntityIdField == null) {
                pktEntityIdField = PlayerInteractEntityC2SPacket.class.getDeclaredField("entityId");
                pktEntityIdField.setAccessible(true);
            }
            int id = (int) pktEntityIdField.get(pkt);
            return mc.world.getEntityById(id);
        } catch (Exception e) {
            return null;
        }
    }

    // AttackEntityEvent.entity is typed via a different mapping than this addon uses, so read
    // it reflectively to avoid a compile-time type mismatch.
    private LivingEntity getAttackEventEntity(AttackEntityEvent event) {
        try {
            if (attackEventEntityField == null) {
                attackEventEntityField = AttackEntityEvent.class.getField("entity");
            }
            Object e = attackEventEntityField.get(event);
            if (e instanceof LivingEntity le) return le;
        } catch (Exception ignored) {
        }
        return null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (phase) {
            case IDLE -> {
                if (pendingTarget != null) {
                    LivingEntity t = pendingTarget;
                    pendingTarget = null;
                    if (checkAndSwapWeapon()) {
                        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                        attackCount = 0;
                        target = t;
                        phase = Phase.SMASH;
                    }
                }
            }
            case SMASH -> runSmash();
            case RETURN_DELAY -> tickReturnDelay();
        }
    }

    // ==================== 攻击核心 ====================

    private void runSmash() {
        if (target == null) { resetState(); return; }

        if (totemBypass.get() && target instanceof PlayerEntity p
                && (!detectTotem.get() || targetHasTotem(p))) {
            int totems = totemsToPop.get();
            if (detectTotem.get()) totems = Math.min(totems, countTotems(p));
            totems = Math.min(totems, Combat.MAX_TOTEM_HITS);
            int base = maxPower.get() ? 170 : fallHeight.get();
            List<String> all = Combat.totemBypassHeights(getDrainHeights(), totems, base, 3);

            if (singleTick.get()) {
                // One mace smash: every escalating hit in a single tick.
                for (String hStr : all) {
                    int h = parseHeight(hStr);
                    if (h > 0) attackOnce(target, h);
                }
                finishAttack();
                return;
            }

            // Multi-tick: spread the escalating hits across ticks.
            if (bypassHeights == null) {
                bypassHeights = all;
                bypassIdx = 0;
            }
            int fired = 0;
            while (bypassIdx < bypassHeights.size() && fired < bypassHitsPerTick.get()) {
                int h = parseHeight(bypassHeights.get(bypassIdx));
                if (h > 0) attackOnce(target, h);
                bypassIdx++;
                fired++;
            }
            if (bypassIdx >= bypassHeights.size()) {
                bypassHeights = null;
                finishAttack();
            }
            return;
        }

        doAttack(target, getAttackHeight(), true);
    }

    private void doAttack(LivingEntity target, int height, boolean isFinal) {
        attackOnce(target, height);
        if (isFinal) {
            finishAttack();
        } else {
            delayTicks = 0;
            phase = Phase.RETURN_DELAY;
        }
    }

    private void attackOnce(LivingEntity target, int height) {
        if (mc.player == null) return;

        // Cap the teleport at the highest air that is still connected to the target. Never
        // teleport through a solid roof into the disconnected sky (that strands the player up there).
        BlockPos hole = Combat.findVclipHole(mc, target.getX(), target.getY(), target.getZ(), height);
        if (hole.getY() <= (int) target.getY() + 1) {
            return; // no clearance under the roof -> don't attack, avoid getting stuck
        }
        Vec3d tpPos = new Vec3d(target.getX(), hole.getY(), target.getZ());

        if (rotate.get()) {
            float yaw = getYawTo(target);
            float pitch = getPitchTo(target);
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), false));
        }

        // VClip：模拟升空（从玩家当前位置到目标正上方）
        sendVClipPackets(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()), tpPos);

        // 模拟掉落
        sendExploitPackets(tpPos);

        // 攻击
        sendAttack(target);
    }

    private void sendVClipPackets(Vec3d from, Vec3d to) {
        double step = moveDistance.get();
        double totalDist = to.y - from.y;
        int steps = (int) Math.ceil(Math.abs(totalDist) / step);
        double stepY = totalDist / steps;

        for (int i = 1; i <= steps; i++) {
            double y = from.y + stepY * i;
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(from.x, y, from.z, false, false));
        }
    }

    private void sendExploitPackets(Vec3d from) {
        double step = moveDistance.get();
        double startY = from.y;
        double targetY = target.getY() + 1.1;

        for (double y = startY; y > targetY + step; y -= step) {
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(from.x, y, from.z, false, false));
        }

        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(from.x, targetY, from.z, false, false));
    }

    private void sendAttack(LivingEntity target) {
        if (silentSwap.get() && maceSlot != -1) {
            setSlotClient(maceSlot);
        }

        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));

        if (swingHand.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        if (silentSwap.get()) {
            setSlotClient(originalSlot);
        }
    }

    private void finishAttack() {
        if (returnPos.get() && originalPos != null) {
            mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.PositionAndOnGround(originalPos.x, originalPos.y, originalPos.z,
                            mc.player.isOnGround(), false));
            delayTicks = 0;
            phase = Phase.RETURN_DELAY;
            return;
        }
        resetState();
    }

    private void tickReturnDelay() {
        delayTicks++;
        if (delayTicks >= 2) {
            if (originalPos != null) {
                mc.getNetworkHandler().sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(originalPos.x, originalPos.y + 0.5, originalPos.z,
                                true, false));
            }
            resetState();
        }
    }

    private void resetState() {
        if (maceSwapBackSlot >= 0) {
            swapMace(maceSwapBackSlot, originalSlot);
            maceSwapBackSlot = -1;
        }
        phase = Phase.IDLE;
        delayTicks = 0;
        attackCount = 0;
        target = null;
        originalPos = null;
        bypassHeights = null;
        bypassIdx = 0;
    }

    // ==================== 武器切换 ====================

    private boolean checkAndSwapWeapon() {
        originalSlot = getSelectedSlot();
        maceSwapBackSlot = -1;

        if (!autoSwitch.get()) return true;

        PlayerInventory inv = mc.player.getInventory();

        if (inv.getStack(originalSlot).isOf(Items.MACE)) {
            maceSlot = originalSlot;
            noMaceWarned = false;
            return true;
        }

        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.MACE)) {
                maceSlot = i;
                noMaceWarned = false;
                if (i != originalSlot) {
                    setSelectedSlot(inv, i);
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i));
                }
                return true;
            }
        }

        int src = -1;
        for (int i = 9; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.MACE)) {
                src = i;
                break;
            }
        }
        if (src == -1) {
            if (!noMaceWarned) {
                error("No mace found in inventory, attack cancelled.");
                noMaceWarned = true;
            }
            return false;
        }

        swapMace(src, originalSlot);
        maceSlot = originalSlot;
        maceSwapBackSlot = src;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
        noMaceWarned = false;
        return true;
    }

    private void swapMace(int invSlot, int hotbarSlot) {
        if (mc.player == null) return;
        int screenSlot = invSlot;
        if (invSlot >= 36 && invSlot <= 39) screenSlot = invSlot - 31; // armor -> 5..8
        else if (invSlot == 40) screenSlot = 45; // offhand
        mc.interactionManager.clickSlot(
                mc.player.playerScreenHandler.syncId, screenSlot, hotbarSlot, SlotActionType.SWAP, mc.player);
    }

    // ==================== 目标过滤 ====================

    private boolean hitCheck(Entity entity) {
        if (!(entity instanceof LivingEntity le) || le.isDead()) return false;
        if (entity == mc.player) return false;

        if (entity instanceof PlayerEntity player) {
            if (!players.get()) return false;
            if (player.isSpectator()) return false;
            if (Friends.get().isFriend(player)) return false;
            if (!Friends.get().shouldAttack(player)) return false;

            if (listMode.get() != ListMode.Off) {
                List<String> list = parsePlayerList();
                String name = player.getName().getString();
                if (listMode.get() == ListMode.Whitelist && !list.contains(name)) return false;
                if (listMode.get() == ListMode.Blacklist && list.contains(name)) return false;
            }
        } else {
            if (!entities.get()) return false;
        }

        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (!throughWalls.get() && !mc.player.canSee(entity)) return false;

        return true;
    }

    // ==================== 高度计算 ====================

    private int getAttackHeight() {
        if (maxPower.get()) return 170;
        return Math.min(fallHeight.get(), 170);
    }

    // ==================== 旋转 ====================

    private float getYawTo(Entity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private float getPitchTo(Entity target) {
        double dx = target.getX() - mc.player.getX();
        double dy = target.getEyeY() - mc.player.getEyeY();
        double dz = target.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, dist));
    }

    // ==================== 反射 ====================

    private int getSelectedSlot() {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            return selectedSlotField.getInt(mc.player.getInventory());
        } catch (Exception e) {
            return 0;
        }
    }

    private void setSelectedSlot(PlayerInventory inv, int slot) {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            selectedSlotField.setInt(inv, slot);
        } catch (Exception ignored) {}
    }

    private void setSlotClient(int slot) {
        setSelectedSlot(mc.player.getInventory(), slot);
    }

    private boolean targetHasTotem(PlayerEntity player) {
        return countTotems(player) > 0;
    }

    private int countTotems(PlayerEntity player) {
        int n = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) n++;
        }
        return n;
    }

    private List<String> getDrainHeights() {
        if (drainMode.get() == DrainMode.INCREMENTAL) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < totemAttacks.get(); i++) {
                list.add(String.valueOf(baseDrainHeight.get() + i * heightIncrement.get()));
            }
            return list;
        }
        return drainHeights.get();
    }

    private int parseHeight(String s) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> parsePlayerList() {
        List<String> list = new ArrayList<>();
        for (String s : playerList.get().split(",")) {
            String trim = s.trim();
            if (!trim.isEmpty()) list.add(trim);
        }
        return list;
    }

    @Override
    public String getInfoString() {
        if (totemBypass.get() && target instanceof PlayerEntity) return "Totem Bypass";
        return target != null ? target.getName().getString() : "Idle";
    }
}
