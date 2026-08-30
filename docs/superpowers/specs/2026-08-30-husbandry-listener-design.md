# Husbandry Listener (NeoForge) Design

**Goal:** Port the Fabric mcMMO `HusbandryListener` (1134 lines) and its 12 supporting mixins to
NeoForge 1.21.1, unlocking the whole Husbandry skill (breed, raise, shear, hive, milk, brush,
Selective Breeding, Brood, Hidden Bounty) plus its super ability, Herdsman's Call.

**Source:** the Fabric original, recoverable at commit `d0764257671576525aedd97308be2f8c6d85e2fd`:
`src/main/java/com/gmail/nossr50/fabric/listeners/HusbandryListener.java` (1134 lines) and 12
mixins under `src/main/java/com/gmail/nossr50/fabric/mixin/`:
`PlayerEntityInteractMixin`, `BredAnimalsCriterionMixin`, `AnimalLovePlayerMixin`,
`PassiveEntityGrowthMixin`, `EntityShearDropMixin`, `ShearableInteractMixin`,
`BeehiveHarvestMixin`, `CowMilkMixin`, `MooshroomStewMixin`, `ArmadilloBrushMixin`,
`HorseChildAttributesMixin`, `EggHatchMixin`.

**`AnimalBreedOriginMixin` is explicitly OUT of scope.** It shares the Fabric mixin package but
belongs to Hunter's anti-farm mob-mastery system (stamps `MobOrigins` on a bred child so a
passive-mob farm cannot advance mob mastery — see its own javadoc, "Hunter's anti-farm gate,
breeding half"). It is not part of Husbandry's mechanics and is not ported by this plan. Its
NeoForge equivalent is not yet wired either (`neoforge/mixin/` has no breed-origin mixin, and
`McMMOAttachments.MOB_ORIGIN` on NeoForge is currently a non-persistent in-memory stand-in — see
"MOB_ORIGIN's pre-existing gap" below) — that is a pre-existing, separately-tracked gap, not
something this plan introduces or fixes.

**The Fabric original is unusually well-documented — read it as primary source, not this spec.**
Every mixin's javadoc explains a real seam-selection mistake an earlier version of *this same
port* made and had to correct (hooking `interactMob` instead of `lovePlayer`, `onGrowUp` instead
of `setBreedingAge`, "the player overload" instead of `onUseWithItem`, etc.), each with the
bytecode evidence for why. This spec's job is only to re-verify those seams still exist with
those exact properties in 1.21.1 NeoForge (methods get renamed and restructured between Yarn and
Mojang mappings, and between Minecraft versions) and to find NeoForge-native replacements where
one now exists. Where a Fabric doc comment's reasoning still holds byte-for-byte, this spec says
so and moves on rather than re-deriving it.

## MC-free skill logic already exists

`com.gmail.nossr50.skills.husbandry.HusbandryManager` is already on this branch, complete,
including `isHerdsmansCallActive()`, `applyGrowthAcceleration`, `rollTwins`, `canMultiBreed`,
`getMultiBreedRadius`, `HARD_MAX_MULTI_BREED_RADIUS`, `onFeedBaby`, `applyFeedBonus`, `onRaise`,
`onBreed`, `onShear`, `rollBonusHarvestDrop`, `rollToolDurabilitySave`, `onHiveHarvest`,
`rollBonusHoney`, `countsAsShelteredHiveHarvest`, `onMilk`, `onBrush`, `rollEggHatch`,
`rollMultipleChicks`, `applyStatBias`, `selectHiddenBounty`, `rollHiddenBounty`,
`onHiddenBountyFound`, `getHarvestCooldownSeconds`. This plan's job is 100% MC-typed glue —
exactly the same shape as Hunter's and Fishing's ports — never re-derive skill math.

## Seam-by-seam verification against 1.21.1 NeoForge

All of the following were verified by reading the real merged/patched sources at
`build/moddev/artifacts/neoforge-21.1.248-sources.jar` (unpacked to Mojang-mapped `.java`, not
decompiled bytecode guesses) and, for one case (shear), the NeoForge-authored (not vanilla)
`IAttachmentHolder`/`IShearable` sources in the same jar.

### 1. Breed XP — `BredAnimalsTrigger` (was `BredAnimalsCriterion`)

Confirmed 1:1. `net.minecraft.advancements.critereon.BredAnimalsTrigger#trigger(ServerPlayer,
Animal, Animal, @Nullable AgeableMob)` is the exact Mojang-mapped rename of Yarn's
`BredAnimalsCriterion#trigger`, same 4-arg shape, same nullable child.

