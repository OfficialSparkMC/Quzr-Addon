# Qazr-Addon

![Quzr Addon Icon](src/main/resources/assets/icons/icon.png)
<br>
> Meteor-Client addon, made by Me. Mostly mace pvp stuff plus some handy utilities.


## Features

**Mace combat**
- MaceAttect — same smash but it triggers when YOU hit someone, no auto targeting; Cave Safe handles low roofs
- Totem bypass — pops stacked totems in one burst, then keeps going one by one until they die; cave mode picks the highest clear air pocket up to 22 blocks (roofs are pierced) and pads the ascent to the packet-5 cap
- Blacklist — `.blacklist add/remove/dis/list` keeps a kill list (saved across restarts); while the module is on, listed players within range (default 60 blocks) get smashed with UltraAura's code
- MaceBreakerPro — axe + mace combo that breaks shields
- UltraAura — teleports to the player closest to your crosshair, smashes them and teleports back
- ArrowKiller — teleports bow/trident shots onto the target (enable AntiPop)
- InfiniteReach — attack entities and use/break blocks far away, with separate entity/block reach and a far-block hover box (far breaks show Minecraft's breaking animation, custom texture packs included)

**Movement**
- PlayerTp — teleports you to the nearest player, or use `.tp <name>` in chat
- AntiPop — the server never sees you touch the ground, so no fall damage
- FreecamTp — fly the freecam somewhere, right click, you're there

**Automation**
- OreTracker — finds ores nearby and digs its way to them by itself
- AutoShulkerBox — places and breaks shulkers over and over for farming
- ItemGiver / PotionGiver — spawn custom gear and potions in creative mode
- AntiCreeper — kills creepers before they blow up
- MassTpa / auto-tpa-reject — tpa spam and auto deny
- Hitback — auto hits back whoever hits you

**Chat stuff**
- auto-gg — sends a gg message when you kill someone
- auto-msg — messages players automatically
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
[https://discord.gg/M389NKHFY](https://discord.gg/H8BaMhuDzc)
