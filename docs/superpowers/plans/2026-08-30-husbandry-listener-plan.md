# Husbandry Listener (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Fabric mcMMO `HusbandryListener` (1134 lines) and its 12 mixins to NeoForge
1.21.1, unlocking the whole Husbandry skill and its super ability, Herdsman's Call.

**Architecture:** Mixin-heavy, like Fishing, but with one genuine simplification win: the shear
verb collapses from Fabric's 2 mixins (species-enumerated) to 1 (NeoForge's `IShearable`/
`ShearsItem#interactLivingEntity` already unifies every shearable species). Everything else is
close to a 1:1 mixin-shape port, with several renamed/restructured vanilla methods that must be
re-verified rather than transcribed — see the spec for exactly which. New, non-mixin
infrastructure: real `AttachmentType<UUID>` registration for the `BRED_BY` marker (this
codebase's first `DeferredRegister` of any kind).

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), Sponge Mixin, JUnit 5 + Mockito.

**Spec:** docs/superpowers/specs/2026-08-30-husbandry-listener-design.md — **read this first**.
It has the seam-by-seam verification (every Mojang rename, the shear simplification, the hive
`isSmokeyPos` polarity inversion, the `AttachmentType` registration/persistence details) this
plan's tasks depend on, plus explicit flags for what could NOT be fully verified in this pass —
those flags are re-stated in the relevant task below as an acceptance-criteria checkpoint, not
skipped.

**Source:** the Fabric original, recoverable at commit `d0764257671576525aedd97308be2f8c6d85e2fd`:
`src/main/java/com/gmail/nossr50/fabric/listeners/HusbandryListener.java` (1134 lines) and the 12
Husbandry mixins under `src/main/java/com/gmail/nossr50/fabric/mixin/` named in the spec. Pull
each with `git show d0764257671576525aedd97308be2f8c6d85e2fd:<path>` and translate using the
spec's mapping table. Do not re-derive the mappings from memory or by transcribing Fabric target
strings — the spec already did the source-jar verification, and flagged exactly which seams still
need the implementer's own `javap`/source check.

## Global Constraints

- Every `HusbandryManager`/`TreasureConfig`/`ItemSpecBuilder`/`MetadataStore` call the Fabric
  original makes already exists on this branch with the same signature (`HusbandryManager` is
  complete — see spec) — do not modify those files, do not re-derive skill math, just call them.
- `AnimalBreedOriginMixin` is explicitly OUT of scope — it is Hunter's anti-farm mob-mastery
  marker, not a Husbandry mechanic. Do not port it as part of this plan.
- `HusbandryListener` needs **no `register()` method** for the mixin-driven parts — mixins apply
  automatically via `mcmmo.mixins.json`. Herdsman's Call (Task E) is the one exception: it is
  wired as a direct call from `PlayerMovementTracker`'s existing per-tick loop, not a mixin.
- `BRED_BY` must be a real, registered `AttachmentType<UUID>` with disk persistence
  (`.serialize(UUIDUtil.CODEC)`) — never a `MetadataStore`/in-memory stand-in. Read and write it
  only through `hasData`/`getExistingDataOrNull`/`setData`/`removeData` — never plain `getData`,
  which materializes and syncs a default value on first read (see spec §11).
- Every new mixin's `@At(value="INVOKE", target=...)` / method descriptor string must be
  independently re-verified against the real `neoforge-21.1.248-sources.jar` (or `javap` against
  the merged jar) by whichever task implements it — the spec verified the seam *exists* and named
  its shape, but several call-site helper methods changed name or arity between Fabric/Yarn and
  this Mojang-mapped NeoForge version (milk's `ItemUtils.createFilledResult`, hive's
  `shrink`/`isSmokeyPos`, etc.) and transcribing a Fabric target string verbatim will fail to
  match at mixin-apply time (loudly) or, worse, silently match the wrong call if a coincidentally
  similar one exists.
- Mixin interfaces (`@Mixin`-annotated `interface`, if any accessor is needed anywhere in this
  plan) must stay pure abstract-only — no static/default members — per the boot-crash lesson in
  `LivingEntityDropFromLootTableAccessor.java`'s own javadoc. This plan is not expected to need
  any new accessor interfaces (no `@Shadow`-only interface case arises), but if one turns out to
  be needed, this rule is non-negotiable.
