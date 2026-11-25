# Mace / Spear Modules

Shared theory: teleport high above the target with `onGround=false` packets, drop back to
`targetY+0.5`, send `ServerboundAttackPacket(entityId)`, then return. Fall distance accrued
between the high packet and the hit packet becomes mace bonus damage. All heights are
`StringListSetting` entries parsed by `Combat.parseHeights` (invalid/≤0 silently dropped).

> The bundled `MaceMissLite`, `XinTpMace`, `TpMace`, `MaceDMG`, `AntiAirMiss`, `MaceAura`,
> `AutoRise` and `AutoTotemToHotbar` module classes were removed entirely (most on 2026-11-18,
> `MaceAura` on 2026-11-25; see `MaceKillAddon.java`). `AdvancedNoFall` remains on disk with its
> registration commented out and cannot be enabled in-game.

## macemiss — `modules/MaceKillModule.java` (479 lines)

Settings: `Range(20)`, `Move Step(20)`, `Swing Hand`, `Require Full Cooldown`, `Teleport Delay(5)`,
`Spam Rotations`, `Auto Totem`, `Sync Client Position`, `Predict Position+Ticks(5)`,
`Armor Break + Threshold`, `Targeting{Players,Hostiles,Animals,Others,Priority}`,
`Armor Break Heights["30","60"]`, `Kill Heights["10","20","30"]`,
`Totem Bypass + Detect Totem + Drain Mode + Drain Heights + Base/Inc + Totem Attacks + Totems To Pop(24)`,
hidden `Single Tick`, hidden `Hits Per Tick`.

Flow: `onTick IDLE → tickIdle()` finds `Targeting.findBestTarget`, saves `originalPos`,
`Movement.doTpTo(targetPos)`, waits `teleportDelay` (re-TP each tick), then `executeAndReturn()`.
Totem path ignores all drain settings and builds `Combat.totemBypassHeights(min(countTotems,+3), clearance)`.
`doReturn()` always steps back to `originalPos`.

## Smasher — `modules/Smasher.java` (894 lines)

Settings: `Move Step(8)`, `Auto Switch(on)`, `Rotate(on)`, `Swing Hand(off)`, `Return to Start(on)`,
`Disable When Blocked(on)`, `Max Damage(on) + Attack Height(170)`, `Silent Swap(off)`,
`Fall Packets(4, 0-17)`, totem group `Totem Bypass(off)`, `Detect Totem(on)`,
`Drain Mode(SPREAD)`, `Drain Heights["4","8","12","16"]`, `Base Drain Height(4)`,
`Height Increment(4)`, `Totem Attacks(4)`, `Totems To Pop(24)`, `Height Step(1.0, 0.25-2.0)`,
hidden `Single Tick`/`Hits Per Tick`;
`Target{Players(on), Entities(off), Through Walls(off), Ignore Named(off), List Mode(Off), Player List("")}`.

Same triggered (not auto-targeting) smash the deleted `TpMace` shared, but self-contained. Listens to `AttackEntityEvent`
(public `entity` field) and `PacketEvent.Send` for `ServerboundAttackPacket` — 26.x sends attacks as
their own packet carrying the entity id, so `pkt.entityId()` resolves the target (no reflection or
Meteor mixin accessor; `request(LivingEntity)` is kept for other modules). Filters:
`Players/Entities/Through Walls/Ignore Named/List Mode`.
`Disable When Blocked(on)` skips blocking+recent-damage, invulnerable, or creative targets.
Phases `IDLE → SMASH → RETURN_DELAY`. Totem burst, `Drain Mode` (`SPREAD`/`LIST`/`INCREMENTAL`),
pierce ceiling, packet estimate, and immediate silent revert are the same implementation the
former `TpMace` carried (fixed 2026-09-25). `runSmash`/`attackOnce`/`stepMove`/`stepMoveDown`/
`findDropColumn`/`returnToStart` are self-contained here (written as cousins of the former
TpMace). The burst path pierces any roof (world top is the only limit), so it works in caves
without a separate cave mode.

`Cave Safe` and `Sustained Drain` were removed on 2026-11-25 together with their
`macekill/CaveBurst.java` planner and `caveBurstSimulation` proof harness. The strictly
increasing single-burst list is the only totem-bypass implementation: AutoTotem re-equips
between ticks, so the whole list must land in one tick / one mace smash. Remaining totems
above `Totems To Pop` are not chased automatically.

## MaceBreakerPro — `modules/MaceBreakerPro.java` (159 lines)

Shield-break combo. Settings: `Only Blocking`, `Sword/Axe/Mace Trigger`, `Auto Return`.
`onAttackEntity`: skip unless `target.isBlocking()` (if set) and held item name matches an
enabled trigger (string `contains("sword"/"_axe"/"mace")` — fragile); `event.setCancelled(true)`;
axe (hotbar-only scan) → `switchTo+attack`; mace (`InvUtils.find`) → move-in if needed →
`switchTo+attack`; restore `originalSlot`; `swingHand`. Both hits same tick.

## SpearKill — `modules/SpearKill.java` (496 lines)

7 spear variants (`WOODEN..NETHERITE_SPEAR`). `Mode{Blink,Lunge}`, `Range(50)`, `NoFall`
(dead — never applied), Blink{`Flush Distance(2)`,`Max Blink Distance(25)`,`Distance Boost(2)`},
Lunge{`Direction(FromAbove)`,`Above Height(10)`,`Strength(1.5)`,`Pause Before Hit+Stop Distance(3)`},
`Target{List Mode(Off/Whitelist),Target List,Ignore Friends}`.

- **Blink**: `startBlink` records `startPos`, `onSendPacket` queues every `PlayerMoveC2SPacket`
  and cancels it; walk locally until `dist ≤ flushRange` → `switchToSpear` + hold `useKey` →
  on full charge (`getItemUseTime() ≥ 10`) release + `flushPackets` (yaw/pitch + bulk send) + attack.
  Timeout past `maxDist` cancels. `onDeactivate` flushes.
- **Lunge**: switch to spear, VClip above (`findAbovePos` downward scan for 2-air),
  pitch −90, hold `useKey`, on full charge release + extra through-target move pkt
  (`strength-1 × 2` blocks).

