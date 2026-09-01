# Archery Non-Combat Listener (NeoForge) Implementation Plan

No spec doc for this one — it is a mechanical port of two patterns this codebase has already
proven (a `ThreadLocal` capture-then-consume bridge, and a call-site-anchored `@Inject` on a
static spawn funnel), landing on top of MC-free skill logic that is **already fully implemented
and already imports `com.gmail.nossr50.neoforge.McMMOMod`** — see
`src/main/java/com/gmail/nossr50/skills/archery/Archery.java`. Nothing on that class needs to
change; this plan only adds the NeoForge-side glue that calls into it.

## Why this is needed

Archery's combat-damage arm (Skill Shot, Arrow Retrieval *credit*, Limit Break) is already fully
ported and live in `EntityDamageListener` (Phase 2 Task C). But two of the three legs Archery's
XP math and Arrow Retrieval stand on are stamped **before** that damage event ever fires, at
projectile launch — and nothing on NeoForge stamps them yet:

- `Archery.bowForceMultiplier(arrowId)` — always reads back the flat `1.0` default, because
  nothing ever calls `Archery.markBowForce`. Confirmed live in
  `EntityDamageListener.java:1445-1449`: the comment already says "an arrow that skipped that
  hook... reads back the flat 1.0", but on this branch *every* arrow skips it — there is no
  Fabric-equivalent of `BowShootMixin` on NeoForge at all.
- `Archery.distanceXpBonusMultiplier(...)` — always reads back `1` (no bonus), because nothing
  ever calls `Archery.markFiredFrom`. Same story: no NeoForge equivalent of
  `ProjectileSpawnMixin`/`ProjectileListener.onProjectileSpawn`.
- Arrow Retrieval's *launch* half (rolling `archery.rollArrowRetrieval()` and marking the arrow
  with `TRACKED_ARROW_KEY`) and *death* half (dropping the tracked arrow count when the struck
  entity dies) are both entirely unported. `archery.retrieveArrows(...)` is called from
  `EntityDamageListener` already, but it can never find a marked arrow to retrieve, since nothing
  ever marks one.

Net effect on the live server right now: Archery's XP is silently paying distance-1/force-1 on
every shot (not zero — the defaults are deliberately generous — but wrong), and Arrow Retrieval
does nothing at all despite its rank/config plumbing being fully wired.

Reference implementation (Yarn-mapped, Fabric): read in full before starting —
```
git show e7203bbf3:src/main/java/com/gmail/nossr50/fabric/listeners/ProjectileListener.java
git show e7203bbf3:src/main/java/com/gmail/nossr50/fabric/mixin/ProjectileSpawnMixin.java
git show 82cde6655:src/main/java/com/gmail/nossr50/fabric/mixin/BowShootMixin.java
git show 82cde6655 -- src/main/java/com/gmail/nossr50/fabric/listeners/ProjectileListener.java
```
The last `git show` (a diff) is the one that added the bow-force stamp to the launch mixin's
sibling logic — read it alongside the full file, not instead of it.

## Global Constraints

- Re-verify every mixin target signature with `javap -p -c` against
  `build/moddev/artifacts/neoforge-21.1.248-merged.jar` before writing the mixin. The Fabric
  reference used Yarn mappings (`ProjectileEntity`, `ArrowEntity`, `BowItem#onStoppedUsing`); this
  branch uses Mojang mappings throughout (`Projectile`, `Arrow`, and `BowItem`'s release-hook is
  likely named `releaseUsing`, not `onStoppedUsing` — **do not hardcode either name from memory,
  confirm both the class and method names against the real jar**).
- `allow = 1` on every call-site-anchored injector; HEAD/RETURN injections into named methods need
  neither `allow` nor `require`, but state the expected `allow` count explicitly in the mixin's
  javadoc the way `AbstractHorseChildAttributesMixin` and `ShearsItemInteractMixin` do, and add a
  bytecode-level structural test for it — this plan's mixins are exactly the class of "silently
  wrong which local gets forwarded" risk that produced the real bug in
  `AbstractHorseChildAttributesMixin` during the Husbandry plan. Do not skip the structural test.
