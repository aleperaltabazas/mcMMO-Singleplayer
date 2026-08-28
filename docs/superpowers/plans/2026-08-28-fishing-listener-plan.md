# Fishing Listener (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Fabric mcMMO `FishingListener` (806 lines) and its two mixins to NeoForge
1.21.1, unlocking the whole Fishing skill.

**Architecture:** A pure-mixin port (no native NeoForge event applies — see the spec's
`ItemFishedEvent` dead-end finding). Two new mixin files retarget the Fabric originals' five
injectors onto `FishingHook#retrieve`/`FishingHook#catchingFish` (the Mojang-mapped renames of
`FishingBobberEntity#use`/`#tickFishingLogic`), each delegating to a static method on a new
`FishingListener`. The listener is split into four tasks along the Fabric original's own
docstring-declared sections.

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), Sponge Mixin, JUnit 5 + Mockito.

**Spec:** docs/superpowers/specs/2026-08-28-fishing-listener-design.md — **read this first**, it
has every Mojang-mapping verification (class renames, the `Enchantment.canBeCombined` removal,
the `PlatformItem.getEnchantmentLevel` reuse) this plan's tasks depend on.

**Source:** the Fabric original, recoverable at `mc/1.21.1` commit `ef5fd3d1a~1`:
`src/main/java/com/gmail/nossr50/fabric/listeners/FishingListener.java` (806 lines),
`src/main/java/com/gmail/nossr50/fabric/mixin/FishingBobberUseMixin.java` (108 lines),
`src/main/java/com/gmail/nossr50/fabric/mixin/FishingWaitTimeMixin.java` (78 lines). Each task
below names the exact Fabric methods it ports — pull them with
`git show ef5fd3d1a~1:<path>` and translate using the spec's mapping table. Do not re-derive the
mappings from memory; the spec already did the `javap`/patched-source verification.

## Global Constraints

- Every `FishingManager`/treasure-datatype/`ItemSpecBuilder`/`MaterialMapStore` call the Fabric
  original makes already exists on this branch with the same signature — do not modify those
  files, do not re-derive their logic, just call them.
- `luckOfTheSeaLevel` must delegate to `PlatformItem#getEnchantmentLevel(Enchantments.LUCK_OF_THE_SEA)`
  — do not re-derive the enchantment lookup by hand.
- `ItemFishedEvent` is not used anywhere in this port (confirmed dead for mutation in the spec).
- `FishingListener` has **no `register()` method** and needs **no wiring into `McMMOMod`** —
  every seam is mixin-driven; mixins apply automatically via `mcmmo.mixins.json`.
- The criterion-trigger `@ModifyArg` (Task A) needs `allow` set to the actual number of
  `CriteriaTriggers.FISHING_ROD_HOOKED.trigger(...)` call sites in the patched
  `FishingHook#retrieve` — verify this count directly against the patched jar during
  implementation (the spec's own reading found 2, but confirm bytecode-side rather than trusting
  a re-read of the same source excerpt).
