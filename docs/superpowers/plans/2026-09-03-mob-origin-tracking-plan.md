# Mob-origin tracking (Hunter D-HU1 anti-farm gate) — implementation plan

## Why this plan exists

The 2026-09-03 feature-completeness audit found Hunter's anti-farm gate is fully dead code on
NeoForge: `MobOrigins.stampOnSpawn`/`carryThroughConversion` are never called from anywhere in
`src/main` (confirmed via grep). Every mob currently reads as `MobOrigin.NATURAL`, so spawner
farms, breeder farms, `/summon`-command mobs, and portal-spawned zombified piglins all silently
count toward Hunter mastery XP.

**No spec doc is needed.** `platform/MobOrigins.java` already IS the spec — its class javadoc is
an unusually thorough, already-Mojang-mapped design document: it names the exact trap (a spawn
funnel that looks like it covers spawner/breeding but doesn't, binding green while leaving those
paths unmarked), the exact invariant (never overwrite a qualifying origin — some spawn reasons
re-introduce an already-marked mob), and the exact classify() mapping. `McMMOAttachments` already
has `getMobOrigin`/`setMobOrigin` (a `ConcurrentHashMap<UUID,String>` stand-in, deliberately not a
real attachment yet — see that field's own javadoc for why). **This plan is pure wiring**: write
the mixins/listener that call the methods that already exist, and verify each one is reached by a
real gameplay path, not just that it compiles.

## The one thing this plan corrects in `MobOrigins.java`'s own javadoc

The javadoc's "no single funnel" trap analysis was written against the *general* problem (true on
Fabric, and true here for the *egg/dispenser/portal* path). But NeoForge already gives us
something Fabric doesn't for the **spawner** path specifically: `BaseSpawner#serverTick` and
`TrialSpawner#spawnMob` both call `EventHooks.finalizeMobSpawnSpawner(Mob, ServerLevelAccessor,
DifficultyInstance, MobSpawnType, SpawnGroupData, IOwnedSpawner, boolean)`, which fires a
`FinalizeSpawnEvent` (extends `MobSpawnEvent`) carrying `getSpawnType()` — verified via `javap`
against `build/moddev/artifacts/neoforge-21.1.248-merged.jar`, confirmed as real fired-in-vanilla
plumbing, not a mod-only utility. **So the spawner/trial-spawner case needs a plain
`@SubscribeEvent` listener, not a mixin.** Task A updates the class javadoc to record this
alongside the still-true trap for the other paths.

## Verified real hooks (all confirmed via `javap` against the actual merged jar — do not
re-derive from memory, re-verify only if NeoForge/MC versions change)

