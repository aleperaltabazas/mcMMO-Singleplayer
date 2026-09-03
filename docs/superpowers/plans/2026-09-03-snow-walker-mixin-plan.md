# Snow Walker has no in-game effect — implementation plan

## Why this plan exists

The 2026-09-03 feature-completeness audit's Finding 3: Parkour's Snow Walker subskill is inert.
`PlayerMovementTracker.java` already computes and publishes per-tick eligibility
(`SNOW_WALKERS`, a `ConcurrentHashMap`-backed set, and `canWalkOnPowderSnow(UUID)` at
`PlayerMovementTracker.java:189-199`) — its own javadoc explicitly says *"Snow Walker has no
in-game effect until that mixin lands"* (line 187), naming the missing consumer as
`PowderSnowBlockMixin`. The state-publishing design (computed once per server tick on the server
thread, read from either the client or server thread via a UUID-keyed concurrent set, specifically
to avoid a data race on `RankUtils`'s non-thread-safe cache and a per-tick config read in a hot
collision-geometry path) is already fully reasoned through. **This plan is purely: write the
consumer mixin.**

No spec doc needed — `PlayerMovementTracker.java`'s existing javadoc (lines 165-199) is the
design doc.

## Verified real hook (via `javap` against `build/moddev/artifacts/neoforge-21.1.248-merged.jar`)

`net.minecraft.world.level.block.PowderSnowBlock`:
```
public static boolean canEntityWalkOnPowderSnow(Entity);
```
Matches the existing javadoc's named target almost exactly (it says
`PowderSnowBlock#canWalkOnPowderSnow`; the real 1.21.1 name has `Entity` in it — re-verify this
wasn't a typo/simplification in the javadoc vs. an actual rename, but treat the `javap` output as
authoritative). Static, takes the querying `Entity`, returns whether that entity currently may walk
on top of powder snow instead of sinking. This is a single, self-contained predicate — the
simplest possible injection shape.

## Task A — `PowderSnowBlockMixin`

1. New mixin targeting `PowderSnowBlock#canEntityWalkOnPowderSnow(Entity)`. `@Inject(at =
   @At("HEAD"), cancellable = true)`. Handler: if the parameter is a `ServerPlayer` (or `Player` —
   confirm which is reachable/correct on both logical sides per the class javadoc's client+server
   dual-thread note) and `PlayerMovementTracker.canWalkOnPowderSnow(player.getUUID())` is true, call
   `cir.setReturnValue(true)`. Otherwise fall through to vanilla's own logic (leather boots,
   fox/rabbit-sized entities, etc. — Snow Walker should be an *additional* way to qualify, not a
   replacement).
2. ⚠️ Confirm whether this method is called from **both** logical client and server (the tracker's
   javadoc explicitly designed for this — verify the real call sites, e.g. `getCollisionShape`
   and/or `entityInside`, actually run on both sides in a singleplayer/integrated-server context,
   matching the reason `SNOW_WALKERS` is a thread-safe published set rather than a live rank check).
3. Bytecode-verify (`javap -c`) `canEntityWalkOnPowderSnow`'s real body — confirm it's a simple
   predicate with one or few return points, so the `@Inject(cancellable = true)` HEAD-override shape
   is safe and doesn't skip any other side effect (this method looks purely functional/predicate-
   shaped from the signature, but verify rather than assume, per this codebase's established
   practice).
4. Bytecode-verified `allow` count; a structural/behavioral test confirming the mixin returns `true`
   for a tracked Snow Walker and defers to vanilla otherwise (mirroring the harness pattern already
   used for similar boolean-predicate mixins in this codebase, e.g. anything in the Alchemy or
   Husbandry plans that override a vanilla gate).
5. Mandatory: `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-snowwalker.log
   | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("`.
6. Recommended real verification: ranked into Snow Walker in a test world, confirm walking onto
   powder snow no longer sinks; confirm an un-ranked player still sinks normally.

## Task B — Final review

Small enough to fold into Task A's own review (single-task plan, same precedent as Agility's and
the firework plan). Standard adjudication for anything residual. Skip
`finishing-a-development-branch`. Delete the SDD workspace.

## Complexity/risk summary

Lowest-risk of the three sized fixes. All the hard design work (thread-safety, avoiding a hot-path
config read, the UUID-keyed dual-side publish pattern) was already done when the state-tracking
half was ported — this plan is one `@Inject(cancellable = true)` on one already-identified,
already-verified-to-exist static predicate method.
