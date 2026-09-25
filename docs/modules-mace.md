# Mace / Spear Modules

Shared theory: teleport high above the target with `onGround=false` packets, drop back to
`targetY+0.5`, send `PlayerInteractEntityC2SPacket.attack`, then return. Fall distance accrued
between the high packet and the hit packet becomes mace bonus damage. All heights are
`StringListSetting` entries parsed by `Combat.parseHeights` (invalid/≤0 silently dropped).

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

## MaceAura — `modules/MaceAura.java` (376 lines)

Settings: `Range(20)`, `Players Only`, `Swing Arm`, `Attack Height(30)`, `Spam Packets(2)`,
`Attack Delay(10)`, `Use Offset + Horizontal(1.5) + Vertical(0.5)`,
`Totem Bypass + Totem Attacks(3) + Height Increment(2)`.

Differences: own `positionCache: Map<BlockPos,Boolean>` + `BlockPos.Mutable` scan
(`getMaxHeightAbovePlayer`, `isSafePosition` allows air/leaves, rejects fluid/cobweb);
`sendMovePacketWithSpam` splits moves into ≤8-block steps and duplicates each `spam` times;
`attackCount`/`heightIncrement` totem math delegates to `Combat.totemBypassHeights(count-1, clearance)`.
Own reflection slot helpers + `InvUtils.move` swap-back. `Friends.shouldAttack` filter.

## TpMace — `modules/TpMace.java` (864 lines)

Settings: `Range(20)`, `Move Step(8)`, `Fall Packets(4)`, `Attack Delay(10)`, `Auto Switch`,
`Rotate`, `Swing Hand`, `Return to Start`, `Max Damage + Attack Height(170)`, `Air Check`
(unused — dead setting), `Silent Swap`, totem group (same shape as macemiss),
`Target{Players,Entities,Through Walls,Ignore Named,List Mode,Player List}`.

Flow: `IDLE → DELAY(3t) → RETURN_DELAY(3t)`. `tickIdle` respects `firstAttack` fast path,
`freeCooldown` after unhittable, `checkAndSwapWeapon` (3-way: held/hotbar/inventory-swap),
then single-burst totem smash or single `getAttackHeight()` hit. Totem heights come from
`macekill/BurstHeights.build` — `Drain Mode` `SPREAD` (even spread, default), `LIST`
(custom `Drain Heights`, extended upward to fill the burst), `INCREMENTAL`
(`Base Drain Height + i*Height Increment`); `Totems To Pop(1-198)` sets burst size
(+3 margin, capped at 198 hits). Ceiling is the pierce ceiling
(`Combat.worldTop - targetY`), so bursts work under caves/roofs; a packet estimate warns
above ~1500 packets/tick (raise `Move Step` for big bursts). Silent swap reverts
**immediately after the burst** (fixed 2026-09-25 — was deferred 3 ticks, desyncing
server/client slots). Durability: every landed hit costs 1 mace durability (vanilla),
so the burst warns when the mace can't survive it — keep `Detect Totem` on, set a sane
`Totems To Pop`, or put Unbreaking on the mace via `ItemGiver`. Core is
`attackOnce(target,height,primeFall)`: `findDropColumn` (nearest clear ±2.5-block column),
rotation pkt, `fallPackets` prime, `stepMove` up + `stepMoveDown` (block-skipping descent),
`sendAttack`. `returnToStart` damage-checks (`preHealth` vs now) and either stepped-return or
client snap. `getMaxHeightAbovePlayer` is dead code (pierce-through-roof replaced it).

## MaceAttect — `modules/MaceAttect.java` (892 lines, sic spelling)

Same smash as TpMace but **triggered, not auto-targeting**. Listens to `AttackEntityEvent`
(reflective `entity` field), `PacketEvent.Send` attack packets (yarn+intermediary field names
`type/field_12871`, `ATTACK/field_29170`, `entityId/field_12870`), plus static
`request(LivingEntity)` for other modules. Filters: `Players/Entities/Through Walls/Ignore Named/List Mode`.
Phases `IDLE → SMASH → RETURN_DELAY`. Totem burst, `Drain Mode` (`SPREAD`/`LIST`/`INCREMENTAL`),
pierce ceiling, packet estimate, and immediate silent revert are identical to TpMace
(fixed 2026-09-25). `runSmash`/`attackOnce`/`stepMove`/`stepMoveDown`/
`findDropColumn`/`returnToStart` are line-for-line cousins of TpMace.

## xintpmace — `modules/XinTpMace.java` (255 lines)

Slim variant. Settings: `Range`, `Predict Ticks(5)`, `Max Step(8)`, `Cooldown(10)`,
`Height List["10","20","30"]`, `Swing Hand`, `Players Only`, totem group (same shape).
`onTick`: cooldown gate → `findTarget` (nearest, no friend-list mode) →
`Targeting.predictPosition(...,true,predictTicks)` → `Movement.doTpTo(predicted)` →
for each height (clamped to `clearance`, skipped if `<5`): `doTpTo(jumpPos)` +
`sendMovePacket(attackPos)` + attack → `switchBack` → `doTpTo(originalPos)`.
Totem path builds `totemBypassHeights` like macemiss.

## MaceMissLite — `modules/MaceMissLite.java` (422 lines)

Settings: `Range`, `Move Step(8)`, `Kill Heights`, `Attack Delay(10)`, `Predict+Ticks`,
`Attack 1/2 Offset (dv1/dv2)`, `Swing Hand`, `Force Skip`, `Target{Players,Hostiles,Sort Priority,Auto Attack Entities}`,
`Armor Break + Threshold + Heights`, totem group, `Render Box + Box Color`.

Flow: `IDLE → DELAY`. `updateTarget` collects + sorts (`DISTANCE/HEALTH/ANGLE` inline comparator),
`isAboutToBeIgnored()` returns `forceIgnore` setting (manual skip switch),
`doAura` → totem (`Combat.getVclipClearance(mc, mc.player)` — **bug: uses player, not target**)
or armor (`isNaked` via `!needsArmorDestroy`) or kill list. `doTpAura` applies `dv1` to hit 0
and `dv2` to hit 1 via random-angle `applyDv`, then shared `Combat.findVclipHole` +
`Movement.doTpTo` + drop + return-to-`basePos` per hit. `Render3DEvent` draws target box.

## MaceDMG — `modules/MaceDMG.java` (214 lines)

Wurst-style fake height. Settings: `Fake Height(22.0 blocks)` (fixed 2026-09-25 — was misleading
sqrt `22.36`), `Attack Interval(55ms)`, `Global Attack Detect`, `Auto Switch Mace`,
`Normal Packets(4)`, `Chat Info`. `onTick(Post)`: auto-switch, then edge-detect
(`lastCooldown>0.5 && current<0.01` for global, `attackKey.wasPressed()` for manual),
rate-limit, `performMaceDMG`: N× `sendFakeY(0,onGround=true)` + 1× `sendFakeY_air(sqrt(h),onGround=false)` +
`fallDistance=0`. No target search — buffs your own hits.

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

## anti-air-miss — `modules/AntiAirMiss.java` (182 lines)

`detection-range(6)`, `vertical-offset(10)`. Every `TickEvent.Pre` with any non-friend alive
player in range: 10 rising + 10 falling + 1 restore `PositionAndOnGround` packets at the
same X/Z. No attack, no rotation, no slot work. ~21 packets/tick spam.
