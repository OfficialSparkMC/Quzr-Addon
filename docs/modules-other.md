# Other Modules (movement / automation / chat / creative)

## Movement

### Rise — `modules/Rise.java` (68 lines)
`Height(10)`, `Delay(20)`, `Bypass`. Every `Delay` ticks sends **20 packets**
(10× up `posY+height` + down `posY`, all `onGround=false`). Dead line:
`if (!bypass.get() && delayTicks > 0) return;` is unreachable (`delayTicks>0` already returned above).
Ban-prone PacketFly.

### AutoRise — `modules/AutoRise.java` (87 lines)
`Detection Range(6)`, `Rise Height(10)`, `Send Rotations` (>12 → 4 junk `LookAndOnGround`).
Finds nearest alive non-creative/spectator attackable entity; if found sends two packets.
**Fixed 2026-09-25**: first packet was `PositionAndOnGround(posX+h, posY, posZ)` (height on X);
now `(posX, posY+h, posZ)`.

### Speed — `modules/SpeedModule.java` (50 lines)`Rise Height(5)`, `Cycles(10)`. Every tick sends `(Cycles+1)×3` packets staircasing
`posY + h*i`, all `onGround=false`. At defaults 33 packets/tick ≈ 660/s. Instant flag.

### PlayerTp — `modules/PlayerTp.java`
`Range(100)`, `Move Step(8)`, `Ignore Friends`. One-shot: on enable finds the nearest
alive non-spectator attackable player in range, travels via shared
`Movement.doTpTo(..., syncClientPos=true)` (client + server stay synced), chats the
name, auto-disables. Named jumps go through `.tp <player>` (see `commands.md`),
which calls the same static `teleportTo()` — module need not be enabled.