- `conflictsWithAny`'s reimplementation (Task D) must preserve exact semantics: two enchantments
  conflict if either one's `exclusiveSet()` contains the other, or if they are literally the same
  enchantment. Task D must include tests proving illegal combinations are never generated — this
  is the single highest-risk piece of this port (see spec's rationale).

---

### Task A: Mixins + catch/treasure/overfishing arm

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/FishingHookRetrieveMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/FishingHookWaitTimeMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/FishingListener.java`
- Modify: `src/main/resources/mcmmo.mixins.json`
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/FishingListenerCatchTest.java`

**Interfaces:**
- Produces: `FishingListener.onFishCaught(FishingHook, Collection<ItemStack>): void`,
  `FishingListener.boostVanillaXp(FishingHook, int): int`,
  `FishingListener.luckOfTheSeaLevel(ServerPlayer): int` (package-private, used internally by this
  task's own `maybeCatchTreasure`). Also produces two **stub** methods later tasks fill in:
  `FishingListener.onEntityHooked(FishingHook): void` (empty body — Task B) and
  `FishingListener.tryIceFishing(FishingHook): void` (empty body — Task B), plus
  `FishingListener.resolveWaitCountdown(FishingHook, RandomSource, int, int, int): int` (Task C) —
  stub this one to always fall through to an unmodified vanilla draw
  (`Mth.nextInt(random, vanillaMinWaitTicks, vanillaMaxWaitTicks)`), matching the Fabric original's
  own documented "any gate miss falls through to an unmodified vanilla draw" behavior, so Master
  Angler is inert but harmless until Task C lands. And a **stub** `maybeApplyMagicHunter`/
  `applyBookEnchantment` pairing: `maybeCatchTreasure` (this task) must call into them, but Task A
  should stub both to always return `false` (no enchantment applied) until Task D lands — this
  keeps `maybeCatchTreasure`'s control flow real (it still builds and awards the treasure item and
  its XP) while deferring only the enchant-roll logic.
- Consumes (from the two mixin files, once Task A creates them): nothing from later tasks — both
  mixin files are complete in this task; they just call stub methods for two of their five
  injectors until Tasks B/C fill those stubs in.

Both mixin files should be **complete** in this task — all five injectors wired, following the
spec's exact seam list (fish-caught `@ModifyArg`, entity-hooked `@Inject`, ice-fishing `@Inject`
HEAD, XP-boost `@ModifyArg`, all four on `FishingHookRetrieveMixin`; Master Angler's `@Redirect`
with `@Slice` on `FishingHookWaitTimeMixin`). Wiring a complete mixin file against stub listener
methods is cheap and means Tasks B/C never touch the mixin files again — only `FishingListener`'s
method bodies change.

- [ ] **Step 1: Pull the Fabric originals for reference**

```bash
git show ef5fd3d1a~1:src/main/java/com/gmail/nossr50/fabric/listeners/FishingListener.java > /tmp/fabric-fishing-listener.java
git show ef5fd3d1a~1:src/main/java/com/gmail/nossr50/fabric/mixin/FishingBobberUseMixin.java > /tmp/fabric-fishing-bobber-mixin.java
git show ef5fd3d1a~1:src/main/java/com/gmail/nossr50/fabric/mixin/FishingWaitTimeMixin.java > /tmp/fabric-fishing-waittime-mixin.java
```

- [ ] **Step 2: Write `FishingHookRetrieveMixin.java`**

Port all four injectors from `/tmp/fabric-fishing-bobber-mixin.java`, retargeting per the spec's
mapping table: `@Mixin(FishingHook.class)`, method target `retrieve`
(`Lnet/minecraft/world/item/ItemStack;)I` return type, not the Fabric original's
`Ljava/lang/Integer;` boxed `CallbackInfoReturnable` shape — verify the real return type via
`javap -p -classpath <patched-jar> net.minecraft.world.entity.projectile.FishingHook` before
committing to a signature, since `retrieve` returns a primitive `int`, not `void`/boxed `Integer`
the way Fabric's `use` did (confirm this against the jar; don't assume the CallbackInfoReturnable
generic parameter from the Fabric file transfers unchanged). The four injectors:

1. `@ModifyArg` on `CriteriaTriggers.FISHING_ROD_HOOKED.trigger(...)`'s 4th argument (index 3),
   calling `FishingListener.onFishCaught((FishingHook) (Object) this, caught)` and returning
   `caught`.
2. `@Inject` before `this.pullEntity(this.hookedIn)` (the official-mapped rename of
   `pullHookedEntity`), calling `FishingListener.onEntityHooked((FishingHook) (Object) this)`.
3. `@Inject(at = @At("HEAD"))` on `retrieve`, calling
   `FishingListener.tryIceFishing((FishingHook) (Object) this)`.
4. `@ModifyArg` on `ExperienceOrb`'s constructor (index 4), calling
   `FishingListener.boostVanillaXp((FishingHook) (Object) this, experience)`.

Preserve the Fabric original's `allow`/`require` values only after re-verifying the call-site
count against the patched jar (see Global Constraints) — do not copy them on faith.

- [ ] **Step 3: Write `FishingHookWaitTimeMixin.java`**

