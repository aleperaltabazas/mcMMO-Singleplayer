# Cooking + Smelting Listener (NeoForge) Implementation Plan

See `docs/superpowers/specs/2026-08-30-cooking-smelting-listener-design.md` for full background,
Mojang-mapping verification, and design rationale. This plan assumes that doc has been read.

## Global Constraints

- Re-verify every mixin target signature with `javap -p -c` against
  `build/moddev/artifacts/neoforge-21.1.248-merged.jar` before writing the mixin — the design
  doc's signatures were read from that jar during planning but must be re-confirmed, not
  copy-pasted blind.
- `allow = 1` on every call-site-anchored injector; HEAD/RETURN injections into named methods
  need neither `allow` nor `require`.
- Mixin interfaces (none expected in this plan) must stay pure-abstract if any are added — see
  the design doc's note on `LivingEntityDropFromLootTableAccessor`.
- Unit tests cannot exercise real Mixin application (no ModLauncher wiring under plain
  `./gradlew test`) — mixin correctness is verified structurally (reflection on the mixin
  class/target signatures, matching this project's existing `LivingEntityDropFromLootTableAccessorTest`
  pattern) plus the mandatory boot check in Task E.
- Register new listeners/mixins exactly where existing ones are wired: `register()` static
  methods called from `McMMOMod`, mixin classes added to `src/main/resources/mcmmo.mixins.json`.

## Task A: Furnace core — owner tracking, Smelting XP, Second Smelt, Fuel Efficiency

**Files:**
- `src/main/java/com/gmail/nossr50/neoforge/listeners/SmeltingListener.java` (new)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/AbstractFurnaceSmeltMixin.java` (new)
- `src/main/resources/mcmmo.mixins.json` (add `AbstractFurnaceSmeltMixin`)
- `src/test/java/com/gmail/nossr50/neoforge/listeners/SmeltingListenerTest.java` (new)
- `src/test/java/com/gmail/nossr50/neoforge/mixin/AbstractFurnaceSmeltMixinTest.java` (new)

**Work:**

1. Port `SmeltingListener` from the Fabric original
   (`git show d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/SmeltingListener.java`),
   keeping its structure and doc comments' *reasoning* intact (the mutual-exclusivity ordering,
   the "one owner map, two managers" note, the thread-local for Understanding the Art), updating
   only MC-API references:
   - `FURNACE_OWNERS: Map<Long, UUID>`, `owner(BlockPos)`, `materialConfigString(ItemStack)`
     (package-visible, `CookingListener` will call it) — ports unchanged.
   - `register()`: `NeoForge.EVENT_BUS.addListener(SmeltingListener::onUseBlock)` for owner
     tracking (mirror `SuperAbilityListener#onUseBlock`'s `PlayerInteractEvent.RightClickBlock`
     pattern — guard `!event.getLevel().isClientSide()`, check
     `event.getLevel().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity`, never
     cancel). Also register the smelted-ore-product indexing (see step 3).
   - `onFurnaceSmelt(ServerLevel, BlockPos, ItemStack)`: unchanged logic, called from the mixin
     (step 2).
   - `onSmeltComplete(BlockPos, ItemStack)`: unchanged logic (Second Smelt), called from the
     mixin.
   - `boostFuelTime(int, BlockPos, ItemStack)`: unchanged logic, but now called from a
     `FurnaceFuelBurnTimeEvent` listener instead of a mixin (step 4) — same signature, same
     `Smelting first, then explicit CookingManager#isCookable check` gate (**not** a blanket
     `else`, per the Fabric javadoc's explicit warning about non-smeltable-but-non-cookable
     inputs like sand/cobblestone/logs).
   - `beginFurnaceExtract` / `endFurnaceExtract` / `boostVanillaXp`: unchanged logic (these are
     called from the `FurnaceResultSlotMixin` in Task B, not this task's mixin).
   - `indexSmeltedOreProducts` / `hasOreBlockInput` / `oreBlockItems`: port using
     `net.minecraft.world.item.crafting.SmeltingRecipe`, `RecipeHolder<?>`, `Ingredient`,
     `SingleRecipeInput` (1.21.1 rename of `SingleStackRecipeInput`) as confirmed in the design
     doc. `Registries.ITEM` → NeoForge/Mojang `BuiltInRegistries.ITEM`.

2. Write `AbstractFurnaceSmeltMixin` targeting `AbstractFurnaceBlockEntity`, 2 injectors:
   - `@Inject` at the `INVOKE` of `burn(RegistryAccess, RecipeHolder, NonNullList, int,
     AbstractFurnaceBlockEntity)` inside `serverTick`, `allow = 1`, default shift (before) so
     `SLOT_INPUT` still holds the pre-decrement item — reads
     `blockEntity.getItem(AbstractFurnaceBlockEntity.SLOT_INPUT)`, calls
     `SmeltingListener.onFurnaceSmelt(...)`. Re-verify the exact `burn` descriptor with `javap`
     first (the design doc's descriptor was read from bytecode during planning, but confirm
     before committing to a `target=` string).
   - `@Inject` at the `INVOKE` of `setRecipeUsed(RecipeHolder<?>)` inside `serverTick`,
     `allow = 1` — reads `blockEntity.getItem(AbstractFurnaceBlockEntity.SLOT_RESULT)` (the
     merged result), calls `SmeltingListener.onSmeltComplete(...)`.
   - Guard both on `world instanceof ServerLevel` (the design doc notes `serverTick`'s world
     parameter type has varied across versions in the Fabric history — check the actual 1.21.1
     parameter type with `javap` and narrow explicitly, matching the Fabric mixin's own
     documented caution).

3. Register `indexSmeltedOreProducts` via `NeoForge.EVENT_BUS.addListener(ServerStartedEvent...)`
   for initial indexing. For the "rebuild after data-pack reload" half: register a
   `PreparableReloadListener` via `AddReloadListenerEvent#addListener` in `register()`, whose
   `apply(...)` calls `indexSmeltedOreProducts` — **verify during implementation** that recipes
   are actually reloaded by the time this listener's `apply()` runs (check other reload listener
   registration order, or simply re-derive the index lazily/defensively if ordering can't be
   guaranteed cheaply). If this turns out unreliable, falling back to `ServerStartedEvent` alone
   (accepting that a mid-session data-pack reload doesn't re-index until next restart) is an
   acceptable, documented deviation — note it in the class javadoc if taken.

4. Register a `FurnaceFuelBurnTimeEvent` listener in `SmeltingListener.register()`:
   `event.setBurnTime(SmeltingListener.boostFuelTime(event.getBurnTime(), pos, input))` — need
   the furnace's `BlockPos` and input stack from the event context. Check
   `FurnaceFuelBurnTimeEvent`'s fields (`getItemStack()` is the fuel, not the furnace input —
   confirm whether the event exposes the furnace's `BlockPos`/block entity at all; if it does
   not, this event cannot resolve "which furnace, which owner" and Fuel Efficiency must fall
   back to the mixin approach after all — **verify this before committing to the event-based
   design**, since the design doc confirmed the event exists and fires correctly but did not
   verify it carries enough context to resolve furnace ownership).

