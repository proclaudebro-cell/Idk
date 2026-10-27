# Hollow Night

Co-op wave shooter vs. zombies on a moonlit city map. Family-friendly: no blood — zombies
dissolve into sparkles. Four hitscan guns, a boss every 5th wave, and three cutscene powers.

## Controls
- Mouse: aim / shoot (hold for auto) &nbsp;|&nbsp; **1-4** switch weapon &nbsp;|&nbsp; **R** reload
- **Z** Radiant Nova, **X** Chrono Break, **C** Skyfall (charge builds from kills)

## Run
1. Install [Rojo 7.x](https://rojo.space) and its Studio plugin.
2. `rojo serve` in this folder, click Connect in Studio, press Play.
   `Lighting.Technology = Future` comes from `default.project.json`; if your Rojo
   doesn't apply it, set Lighting > Technology to Future by hand.

## Layout
- `src/shared/Config.luau` — weapons, zombies, powers, wave tuning
- `src/server/` — map + lighting, zombie AI, weapons (server-authoritative), powers, wave loop
- `src/client/` — shooter camera/recoil/tracers, HUD, cutscene director

## Making it look even better
Graphics here come from Future lighting, atmosphere, bloom, DoF, sun rays, colour grade,
dynamic lights and built-in PBR materials. To go further: add custom meshes/MaterialVariants,
sound ids (none are set), and animations.
