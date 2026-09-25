# Overview — Qazr-Addons

> Meteor Client addon for Minecraft 1.21.11. All combat logic is packet-based
> fake-fall / teleport ("VClip") mace damage. Expect kicks/bans on vanilla,
> Paper, Grim, Vulcan, etc. This is a cheat addon, not a vanilla utility.

## Identity

| Key | Value (source) |
|-----|----------------|
| Mod id | `qazr-addons` (`src/main/resources/fabric.mod.json:3`) |
| Name | `Qazr Addons` (`fabric.mod.json:6`) |
| Entrypoint | `com.macekill.addon.MaceKillAddon` (`fabric.mod.json:14`) |
| Category | `Qazr1234` (`MaceKillAddon.java:36`) |
| Package | `com.macekill.addon` (`MaceKillAddon.java:87`) |
| MC / Yarn / Loader | `1.21.11 / 1.21.11+build.3 / 0.18.6` (`gradle.properties:3-5`) |
| Meteor | `0.18.6` local jar `libs/MeteorClient-1.21.11-0.18.6.jar` (renamed from `MeteorCilent` typo 2026-09-25) |
| Java | 21 (`build.gradle:41`, `fabric.mod.json:23`) |
| Registration | `MaceKillAddon.onInitialize()` adds ~25 modules (`MaceKillAddon.java:43-75`) |

## Module table (25 modules)

### Mace / spear combat (see `modules-mace.md`)

| In-game name | Class | One-liner |
|--------------|-------|-----------|
| `macemiss` | `modules/MaceKillModule.java` | TP-to-target + VClip multi-height attack, armor-break + totem-bypass modes |
| `MaceAura` | `modules/MaceAura.java` | Closest-target aura, offset drops, spam packets, position cache |
| `TpMace` | `modules/TpMace.java` | Long-range pierce-through-roof smash, silent swap, fall-prime packets |
| `xintpmace` | `modules/XinTpMace.java` | Slim TP + predicted-pos + multi-height VClip |
| `MaceMissLite` | `modules/MaceMissLite.java` | Lite missile, dv1/dv2 offsets, force-skip, render box |
| `MaceAttect` | `modules/MaceAttect.java` | Hit-triggered smash (manual hit or attack packet), no auto-TP |
| `MaceDMG` | `modules/MaceDMG.java` | Fake-Y height on hit (Wurst-style), global/manual detect |
| `MaceBreakerPro` | `modules/MaceBreakerPro.java` | Axe+mace double-hit on `AttackEntityEvent` to break shields |
| `SpearKill` | `modules/SpearKill.java` | Spear Blink (packet queue) / Lunge (charged dash) |
| `anti-air-miss` | `modules/AntiAirMiss.java` | Sends 21 vertical packets/tick when a player is near (spam, dubious value) |

### Movement (see `modules-other.md`)

| Name | Class | One-liner |
|------|-------|-----------|
| `Rise` | `modules/Rise.java` | 10× up/down jitter packets every `Delay` ticks |
| `AutoRise` | `modules/AutoRise.java` | Rises when a target is within `Detection Range` (has X/Y bug, see `problems.md`) |
| `Speed` | `modules/SpeedModule.java` | Staircase rise `Cycles × Rise Height` packets per tick |

### Automation

| Name | Class | One-liner |
|------|-------|-----------|
| `OreTracker` | `modules/AutoMineModule.java` | 1053-line Wurst-style tunnel miner, A* + fly-up/dig-down, phase machine |
| `AutoShulkerBox` | `modules/AutoShulkerBox.java` | Place/break shulker loop at crosshair pos, reflection slot swap |
| `ItemGiver` | `modules/CreativeGiveModule.java` | Creative generic item + enchant + attribute spawner |
| `PotionGiver` | `modules/CustomPotionModule.java` | Creative custom splash/lingering potion spawner |
| `AutoTotemToHotbar` | `modules/AutoTotemToHotbar.java` | Moves a totem into hotbar `Slot` every tick |
| `MassTpa` | `modules/MassTpa.java` | Sends `/tpa <everyone>` on a tick timer |
| `freecamtp` | `modules/FreecamTp.java` | Right-click teleports real player to Freecam camera |
| `Hitback` | `modules/Hitback.java` | Replies to `EntityDamageS2CPacket` with an attack packet |

### Chat / info

| Name | Class | One-liner |
|------|-------|-----------|
| `auto-gg` | `modules/AutoGGModule.java` | GG on kill (death-message parse) + chat prefix/suffix injector |
| `auto-msg` | `modules/AutoMsgModule.java` | Random/burst harassing DMs, typo engine, tell-once set |
| `auto-tpa-reject` | `modules/AutoTpaReject.java` | Denies `* wants to be teleported to you` messages |
| `NearestPlayerHUD` | `modules/NearestPlayerHUD.java` | `getInfoString()` nearest-player name+dist (module info, not HUD element) |

Shared helpers: `modules/macekill/{Combat,Config,Movement,Inventory,Targeting,TargetFilter,SortPriority}.java`,
`utils/CreativeGiveUtil.java`. See `architecture.md`.

## Requirements

- Minecraft 1.21.11 + Fabric Loader 0.18.6 + Meteor Client 0.18.6.
- Mace in inventory for all mace modules; spear items for `SpearKill`; pickaxe for miner/shulker.
- Creative mode for `ItemGiver` / `PotionGiver` (checked via `player.getAbilities().creativeMode`).
- OP / permissive server for packet modules — strict anticheat will flag `PositionAndOnGround(false)` teleports.

## Warnings

1. **Ban risk.** `TpMace`, `MaceAttect`, `MaceKillModule`, `Rise`, `Speed`, `AntiAirMiss`, `MassTpa`,
   `AutoMineModule` (fly) all send abnormal packet bursts. Use only on test servers.
2. **Desync risk.** Many modules move the *server* via packets but never update the client
   (or vice versa). See `problems.md` § desync.
3. **Config confusion.** Five mace modules overlap ~80%. Pick one; do not enable several at once
   (they all listen to `TickEvent.Pre` and will fight over slot selection).
4. **Stolen-client drama.** `README.md` contains profanity and an ownership claim; not documentation.
   This `docs/` folder replaces it as the real reference.