- No `@Mixin`-annotated interfaces in this plan; if one becomes necessary, it must stay
  pure-abstract (see the Mixin target-type inference boot-crash rule — a concretely-implemented
  member on a `@Mixin` interface crashes boot).
- Unit tests cannot exercise real Mixin application (no ModLauncher wiring under plain
  `./gradlew test`) — verify mixin correctness structurally (ASM/reflection on the compiled
  handler, matching `AbstractHorseChildAttributesMixinTest`'s pattern) plus the mandatory boot
  check below.
- Register the new listener exactly where existing ones are wired: a `register()` static method
  called from `McMMOMod`, mixin classes added to `src/main/resources/mcmmo.mixins.json`
  (alphabetically).
- **Mandatory at the end of every task**: run
  `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-archery-<task>.log | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("`
  and confirm a clean `Done (` boot with no mixin errors before marking the task complete.

## Task A: Bow draw-force capture

**Files:**
- `src/main/java/com/gmail/nossr50/neoforge/mixin/BowShootStashMixin.java` (new)
- `src/test/java/com/gmail/nossr50/neoforge/mixin/BowShootStashMixinTest.java` (new)
- `src/main/resources/mcmmo.mixins.json` (add entry)

**Work:**

1. Confirm the real name/descriptor of `BowItem`'s "stopped using" hook in the merged jar
   (`javap -p -c net.minecraft.world.item.BowItem` or the equivalent path in the extracted
   artifact) — the Yarn reference calls it `onStoppedUsing(ItemStack, World, LivingEntity, int):
   boolean`; find its Mojang-mapped name and confirm the signature shape matches (stack, level,
   living entity, remaining-use-ticks → boolean).
2. Write `BowShootStashMixin` (`@Mixin(BowItem.class)`, two `@Inject`s — `HEAD` and every
   `RETURN` — mirroring `BowShootMixin`'s HEAD-capture/RETURN-clear bracket exactly): at `HEAD`,
   guard on the user being a `Player` (skeletons fire through their attack goal, not this method),
   compute `useTicks = getMaxUseTime(stack, user) - remainingUseTicks`, call
   `Archery.beginBowShot(BowItem.getPullProgress(useTicks))`; at every `RETURN`, call
   `Archery.endBowShot()`. Confirm `getPullProgress` is still `static` and still takes an `int` in
   1.21.1's Mojang mappings before relying on it.
3. Add `BowShootStashMixin` to `mcmmo.mixins.json`.
4. Structural test: assert (via `javap`/ASM on the compiled mixin class, not reflection on
   loaded-mixin behavior) that both injectors exist, target the confirmed method descriptor, and
   that the RETURN injector's `allow` count matches the real number of return opcodes in the
   target method (bytecode-verify this count the way `ShearsItemInteractMixin`'s `allow=2` was
   verified — do not assume it is exactly 1).
5. Run the mandatory `runServer` boot check.

## Task B: Arrow Retrieval launch/death + fired-from XP stamp

**Files:**
- `src/main/java/com/gmail/nossr50/neoforge/listeners/ProjectileListener.java` (new)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/ProjectileSpawnMixin.java` (new)
- `src/test/java/com/gmail/nossr50/neoforge/mixin/ProjectileSpawnMixinTest.java` (new)
- `src/main/resources/mcmmo.mixins.json` (add entry)
- `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (wire `ProjectileListener.register()`)

**Work:**

1. Port `ProjectileListener` from the Fabric original (`onProjectileSpawn` and `onDeath`),
   translating Fabric-API/Yarn types to NeoForge/Mojang:
   - `onProjectileSpawn(Projectile projectile, ServerLevel level)`: narrow to `Arrow` (Mojang name
     for `ArrowEntity` — confirm `SpectralArrow`/`ThrownTrident` are still siblings under it, not
     subtypes, exactly as the Fabric javadoc verified for Yarn's `AbstractArrow` hierarchy — a
     wrong assumption here silently widens or narrows who's eligible), then a `ServerPlayer`
     owner. Look up `McMMOPlayer`/`ArcheryManager` the same way `EntityDamageListener` does
     elsewhere in this codebase (grep it for the pattern, don't reinvent it). Skip
     Infinity-enchanted and Piercing-in-hand shots exactly as the reference does — confirm
     `getWeaponStack()` is the same accessor name in Mojang mappings (it very likely is, this is
     already used in `EntityDamageListener`, grep for it there first rather than re-deriving).
     On a successful `rollArrowRetrieval()` roll: `MetadataStore.setFlag(arrow,
     Archery.TRACKED_ARROW_KEY)`, plus **both** of the stamps this task adds beyond retrieval —
     `Archery.markFiredFrom(...)` (world key + XYZ, unconditional — happens regardless of the
     retrieval roll's outcome, exactly as the Fabric reference orders it: fired-from and bow-force
     are stamped *before* the retrieval gates, only the `TRACKED_ARROW_KEY` mark is gated) and, if
     `Archery.currentBowShotForce()` is non-null, `Archery.markBowForce(arrowId, force)`.
     Schedule cleanup of all three keys (`TRACKED_ARROW_KEY`, `FIRED_FROM_KEY`, `BOW_FORCE_KEY`)
     after `MARK_CLEANUP_DELAY_TICKS` (`20 * 120`, kept verbatim) via
     `McMMOMod.getScheduler().runLater(...)` — grep an existing NeoForge listener for this
     scheduler's exact call shape rather than guessing it.
   - `onDeath(LivingEntity victim)`: `Archery.arrowRetrievalCheck(victim.getUUID())`; if `> 0`,
     spawn an `ItemEntity` of `count` arrows at the victim's position with
     `setDefaultPickUpDelay()` (confirm this is still the Mojang name for Yarn's
     `setToDefaultPickupDelay`), `level.addFreshEntity(...)` (Mojang name for `spawnEntity`).
     Register this on whatever NeoForge death event this codebase already uses elsewhere for a
     similar "after this entity dies" hook — check `CombatListener` or `EntityDamageListener` for
     the established event/registration pattern (likely `LivingDeathEvent`) rather than
     reintroducing a new one; keep it a **separate** registration from any existing kill-XP hook,
     matching the Fabric reference's explicit reasoning (arrows are owed regardless of who/what
     landed the killing blow, unlike kill-XP which requires a player).
2. Find the real spawn funnel: confirm (via `javap`) that `Projectile`'s Mojang-mapped static
   spawn method is the single funnel every `spawnCreatureAt`/velocity-variant path delegates
   through, the same way the Fabric reference bytecode-verified `ProjectileEntity#spawn`. Do not
   assume the name is unchanged from Yarn — search the merged jar.
3. Write `ProjectileSpawnMixin` (`@Mixin(Projectile.class)`, one `@Inject` at `TAIL` of the
   confirmed funnel method) calling `ProjectileListener.onProjectileSpawn(projectile, level)`.
4. Add `ProjectileSpawnMixin` to `mcmmo.mixins.json`; wire `ProjectileListener.register()` from
   `McMMOMod` (death-hook registration) alongside the existing listener registrations.
5. Structural test for `ProjectileSpawnMixin` following the `BowShootStashMixinTest`/
   `AbstractHorseChildAttributesMixinTest` pattern: confirm the injector targets the real
   confirmed method descriptor at `TAIL`.
6. Run the mandatory `runServer` boot check — this is the task most likely to reveal a wrong
   method-name guess, since a bad `target=` string fails to apply at boot rather than at compile
   time.

## Task C: Final review

Whole-branch review of Tasks A+B together (`opus`, matching every prior plan's final-review
model choice): confirm the ThreadLocal bridge in `BowShootStashMixin`↔`ProjectileSpawnMixin` is
airtight (capture happens strictly before the funnel runs, on the same thread, for exactly one
arrow — verify there's no reentrancy risk from a multi-shot crossbow-adjacent path if one
exists), confirm `ProjectileListener`'s death-hook registration doesn't double-fire alongside any
existing kill-XP hook, confirm all three `MetadataStore` keys are cleaned up on every path
(including the early-return branches), and confirm the structural tests actually catch a
regression (compile a deliberately-wrong variant of each mixin during review and confirm the test
fails against it — the same empirical-proof standard the Husbandry final review applied to the
Beekeeper-polarity test gap). One fix wave + one scoped re-review if anything Important or
Critical turns up; park Minor findings with a ruling, per this project's established SDD
close-out process (no second wave).
