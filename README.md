# Voxy World Gen V2

![Logo](src/main/resources/logo.png)

This is a rewrite of my old Voxy World Gen mod, this mod is NOT a fork of the passive chunk generator mod and instead is a entirely different mod.

## Features

- Generates chunks very fast in the background and auto-ingest them with voxy.
- Configurable generation speed and queue size.
- Tellus integration. https://github.com/Yucareux/Tellus

## Dependencies

- **Minecraft**: 1.21.1 (Tested on 1.21.1, anything less or more is considered unstable and may not work)
- **Fabric Loader**: >= 0.16.0
- **Java**: 21 (Required)
- **Fabric API**
- **Cloth Config**: >= 15.0.127

## Building

This project requires Java 21.

```bash
# Clone the repo
git clone https://github.com/iSeeEthan/voxy_worldgen_v2.git

# Build
./gradlew build
```

Artifacts are output to `build/libs/`.

## Configuration

Config files are located in `config/voxyworldgenv2.json`.

- `generationSpeed`: Controls how many chunks are processed per tick.
- `enablePassive`: Toggle background generation.

## License

CUSTOM, refer to LICENSE file for more information.
