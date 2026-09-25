# Architecture

## Entry point

`src/main/java/com/macekill/addon/MaceKillAddon.java`

- `extends MeteorAddon`, `LOG = "Qazr-Addons"`.
- `onRegisterCategories()` registers `CATEGORY = new Category("Qazr1234")`.
- `onInitialize()` adds every module via `Modules.get().add(new X())` grouped by comments
  (重锤战斗 / 长矛战斗 / 移动辅助 / 自动化 / 聊天/信息).
- `getPackage()` returns `"com.macekill.addon"` — Meteor uses it to scan settings/commands.
- Logs module count via `Modules.get().getGroup(CATEGORY).size()`.

To add a module: create class under `modules/`, then add one `Modules.get().add(...)` line here.
To add a HUD element: it must extend Meteor's `HudElement`, not `Module` (note:
`NearestPlayerHUD` is currently a `Module` that only abuses `getInfoString()`).

## Shared `macekill/*` helpers

| File | Type | Responsibility |
|------|------|----------------|
| `macekill/Config.java` | `record` | `(moveDistance, swingHand, autoTotem, syncClientPos, enableArmorDestroy, ignoreArmorValue, destroyHeights, killHeights)` — passed to `Combat.executeAttack` |
| `macekill/Combat.java` | static util | `executeAttack`, `findVclipHole`, `vclipSafe`, `needsArmorDestroy`, `parseHeights`, `getVclipClearance`, `totemBypassHeights`, dead `BypassRunner` |
| `macekill/Movement.java` | static util | `doTpTo`, `sendMovePacket`, `sendRotations`, `sendSlotPacket`, `attackEntity` |
| `macekill/Inventory.java` | static util + static `lastMaceSwapSlot` | `switchToMace`, `switchBack`, `findTotemSlot`, `ensureTotem`, reflection `selectedSlot` access |
| `macekill/Targeting.java` | static util | `findBestTarget(mc, range, filter, priority)`, `predictPosition`, `isSafeBlock` |
| `macekill/TargetFilter.java` | `record` | `(players, hostiles, animals, others)` + `accepts(LivingEntity)` |
| `macekill/SortPriority.java` | `enum` | `DISTANCE / ANGLE / HEALTH` |
| `utils/CreativeGiveUtil.java` | static util | `give(stack)` (empty-slot + `CreativeInventoryActionC2SPacket`), `warn/info/resetError` |

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

### Inventory switching (all mace modules)

1. If selected hotbar slot already holds the mace → keep.
2. Else if hotbar holds a mace → reflection `selectedSlot = i` + `UpdateSelectedSlotC2SPacket(i)`
   (or silent variant: send packet but keep client slot, revert later).
3. Else `InvUtils.find(MACE)` + `InvUtils.move().from(src).to(cur)` (async inventory click!),
   remember `maceSwapBackSlot` and move back on finish.

Reflection target is `PlayerInventory.selectedSlot` (`Inventory.java:103-108`), duplicated
in `MaceAura`, `TpMace`, `MaceAttect`, `MaceDMG`, `AutoMineModule`, `AutoShulkerBox`, `SpearKill`.

### Packet primitives used

- `PlayerMoveC2SPacket.PositionAndOnGround(x,y,z,onGround=false,false)` — teleport + fake fall.
- `PlayerMoveC2SPacket.LookAndOnGround(yaw,pitch,onGround,false)` — rotation / fall-prime.
- `PlayerInteractEntityC2SPacket.attack(target, sneaking)` — server-side hit.
- `UpdateSelectedSlotC2SPacket(slot)` — server slot sync.
- `CreativeInventoryActionC2SPacket(slot, stack)` — creative give.
- `PlayerActionC2SPacket(START/STOP_DESTROY_BLOCK)` + `PlayerInteractBlockC2SPacket` — miner/shulker.
- `clickSlot(syncId, slot, button, SlotActionType.SWAP, player)` — inventory moves.
- `sendChatMessage / sendChatCommand` — chat modules.

## Per-module event wiring

| Module | Listens to | Does on event |
|--------|------------|---------------|
| MaceKillModule | `TickEvent.Pre`, phases IDLE/START_DELAY/RETURN_DELAY | TP → delay → smash → return |
| MaceAura | `TickEvent.Pre` + `attackDelay` cooldown | nearest target → totem list or single height |
| TpMace | `TickEvent.Pre`, phases IDLE/DELAY/RETURN_DELAY | first-attack fast path, burst smash, stepped return |
| MaceAttect | `AttackEntityEvent`, `PacketEvent.Send` (attack pkt), `TickEvent.Pre` | `pendingTarget` → SMASH → RETURN_DELAY |
| MaceMissLite | `TickEvent.Pre` IDLE/DELAY, `Render3DEvent` | sorted target → dv-offset smash + box render |
| XinTpMace | `TickEvent.Pre` + `cooldownTicks` | predicted TP + clamped heights |
| MaceDMG | `TickEvent.Post` | cooldown-edge detect → fake-Y burst |
| MaceBreakerPro | `AttackEntityEvent` | cancel → axe hit → mace hit → restore |
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
| Rise/Speed/AutoRise/AntiAirMiss | `TickEvent.Pre` | packet bursts (see modules-other) |

## Resources

- `src/main/resources/fabric.mod.json` — id/version/entrypoint/mixins/deps.
- `src/main/resources/qazr-addons.mixins.json` — empty (`"client": []`, no mixins). Declared but does nothing.
- `src/main/resources/macekill.accesswidener` — single-line `accessWidener v2 named`, no rules.
- `src/main/resources/assets/macekill/icon.png` — mod icon.
