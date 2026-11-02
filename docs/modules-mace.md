# Mace / Spear Modules

Shared theory: teleport high above the target with `onGround=false` packets, drop back to
`targetY+0.5`, send `PlayerInteractEntityC2SPacket.attack`, then return. Fall distance accrued
between the high packet and the hit packet becomes mace bonus damage. All heights are
`StringListSetting` entries parsed by `Combat.parseHeights` (invalid/≤0 silently dropped).

> Headings marked *(unregistered)* still exist on disk, but their entrypoint lines are commented
> out in `MaceKillAddon.onInitialize()` (commit `728828c`) — they cannot be enabled in-game.

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

## MaceAura *(unregistered)* — `modules/MaceAura.java` (376 lines)

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
`macekill/BurstHeights.build` (fractional doubles) — `Drain Mode` `SPREAD` (even spread,
default), `LIST` (custom `Drain Heights`, extended upward to fill the burst), `INCREMENTAL`
(`Base Drain Height + i*Height Increment`); `Totems To Pop(1-198)` sets burst size
(+3 margin, capped at 198 hits). NOTE: it pops at most what the enemy actually holds —
~37 is a full legit inventory, so 64/198 only matters vs totem-refill cheats, and then
across several bursts, not one. `Detect Totem` shrinks the burst to their visible count
+3, so leave it on unless they stash totems out of hand. Big-burst tuning: `Height Step:
0.5`, `Move Step: 16-32` (fewer packets per hit — the #1 cause of bursts stalling partway
is the server dropping excess same-tick packets or rubberbanding an over-cap jump, after
which later hits land with no fall). `Sustained Drain(on)` + `Max Sustained Hits(40)`: if a
burst can't finish stacked totems (server mace-damage cap, dropped packets), the module arms
a chain on that target and keeps popping one full-height lethal single per 22 ticks (past
hurt invuln, no escalation needed) until death/exhaustion — this is what clears 20-stacks
and refill inventories that outlast any single burst. `Height Step(1.0, 0.25-2.0)` sets minimum separation —
0.5/0.25 packs a 198-burst into ~50-100 blocks instead of ~200 (fewer packets, fits low
roofs; server fall is float so escalation still holds). Ceiling is the pierce ceiling
(`Combat.worldTop - targetY`), so bursts work under caves/roofs; the drop column is
scanned once per burst and the attack Y is picked as the highest free-hitbox spot within
2.9 blocks of the target (fixed: old `ty+1.1` landed inside 2-high tunnel ceilings and the
server reset the fall). A packet estimate warns above ~1500 packets/tick (raise `Move Step`
for big bursts). Silent swap reverts
**immediately after the burst** (fixed 2026-09-25 — was deferred 3 ticks, desyncing
server/client slots). Durability: every landed hit costs 1 mace durability (vanilla),
so the burst warns when the mace can't survive it — keep `Detect Totem` on, set a sane
`Totems To Pop`, or put Unbreaking on the mace via `ItemGiver`. Core is
`attackOnce(target,height,primeFall)`: `findDropColumn` (nearest clear ±2.5-block column),
rotation pkt, `fallPackets` prime, `stepMove` up + `stepMoveDown` (block-skipping descent),
`sendAttack`. `returnToStart` damage-checks (`preHealth` vs now) and either stepped-return or
client snap. `getMaxHeightAbovePlayer` is dead code (pierce-through-roof replaced it).

## MaceAttect — `modules/MaceAttect.java` (1242 lines, sic spelling)

Settings: `Move Step(8)`, `Auto Switch(on)`, `Rotate(on)`, `Swing Hand(off)`, `Return to Start(on)`,
`Disable When Blocked(on)`, `Max Damage(on) + Attack Height(170)`, `Silent Swap(off)`,
`Fall Packets(4, 0-17)`, totem group `Totem Bypass(off)`, `Detect Totem(on)`,
`Drain Mode(SPREAD)`, `Drain Heights["4","8","12","16"]`, `Base Drain Height(4)`,
`Height Increment(4)`, `Totem Attacks(4)`, `Totems To Pop(24)`, `Height Step(1.0, 0.25-2.0)`,
`Sustained Drain(on)`, `Cave Safe(on)`, `Max Sustained Hits(40)`, hidden `Single Tick`/`Hits Per Tick`;
`Target{Players(on), Entities(off), Through Walls(off), Ignore Named(off), List Mode(Off), Player List("")}`.

