# Qazr-Meteor-Addon 0.1 (*Alpha*)

![Quzr Addon Icon](src/main/resources/assets/icons/icon.png)

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
