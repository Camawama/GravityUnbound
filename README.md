# Gravity Unbound

Every entity gets its own gravity: a direction and a strength, instead of the world's one "down". Walk on walls and
ceilings, around a small cube planet or across the underside of a moving Valkyrien Skies ship, with collision, the
camera, water, projectiles and the rest following your gravity so that someone standing upside down can't tell. The mod
is API-first: its blocks and items are reference implementations as much as gameplay content.

Gravity Unbound was GravityVS before 2.0 (mod id `gravityunbound`); it started from qouteall's GravityChanger and
min2222's Forge port (credits below).

## Requirements

- Minecraft 1.20.1 with Forge 47.4 or newer, on the server and every client.
- **[Camafy](https://www.curseforge.com/minecraft/mc-mods/camafy) 0.1.89 or newer** (required, both sides).
- **Valkyrien Skies 2.4.11** (required).
- Optional, picked up when present: Ad Astra (planet gravity and its fluids), VS Genesis (celestial gravity, through
  Camafy), VMod (ship schematics with fields), Vanilla Backport, Alex's Caves, Pehkui.
- Bundled inside the jar: MixinExtras and MixinSquared.

## What it does

### Where gravity comes from
An entity's gravity is resolved each tick from, lowest priority first:
1. **Base gravity**: world-down, unless an item, effect or `/gravity` changed it.
2. **Per-dimension gravity**: `dimensionGravity` in the config, e.g. `"minecraft:the_nether=up,0.5"`.
3. **Gravity fields** from the three field blocks below; overlapping fields blend, so walking between two plates'
   fields curves smoothly. Normalizer beats plating beats core, and nearer beats farther.
4. **Direct effects**: gravity potions and tipped arrows (a direction, stronger, weaker, inverted) and the handheld
   Gravity Anchors.

Strength goes through Forge's entity-gravity attribute, so it composes with slow falling and other modifiers.

### Field blocks (creative and staff tools)
Configured through their own screen (right-click with an empty hand, creative only, like a command block): field size,
acceleration (Overworld, Moon, Zero-G and Jupiter presets), falloff and a field preview.
- **Gravity Plating** (and **Dense Gravity Plating**): sits on any face of a block and pulls onto that face (or pushes
  away) in a column `level` blocks deep. Per-face settings, "Copy to Connected Plates", waterloggable.
- **Gravity Core**: a spherical field pulling toward its centre; players walk round whatever surrounds it, and
  projectiles can orbit it.
- **Gravity Normalizer**: a cubic safe room with one fixed down; on a ship it covers the ship and turns with it.

### Items and enchantments
- **Gravity Changers** (six directions, and AOE versions) and **Gravity Anchors** (hold one to keep a direction).
- **Surface Cling** (boots enchantment): cling to any block surface, walls, ceilings and ships, moving or not.
- **Sticky blocks**: a Sticky Chest, a Sticky Rail and the Sticky Mimic, blocks placed in any of the 24 orientations.
- **Effects**: Gravity Increase and Decrease, Up, Down, North, South, East and West Gravity, Invert and Reverse.

### What follows your gravity
Players and mobs (collision that turns with gravity, planet-walking, the camera, swimming), projectiles (pulled along
the field, so they can orbit), items, XP, TNT and falling blocks, water and lava (they flow along the field's down,
around a core's planet too), particles, explosions, knockback, elytra, name tags and shadows. With gravity at plain
world-down everything behaves exactly as vanilla.

### Valkyrien Skies
Plating, cores and normalizers work on ships and turn with them, ships can be pulled by cores and plating, a ship's
plating carries its riders' weight, and players can walk anywhere on a moving ship with Surface Cling.

### Celestial gravity (VS Genesis)
In Genesis' space (`genesis:great_unknown`) each body pulls what is near it toward its centre, inverse-square from its
surface out to its reach, and turns it to stand on the body. A world that is a Genesis body (a moon, a planet made with
Camafy's `/planet`) has that body's surface gravity. A ship leaving a planet gets a grace period before that planet
pulls it back. Strength and reach per body come from Camafy (`/planet gravity`).

### Server checks
A player moving with turned gravity (near a field, on plating, in Surface Cling boots) is moved by the position their
client reports, because vanilla's movement check doesn't know a turned body. The server checks those moves itself: a
move into or through a block, the world's or a ship's, or more than 20 blocks in one packet, is refused and logged.
Switches for a run: `-Dgravityunbound.clientMoveSanity=false`, `-Dgravityunbound.clientMoveShips=false`,
`-Dgravityunbound.clientMoveSpeed=false`.

## Commands

`/gravity` needs operator level 2, or the permission `gravityunbound.command.gravity` from a permissions mod (such as
LuckPerms, through Camafy's permissions):
- `/gravity set_base_direction <direction> [entities]`, `set_relative_base_direction`, `randomize_base_direction`
- `/gravity set_base_strength <strength> [entities]`
- `/gravity reset [entities]`, `/gravity view`
- `/gravity celestial ...`: the celestial gravity of Genesis' bodies

## Configuration

Server config `<world>/serverconfig/gravity-apivs.toml` (the file keeps its old name), among others:
- `gravityStrengthMultiplier`, `rotationTime` (ms of a gravity flip's turn), `worldVelocity`, `resetGravityOnRespawn`,
  `voidDamageAboveWorld`, `voidDamageOnHorizontalFallTooFar`.
- Fields: `platingMaxLevel`, `gravityCoreDefaultRange`, `gravityCoreMaxRange`, `normalizerDefaultRange`,
  `normalizerMaxRange`, `gravityAffectsFluids`, `gravityAffectsParticles`, `dimensionGravity`.
- Ships: `gravityCoreAffectsShips`, `gravityPlatingAffectsShips`, `gravityCoreShipForceMultiplier`,
  `shipFieldsAffectWorldFluids`, `shipFieldsCarryWeight`, `shipsCollideWithPlatingPanels`.
- Celestial gravity: `celestialGravity`, `celestialGravityAffectsShips`, `celestialSurfaceGravity`,
  `celestialDepartureGraceSeconds`.

## For developers

The API (`net.camacraft.gravityunbound.api`) gives and reads an entity's gravity direction and strength, and Gravity
Unbound registers itself as Camafy's gravity provider, so other mods can ask Camafy's `compat.gravity.Gravity` without
depending on this mod. More in `docs/`: `GravitySystem_Overview.md` (how the system works), `CapsuleCollider_Explained.md`
(collision), `GravityBlockFramework_Design.md` (rotatable blocks), and the changelog `docs/CHANGELOG.md`.

## Credits

Original mod: https://github.com/qouteall/GravityChanger/tree/1.20.4 \
Original Forge fork: https://github.com/min2222/Gravity-API

## License

GPL-3.0.
