# Fishing Listener (NeoForge) Design

## Goal

Port the Fabric mcMMO `FishingListener` (806 lines) and its two mixins
(`FishingBobberUseMixin`, `FishingWaitTimeMixin`) to NeoForge 1.21.1. This unlocks the whole
Fishing skill: base catch XP, Treasure Hunter's bonus loot roll (with Magic Hunter enchant rolls
and enchanted-book treasures), Shake (reeling in a hooked mob), Ice Fishing, Master Angler's
wait-time reduction, the vanilla-XP boost, the overfishing punishment, and the
`Override_Vanilla_Treasures` config switch.

## Background

The Fabric original (deleted from this repo's `fabric/` tree at commit `d28422305`; recoverable at
`mc/1.21.1` commit `ef5fd3d1a~1`) is pure MC-typed glue over an already-ported MC-free layer:
`FishingManager` (`src/main/java/com/gmail/nossr50/skills/fishing/FishingManager.java`), the
treasure datatypes (`src/main/java/com/gmail/nossr50/datatypes/treasure/`), `ItemSpecBuilder`, and
`MaterialMapStore` are all already ported and unchanged by this task — every `FishingManager`
method the Fabric listener calls exists on this branch today with the same signature (verified by
reading the file directly). This task only has to reconnect Minecraft-typed events/mixins to that
existing layer, the same shape as the Hunter and EntityDamageListener ports.

