# Problems — Code-Health / Bug / Ban-Risk Report

> Generated from `src/` only (per owner: jars are compiled from src, not reviewed).
> Format: severity | location | what is wrong | fix.
> Severities: **CRITICAL** (crash/kick/data-loss), **HIGH** (wrong behavior/ban),
> **MEDIUM** (dead code/UX), **LOW** (style).
> `FIXED 2026-09-25` marks issues repaired in this commit; unfixed items remain open.

## 0. Build / config — HIGH

1. **FIXED 2026-09-25 — `build.gradle`, `libs/` jar typo.**
   Renamed `libs/MeteorCilent-1.21.11-0.18.6.jar` → `libs/MeteorClient-1.21.11-0.18.6.jar`;
   `build.gradle` now uses `files("libs/MeteorClient-${minecraft_version}-${meteor_version}.jar")`
   so `meteor_version` is no longer unused. `libs/` is still gitignored — fresh clones need the
   jars (see `docs/build.md`). Full Maven-ization still TODO.
2. **FIXED 2026-09-25 — `src/main/resources/fabric.mod.json`.** Pinned to
   `minecraft ~1.21.11`, `fabricloader >=0.18.6`, `meteor-client >=0.18.6`; removed empty
   `mixins` array (file `qazr-addons.mixins.json` kept but unwired).
   NOTE: meteor-client must stay a `>=` range — an exact `"0.18.6"` pin blocks launch for
   users on newer Meteor versioning (e.g. `1.21.11-86`, loader error "Some of your mods
   are incompatible"). Runtime against newer Meteor is untested (we compile against the
   local 0.18.6 jar), so API drift may still break at runtime.
4. **MEDIUM — naming.** `maven_group com.macekill` vs `archives_base_name Qazr-Addons` vs id
   `qazr-addons` vs `MaceKillAddon` — pick one brand. `MassTpa`/`Smasher` inconsistent caps.
5. **LOW — `README.md`.** Profanity + theft claim, no usage/build info. Keep `docs/` as canonical reference.

## 1. Duplication — HIGH (maintenance)

- `TpMace.java` (deleted 2026-11-18) vs `Smasher.java` (894): `attackOnce/stepMove/stepMoveDown/hitboxBlocked/
  firstClearBelow/findDropColumn/sendAttack/finishAttack/returnToStart/resetState/checkAndSwapWeapon/
  swapMace/getYawTo/getPitchTo` were ~95% identical. Same for `getDrainHeights/parseHeight/countTotems`
  ×5 files, `getSelectedSlot/setSelectedSlot` ×7 files, totem-bypass prologue ×5 files.
  Fix: move to `macekill/` (`SmashEngine`, `WeaponSwap`, `Heights`) and delete copies.
- `Combat.BypassRunner` (`Combat.java`) is never instantiated — dead. `Totem Attacks` /
  `getDrainHeights()` in TpMace/MaceAttect are superseded by `BurstHeights` single-burst
  (fixed 2026-09-25: `Drain Mode` now `SPREAD`(default)/`LIST`/`INCREMENTAL`, all wired).
  Still dead: hidden `Single Tick` / `Hits Per Tick` settings, `Air Check` in TpMace,
  `NoFall` in SpearKill, `bypassRunner/bypassTarget/bypassHeights/bypassIdx` fields.

## 2. Movement / teleport bugs — CRITICAL/HIGH

6. **FIXED 2026-09-25 — `macekill/Movement.java` `doTpTo`.** Now interpolates `from → to`
   in `ceil(dist/moveDistance)` steps, null-guards `mc/player/networkHandler/to`, clamps
   `moveDistance`, and `sendRotations`/`attackEntity` null-guard `player`.
7. **FIXED 2026-09-25 — `MaceMissLite.java`.** `getVclipClearance(mc, currentTarget)` (was `mc.player`).
8. **FIXED 2026-09-25 — `AutoRise.java`.** First packet is now `(posX, posY+h, posZ)` (was `posX+h`).
9. **OPEN — `Combat.java` height loop.** `findVclipHole` scanned from player pos by design
   (VClip straight up, then drop onto target); `basePos` reuse matches that design. Left as-is.
10. **PARTIALLY FIXED 2026-09-25 — hardcoded world top.** `Combat` uses `worldTop(mc)` =
    `world.getTopYInclusive()` with 319 fallback; `getVclipClearance` uses `Math.floor(target.getY())`;
    `Smasher` totem bursts use the pierce ceiling (`worldTop - targetY`, cave-proof).
    Non-burst `getAttackHeight` paths still hardcode caps — unify next.
