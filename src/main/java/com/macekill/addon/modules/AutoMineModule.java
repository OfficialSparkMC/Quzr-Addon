package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos.Mutable;
import net.minecraft.world.World;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 矿物追踪 — Wurst TunnelHack 风格。
 * 路径: 折线形, X/Z优先对齐 → 飞行垂直 → 挖掘 → 返回隧道层。
 */
public class AutoMineModule extends Module {

    /* ==================== Settings ==================== */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scanRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("扫描范围").description("搜索矿石的最大半径(格)")
        .defaultValue(64.0).min(8.0).max(256.0).sliderMax(128.0).build());

    private final Setting<Boolean> autoMine = sgGeneral.add(new BoolSetting.Builder()
        .name("自动挖矿").description("自动寻路+挖掘")
        .defaultValue(true).build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("最低Y层").description("此层数及以下的方块不会被向下挖掘")
        .defaultValue(-64).min(-64).max(320).build());

    private final Setting<Integer> stuckTime = sgGeneral.add(new IntSetting.Builder()
        .name("卡住判定").description("多久不动算卡住(tick)")
        .defaultValue(60).min(10).max(200).build());

    private final Setting<Integer> abandonThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("放弃阈值").description("上一个矿挖完后超时未到达则黑名单3分钟(tick)")
        .defaultValue(200).min(20).max(1200).build());

    private final Setting<Integer> safeDistance = sgGeneral.add(new IntSetting.Builder()
        .name("安全距离").description("躲避流体的最小距离(格)")
        .defaultValue(5).min(2).max(15).build());

    private final Setting<Boolean> avoidCaves = sgGeneral.add(new BoolSetting.Builder()
        .name("躲避洞穴").description("隧道避开天然空气/洞穴(会降低寻路成功率)")
        .defaultValue(false).build());

    private final Setting<Boolean> filterDangerOres = sgGeneral.add(new BoolSetting.Builder()
        .name("排除危险区矿物").description("不挖掘安全距离内靠近流体/洞穴的矿物")
        .defaultValue(true).build());

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("聊天信息").description("在聊天栏输出状态")
        .defaultValue(false).build());

    /* 矿石过滤 */
    private final SettingGroup sgOres = settings.createGroup("矿物列表");
    private final Setting<Boolean> mineCoal        = sgOres.add(new BoolSetting.Builder().name("煤矿").defaultValue(true).build());
    private final Setting<Boolean> mineIron        = sgOres.add(new BoolSetting.Builder().name("铁矿").defaultValue(true).build());
    private final Setting<Boolean> mineCopper      = sgOres.add(new BoolSetting.Builder().name("铜矿").defaultValue(true).build());
    private final Setting<Boolean> mineGold        = sgOres.add(new BoolSetting.Builder().name("金矿").defaultValue(true).build());
    private final Setting<Boolean> mineRedstone    = sgOres.add(new BoolSetting.Builder().name("红石矿").defaultValue(true).build());
    private final Setting<Boolean> mineLapis       = sgOres.add(new BoolSetting.Builder().name("青金石").defaultValue(true).build());
    private final Setting<Boolean> mineDiamond     = sgOres.add(new BoolSetting.Builder().name("钻石矿").defaultValue(true).build());
    private final Setting<Boolean> mineEmerald     = sgOres.add(new BoolSetting.Builder().name("绿宝石").defaultValue(true).build());
    private final Setting<Boolean> mineNetherQuartz= sgOres.add(new BoolSetting.Builder().name("下界石英").defaultValue(true).build());
    private final Setting<Boolean> mineNetherGold  = sgOres.add(new BoolSetting.Builder().name("下界金矿").defaultValue(true).build());
    private final Setting<Boolean> mineAncientDebris=sgOres.add(new BoolSetting.Builder().name("远古残骸").defaultValue(true).build());

    /* 渲染 */
    private final SettingGroup sgRender = settings.createGroup("渲染");
    private final Setting<Boolean> renderOres = sgRender.add(new BoolSetting.Builder()
        .name("渲染矿石").defaultValue(true).build());
    private final Setting<SettingColor> oreColor = sgRender.add(new ColorSetting.Builder()
        .name("矿石颜色").defaultValue(new SettingColor(255, 255, 0, 200)).visible(renderOres::get).build());
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("目标颜色").defaultValue(new SettingColor(0, 255, 0, 200)).build());
    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("路径颜色").defaultValue(new SettingColor(100, 200, 255, 180)).build());

    /* ==================== 状态 ==================== */
    private enum Phase { IDLE, WALK_XZ, FLY_UP, DIG_DOWN, MINE_ORE, RETURN_TO_TUNNEL }

    private final Set<BlockPos> orePositions = new HashSet<>();
    private BlockPos currentTarget;       // 当前目标矿石
    private final List<BlockPos> waypoints = new ArrayList<>(); // 折线路径节点
    private int wpIndex;                  // 当前路径节点索引
    private Phase phase = Phase.IDLE;
    private int scanCooldown = 60; // 每3秒扫描一次, 降低卡顿
    private int tickCounter;

    // 隧道挖掘
    private BlockPos breakingPos;         // 正在挖掘的方块
    private int breakTicks;
    private int stuckTicks;
    private boolean isStuck;
    private BlockPos lastPos;
    private int targetLockTime;           // 锁定同一个矿石的tick数(性能保护)

    // 垂直
    private int tunnelY;                  // 隧道层 Y (飞行前记录, 挖完矿返回此层)
    private boolean wasFlying;

    // 性能保护
    private Method getCpuLoadMethod;       // getProcessCpuLoad 反射方法(可能为null)
    private int cpuCheckTick;              // CPU检查计数器
    private long perfPauseUntil;           // 性能保护暂停到何时(ms)

    // 黑名单
    private final Map<BlockPos, Long> abandonBlacklist = new HashMap<>();
    private final Map<BlockPos, Long> dugPositions = new HashMap<>(); // 已挖掘位置, 1分钟过期
    private long lastOreMinedTime;

    private static Field selectedSlotField;

    /* ==================== 构造 & 生命周期 ==================== */
    public AutoMineModule() {
        super(MaceKillAddon.CATEGORY, "矿物追踪", "Wurst TunnelHack风格: 折线寻路+飞行垂直+自动挖掘");
    }

    @Override public void onActivate() {
        orePositions.clear(); currentTarget = null; waypoints.clear();
        wpIndex = 0; phase = Phase.IDLE; scanCooldown = 60; tickCounter = 0;
        breakingPos = null; breakTicks = 0; stuckTicks = 0; isStuck = false;
        targetLockTime = 0;
        abandonBlacklist.clear(); dugPositions.clear(); lastOreMinedTime = 0;
        lastPos = mc.player != null ? mc.player.getBlockPos() : null;
        wasFlying = mc.player != null && mc.player.getAbilities().flying;
        // 反射获取 CPU 监控方法(安全降级)
        try {
            getCpuLoadMethod = ManagementFactory.getOperatingSystemMXBean()
                .getClass().getMethod("getProcessCpuLoad");
        } catch (Exception ignored) {
            getCpuLoadMethod = null;
        }
    }

    @Override public void onDeactivate() {
        orePositions.clear(); waypoints.clear();
        releaseControls();
        // 恢复原始飞行状态
        if (!wasFlying) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().allowFlying = false;
        }
    }

    @Override public String getInfoString() {
        if (isStuck) return "清理...";
        if (phase == Phase.WALK_XZ && currentTarget != null) return "→" + currentTarget.toShortString();
        if (phase == Phase.FLY_UP) return "飞行上升...";
        if (phase == Phase.DIG_DOWN) return "阶梯下降...";
        if (phase == Phase.MINE_ORE) return "挖掘矿石...";
        if (phase == Phase.RETURN_TO_TUNNEL) return "返回隧道...";
        return orePositions.size() + " ore(s)";
    }

    /* ==================== 主循环 ==================== */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // 死亡自动关闭
        if (mc.player.isDead() || mc.player.getHealth() <= 0) {
            toggle();
            return;
        }

        tickCounter++;

        // 清理过期黑名单
        if (tickCounter % 100 == 0) {
            long now = System.currentTimeMillis();
            abandonBlacklist.entrySet().removeIf(e -> e.getValue() < now);
            dugPositions.entrySet().removeIf(e -> e.getValue() < now);
        }

        // 扫描
        if (scanCooldown-- <= 0) { scanCooldown = 60; scan(); }
        orePositions.removeIf(p -> {
            BlockState s = mc.world.getBlockState(p);
            return !isTargetOre(s.getBlock()) || s.isAir();
        });

        // 更新最近矿石
        BlockPos nearest = orePositions.isEmpty() ? null : orePositions.stream()
            .filter(p -> !abandonBlacklist.containsKey(p))
            .filter(p -> !dugPositions.containsKey(p))
            .min(Comparator.comparingDouble(p -> p.getSquaredDistance(mc.player.getEyePos()))).orElse(null);

        // 目标矿石被挖掉 → 记录时间
        if (currentTarget != null) {
            BlockState ts = mc.world.getBlockState(currentTarget);
            if (!isTargetOre(ts.getBlock()) || ts.isAir()) {
                lastOreMinedTime = System.currentTimeMillis();
                currentTarget = null; waypoints.clear(); wpIndex = 0; phase = Phase.IDLE;
                targetLockTime = 0;
                breakingPos = null; breakTicks = 0;
                returnIfFlying();
                notify("矿石已采掘完毕");
            }
        }

        // 放弃阈值
        if (currentTarget != null && lastOreMinedTime > 0) {
            long elapsed = System.currentTimeMillis() - lastOreMinedTime;
            if (elapsed > abandonThreshold.get() * 50L) {
                blacklistWithNeighbors(currentTarget);
                orePositions.remove(currentTarget);
                currentTarget = null; waypoints.clear(); wpIndex = 0; phase = Phase.IDLE;
                lastOreMinedTime = 0; // 重置, 避免每tick触发
                returnIfFlying();
                notify("放弃目标,黑名单3分钟");
            }
        }

        // 锁定新目标
        if (currentTarget == null && nearest != null) {
            currentTarget = nearest;
            targetLockTime = 0;
            planWaypoints();
            if (waypoints.isEmpty()) {
                currentTarget = null;
            } else if (autoMine.get()) {
                wpIndex = 0;
                // 检查是否在同Y层
                int pY = mc.player.getBlockPos().getY();
                if (Math.abs(currentTarget.getY() - pY) <= 1) {
                    phase = Phase.WALK_XZ;
                } else if (currentTarget.getY() != pY) {
                    phase = Phase.WALK_XZ; // 先平走到XZ位置
                }
            }
        }

        // 卡住检测
        BlockPos curPos = mc.player.getBlockPos();
        if (lastPos != null && curPos.equals(lastPos)) stuckTicks++;
        else { stuckTicks = 0; isStuck = false; }
        lastPos = curPos;
        if (stuckTicks >= stuckTime.get()) isStuck = true;

        // 卡住清理
        if (isStuck) { doClearing(); return; }

        // 如果手动关闭了自动挖矿, 仅扫描
        if (!autoMine.get() || currentTarget == null) return;

        // 性能保护暂停: 禁止寻路, 等待恢复
        if (perfPauseUntil > 0) {
            if (System.currentTimeMillis() < perfPauseUntil) return;
            perfPauseUntil = 0; cpuCheckTick = 0;
            notify("寻路已恢复");
        }

        // CPU实时监测: 每100tick检查进程CPU负载
        if (getCpuLoadMethod != null && ++cpuCheckTick >= 100) {
            cpuCheckTick = 0;
            try {
                double cpuLoad = (Double) getCpuLoadMethod.invoke(
                    ManagementFactory.getOperatingSystemMXBean());
                if (cpuLoad > 0.8) { // CPU > 80%
                    if (currentTarget != null) {
                        blacklistWithNeighbors(currentTarget);
                        orePositions.remove(currentTarget);
                    }
                    currentTarget = null; waypoints.clear(); wpIndex = 0; phase = Phase.IDLE;
                    targetLockTime = 0; lastOreMinedTime = 0;
                    breakingPos = null; breakTicks = 0;
                    mc.interactionManager.cancelBlockBreaking();
                    perfPauseUntil = System.currentTimeMillis() + 5_000L;
                    notify(String.format("CPU过载(%.0f%%),暂停5秒", cpuLoad * 100));
                    return;
                }
            } catch (Exception ignored) {}
        }

        // 性能保护: 锁定同一个矿石超过60秒 → 强制黑名单
        if (++targetLockTime > 1200) {
            blacklistWithNeighbors(currentTarget);
            orePositions.remove(currentTarget);
            currentTarget = null; waypoints.clear(); wpIndex = 0; phase = Phase.IDLE;
            targetLockTime = 0; lastOreMinedTime = 0;
            breakingPos = null; breakTicks = 0;
            mc.interactionManager.cancelBlockBreaking();
            notify("无法接近,黑名单3分钟");
            return;
        }

        // 分阶段处理
        switch (phase) {
            case WALK_XZ   -> { disableFlight(); walkXZPhase(); }
            case FLY_UP    -> { enableFlight(); flyUpPhase(); }
            case DIG_DOWN  -> { disableFlight(); digDownPhase(); }
            case MINE_ORE  -> { disableFlight(); mineOrePhase(); }
            case RETURN_TO_TUNNEL -> returnToTunnelPhase();
        }
    }

    /* ==================== 路径规划: 折线节点 + 寻路避障 ==================== */
    private void planWaypoints() {
        waypoints.clear();
        BlockPos from = mc.player.getBlockPos();
        BlockPos to = currentTarget;

        // 终点选相邻空气位置
        to = findAirAdjacent(to);

        tunnelY = from.getY(); // 记录隧道层

        // XZ 水平寻路(躲避危险)
        BlockPos hTarget = new BlockPos(to.getX(), tunnelY, to.getZ());
        List<BlockPos> hPath = findHorizontalPath(from, hTarget);
        // 过滤危险节点
        waypoints.addAll(hPath);
        waypoints.removeIf(wp -> isDangerZone(wp, tunnelY)); // 移除任何进入危险区的节点

        // 垂直差
        if (to.getY() != tunnelY) {
            waypoints.add(to); // 最终目标
        }

        if (!waypoints.isEmpty() && waypoints.get(0).equals(from))
            waypoints.remove(0); // 移除起点

        // 终检: 路径为空或无效则放弃
        if (waypoints.isEmpty()) {
            blacklistAndNext("无法规划安全路径");
            return;
        }

        wpIndex = 0;
    }

    /* ==================== A* 水平寻路 ==================== */

    /** 4方向 A*: 在XZ平面寻路, 躲避流体/洞穴, 输出折线节点 */
    private List<BlockPos> findHorizontalPath(BlockPos from, BlockPos to) {
        int y = from.getY();
        // 4方向: 北/南/西/东, 无对角线
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};

        Map<Long, Float> gScore = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();

        long startKey = key(from);
        long goalKey = key(to);
        gScore.put(startKey, 0f);
        open.offer(new Node(startKey, 0, heuristic(from, to)));

        int maxExpand = 6000, expanded = 0;
        long goalFound = -1;

        while (!open.isEmpty() && expanded < maxExpand) {
            Node cur = open.poll();
            if (cur.key == goalKey) { goalFound = cur.key; break; }
            expanded++;

            BlockPos cp = unkey(cur.key, y);
            // 跳过危险区(起点可豁免)
            if (!cp.equals(from) && isDangerZone(cp, y)) continue;

            for (int[] d : dirs) {
                BlockPos np = cp.add(d[0], 0, d[1]);
                long nk = key(np);
                if (isDangerZone(np, y)) continue;

                float tentativeG = gScore.getOrDefault(cur.key, Float.MAX_VALUE) + 1f;
                if (tentativeG < gScore.getOrDefault(nk, Float.MAX_VALUE)) {
                    gScore.put(nk, tentativeG);
                    parent.put(nk, cur.key);
                    open.offer(new Node(nk, tentativeG, tentativeG + heuristic(np, to)));
                }
            }
        }

        // 重构路径
        List<BlockPos> path = new ArrayList<>();
        if (goalFound < 0) {
            // A*失败 → 回退到直线(逐格检测危险)
            return fallbackPath(from, to, y);
        }

        long pKey = goalFound;
        while (pKey != startKey) {
            BlockPos bp = unkey(pKey, y);
            // 折线过滤: 只保留拐点
            path.add(bp);
            pKey = parent.getOrDefault(pKey, startKey);
        }
        path.add(from);
        Collections.reverse(path);

        // 折线压缩: 移除可直线到达的冗余节点
        return compressPath(path, y);
    }

    /** 折线压缩: 如果从 A 可以直接走到 C (无危险), 则删除中间节点 B */
    private List<BlockPos> compressPath(List<BlockPos> path, int y) {
        if (path.size() <= 2) return path;
        List<BlockPos> result = new ArrayList<>();
        result.add(path.get(0));
        int anchor = 0;
        for (int i = 2; i < path.size(); i++) {
            if (!canWalkStraight(path.get(anchor), path.get(i), y)) {
                result.add(path.get(i - 1));
                anchor = i - 1;
            }
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    /** 检查A到B之间的直线是否安全(单向,无危险区) */
    private boolean canWalkStraight(BlockPos a, BlockPos b, int y) {
        int dx = Integer.signum(b.getX() - a.getX());
        int dz = Integer.signum(b.getZ() - a.getZ());
        if (dx != 0 && dz != 0) return false; // 禁止对角线
        if (dx == 0 && dz == 0) return true;
        int steps = dx != 0 ? Math.abs(b.getX() - a.getX()) : Math.abs(b.getZ() - a.getZ());
        for (int i = 1; i <= steps; i++) {
            if (isDangerZone(a.add(dx * i, 0, dz * i), y)) return false;
        }
        return true;
    }

    /** A*失败时的回退: 逐格走直线+危险区跳过 */
    private List<BlockPos> fallbackPath(BlockPos from, BlockPos to, int y) {
        List<BlockPos> path = new ArrayList<>();
        path.add(from);
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int stepsX = Math.abs(to.getX() - from.getX());
        int stepsZ = Math.abs(to.getZ() - from.getZ());

        // 先X
        for (int i = 1; i <= stepsX; i++) {
            BlockPos p = from.add(dx * i, 0, 0);
            if (!isDangerZone(p, y)) path.add(p);
            else {
                // 尝试Z侧移绕行
                for (int s = 1; s <= 4; s++) {
                    BlockPos det = p.add(0, 0, s);
                    if (!isDangerZone(det, y)) { path.add(det); path.add(det.add(dx, 0, 0)); break; }
                    det = p.add(0, 0, -s);
                    if (!isDangerZone(det, y)) { path.add(det); path.add(det.add(dx, 0, 0)); break; }
                }
            }
        }
        BlockPos afterX = new BlockPos(to.getX(), y, from.getZ());
        // 再Z
        for (int i = 1; i <= stepsZ; i++) {
            BlockPos p = afterX.add(0, 0, dz * i);
            if (!isDangerZone(p, y)) path.add(p);
            else {
                for (int s = 1; s <= 4; s++) {
                    BlockPos det = p.add(s, 0, 0);
                    if (!isDangerZone(det, y)) { path.add(det); path.add(det.add(0, 0, dz)); break; }
                    det = p.add(-s, 0, 0);
                    if (!isDangerZone(det, y)) { path.add(det); path.add(det.add(0, 0, dz)); break; }
                }
            }
        }
        return path;
    }

    /** 检测2格高隧道是否进入危险区域(流体+可选洞穴) */
    private boolean isDangerZone(BlockPos pos, int yLevel) {
        int safeR = safeDistance.get();
        int minY = this.minY.get();
        boolean checkCaves = avoidCaves.get();
        for (int dx = -safeR; dx <= safeR; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -safeR; dz <= safeR; dz++) {
                    BlockPos check = pos.add(dx, yLevel + dy, dz);
                    if (check.getY() <= minY) continue;
                    BlockState s = mc.world.getBlockState(check);
                    if (isFluid(s)) return true;
                    if (checkCaves && s.isAir()) return true;
                }
        return false;
    }

    private long key(BlockPos p) { return ((long)p.getX() << 32) | (p.getZ() & 0xFFFFFFFFL); }
    private BlockPos unkey(long k, int y) { return new BlockPos((int)(k >> 32), y, (int)k); }
    private float heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }
    private static class Node implements Comparable<Node> {
        long key; float g, f;
        Node(long key, float g, float f) { this.key = key; this.g = g; this.f = f; }
        public int compareTo(Node o) { return Float.compare(f, o.f); }
    }

    private BlockPos findAirAdjacent(BlockPos ore) {
        // 找矿石周围最近的空气位置
        BlockPos best = ore;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = mc.player != null ? mc.player.getEyePos() : Vec3d.ZERO;
        for (Direction d : Direction.values()) {
            BlockPos adj = ore.offset(d);
            if (mc.world.getBlockState(adj).isAir() || isReplaceable(mc.world.getBlockState(adj))) {
                double dist = adj.getSquaredDistance(eye);
                if (dist < bestDist) { bestDist = dist; best = adj; }
            }
        }
        // 如果没有相邻空气，选最近的方向
        if (bestDist == Double.MAX_VALUE) {
            for (Direction d : Direction.values()) {
                double dist = ore.offset(d).getSquaredDistance(eye);
                if (dist < bestDist) { bestDist = dist; best = ore.offset(d); }
            }
        }
        return best;
    }

    /* ==================== 阶段1: 水平隧道挖掘 ==================== */
    private void walkXZPhase() {
        if (wpIndex >= waypoints.size()) {
            // 水平路径走完, 判断垂直方向
            int pY = mc.player.getBlockPos().getY();
            int tY = currentTarget.getY();
            if (tY > pY + 1) {
                phase = Phase.FLY_UP;
                enableFlight();
                return;
            }
            if (tY < pY - 1) {
                phase = Phase.DIG_DOWN;
                return;
            }
            // Y已对齐 → 直接挖矿
            phase = Phase.MINE_ORE;
            return;
        }

        BlockPos wp = waypoints.get(wpIndex);
        BlockPos feet = mc.player.getBlockPos();

        // 是否已到达当前节点 (X/Z对齐)
        boolean atZ = feet.getZ() == wp.getZ();
        boolean atX = feet.getX() == wp.getX();
        if (atX && atZ) {
            wpIndex++;
            // 检查下一个节点是否安全
            if (wpIndex < waypoints.size() && isDangerZone(waypoints.get(wpIndex), feet.getY())) {
                planWaypoints();
                if (waypoints.isEmpty() || wpIndex >= waypoints.size()) {
                    blacklistAndNext("下一节点不安全");
                    return;
                }
                wpIndex = 0;
            }
            return;
        }

        // 此段是X方向还是Z方向
        boolean xSeg = wp.getX() != feet.getX() && wp.getZ() == feet.getZ();
        boolean zSeg = wp.getZ() != feet.getZ() && wp.getX() == feet.getX();

        if (!xSeg && !zSeg) {
            wpIndex++; return; // 意外情况, 跳过
        }

        int stepX = xSeg ? Integer.signum(wp.getX() - feet.getX()) : 0;
        int stepZ = zSeg ? Integer.signum(wp.getZ() - feet.getZ()) : 0;
        Vec3d faceTarget = new Vec3d(feet.getX() + stepX + 0.5, feet.getY() + 0.5, feet.getZ() + stepZ + 0.5);

        // 朝向目标方向
        face(faceTarget);

        // Wurst TunnelHack: 检查前进方向2格高方块
        BlockPos frontLow = feet.add(stepX, 0, stepZ);
        BlockPos frontHigh = frontLow.up();

        if (frontLow.getY() <= minY.get() || frontHigh.getY() <= minY.get()) {
            blacklistAndNext("到达最低Y层限制"); return;
        }

        // 找到需要挖掘的方块 (Wurst: dig box in front)
        List<BlockPos> toDig = new ArrayList<>();
        if (isBlocking(mc.world.getBlockState(frontLow))) toDig.add(frontLow);
        if (isBlocking(mc.world.getBlockState(frontHigh))) toDig.add(frontHigh);

        // 安全检查: 下一步位置是否进入危险区
        boolean frontDanger = isDangerZone(frontLow, feet.getY());
        if (frontDanger) {
            // 是固体墙且危险 → 不挖, 重新规划
            if (isBlocking(mc.world.getBlockState(frontLow))) {
                blacklistBlock(frontLow);
            }
            planWaypoints();
            if (waypoints.isEmpty() || wpIndex >= waypoints.size()) {
                blacklistAndNext("路径进入危险区");
                return;
            }
            wpIndex = 0;
            mc.options.forwardKey.setPressed(false);
            return;
        }

        // Wurst: 如果已指定breakingPos, 继续挖它
        if (breakingPos != null) {
            toDig.clear();
            toDig.add(breakingPos);
        }

        if (!toDig.isEmpty()) {
            // 有方块需要挖 → 停止移动, 挖掘
            BlockPos dig = toDig.get(0); // 优先脚部再头部
            // 如果在挖第二个方块(top priority is the one closest)
            if (toDig.size() == 2) {
                double dLow = mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(frontLow));
                double dHigh = mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(frontHigh));
                dig = dLow < dHigh ? frontLow : frontHigh;
            }
            doDigBlock(dig);
        } else {
            // 无障碍 → 前进
            breakingPos = null;
            breakTicks = 0;
            mc.interactionManager.cancelBlockBreaking();
            mc.options.forwardKey.setPressed(true);
        }
    }

    /* ==================== 阶段2A: 竖井上升 (挖掘头顶到矿石之间的所有方块) ==================== */
    private void flyUpPhase() {
        BlockPos feet = mc.player.getBlockPos();
        int tY = currentTarget.getY();

        if (feet.getY() >= tY - 1) {
            disableFlight();
            phase = Phase.MINE_ORE;
            return;
        }

        // 挖掘从头顶到矿石之间的所有阻挡方块
        for (int dy = 1; dy <= (tY - feet.getY() + 1); dy++) {
            BlockPos check = new BlockPos(feet.getX(), feet.getY() + dy, feet.getZ());
            if (check.getY() <= minY.get()) { blacklistAndNext("到达最低Y层"); return; }
            if (isBlocking(mc.world.getBlockState(check))) {
                face(Vec3d.ofCenter(check));
                doDigBlock(check);
                enableFlight(); // 飞行悬空挖掘
                return;
            }
        }

        // 无障碍 → 飞行上升
        enableFlight();
        Vec3d vel = mc.player.getVelocity();
        mc.player.setVelocity(vel.x, 0.5, vel.z);
    }

    /* ==================== 阶段2B: 竖井下降 (挖掘脚底到矿石之间的所有方块) ==================== */
    private void digDownPhase() {
        BlockPos feet = mc.player.getBlockPos();
        int tY = currentTarget.getY();

        if (feet.getY() <= tY + 1) {
            phase = Phase.MINE_ORE;
            return;
        }

        // 挖掘从脚底到矿石之间的所有阻挡方块
        for (int dy = 1; dy <= (feet.getY() - tY); dy++) {
            BlockPos check = new BlockPos(feet.getX(), feet.getY() - dy, feet.getZ());
            if (check.getY() <= minY.get()) { blacklistAndNext("到达最低Y层"); return; }
            if (isBlocking(mc.world.getBlockState(check))) {
                face(Vec3d.ofCenter(check));
                doDigBlock(check);
                return;
            }
        }

        // 无障碍 → 走下一步
        mc.options.forwardKey.setPressed(true);
        mc.options.sneakKey.setPressed(true);
    }

    /* ==================== 阶段3: 挖掘矿石 ==================== */
    private void mineOrePhase() {
        BlockPos ore = currentTarget;
        if (ore == null) { phase = Phase.IDLE; return; }

        BlockState s = mc.world.getBlockState(ore);
        if (!isTargetOre(s.getBlock()) || s.isAir()) {
            // 矿石已消失
            lastOreMinedTime = System.currentTimeMillis();
            currentTarget = null; waypoints.clear(); wpIndex = 0;
            targetLockTime = 0;
            breakingPos = null; breakTicks = 0;
            mc.interactionManager.cancelBlockBreaking();
            phase = Phase.RETURN_TO_TUNNEL;
            return;
        }

        // Wurst BlockBreaker: 如果已在挖掘同一矿石, 继续进度
        if (breakingPos != null && breakingPos.equals(ore)) {
            if (breakTicks++ > 200) { blacklistBlock(ore); breakingPos = null; breakTicks = 0; mc.interactionManager.cancelBlockBreaking(); planWaypoints(); return; }
            mc.interactionManager.updateBlockBreakingProgress(ore, bestFace(ore));
            face(Vec3d.ofCenter(ore));
            return;
        }

        // 开始新挖掘
        face(Vec3d.ofCenter(ore));
        switchToPick();
        doDigBlock(ore);
    }

    /* ==================== 阶段4: 返回隧道层 ==================== */
    private void returnToTunnelPhase() {
        int pY = mc.player.getBlockPos().getY();

        if (Math.abs(pY - tunnelY) <= 1) {
            disableFlight();
            phase = Phase.IDLE;
            return;
        }

        if (pY < tunnelY) {
            // 需要上升 → 飞行
            enableFlight();
            Vec3d vel = mc.player.getVelocity();
            mc.player.setVelocity(vel.x, 0.5, vel.z);
        } else {
            // 需要下降 → 走台阶(不飞行)
            disableFlight();
            BlockPos below = mc.player.getBlockPos().down();
            if (isBlocking(mc.world.getBlockState(below))) {
                face(Vec3d.ofCenter(below));
                doDigBlock(below);
            } else {
                mc.options.forwardKey.setPressed(true);
            }
        }
    }

    /* ==================== 挖掘单个方块 (Wurst BlockBreaker 风格) ==================== */
    private void doDigBlock(BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        if (s.isAir() || isReplaceable(s) || isUnbreakable(s.getBlock()) || isFluid(s)) {
            breakingPos = null; breakTicks = 0;
            mc.interactionManager.cancelBlockBreaking();
            return;
        }

        // 危险区预检: 不挖靠近流体的方块
        if (isDangerZone(pos, pos.getY())) {
            blacklistBlock(pos);
            breakingPos = null; breakTicks = 0;
            mc.interactionManager.cancelBlockBreaking();
            waypoints.clear();
            planWaypoints();
            if (waypoints.size() > 0) wpIndex = 0;
            return;
        }

        // 切换挖掘目标时重置计数
        if (breakingPos != null && !breakingPos.equals(pos)) {
            breakTicks = 0;
            mc.interactionManager.cancelBlockBreaking();
        }
        breakingPos = pos;
        dugPositions.put(pos, System.currentTimeMillis() + 60_000L); // 1分钟后过期
        face(Vec3d.ofCenter(pos));
        switchToPick();

        // Wurst: 选择最佳面
        Direction bestSide = bestFace(pos);

        if (breakTicks > 300) {
            // 超时 → 跳过
            blacklistBlock(pos);
            breakingPos = null; breakTicks = 0;
            mc.interactionManager.cancelBlockBreaking();
            waypoints.clear(); wpIndex = 0;
            planWaypoints();
            if (!waypoints.isEmpty()) wpIndex = 1;
            return;
        }

        // 首帧 attackBlock 发起挖掘, 后续帧 updateProgress 推进
        if (breakTicks == 0) {
            mc.interactionManager.attackBlock(pos, bestSide);
        }
        mc.interactionManager.updateBlockBreakingProgress(pos, bestSide);
        mc.player.swingHand(Hand.MAIN_HAND);

        breakTicks++;
    }

    /* ==================== 卡住清理 ==================== */
    private void doClearing() {
        BlockPos pp = mc.player.getBlockPos();
        List<BlockPos> nearby = new ArrayList<>();
        int minYLev = minY.get();

        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos pos = pp.add(dx, dy, dz);
                    if (pos.getY() <= minYLev) continue;
                    BlockState s = mc.world.getBlockState(pos);
                    if (!s.isAir() && s.getBlock().getHardness() >= 0
                        && !isUnbreakable(s.getBlock()) && !isFluid(s))
                        nearby.add(pos);
                }

        if (nearby.isEmpty()) { isStuck = false; stuckTicks = 0; return; }

        nearby.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(mc.player.getEyePos())));
        BlockPos dig = nearby.get(0);
        face(Vec3d.ofCenter(dig));
        switchToPick();
        mc.interactionManager.updateBlockBreakingProgress(dig, bestFace(dig));
        mc.player.swingHand(Hand.MAIN_HAND);

        // 挖掉后卡住重置
        if (mc.world.getBlockState(dig).isAir()) { isStuck = false; stuckTicks = 0; }
    }

    /* ==================== 扫描 ==================== */
    private void scan() {
        World world = mc.world;
        BlockPos pp = mc.player.getBlockPos();
        int r = (int) Math.ceil(scanRange.get()), rSq = r * r;
        int minYLev = minY.get();
        boolean filterDanger = filterDangerOres.get();
        Mutable m = new Mutable();
        for (int dx = -r; dx <= r; dx++) {
            int dx2 = dx * dx;
            for (int dy = -r; dy <= r; dy++) {
                int dxy2 = dx2 + dy * dy;
                if (dxy2 > rSq) continue;
                for (int dz = -r; dz <= r; dz++) {
                    if (dxy2 + dz * dz > rSq) continue;
                    m.set(pp.getX() + dx, pp.getY() + dy, pp.getZ() + dz);
                    if (m.getY() <= minYLev) continue;
                    if (abandonBlacklist.containsKey(m)) continue;
                    if (dugPositions.containsKey(m)) continue;
                    BlockState s = world.getBlockState(m);
                    if (!isTargetOre(s.getBlock())) continue;
                    if (s.getHardness(world, m) < 0) continue;
                    if (filterDanger && isOreNearFluid(m)) continue;
                    orePositions.add(m.toImmutable());
                }
            }
        }
    }

    /** 检查矿物是否在流体安全距离内 */
    private boolean isOreNearFluid(BlockPos ore) {
        int safeR = safeDistance.get();
        for (int dx = -safeR; dx <= safeR; dx++)
            for (int dy = -safeR; dy <= safeR; dy++)
                for (int dz = -safeR; dz <= safeR; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos check = ore.add(dx, dy, dz);
                    if (isFluid(mc.world.getBlockState(check))) return true;
                }
        return false;
    }

    /* ==================== 矿石判定 ==================== */
    private boolean isTargetOre(Block b) {
        if (b == Blocks.COAL_ORE        || b == Blocks.DEEPSLATE_COAL_ORE)         return mineCoal.get();
        if (b == Blocks.IRON_ORE        || b == Blocks.DEEPSLATE_IRON_ORE)         return mineIron.get();
        if (b == Blocks.COPPER_ORE      || b == Blocks.DEEPSLATE_COPPER_ORE)       return mineCopper.get();
        if (b == Blocks.GOLD_ORE        || b == Blocks.DEEPSLATE_GOLD_ORE)         return mineGold.get();
        if (b == Blocks.REDSTONE_ORE    || b == Blocks.DEEPSLATE_REDSTONE_ORE)     return mineRedstone.get();
        if (b == Blocks.LAPIS_ORE       || b == Blocks.DEEPSLATE_LAPIS_ORE)        return mineLapis.get();
        if (b == Blocks.DIAMOND_ORE     || b == Blocks.DEEPSLATE_DIAMOND_ORE)      return mineDiamond.get();
        if (b == Blocks.EMERALD_ORE     || b == Blocks.DEEPSLATE_EMERALD_ORE)      return mineEmerald.get();
        if (b == Blocks.NETHER_QUARTZ_ORE) return mineNetherQuartz.get();
        if (b == Blocks.NETHER_GOLD_ORE)   return mineNetherGold.get();
        if (b == Blocks.ANCIENT_DEBRIS)     return mineAncientDebris.get();
        return false;
    }

    private boolean isUnbreakable(Block b) {
        return b == Blocks.BEDROCK || b == Blocks.BARRIER || b == Blocks.COMMAND_BLOCK
            || b == Blocks.CHAIN_COMMAND_BLOCK || b == Blocks.REPEATING_COMMAND_BLOCK
            || b == Blocks.STRUCTURE_BLOCK || b == Blocks.JIGSAW || b == Blocks.STRUCTURE_VOID;
    }

    /* ==================== 辅助 ==================== */
    private void face(Vec3d t) {
        Vec3d e = mc.player.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    /** Wurst: 选最佳攻击面 */
    private Direction bestFace(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d c = Vec3d.ofCenter(pos);
        Vec3d d = c.subtract(eye).normalize();
        double best = -2; Direction r = Direction.UP;
        for (Direction dir : Direction.values()) {
            double dot = d.dotProduct(Vec3d.of(dir.getVector()));
            if (dot > best) { best = dot; r = dir; }
        }
        return r;
    }

    private boolean isBlocking(BlockState s) {
        if (s.isAir() || s.isReplaceable() || isFluid(s)) return false;
        return s.isSolid() || s.getBlock().getHardness() >= 0;
    }

    private boolean isReplaceable(BlockState s) {
        return s.isReplaceable() || s.getBlock() instanceof TallFlowerBlock
            || s.getBlock() instanceof FlowerBlock;
    }

    private boolean isFluid(BlockState s) {
        return s.getFluidState().isIn(FluidTags.LAVA) || s.getFluidState().isIn(FluidTags.WATER);
    }

    private void blacklistAndNext(String reason) {
        if (currentTarget != null) {
            blacklistWithNeighbors(currentTarget);
            orePositions.remove(currentTarget);
        }
        currentTarget = null; waypoints.clear(); wpIndex = 0; phase = Phase.IDLE;
        targetLockTime = 0;
        breakingPos = null; breakTicks = 0;
        returnIfFlying();
        if (chatInfo.get()) notify(reason + ", 黑名单3分钟");
    }

    private void blacklistBlock(BlockPos pos) {
        abandonBlacklist.put(pos, System.currentTimeMillis() + 180_000L);
    }

    private void blacklistWithNeighbors(BlockPos center) {
        long expire = System.currentTimeMillis() + 180_000L;
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2) continue;
                    BlockPos pos = center.add(dx, dy, dz);
                    if (orePositions.contains(pos)) abandonBlacklist.put(pos, expire);
                }
    }

    private void enableFlight() {
        mc.player.getAbilities().flying = true;
        mc.player.getAbilities().allowFlying = true;
    }

    private void disableFlight() {
        if (!wasFlying) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().allowFlying = false;
        }
    }

    private void returnIfFlying() {
        if (phase == Phase.FLY_UP || phase == Phase.RETURN_TO_TUNNEL) phase = Phase.RETURN_TO_TUNNEL;
    }

    private void notify(String msg) {
        if (mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§8[§6矿物追踪§8] §f" + msg), true);
    }

    private void releaseControls() {
        mc.options.forwardKey.setPressed(false); mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false); mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false); mc.options.sneakKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
    }

    /* ==================== 工具切换 (Wurst AutoTool) ==================== */
    private void switchToPick() {
        if (isPickaxe(mc.player.getMainHandStack().getItem())) return;
        for (int i = 0; i < 9; i++)
            if (isPickaxe(mc.player.getInventory().getStack(i).getItem())) {
                setSelectedSlot(mc.player.getInventory(), i);
                return;
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

    private boolean isPickaxe(Item i) {
        return i == Items.WOODEN_PICKAXE || i == Items.STONE_PICKAXE || i == Items.IRON_PICKAXE
            || i == Items.GOLDEN_PICKAXE || i == Items.DIAMOND_PICKAXE || i == Items.NETHERITE_PICKAXE;
    }

    /* ==================== 渲染 ==================== */
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;

        // 所有矿石
        if (renderOres.get())
            for (BlockPos p : orePositions)
                if (isTargetOre(mc.world.getBlockState(p).getBlock()))
                    event.renderer.box(p, oreColor.get(), oreColor.get(), ShapeMode.Both, 0);

        // 当前目标
        if (currentTarget != null && isTargetOre(mc.world.getBlockState(currentTarget).getBlock()))
            event.renderer.box(currentTarget, targetColor.get(), targetColor.get(), ShapeMode.Both, 0);

        // 路径节点
        if (!waypoints.isEmpty()) {
            for (int i = wpIndex; i < waypoints.size(); i++) {
                BlockPos p = waypoints.get(i);
                event.renderer.box(p, pathColor.get(), pathColor.get(), ShapeMode.Lines, 0);
                if (i > 0) {
                    Vec3d a = Vec3d.ofCenter(waypoints.get(i - 1));
                    Vec3d b = Vec3d.ofCenter(p);
                    event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, pathColor.get());
                }
            }
        }
    }
}
