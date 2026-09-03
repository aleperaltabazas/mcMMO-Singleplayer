# Cosmetic Firework Damage Fix (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop mcMMO's cosmetic level-up/ability fireworks from dealing real explosion damage to
the player (and anyone/anything within 5 blocks) on NeoForge 1.21.1.

**No separate spec doc.** This is a single, small, mechanical port — one mixin, one injector,
following an existing established pattern in this repo (`BrewingStandTickMixin`'s cancellable HEAD
injector). The tagging infrastructure it depends on already exists and is unchanged.

**The bug.** `ParticleEffectUtils.spawnFirework` (`src/main/java/com/gmail/nossr50/platform/
ParticleEffectUtils.java:252-278`) already tags every firework it spawns with
`MetadataStore.setFlag(firework, COSMETIC_FIREWORK_KEY)`, and its own javadoc says outright that
this tag exists *so that `FireworkRocketEntityMixin` can cancel the damage half of detonation* —
but that mixin was never written on this branch. `FireworkRocketEntity` is decompiled (Mojang
mappings, confirmed via the ModDevGradle `decompile_*_output.jar` cache) as:

```java
private void explode() {
    this.level().broadcastEntityEvent(this, (byte)17);  // the visual burst — client-side particles
    this.gameEvent(GameEvent.EXPLODE, this.getOwner());
    this.dealExplosionDamage();                          // the ONLY damage-dealing call
    this.discard();
}

private void dealExplosionDamage() {
    float f = 0.0F;
    List<FireworkExplosion> list = this.getExplosions();
    if (!list.isEmpty()) {
        f = 5.0F + (float)(list.size() * 2);
    }
    if (f > 0.0F) {
        // hurts this.attachedToEntity (never set here — see below) and every LivingEntity
        // within 5 blocks with line-of-sight, via this.damageSources().fireworks(...)
        ...
    }
}
```

Both methods are `private`; Sponge Mixin injects into private methods at the bytecode level with
no reflection involved, so this is not a landmine. `dealExplosionDamage` is the entire damage
surface — the visual burst (`broadcastEntityEvent`, byte `17`) and the `gameEvent`/`discard()`
calls are unrelated to damage and must stay untouched, exactly as `ParticleEffectUtils`'s own
javadoc already promises ("The visual is unaffected... which is pure damage").

`spawnFirework` never sets a shooter/owner (`new FireworkRocketEntity(world, rocket, pos.x, pos.y,
pos.z, true)` — the 5-arg constructor that leaves `owner` null), and never attaches it to an
entity (`attachedToEntity` stays null), so the `this.attachedToEntity != null` branch inside
`dealExplosionDamage` can never fire for an mcMMO firework — only the "every nearby `LivingEntity`"
loop applies, which is exactly the loop that currently hits the celebrating player.

**No NeoForge event exists for this.** There is no `FireworkExplodeEvent` or equivalent on the
event bus for firework detonation — confirmed by reading `FireworkRocketEntity`'s decompiled
source in full (no `NeoForge.EVENT_BUS.post(...)` call anywhere in it) and by checking this
project's own prior audits, which never found one. A mixin is the only hook point.

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), JUnit 5. Sponge Mixin, one new
`@Mixin(FireworkRocketEntity.class)` — a concrete class, not an interface, so the boot-crash rule
(interface with a concrete member breaking target-type inference) does not apply here.

## Global Constraints

- Cancel damage, never the visual burst. The injector targets `dealExplosionDamage` specifically,
  not `explode()` — cancelling `explode()` itself would also suppress the client particle burst
  and the `gameEvent`/`discard()` calls, none of which are the bug.
- The tag check reads `MetadataStore.has((Entity) (Object) this, ParticleEffectUtils
  .COSMETIC_FIREWORK_KEY)` — follow `LivingEntityEatMixin`/`LivingEntityDamageMixin`'s existing
  `(TargetType) (Object) this` cast convention for referencing the mixin's own target instance.
- No `MetadataStore` cleanup call is needed in this mixin. Existing per-entity `MetadataStore`
  usage in this repo (e.g. Archery's arrow marks, per `Archery.java`'s own comment) is left
  un-cleared until server stop (`MetadataStore.clearAll()` in `McMMOMod`) — the firework entry
  follows the same established, accepted pattern; do not add a new cleanup call as part of this
  task.
- `allow = 1` on the injector — `dealExplosionDamage` is declared exactly once on
  `FireworkRocketEntity`.

---

### Task A: `FireworkRocketEntityMixin`

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/FireworkRocketEntityMixin.java`
- Modify: `src/main/resources/mcmmo.mixins.json` (add `"FireworkRocketEntityMixin"` to the
  `mixins` array, alphabetically — it sorts between `"FishingHookWaitTimeMixin"` and
  `"FoodDataExhaustionMixin"`)
- Test: `src/test/java/com/gmail/nossr50/neoforge/mixin/FireworkRocketEntityMixinTest.java`

**Interfaces:**
- Consumes (existing, unchanged): `com.gmail.nossr50.platform.MetadataStore#has(Entity, String)`,
  `com.gmail.nossr50.platform.ParticleEffectUtils#COSMETIC_FIREWORK_KEY` (already `public static
  final`, no visibility change needed).
- Produces: nothing new — this is a pure interception, no new public API.