11. **FIXED 2026-09-25 — cave attack position.** `TpMace`/`MaceAttect.findDropColumn` is now
    two-phase: fully-clear column (open sky) else nearest column with a FREE hitbox attack
    spot within 2.9 blocks of the target (`findAttackY`, top-down scan). The old fixed
    `ty+1.1` attack Y landed inside 2-high tunnel ceilings → server fall reset → zero-bonus
    hits. Column scan is hoisted to once per burst (was ~1M block lookups on 198-bursts);
    `tpY` cap uses `worldTop` (was hardcoded 318); burst heights are fractional with a
    `Height Step` setting so big bursts fit under low roofs with fewer packets.
12. **FIXED 2026-09-25 — `Rise.java`.** Removed unreachable
    `if (!bypass.get() && delayTicks > 0) return;`. Note: `Bypass` setting is now unused
    (kept for config compat).
12b. **FIXED 2026-09-25 — descent skip uncapped (`TpMace`/`MaceAttect.stepMoveDown`).**
    The ceiling-skip allowed one up-to-100-block packet, tripping "moved too quickly":
    the server snaps you back and every later burst hit cascades into no-fall weak hits,
    which is exactly bursts stalling partway (~15 hits in, rest whiffed). Skips now fire
    only when they fit in one step (thin roofs); thick rock is passed through in-cap —
    every packet stays within the move cap on vanilla.
12c. **REMOVED 2026-11-25 — stacked totems outlasting the burst / `Sustained Drain`.**
    The `Sustained Drain` chain (and its `Max Sustained Hits` setting) was removed from
    `Smasher` (then `MaceAttect`). A single burst still cannot exceed reality (actual held
    totems) and stalls under server mace-damage caps or packet drops, but there is no
    follow-up chain anymore: `Totems To Pop` / `Detect Totem` bound the burst.

## 3. Inventory / slot desync — HIGH