Port `FishingWaitTimeMixin`'s single `@Redirect` from `/tmp/fabric-fishing-waittime-mixin.java`,
retargeting: `@Mixin(FishingHook.class)`, `@Shadow @Final private int lureSpeed;` (renamed from
`waitTimeReductionTicks`), method target `catchingFish` (renamed from `tickFishingLogic`), the
`@Slice` still anchored at `@At(value = "CONSTANT", args = "intValue=600")`, redirecting
`Mth.nextInt(RandomSource, int, int)` (renamed from `MathHelper.nextInt`), calling
`FishingListener.resolveWaitCountdown((FishingHook) (Object) this, random, minWaitTicks,
maxWaitTicks, this.lureSpeed)`. Keep `require = 1, allow = 1` — this is the exact same
load-bearing guard the Fabric original's own javadoc explains (an unconstrained redirect would
silently hijack the hook/fish-travel countdowns too); re-verify the constant and call-site
uniqueness against the patched jar before committing.

- [ ] **Step 4: Register both mixins**

Add `"FishingHookRetrieveMixin"` and `"FishingHookWaitTimeMixin"` to the `"mixins"` array in
`src/main/resources/mcmmo.mixins.json`, alphabetical order.

- [ ] **Step 5: Write `FishingListener.java`'s catch/treasure/overfishing arm**

Port from `/tmp/fabric-fishing-listener.java`: `onFishCaught`, `overrideVanillaTreasures`,
`isVanillaFish`, `punishOverfishing`, `boostVanillaXp`, `maybeCatchTreasure`, `applyRandomWear`,
`luckOfTheSeaLevel`. Retarget every yarn-mapped type per the spec's table (`ServerPlayerEntity`→
`ServerPlayer`, `ServerWorld`→`ServerLevel`, etc.). `maybeCatchTreasure`'s enchant branch (the
`rolled.get() instanceof FishingTreasureBook book ? applyBookEnchantment(...) :
maybeApplyMagicHunter(...)` ternary) calls this task's two stub methods — keep the ternary and
notification-sending logic real; only the two called methods' bodies are stubs
(`private static boolean applyBookEnchantment(...) { return false; }` and same shape for
`maybeApplyMagicHunter`, with the exact parameter lists Task D's real implementations will need —
match the Fabric original's parameter lists so Task D doesn't have to touch call sites).

Add the three remaining stub methods per the Interfaces section above: `onEntityHooked`,
`tryIceFishing` (both empty `{}` bodies — safe no-ops, since nothing calls them from
`FishingListener` itself in this task, only the mixin's already-wired injectors, and a no-op
means Shake/Ice-Fishing are simply inert, not broken, until Task B), and `resolveWaitCountdown`
(falls through to the unmodified vanilla draw as specified above).

- [ ] **Step 6: Write tests**

`FishingListenerCatchTest.java` — pin `overrideVanillaTreasures` (the four exempt vanilla fish
pass through unchanged; everything else becomes a single `Items.SALMON` stack; an all-fish catch
is left untouched — i.e. no defensive copy churn when nothing changes) and `punishOverfishing`
(clears the collection). Mock `FishingManager`/`McMMOPlayer` the same way
`EntityDamageListenerHunterTest`/`HunterListenerTest` do (`McTestRegistries.bootstrap()`,
`UserManager.track`/`remove`, `GeneralConfig` via `@TempDir`).

- [ ] **Step 7: Compile and test**

Run: `./gradlew compileJava compileTestJava test --tests "com.gmail.nossr50.neoforge.listeners.FishingListenerCatchTest"`
Expected: PASS, and the whole project still compiles (the mixin files reference stub methods that
must exist with matching signatures).

- [ ] **Step 8: Commit**

---

### Task B: Shake + Ice Fishing

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/FishingListener.java` (fill
  `onEntityHooked`, add `shearIfWool`; fill `tryIceFishing`, add `sitsOverWater`, `meltIce`)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/FishingListenerShakeIceTest.java`

**Interfaces:**
- Consumes: Task A's `FishingListener` class (same file, filling in two of its stub methods) —
  no new cross-file interfaces.
- Produces: nothing new consumed by later tasks.

- [ ] **Step 1: Port Shake** (`onEntityHooked`, `shearIfWool`) from
  `/tmp/fabric-fishing-listener.java`, retargeting `SheepEntity`→`Sheep`,
  `ServerWorld`→`ServerLevel`, `LivingEntity` unchanged, `Registries.ENTITY_TYPE` unchanged. The
  `CombatUtils.safeDealDamage(target, FishingManager.shakeDamage(...), serverPlayer)` call is
  unchanged — that's already ported platform code.

