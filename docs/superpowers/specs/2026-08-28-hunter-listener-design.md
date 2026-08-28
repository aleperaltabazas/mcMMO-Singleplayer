# Hunter Kill-Counter + Trophy Hunter (NeoForge) Design

## Goal

Port the Fabric mcMMO `HunterListener`'s kill-counting/XP-award logic and the Trophy Hunter
bonus-loot reroll to NeoForge 1.21.1, replacing the currently-shipped stub (only
`masteryKeyOf` is ported; see `src/main/java/com/gmail/nossr50/neoforge/listeners/HunterListener.java`).
This is the last unported piece of the Hunter skill's damage/reward pipeline —
`EntityDamageListener#applyHunterMastery` already spends the mastery bonus this task must
produce.

## Background

The Fabric original (408 lines, `src/main/java/com/gmail/nossr50/fabric/listeners/HunterListener.java`,
deleted from this repo at commit `d28422305` when the `fabric/` tree was removed, recoverable at
`mc/1.21.1` commit `ef5fd3d1a~1`) has two seams:

1. **Kill counting + XP** (`onDeath`): registered on Fabric's `ServerLivingEntityEvents.AFTER_DEATH`,
   which fires from inside `LivingEntity#onDeath` *after* `drop()` has already run. Counts a mastery
   kill, awards Hunter skill XP, and announces a mastery-tier crossing.
2. **Trophy Hunter loot reroll** (`onLootDropped`): a mixin
   (`fabric/mixin/LivingEntityTrophyHunterMixin`, 103 lines) injects at `TAIL` of
   `LivingEntity#dropLoot(DamageSource, boolean)` and re-invokes that same method a second time
   (guarded by an instance re-entrancy flag) if the roll succeeds.

Both seams share one gate chain (`qualifyingKiller`, 4 gates: player-attributor, PVE/PVP switch,
transient/manufactured exclusion, spawn-origin marker) and one key function (`masteryKeyOf`, already
ported) — a kill that doesn't count must never pay loot either, and vice versa.

## NeoForge Event Mapping (verified against the patched 1.21.1 jar)

NeoForge's `LivingDropsEvent` fires once per server-side death, from `CommonHooks.onLivingDrops`
inside `LivingEntity#dropAllDeathLoot`, strictly *after* `dropFromLootTable`, `dropCustomDeathLoot`,
`dropEquipment`, and `dropExperience` have all already run. This is the same "after loot" timing
Fabric needed two separate mechanisms to reach — on NeoForge, both Fabric seams collapse into **one**
`LivingDropsEvent` listener. `LivingDropsEvent` carries `getSource()` (the `DamageSource`) directly.

For the Trophy Hunter reroll, a Mixin `@Invoker` accessor exposes `dropFromLootTable(DamageSource,
boolean)` directly, so the bonus roll calls the loot table itself rather than the outer
`dropAllDeathLoot`/`dropLoot`. This invoker does not re-post `LivingDropsEvent` and does not
recurse — no re-entrancy guard is needed, unlike the Fabric original's `mcmmo$inBonusRoll` field.

`CopperGolemEntity` does not exist in this exact jar (1.21.1 predates it), so the Fabric original's
`MANUFACTURED_SPECIES` set of `{snow_golem, copper_golem}` narrows to just `{snow_golem}`. The iron
golem check stays a direct `instanceof IronGolem` + `isPlayerCreated()` test, unchanged.

## Files

