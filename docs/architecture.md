# Architecture

## Entry point

`src/main/java/com/macekill/addon/MaceKillAddon.java`

- `extends MeteorAddon`, `LOG = "Qazr-Addons"`.
- `onRegisterCategories()` registers `CATEGORY = new Category("Qazr1234")`.
- `onInitialize()` adds 26 registered modules via `Modules.get().add(new X())` grouped by comments
  (重锤战斗 / 长矛战斗 / 移动辅助 / 自动化 / 聊天/信息); five classes remain unregistered.
- `getPackage()` returns `"com.macekill.addon"` — Meteor uses it to scan settings/commands.
- Logs module count via `Modules.get().getGroup(CATEGORY).size()`.

To add a module: create class under `modules/`, then add one `Modules.get().add(...)` line here.
To add a HUD element: it must extend Meteor's `HudElement`, not `Module` (note:
`NearestPlayerHUD` is currently a `Module` that only abuses `getInfoString()`).

## Shared `macekill/*` helpers

| File | Type | Responsibility |
|------|------|----------------|
| `macekill/Config.java` | `record` | `(moveDistance, swingHand, autoTotem, syncClientPos, enableArmorDestroy, ignoreArmorValue, destroyHeights, killHeights)` — passed to `Combat.executeAttack` |
| `macekill/Combat.java` | static util | `executeAttack`, `findVclipHole`, `vclipSafe`, `needsArmorDestroy`, `parseHeights`, `getVclipClearance`, `worldTop`, `totemBypassHeights`, dead `BypassRunner` |
| `macekill/BurstHeights.java` | static util | Single-burst totem lists `SPREAD/LIST/INCREMENTAL` (`build`), pierce ceiling, 198 cap, strictly increasing |
| `macekill/Movement.java` | static util | `doTpTo`, `sendMovePacket`, `sendRotations`, `sendSlotPacket`, `attackEntity` |
| `macekill/Inventory.java` | static util + static `lastMaceSwapSlot` | `switchToMace`, `switchBack`, `findTotemSlot`, `ensureTotem`, reflection `selectedSlot` access |
| `macekill/Targeting.java` | static util | `findBestTarget(mc, range, filter, priority)`, `predictPosition`, `isSafeBlock` |
| `macekill/TargetFilter.java` | `record` | `(players, hostiles, animals, others)` + `accepts(LivingEntity)` |
| `macekill/SortPriority.java` | `enum` | `DISTANCE / ANGLE / HEALTH` |
| `utils/CreativeGiveUtil.java` | static util | `give(stack)` (empty-slot + `CreativeInventoryActionC2SPacket`), `warn/info/resetError` |
| `utils/Attack.java` | static util | `attack(target, useMace, attackHeight, autoEquipMace)` — teleport in, optional up/down mace path, attack packet, teleport back (brew-addon port) |
| `utils/Movement.java` | static util | `teleport` / `execute` / `calculatePackets` / `sendPackets` — `PositionAndOnGround` packet teleports, 10 blocks per filler packet (brew-addon port) |
| `utils/HitResults.java` | static util | `getCrosshairTarget(entity, range, ignoreBlocks, filter)` — camera raycast with custom reach (brew-addon port) |

### Canonical combat flow (MaceKillModule / XinTpMace / MaceMissLite)

```
TickEvent.Pre → find target (Targeting / inline loop)
             → Targeting.predictPosition (XZ velocity × predictTicks)
             → Movement.doTpTo(predicted)
             → for each height h:
                   Combat.findVclipHole(playerX/Y/Z, h) → Vec3d.ofBottomCenter(hole)
                   Movement.doTpTo(vclipPos)
                   Movement.sendMovePacket(targetX, targetY+0.5, targetZ)
                   Movement.sendMovePacket(basePos)   // fake return so fall accrues
                   swing? + Movement.attackEntity(target)
             → Inventory.switchBack
             → Movement.doTpTo(originalPos)
```

`TpMace` / `MaceAttect` do **not** use this flow — they have their own
`stepMove` / `stepMoveDown` / `findDropColumn` / `checkAndSwapWeapon` copies
(~400 duplicated lines each). See `problems.md` § duplication.

