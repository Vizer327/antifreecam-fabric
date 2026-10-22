# AntiFreecam Fabric

Server-side protection against **freecam**, **ESP** and **X-ray** for Fabric (Minecraft 26.3).
Cheats can only show what the server sends — so the server stops sending what a player can't actually see.

- **Players** behind walls are not sent (raytraced from the eyes).
- **Entities** behind walls are not sent: mobs, animals, villagers, item frames, armor stands, minecarts,
  dropped items... (whitelist or blacklist mode).
- **Blocks** a player can't see are replaced in the chunk data until he does: chests, signs, furnaces — or, in
  whitelist mode, everything that isn't natural terrain (bases, mineshafts, ores).
  Placing, breaking, chest lids, cracks, break particles and block sounds of hidden blocks are filtered too.
- **Ultra mode** fills closed rooms and stashes with stone in the data sent to the client, so freecam sees no
  cavity at all.

Only the server needs it. Clients don't install anything.

## Install

1. Fabric Loader, Fabric API, Java 25.
2. Put `antifreecam-fabric-<version>.jar` into `mods/`. Remove PlayerCulling and AntiXray if you have them —
   this mod contains both.
3. Start the server once, then edit `config/antifreecam.toml` (every option is explained in the file).

## Presets

`preset = "low" | "medium" | "high"` at the top of the config; any setting written in the file overrides the preset.

| | low | medium (default) | high |
|---|---|---|---|
| Players behind walls | hidden, name tag rule 64 blocks | same | line of sight only |
| Locator bar | vanilla | vanilla | only visible players |
| Entities | blacklist (mobs, frames, stands, items...) | whitelist (all but technical) | whitelist |
| Blocks | blacklist (storage, workstations, signs...) everywhere | whitelist underground (bases, mineshafts, ores) | underground only stone and deepslate stay visible + every closed underground room and the inside of buildings open to caves filled with stone |
| Nether / End | blacklist | blacklist | blacklist |
| Cost | lowest | medium | higher |

## Commands

Permission `antifreecam.command` (operators by default):

- `/antifreecam reload` — re-read the config ([players], [entities], [scheduler] apply at once, blocks after a restart)
- `/antifreecam status` — culling threads and how much is hidden from each player
- `/antifreecam hidden <player>` — which players are hidden from him right now
- `/antifreecam resend [player]` — send all loaded chunks again (see new block settings without relogging)
- `/antifreecam pocket [x y z]` — an air block of a room (default: where you stand): is the room filled for others,
  and if not, why (open to the surface, too big...). You yourself don't count

Permission `antifreecam.bypass` — sees everything (operators do **not** bypass, so you can test it).
A scoreboard tag (`exempt-tag` in the config) does the same for players and is handy for cutscenes.

## How to test

Use a second account. Go far away first (chunks have to be sent again after a config change), then fly
with freecam into a cave base / behind a wall: players, entities and hidden blocks must not be there,
and must appear as soon as you can actually see them.

## Building

```
./gradlew build
```
The jar is in `build/libs/`. Requires JDK 25.

## Credits and license

AGPL-3.0-or-later. Built from [PlayerCulling](https://github.com/MinceraftMC/PlayerCulling) (AGPL-3.0) by
pianoman911 and booky10, [AntiXray](https://github.com/DrexHD/AntiXray) (MIT) by DrexHD — a port of Paper's
Anti-Xray — and [OcclusionCulling](https://github.com/LogisticsCraft/OcclusionCulling) (MIT).
See `THIRD_PARTY_LICENSES.md`.
