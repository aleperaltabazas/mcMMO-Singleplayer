# Alchemy Listener (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Fabric mcMMO `AlchemyListener` (155 lines) and its two mixins to NeoForge
1.21.1, unlocking the Alchemy skill (brew-owner XP + Catalysis brew-speed).

**Architecture:** Mixed — matches Fabric's three injectors 1:1. Craft/XP becomes a plain
`PotionBrewEvent.Pre` listener, but (verified during Task B, correcting this plan's original
"no mixin" claim) still needs a third injector — `doBrew` HEAD on `BrewingStandTickMixin` — to
bridge the brewing stand's `BlockPos` into the listener via a `ThreadLocal`, since
`PotionBrewEvent` exposes no `BlockPos`/`Level` accessor at all. Recipe recognition
(`isBrewable`) and Catalysis (`brewTime` read/write + `serverTick` hook) stay mixins, same shape
as Fabric's `canCraft`/`tick` injectors. Owner tracking stays a `PlayerInteractEvent.RightClickBlock`
listener, same pattern already used by `SuperAbilityListener.onUseBlock`.

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), Sponge Mixin, JUnit 5 + Mockito.

**Spec:** docs/superpowers/specs/2026-08-30-alchemy-listener-design.md — **read this first**. It
verifies every signature this plan depends on and has the rejected-alternative writeup for
`RegisterBrewingRecipesEvent`/`IBrewingRecipe` (don't re-investigate that path — it's a dead end,
confirmed against the real NeoForge source).

**Source:** the Fabric original, recoverable at commit `d0764257671576525aedd97308be2f8c6d85e2fd`:
`src/main/java/com/gmail/nossr50/fabric/listeners/AlchemyListener.java` (155 lines),
`src/main/java/com/gmail/nossr50/fabric/mixin/BrewingStandBlockEntityMixin.java` (69 lines),
`src/main/java/com/gmail/nossr50/fabric/mixin/BrewingStandBrewTimeAccessor.java` (22 lines). Pull
with `git show d0764257671576525aedd97308be2f8c6d85e2fd:<path>`.

## Global Constraints