### Totem-bypass theory (as coded)

`Combat.totemBypassHeights(totemCount, maxHeight)` builds `totemCount+1` strictly-increasing
integer heights spread over `[6, avail]` (`Combat.java:160-181`). Rationale in comments:
each successive mace hit must deal strictly more damage to beat `hurtResistantTime`
invuln, and the whole list must fire in **one tick / one smash** to beat enemy AutoTotem
re-equip. Callers add `+3` margin (`Math.min(totems+3, MAX_TOTEM_HITS=198)`).

`MaceAttect`'s **Cave Safe** branch applies the same strict-increase rule to MaceTotemFail's
capped cave movement. It scans for the highest hitbox-clear height within 22 blocks (a
destination scan, so an air pocket above a thin roof qualifies), sends one up/down cycle per
hit, and uses `BurstHeights` heights in `[6, maxClear]`, skipping built heights whose
destination hitbox is not clear (an air pocket above a thin roof is reachable, the roof
between is not a valid endpoint). Because `MaceItem.postDamageEntity`
calls `onLanding()` after every landed smash, the attacker's fall resets per hit; when the
available distinct heights run out (or the cave is lower than 6 blocks) the branch stacks
`2..n` up/down cycles before each hit to rebuild a larger fall. `MAX_CAVE_CYCLES=400` caps one
tick (~800 move packets); `Sustained Drain` continues afterwards.

### Inventory switching (all mace modules)

1. If selected hotbar slot already holds the mace → keep.
2. Else if hotbar holds a mace → reflection `selectedSlot = i` + `UpdateSelectedSlotC2SPacket(i)`
   (or silent variant: send packet but keep client slot, revert later).
3. Else `InvUtils.find(MACE)` + `InvUtils.move().from(src).to(cur)` (async inventory click!),
   remember `maceSwapBackSlot` and move back on finish.

Reflection target was `PlayerInventory.selectedSlot` (`Inventory.java:103-108`), duplicated
in `MaceAura`, `TpMace`, `MaceAttect`, `MaceDMG`, `AutoMineModule`, `AutoShulkerBox`, `SpearKill`.
FIXED 2026-09-25: all copies now use the public `getSelectedSlot()/setSelectedSlot()` API —
the Yarn-name reflection never resolved at runtime (intermediary), silently breaking slot
reverts (root cause of the silent-swap bug, see `problems.md` §3).

### Packet primitives used

- `PlayerMoveC2SPacket.PositionAndOnGround(x,y,z,onGround=false,false)` — teleport + fake fall.
- `PlayerMoveC2SPacket.LookAndOnGround(yaw,pitch,onGround,false)` — rotation / fall-prime.
- `PlayerMoveC2SPacket.Full` / `PositionAndOnGround` / `LookAndOnGround` / `OnGroundOnly` — AntiPop
  rebuilds the same subtype with `onGround=false` (1.21.11 made the packet fields `final`, so no accessor mixin).
- `PlayerInteractEntityC2SPacket.attack(target, sneaking)` — server-side hit.
- `PlayerInteractItemC2SPacket(hand, sequence, yaw, pitch)` — bow/trident use (ArrowKiller re-sends it at the teleported position).
- `PlayerActionC2SPacket(RELEASE_USE_ITEM)` — bow release (ArrowKiller cancels/replays it at the target).
- `VehicleMoveC2SPacket.fromVehicle(entity)` — ArrowKiller's movement/teleport packet when riding.
- `UpdateSelectedSlotC2SPacket(slot)` — server slot sync.
- `CreativeInventoryActionC2SPacket(slot, stack)` — creative give.
- `PlayerActionC2SPacket(START/STOP_DESTROY_BLOCK)` + `PlayerInteractBlockC2SPacket` — miner/shulker.
- `clickSlot(syncId, slot, button, SlotActionType.SWAP, player)` — inventory moves.
- `sendChatMessage / sendChatCommand` — chat modules.

## Per-module event wiring