Same smash as TpMace but **triggered, not auto-targeting**. Listens to `AttackEntityEvent`
(public `entity` field) and `PacketEvent.Send` attack packets — `PlayerInteractEntityC2SPacket.handle(Handler)`
detects the `ATTACK` variant and Meteor's `IPlayerInteractEntityC2SPacket.meteor$getEntity()` resolves
the target (the old yarn/intermediary field-name reflection is gone; `request(LivingEntity)` is kept
for other modules). Filters: `Players/Entities/Through Walls/Ignore Named/List Mode`.
`Disable When Blocked(on)` skips blocking+recent-damage, invulnerable, or creative targets.
Phases `IDLE → SMASH → RETURN_DELAY`. Totem burst, `Drain Mode` (`SPREAD`/`LIST`/`INCREMENTAL`),
pierce ceiling, packet estimate, and immediate silent revert are identical to TpMace
(fixed 2026-09-25). `runSmash`/`attackOnce`/`stepMove`/`stepMoveDown`/
`findDropColumn`/`returnToStart` are line-for-line cousins of TpMace.

**Cave Safe** (`Cave Safe(on)`, Totem group) is the MaceTotemFail port for low ceilings. Instead of
piercing to `worldTop`, it finds the highest hitbox-clear destination within 22 blocks (an air pocket
above a thin roof counts; a contiguous clear column is not required), sends `start → start+h → start`
all with `onGround=false`, then attacks. A landed mace hit resets the attacker's fall
(`MaceItem.postDamageEntity` → `onLanding`), so every hit needs a strictly greater fall:
`BurstHeights` supplies ascending one-climb heights in `[MIN_LETHAL=6, maxClear]`, and only heights
whose destination hitbox is clear are sent (a height inside the thin roof between the player and an
air pocket is skipped). When the built heights run out
(or `maxClear < 6`) the burst stacks `2..n` clear up/down cycles before each hit — a low cave needs
`ceil(6/maxClear)` cycles for the first lethal hit. The burst is capped at `MAX_CAVE_CYCLES=400`
(≈800 move packets) and warns when the cap cuts it short; `Sustained Drain` then continues with one
lethal cave single per 22 ticks. It warns once when `AntiPop` (or another NoFall) is inactive, because
the stacked fake fall is only safe if the final landing packet is stripped. Cave Safe still passes
through roof blocks between the two packet endpoints (positions, not collisions), so it remains
anticheat-visible; it just avoids landing inside a block and the huge world-top teleport.

## xintpmace *(unregistered)* — `modules/XinTpMace.java` (255 lines)

Slim variant. Settings: `Range`, `Predict Ticks(5)`, `Max Step(8)`, `Cooldown(10)`,
`Height List["10","20","30"]`, `Swing Hand`, `Players Only`, totem group (same shape).
`onTick`: cooldown gate → `findTarget` (nearest, no friend-list mode) →
`Targeting.predictPosition(...,true,predictTicks)` → `Movement.doTpTo(predicted)` →
for each height (clamped to `clearance`, skipped if `<5`): `doTpTo(jumpPos)` +
`sendMovePacket(attackPos)` + attack → `switchBack` → `doTpTo(originalPos)`.
Totem path builds `totemBypassHeights` like macemiss.

## MaceMissLite *(unregistered)* — `modules/MaceMissLite.java` (422 lines)

Settings: `Range`, `Move Step(8)`, `Kill Heights`, `Attack Delay(10)`, `Predict+Ticks`,
`Attack 1/2 Offset (dv1/dv2)`, `Swing Hand`, `Force Skip`, `Target{Players,Hostiles,Sort Priority,Auto Attack Entities}`,
`Armor Break + Threshold + Heights`, totem group, `Render Box + Box Color`.

Flow: `IDLE → DELAY`. `updateTarget` collects + sorts (`DISTANCE/HEALTH/ANGLE` inline comparator),
`isAboutToBeIgnored()` returns `forceIgnore` setting (manual skip switch),
`doAura` → totem (`Combat.getVclipClearance(mc, mc.player)` — **bug: uses player, not target**)
or armor (`isNaked` via `!needsArmorDestroy`) or kill list. `doTpAura` applies `dv1` to hit 0
and `dv2` to hit 1 via random-angle `applyDv`, then shared `Combat.findVclipHole` +
`Movement.doTpTo` + drop + return-to-`basePos` per hit. `Render3DEvent` draws target box.

## MaceDMG *(unregistered)* — `modules/MaceDMG.java` (214 lines)

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

## anti-air-miss *(unregistered)* — `modules/AntiAirMiss.java` (182 lines)

`detection-range(6)`, `vertical-offset(10)`. Every `TickEvent.Pre` with any non-friend alive
player in range: 10 rising + 10 falling + 1 restore `PositionAndOnGround` packets at the
same X/Z. No attack, no rotation, no slot work. ~21 packets/tick spam.