13. **FIXED 2026-09-25 — reflection `PlayerInventory.selectedSlot` (ROOT CAUSE of the
    silent-swap bug).** All 9 copies (`macekill/Inventory`, `TpMace`, `Smasher` (then
    `MaceAttect`), `MaceAura` (deleted), `MaceBreakerPro`, `MaceDMG`, `AutoShulkerBox`,
    `AutoMineModule`, `SpearKill`) used
    `getDeclaredField("selectedSlot")` with the Yarn name, which does not exist at runtime
    (intermediary mappings) — so in production reads always returned 0 and writes were silently
    dropped. Effects: silent-swap reverts sent slot 0 instead of the true original (server stuck
    on the wrong slot after every smash), the "already holding mace" check read slot 0's stack
    (could skip the slot packet → weak hit), and non-silent client switches never rendered.
    Now uses the public `PlayerInventory.getSelectedSlot()/setSelectedSlot()` API (verified via
    javap), which Loom remaps correctly — works in dev and production. Dead `Field` decls/imports
    removed. (MaceAttect's dual-name packet-field reflection is unaffected — it already tries both.)
14. **OPEN (historical) — `InvUtils.move()` is async** but `Combat.executeAttack`, the deleted
    `MaceAura.executeAttack`, `XinTpMace.onTick`, `MaceMissLite.doTpAura` attacked in the same
    tick — server still sees the old item → no mace damage. Fix: defer attack 1–2 ticks after a
    backpack move.
15. **FIXED 2026-09-25 — `AutoShulkerBox.setSelectedSlot`.** Now validates slot range and sends
    `UpdateSelectedSlotC2SPacket` so server/client stay in sync.
16. **PARTIALLY FIXED 2026-09-25 — `swapMace` / silent-swap.** `TpMace` (deleted) / `Smasher`
    `onDeactivate`/`resetState`/`switchTo` null-guard `player`/`networkHandler`/`interactionManager`
    and slot range; `swapMace` validates both slots. Found 2026-09-25 — the SILENT-SWAP BUG:
    the revert waited in `resetState()` until after the 3-tick `RETURN_DELAY`, so for 3 ticks
    the server held the mace while the client showed the original item (wrong-item interacts,
    anticheat slot desync). Both modules now call `revertSilentSwap()` immediately after the
    burst loop; `resetState`/`onDeactivate` keep it as a safety net, and `resetState` also
    clears stale `originalSlot`/`maceSlot`. Screen-slot math itself unchanged.
17. **FIXED 2026-09-25 — silent-swap revert NPE.** Guarded by `networkHandler != null` in both modules.

## 4. Combat correctness — HIGH

18. **PARTIALLY FIXED 2026-09-25 — `MaceDMG.java`.** `Fake Height` is now plain blocks
    (default `22.0`, was misleading `sqrt` `22.36`), packet sends `height` directly with null guards.
    Timing issue remains: detection runs on `TickEvent.Post` after the hit — needs a
    `PacketEvent.Send` pre-hit rework for full effect.
19. **FIXED 2026-09-25 — `MaceBreakerPro.java`.** Axe/mace now use
    `instanceof AxeItem/MaceItem`; swords use registry-id `contains("sword")` because
    1.21.11 has no `SwordItem` class (verified against merged jar). `switchTo` validates
    slot + nulls. Same-tick double-hit eaten by cooldown still open.
20. **FIXED 2026-09-25 — `Targeting.isSafeBlock`.** Null-guards `mc/world/pos`.
    XZ-only prediction limitation still open.
21. **OBSOLETE 2026-11-25 — `MaceAura.positionCache` never invalidated** (module deleted).
22. **OBSOLETE 2026-11-25 — entity loops** (`MaceAura:146`, `Targeting:31`, `TpMace:691`, …) iterate
    `world.getEntities()` live — CME risk under spawn/despawn. `MaceAura`/`TpMace` are deleted;
    `Targeting` remains. Snapshot or use `world.getPlayers()`.
23. **OBSOLETE 2026-11-25 — friend checks inconsistent.** `TpMace:718-719` checked both `isFriend`
    and `shouldAttack`; `MaceAura:152` only `shouldAttack` (both modules deleted).
24. **FIXED 2026-11-02 — `Smasher` (then `MaceAttect`) reflection.** `AttackEntityEvent.entity` is now read directly
    (public field), and 26.x attack packets use `ServerboundAttackPacket.entityId()` — the
    yarn/intermediary field-name lookup and its silent failure mode are gone. (On the 1.21.11
    branch the same module used `PlayerInteractEntityC2SPacket.handle(Handler)` +
    `IPlayerInteractEntityC2SPacket.meteor$getEntity()`.) `MaceTotemFail`'s
    `Disable When Blocked` guard was also added.

## 5. Packet-spam / ban risk — CRITICAL

| Module | Burst | Verdict |
|--------|-------|---------|
| ~~`AntiAirMiss`~~ | 21 pkts **every tick** while anyone in 6 blocks | **DELETED 2026-11-18** (module removed). Was: no combat value; pure self-flag. |
| `Rise` | 20 pkts per `Delay` | PacketFly signature. |
| `SpeedModule` | 33 pkts/tick at defaults (up to 153) | Instant Grim/Vulcan ban. |
| `SpearKill` Blink flush | **FIXED 2026-09-25: capped at 500 queued packets** (auto-flush+reset) | Was unbounded OOM/kick risk. |
| `MassTpa` | `/tpa` to everyone on timer | Spam mute + social-engineering abuse vector. Add confirm + cooldown. |
| `PlayerTp` / `.tp` | stepped long-range TP packets | Same teleport flags as mace smashes; cross-map jumps kick vanilla/Paper. |
| `AdvancedNoFall` | 1-5 `onGround=true`/tick while falling | Safe by design on vanilla/Paper; Grim-style anticheats flag sustained spoofs. |
| `ArrowKiller` | 4 no-ground primes + 6-8 tagged teleports + use/release per shot | Blatant server-side teleport; the prime is `onGround=true` unless AntiPop strips it. |
| `Smasher` | Up to a 198-hit pierce burst = thousands of move packets in one tick | Same VClip profile as `MaceKillModule`: 170-block `onGround=false` teleports, anticheat-visible. `Move Step`, `Totems To Pop` and `Detect Totem` bound the burst; the module warns above ~1500 packets. |
| `Blacklist` | 2 move packets (PAPER) or a stepped path (SPIGOT) + attack packet, per smash | One smash per `Attack Delay` (default 20 ticks) on every blacklisted name in `Scan Distance` (0-256, default 60). Reuses `UltraAura.smash` — whatever ServerType/steps setup UltraAura is configured with. Same ban profile as UltraAura. |
| `AutoMineModule` fly | `allowFlying` without creative | Fly flag on any anticheat. Needs a real movement bypass or removal. |
| All VClip smashes | 170-block `onGround=false` teleports | Vanilla `moved too quickly`, Paper `invalid move`, Grim teleport checks. Test-server only. |

## 6. Automation bugs — HIGH/MEDIUM

25. **HIGH — `AutoMineModule.scan` (`:846-872`).** Radius-64 sphere ≈ 1M `getBlockState` calls every
    60 ticks on the render thread → freeze spikes. Chunk-iterate or shrink default; run off-thread.
26. **HIGH — `isDangerZone` (`:487-501`).** `(2*5+1)²×4 ≈ 484` lookups per A* node × 6000 nodes ≈ 2.9M
    lookups per plan. Cache chunk snapshots.
27. **FIXED 2026-09-25 — `Mutable` as map key** (`scan`). Now copies `m.toImmutable()` into
    `imm` before `abandonBlacklist`/`dugPositions` checks and `getBlockState`/`isOreNearFluid`/`add`.
28. **MEDIUM — A* `key()` drops Y** (`:503-504`). Different levels collide in `gScore/parent`.
    Include Y or scope per `tunnelY`.
29. **FIXED 2026-09-25 — `onDeactivate`/reset NPEs.** `AutoMineModule.onDeactivate` guards `player`;
    `FreecamTp` guards `freecam == null` + `player`; `Smasher` guards
    `player`/`networkHandler`/`interactionManager` in `onDeactivate`/`resetState`.
30. **MEDIUM — `AutoShulkerBox.placeBlock` sequence `0`.** `PlayerInteractBlockC2SPacket(hand, hit, 0)`
    hardcodes sequence; server may reject. Use proper sequence/allocation.
31. **FIXED 2026-09-25 — `Hitback` range.** Skips attackers beyond 4.5 blocks + null-guards
    `player`/`networkHandler` in `hitBack`.

## 7. Creative modules — MEDIUM

32. `CreativeGiveUtil.give` only handles main inventory slots; offhand/armor unhandled. No server-side
    creative re-check at send time → kick if survival. Enchant `long → int` clamp allows 2B levels that
    vanilla strips/kicks for. Attribute `Identifier("qazr1234","mod_"+nanoTime())` leaks unique ids per item.
    `enchantmentRegistry/artributeRegistry/statusEffectRegistry` `orElseThrow` crashes if world/registry
    not synced — catch and warn instead.

## 8. Chat modules — MEDIUM/LOW

33. `AutoGG` death match (`startsWith(playerName) && contains(localName)`) false-positives on chat and
    misses non-vanilla death messages. `skipChatModify` is `static` — two instances / reloads interfere.
34. `AutoMsg` `<Name>` author parse misses `/msg`, Essentials nick, translatable prefixes. Tell-once set
    keyed by name, not UUID — nick changes bypass it.
35. Both send public chat spam → reports/mutes. Default them OFF with an ethics warning in docs (done in overview).

## 9. Ported modules (2026-11-02) — MEDIUM

36. **FIXED IN PORT — brew-addon `Attack`.** The original ran its packet steps through
    `CompletableFuture.runAsync`, i.e. off the client thread, while calling
    `mc.player.setPosition(...)` and sending packets. `utils/Attack.java` executes the three steps
    in order on the client thread; the resulting packet sequence is unchanged.
37. **FIXED IN PORT — vicore `UltraAura` silent swap.** `silentSwap` was only assigned from the
    `Swap Mode` setting callback, so the default `SILENT` mode never restored the previous hotbar
    slot. The constructor now initializes it from the setting.
38. **1.21.11 DELTA — `AntiPop`.** vicore set the packet's `onGround` through a mixin accessor; in
    1.21.11 all `PlayerMoveC2SPacket` fields are `final`. `modules/AntiPop.java` rebuilds the same
    packet subtype with `onGround=false` and `sendSilently`s it — no mixin/accesswidener needed.
39. **1.21.11 DELTA — position getters.** `Entity.getPos()` / `Camera.getPos()` were renamed to
    `getEntityPos()` / `getCameraPos()`; the brew ports use the new names.
40. **KNOWN RISK — `UltraAura`, `InfiniteReach`, `ArrowKiller`, `b-anti-creeper`.** All four send
    server-side teleports to attack outside vanilla reach; trivially detectable by anticheat.
    `AntiPop` directly conflicts with `AdvancedNoFall` (currently unregistered, file kept; both
    rewrite outgoing move packets), so never enable both.
41. **REMOVED 2026-11-25 — `Smasher` (then `MaceAttect`) Cave Safe.** The cave mode,
    `macekill/CaveBurst.java`, `Sustained Drain`, `Max Sustained Hits` and the
    `caveBurstSimulation` proof harness were removed. The remaining pierce burst still sends
    position-only teleports (anticheat-visible) and warns once when `AntiPop` is inactive: an
    unlanded hit leaves real server fall that can hurt on the next landing.
42. **1.21.11 DELTA — `ArrowKiller` fallback lookup.** vicore used `World.getChunk(BlockPos)` to
    reject unloaded positions; the port uses `World.isPosLoaded(BlockPos)` (the 1.21.11 API).
    Vehicles use `VehicleMoveC2SPacket.fromVehicle`; the player path tags its
    `PositionAndOnGround` packets with Meteor's move tag. The prime packet stays `onGround=true`
    (vicore behavior), which is exactly why AntiPop is required.
43. **1.21.11 DELTA — `InfiniteReach` hover box.** Uses `Camera.getCameraPos()` and
    `Renderer3D.box(BlockPos, Color, Color, ShapeMode, int)` at `Render3DEvent` priority 0. The
    raycast is camera-based; only hits beyond vanilla 4.5 blocks are drawn so the vanilla outline
    is not duplicated.
44. **KNOWN RISK — `Blacklist` (new 2026-11-05).** Auto-smash list over chat commands
    (`.blacklist add/remove/dis/list`, names + range persist in module settings). The scan reuses
    `UltraAura.smash` — same `PositionAndOnGround(false)` teleport profile, so strict anticheats
    flag every smash exactly like UltraAura. One smash per `Attack Delay` ticks while the module
    is on; setting `Attack Delay` near 0 turns it into a continuous packet wall (no client-side
    limiter beyond the delay). Only one victim per attempt, no multi-target.
45. **26.3 DELTA — `InfiniteReach` crack overlay.** Ported to the 26.3 official mappings:
    yarn `ClientWorld.setBlockBreakingInfo` → `ClientLevel.destroyBlockProgress(int, BlockPos, int)`
    (same semantics: stage `<0 || >=10` removes the crack, `0..9` shows it; vanilla calls it from
    `MultiPlayerGameMode.getDestroyStage()` during `continueDestroyBlock`, remotely via
    `ClientboundBlockDestructionPacket`); `BlockPos.ORIGIN` → `BlockPos.ZERO`;
    `MinecraftClient.getNetworkHandler()` → `getConnection()`; `mc.world` → `mc.level`.
    Packet CTORs verified against the 26.3 merged jar (`ServerboundPlayerActionPacket(Action,
    BlockPos, Direction[, seq])`, `ServerboundUseItemOnPacket(hand, hit, seq)`); Meteor 26.3's
    `BlockUtils.getBreakDelta(slot, state)` indexes the same `items` list `Inventory.getItem(0..8)`
    does, so hotbar indices stay valid. Hover box unchanged (`Camera.position()`/`xRot()`/`yRot()`).
46. **2026-11-18 / 2026-11-25 — modules deleted and renamed.** `TpMace`, `MaceMissLite`,
    `XinTpMace`, `MaceDMG`, `AntiAirMiss`, `AutoRise` and `AutoTotemToHotbar` were removed on
    2026-11-18; `MaceAura` and `NearestPlayerHUD` were removed on 2026-11-25. All were deleted
    as classes + `MaceKillAddon` imports/registrations. `MaceAttect` was renamed `Smasher` on
    2026-11-25, and its Cave Safe / Sustained Drain features plus `macekill/CaveBurst.java` and
    the `caveBurstSimulation` task were removed. `AdvancedNoFall` stays on disk with its
    registration commented out. 23 modules are registered. Historical numeric entries above
    that reference the deleted files (e.g. #4, #7, #12b/c, #15, #18, #22, #23, #35, #39, #43)
    describe pre-deletion code and are kept as history.

## Fix priority (suggested)

1. DONE 2026-09-25: `Movement.doTpTo` interpolation + `AutoRise` X/Y swap + `MaceMissLite` clearance target.
2. DONE 2026-09-25: `Mutable`-as-key + null-guard `onDeactivate` paths.
3. PARTIAL 2026-09-25: jar typo fixed + `meteor_version` wired + `fabric.mod.json` pinned + empty mixins unwired.
   Still TODO: full Maven-ization.
4. DONE 2026-11-18 / 2026-11-25: TpMace (and MaceMissLite/XinTpMace/MaceDMG/MaceAura) deleted;
   `MaceAttect` renamed `Smasher` and reduced to the pierce-burst smash engine — the dedup is
   done by deletion.
5. PARTIAL 2026-09-25: SpearKill queue capped at 500. ~~AntiAirMiss~~ deleted 2026-11-18; still TODO: rate-limit Rise/Speed/MassTpa.
6. TODO: Async `InvUtils.move` → tick-delayed attack; unify slot reflection into one tested helper.