- **Modify** `src/main/java/com/gmail/nossr50/neoforge/listeners/HunterListener.java` — expand from
  the current stub (package-private → keep package-private, this is wiring code, not public API) to:
  - `register()`: `NeoForge.EVENT_BUS.addListener(HunterListener::onLivingDrops)`
  - `onLivingDrops(LivingDropsEvent event)`: runs the gate chain once via `qualifyingKiller`, then
    does both the kill-count/XP-award (former `onDeath` body) and the Trophy Hunter roll (former
    `onLootDropped` body) against the same qualifying killer — no need to run the gate chain twice
    per death.
  - `qualifyingKiller(LivingEntity victim, DamageSource source)`: ported verbatim from the Fabric
    original, `ServerPlayerEntity` → `ServerPlayer`, `source.getAttacker()` →
    `source.getEntity()` (NeoForge/Mojang-mapped `DamageSource` accessor — confirms to the same
    "arrow resolves to shooter" semantics already relied on in
    `EntityDamageListener`'s `Projectile`/`AbstractArrow` handling).
  - `hunterPlayer(ServerPlayer killer)`: ported verbatim (`UserManager.getPlayer`, null/no-manager
    guard).
  - `isManufactured(LivingEntity victim)`: ported with `MANUFACTURED_SPECIES = Set.of("minecraft:snow_golem")`
    (narrowed per the jar-version fact above) and `IronGolem` (Mojang mapping of `IronGolemEntity`)
    kept as its own `instanceof` arm.
  - `announceMastery`, `announceFirstCountedKill`, `announceFirstTrophy`,
    `resetFirstKillLogForTesting`: ported verbatim (same `NotificationManager`/`SoundManager`/
    `McMMOMod.LOGGER` calls already used elsewhere in this codebase).
  - Keep `masteryKeyOf` exactly as currently shipped (no change — `EntityDamageListener` already
    depends on it).
- **Create** `src/main/java/com/gmail/nossr50/neoforge/mixin/LivingEntityDropFromLootTableAccessor.java`
  — a `@Mixin(LivingEntity.class)` `@Invoker`-annotated interface exposing
  `dropFromLootTable(DamageSource, boolean)`, following this codebase's existing accessor-mixin
  pattern (see `HoeTillingActionsAccessor.java` for the shape to match: interface, `@Invoker`,
  package `neoforge.mixin`).
- **Modify** `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` — add
  `HunterListener.register();` to the constructor's listener-wiring block, alongside the other
  `register()` calls.
- **Modify** the mixin config JSON (wherever `HoeTillingActionsAccessor`/`LivingEntityDamageMixin`
  etc. are registered — locate via `grep -rn "LivingEntityDamageMixin" src/main/resources`) to add
  the new accessor mixin.
- **Create** `src/test/java/com/gmail/nossr50/neoforge/listeners/HunterListenerTest.java` — a
  pinning test covering: each of the 4 gates individually refusing (mirrors
  `EntityDamageListenerHunterTest`'s style), a full qualifying kill incrementing the counter and
  awarding XP, a mastery-threshold crossing firing the announcement, and the manufactured-species
  exclusion (snow golem always excluded, iron golem excluded only when player-created).

## Global Constraints

- Gate order and semantics must match the Fabric original exactly: player-attribution → PVE/PVP
  switch → transient/manufactured exclusion → spawn-origin marker. Both kill-counting and Trophy
  Hunter consume the *same* `qualifyingKiller` call per death (never re-derive the chain a second
  time — this is the exact "drift" bug the Fabric original's own javadoc calls out as a trap this
  port has already hit multiple times).
- `masteryKeyOf` is not touched — it's a two-consumer function
  (`HunterListener`/`EntityDamageListener.applyHunterMastery`) and any change here that diverges from
  what `EntityDamageListener` expects silently breaks the mastery damage bonus.
- Trophy Hunter's bonus roll must run **the invoker directly** (`dropFromLootTable`), never the
  outer `dropAllDeathLoot`/`dropLoot`, to avoid re-triggering `LivingDropsEvent` (which would
  re-enter this very listener).
- No client-side firing to guard against — `LivingDropsEvent` only fires server-side (verify this
  against the patched jar/NeoForge javadoc during implementation; if wrong, add the same
  `!level().isClientSide()`-style guard already used elsewhere in this codebase).
