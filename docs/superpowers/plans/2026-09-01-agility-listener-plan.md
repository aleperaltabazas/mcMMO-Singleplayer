# Agility Listener (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the two remaining Fabric Agility listeners — `AthleteListener` (sprint exhaustion
discount) and `GlideListener` (Fleet Footed air / Glide velocity boost) — to NeoForge 1.21.1. This
is the last unported piece of the Agility skill: Roll, Graceful Roll, and Dodge already live in
`neoforge/listeners/EntityDamageListener.java` (ported as part of the entity-damage-listener plan),
and every `MovementManager` method both new hooks need (`getAthleteExhaustionMultiplier`,
`getFleetFootedBonus(Medium)`, `getGlideDescentReduction`) already exists unmodified on this branch.

**Architecture:** Two independent, single-purpose mixins, each a one-line seam delegating into a
small static listener class — the same shape as `HungerManagerExhaustionMixin`/`AthleteListener`
and `LivingEntityGlideMixin`/`GlideListener` on Fabric. No new infrastructure, no ThreadLocal
bridges, no config/registration changes. This is a mechanical port; **no spec was written** — the
Fabric source is short, self-documented (both files carry detailed javadoc explaining the seam
choice), and the NeoForge target classes/methods use the same Mojang-mapped names Fabric's own
javadoc already discusses in Yarn terms with a well-known Mojang mapping (`HungerManager` →
`FoodData`, its owning entity's exhaustion method stays `addExhaustion(float)`; `LivingEntity` and
`travel`/`isFallFlying`/`getDeltaMovement`/`setDeltaMovement` are already Mojang names, unchanged
between the two mapping sets).

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), Sponge Mixin, JUnit 5 + Mockito.

**Source:** the Fabric original, recoverable at commit `d0764257671576525aedd97308be2f8c6d85e2fd`:
- `src/main/java/com/gmail/nossr50/fabric/listeners/AthleteListener.java`
- `src/main/java/com/gmail/nossr50/fabric/listeners/GlideListener.java`
- `src/main/java/com/gmail/nossr50/fabric/mixin/HungerManagerExhaustionMixin.java`
- `src/main/java/com/gmail/nossr50/fabric/mixin/LivingEntityGlideMixin.java`

Pull each with `git show d0764257671576525aedd97308be2f8c6d85e2fd:<path>`.

## Global Constraints

- Every `MovementManager` call both listeners make already exists on this branch with the same
  signature — do not modify `MovementManager`, do not re-derive skill math, just call them.
- `AthleteListener` resolves its owning player by identity scan over
  `McMMOMod.getServer().getPlayerManager()`'s NeoForge equivalent (`getServer().getPlayerList()
  .getPlayers()`) — confirm the exact accessor name/shape on this codebase's `McMMOMod` (it already
  exposes a static `getServer()`; check for an existing helper before writing a new scan, since
  other listeners on this branch may already resolve `ServerPlayer` by UUID or by owner-scan and
  that pattern should be reused for consistency rather than re-invented).
- `GlideListener.modifyGlideVelocity` runs on **both logical sides** in Fabric (client + server, no
  packet needed because both simulate flight identically). NeoForge's mixin sourceSet in this repo
  only ships a server/common jar in this branch's current test setup — confirm via
  `mcmmo.mixins.json`'s existing entries whether any prior mixin in this codebase already
  distinguishes client vs. common/server injection (grep the `mixins.json` structure and any
  existing `"client"` array) before assuming this one needs special handling. If this codebase's
  mixin config only ever applies to the common/server side (likely, given it's a singleplayer-only
  mod per `AthleteListener`'s own javadoc — "In singleplayer that is a one-element scan"), a
  single-side injector matches every other mixin on this branch and is correct; note the deviation
  from Fabric's dual-side seam explicitly in the new mixin's javadoc rather than silently dropping
  it.
- `FoodData#addExhaustion` signature and `LivingEntity#travel`'s single-return-point property must
  be independently re-verified against the real NeoForge/Mojang-mapped sources (`javap` against the
  resolved Minecraft jar, or the NeoForge sources jar if available in the Gradle cache) before
  writing the `@At`/`@ModifyVariable` target — do not transcribe the Fabric/Yarn method descriptor
  verbatim. If no sources jar is available offline, decompile-check via the merged jar in
  `~/.gradle/caches` is the fallback used elsewhere on this branch.