- [ ] **Step 1: Verify the real signature before writing the injector**

Run (adjust the merged-jar path if it differs from prior plans' verified path):

```bash
javap -p -classpath build/moddev/artifacts/neoforge-21.1.248-merged.jar \
  net.minecraft.world.entity.projectile.FireworkRocketEntity | grep -i explosionDamage
```

Confirm `dealExplosionDamage()` is `private void dealExplosionDamage()` with **no parameters** —
this plan's Task A body assumes that shape from the decompiled source above; re-derive from
`javap` if the merged jar disagrees (decompiled parameter names are not authoritative, but the
descriptor `()V` is).

- [ ] **Step 2: Write `FireworkRocketEntityMixin.java`**

```java
package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.ParticleEffectUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the blast-damage half of a firework's detonation when
 * {@link ParticleEffectUtils#spawnFirework} tagged it as cosmetic
 * ({@link ParticleEffectUtils#COSMETIC_FIREWORK_KEY}). Without this, every level-up/ability
 * firework mcMMO spawns deals its full {@code 5 + 2 × explosions} damage to the celebrating
 * player and anyone within 5 blocks — {@code spawnFirework}'s own javadoc already documents this
 * mixin as the other half of that design; it was simply never ported.
 *
 * <p>Targets {@code dealExplosionDamage} specifically, not {@code explode()} — {@code explode()}
 * also broadcasts the client-side visual burst (entity status {@code 17}) and fires
 * {@code gameEvent}/{@code discard()}, none of which are the bug and all of which must keep
 * running unchanged for a cosmetic firework.
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {

    @Inject(method = "dealExplosionDamage", allow = 1, at = @At("HEAD"), cancellable = true)
    private void mcmmo$cancelCosmeticFireworkDamage(CallbackInfo ci) {
        if (MetadataStore.has((Entity) (Object) this, ParticleEffectUtils.COSMETIC_FIREWORK_KEY)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 3: Register the mixin**

Add `"FireworkRocketEntityMixin"` to `src/main/resources/mcmmo.mixins.json`'s `mixins` array, in
alphabetical order.

- [ ] **Step 4: Write a structural test**

Follow this repo's established ASM-based mixin-wiring test pattern (see e.g.
`LivingEntityGlideMixinTest`/`AbstractHorseChildAttributesMixinTest` for the shape) — do not rely
on a purely reflective assertion that only checks a method *name* exists, per this project's own
prior lesson (`AbstractHorseChildAttributesMixin`'s real bug slipped past a reflective-only test).
At minimum:

- Confirm `FireworkRocketEntityMixin` declares exactly one `@Inject` targeting
  `dealExplosionDamage`, `cancellable = true`, `allow = 1`.
- A behavioral unit test (no mixin weaving required) directly exercising
  `mcmmo$cancelCosmeticFireworkDamage`'s logic is not possible since it's a private instance
  method on a mixin class merged into `FireworkRocketEntity` at weave time — instead, cover the
  *decision* logic by asserting `MetadataStore.has(entity, ParticleEffectUtils
  .COSMETIC_FIREWORK_KEY)` correctly reflects `ParticleEffectUtils.spawnFirework`'s tagging (a
  `PlatformPlayer`-driven integration test spawning a real `FireworkRocketEntity` via
  `spawnFirework`'s reflection-free path, or a narrower test asserting the tag is present
  immediately after `spawnFirework` runs, mirroring how `LevelUpEffectTest`-style tests in this
  repo already assert `isMilestoneLevel`/config-gating without a live server).

- [ ] **Step 5: Verify mixin application against a real boot**

```bash
timeout 150 ./gradlew runServer --console=plain 2>&1 \
  | tee /tmp/runserver-firework-taskA.log \
  | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("
```

Expected: clean `Done (...)!`  with no `InvalidMixinException`/`FATAL`/`MixinApplyError` lines. Also
run `python scripts/mixin-allow-audit.py --check` if present on this branch (confirmed present in
prior plans' verification steps) to confirm the new injector's `allow = 1` genuinely resolves to
exactly one site, not zero.

- [ ] **Step 6: Run the full suite**

```bash
./gradlew test
```

Expected: PASS, total test count higher than the pre-task baseline.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/gmail/nossr50/neoforge/mixin/FireworkRocketEntityMixin.java \
        src/main/resources/mcmmo.mixins.json \
        src/test/java/com/gmail/nossr50/neoforge/mixin/FireworkRocketEntityMixinTest.java
git commit -m "fix(neoforge): cancel cosmetic firework blast damage on level-up/ability fireworks"
```

---

## Manual In-Game Verification (after the task lands)

1. Set `Particles.LevelUp_Enabled: true` (default) and grind a skill to a milestone level (or
   lower `Particles.LevelUp_Tier` temporarily for a fast repro). Confirm the firework bursts
   visually with no damage taken and no nearby mob/villager hurt.
2. Enable `Particles.Ability_Activation`/`Ability_Deactivation` and trigger/expire a super ability.
   Confirm the same: visual burst, zero damage.
3. As a control, place and detonate an ordinary player-crafted firework rocket (not one mcMMO
   spawned) near yourself. Confirm it still deals its normal vanilla blast damage — this mixin
   must only suppress damage for mcMMO-tagged fireworks, never vanilla ones.