**Acceptance:**
- `./gradlew test` passes.
- `AbstractFurnaceSmeltMixinTest` reflectively confirms both injectors exist with correct target
  descriptors (matching `LivingEntityDropFromLootTableAccessorTest`'s structural-verification
  style).
- `SmeltingListenerTest` covers: owner tracking round-trip, Smelting-vs-Cooking mutual exclusion
  (an item priced under both tables pays Smelting only), Second Smelt table-membership
  precedence, Fuel Efficiency's explicit-cookable-check (not blanket-else) for a
  non-smeltable/non-cookable input.

## Task B: Furnace extraction — Understanding the Art

**Files:**
- `src/main/java/com/gmail/nossr50/neoforge/mixin/FurnaceResultSlotMixin.java` (new)
- `src/main/resources/mcmmo.mixins.json` (add `FurnaceResultSlotMixin`)
- `src/test/java/com/gmail/nossr50/neoforge/mixin/FurnaceResultSlotMixinTest.java` (new)

Depends on Task A (`SmeltingListener.beginFurnaceExtract`/`endFurnaceExtract`/`boostVanillaXp`
must exist first, but is independent of Task A's mixin — can run in parallel with Task A once
`SmeltingListener`'s public surface is stubbed/agreed).

**Work:**

1. Write `FurnaceResultSlotMixin` targeting `net.minecraft.world.inventory.FurnaceResultSlot`:
   `@Shadow @Final private Player player`. Two HEAD/RETURN injections on
   `checkTakeAchievements(ItemStack)` (protected — confirm exact descriptor with `javap`):
   - HEAD: call `SmeltingListener.beginFurnaceExtract(player, stack)`.
   - RETURN: call `SmeltingListener.endFurnaceExtract()`.
   No `allow`/`require` needed (named-method HEAD/RETURN, not a call-site anchor — matches the
   Fabric original's own stated rationale).
2. Add a second `AbstractFurnaceSmeltMixin`-adjacent injector — **not** in that class, but
   wherever `createExperience`'s `ExperienceOrb.award` call lives — for
   `boostVanillaXp`. Per the design doc, `createExperience(ServerLevel, Vec3, int, float)` is a
   private static method on `AbstractFurnaceBlockEntity` (not inside a lambda), calling
   `ExperienceOrb.award(...)` at a fixed offset. Add this as a third injector on
   `AbstractFurnaceSmeltMixin` (Task A's file) since it targets the same class:
   `@ModifyArg(method = "createExperience", index = 2, at = @At(value = "INVOKE", target =
   ".../ExperienceOrb;award(...)"))` calling `SmeltingListener.boostVanillaXp(amount)`. Re-verify
   the exact descriptor and index with `javap` — do not assume the design doc's read is
   byte-for-byte final.

**Acceptance:**
- `./gradlew test` passes.
- Reflective structural test confirms `FurnaceResultSlotMixin`'s shadow field and both
  injections exist with correct signatures; confirms the third injector on
  `AbstractFurnaceSmeltMixin` targets `createExperience` correctly.
- A listener-level test (using the existing `SmeltingListenerTest` or a new
  `SmeltingListenerUnderstandingTheArtTest`) verifies: no multiplier when unranked, multiplier
  applied only for indexed ore-smelt products (not arbitrary furnace output), thread-local
  cleared after `endFurnaceExtract`.

## Task C: Cooking — crafting grid + campfire

**Files:**
- `src/main/java/com/gmail/nossr50/neoforge/listeners/CookingListener.java` (new)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/ResultSlotMixin.java` (new)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/CampfireCookMixin.java` (new)
- `src/main/resources/mcmmo.mixins.json` (add `ResultSlotMixin`, `CampfireCookMixin`)
- `src/test/java/com/gmail/nossr50/neoforge/listeners/CookingListenerTest.java` (new)
- `src/test/java/com/gmail/nossr50/neoforge/mixin/ResultSlotMixinTest.java`,
  `CampfireCookMixinTest.java` (new)

Depends on Task A (`SmeltingListener.materialConfigString` must be package-visible and stable;
`CookingListener`'s furnace-side XP is dispatched from `SmeltingListener.onFurnaceSmelt`, already
in place from Task A — no new furnace-side code needed here).

**Work:**

1. Port `CookingListener` from the Fabric original
   (`git show d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/CookingListener.java`):
   - `CAMPFIRE_OWNERS` map + `onUseBlock` (own `PlayerInteractEvent.RightClickBlock` listener,
     checking `CampfireBlockEntity`, registered separately from `SmeltingListener`'s — per the
     Fabric javadoc, deliberately not shared).
   - `onCampfireCook(ServerLevel, BlockPos, ItemStack input, ItemStack result)`: unchanged logic,
     including the **identity check** (`input == result`, not `.equals`) for the
     no-recipe-matched/data-pack-reload-mid-cook edge case.
   - `onCraftedItemTaken(Player, ItemStack result, int items)`: unchanged logic.
   - `register()` / `clearOwners()`.

2. Write `ResultSlotMixin` targeting `net.minecraft.world.inventory.ResultSlot`:
   `@Shadow @Final private Player player`, `@Shadow private int removeCount` (Mojang's name for
   Fabric's `amount` field — confirm with `javap` before writing the shadow). One HEAD injection
   on `checkTakeAchievements(ItemStack)`: `allow = 1`
   (unlike the furnace variant, this is still just HEAD on a named method — `allow` isn't
   strictly required, but keep the Fabric original's choice unless a specific reason says
   otherwise — check whether the Fabric original used `allow` here; if not, omit it to match).
   Calls `CookingListener.onCraftedItemTaken(player, stack, removeCount)` — **HEAD is mandatory**,
   matching Fabric's documented reasoning (the field is zeroed before the method returns).

3. Write `CampfireCookMixin` targeting `net.minecraft.world.level.block.entity.CampfireBlockEntity`:
   `@ModifyArg` on `Containers.dropItemStack(Level, double, double, double, ItemStack)` inside
   `cookTick`, `index = 4`, `allow = 1`. Capture `@Local(argsOnly = true) Level world`,
   `@Local(argsOnly = true) BlockPos pos`, `@Local SingleRecipeInput cooked` (implicit-type
   capture — confirm it really is the sole `SingleRecipeInput` local in `cookTick`, per the
   design doc). Guard `world instanceof ServerLevel`. Calls
   `CookingListener.onCampfireCook(serverLevel, pos, cooked.item(), result)`.

**Acceptance:**
- `./gradlew test` passes.
- Reflective structural tests for both mixins (shadow fields, injector target descriptors,
  `@Local` capture types).
- `CookingListenerTest` covers: campfire owner tracking round-trip, the `input == result`
  identity-check no-op path, Master Chef table-membership roll on the campfire path, crafting-grid
  batch-count pricing (a shift-clicked stack of N pays for N, not 1), rate-cap notification
  fires once per window not once per cook/craft.

## Task D: Cross-skill regression coverage

**Files:**
- `src/test/java/com/gmail/nossr50/neoforge/listeners/SmeltingCookingMutualExclusionTest.java`
  (new) — or fold into `SmeltingListenerTest` if that reads more naturally once Tasks A-C are
  done; implementer's judgment.

Depends on Tasks A, B, C all landing first (exercises the seams together).

**Work:**

Write tests that exercise the three-way mutual-exclusivity guarantee across skill boundaries,
since Tasks A/B/C each test their own seam in isolation but nothing yet proves the seams agree
with each other:

1. An item configured under **both** `Experience_Values.Smelting` and
   `Experience_Values.Cooking.Cook` — smelting it pays Smelting XP only, boosts fuel via
   `SmeltingManager`, and Cooking's manager is never touched.
2. An item configured under **both** `Bonus_Drops.Smelting` and `Bonus_Drops.Cooking` for Second
   Smelt / Master Chef — the roll happens exactly once, via `SmeltingManager`, never both.
3. An item that is neither smeltable nor cookable (sand, cobblestone, a log) gets **no** fuel
   bonus from either manager — the explicit non-blanket-else gate from Task A holds under a
   real owner + real furnace state, not just a unit-level function call.

**Acceptance:**
- `./gradlew test` passes with all three cross-skill scenarios covered and failing without the
  ordering fix (i.e., write these against a deliberately-broken ordering first to confirm they'd
  catch a regression, then confirm they pass against the real implementation — standard
  test-effectiveness sanity check, not a formal TDD requirement for this task).

## Task E: Registration wiring + boot verification

**Files:**
- Wherever `McMMOMod` (or its NeoForge equivalent) calls other listeners' `register()` methods —
  add `SmeltingListener.register()` and `CookingListener.register()` alongside them.
- `src/main/resources/mcmmo.mixins.json` — confirm all 4 new mixin classes are listed (should
  already be done incrementally in Tasks A-C; this task is the final cross-check).

Depends on Tasks A, B, C.

**Work:**

1. Wire both listeners' `register()` calls into mod startup, matching how `HunterListener`,
   `FishingListener`'s mixins, etc. are wired (`FishingListener` needed no `register()` since it
   was pure-mixin; `SmeltingListener`/`CookingListener` both need one, matching `HunterListener`'s
   shape).
2. Confirm `clearOwners()` (both classes) is called on server stop, matching the Fabric
   originals' lifecycle and this project's existing pattern for other owner-map-based skills.
3. **Mandatory boot verification** — not optional, not deferred to manual testing:
   ```
   timeout 150 ./gradlew runServer --console=plain 2>&1 \
     | tee /tmp/runserver-cooksmelt.log \
     | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("
   ```
   Confirm the log reaches `Done (...)!` with zero mixin-related errors. This is the only way to
   catch a Mixin target-type-inference or target-signature-mismatch failure in this sandboxed
   environment — unit tests structurally cannot (no ModLauncher wiring under plain
   `./gradlew test`), and this project has already hit exactly this class of bug once
   (`LivingEntityDropFromLootTableAccessor`, a real boot crash no unit test caught).

**Acceptance:**
- `./gradlew test` passes.
- Headless server boot log shows `Done (...)!`, zero mixin errors.
- Both `register()` calls and both `clearOwners()` calls are wired into the mod lifecycle.

## Manual In-Game Verification (after all tasks land)

Left for the user's own testing pass (per this project's established batched-testing cadence),
not part of automated acceptance:

- Right-click a furnace, smelt ore → Smelting XP awarded, Fuel Efficiency multiplies burn time
  for ranked players.
- Same furnace, cook food → Cooking XP instead, Kitchen Efficiency applies, Smelting untouched.
- Second Smelt / Master Chef bonus drops observed at expected rates (not deterministically
  testable, but should be visibly non-zero over enough smelts/cooks).
- Understanding the Art: extract a furnace's stored XP as a ranked player → boosted orb size
  visible/audible vs. an unranked extraction.
- Campfire: cook food on a lit campfire and a soul campfire, confirm XP + Master Chef both fire,
  confirm Kitchen Efficiency does *not* apply (no fuel to boost).
- Crafting grid: craft a batch of cookies (8 at once) and shift-click the result, confirm XP
  scales with the batch, not a flat per-take amount.