| Module | Listens to | Does on event |
|--------|------------|---------------|
| MaceKillModule | `TickEvent.Pre`, phases IDLE/START_DELAY/RETURN_DELAY | TP → delay → smash → return |
| MaceAura *(unregistered)* | `TickEvent.Pre` + `attackDelay` cooldown | nearest target → totem list or single height |
| TpMace | `TickEvent.Pre`, phases IDLE/DELAY/RETURN_DELAY | first-attack fast path, burst smash, stepped return |
| MaceAttect | `AttackEntityEvent`, `PacketEvent.Send` (public `handle`/`meteor$getEntity`), `TickEvent.Pre` | `pendingTarget` → SMASH (pierce or Cave Safe) → RETURN_DELAY |
| MaceMissLite *(unregistered)* | `TickEvent.Pre` IDLE/DELAY, `Render3DEvent` | sorted target → dv-offset smash + box render |
| XinTpMace *(unregistered)* | `TickEvent.Pre` + `cooldownTicks` | predicted TP + clamped heights |
| MaceDMG *(unregistered)* | `TickEvent.Post` | cooldown-edge detect → fake-Y burst |
| MaceBreakerPro | `AttackEntityEvent` | cancel → axe hit → mace hit → restore |
| UltraAura | `TickEvent.Pre` (target list), `TickEvent.Post` (attack timer) | closest-angle target → packet TP in → attack → TP back |
| ArrowKiller | `TickEvent.Pre`/`Post`, `PacketEvent.Send` (priority 200), `Render3DEvent` | predict target / charge bow; intercept use/release → TP to target → replay → TP back; optional boxes |
| InfiniteReach | `TickEvent.Post`, `Render3DEvent` + attack/use key edges | entity raycast (if `entities`) → attack; else block raycast (if `blocks`) → interact/break; hover box for far blocks |
| AntiPop | `PacketEvent.Send` (move packets) | rebuild packet with `onGround=false` and resend |
| SpearKill Blink | `TickEvent.Pre` + `PacketEvent.Send` (move pkts) | queue moves → flush on charge |
| SpearKill Lunge | `TickEvent.Pre` | VClip above → charge → release |
| AutoMineModule | `TickEvent.Post` + `Render3DEvent` | scan → A* plan → WALK_XZ/FLY_UP/DIG_DOWN/MINE_ORE/RETURN |
| AutoShulkerBox | `TickEvent.Pre` | air → place; shulker → insta-mine |
| ItemGiver/PotionGiver | `onActivate` + `TickEvent.Post` (if continuous) | build stack → `CreativeGiveUtil.give` |
| Hitback | `PacketEvent.Receive(EntityDamageS2C)` + `TickEvent.Pre` (cd) | `Rotations.rotate` → attack pkt |
| AutoGG | `TickEvent.Post` (scan+delay), `PacketEvent.Send/Receive` | death-msg match → delayed GG |
| AutoMsg | `TickEvent.Post`, `PacketEvent.Receive(GameMessageS2C)` | interval/msg-count trigger → chat/cmd |
| MassTpa | `TickEvent.Pre` | one `tpa <name>` per `Delay` ticks |
| FreecamTp | `MouseClickEvent` (right) | teleport to `Freecam.pos` |
| Rise/Speed/AutoRise | `TickEvent.Pre` | packet bursts (see modules-other) |
| b-anti-creeper | `TickEvent.Post` (OnExplode), `EntityAddedEvent` (OnSpawn) | in-range creeper → `Attack.attack(creeper, mace, 10, equip)` |
| PlayerTp | one-shot `onActivate` | nearest player → `Movement.doTpTo` → auto-disable |
| AdvancedNoFall *(unregistered)* | `TickEvent.Pre` | client-fall trigger → `onGround=true` spoof |
| TpCommand (`.tp`) | Brigadier execute (client thread) | `PlayerTp.teleportTo()` (module need not be active) |

## Resources

- `src/main/resources/fabric.mod.json` — id/version/entrypoint/mixins/deps.
- `src/main/resources/qazr-addons.mixins.json` — empty (`"client": []`, no mixins). Declared but does nothing.
- `src/main/resources/macekill.accesswidener` — single-line `accessWidener v2 named`, no rules.
- `src/main/resources/assets/macekill/icon.png` — mod icon.
