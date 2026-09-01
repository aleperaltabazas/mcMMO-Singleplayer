# Second Wind, Smoke Bomb & Food Listener (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close three remaining gaps left over from the NeoForge port: Agility's super ability
**Second Wind** and Stealth's super ability **Smoke Bomb** — both fully unwired on NeoForge, the same
missing-activation-trigger bug class Herdsman's Call had (see commit `06d4b7d34`) — plus the wholly
unported **Food** seam (Farmer's Diet, Fisherman's Diet, Power Cook).

**Confirmed, not assumed.** `HerdsmansCallListener`'s own javadoc states outright: "Neither Second
Wind nor Smoke Bomb has been wired to a NeoForge listener yet (Fabric-only so far)." Independently
confirmed here: `grep -rln "SECOND_WIND\|SmokeBomb\|SMOKE_BOMB"
src/main/java/com/gmail/nossr50/neoforge/` returns nothing, and `grep -rl
"FarmersDiet\|FishermansDiet\|PowerCook" src/main/java/com/gmail/nossr50/neoforge/` also returns
nothing.

**No spec for Task A.** Second Wind and Smoke Bomb are self-contained hand-rolled activation
listeners — same shape as `HerdsmansCallListener`, no new design surface, no shared state with
anything else. **Task B has a spec** (`docs/superpowers/specs/2026-09-01-food-listener-design.md`):
Food is a mixin-driven cross-skill seam with a documented ordering trap, worth writing down before
touching code.

**Sizing note — Task A is bigger than Herdsman's Call was.** Herdsman's Call's effect logic already
lived in `HusbandryManager`/`HusbandryListener`/`PlayerMovementTracker`, so its NeoForge listener only
had to own the *activation trigger*. Second Wind and Smoke Bomb are different: Fabric's
`SecondWindListener`/`SmokeBombListener` are the *entire* ability, trigger and effect both — Second
Wind's `dart`/`aquaman`/`limitless` bodies and Smoke Bomb's invisibility application live inside
`activate()`, not behind a `getAbilityMode()` read elsewhere. Porting these means porting the full
listener class, not just a trigger check.

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), JUnit 5 + Mockito. Task A: no Sponge
Mixin (same `PlayerInteractEvent.RightClickItem` seam as `HerdsmansCallListener`, already proven safe
and unambiguous). Task B: one Sponge Mixin injector on a concrete class (`LivingEntity`) — safe under
the target-type-inference boot-crash rule (no interface, no unrelated concrete member).

## Global Constraints

- Second Wind's trigger item (`Second_Wind_Item`, default `FEATHER`) and Smoke Bomb's
  (`Smoke_Bomb_Item`, default `GUNPOWDER`) must each be read from `McMMOMod.getGeneralConfig()` with
  the same hardcoded fallback pattern `HerdsmansCallListener.isHoldingTriggerItem` uses for
  `GOAT_HORN` — verify `GeneralConfig` actually exposes `getSecondWindItem()`/`getSmokeBombItem()` (it
  should, config plumbing is skill-agnostic and predates this port) before assuming the fallback is
  needed.
- All three trigger items (`GOAT_HORN`, `FEATHER`, `GUNPOWDER`) must stay distinct — this is already
  true in Fabric's config defaults and `HerdsmansCallListener`'s javadoc calls out why: a shared item
  fires whichever gate passes first and prints the others' refusal message alongside it. Do not
  change any default.
- Neither listener may cancel the event or consume the trigger item — both are pure observers of the
  click, exactly like `HerdsmansCallListener`.
- A refused activation (not moving for Second Wind, medium's rank not unlocked, abilities toggled
  off) must **not** burn the cooldown — every early return in the Fabric originals sits above the
  `calculateTimeRemaining`/`setAbilityDATS` stamp; preserve that order exactly.