| Origin | Real hook | Shape |
|---|---|---|
| `SPAWNER`, `TRIAL_SPAWNER` | `FinalizeSpawnEvent` (NeoForge event, fired by both `BaseSpawner#serverTick` and `TrialSpawner#spawnMob` via `EventHooks.finalizeMobSpawnSpawner`) | `@SubscribeEvent` listener, no mixin. `getEntity()`/`getSpawnType()` |
| `SPAWN_EGG`, `DISPENSER`, `STRUCTURE` | `EntityType#create(ServerLevel, Consumer<T>, BlockPos, MobSpawnType, boolean, boolean)` | ONE mixin — verified this is the single funnel all three paths converge on: `EntityType#spawn(SL, ItemStack, Player, BlockPos, MobSpawnType, Z, Z)` (spawn egg), `EntityType#spawn(SL, BlockPos, MobSpawnType)` (used by `NetherPortalBlock` with `MobSpawnType.STRUCTURE` for zombified piglins), and the 6-arg `Consumer`-based `spawn` all call this one `create` overload |
| `BREEDING` | `Animal#finalizeSpawnChildFromBreeding(ServerLevel, Animal mate, AgeableMob child)` | `child` is already a constructed parameter — inject at HEAD or TAIL, call `stampOnSpawn(child, MobSpawnType.BREEDING)` directly (skip the `Level`+`MobSpawnType` two-arg overload's guard duplication, or use it — either is fine, the `Entity`-only overload does the same client/`LivingEntity` guard) |
| `COMMAND` | `SummonCommand#createEntity(CommandSourceStack, Holder.Reference<EntityType<?>>, Vec3, CompoundTag, boolean)` — **static**, returns `Entity` | Inject at TAIL/RETURN, call `stampOnSpawn(returnedEntity, MobSpawnType.COMMAND)` |
| `CONVERSION` (carry-through only — `classify` already maps `CONVERSION` itself to `NATURAL`) | `Mob#convertTo(EntityType<T>, boolean)` — returns `T` (the new mob), calls `EntityType#create(Level)` internally (confirmed via bytecode: no `MobSpawnType` passed, matches `MobOrigins`'s doc exactly) | Inject at TAIL/RETURN, call `carryThroughConversion(this, returnedMob)` |

`classify()`'s `NATURAL` bucket (`NATURAL, CHUNK_GENERATION, MOB_SUMMONED, JOCKEY, EVENT,
CONVERSION, REINFORCEMENT, TRIGGERED, BUCKET, PATROL`) needs no hook at all — those origins are
deliberately never stamped, so no wiring is missing for them.

## Task A — Spawner + Trial Spawner (event listener, no mixin) + javadoc correction

1. In `src/main/java/com/gmail/nossr50/neoforge/listeners/` (new file, e.g.
   `MobOriginListener.java`, or fold into an existing lifecycle listener if this codebase has one
   — check `McMMOMod.java` for where other `@SubscribeEvent`-style NeoForge event listeners are
   registered): a listener method on `net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent`
   that calls `MobOrigins.stampOnSpawn(event.getEntity(), event.getSpawnType())`. Verify the event
   bus registration pattern this codebase already uses (mod bus vs game bus — `FinalizeSpawnEvent`
   is a game-bus event; check an existing NeoForge event listener in this codebase for the
   `@EventBusSubscriber`/manual-register pattern used elsewhere and match it).
2. Update `MobOrigins.java`'s class javadoc: add a note next to the existing trap-analysis section
   that on NeoForge specifically, the spawner path is NOT a mixin — it's `FinalizeSpawnEvent` via
   `EventHooks.finalizeMobSpawnSpawner`, cite the two real call sites (`BaseSpawner#serverTick`,
   `TrialSpawner#spawnMob`). Do not delete the existing trap-analysis prose — it's still the correct
   reasoning for the egg/dispenser/portal and breeding paths in Task B/C, and for why this ISN'T
   solved by one universal mixin/event.
3. Verify via a real spawner in a test world (or a targeted integration test) that `SPAWNER` origin
   gets stamped, and via a trial spawner if a play-tested chamber is feasible; at minimum, a unit
   test asserting the listener calls `stampOnSpawn` with the right arguments given a constructed
   `FinalizeSpawnEvent`.
4. Mandatory: `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-moborigin-taskA.log | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("`.

## Task B — Egg/Dispenser/Portal funnel mixin + Breeding mixin

1. New mixin `EntityTypeSpawnOriginMixin` targeting `EntityType#create(ServerLevel, Consumer, BlockPos, MobSpawnType, boolean, boolean)`. Inject at TAIL, read the returned `Entity` (may be
   null per the method's own contract when spawn placement fails or the type is behind a disabled
   feature flag — `stampOnSpawn` already null-guards), call
   `MobOrigins.stampOnSpawn(level, spawnType, returnedEntity)` using the level/MobSpawnType method
   args already in scope. Bytecode-verify (`javap -c`) the exact local-variable slots for the method
   args before writing the `@Inject` handler signature — the plan's earlier sizing pass confirmed
   the method exists and what it's called from, but did NOT decompile its full body; the implementer
   must do that before committing to the handler's parameter list.
2. New mixin `AnimalBreedChildOriginMixin` (or similar name) targeting
   `Animal#finalizeSpawnChildFromBreeding(ServerLevel, Animal, AgeableMob)`. Inject at HEAD or TAIL
   (HEAD is fine — the child is already fully constructed by the time this method runs), call
   `MobOrigins.stampOnSpawn(child, MobSpawnType.BREEDING)` using the `AgeableMob child` parameter.
   ⚠️ Verify this method is not itself gated by something that skips it for certain animal types
   (shulker self-duplication is called out in `MobOrigins`'s javadoc as "every `createChild` in the
   game, plus shulker self-duplication" mapping to `BREEDING` — confirm shulkers route through this
   same method or need a second injector).
3. Bytecode-verified `allow` counts on both injectors (this codebase's established mandatory
   practice — see `mcmmo.mixins.json`'s existing entries for the pattern).
4. Structural test proving each injector fires with the correct arguments (the
   `AbstractHorseChildAttributesMixinTest` ASM-based pattern from the Husbandry plan is the
   reference — this exact bug class, wrong/missing local forwarded, is the highest-risk failure mode
   here).
5. Mandatory `runServer` check per this codebase's standard.

## Task C — Conversion + Summon Command mixins

1. New mixin `MobConversionOriginMixin` targeting `Mob#convertTo(EntityType<T>, boolean)`. Inject at
   TAIL/RETURN (bytecode confirms a single `areturn`-style exit is NOT guaranteed — the method has
   multiple early-return points for `isRemoved()` and a null spawn result; verify all return points
   via `javap -c` and use the appropriate `@Inject`/`allow` count, likely `@Inject(at = "RETURN")`
   with `allow` matching every exit including the early nulls — `carryThroughConversion` already
   null-guards `to`). Call `MobOrigins.carryThroughConversion(thisMob, returnedMob)`.
2. New mixin `SummonCommandOriginMixin` targeting the **static**
   `SummonCommand#createEntity(CommandSourceStack, Holder.Reference<EntityType<?>>, Vec3,
   CompoundTag, boolean)`. Inject at TAIL/RETURN, call `MobOrigins.stampOnSpawn(returnedEntity,
   MobSpawnType.COMMAND)`. ⚠️ This is a **static** method with no `this` — confirm the `@Inject`
   handler is declared `private static` to match, a detail the Husbandry/Taming plans' mixins didn't
   need to handle.
3. Same bytecode-verified `allow` counts + structural tests + `runServer` check as Task B.

## Task D — Final whole-branch review

1. Run the full existing `MobOriginsTest` (if one exists — check `src/test`) plus every new test
   from Tasks A-C.
2. Live-verify (in a real or scripted test world) at minimum: a mob spawner farm mob does NOT
   advance Hunter mastery; a bred animal's offspring does NOT; a `/summon`-ed mob does NOT; a
   natural-spawn or hand-killed mob STILL DOES (the gate must not become "nothing counts").
3. Standard final-review process: opus reviewer, ONE fix wave, ONE scoped re-review, adjudicate
   residuals (park or rule), skip `finishing-a-development-branch` (branch stays open), delete the
   SDD workspace.
4. Cross-check against `docs/superpowers/notes/2026-09-03-neoforge-unblocked-farm-guards.md` — this
   plan does NOT cover the four exploits noted there (Endermite lure, fluid-generated blocks, piston
   block-cheat, snow golem trails); do not scope-creep into those.

## Complexity/risk summary

Lower risk than the audit's initial framing suggested: the spawner case turned out to need **zero
mixins** (a real NeoForge event already carries everything needed), collapsing what looked like 6
Fabric-shaped mixins down to 4 mixins + 1 event listener. The remaining real risk is exactly the
class of bug this session has hit before (`AbstractHorseChildAttributesMixin`'s backwards-local
bug): an injector that binds cleanly but forwards the wrong entity/reason, or binds on a path real
gameplay doesn't reach (the `MobOrigins.java` javadoc's own stated worst-case — "strictly worse
than the ZERO it replaced, because a ZERO is at least loud"). Every task above mandates structural
tests specifically to catch that.