Vanilla fires no "player caught a fish" event Fabric could hook, so the original used four Sponge
Mixin injectors inside `FishingBobberEntity#use` (`FishingBobberUseMixin`) plus one `@Redirect` in
`FishingBobberEntity#tickFishingLogic` (`FishingWaitTimeMixin`). NeoForge does add one native event
here — `net.neoforged.neoforge.event.entity.player.ItemFishedEvent` — but it does **not** replace
the mixin approach: verified against the actual patched source
(`net.minecraft.world.entity.projectile.FishingHook#retrieve`, the official-mapped rename of
`use`), the event's own `getDrops()` is a copy taken at construction time, and NeoForge's own spawn
loop iterates the *local* `list` variable, never `event.getDrops()` — mutating the event has zero
effect on what actually spawns (this exactly matches the event's own javadoc warning: "You cannot
use this to modify the drops the player will get"). Confirmed this before designing around it,
since trusting the event's surface API without reading the patched source would have reproduced
the exact "silent no-op" failure class this port's process exists to catch (see the
`LivingEntityDamageMixin` `@ModifyReturnValue` incident from Phase 2's EntityDamageListener design).
So this port does **not** use `ItemFishedEvent` at all — it stays a pure mixin port, and it turns
out to port almost verbatim: `retrieve()` still calls the *same*
`CriteriaTriggers.FISHING_ROD_HOOKED.trigger(player, rod, this, list)` at the same two call sites
(a real catch with the resolved `list`, and the entity-hook branch with `Collections.emptyList()`)
that Fabric's `@ModifyArg` targeted — just retargeted to official mappings and the renamed method.

## Mojang-Mapping Verification (against the patched 1.21.1 jars)

- `FishingBobberEntity` → `FishingHook`; its reel method `use(ItemStack)` → `retrieve(ItemStack)`
  (public, unchanged visibility need — no more `@Shadow` tricks required for the hooked-entity
  check: `getHookedIn()` is a public accessor).
- `tickFishingLogic` → `catchingFish(BlockPos)`; the wait-time field is `timeUntilLured` (not
  yarn's `waitCountdown`), the Lure-reduction field is `lureSpeed` (not
  `waitTimeReductionTicks`), and the draw is still `Mth.nextInt(this.random, 100, 600)` followed by
  a straight subtraction — the `@Redirect` + `@Slice`-anchored-on-the-`600`-constant technique
  applies unchanged.
- `ServerPlayerEntity`→`ServerPlayer`, `ServerWorld`→`ServerLevel`, `SheepEntity`→`Sheep`,
  `BoatEntity`→`Boat`, `Identifier`→`ResourceLocation`, `RegistryEntry`→`Holder`,
  `RegistryKeys`→`Registries` (`net.minecraft.core.registries.Registries`),
  `DynamicRegistryManager`→`RegistryAccess` (via `Entity#registryAccess()`),
  `MathHelper`→`Mth`, `FluidTags`→unchanged name, moved package
  (`net.minecraft.tags.FluidTags`). `Sheep#isSheared()`/`#setSheared(boolean)`,
  `ExperienceOrb(Level, double, double, double, int)`, and
  `CriteriaTriggers.FISHING_ROD_HOOKED` (type `FishingRodHookedTrigger`) all confirmed present
  and unchanged in shape.
- `ServerPlayerEntity#raycast(double, float, boolean)` → `Entity#pick(double, float, boolean)`
  (inherited on `ServerPlayer`) — same 3-arg shape, used for Ice Fishing's crosshair block lookup.
- **Enchantment API reworked in 1.21 — this is genuinely new code, not a rename.**
  `ItemEnchantmentsComponent` does not exist; the official class is
  `net.minecraft.world.item.enchantment.ItemEnchantments`, written via
  `EnchantmentHelper.updateEnchantments(ItemStack, Consumer<ItemEnchantments.Mutable>)` (one call,
  no separate builder-then-set the way the Fabric code did with
  `ItemEnchantmentsComponent.Builder`). `Enchantment#isAcceptableItem` → `isSupportedItem(ItemStack)`
  (rename only). **`Enchantment.canBeCombined` no longer exists as a static method** — 1.21's
  data-driven enchantment rework replaced it with an instance `Enchantment#exclusiveSet(): HolderSet<Enchantment>`;
  the "can combine" check (`conflictsWithAny` in the Fabric original) must be reimplemented as
  `!a.exclusiveSet().contains(bHolder) && !b.exclusiveSet().contains(aHolder)` plus an
  identity/self-check — this is genuinely new logic this port has to write, called out explicitly
  so the implementer doesn't go looking for a drop-in replacement that isn't there.
- Registry access for Magic Hunter/book-enchant enumeration:
  `serverPlayer.registryAccess().registryOrThrow(Registries.ENCHANTMENT)` returns
  `Registry<Enchantment>`; iterate via `.holders(): Stream<Holder.Reference<Enchantment>>`
  (replaces yarn's `getIndexedEntries()`); single lookup via
  `.getHolder(ResourceLocation): Optional<Holder.Reference<Enchantment>>` (replaces `getEntry`).
- **`luckOfTheSeaLevel` needs no new Minecraft code at all.**
  `src/main/java/com/gmail/nossr50/platform/PlatformItem.java:120-130` already has
  `getEnchantmentLevel(ResourceKey<Enchantment> enchantmentKey)`, resolving via
  `handle.getEnchantments()` and `Holder<Enchantment>#is(ResourceKey)` — an exact drop-in for the
  Fabric original's manual lookup: `new PlatformItem(rod).getEnchantmentLevel(Enchantments.LUCK_OF_THE_SEA)`.
  Note `Enchantments.LUCK_OF_THE_SEA` is now a `ResourceKey<Enchantment>`, not a direct instance
  (matches `getEnchantmentLevel`'s parameter type). `PlatformItem`'s own class javadoc explicitly
  flags that enchantment-*writing* adapters are not yet built ("Those get their own adapter once a
  consumer is ported") — confirming Magic Hunter's and the book treasure's enchantment writes stay
  new MC-typed code in the new listener, not something to look for in `PlatformItem` first.

## Files

- **Create** `src/main/java/com/gmail/nossr50/neoforge/listeners/FishingListener.java` — full port
  of the Fabric original's 806 lines: `onFishCaught`, `overrideVanillaTreasures`, `isVanillaFish`,
  `punishOverfishing`, `boostVanillaXp`, `maybeCatchTreasure`, `applyBookEnchantment`,
  `warnUnknownWhitelistedEnchantments`, `maybeApplyMagicHunter`, `conflictsWithAny` (reimplemented
  per the `exclusiveSet()` note above), `applyRandomWear`, `onEntityHooked`, `shearIfWool`,
  `tryIceFishing`, `sitsOverWater`, `meltIce`, `resolveWaitCountdown`, `masterAnglerWaitTimes`,
  `luckOfTheSeaLevel` (now a one-line `PlatformItem` delegate). Package-private class with **no
  `register()` method** — unlike every other listener in this port, this one has nothing to
  register on `NeoForge.EVENT_BUS`. Every seam here is mixin-driven (mixins apply automatically via
  `mcmmo.mixins.json`, not via a Java call), so a `register()` that does nothing would be dead
  ceremony, not consistency. Do not add one, and do not add a wiring line to `McMMOMod`.
- **Create** `src/main/java/com/gmail/nossr50/neoforge/mixin/FishingHookRetrieveMixin.java` — the
  four injectors currently in `FishingBobberUseMixin`, retargeted to `FishingHook#retrieve`:
  `@ModifyArg` on `CriteriaTriggers.FISHING_ROD_HOOKED.trigger(...)`'s 4th argument (`allow = 2`,
  matching the two call sites verified above) calling `FishingListener.onFishCaught`; `@Inject`
  before `this.pullEntity(this.hookedIn)` calling `FishingListener.onEntityHooked`; `@Inject` at
  `HEAD` calling `FishingListener.tryIceFishing`; `@ModifyArg` on `ExperienceOrb`'s constructor
  (index 4) calling `FishingListener.boostVanillaXp`.
- **Create** `src/main/java/com/gmail/nossr50/neoforge/mixin/FishingHookWaitTimeMixin.java` — the
  one `@Redirect` currently in `FishingWaitTimeMixin`, retargeted to `FishingHook#catchingFish`:
  `@Shadow @Final private int lureSpeed;`, `@Redirect` on `Mth.nextInt(Random, int, int)` sliced
  from the `600` constant, calling `FishingListener.resolveWaitCountdown`.
- **Modify** `src/main/resources/mcmmo.mixins.json` — register both new mixins (alphabetical order).
- **No change** to `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` — nothing to wire (see
  above).
- **Create** `src/test/java/com/gmail/nossr50/neoforge/listeners/FishingListenerTest.java` — pinning
  tests for the MC-typed logic that doesn't require a live mixin: `overrideVanillaTreasures` (the
  four exempt fish vs. replacement), `conflictsWithAny`'s reimplemented `exclusiveSet()` logic
  (this is new code, so it needs its own coverage — no Fabric-original test to diff against),
  `shearIfWool`, `sitsOverWater`/`meltIce` gating via mocks, and `masterAnglerWaitTimes`'s gates
  (main-hand-rod-required, off-hand-rod-excluded).

## Global Constraints

- `ItemFishedEvent` is not used anywhere in this port — confirmed dead for mutation, and using it
  read-only for anything the mixins already handle would be two mechanisms doing overlapping work.
  Don't reach for it "just to use the native NeoForge event" without a concrete gap the mixins leave
  unfilled.
- The criterion-trigger `@ModifyArg` must use `allow = 2` (both the real-catch and empty-list
  entity-hook call sites) — verified via the patched source, not assumed from the Fabric original's
  own `allow` value, which could differ per-version. Confirm the actual bytecode call count during
  implementation rather than copying `2` on faith.
- `conflictsWithAny`'s `exclusiveSet()`-based reimplementation must preserve the Fabric original's
  exact semantics: two enchantments conflict if either one's `exclusiveSet()` contains the other,
  or if they are the same enchantment. Get this wrong and Magic Hunter can double-roll the same
  enchantment or produce illegal combinations (e.g. two exclusive fishing-rod enchantments) with no
  visible symptom until a player notices — the same "silent wrong, not loud wrong" failure class
  this whole port's process is built to catch.
- `luckOfTheSeaLevel` must delegate to `PlatformItem#getEnchantmentLevel`, not re-derive the lookup
  — this file already exists and is already used elsewhere in the NeoForge branch; re-deriving it
  here would be the same kind of "two places, one silently drifts" trap `masteryKeyOf` was
  called out for on the Hunter port.
- `FishingManager`, the treasure datatypes, `ItemSpecBuilder`, and `MaterialMapStore` are not
  touched by this task — every method this listener calls into them already exists with the
  signature the Fabric original expects (verified directly against `FishingManager.java`).
