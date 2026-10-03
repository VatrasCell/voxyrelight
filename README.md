# Voxy Sky-Light Repair

A small client-side Fabric mod that repairs missing sky light in existing [Voxy](https://github.com/MCRcortex/voxy)
LOD data, so regions that Voxy renders dark ("as if it were night") look right again without flying over them again.

This project is not affiliated with or endorsed by Voxy or its author.

## Background

Voxy stores each voxel together with its sky and block light. Regions whose chunks were imported without sky light
data (for example from cached fake chunks) end up with sky light 0 above the surface and are rendered dark. This mod
walks over the stored LOD 0 sections column by column, recalculates the sky light from the top down like vanilla does
(air and glass keep light 15, water, leaves and ice attenuate it per block) and writes the result back through
Voxy's own update path, so the higher LOD levels and the render data are rebuilt as well.

## Requirements

| Component     | Version                |
|---------------|------------------------|
| Minecraft     | 26.2                   |
| Fabric Loader | 0.19.3 or newer        |
| Fabric API    | any version for 26.2   |
| Voxy          | 0.2.19-beta (exactly)  |
| Java          | 25 or newer            |

Voxy has to be installed separately. It is not included in this mod and is not part of this repository.
The mod is client-side only.

## Installation

1. Install Fabric Loader, Fabric API and Voxy.
2. Download `voxyrelight-<version>.jar` from the [releases](https://github.com/VatrasCell/voxyrelight/releases)
   and put it into your `mods` folder.

## Usage

**Back up your `.voxy` folder before running a repair.** The repair rewrites Voxy's stored data.

| Command                                  | Description                                                      |
|------------------------------------------|------------------------------------------------------------------|
| `/voxyrelight scan <radius>\|all`        | Dry run: reports what would be changed, writes nothing.          |
| `/voxyrelight fix <radius>\|all [force]` | Repairs dark columns. With `force`, all columns are recalculated. |
| `/voxyrelight cancel`                    | Cancels the running job.                                         |
| `/voxyrelight status`                    | Shows the running job.                                           |

The radius is counted in LOD 0 sections (32 blocks each) around your position, up to 1024. `all` processes the whole
Voxy world of the current dimension. Only one job runs at a time, and it is cancelled automatically when you leave
the world. `force` also touches columns that look intact, which can remove light that entered from the side, for
example at the edge of shallow water.

## Building

```
./gradlew build
```

The JAR ends up in `build/libs`. A JDK 25 is required. Voxy is resolved from Modrinth Maven
(`https://api.modrinth.com/maven`). To build against a local Voxy JAR instead, pass `-Pvoxy_jar=path/to/voxy.jar`.

## License

Licensed under the [GNU Lesser General Public License v3.0](COPYING.LESSER) (together with the
[GNU General Public License v3.0](COPYING) it refers to).

Voxy is "All rights reserved" by its author. This mod only compiles against Voxy and does not contain or
redistribute any Voxy code.