- [ ] **Step 2: Port Ice Fishing** (`tryIceFishing`, `sitsOverWater`, `meltIce`) from the same
  file, retargeting `FluidTags`→same name, moved package (`net.minecraft.tags.FluidTags`),
  `serverPlayer.raycast(100.0, 1.0F, false)`→`serverPlayer.pick(100.0, 1.0F, false)` (per the
  spec's verified rename), `BlockHitResult`/`HitResult`/`Blocks.ICE`/`Blocks.WATER` unchanged.

- [ ] **Step 3: Write tests** — `shearIfWool` (an already-sheared sheep refuses; a non-sheep, or a
  non-wool drop, is a no-op pass-through) and `sitsOverWater` (water within the 1-4 block scan
  returns true; none does not) using mocked `ServerLevel`/`BlockPos` the same way
  `MobOriginsTest`/existing tests mock `Level`.

- [ ] **Step 4: Compile and test**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.FishingListenerShakeIceTest"`

- [ ] **Step 5: Commit**

---

### Task C: Master Angler

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/FishingListener.java` (fill
  `resolveWaitCountdown`, add `masterAnglerWaitTimes`)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/FishingListenerMasterAnglerTest.java`

**Interfaces:**
- Consumes: `FishingManager.resolveMasterAnglerWaitTimesFromLureTicks(int, int, int, boolean,
  int): MasterAnglerWaitTimes` (already exists, unchanged), `RankUtils.getRank(McMMOPlayer,
  SubSkillType): int` (already exists, unchanged).
- Produces: nothing new consumed by later tasks.

- [ ] **Step 1: Port** `resolveWaitCountdown` and `masterAnglerWaitTimes` from
  `/tmp/fabric-fishing-listener.java`, retargeting `Items.FISHING_ROD` unchanged,
  `BoatEntity`→`Boat`, `Random`→`RandomSource` (confirm this rename against the patched jar —
  `FishingHookWaitTimeMixin`'s `@Redirect` target determines the real parameter type; match it
  exactly, do not assume).

- [ ] **Step 2: Write tests** — the gate chain: no fishing rod in main hand → vanilla draw; rod in
  off-hand too → vanilla draw; `canMasterAngler()` false → vanilla draw; a qualifying player with a
  boat bonus produces a narrower range than without. Mock `FishingManager`/`McMMOPlayer`/
  `ServerPlayer` following the established pattern.

- [ ] **Step 3: Compile and test**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.FishingListenerMasterAnglerTest"`

- [ ] **Step 4: Commit**

---

### Task D: Magic Hunter + book enchantment (the new `exclusiveSet()` logic)

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/FishingListener.java` (fill
  `maybeApplyMagicHunter`, `applyBookEnchantment`, `warnUnknownWhitelistedEnchantments`; add
  `conflictsWithAny` reimplemented per the spec)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/FishingListenerMagicHunterTest.java`

**Interfaces:**
- Consumes: `FishingManager.rollMagicHunterRarity(double): Optional<Rarity>`,
  `FishingManager.selectMagicHunterEnchants(List<EnchantmentTreasure>, BiPredicate<List<EnchantmentTreasure>,
  EnchantmentTreasure>, IntUnaryOperator): List<EnchantmentTreasure>` (or whatever its real
  functional-parameter shape is — read `FishingManager.java:470` directly, don't guess),
  `FishingManager.pickBookEnchantment(List<EnchantmentTreasure>, IntUnaryOperator):
  Optional<EnchantmentTreasure>`, `McMMOMod.getFishingTreasureConfig().getEnchantmentTreasures(Rarity):
  List<EnchantmentTreasure>` (all already exist, unchanged).
- Produces: nothing new consumed by later tasks — this is the last task.

- [ ] **Step 1: Read the spec's `Enchantment.canBeCombined` section again.** This is genuinely new
  logic, not a rename. Read `net.minecraft.world.item.enchantment.Enchantment`'s real
  `exclusiveSet()` method signature via `javap -p -classpath <patched-jar>
  net.minecraft.world.item.enchantment.Enchantment` before writing `conflictsWithAny` — confirm
  the return type is `HolderSet<Enchantment>` and that `HolderSet#contains(Holder<Enchantment>):
  boolean` is the right membership check (or whatever the actual API surface is; do not assume
  from this plan's description alone).

- [ ] **Step 2: Port `applyBookEnchantment`** from `/tmp/fabric-fishing-listener.java`, retargeting
  the registry-enumeration chain per the spec: `serverPlayer.registryAccess().registryOrThrow(Registries.ENCHANTMENT)`,
  `.holders()` (replaces `getIndexedEntries()`), and the write path via
  `EnchantmentHelper.updateEnchantments(treasureStack, mutable -> mutable.set(holder, level))`
  (replaces `ItemEnchantmentsComponent.Builder`/`EnchantmentHelper.set`). Port
  `warnUnknownWhitelistedEnchantments` unchanged in logic (just retype).

- [ ] **Step 3: Port `maybeApplyMagicHunter`**, retargeting `Identifier`→`ResourceLocation`,
  `.getEntry(id)`→`.getHolder(id)`, `entry.get().value().isAcceptableItem(treasureStack)`→
  `.isSupportedItem(treasureStack)` (renamed per the spec), and the write path the same
  `updateEnchantments` call as Step 2.

- [ ] **Step 4: Write `conflictsWithAny`**, replacing the Fabric original's
  `!Enchantment.canBeCombined(existing, entry)` calls with the `exclusiveSet()`-based check the
  spec specifies: two enchantments conflict if `a.exclusiveSet().contains(bHolder) ||
  b.exclusiveSet().contains(aHolder)`, or if they are the same enchantment (`a.equals(b)` on the
  `Holder`, or compare underlying `ResourceKey`s — verify which comparison `Holder` supports
  cleanly). Preserve the method's existing two loops (against `alreadyOnItem` and against
  `selectedSoFar`) — only the per-pair conflict predicate changes.

- [ ] **Step 5: Write tests proving illegal enchantment combinations are never generated.** This
  is the task the human operator explicitly asked for — do not skip or under-scope it. At minimum:
  - Two enchantments with real vanilla exclusivity (e.g. `Enchantments.INFINITY` and
    `Enchantments.MENDING`, or `Enchantments.SILK_TOUCH` and `Enchantments.FORTUNE` — verify which
    pairs are actually mutually exclusive in this exact 1.21.1 registry via `javap`/reading
    vanilla's own enchantment data before picking the pair, don't assume a Bedrock/older-version
    exclusivity list still holds) must never both land on the same item via `conflictsWithAny`
    returning `true` for that pair.
  - An enchantment already on the item (`alreadyOnItem`) correctly blocks a conflicting candidate
    from `selectMagicHunterEnchants`'s roll — i.e. `conflictsWithAny` returns `true` when checked
    against `alreadyOnItem`, not just against `selectedSoFar`.
  - Two conflicting candidates both present in the same roll's candidate pool: the second one is
    excluded once the first is chosen (i.e. `selectedSoFar`-based conflict detection actually
    excludes it — this is the one Fabric's own javadoc calls out as "this port's deviation," so
    pin that it still works here).
  - A non-conflicting pair (e.g. two enchantments neither excludes) is correctly allowed to
    co-exist — a test that only ever asserts exclusion would pass even if `conflictsWithAny`
    always returned `true`; assert the permissive case too.

- [ ] **Step 6: Compile and test**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.FishingListenerMagicHunterTest"`
then the full suite: `./gradlew test`.

- [ ] **Step 7: Commit**

## Manual In-Game Verification (after all four tasks land)

None of the automated tests exercise the real mixins end-to-end (consistent with every other mixin
in this port — see the Hunter plan's identical note). Before considering this done:

1. Fish normally; confirm base XP is awarded and the catch isn't silently replaced/dropped.
2. Confirm `Override_Vanilla_Treasures` (on by default) turns non-fish catches into salmon.
3. Fish long enough in one spot to trigger the overfishing punishment; confirm the catch is
   confiscated and the warning message appears.
4. Hook and reel in a mob (e.g. a nearby chicken) to trigger Shake; confirm a drop appears and the
   mob takes damage. Shake a sheep specifically and confirm it shears.
5. Reel in a bobber resting on ice over water; confirm a 3×3 hole melts.
6. With Master Angler ranked up, confirm bite waits are noticeably shorter than an unranked catch.
7. Catch enough treasures to observe a Magic Hunter enchant proc and a book treasure; confirm no
   item ever carries two enchantments that shouldn't coexist.
