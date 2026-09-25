# Build

## Toolchain

- Java 21 (`build.gradle:41-50`, `fabric.mod.json:23` `java >= 21`).
- Fabric Loom `1.14-SNAPSHOT` (`build.gradle:2`), Gradle wrapper (`gradlew`).
- `org.gradle.jvmargs=-Xmx2G` (`gradle.properties:1`).

## Versions (`gradle.properties`)

| Prop | Value |
|------|-------|
| `minecraft_version` | `1.21.11` |
| `yarn_mappings` | `1.21.11+build.3` |
| `loader_version` | `0.18.6` |
| `meteor_version` | `0.18.6` (declared but **unused** in `build.gradle`) |
| `mod_version` | `1.0.0` |
| `maven_group` | `com.macekill` |
| `archives_base_name` | `Qazr-Addons` |

`version = mod_version`, `archivesName = archives_base_name` (`build.gradle:5-10`).

## Dependencies (`build.gradle:26-33`)

```groovy
minecraft "com.mojang:minecraft:${project.minecraft_version}"
mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
modCompileOnly files("libs/MeteorClient-${project.minecraft_version}-${project.meteor_version}.jar")
compileOnly files('libs/orbit-0.2.4.jar')
```

- `libs/` is **gitignored** (`.gitignore:5`) — every fresh clone must be given these two jars
  manually or the build fails. Filenames follow `MeteorClient-<mc>-<meteor>.jar` from
  `gradle.properties`. Preferred fix: depend on Meteor from
  `https://maven.meteordev.org/releases` (already in `repositories`, currently unused).
- `processResources` expands `${version}` into `fabric.mod.json`.
- `JavaCompile release 21`, toolchain 21, `withSourcesJar()`.

## fabric.mod.json (`src/main/resources/fabric.mod.json`)

- `id: qazr-addons`, `version: ${version}`, `environment: client`.
- Entrypoint `meteor: [com.macekill.addon.MaceKillAddon]`.
- No `mixins` entry (empty `qazr-addons.mixins.json` is kept but unwired since 2026-09-25).
- `depends: {java: ">=21", minecraft: "~1.21.11", fabricloader: ">=0.18.6", meteor-client: ">=0.18.6"}`.
  meteor-client is a **range**, not an exact pin: a bare `"0.18.6"` is an exact match in
  Fabric and rejects newer Meteor builds (e.g. `1.21.11-86`), blocking launch with
  "Some of your mods are incompatible". `>=0.18.6` accepts both old and new versioning.
- `custom."meteor-client:color": "255,80,80"`.

## Commands

```bash
./gradlew build        # full build, jar -> build/libs/Qazr-Addons-1.0.0.jar (+ -sources.jar)
./gradlew runClient    # loom test client (needs Meteor jar present)
./gradlew --refresh-dependencies build   # after changing versions
```

## Version bump checklist

1. `gradle.properties` → `mod_version`.
2. `fabric.mod.json` needs no edit (`${version}` expanded at build).
3. Rebuild; confirm `build/libs/` artifact name.
4. Update `docs/overview.md` version table + `docs/ai-agents.md` last-verified line.
5. If MC/Meteor changes: update `minecraft_version`, `yarn_mappings`, `loader_version`,
   `meteor_version`, local `libs/*.jar` names in `build.gradle`, and re-verify every
   reflection string in `MaceAttect` (`field_12871/type`, `field_29170/ATTACK`, `field_12870/entityId`)
   and `PlayerInventory.selectedSlot`.

## Known build smells (detail in `problems.md`)

- ~~Typo `MeteorCilent` in jar name~~ — fixed 2026-09-25 (renamed to `MeteorClient`).
- ~~`meteor_version` prop unused~~ — fixed 2026-09-25 (filename now uses it).
- ~~Wildcard `depends` on minecraft + meteor-client~~ — fixed 2026-09-25 (pinned).
- ~~Empty mixins wired~~ — fixed 2026-09-25 (unwired, file kept).
- Empty access widener still present; `libs/`, `build/`, `.gradle/` committed to git history despite `.gitignore`.
