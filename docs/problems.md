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
   `qazr-addons` vs `MaceKillAddon` — pick one brand. `MassTpa`/`MaceAttect`(sic)/`MaceDMG` inconsistent caps.
5. **LOW — `README.md`.** Profanity + theft claim, no usage/build info. Keep `docs/` as canonical reference.

## 1. Duplication — HIGH (maintenance)

- `TpMace.java` (864) vs `MaceAttect.java` (892): `attackOnce/stepMove/stepMoveDown/hitboxBlocked/
  firstClearBelow/findDropColumn/sendAttack/finishAttack/returnToStart/resetState/checkAndSwapWeapon/
  swapMace/getYawTo/getPitchTo` are ~95% identical. Same for `getDrainHeights/parseHeight/countTotems`
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
    TpMace/MaceAttect totem bursts use the pierce ceiling (`worldTop - targetY`, cave-proof).
    `MaceAura` scan and non-burst `getAttackHeight` paths still hardcode caps — unify next.
11. **OPEN — `TpMace.findDropColumn` / `MaceAttect.findDropColumn`.**
    Scans `isAir()` only — leaves, fluids, cobwebs counted as blocked while `vclipSafe` allows leaves.
    Inconsistent with `Combat.vclipSafe`. Unify predicate.
12. **FIXED 2026-09-25 — `Rise.java`.** Removed unreachable
    `if (!bypass.get() && delayTicks > 0) return;`. Note: `Bypass` setting is now unused
    (kept for config compat).

## 3. Inventory / slot desync — HIGH

13. **FIXED 2026-09-25 — reflection `PlayerInventory.selectedSlot` (ROOT CAUSE of the
    silent-swap bug).** All 9 copies (`macekill/Inventory`, `TpMace`, `MaceAttect`, `MaceAura`,
    `MaceBreakerPro`, `MaceDMG`, `AutoShulkerBox`, `AutoMineModule`, `SpearKill`) used
    `getDeclaredField("selectedSlot")` with the Yarn name, which does not exist at runtime
    (intermediary mappings) — so in production reads always returned 0 and writes were silently
    dropped. Effects: silent-swap reverts sent slot 0 instead of the true original (server stuck
    on the wrong slot after every smash), the "already holding mace" check read slot 0's stack
    (could skip the slot packet → weak hit), and non-silent client switches never rendered.
    Now uses the public `PlayerInventory.getSelectedSlot()/setSelectedSlot()` API (verified via
    javap), which Loom remaps correctly — works in dev and production. Dead `Field` decls/imports
    removed. (MaceAttect's dual-name packet-field reflection is unaffected — it already tries both.)
14. **OPEN — `InvUtils.move()` is async** but `Combat.executeAttack`, `MaceAura.executeAttack`,
    `XinTpMace.onTick`, `MaceMissLite.doTpAura` attack in the same tick — server still sees the old
    item → no mace damage. Fix: defer attack 1–2 ticks after a backpack move.
15. **FIXED 2026-09-25 — `AutoShulkerBox.setSelectedSlot`.** Now validates slot range and sends
    `UpdateSelectedSlotC2SPacket` so server/client stay in sync.
16. **PARTIALLY FIXED 2026-09-25 — `swapMace` / silent-swap.** `TpMace`/`MaceAttect`
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
21. **MEDIUM — `MaceAura.positionCache` never invalidated** except on kill/deactivate. Newly placed
    blocks are treated as air → teleport inside them → fall reset. Clear per tick or on block update.
22. **MEDIUM — entity loops** (`MaceAura:146`, `Targeting:31`, `TpMace:691`, …) iterate
    `world.getEntities()` live — CME risk under spawn/despawn. Snapshot or use `world.getPlayers()`.
23. **LOW — friend checks inconsistent.** `TpMace:718-719` checks both `isFriend` and `shouldAttack`;
    `MaceAura:152` only `shouldAttack`. Standardize on `shouldAttack`.
24. **LOW — `MaceAttect` reflection.** `AttackEntityEvent.entity` via `getField("entity")` and packet
    `type/entityId` dual-name lookup fail silently → module never triggers on remapped builds.
    Prefer public getters where available; log on failure.

## 5. Packet-spam / ban risk — CRITICAL

| Module | Burst | Verdict |
|--------|-------|---------|
| `AntiAirMiss` | 21 pkts **every tick** while anyone in 6 blocks | No combat value (own position doesn't change enemy miss calc); pure self-flag. Disable by default / delete. |
| `Rise` | 20 pkts per `Delay` | PacketFly signature. |
| `SpeedModule` | 33 pkts/tick at defaults (up to 153) | Instant Grim/Vulcan ban. |
| `SpearKill` Blink flush | **FIXED 2026-09-25: capped at 500 queued packets** (auto-flush+reset) | Was unbounded OOM/kick risk. |
| `MassTpa` | `/tpa` to everyone on timer | Spam mute + social-engineering abuse vector. Add confirm + cooldown. |
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
    `FreecamTp` guards `freecam == null` + `player`; `TpMace`/`MaceAttect` guard
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

## Fix priority (suggested)

1. DONE 2026-09-25: `Movement.doTpTo` interpolation + `AutoRise` X/Y swap + `MaceMissLite` clearance target.
2. DONE 2026-09-25: `Mutable`-as-key + null-guard `onDeactivate` paths.
3. PARTIAL 2026-09-25: jar typo fixed + `meteor_version` wired + `fabric.mod.json` pinned + empty mixins unwired.
   Still TODO: full Maven-ization.
4. TODO: Dedup TpMace/MaceAttect into one `SmashEngine`; delete dead settings/fields.
5. PARTIAL 2026-09-25: SpearKill queue capped at 500. Still TODO: rate-limit AntiAirMiss/Rise/Speed/MassTpa.
6. TODO: Async `InvUtils.move` → tick-delayed attack; unify slot reflection into one tested helper.