**Fox and Turtle still bypass the "obvious" funnel in 1.21.1, exactly as the Fabric doc warns.**
`Animal#spawnChildFromBreeding` (Mojang name for `AnimalEntity#breed`) now also fires NeoForge's
own `BabyEntitySpawnEvent` — but `Fox.FoxBreedGoal` (verified: `net/minecraft/world/entity/
animal/Fox.java:832`, class `FoxBreedGoal extends BreedGoal`) re-implements the whole breeding
sequence inline and calls `CriteriaTriggers.BRED_ANIMALS.trigger(...)` directly at line 875,
**never reaching `spawnChildFromBreeding`, and therefore never firing `BabyEntitySpawnEvent`
either.** This rules out `BabyEntitySpawnEvent` as the universal breed hook — it would silently
pay zero for foxes (and, by the same inline-reimplementation pattern, turtles) exactly as
`AnimalEntity#breed` would have on Fabric. **`BredAnimalsTrigger#trigger` remains the correct,
only-universal seam.** Mixin, not event.

### 2. Multi-Breed — `Animal#setInLove` (was `AnimalEntity#lovePlayer`)

Confirmed. `net/minecraft/world/entity/animal/Animal.java:159`, `public void
setInLove(@Nullable Player)`. Same shared-callee shape the Fabric doc found: horses, camels,
llamas and pandas each override `mobInteract` (Mojang name for `interactMob`) and call
`setInLove` themselves rather than deferring to `Animal`'s own implementation — not
re-individually verified per species in this pass (the Fabric doc's own verification — "the only
classes in `net.minecraft.entity` that reference it" — is a structural claim unlikely to have
changed under a rename), but the seam itself, and the reason to prefer it over `mobInteract`,
carries over unchanged. Mixin, `@Inject` at `TAIL`, same `ThreadLocal` re-entrancy guard.

### 3. Raise + Feed + Accelerated Growth — `AgeableMob#setAge`/`#ageUp` (was `PassiveEntity#setBreedingAge`/`#growUp`)

Confirmed, including the exact bug the Fabric doc's "why not `onGrowUp`" section warns about.
Mojang names: `setBreedingAge(int)` → `setAge(int)` (`AgeableMob.java:90`); `growUp(int,
boolean)` → `ageUp(int, boolean)` (`AgeableMob.java:65`); the plan's original candidate
`onGrowUp()` → `ageBoundaryReached()` (called from inside `setAge`, line 92).

**Verified directly: `Hoglin#ageBoundaryReached` and `Goat#ageBoundaryReached` do NOT call
`super.ageBoundaryReached()`** (`net/minecraft/world/entity/monster/hoglin/Hoglin.java:183`,
`net/minecraft/world/entity/animal/goat/Goat.java:124` — both override, neither has a `super`
call in the body; contrast `Villager.java:257` and `Turtle.java:304`, which do). A mixin on
`ageBoundaryReached`/`onGrowUp` would still pay exactly zero raise XP for goats and hoglins in
1.21.1, for the identical reason the Fabric port rejected it. **`setAge(int)` is the correct
seam** — declared only on `AgeableMob`, overridden by nothing, and it is where the
`ageBoundaryReached()` call itself lives, so every path (including both `Goat`'s and `Hoglin`'s)
arrives here regardless of their broken override.

The feed verb's `growUp`/`ageUp` seam and its dependency on an interaction stash (see §4) carry
over unchanged in shape; not independently re-verified species-by-species (would require
re-reading horse/camel/llama/dolphin/panda `mobInteract`+`receiveFood` bodies, deferred to the
implementer as low-risk since the funnel itself — `ageUp(int, boolean)` — is unambiguous and
singular on `AgeableMob`).

### 4. The interaction stash — `Player#interact` (was `PlayerEntity#interact`)

Not located by an exact grep hit in this pass (`Player.java` is large; the method exists —
`Entity#interact`/`Player` dispatch is standard vanilla shape and this exact seam is *already*
proven correct on Fabric, including its "verified via the server packet handler's bootstrap
method" claim, which is Fabric-toolchain-specific plumbing that has no NeoForge equivalent to
re-verify the same way). **The implementer must confirm the exact signature and package via
`javap` or a source read of `net/minecraft/world/entity/player/Player.java` before writing
Task A's mixin** — expect `public InteractionResult interact(Entity, InteractionHand)`, the
direct Mojang rename of `interact(Entity, Hand) -> ActionResult`. This is the one seam in this
plan not independently confirmed against the real 1.21.1 source; flagged rather than assumed.