- Verify mixin application for real before calling the task done, the same way every prior plan on
  this branch has: `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee
  /tmp/runserver-agility.log | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("`
  — plain JUnit never applies mixins.

---

### Task A: Athlete + Glide

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/AgilityListener.java` (both
  `scaleExhaustion` and `modifyGlideVelocity`, ported from `AthleteListener`/`GlideListener` — merge
  into one file since both are small, stateless, single-purpose static helpers for the same skill;
  do not split into two files unless a real reason emerges during implementation)
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/FoodDataExhaustionMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/LivingEntityGlideMixin.java`
- Modify: `src/main/resources/mcmmo.mixins.json` (add both, alphabetically placed)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/AgilityListenerTest.java` (behavioral —
  exhaustion scaling math, glide velocity math, the "nothing unlocked → return same reference"
  fast path, the "only dampen downward motion" asymmetry)
- Test: `src/test/java/com/gmail/nossr50/neoforge/mixin/FoodDataExhaustionMixinTest.java` and
  `LivingEntityGlideMixinTest.java` (structural/reflection or ASM-based, same pattern as
  `AbstractHorseChildAttributesMixinTest.java` — confirm the injector targets the real method and,
  for the glide mixin, that `travel` genuinely has one return point before trusting `TAIL`)

**Interfaces:**
```java
// AgilityListener
public static float scaleExhaustion(FoodData foodData, float exhaustion)
public static Vec3 modifyGlideVelocity(LivingEntity entity, Vec3 glideVelocity)

// FoodDataExhaustionMixin (@Mixin(FoodData.class))
@ModifyVariable(method = "addExhaustion", at = @At("HEAD"), argsOnly = true)
private float mcmmo$applyAthlete(float exhaustion)

// LivingEntityGlideMixin (@Mixin(LivingEntity.class))
@Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
private void mcmmo$applyGlideBonus(Vec3 travelVector, CallbackInfo ci)
```

**Acceptance Criteria:**
- [ ] `FoodData#addExhaustion`'s real signature confirmed (`javap`/sources jar), not transcribed
      from Fabric/Yarn.
- [ ] `LivingEntity#travel`'s single-return-point property independently re-verified on this
      Mojang-mapped version before trusting `@At("TAIL")` (same bytecode-verification discipline
      Fabric's own javadoc used).
- [ ] Exhaustion discount only applies while `player.isSprinting()`, matching Fabric's gate exactly
      — mining/jumping/swimming/damage/regen exhaustion must be unaffected.
- [ ] Glide bonus gates on `isFallFlying()`, scales horizontal (`x`/`z`) by `1 + fleetFootedBonus`,
      dampens only *downward* `y` motion by `1 - descentReduction` (never boosts upward motion).
- [ ] The "both logical sides" question from Global Constraints is resolved and documented in the
      new mixin's javadoc, not silently dropped.
- [ ] `mcmmo.mixins.json` updated; both mixins present and alphabetically placed.
- [ ] Neither mixin is a `@Mixin`-annotated interface with concrete members (boot-crash rule — not
      expected to apply here since both are plain classes, but confirm).
- [ ] `./gradlew runServer` verification run per Global Constraints — clean of mixin errors.
- [ ] `./gradlew test` green.

---

## Final Review

After Task A lands, run this plan's final whole-branch review the same way every prior plan on
this branch has (`superpowers:subagent-driven-development`'s final-review step) — one fix wave, one
scoped re-review, residuals adjudicated by the controller. Given this plan is a single task, the
final review can reasonably fold into Task A's own task-review if the controller judges the scope
too small to warrant a separate pass — note that judgment call in the ledger either way.
