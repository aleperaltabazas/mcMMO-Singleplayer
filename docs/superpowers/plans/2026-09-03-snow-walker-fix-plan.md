# Parkour Snow Walker Fix (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Parkour's **Snow Walker** subskill an actual in-game effect. Its state-tracking half
already ships on this branch — `PlayerMovementTracker.SNOW_WALKERS` / `canWalkOnPowderSnow(UUID)`
is computed once per server tick and published for any thread to read — but the consumer mixin was
never ported, so a ranked player still sinks into powder snow exactly like an unranked one.

**No separate spec doc.** This is a single-injector mechanical port with one already-published,
already-thread-safe piece of state to read. Comparable in size/risk to the Agility plan.

**Architecture:** Fabric's `PowderSnowBlockMixin` injects at the `HEAD` of
`PowderSnowBlock#canWalkOnPowderSnow(Entity)`, cancellable, and returns `true` when the entity is a
player `PlayerMovementTracker` currently flags as a Snow Walker — turning a `false` into a `true`
only, never the reverse, so every other vanilla path that already allows walking (leather boots, the
`POWDER_SNOW_WALKABLE_MOBS` tag) is untouched.

**⚠️ The method name is NOT the same under Mojang mappings.** Verified via `javap` against this
branch's real merged 1.21.1 jap (`net/minecraft/world/level/block/PowderSnowBlock.class`, from
`neoformruntime`'s `rename_*_output.jar`):

```
public static boolean canEntityWalkOnPowderSnow(net.minecraft.world.entity.Entity);
```

Yarn's name (`canWalkOnPowderSnow`) does **not** carry over — Mojang's is
`canEntityWalkOnPowderSnow`, static, still a single `(Entity) -> boolean`. The implementer must
target this exact name, not transcribe the Fabric source's `method = "canWalkOnPowderSnow"` string.

**Single common-side injector, matching the Agility Glide precedent.** `PowderSnowBlock` is a shared
(non-`.client.`) class computed independently on both the client and the integrated server in
singleplayer — same reasoning the Agility plan's `LivingEntityGlideMixin` already established: this
branch's `mcmmo.mixins.json` has no client/server split, so one injector in the common mixin set
covers both sides (client + integrated server share one JVM). Do not try to split this into two
mixins the way Fabric's dual-side registration implied.

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), Sponge Mixin, JUnit 5.

## Global Constraints

- Only ever turn a `false` into a `true`. Do **not** inject at `HEAD` and cancel unconditionally, or
  short-circuit anything for entities the vanilla method would already allow through (leather boots,
  the walkable-mobs tag) — mirror Fabric's exact shape: check the condition, and only call
  `cir.setReturnValue(true)` when it holds; otherwise fall through to vanilla's own logic untouched.
- Only players are eligible — non-player entities must fall straight through to vanilla with no
  mcMMO involvement at all.
- Read Snow Walker status via `PlayerMovementTracker.canWalkOnPowderSnow(UUID)` exactly as already
  documented in that class's javadoc (`src/main/java/com/gmail/nossr50/neoforge/listeners/
  PlayerMovementTracker.java:184-199`) — do not add a second lookup path or touch `RankUtils`/config
  directly from the mixin; that per-tick, two-thread hot path is exactly why the published-flag
  design exists (see the same file's javadoc, which also cites the Catalysis per-tick-config-read
  trap this must not repeat).
- `@Inject(method = "canEntityWalkOnPowderSnow", allow = 1, at = @At("HEAD"), cancellable = true)` —
  confirm `allow = 1` reproduces against the real jar via the mixin-application check in Step 3
  below, the same bytecode-verification discipline every other mixin in this repo follows.
- Follow this repo's existing mixin package/style conventions (see
  `src/main/java/com/gmail/nossr50/neoforge/mixin/` for the established javadoc/import/package
  shape) rather than transcribing Fabric's package name (`com.gmail.nossr50.fabric.mixin`).

---

### Task A: `PowderSnowBlockMixin`, wired

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/PowderSnowBlockMixin.java`
- Modify: `src/main/resources/mcmmo.mixins.json` (register the new mixin, alphabetically placed
  among the existing `"mixins"` entries)
- Test: `src/test/java/com/gmail/nossr50/neoforge/mixin/PowderSnowBlockMixinTest.java` — a
  structural/ASM test in the style already established for this repo's other single-injector mixins
  (e.g. `LivingEntityGlideMixinTest` from the Agility plan), confirming the injector targets the
  real `canEntityWalkOnPowderSnow` descriptor and that `allow = 1` is satisfied against the real
  compiled class.