- Verify mixin application for real before calling any task done, the same way the Hunter boot
  crash was actually caught and fixed: `timeout 150 ./gradlew runServer --console=plain 2>&1 |
  tee /tmp/runserver-husbandry.log | grep -iE "mixin|InvalidMixinException|FATAL|
  MixinApplyError|Done \("` — plain JUnit never applies mixins, so this is the only way to catch
  a bad `@At` target or a mixin target-type mismatch before the user's own `runClient`/`runServer`
  does. Run this at the end of every task in this plan, not just at the very end of the branch.

---

### Task A: Foundations — interaction stash, `BRED_BY` attachment, listener skeleton

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/PlayerInteractionStashMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/HusbandryListener.java`
  (skeleton: private constructor, `Interaction` record, `PLAYER_INTERACTION` `ThreadLocal`,
  `beginPlayerInteraction`/`endPlayerInteraction`, `husbandryOf`, `husbandryOfInteractionWith`,
  `configStringOf`, `giveOrDrop` — the shared helpers every later task calls into)
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOAttachments.java` (add a real,
  registered `AttachmentType<UUID> BRED_BY`, alongside the existing `MOB_ORIGIN` stand-in —
  do not touch `MOB_ORIGIN`'s existing behavior)
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (register the new
  `DeferredRegister<AttachmentType<?>>` on the mod event bus — this is the first
  `DeferredRegister` in this codebase; follow standard NeoForge registration shape, register in
  the constructor alongside other one-time mod-bus setup)
- Modify: `src/main/resources/mcmmo.mixins.json`
- Test: `src/test/java/com/gmail/nossr50/neoforge/mixin/PlayerInteractionStashMixinTest.java`
  (structural/reflection-only, same shape as
  `LivingEntityDropFromLootTableAccessorTest.java` — plain JUnit cannot apply mixins)
- Test: `src/test/java/com/gmail/nossr50/neoforge/McMMOAttachmentsBredByTest.java` (attachment
  registration + `hasData`/`getExistingDataOrNull`/`setData`/`removeData` round-trip; this CAN be
  tested under plain JUnit if `NeoForgeRegistries`/the attachment system doesn't require a live
  ModLauncher context to construct in isolation — if it does, fall back to the same
  reflection-only structural-check pattern the mixin tests use, and note why in the test's own
  javadoc)

**Interfaces:**
```java
// PlayerInteractionStashMixin (Mixin(Player.class)) — verify exact interact() signature
// per spec §4 before writing the @At target
@Inject(method = "interact(...)", at = @At("HEAD"))
private void mcmmo$beginInteraction(Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir)

@Inject(method = "interact(...)", at = @At("RETURN"))
private void mcmmo$endInteraction(Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir)

// HusbandryListener
public static void beginPlayerInteraction(Player player, Entity target)
public static void endPlayerInteraction()
private static HusbandryManager husbandryOfInteractionWith(Entity target)
private static HusbandryManager husbandryOf(ServerPlayer player)
private static String configStringOf(Entity animal)
private static void giveOrDrop(ServerPlayer player, ItemStack stack)

// McMMOAttachments
public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = ...
public static final Supplier<AttachmentType<UUID>> BRED_BY = ATTACHMENT_TYPES.register("bred_by",
    () -> AttachmentType.builder(...).serialize(UUIDUtil.CODEC).build());
```

**Acceptance Criteria:**
- [ ] `interact`'s real Mojang signature confirmed via source read or `javap` (spec §4 flag) —
      the mixin's `@At` target reflects the confirmed signature, not a guess.
- [ ] `BRED_BY` is registered through `NeoForgeRegistries.Keys.ATTACHMENT_TYPES`, confirmed by a
      test that calls `hasData`/`setData`/`removeData` on a constructed entity (or, if that needs
      a live game context, a reflection-based structural check with a javadoc explaining why).
- [ ] `MOB_ORIGIN`'s existing behavior and its own javadoc are untouched — this task adds `BRED_BY`
      alongside it, does not "fix" or refactor `MOB_ORIGIN`'s pre-existing non-persistence.
- [ ] `./gradlew runServer` boots clean (global constraint's verification command) with the new
      mixin and the new attachment registration both present.

---

### Task B: Breed + Raise — `onAnimalsBred`, `onLovePlayer` (Multi-Breed), `onGrowthApplied`/`onBreedingAgeChange` (raise, feed, Accelerated Growth)

**Depends on:** Task A (interaction stash, `BRED_BY`, listener skeleton).

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/BredAnimalsTriggerMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/AnimalSetInLoveMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/AgeableMobGrowthMixin.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/HusbandryListener.java` (add
  `onAnimalsBred`, `claimOffspring`, `maybeBearTwin`, `onLovePlayer`, `isMultiBreedCandidate`,
  `SPREADING_LOVE` `ThreadLocal`, `onGrowthApplied`, `onBreedingAgeChange`,
  `isCallOfTheWildSummon`)
- Modify: `src/main/resources/mcmmo.mixins.json`
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/HusbandryListenerBreedRaiseTest.java`

**Interfaces:** port `onAnimalsBred`/`onLovePlayer`/`onGrowthApplied`/`onBreedingAgeChange` from
the Fabric original near-verbatim (MC-typed glue only — `AnimalEntity`→`Animal`,
`PassiveEntity`→`AgeableMob`, `ServerWorld`→`ServerLevel`, `getUuid()`→`getUUID()`, etc.). The
`BRED_BY` marker read/write (`claimOffspring`'s `setAttached`, `onBreedingAgeChange`'s
`removeAttached`) must go through Task A's real `AttachmentType` accessors, not a stand-in.

**Acceptance Criteria:**
- [ ] `BredAnimalsTriggerMixin` targets `net.minecraft.advancements.critereon.BredAnimalsTrigger
      #trigger` (spec §1) — verify the exact descriptor via `javap` before finalizing `allow`.
- [ ] A test proves foxes and turtles still pay breeding XP through this seam (or documents why
      it cannot be tested without a live `Fox`/`Turtle` entity and falls back to a structural
      check on the mixin's target class + method name) — this is the exact case the Fabric port
      was originally wrong about; a test that only covers `Cow`/generic `Animal` would not have
      caught that bug and should not be trusted to catch its NeoForge equivalent either.
- [ ] `AgeableMobGrowthMixin`'s raise hook targets `setAge(int)`, not `ageBoundaryReached()` —
      a test (or a code comment citing spec §3's Goat/Hoglin `super` finding) makes the reason
      explicit so a future edit cannot "simplify" it back onto the wrong seam.
- [ ] Multi-Breed's `SPREADING_LOVE` re-entrancy guard is present and covers the whole sweep —
      a test proves a fed animal's neighbours do not themselves trigger a second sweep.
- [ ] `claimOffspring`/`onBreedingAgeChange` use `hasData`/`getExistingDataOrNull`/`setData`/
      `removeData` on `McMMOAttachments.BRED_BY`, never plain `getData`.
- [ ] `./gradlew runServer` boots clean.

---

### Task C: Harvest family — Shear, Hive, Milk, Brush, Hidden Bounty, D-H5 cooldown

**Depends on:** Task A. Independent of Task B (can run in parallel with it).

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/ShearsItemInteractMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/BeehiveBlockUseItemOnMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/CowGoatMilkMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/MushroomCowStewMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/ArmadilloBrushMixin.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/HusbandryListener.java` (add
  `beginShear`/`endShear`/`onShearDropStack`/`onShearToolDamaged`, `onHoneycombHarvested`/
  `onHoneyBottled`/`bonusHiveHelpings`/`hiveHarvestLeavesBeesCalm`/`onHiveToolDamaged`,
  `onMilked`, `onBrushed`/`onBrushToolDamaged`, `rollHiddenBounty`, `harvestCooldownElapsed`,
  `HARVEST_COOLDOWN_KEY`, the four `HIDDEN_BOUNTY_*` verb constants)
- Modify: `src/main/resources/mcmmo.mixins.json`
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/HusbandryListenerShearTest.java`
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/HusbandryListenerHiveTest.java`
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/HusbandryListenerMilkBrushTest.java`

**Interfaces:** `ShearsItemInteractMixin` is a genuine redesign, not a transcription — see spec
§5. It replaces Fabric's `EntityShearDropMixin` + `ShearableInteractMixin` with one mixin on
`ShearsItem#interactLivingEntity`. `BeehiveBlockUseItemOnMixin` ports Fabric's
`BeehiveHarvestMixin` 4-for-4 but **the Beekeeper injector's polarity is inverted** relative to
Fabric — see spec §6's worked example (`return smokey || husbandry.
countsAsShelteredHiveHarvest();`, gated on `isSmokeyPos`, not `isLitCampfireInRange`). Milk and
Brush port close to 1:1 per spec §7/§8, with renamed call-site helpers to re-derive, not copy.

**Acceptance Criteria:**
- [ ] `ShearsItemInteractMixin`'s injection points (window-open, drops-doubling, durability-save)
      are verified against the real `interactLivingEntity` method body via `javap`/source read —
      spec §5 explicitly flags this as unconfirmed and names the fallback (`@Redirect`/
      `@WrapOperation` on `spawnShearedDrop` if `drops` isn't a `@ModifyVariable`-reachable local).
- [ ] A test shears a `Bogged` or `SnowGolem` (not just `Sheep`) — or documents why it can't —
      proving the new one-mixin design actually covers non-sheep species without a species list,
      which is the entire point of preferring `ShearsItem#interactLivingEntity` over Fabric's
      approach.
- [ ] A test exercises the Beekeeper polarity directly: a sheltered harvest (`isSmokeyPos` true,
      i.e. campfire present) stays calm regardless of the sub-skill, and an unsheltered harvest
      is calm **only** when `countsAsShelteredHiveHarvest()` returns true — getting this backwards
      is exactly the bug spec §6 warns is easy to introduce by transcribing the Fabric expression.
- [ ] `CowGoatMilkMixin` and `MushroomCowStewMixin`'s `@At` targets use `ItemUtils.
      createFilledResult`'s real descriptor, confirmed via source/`javap`, not `ItemUsage.
      exchangeStack` (the Fabric name).
- [ ] Milk's dispenser-exclusion claim (spec §7: "no milking dispenser exists") is re-confirmed
      with a grep of `net/minecraft/core/dispenser/` for `MILK_BUCKET` before relying on it as a
      security boundary.
- [ ] Hidden Bounty's four verb-string constants (`"Shear"`, `"Hive"`, `"Milk"`, `"Brush"`) match
      `treasures.yml`'s `Drops_From` groups exactly — pin with a config test the same way the
      Fabric original's `HusbandryTreasureConfigTest` did, per the Fabric doc's own note that a
      typo here is a sub-skill that silently never finds anything.
- [ ] `./gradlew runServer` boots clean.

---

### Task D: Selective Breeding + Brood

**Depends on:** Task A. Independent of Tasks B and C (can run in parallel).

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/AbstractHorseChildAttributesMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/ThrownEggHatchMixin.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/HusbandryListener.java` (add
  `beginSelectiveBreeding`/`endSelectiveBreeding`/`applySelectiveBreedingBias`/
  `husbandryOfBreeder`, `SELECTIVE_BREEDING` `ThreadLocal`, `onEggHatchRoll`/`onFullClutchRoll`/
  `husbandryOfThrower`)
- Modify: `src/main/resources/mcmmo.mixins.json`
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/HusbandryListenerSelectiveBreedingTest.java`
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/HusbandryListenerBroodTest.java`

**Interfaces:** per spec §9, the stash opens around `AbstractHorse#setOffspringAttributes` (not
Fabric's `setChildAttributes` name) and the bias applies inside `createOffspringAttribute`'s
`@ModifyReturnValue`, `allow = 3` (three `return` statements — confirmed, do not reduce to 1). Per
spec §10, `ThrownEggHatchMixin` targets `ThrownEgg#onHit`, two `@ModifyExpressionValue`s on
`this.random.nextInt(...)` at `ordinal = 0` and `ordinal = 1`.

**Acceptance Criteria:**
- [ ] `createOffspringAttribute`'s `@ModifyReturnValue` is declared with `allow = 3` and a test
      or comment explains why (the two out-of-range reflection branches, not just the in-range
      one) — an `allow = 1` here would fail to load per spec §9, but a silently-reduced-scope
      version (e.g. catching the exception and downgrading to a mixin that only fires once) would
      be worse: extreme rolls would go unbiased with no error at all.
- [ ] A test confirms `Fox`/`Turtle`-style bypass does NOT apply here — `setOffspringAttributes`
      is declared once on `AbstractHorse` with nothing overriding it (unlike the breed-XP seam,
      this one has no known per-species bypass, but this should be positively confirmed, not
      assumed by analogy to Task B's finding).
- [ ] Brood's chick is confirmed to carry no `BRED_BY` marker (spec: "a hatched chick is
      deliberately unmarked" — Fabric's own precedent) — a test proves this directly rather than
      by omission.
- [ ] `Projectile#getOwner()` (or its 1.21.1 equivalent) is confirmed to still exist and still
      close the dispenser-egg exclusion the same way (spec §10's one unverified item).
- [ ] `./gradlew runServer` boots clean.

---

### Task E: Herdsman's Call

**Depends on:** Tasks A–D should be substantially complete (`HusbandryManager` calls this task
needs — `isHerdsmansCallActive()`, the max-radius helper — already exist independent of this
plan's other tasks, so this task's only real dependency is understanding what `callTheHerd` does,
which requires locating and reading the Fabric source first).

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/PlayerMovementTracker.java`
  (replace the `OMISSION` comment at lines 269-272 with a real
  `applyHerdsmansCall(player, mmoPlayer)` call, positioned identically to where the Fabric
  original's `callTheHerd` call sat — above the Agility-manager-missing guard, same as
  `applyIronSkin`)
- Modify or create: wherever `applyHerdsmansCall`'s logic actually belongs — determine this after
  reading the Fabric `callTheHerd` source (see below); it may belong directly in
  `PlayerMovementTracker` (if it is genuinely a per-tick player-side effect, matching
  `applyIronSkin`'s shape) or may need its own small helper in `HusbandryListener`/a new file
  (if it is closer in shape to Multi-Breed's one-shot entity sweep) — do not assume the shape
  before reading the source.
- Test: matching whichever file ends up holding the logic.

**First step, before any code:** locate `callTheHerd`'s real Fabric implementation. The spec's
own research pass could not find it inside `fabric/listeners/HusbandryListener.java` and flagged
this as unresolved — search the whole tree at commit
`d0764257671576525aedd97308be2f8c6d85e2fd` (`git grep -n callTheHerd
d0764257671576525aedd97308be2f8c6d85e2fd` or equivalent) before writing any interface or task
breakdown further than what's here. This task's `Files:`/`Interfaces:` sections above are
provisional until that source is read — update this task's plan in place once it's found, rather
than guessing its shape now.

**Acceptance Criteria:**
- [ ] `callTheHerd`'s Fabric source has been located and read in full before any NeoForge code is
      written for this task.
- [ ] `applyHerdsmansCall` sits above the Agility-manager-missing `return` in
      `PlayerMovementTracker`'s per-tick loop, matching `applyIronSkin`'s position, with a comment
      explaining why (same reasoning as the existing `OMISSION` comment it replaces).
- [ ] `isHerdsmansCallActive()`'s cooldown-bypass effect on `harvestCooldownElapsed` (Task C,
      already implemented per the Fabric source's own cross-reference) is confirmed still correct
      now that Herdsman's Call itself is wired up end-to-end — i.e. activating the ability during
      a live test actually lets harvest verbs bypass their cooldown, not just that the code path
      exists.
- [ ] `./gradlew runServer` boots clean.

---

## Final Verification (whole branch)

- [ ] All five tasks' `./gradlew runServer` boot checks pass with the full Husbandry mixin set
      applied together (a per-task boot check does not prove they compose — Sponge Mixin
      conflicts between mixins targeting the same class, e.g. any future overlap on `Animal` or
      `AgeableMob`, only show up with everything applied at once).
- [ ] `./gradlew test` passes.
- [ ] A whole-branch review (per this project's established SDD final-review process) checks
      every spec-flagged "not independently verified in this pass" item got resolved by its
      owning task, not silently carried forward unresolved: `Player#interact`'s exact signature
      (Task A), `MushroomCow#mobInteract`'s exact stew-branch call shape and the milk-dispenser
      grep (Task C), `Projectile#getOwner()` (Task D), and `callTheHerd`'s actual mechanic
      (Task E).
