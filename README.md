# Qazr-Meteor-Addon 0.1 (*Alpha*)

![Quzr Addon Icon](src/main/resources/assets/icons/icon.png)
<br>
minecraft meteor addon, made by Me. mostly mace pvp stuff plus some handy utilities.


## Features

**Mace combat**
- TpMace — teleports to the target and smashes down with the mace, works underground too
- MaceAttect — same smash but it triggers when YOU hit someone, no auto targeting
- MaceAura / macemiss / MaceMissLite — auto-attack nearby players with the mace
- Totem bypass — pops stacked totems, first in one big burst then keeps going one by one until they die
- MaceBreakerPro — axe + mace combo that breaks shields
- MaceDMG — fakes extra fall height for bigger mace hits

**Movement**
- PlayerTp — teleports you to the nearest player, or use `.tp <name>` in chat
- Rise / Speed / AutoRise — quick vertical movement tricks
- AdvancedNoFall — never take fall damage, doesn't mess with your mace smashes
- FreecamTp — fly the freecam somewhere, right click, you're there

**Automation**
- OreTracker — finds ores nearby and digs its way to them by itself
- AutoShulkerBox — places and breaks shulkers over and over for farming
- ItemGiver / PotionGiver — spawn custom gear and potions in creative mode
- AutoTotemToHotbar — keeps a totem in your hotbar slot
- MassTpa / auto-tpa-reject — tpa spam and auto deny
- Hitback — auto hits back whoever hits you

**Chat stuff**
- auto-gg — sends a gg message when you kill someone
- auto-msg — messages players automatically
- NearestPlayerHUD — shows the closest player and how far they are
> heads up: most of the combat and movement stuff sends weird packets, so only use
> it on servers where that's allowed. you will get banned anywhere with anticheat.


## Installation

### 1. Clone the repository

```bash
git clone https://github.com/turbocorex/Quzr-Addon.git
cd Quzr-Addon
```

### 2. Install dependencies (*arch-linux* or *arch based linux*)

Install Java 21:

```bash
sudo pacman -S jdk21-openjdk
```

Set Java 21 as the active JDK:

```bash
sudo archlinux-java set java-21-openjdk
```

Verify the Java version:

```bash
java -version
```

### 3. Build the project

Run the Gradle build:

```bash
./gradlew build
```

### 4. Build output

After a successful build, the compiled `.jar` file can be found in:

```text
build/libs/
```

## Development

To clean the previous build:

```bash
./gradlew clean
```

To build again:

```bash
./gradlew build
```

## Official *discord* server:
https://discord.gg/M389NKHFY


## AI agents
Read the docs files