- `AlchemyPotionBrewer.isValidBrew(PlatformInventory)` and
  `AlchemyPotionBrewer.finishBrewing(PlatformInventory, McMMOPlayer)` already exist on this
  branch with these exact signatures — do not modify them, do not re-derive brew-validity logic,
  just call them (same discipline as Fishing's `FishingManager` calls).
- `CatalysisTimer` (MC-free) already exists unchanged — reuse `extraTicks`/`reducedBrewTime`/
  `reset`/`clear`/`VANILLA_BREW_SPEED` exactly as the Fabric original did.
- **Any `@Mixin`-annotated interface must stay pure abstract-only** (only `@Accessor`/`@Invoker`
  declarations, zero static/default members) — see
  `src/main/java/com/gmail/nossr50/neoforge/mixin/LivingEntityDropFromLootTableAccessor.java`'s
  own javadoc for why: a static helper on a `@Mixin` interface broke Sponge Mixin's target-type
  inference and caused a real boot crash (`InvalidMixinException`) on this branch already. The
  Fabric `BrewingStandBrewTimeAccessor` is already pure (2 abstract accessor methods only) — port
  it as-is, do not add anything to it. If a call-shape helper is ever needed, put it in a
  separate plain (non-`@Mixin`) class, following
  `LivingEntityDropFromLootTableAccessorCalls.java`'s pattern.
- Re-verify every `allow`/`require` injector count and every method signature against the real
  patched jar (`build/moddev/artifacts/neoforge-21.1.248-merged.jar`, `javap -p`) before
  committing to a mixin — do not copy Fabric's injector counts on faith; the spec's own findings
  are a starting point, not a substitute for re-checking during implementation.
- `AlchemyListener` needs a `register()` method wired into `McMMOMod` (unlike Fishing) — it has
  two real NeoForge-event listeners (owner tracking, `PotionBrewEvent.Pre`), following
  `HunterListener.register()`'s exact pattern:
  `NeoForge.EVENT_BUS.addListener(AlchemyListener::onUseBlock);` +
  `NeoForge.EVENT_BUS.addListener(AlchemyListener::onPotionBrewPre);`.
- Keep the owner map's known limitation (keyed by `BlockPos.asLong()` only, not dimension) —
  the Fabric original's own javadoc says this is harmless in singleplayer; do not "fix" it, that
  would be scope creep.

---

### Task A: Owner tracking + Catalysis mixins

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/BrewingStandTickMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/BrewingStandBrewTimeAccessor.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/AlchemyListener.java`
- Modify: `src/main/resources/mcmmo.mixins.json`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (wire `AlchemyListener.register()`)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/AlchemyListenerCatalysisTest.java`
- Test: `src/test/java/com/gmail/nossr50/neoforge/mixin/BrewingStandBrewTimeAccessorTest.java`
  (reflection-based structural test, same shape as
  `LivingEntityDropFromLootTableAccessorTest.java` — mixins are never applied under plain JUnit,
  see that test's own javadoc for why)

**Interfaces:**
- Produces: `AlchemyListener.applyCatalysis(BlockPos, BrewingStandBlockEntity): void`,
  `AlchemyListener.clearOwners(): void`, plus a **stub**
  `AlchemyListener.isValidBrew(NonNullList<ItemStack>): boolean` returning `false` unconditionally
  (Task B fills this in) and a **stub**
  `AlchemyListener.onPotionBrewPre(PotionBrewEvent.Pre): void` with an empty body (Task B fills
  this in) — stubbing both here means this task's `isBrewable`-forcing mixin can be wired
  immediately without waiting on Task B, and the stand simply won't recognize mcMMO-only brews
  until Task B lands (vanilla-valid recipes are unaffected either way).
- Consumes: nothing from later tasks.

- [ ] **Step 1: Pull the Fabric originals for reference**

```bash
git show d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/AlchemyListener.java > /tmp/fabric-alchemy-listener.java
git show d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/mixin/BrewingStandBlockEntityMixin.java > /tmp/fabric-brewingstand-mixin.java
git show d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/mixin/BrewingStandBrewTimeAccessor.java > /tmp/fabric-brewtime-accessor.java
```

- [ ] **Step 2: Write `BrewingStandBrewTimeAccessor.java`**

Port `/tmp/fabric-brewtime-accessor.java` directly: `@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandBrewTimeAccessor`, `@Accessor("brewTime") int getBrewTime();`,
`@Accessor("brewTime") void setBrewTime(int brewTime);`. Already pure per the Global Constraints
note — do not add anything else to this interface.

- [ ] **Step 3: Write `BrewingStandTickMixin.java`**

`@Mixin(BrewingStandBlockEntity.class)`. Two injectors, both targeting the verified 1.21.1
signatures from the spec (re-confirm via `javap -p -classpath
build/moddev/artifacts/neoforge-21.1.248-merged.jar net.minecraft.world.level.block.entity.BrewingStandBlockEntity`
before committing):

1. `@Inject(method = "isBrewable", at = @At("HEAD"), cancellable = true)` on
   `private static boolean isBrewable(PotionBrewing, NonNullList<ItemStack>)` — call
   `AlchemyListener.isValidBrew(slots)`; if true, `cir.setReturnValue(true)`. Direct analogue of
   Fabric's `canCraft` injector.
2. `@Inject(method = "serverTick", at = @At("HEAD"))` on
   `public static void serverTick(Level, BlockPos, BlockState, BrewingStandBlockEntity)` — call
   `AlchemyListener.applyCatalysis(pos, blockEntity)`. Direct analogue of Fabric's `tick`
   injector.

Verify `allow`/`require` counts against the jar per Global Constraints (Fabric used `allow = 1`
on both; confirm this still holds, don't copy blind).

- [ ] **Step 4: Register the mixin**

Add `"BrewingStandTickMixin"` and `"BrewingStandBrewTimeAccessor"` to the `"mixins"` array in
`src/main/resources/mcmmo.mixins.json`, alphabetical order.

- [ ] **Step 5: Write `AlchemyListener.java`**

Port from `/tmp/fabric-alchemy-listener.java`: the `BREWING_STAND_OWNERS` map, `CATALYSIS_TIMER`
field, `register()` (wire both real listeners per Global Constraints), `clearOwners()`,
`onUseBlock` (retarget to `PlayerInteractEvent.RightClickBlock` — follow
`SuperAbilityListener.onUseBlock`'s exact server-side-only guard pattern, `event.getLevel()`/
`event.getPos()`/`event.getEntity()` in place of the Fabric callback's parameters — read
`SuperAbilityListener.java`'s `onUseBlock` for the real event API, don't guess field names),
`applyCatalysis`, `resolveBrewSpeed`. Add the two stub methods per the Interfaces section above.

Do not port `isValidBrew`/`onBrewCraft` bodies yet — stub `isValidBrew` to always return `false`
and leave `onPotionBrewPre` an empty method (Task B fills both in).

- [ ] **Step 6: Write tests**

`AlchemyListenerCatalysisTest.java` — pin `applyCatalysis`'s three branches: idle stand
(`brewTime <= 0`) resets and does nothing; a running brew with no tracked owner uses
`CatalysisTimer.VANILLA_BREW_SPEED`; a tracked owner with Catalysis enabled uses
`AlchemyManager.calculateBrewSpeed(...)`. Mock `BrewingStandBrewTimeAccessor` (the accessor
interface itself, not a real entity — same reflection-avoidance approach `HunterListenerTest`
uses for accessor calls) and `McMMOPlayer`/`UserManager` the established way.

`BrewingStandBrewTimeAccessorTest.java` — reflection-based structural checks only (accessor
methods exist with correct signature, `@Accessor` annotation value is `"brewTime"`,
`BrewingStandBlockEntity` actually declares a `brewTime` field) — same shape as
`LivingEntityDropFromLootTableAccessorTest.java`.

- [ ] **Step 7: Compile and test**

Run: `./gradlew compileJava compileTestJava test --tests "com.gmail.nossr50.neoforge.listeners.AlchemyListenerCatalysisTest" --tests "com.gmail.nossr50.neoforge.mixin.BrewingStandBrewTimeAccessorTest"`

- [ ] **Step 8: Commit**

---

### Task B: Craft/XP via `PotionBrewEvent.Pre`

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/AlchemyListener.java` (fill
  `isValidBrew`, `onPotionBrewPre`; add `onBrewCraft`)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/AlchemyListenerBrewTest.java`

**Interfaces:**
- Consumes: Task A's `AlchemyListener` class (same file, filling in the two stubs) — no new
  cross-file interfaces. `AlchemyPotionBrewer.isValidBrew(PlatformInventory)` and
  `.finishBrewing(PlatformInventory, McMMOPlayer)` (already exist, unchanged).
- Produces: nothing new consumed by later tasks — this is the last task.

- [ ] **Step 1: Confirm the slot-order caveat from the spec.** Before writing `onBrewCraft`,
  verify `PotionBrewEvent`'s 5-slot array ordering against `BrewingStandBlockEntity`'s own
  slot-index constants in the merged jar (`javap -p` — look for `FUEL_SLOT`/ingredient-slot
  constants, or read the class's `getContainerSize`/slot-usage in `serverTick`). Confirm it is
  bottle slots 0-2, ingredient 3, fuel 4 (matching what `AlchemyPotionBrewer` expects via
  `PlatformInventory`) before wiring the event handler — do not assume from the spec's javadoc
  quote alone.

- [ ] **Step 2: Fill `isValidBrew`** — `return
  AlchemyPotionBrewer.isValidBrew(new PlatformInventory(slots));`, replacing the stub.

- [ ] **Step 3: Write `onBrewCraft(Level, BlockPos, NonNullList<ItemStack>)`** and
  **`onPotionBrewPre(PotionBrewEvent.Pre)`**, porting `/tmp/fabric-alchemy-listener.java`'s
  `onBrewCraft` body: resolve the owner from `BREWING_STAND_OWNERS`, call
  `AlchemyPotionBrewer.finishBrewing`. `onPotionBrewPre` reads the event's 5 slots into a
  `NonNullList<ItemStack>` (or wraps the event directly if `PlatformInventory` can adapt to the
  event's `getItem`/`setItem` accessors — check `PlatformInventory`'s constructor options before
  deciding, don't force a copy if a direct wrap works), checks `isValidBrew`, and if true: gets
  the event's `BlockPos`/`Level` (check `PotionBrewEvent`'s real API for how to recover these —
  it may not expose them directly, unlike the Fabric mixin's `craft(World world, BlockPos pos,
  ...)` parameters; if `PotionBrewEvent` truly has no position/level accessor, resolving the
  owner will need the owner map keyed differently, or a `TileEntityBrewingStand`-adjacent lookup
  — **investigate this at implementation time, it is not resolved in the spec** and may require
  falling back to a mixin `@Inject` capturing `pos`/`world` into a thread-local the event handler
  reads, mirroring how `LivingEntityDropFromLootTableAccessorCalls`-style helpers bridge mixin
  context to listener code), calls `onBrewCraft`, then cancels the event
  (`event.setCanceled(true)` per its `ICancellableEvent` contract — no items need re-setting on
  the event itself since `onBrewCraft` already mutated `slots` in place before cancellation, and
  cancellation is what makes NeoForge write the array back and fire `Post`).

- [ ] **Step 4: Write tests** — `AlchemyListenerBrewTest.java`: an unrecognized (non-mcMMO,
  non-vanilla-covered-by-mixin) brew leaves the event uncancelled; a recognized mcMMO brew with a
  tracked owner cancels the event and awards XP via a mocked `AlchemyManager`; a recognized brew
  with no tracked owner still finishes (cancels, mutates slots) but awards no XP. Mock
  `PotionBrewEvent.Pre` (real class, not final — check whether it needs a real `NonNullList`
  backing rather than a Mockito stub, since `getItem`/`setItem` operate on a real list field, not
  an overridable method chain someone can mock cleanly — likely needs a **real** `PotionBrewEvent.Pre`
  instance constructed with a real `NonNullList<ItemStack>`, similar to how
  `FishingListenerMagicHunterTest` had to use real `Enchantment` instances instead of mocks
  because the real method read a private field directly).

- [ ] **Step 5: Compile and test**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.AlchemyListenerBrewTest"` then
the full suite: `./gradlew test`.

- [ ] **Step 6: Commit**

## Manual In-Game Verification (after both tasks land)

None of the automated tests exercise the real mixins end-to-end (consistent with every other
mixin in this port). Before considering this done:

1. Right-click a brewing stand, then brew a normal vanilla potion; confirm it still works
   unmodified and the player receives no unexpected XP interference.
2. Brew an mcMMO-only recipe (one vanilla does not recognize); confirm it starts and completes,
   and Alchemy XP is awarded to the player who last right-clicked the stand.
3. With Catalysis ranked up, confirm a brew completes noticeably faster than the vanilla 400-tick
   baseline.
4. Confirm the mod still boots cleanly (`./gradlew runServer`, headless, grep the log for
   `InvalidMixinException`/`MixinApplyError` — see the Hunter/Fishing plans' own boot-crash
   lesson) before considering this shippable, not just unit-tested.
