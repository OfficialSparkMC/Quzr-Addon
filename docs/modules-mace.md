# Mace / Spear Modules

Shared theory: teleport high above the target with `onGround=false` packets, drop back to
`targetY+0.5`, send `PlayerInteractEntityC2SPacket.attack`, then return. Fall distance accrued
between the high packet and the hit packet becomes mace bonus damage. All heights are
`StringListSetting` entries parsed by `Combat.parseHeights` (invalid/≤0 silently dropped).

> The bundled `MaceMissLite`, `XinTpMace`, `TpMace`, `MaceDMG` and `AntiAirMiss` module classes were
> removed entirely on 2026-11-18 (see `MaceKillAddon.java`). `MaceAura` remains on disk as the only
> Mace-group class with its registration commented out — it cannot be enabled in-game.

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

## MaceAttect — `modules/MaceAttect.java` (1269 lines, sic spelling)

Settings: `Move Step(8)`, `Auto Switch(on)`, `Rotate(on)`, `Swing Hand(off)`, `Return to Start(on)`,
`Disable When Blocked(on)`, `Max Damage(on) + Attack Height(170)`, `Silent Swap(off)`,
`Fall Packets(4, 0-17)`, totem group `Totem Bypass(off)`, `Detect Totem(on)`,
`Drain Mode(SPREAD)`, `Drain Heights["4","8","12","16"]`, `Base Drain Height(4)`,
`Height Increment(4)`, `Totem Attacks(4)`, `Totems To Pop(24)`, `Height Step(1.0, 0.25-2.0)`,
`Sustained Drain(on)`, `Cave Safe(on)`, `Max Sustained Hits(40)`, hidden `Single Tick`/`Hits Per Tick`;
`Target{Players(on), Entities(off), Through Walls(off), Ignore Named(off), List Mode(Off), Player List("")}`.

Same triggered (not auto-targeting) smash the deleted `TpMace` shared, but self-contained. Listens to `AttackEntityEvent`
(public `entity` field) and `PacketEvent.Send` attack packets — `PlayerInteractEntityC2SPacket.handle(Handler)`
detects the `ATTACK` variant and Meteor's `IPlayerInteractEntityC2SPacket.meteor$getEntity()` resolves
the target (the old yarn/intermediary field-name reflection is gone; `request(LivingEntity)` is kept
for other modules). Filters: `Players/Entities/Through Walls/Ignore Named/List Mode`.
`Disable When Blocked(on)` skips blocking+recent-damage, invulnerable, or creative targets.
Phases `IDLE → SMASH → RETURN_DELAY`. Totem burst, `Drain Mode` (`SPREAD`/`LIST`/`INCREMENTAL`),
pierce ceiling, packet estimate, and immediate silent revert are the same implementation the
former `TpMace` carried (fixed 2026-09-25). `runSmash`/`attackOnce`/`stepMove`/`stepMoveDown`/
`findDropColumn`/`returnToStart` are self-contained here (written as cousins of the former
TpMace), except the Cave Safe branch, which uses `CaveBurst` + `caveLift`/`caveClearance`/`caveClimb`.

**Cave Safe** (`Cave Safe(on)`, Totem group) is the MaceTotemFail port for low ceilings, corrected
for 1.21.11. It scans **destinations** from `CaveBurst.MAX_LIFT=22` downwards and picks the highest
collision-clear endpoint. The server accepts a movement packet when the destination hitbox is clear
(`isEntityNotCollidingWithBlocks`) and discards vertical moved-wrongly error via the tautological
`if (m > -0.5 || m < 0.5) m = 0.0;`, so the climb may pass through a cave roof and finish in an air
pocket above it. Contiguous clearance is only the fallback when no higher endpoint exists. Each
climb sends four no-op movement packets first (the last may carry the rotation), making the ascent
packet 5 of the tick and raising the displacement cap to `sqrt(500)=22.36` blocks; then
`start → start+lift → start` with `onGround=false`, then attack from the start position. This is
required because `ServerPlayNetworkHandler.onPlayerMove` calls `player.onLanding()` on every upward
move packet, so an up/down cycle cannot stack fall — the ascent wipes whatever the previous descent
added. `MaceItem.postDamageEntity` also resets the attacker's fall after a landed smash.
`CaveBurst.burstPlan` uses the full verified endpoint for the first climb (a minimum-height climb
could sit inside a thick roof below the pocket), capped by the packet cap; with the 4-packet padding
the production burst is exactly one hit. `Sustained Drain` then repeats the same padded climb for the
remaining totems, one hit per dynamically computed cooldown window (`CaveBurst.sustainedDelay`,
22-60 ticks), waiting for enough mace charge at the available lift. A full-charge lethal hit needs
~4.25 blocks of climb (about 7 blocks of clear space); if no clear endpoint that high exists, the
module warns and skips rather than pretending stacked cycles can help. It warns once when `AntiPop`
(or another NoFall) is inactive: each climb builds real server fall, so a rejected hit can hurt on
the next landing. Position-only packets are still anticheat-visible, but the selected endpoint never
collides with a block. Proof: `./gradlew caveBurstSimulation` runs a deterministic 1.21.11 model of
endpoint acceptance through a thick roof, the 4-packet padding, the movement cap, upward fall reset,
mace damage, hurt invulnerability, and totem pops; it proves a 2-block cave with a 6-block roof and
an air pocket above it pops 24/24 totems with zero rejected packets, plus the 7-block
sustained-only and 3-block impossible cases.

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

