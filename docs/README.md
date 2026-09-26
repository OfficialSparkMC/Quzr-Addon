# Qazr-Addons Docs

Meteor Client addon (`com.macekill.addon`, mod id `qazr-addons`) for Minecraft 1.21.11 / Meteor 0.18.6.
Category in-game: `Qazr1234` (`MaceKillAddon.CATEGORY`).

Source of truth is `src/` — the jars under `libs/` (`MeteorCilent-1.21.11-0.18.6.jar`, `orbit-0.2.4.jar`) are
**local compile-only inputs only** and are gitignored (see `.gitignore`). Never decompile them for docs;
read `src/main/java/**` instead.

## Docs index

| File | What it covers |
|------|----------------|
| `overview.md` | What the addon is, module table, requirements, warnings |
| `architecture.md` | Entry point, shared `macekill/*` helpers, per-module patterns |
| `modules-mace.md` | All mace / spear combat modules, settings, packet flow |
| `modules-other.md` | Movement, automation, chat, creative, HUD modules |
| `build.md` | Java 21 + Fabric Loom build, `fabric.mod.json`, version bump |
| `commands.md` | Client chat commands (`.tp <player>`) |
| `problems.md` | Full code-health / bug / ban-risk report with `file:line` refs |
| `ai-agents.md` | **Mandatory instructions for any AI agent editing this repo** |

## Quick start for Human()

1. Read `overview.md` for the module list.
2. Read `architecture.md` before touching `macekill/Combat.java`, `Movement.java`, `Inventory.java`.
3. Read `build.md` to compile (`./gradlew build`, output in `build/libs/`).
4. Read `problems.md` before enabling anything on a real server — most combat modules are
   packet exploits and **will get you kicked/banned** on anticheat servers.

## Quick start for AI agents

You **must** read `ai-agents.md` first. It tells you how to keep these docs in sync with `src/`.