- `Player#addStatusEffect` renames to `LivingEntity#addEffect` and `StatusEffectInstance` renames to
  `MobEffectInstance` under Mojang mappings (already used this way elsewhere in this repo, e.g.
  Cooking's Power Cook — see Task B) — verify exact constructor arg order via `javap` rather than
  assuming Fabric's yarn-named constructor lines up 1:1.
- `EntityVelocityUpdateS2CPacket`/`player.velocityDirty`/`networkHandler.sendPacket` (Second Wind's
  `setVelocity` helper, needed because a player never receives their own velocity update through the
  normal entity-tracker broadcast) has a Mojang-mapped equivalent — confirm the exact class name
  (`ClientboundSetEntityMotionPacket` is the likely NeoForge/Mojang name) and field/method names via
  `javap` before implementing; do not guess.
- Food's `nutrition <= 0` guard must stay above every other check in `onFoodConsumed` (see spec).
  The diet chain and the Power Cook call must stay two separately-invoked methods, never merged into
  one conditional chain (see spec's "Ordering trap").

---

### Task A: Second Wind & Smoke Bomb activation listeners

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/SecondWindListener.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/SmokeBombListener.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (register both, add to any
  shutdown/clear path if one exists for this listener family — check whether
  `HerdsmansCallListener` needed one; if it didn't, these likely don't either, since neither carries
  any static/ThreadLocal state)
- Reference: `src/main/java/com/gmail/nossr50/neoforge/listeners/HerdsmansCallListener.java` (the
  trigger-gate pattern), `src/main/java/com/gmail/nossr50/neoforge/listeners/SuperAbilityListener.java`
  (existing `RightClickItem`/hand/side conventions)
- Full Fabric source for both classes is reproduced in this plan's sizing investigation; also
  recoverable via `git show <the commit before ef5fd3d1a>:src/main/java/com/gmail/nossr50/fabric/listeners/SecondWindListener.java`
  and the `SmokeBomb` sibling.

**Steps:**

- [ ] Port `SecondWindListener`: `register()` via `NeoForge.EVENT_BUS.addListener`, an `onUseItem`
  handler on `PlayerInteractEvent.RightClickItem` (main hand only, server-side only — same gates as
  `HerdsmansCallListener.onUseItem`), holding-trigger-item check via `Materials.item(...)` +
  `stack.is(...)` (same pattern as `HerdsmansCallListener.isHoldingTriggerItem`, not
  `player.isHoldingItem(String)` — that Fabric method doesn't exist here), then `tryActivate` /
  `activate` reproducing the Fabric original's gates and ordering exactly: already-active check,
  abilities-off check, `PlayerMovementTracker.classifyMedium` (already ported, confirmed present at
  `src/main/java/com/gmail/nossr50/neoforge/listeners/PlayerMovementTracker.java:294` and exposed —
  verify it's `public`/package-visible enough to call from here, widen visibility if needed and note
  why in a one-line comment), cooldown check, `agility.computeSecondWind(medium, ticks)` null check
  (rank not unlocked). On a clean pass: notify, sound, `setAbilityDATS`, `setAbilityMode(true)`,
  schedule `AbilityDisableTask`, then dispatch to `dart`/`aquaman`/`limitless` by `result.medium()`.
- [ ] Port the three effect bodies (`dart`, `aquaman`, `limitless`) and the `setVelocity` helper,
  translating to Mojang-mapped types: `ServerPlayer`, `Vec3`, `AABB` (not `Box`), `ServerLevel`,
  `EntityType`/`EntitySelector`-equivalent for the entity sweep (`ServerLevel#getEntities` with an
  `EntityTypeTest`/predicate — check the exact overload used elsewhere in this repo, e.g.
  `PlayerMovementTracker`'s Herdsman's Call sweep from the Husbandry plan, for the established
  pattern), `LivingEntity#hurt`/`DamageSource` (not `damage`/`getDamageSources().playerAttack`; verify
  exact Mojang names via `javap`), `LivingEntity#knockback` (not `takeKnockback`; verify signature —
  NeoForge/Mojang's is `(double strength, double x, double z)`, confirm sign convention matches
  Fabric's `-look.x, -look.z` intent), `MobEffectInstance`/`MobEffects.STRENGTH`/`REGENERATION`/
  `NIGHT_VISION`/`DOLPHINS_GRACE` (verify exact field names), and the velocity-sync packet (see
  Global Constraints).
- [ ] Port `SmokeBombListener`: same trigger-gate shape as Second Wind but Stealth-specific —
  `mmoPlayer.getStealthManager()` null/`canSmokeBomb()` check, `durationTicks` (the two-knob
  max-of-scaled-and-floor calculation, unchanged logic), `activate` applying `MobEffects.INVISIBILITY`
  with the exact `(effect, duration, amplifier, ambient=false, showParticles=false, showIcon=true)`
  argument shape — verify the `MobEffectInstance` constructor overload with all six params exists
  under Mojang mappings and preserves argument order (ambient/particles/icon booleans are easy to
  transpose silently).
- [ ] Wire both `register()` calls into `McMMOMod.java`, alongside the existing
  `HerdsmansCallListener.register()` call.
- [ ] Unit tests for both listeners' gate logic (already-active / abilities-off / rank-locked /
  on-cooldown / clean-activation), following `HerdsmansCallListenerTest.java`'s structure (package-
  private `onUseItem` driven directly, not just the private helpers) — 9-ish cases per listener,
  matching Herdsman's Call's test count as a rough size signal.
- [ ] Run `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-supertrigger-a.log | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("` and confirm a clean `Done (`
  with no mixin errors — even though this task adds no mixin, the full plan must still boot clean
  after every task, and this task shares `McMMOMod.java` with the plan's mixin-bearing task.

---

### Task B: Food listener (Farmer's Diet, Fisherman's Diet, Power Cook)

**Spec:** `docs/superpowers/specs/2026-09-01-food-listener-design.md` — read in full before starting.

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/FoodListener.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/LivingEntityEatMixin.java`
- Modify: `src/main/resources/mcmmo.mixins.json` (register the new mixin, alphabetically placed)
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` if `FoodListener` needs any
  registration call (likely none — it has no `register()`, since it's driven purely by the mixin,
  same shape as `SmeltingListener`'s owner-map-only classes with no event-bus registration of their
  own; verify against the Fabric original, which also has no `register()` method)
- Reference: `src/main/java/com/gmail/nossr50/neoforge/listeners/CookingListener.java` (existing
  `Potions.matchEffect`-adjacent status-effect application pattern, if any overlap exists — check
  before assuming, Power Cook may be the first user of `Potions.matchEffect` in `neoforge/`)

**Steps:**

- [ ] Before writing the mixin: `javap -c` (not just `-p`) on `Player.class` (both the extracted jar
  path used in the spec's research and, if available, a fresher decompile) to confirm the override of
  `eat(Level, ItemStack, FoodProperties)` applies hunger *before* calling `super` — this is the
  load-bearing assumption behind using `TAIL`. If the order differs from Fabric's, adjust the
  injection point and document why in the mixin's javadoc, same rigor as the Fabric original's
  bytecode-verified claim.
- [ ] Confirm `FoodData`'s exact method names (`getFoodLevel`/`setFoodLevel`/`getSaturationLevel`/
  `setSaturationLevel` are the likely Mojang names; confirm via `javap -p` on `FoodData.class`) and
  the max-food-level constant (likely just the literal `20`, since `HungerConstants` may not have a
  Mojang-mapped equivalent — check before hardcoding).
- [ ] Confirm `GeneralConfig`/registry lookups: `Registries.ITEM` → `BuiltInRegistries.ITEM` (Mojang
  naming), `RegistryEntry<StatusEffect>` → `Holder<MobEffect>`, `StatusEffect` → `MobEffect`,
  `StatusEffectInstance` → `MobEffectInstance` — verify `Potions.matchEffect`'s actual return type in
  this repo's `platform` package rather than assuming it matches the Fabric original's yarn name.
- [ ] Port `FoodListener.onFoodConsumed`, `applyDietBonus`, `applyPowerCook`, `applyBonus` verbatim in
  structure (see spec's "Ordering trap" — do not merge the diet chain and Power Cook call), renaming
  types only. Keep the `UNRESOLVED_EFFECTS` warn-once-per-name guard.
- [ ] Write `LivingEntityEatMixin`: `@Mixin(LivingEntity.class)`, `@Inject` on
  `eat(Level, ItemStack, FoodProperties)` at `TAIL`, `allow = 1`, forwarding to
  `FoodListener.onFoodConsumed(level, (LivingEntity) this, stack, food)`. Concrete class target, no
  interface — confirm no other member on this exact mixin class introduces a boot-crash risk (there
  shouldn't be one; this is a single-injector accessor-free mixin, the safest possible shape).
- [ ] Unit tests: diet mutual-exclusion (a Farmer's Diet food does not also get Fisherman's Diet
  treatment and vice versa), Power Cook firing on both diet and non-diet foods (the 17-item overlap
  case is the one regression this seam exists to prevent — write an explicit test for at least one
  food in that overlap, e.g. `cooked_cod` or `bread`), the `nutrition <= 0` early return, and the
  unresolved-effect-name warn-once behavior (mirroring the Fabric test file recoverable at
  `git show ef5fd3d1a~1:src/test/java/com/gmail/nossr50/fabric/listeners/FoodListenerTest.java` for
  case coverage — do not copy Fabric-specific mocks, but do match its scenario list).
  Consider one cross-skill regression test in the same spirit as
  `SmeltingCookingMutualExclusionTest.java` (Cooking+Smelting plan) if it's cheap: eat a food in the
  17-item overlap with both a diet rank and a Power Cook mapping active, assert both fire.
- [ ] Run `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-supertrigger-b.log | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("` and confirm a clean `Done (`
  with no mixin errors.

---

## Final Review

Whole-branch review per the standard SDD process: one final review pass (opus), one fix wave if
findings warrant it, one scoped re-review, then controller adjudication of any residuals — no second
fix wave. Pay particular attention to:
- Second Wind/Smoke Bomb: are all three trigger items still mutually distinct after both tasks land?
- Food: does the 17-item diet/Power Cook overlap actually get exercised by a real test, not just
  each tenant tested in isolation?
- Cooldown-not-burned-on-refusal: re-verify for both Second Wind and Smoke Bomb, since this exact
  class of ordering bug (a check moved below the cooldown stamp) has bitten this port before.
