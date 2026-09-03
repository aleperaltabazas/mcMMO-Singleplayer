# Cosmetic fireworks dealing real damage — implementation plan

## Why this plan exists

The 2026-09-03 feature-completeness audit's top finding: `ParticleEffectUtils.spawnFirework`
(`src/main/java/com/gmail/nossr50/platform/ParticleEffectUtils.java:254-279`) explicitly tags every
ability-activation/deactivation and level-up firework with `MetadataStore.setFlag(firework,
COSMETIC_FIREWORK_KEY)`, and its own javadoc says outright: *"`FireworkRocketEntityMixin` cancels
the damage half of the detonation for rockets this method spawned."* That mixin was never written.
With `config.yml`'s defaults (`Particles.LevelUp_Enabled: true`), every player hits this at their
first 100-level milestone in any skill: `5 + 2×explosions` damage to themselves and every
`LivingEntity` within 5 blocks (a pet, a villager, anything nearby).

No spec doc needed — the fix is a single small mixin plus a `MetadataStore` cleanup call, both
already fully specified by `ParticleEffectUtils`'s existing javadoc.

## Verified real hook (via `javap` against `build/moddev/artifacts/neoforge-21.1.248-merged.jar`)

`net.minecraft.world.entity.projectile.FireworkRocketEntity` has, at 1.21.1:
- `private void explode()` — fires the client-visible burst (entity status `17`) and then calls
  `dealExplosionDamage()`.
- `private void dealExplosionDamage()` — **a separate private method**, exists specifically for
  this. Mojang already split the damage logic out from the visual, which is cleaner than what the
  existing javadoc assumed (it describes needing to cancel "the damage half of the detonation"
  inside `explode` itself — the real target is simpler: this dedicated method).

## Task A — `FireworkRocketEntityMixin`

1. New mixin targeting `dealExplosionDamage()`. `@Inject(method = "dealExplosionDamage", at =
   @At("HEAD"), cancellable = true)`. Handler: if `MetadataStore.has(this, COSMETIC_FIREWORK_KEY)`
   (or the typed `hasFlag`-style accessor — check whether to add one to `MetadataStore` or just use
   `has(entity, key)`, which already exists), call `ci.cancel()`.
   - Both `explode()` and `dealExplosionDamage()` are **private**, so the mixin needs `@Shadow` or a
     private-method injector the way this codebase's other private-target mixins already do (check
     an existing example, e.g. `AbstractFurnaceGetBurnDurationMixin` or similar private-target
     mixins from the Cooking+Smelting plan, for the established pattern in this codebase).
   - Verify via `javap -c` that `dealExplosionDamage()` has exactly one entry point (it should, as a
     small private helper) before asserting an `allow` count — don't assume.
2. Add `MetadataStore.clear(entity)` — or specifically `MetadataStore.remove(entity,
   COSMETIC_FIREWORK_KEY)` — to whatever already-existing entity-removal/cleanup path this codebase
   uses (check if `MetadataStore.clear` is already called from a generic entity-death/removal
   listener; the class's own javadoc says data should be cleared "when the entity is removed" — a
   firework rocket removes itself after exploding, so confirm this doesn't already leak, and if no
   generic cleanup exists for short-lived entities like this, add a call at the mixin's injection
   site or via a `Discard`/remove-event hook).
3. Bytecode-verified `allow` count on the injector; a structural test confirming the mixin targets
   the right method with the right cancellable shape (reference pattern: this session's other
   `@Inject(cancellable = true)` mixins, e.g. anything in `EntityDamageListener`'s mixin set).
4. Behavioral test: spawn two fireworks (one flagged cosmetic, one not) in a test harness or via
   direct method invocation, confirm `dealExplosionDamage()` is cancelled for the flagged one and
   runs normally for the other. If a full integration test isn't feasible without a running world,
   at minimum a reflective/ASM test proving the injector's cancellation condition reads the right
   `MetadataStore` key.
5. Mandatory: `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-firework.log
   | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("`.
6. **Real in-game verification recommended before calling this done**: trigger a level-up firework
   (or temporarily lower `Particles.LevelUp_Tier` to 1 for a quick test) and confirm no damage is
   taken, since this is exactly the kind of "compiles and binds but doesn't actually behave"
   bug class this session has hit before (e.g. `AbstractHorseChildAttributesMixin`'s wrong-local
   bug, `EntityTypeSpawnOriginMixin`'s dead-binding trap in the mob-origin plan).

## Task B — Final review

Small enough to fold into Task A's own review per this codebase's established practice for
single-task plans (see Agility's plan, which did the same). Standard adjudication if anything
residual turns up: park with a ruling, or fix if load-bearing. Skip
`finishing-a-development-branch` (branch stays open). Delete the SDD workspace.

## Complexity/risk summary

Low. One mixin, one already-fully-specified behavior, a real Mojang method (`dealExplosionDamage`)
that conveniently already separates the damage logic from the visual — simpler than the existing
javadoc even anticipated. The only real risk is the private-method injection mechanics (verify this
codebase's established pattern for private mixin targets) and remembering the in-game smoke test,
since a cancelled-but-untested injector is indistinguishable from a correct one until someone
actually gets hit.
