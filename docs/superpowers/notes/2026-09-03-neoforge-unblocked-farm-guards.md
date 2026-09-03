# NeoForge port — unblocked anti-farm/anti-exploit guards

Found during the 2026-09-03 feature-completeness audit (full Fabric `fabric/listeners` +
`fabric/mixin` vs. current `neoforge/listeners` + `neoforge/mixin` diff, 87 Fabric-era files).
Not fixed — recorded here so they aren't lost. None of these crash or degrade the mod; they
just let a player farm skill XP for free through setups mcMMO's Fabric build deliberately
excluded.

- **Enderman/Endermite lure farms pay full Combat/Taming XP.** Fabric's
  `EndermanEndermiteLureMixin` (tags Endermites spawned from a thrown Ender Pearl as
  ineligible) was never ported.
- **Lava/water stone & cobble generators pay full Mining/Excavation XP.** Fabric's
  `FluidBlockFormationMixin` + `LavaFluidStoneFormationMixin` (tag fluid-generated blocks as
  ineligible) were never ported.
- **Piston block-cheat is unblocked.** Fabric's `PistonMoveFlagsMixin` (tags piston-moved
  blocks so re-breaking them doesn't pay XP) was never ported.
- **Snow golem trail farms pay full Excavation XP.** Fabric's `SnowGolemTrailMixin` (tags
  golem-placed snow layers as ineligible) was never ported — a penned snow golem + auto-shovel
  rig is an infinite AFK Excavation farm.

Sized separately and not yet fixed: level-up firework blast damage (Finding 1), Hunter's dead
mob-origin anti-farm gate (Finding 2), and Parkour's inert Snow Walker subskill (Finding 3) —
those three are being planned/ported now since they affect normal play, not just farming.