**Steps:**

1. **Read** `PlayerMovementTracker.java` lines ~160-200 in full before writing anything — the
   existing javadoc on `SNOW_WALKERS` and `canWalkOnPowderSnow(UUID)` documents exactly why the flag
   is published rather than computed live, and the mixin must not reintroduce either hazard
   (`RankUtils`' non-thread-safe cache; a config read in a hot per-tick, per-entity geometry path).

2. **Verify the real method** against this branch's actual merged jar before writing the injector —
   do not trust this plan's `javap` output as a substitute for re-checking it:
   ```
   python3 -c "
   import zipfile
   z = zipfile.ZipFile('<the branch's current rename_*_output.jar under
        ~/.gradle/caches/neoformruntime/intermediate_results/>')
   z.extract('net/minecraft/world/level/block/PowderSnowBlock.class', '/tmp/psb')
   "
   javap -p /tmp/psb/net/minecraft/world/level/block/PowderSnowBlock.class
   ```
   Confirm `canEntityWalkOnPowderSnow(Entity)` is `public static`, returns `boolean`, and is the
   only method by that name (i.e. `allow = 1` is correct, not a guess).

3. **Write `PowderSnowBlockMixin`:**
   ```java
   @Mixin(PowderSnowBlock.class)
   public class PowderSnowBlockMixin {
       @Inject(method = "canEntityWalkOnPowderSnow", allow = 1, at = @At("HEAD"), cancellable = true)
       private static void mcmmo$parkourSnowWalker(Entity entity, CallbackInfoReturnable<Boolean> cir) {
           if (entity instanceof Player player
                   && PlayerMovementTracker.canWalkOnPowderSnow(player.getUUID())) {
               cir.setReturnValue(true);
           }
       }
   }
   ```
   Follow this repo's real import paths (`net.minecraft.world.level.block.PowderSnowBlock`,
   `net.minecraft.world.entity.Entity`, `net.minecraft.world.entity.player.Player`) — verify each
   against the jar, not against the Fabric source's Yarn-mapped imports.

4. **Register** the mixin in `src/main/resources/mcmmo.mixins.json`'s `"mixins"` array
   (common/server set — no client-only list on this branch), alphabetically among the existing
   entries.

5. **Structural test** (`PowderSnowBlockMixinTest`): confirm via reflection/ASM that
   `mcmmo$parkourSnowWalker` exists on the compiled mixin class and that its `@Inject` annotation's
   `method` value is exactly `"canEntityWalkOnPowderSnow"` — the same class of regression test the
   Agility plan's `LivingEntityGlideMixinTest`/`FoodDataExhaustionMixinTest` established, to catch a
   silently-non-applying injector rather than relying on a green compile alone.

6. **Behavioral sanity check**: a small unit test (or extend an existing `PlayerMovementTracker`
   test) confirming `canWalkOnPowderSnow(uuid)` still returns `true`/`false` correctly for a
   flagged/unflagged UUID — this already exists as production logic, so this step is confirming no
   regression, not adding new logic.

7. **Mandatory mixin-application verification** — run at the end of this task, not skippable:
   ```
   timeout 150 ./gradlew runServer --console=plain 2>&1 \
     | tee /tmp/runserver-snowwalker-taskA.log \
     | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("
   ```
   Confirm a clean `Done (...)!` boot with no `InvalidMixinException`/`FATAL`/`MixinApplyError` line
   referencing `PowderSnowBlockMixin`.

8. **Full test suite**: `./gradlew test` must pass with no regressions.

**Acceptance criteria:**
- A player flagged by `PlayerMovementTracker` as a Snow Walker does not sink into powder snow; an
  unflagged player still sinks exactly as vanilla.
- No other entity's behavior around powder snow changes.
- `runServer` boots clean with the new mixin applying (verified by log grep, not assumed).
- Structural test proves the injector targets the real method name, not a stale/guessed one.

---

## Final Review

Single-task plan — fold the final whole-branch review into Task A's own review, as this repo's
established convention allows for single-task plans (see the Agility plan's precedent). Skip
`finishing-a-development-branch`: the shared branch `neoforge/1.21.1` stays open for the user's own
consolidated test pass, consistent with every other plan this session.