### 5. Shear (XP, Bountiful Harvest bonus drop, durability save) — genuine simplification, NOT a 1:1 port

**Do not port `EntityShearDropMixin` and `ShearableInteractMixin` as separate mixins.** NeoForge
1.21.1 has unified all entity shearing behind one interface, `net.neoforged.neoforge.common.
IShearable` (`isShearable`/`onSheared`/`spawnShearedDrop`), and one call site,
`net.minecraft.world.item.ShearsItem#interactLivingEntity(ItemStack, Player, LivingEntity,
InteractionHand)` (confirmed by reading both files directly — `ShearsItem.java`'s own doc
comment: *"Neo: Migrate shear behavior into `ShearsItem#interactLivingEntity` to call into
IShearable instead of relying on `Mob#mobInteract`"*). One method now does everything Fabric
needed two mixins for:

```java
// ShearsItem#interactLivingEntity, abbreviated
if (entity instanceof IShearable target) {
    if (target.isShearable(player, stack, entity.level(), pos)) {
        List<ItemStack> drops = target.onSheared(player, stack, entity.level(), pos);
        if (!isClient) for (ItemStack drop : drops) target.spawnShearedDrop(entity.level(), pos, drop);
        entity.gameEvent(GameEvent.SHEAR, player);
        if (!isClient) stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        return InteractionResult.sidedSuccess(isClient);
    }
}
```

This covers every current and future `IShearable` (`Sheep`, `MushroomCow`, `SnowGolem`,
`Bogged`, and — per the Fabric doc's own note about a 5th shearable added mid-version —
`CopperGolem`, all automatically, with no species enumeration needed at all, improving on the
Fabric port's own already-good "one funnel" seam) and closes the dispenser exploit
structurally: `ShearsDispenseItemBehavior#tryShearLivingEntity`
(`net/minecraft/core/dispenser/ShearsDispenseItemBehavior.java:52`) calls `IShearable#onSheared`
**directly**, with a `null` player, never going through `ShearsItem#interactLivingEntity` at all.

Plan: one new mixin, `ShearsItemInteractMixin` (`@Mixin(ShearsItem.class)`), with:
- `@Inject` at `HEAD` of `interactLivingEntity` to open the shear window (pay XP verb, roll
  Hidden Bounty, decide Bountiful Harvest for the whole shear) — same
  `husbandryOfInteractionWith`-style real-player gate as Fabric's `beginShear`, except the
  player is already a direct parameter here (no interaction-stash lookup needed for this verb —
  simpler than Fabric).
- A hook on the `drops` list (either `@ModifyExpressionValue`/`@ModifyVariable` on the local, or
  wrapping the `spawnShearedDrop` call) to double each item on a winning Bountiful Harvest roll —
  the direct Mojang-shaped equivalent of `onShearDropStack`.
- `@ModifyArg` on `stack.hurtAndBreak(1, player, ...)`'s first argument for the durability save —
  replacing `ShearableInteractMixin` outright, and no longer needing a `@Mixin({Sheep.class,
  MushroomCow.class, SnowGolem.class, Bogged.class})` species list at all.

**Implementer must verify exact injection points via `javap`/bytecode against the actual
`interactLivingEntity` method body** (this spec read the source, not the class file) before
committing to `@At` targets — in particular, confirm whether `drops` survives as a local variable
reachable by `@ModifyVariable` or whether the loop needs a `@Redirect`/`@WrapOperation` on
`target.spawnShearedDrop(...)` instead.

### 6. Hive (honeycomb, honey bottle, Beekeeper, durability) — `BeehiveBlock#useItemOn` (was `#onUseWithItem`)

Confirmed present with the same shape but several renamed/restructured pieces — **do not
copy-paste the Fabric target strings**:
- `onUseWithItem` → `useItemOn` (`net/minecraft/world/level/block/BeehiveBlock.java:122`).
- The honeycomb branch still calls `dropHoneycomb(Level, BlockPos)` — same signature, same call
  site to anchor on (line 131).
- The bottle branch no longer calls a bare `decrement(1)`; it calls `p_316844_.shrink(1)`
  (`ItemStack#shrink`, line 135) — anchor the bottle hook there instead.
- The "5-arg automated overload" Fabric warned about no longer exists as a separate overload at
  all: it is inlined as `releaseBeesAndResetHoneyLevel(level, state, pos, player,
  BeeReleaseStatus.EMERGENCY)` vs. the calm path's `resetHoneyLevel(level, state, pos)` (lines
  151–154). The **same underlying trap the Fabric doc found is still there** — both are reached
  from the one player path, gated on the same campfire check — so the design principle (hook
  `useItemOn`, not either sub-primitive) is unchanged, only the two method names are.
- `CampfireBlock.isLitCampfireInRange` → **`CampfireBlock.isSmokeyPos`, and the polarity is
  inverted**: Fabric's Beekeeper hook widened a `true` ("lit campfire in range") into "also calm
  if my sub-skill says so." 1.21.1's condition is `if (!CampfireBlock.isSmokeyPos(...))` — i.e.
  the *angry* branch is guarded by "NOT smokey" — so the `@ModifyExpressionValue` target is
  `isSmokeyPos`'s return value, and Beekeeper must widen it toward **the angry branch not
  being taken**, i.e. `return smokey || husbandry.countsAsShelteredHiveHarvest();`. Getting this
  backwards would make Beekeeper anger bees on a *sheltered* harvest and do nothing on an
  unsheltered one — the implementer must re-derive this from the real 1.21.1 branch shape shown
  above, not transcribe the Fabric expression.
- The durability save still anchors on one `ItemStack#hurtAndBreak(int, LivingEntity,
  EquipmentSlot)` call inside the shears branch (Mojang rename of `damage(...)`) — same
  `@ModifyArg` shape as Fabric.

Plan: one mixin, `BeehiveBlockUseItemOnMixin` (`@Mixin(BeehiveBlock.class)`), 4 injectors
(honeycomb, bottle, Beekeeper, durability save) — same count as Fabric's `BeehiveHarvestMixin`.

### 7. Milk — `Cow#mobInteract` / `Goat#mobInteract` / `MushroomCow#mobInteract`

**Call shape changed from Fabric's `ItemUsage.exchangeStack` to `ItemUtils.createFilledResult`.**
Confirmed: `Cow.java:92`, `Goat.java:220` both call
`ItemUtils.createFilledResult(itemstack, player, Items.MILK_BUCKET.getDefaultInstance())` — a
different helper with a different argument order than Fabric's `ItemUsage.exchangeStack`, so the
Fabric `@At(value="INVOKE", target=...)` strings cannot be transcribed; the implementer must
re-derive the exact descriptor from `ItemUtils#createFilledResult`'s real signature. `Goat` does
still re-implement the branch inline exactly as the Fabric doc found (not inherited from `Cow`),
so it needs its own `@Mixin` target, same as Fabric. `MushroomCow.mobInteract` (bowl-of-stew
branch) was not independently re-read in this pass for its exact call shape — the Fabric doc's
finding that it falls through to `super.mobInteract` for the plain-bucket path, and needs its own
mixin only for the stew branch, is structural and likely unchanged, but the implementer must
re-verify against `net/minecraft/world/entity/animal/MushroomCow.java:86` directly (found by
this spec, not read in full) before writing the target string. No milking dispenser exists in
1.21.1 either — not independently re-checked in this pass, carried over from the Fabric doc's own
jar-grep as a reasonable structural claim (bucket-family dispensers are a small, stable set) but
flagged for the implementer to re-confirm with a quick classpath grep for `MILK_BUCKET` in
`net/minecraft/core/dispenser/` before relying on it.

Plan: `CowGoatMilkMixin` (`@Mixin({Cow.class, Goat.class})`, 1 injector reused across both
targets if the `ItemUtils.createFilledResult` call is exactly one per method body, matching
Fabric's `allow=1`-per-target reasoning) + `MushroomCowStewMixin` (1 injector), same shear-verb
cooldown/`husbandryOf` reuse as Fabric.

### 8. Brush — `Armadillo#mobInteract` / `Armadillo#brushOffScute` (was `#interactMob`/`#brushScute`)

Confirmed, and simpler in 1.21.1 than the Fabric doc's own account of the seam: there is **no
`forEachBrushedItem` funnel in 1.21.1** (not present in `Armadillo.java`) — the Fabric doc's own
"where that funnel does not exist" fallback branch is the one that applies here, unconditionally.
`Armadillo#brushOffScute()` (Mojang rename of `brushScute()`,
`net/minecraft/world/entity/animal/armadillo/Armadillo.java:320`) already both (a) returns
`false` for a baby and `true` otherwise, exactly like Fabric, **and** (b) calls
`this.spawnAtLocation(new ItemStack(Items.ARMADILLO_SCUTE))` itself — the first scute's delivery
is baked into the method vanilla calls, same as Fabric. `mobInteract`
(`Armadillo.java:301`) calls `brushOffScute()` directly as an `&&`-chained condition and then
`itemstack.hurtAndBreak(16, player, ...)` — both the gate and the durability call are in the same
method, one call each, no funnel needed. Same "the dispenser calls `brushOffScute` directly,
bypassing `mobInteract`" exclusion the Fabric doc relies on — not independently re-verified for a
1.21.1 armadillo-brushing dispenser in this pass, but structurally the same shape (`mobInteract`
is the only path with a `Player` in scope) so the exclusion logic is unaffected either way.

Plan: `ArmadilloBrushMixin` (`@Mixin(Armadillo.class)`), 2 injectors — `@ModifyExpressionValue`
on the `brushOffScute()` call (pass the boolean to
`HusbandryListener#onBrushed(Entity, Entity, boolean)` unchanged, drop the bonus scute via
`armadillo.spawnAtLocation(...)` on a winning Bountiful Harvest roll, exactly as Fabric does with
`dropStack`) + `@ModifyArg` on the `hurtAndBreak` call for the durability save.

### 9. Selective Breeding — `AbstractHorse#setOffspringAttributes`/`#createOffspringAttribute` (was `#setChildAttributes`/`#calculateAttributeBaseValue`)

Confirmed, renamed but same two-part shape. `setChildAttributes(PassiveEntity, AbstractHorseEntity)`
→ **`setOffspringAttributes(AgeableMob, AbstractHorse)`** (`AbstractHorse.java:890`) — the
HEAD/RETURN stash target. The static roll,
`calculateAttributeBaseValue(double, double, double, double, Random)` →
**`createOffspringAttribute(double, double, double, double, RandomSource)`**
(`AbstractHorse.java:904`, package-private `static`, same 5-arg shape, same
`(parentA, parentB, min, max, random)` order). **Confirmed it still has three `return` statements**
(the in-range result plus the two out-of-range reflections, lines ~906–920) — the Fabric doc's
"no `allow` here, deliberately... `allow=3`" reasoning applies unchanged; do not cap it at 1.

One structural difference from Fabric worth flagging for the implementer: `setOffspringAttributes`
itself does not call `createOffspringAttribute` directly — it calls a new private instance helper,
`setOffspringAttribute(AgeableMob, AbstractHorse, Holder<Attribute>, double, double)`, three times
(once per attribute), and *that* helper calls `createOffspringAttribute`. This does not change the
mixin plan (the stash still opens/closes around `setOffspringAttributes`, the bias still applies
inside `createOffspringAttribute`'s `@ModifyReturnValue`) but the implementer should not be
surprised to find an extra layer of indirection between the two hooks that Fabric's version did
not have.

### 10. Brood — `ThrownEgg#onHit` (was `EggEntity#onCollision`)

Confirmed 1:1 in shape. `EggEntity#onCollision(HitResult)` → **`ThrownEgg#onHit(HitResult)`**
(`net/minecraft/world/entity/projectile/ThrownEgg.java:57`), with the exact same two
`this.random.nextInt(...)` calls in the exact same order — `nextInt(8) == 0` (line 60) then,
nested inside it, `nextInt(32) == 0` (line 62). Same `ordinal = 0` / `ordinal = 1`
`@ModifyExpressionValue` pair as Fabric, same "return 0 to force vanilla's own success branch"
semantics, same `ProjectileEntity#getOwner()` (unverified rename in this pass — likely unchanged,
`Entity`/`Projectile` naming is stable across this version range — implementer should confirm
`getOwner()` still exists on `Projectile`/`ThrownEgg`'s hierarchy) dispenser exclusion.

### 11. Data attachment — `BRED_BY`, real NeoForge persistence (not a stand-in)

**This plan must build real, registered `IAttachmentHolder`/`AttachmentType<UUID>` persistence,
not extend the existing in-memory stand-in.** `src/main/java/com/gmail/nossr50/neoforge/
McMMOAttachments.java` currently holds only `MOB_ORIGIN`, explicitly documented in its own
javadoc as a **non-persistent, in-memory-only stand-in** for Fabric's real NBT-backed attachment
API, and explicitly states `BRED_BY` was **not** included ("not needed by `platform/`") — so
`BRED_BY` does not exist on NeoForge in any form yet, not even as a stand-in. This plan adds it
for real.

Verified against `net.neoforged.neoforge.attachment.*` in the same sources jar:
- `AttachmentType<T>` instances are registered values in a real Minecraft registry
  (`NeoForgeRegistries.Keys.ATTACHMENT_TYPES`), the same registration shape as items/blocks —
  built with `AttachmentType.builder(...)`, most simply `AttachmentType.builder(() ->
  (UUID) null)` is not directly expressible (`builder` wants a `Supplier<T>`/`Function
  <IAttachmentHolder,T>` default-value supplier, not a nullable), so model `BRED_BY` the same
  way the sizing risk anticipated — as an *absence-checked* attachment, never read through the
  materializing `getData(type)` accessor. Use `.serialize(UUIDUtil.CODEC)` (vanilla already ships
  `UUIDUtil.CODEC`) for disk persistence.
- **The registration-timing risk is real but the failure mode is loud, not silent.**
  `AttachmentHolder#validateAttachmentType` (the internal check every `hasData`/`getData`/
  `setData`/`removeData` call goes through) throws `IllegalArgumentException` in dev
  (`!FMLLoader.isProduction()`) if the `AttachmentType` is not found in
  `NeoForgeRegistries.ATTACHMENT_TYPES` — this corrects the sizing pass's "could silently drop
  data" concern: registered too late or not at all is a boot/first-use **crash in dev builds**,
  not silent data loss. Still must be registered correctly (via a `DeferredRegister<AttachmentType
  <?>>` on the mod event bus, in `McMMOMod`'s constructor — this project has **no existing
  `DeferredRegister` anywhere**, confirmed by grep, so this is genuinely new registration
  plumbing, not a pattern to copy from elsewhere in this codebase) but the implementer will find
  out immediately if it is wrong, in a dev run, rather than discovering silent data loss weeks
  later.
- Use `hasData`/`getExistingDataOrNull`/`removeData` exclusively for `BRED_BY` — **never call
  plain `getData(type)`**, which materializes and stores the default value (and syncs it to
  clients) on first read, turning "no marker" into "a stored null-ish default," defeating the
  point of an absence check.
- `Builder#copyOnDeath()` is not needed for `BRED_BY` (a breeding-age crossing consumes and
  removes the marker before an animal could plausibly die carrying it in a way that matters —
  same reasoning Fabric's port used implicitly by never calling anything death-related for this
  marker).

**`MOB_ORIGIN`'s pre-existing non-persistence is explicitly out of scope for this plan.** It is a
separate, already-flagged gap (`McMMOAttachments.java`'s own javadoc: "flagged in the Task 3
report for a later task to pick up") belonging to Hunter's anti-farm system, not Husbandry's.
Once this plan builds real `AttachmentType` registration plumbing for `BRED_BY`, upgrading
`MOB_ORIGIN` to ride the same infrastructure becomes a small, low-risk follow-up — worth noting
to the user as an opportunity, not bundling into this plan uninvited.

## Herdsman's Call — plugging into existing super-ability wiring

`PlayerMovementTracker.java` (lines 98-99, 269-272) explicitly documents the omission: the
Fabric original called `callTheHerd(player, mmoPlayer)` from the same per-tick per-player loop
that applies Unarmored's Iron Skin, **positioned above the Agility-manager-missing guard** for
the same reason Iron Skin is — an unrelated skill's buff must not silently depend on Agility's
manager having loaded. `HusbandryManager#isHerdsmansCallActive()` (line 1053) and
`#getMaxHerdRadius()`/the radius helper at line 1064 already exist, MC-free, ready to call.

Plan: add `applyHerdsmansCall(player, mmoPlayer)` to `PlayerMovementTracker`, called from
exactly the same tick-loop position the removed Fabric call site held (above the Agility guard,
same as `applyIronSkin`), implementing whatever entity-attraction mechanic `callTheHerd` performs
on Fabric — **the implementer must read the Fabric `callTheHerd` method itself** (search
`d0764257671576525aedd97308be2f8c6d85e2fd` across `fabric/listeners/` and `fabric/` broadly; it
was not located inside `HusbandryListener.java` itself in this pass, so it likely lives in
`PlayerMovementTracker`'s own Fabric equivalent or a taming/husbandry-shared file — confirm its
exact location before starting this task) to determine the exact NeoForge seam (likely an
AABB entity sweep + attraction/movement goal override, analogous in shape to Multi-Breed's
`getEntitiesByClass` sweep in §2, but not yet verified against real source in this pass).