### AdvancedNoFall — `modules/AdvancedNoFall.java`
`Fall Threshold(3.0)`, `Fast-Fall Trigger(off)`, `Packets(1)`, `Pause on Elytra(on)`.
Smash-safe by construction: triggers only on **client** `fallDistance` (packet smashes
never move the client, so it idles through every smash and never resets the server's
fake fall), then spoofs `onGround=true` at the same X/Y/Z every tick while really
falling — the server can never accumulate a lethal fall, so post-smash/sky-strand
landings always survive. Skips ground/creative/spectator/vehicle/elytra states.
Not a void saver (void damage isn't fall damage); onGround spoof can flag strict
anticheats — test-server first.

### AntiPop — `modules/AntiPop.java` (68 lines)
`log-on-effect(off)` in the default group. On `PacketEvent.Send` for any `PlayerMoveC2SPacket`
with `isOnGround() == true`: cancels the original and `sendSilently` re-sends a rebuilt packet of
the **same subtype** (`Full` / `PositionAndOnGround` / `LookAndOnGround` / `OnGroundOnly`) with
`onGround=false`, preserving X/Y/Z, yaw/pitch, `changePosition`/`changeLook` and
`horizontalCollision`. The server therefore never registers a landing (no fall damage / landing
effects). Port note: 1.21.11 declares the packet fields `final`, so vicore's mixin-accessor
mutation is impossible — the rebuild is public-API only. Do not run together with
`AdvancedNoFall`: both rewrite the same move packets (AntiPop would erase NoFall's
`onGround=true` spoof).

## Combat / reach (ported 2026-11-02)

### UltraAura — `modules/UltraAura.java` (422 lines)
`Configuration` group: `Attack Delay(20, 0-60)`, `Distance(50, 0-150)`, `Steps(8.5, 1-20)`,
`Lock Target(off)`, `Ignore targets on water(off)`, `Entities(only attackable, PLAYER)`,
`ServerType(PAPER)` `{SPIGOT, PAPER}`, `Switch(off)`, `Swap Mode(SILENT)` `{SILENT, NORMAL}`,
`Weapon(MACE)` `{SWORD, AXE, MACE, TRIDENT, ALL, ANY}`.
`TickEvent.Pre` → `TargetUtils.getList(targets, ClosestAngle, 5)`; `TickEvent.Post` runs the
attack timer. Target = locked target or first sorted entry; `updatePositions` uses
`Entity.getEntityPos()`. After `Attack Delay`: `PAPER` sends one `PositionAndOnGround(targetPos)`
and one back to `startPos`; `SPIGOT` sends the stepped blink path (`Steps` blocks per packet,
stopping 4 blocks short on the way in). The client `setPosition` mirrors both hops, then
`PlayerInteractEntityC2SPacket.attack` + swing, optional hotbar swap to `Weapon`
(`SILENT` restores the previous slot), return, timer reset. Filter: living, attackable type,
not self/camera, inside `Distance` of the bounding box, non-creative, `Friends.shouldAttack`;
optional water skip. `getInfoString()` = target name.
Port fix: `silentSwap` is initialized from the `Swap Mode` default — vicore only updated it in the
setting callback, so the default `SILENT` never restored the weapon. The `MaceTotemFail`
name-lookup is kept from the original; it is a no-op in this addon.

### InfiniteReach — `modules/InfiniteReach.java` (281 lines)
`entities(on)`, `entity-reach(210, min5)`, `blocks(on)`, `block-reach(210, min5)`,
`mace-attack(off)`, `attack-height(35, 1-170, visible with mace-attack)`, `Debug(off)`.
`TickEvent.Post` edge-detects the attack/use keys. Attack click: with `entities` on, entity raycast
(blocks ignored) → entity beyond 4.5 blocks and within `entity-reach` (not an item entity) →
`Attack.attack(target, mace-attack, attack-height, no auto-equip)`; if that did not fire and `blocks`
is on → `breakBlock`. Use click only works while `blocks` is on.
`breakBlock`: packet-teleport to `block.toCenterPos().add(0,2,0)`, send `START_DESTROY_BLOCK`,
teleport back; while held, `updateBreakProgress` picks the fastest hotbar tool and adds
`BlockUtils.getBreakDelta`, at `>=1.0` → `STOP_DESTROY_BLOCK` + reset. `interact` uses the same
block path with `PlayerInteractBlockC2SPacket`. Turning `blocks` off, releasing the key, or
`onDeactivate` resets the break state. All teleports are server-side only
(`setClientSided=false`) and are trivially visible to anticheat.

## Automation

### OreTracker — `modules/AutoMineModule.java` (1053 lines, largest file)
Wurst TunnelHack-style miner. Settings: `Scan Range(64)`, `Auto Mine`, `Min Y(-64)`,
`Stuck Timeout(60t)`, `Abandon Timeout(200t)`, `Safe Distance(5)`, `Avoid Caves`,
`Skip Dangerous Ores`, `Chat Info`, 11 ore toggles, `Render{Ores, Ore/Target/Path Color}`.

Loop (`TickEvent.Post`): death → toggle off; every 100t expire blacklists; every 60t `scan()`
(triple-loop sphere, `Mutable` cursor); prune mined ores; nearest non-blacklisted ore →
`planWaypoints()` (air-adjacent goal + `tunnelY` record + 4-dir A* `findHorizontalPath`
with `compressPath`/`canWalkStraight`, `fallbackPath` on failure); stuck detect
(`stuckTime` same `BlockPos`); `WALK_XZ → FLY_UP → DIG_DOWN → MINE_ORE → RETURN_TO_TUNNEL`.
Digging via `attackBlock` + `updateBlockBreakingProgress` + swing; flight via
`abilities.flying/allowFlying` (flagged without creative); render boxes+path lines.
Blacklists: 3-min `abandonBlacklist`, 1-min `dugPositions`, 60-s target-lock guard, CPU>80% 5-s pause.

### AutoShulkerBox — `modules/AutoShulkerBox.java` (341 lines)
`Shulker Slot(0)`, `Pickaxe Durability Threshold(1)`, `Cycle Shulkers`. `onActivate`
pins `targetPos = crosshair.up()`. Per tick: air/replaceable → `ensureShulkerBoxInHand`
(config slot → hotbar scan → backpack SWAP) + `placeBlock` (`PlayerInteractBlockC2SPacket`,
face UP against `targetPos.down()`, sequence `0`); shulker block → one-tick grace
(`wasPlacing`) → `ensurePickaxeInHand` (durability filter + `lastPickSlot` cache) +
`breakBlock` (START+STOP same tick + swing) + `cycleShulkerBox` (refill slot).
Slot switches send `UpdateSelectedSlotC2SPacket` since 2026-09-25 (was reflection-only desync).

### AutoTotemToHotbar — `modules/AutoTotemToHotbar.java` (50 lines)
`Slot(7)`. Per tick: offhand totem → done; else hotbar totem → `InvUtils.move` to `Slot`;
else any-inventory totem → move to `Slot`. Uses Meteor `InvUtils` (async clicks).

### MassTpa — `modules/MassTpa.java` (111 lines)
`Delay(20t)`, `Ignore Friends`, `Send On Enable`, `Chat Feedback`. `buildQueue` from
tab-list minus self/friends; `onTick` sends one `sendChatCommand("tpa "+name)` per `Delay`.
Spam/mute risk; no per-server cooldown.

### freecamtp — `modules/FreecamTp.java` (71 lines)
`Eye Offset(1.62)`. `onActivate` forces Meteor `Freecam` on (remembers prior state);
right-click (`MouseClickEvent`, `GLFW_MOUSE_BUTTON_2`) sends one
`PositionAndOnGround(freecam.pos - eyeOffset)` + client `setPos`, `event.cancel()`.
Single far packet usually rubberbanded — needs stepping for >10 blocks.

### Hitback — `modules/Hitback.java` (99 lines)
`Rotate`, `Ignore Friends`, `Only Players`, `Cooldown(10t)`. On
`PacketEvent.Receive(EntityDamageS2CPacket)` addressed to self with `sourceCauseId>0`:
resolve attacker, friend/player filters, cooldown gate → `Rotations.rotate(yaw,pitch,attack)`
sending `PlayerInteractEntityC2SPacket.attack`. Skips attackers farther than 4.5 blocks
(server drops those hits anyway).

### b-anti-creeper — `modules/AntiCreeper.java` (79 lines)
`kill-mode(OnExplode)` `{OnExplode, OnSpawn}`, `range(4)`.
`OnExplode`: `TickEvent.Post` scans `mc.world.getEntities()` for `CreeperEntity` with
`getFuseSpeed() > 0` inside range → `Attack.attack(creeper, useMace=true, attackHeight=10,
autoEquipMace=true)`. `OnSpawn`: `EntityAddedEvent` → same attack for a newly added creeper in range.
`Attack` teleports the player to the creeper and (with a mace) through a 10-block up/down path —
very obvious to nearby players and anticheat.

## Chat / info

### auto-gg — `modules/AutoGGModule.java` (246 lines)
`gg-messages["gg {player}",...]`, `detection-range(16)`, `chat-prefix/suffix` (`&` colors).
`TickEvent.Post`: 1/s `updateNearbyPlayers` + delayed-GG countdown (4–24t random).
`PacketEvent.Send(ChatMessageC2S)`: cancel + resend with prefix/suffix (re-entrancy `processing` flag).
`PacketEvent.Receive(GameMessageS2C)`: `message.startsWith(playerName) && contains(localName)` →
`triggerKill` → HUD title `Killed <name>` + local echo + public GG. Brittle on custom death msgs.

### auto-msg — `modules/AutoMsgModule.java` (769 lines)
`Mode{trigger-mode(RANDOM_INTERVAL/WAIT_FOR_MESSAGE)+intervals/counts, send-mode(SINGLE/BURST)+counts/delays}`,
`Target{target-selection(NEAREST/RANDOM/FIXED)+fixed-player+custom-command}`,
`Messages{message-list with [Group] headers + {player}}`,
`Typos{enable-typos+frequency+strength+typo-replacements}`,
`Tell Once{tell-once+range(64)}`. Burst queue, tell-once `HashSet` pruned by logout/range,
author parse `<Name>` only, `/`-prefixed output via `sendChatCommand` else `sendChatMessage`.

### auto-tpa-reject — `modules/AutoTpaReject.java` (70 lines)
`deny-command("tpdeny", {player} supported)`. On Meteor `ReceiveMessageEvent`: strip `§` codes,
require message to **end with** `wants to be teleported to you`, 1.5-s cooldown, extract prefix
as name, `sendChatCommand`. Nothing else ever matches by design.

### NearestPlayerHUD — `modules/NearestPlayerHUD.java` (54 lines)
`Range(100)`. Only override is `getInfoString()`: nearest alive player name + dist or `None`.
Not a real Meteor HUD element — shows in the module list, not as screen overlay.

## Creative

Shared: `utils/CreativeGiveUtil.java` — `give(stack)`: `getEmptySlot` → local `setStack` →
`CreativeInventoryActionC2SPacket(slot<9 ? slot+36 : slot, stack)`; `warn` (dedupe), `info`, `resetError`.

### ItemGiver — `modules/CreativeGiveModule.java` (343 lines)
`Preset(NONE/DAMAGE_SWORD/DAMAGE_ARMOR/TOTEM)`, `Item ID`, `Count(1-64)`, `Custom Name+Name Text(& codes)`,
`Enchantments["minecraft:sharpness:10"]` (`id:level`, 64-bit parse → int clamp),
`Attributes["generic.attack_damage|1000|MAINHAND"]` (`id|amount|slot`, slots MAINHAND..ANY),
`Continuous`. `onActivate` (or every `Post` tick if continuous): creative check → preset or
custom build (`CUSTOM_NAME` + `ENCHANTMENTS` + `ATTRIBUTE_MODIFIERS` components) → `give`.
Presets: 137891-damage sword, full 137891 armor+toughness+KB set, named totem.

### PotionGiver — `modules/CustomPotionModule.java` (238 lines)
`Potion Type(SPLASH)`, `Count`, `Effect List["instant_health|125|1"]` (`id|level0-255|seconds`,
duration capped 60 min), `Custom Color + Potion Color`, `Custom Name + Name Text`, `Continuous`.
Builds `PotionContentsComponent(empty, color?, effects, empty)` + optional `CUSTOM_NAME` → `give`.
Same `&/§` name parser as ItemGiver.
