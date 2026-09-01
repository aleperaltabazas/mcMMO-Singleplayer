# Taming Listener (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the four remaining Fabric Taming files to NeoForge 1.21.1: `TamingListener` (tame
XP), `PetCombatModeListener` (stance-toggle gesture), `PetCombatSweep` (aggression/engage sweep),
`PetFollowTeleport` (teleport-through pets). All four are small (Fabric total ~450 lines across the
four files) and every MC-free helper/manager/config method they call already exists on this branch.

**Architecture:** Two new tiny `@Inject`-only mixins for tame XP (Task A); one genuine
event-based port with no mixin for the stance toggle, using `PlayerInteractEvent.EntityInteract`
(Task B); two plain methods wired into `PlayerMovementTracker`'s already-marked hook points, no
mixin, no event (Task C).

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), Sponge Mixin, JUnit 5 + Mockito.

**Spec:** docs/superpowers/specs/2026-09-01-taming-listener-design.md — **read this first**. It has
the seam-by-seam mapping (which Fabric/Yarn names are confirmed vs. still need `javap`
verification), the `EntityInteract`-vs-mixin risk for Task B, and the exact `PlayerMovementTracker`
hook-point line numbers for Task C.

**Source:** the Fabric original, recoverable at commit `d0764257671576525aedd97308be2f8c6d85e2fd`:
`src/main/java/com/gmail/nossr50/fabric/listeners/{TamingListener,PetCombatModeListener,
PetCombatSweep,PetFollowTeleport}.java`. Pull each with `git show
d0764257671576525aedd97308be2f8c6d85e2fd:<path>`.

## Global Constraints

- Every `TamingManager`/`PetCombatMode`/`PetTargeting`/config-getter call the Fabric originals make
  already exists on this branch with the same signature — do not modify those files, do not
  re-derive skill math, just call them. See spec's "already ported" list.
- Do not transcribe any Yarn/Fabric MC-type or method name verbatim into a mixin `@At` target or a
  direct call — the spec flags several as unverified (`isOwnedBy` vs `isOwner`,
  `player.level()` vs `getEntityWorld()`, `Attributes.FOLLOW_RANGE` vs `GENERIC_FOLLOW_RANGE`,
  `PositionFlag`'s Mojang name, etc.). Re-verify each via `javap` against
  `build/moddev/artifacts/neoforge-21.1.248-merged.jar` or the extracted NeoForge sources
  (`/tmp/mcsrc/` or re-extract from the sources jar at
  `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.248/*/neoforge-21.1.248-sources.jar`
  if that path is gone) before writing code that depends on it.
- Mixin interfaces (`@Mixin`-annotated `interface`), if any turn out to be needed, must stay pure
  abstract-only — no static/default members — per the boot-crash lesson this project has hit
  repeatedly. This plan is not expected to need one (both Task A mixins target concrete classes).
- Verify mixin application for real at the end of every task, not just the last one:
  `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-taming.log |
  grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("` — plain JUnit never
  applies mixins.
- `PetCombatSweep.tick` and `PetFollowTeleport.onPlayerMoved` are called **unconditionally** on
  every ticked player from `PlayerMovementTracker`, positioned **above** the missing-profile early
  return — same relative position as Fabric. Do not gate either call behind a loaded-profile check;
  both are explicitly designed to keep working during a fresh join (see spec §3/§4 and the
  `OMISSION` comments already in `PlayerMovementTracker.java` at lines ~260-269).

---

### Task A: Tame XP — two mixins, `TamingListener`

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/TamingListener.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/TameableAnimalTameMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/AbstractHorseTameMixin.java`
- Modify: `src/main/resources/mcmmo.mixins.json` (register both new mixins, alphabetically placed)

**Do:**
1. Read the spec's §1. Read the Fabric original: `git show
   d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/
   TamingListener.java`.
2. `javap -p` `TamableAnimal.class` and `AbstractHorse.class` from the merged jar
   (`build/moddev/artifacts/neoforge-21.1.248-merged.jar`) to confirm `tame(Player)` and
   `tameWithName(Player)` are the real 1.21.1 method names and signatures (the spec already did
   this once; re-confirm it yourself before writing the `@At` targets — do not trust a stale
   reading).
3. Write `TamingListener.onEntityTamed(Player owner, Entity tamed)`: `ServerPlayer` instanceof
   guard → `UserManager.getPlayer(uuid)` null-guard → `TamingManager` null-guard →
   `ConfigStringUtils.getConfigEntityTypeString(BuiltInRegistries.ENTITY_TYPE.getKey(tamed.getType())
   .getPath())` → `taming.awardTamingXP(entityConfigString)`. Private constructor, final class,
   matching the Fabric original's shape.
4. Write both mixins as `@Inject`-only on concrete classes (`TamableAnimal`, `AbstractHorse`),
   `TAIL` of the tame method, forwarding `((Player) player-argument, (Entity) this)` (or however
   the locals actually read once verified) to `TamingListener.onEntityTamed`.
5. Register both mixins in `mcmmo.mixins.json`.
6. Run the mandatory `runServer` verification (see Global Constraints). Confirm no
   `InvalidMixinException`/`MixinApplyError` and that the server reaches `Done (`.
7. Write a unit test for `TamingListener.onEntityTamed` covering: non-`ServerPlayer` owner
   (no-op), no loaded `McMMOPlayer` (no-op), no `TamingManager` (no-op), the happy path (calls
   `awardTamingXP` with the right config string for at least two entity types, e.g. wolf and
   horse, to prove the registry-path lookup is correct).

**Acceptance:** `TamingListener` compiles, both mixins apply cleanly under `runServer`, taming a
wolf or horse in a manual test (left to the user's later pass) awards Taming XP, unit tests pass.

---

### Task B: Pet combat-mode toggle — `PetCombatModeListener` via `EntityInteract`

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/PetCombatModeListener.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (call
  `PetCombatModeListener.register()` at mod load, alongside the other listener `register()` calls)

**Do:**
1. Read spec §2 in full, including the risk note about `EntityInteract` possibly not being the
   right seam — **before writing any code, re-read
   `net/neoforged/neoforge/event/entity/player/PlayerInteractEvent.java`** from the extracted
   NeoForge sources (paths in the spec/global constraints) and confirm for yourself that
   `EntityInteract` fires early enough and cancels the right vanilla call
   (`Entity#interact(Player, InteractionHand)`) to claim a sneak+bone click before vanilla sits the
   pet. If it does not, fall back to a HEAD-injecting mixin on `Player#interactOn(Entity,
   InteractionHand)` (the same target `PlayerInteractionStashMixin` already hooks for Husbandry —
   read that file for the established pattern) and say so explicitly in the task report.
2. Read the Fabric original in full (already quoted in this session's investigation, or re-fetch
   via `git show d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/
   listeners/PetCombatModeListener.java`).
3. Read `RepairSalvageListener.java` on this branch for the established "claim on both logical
   sides identically" idiom already proven to work on NeoForge for an equivalent click-claiming
   problem.
4. Port `register`, `onEntityInteract` (renamed from `onUseEntity`), `isToggleGesture`,
   `announce`, `isFeatureEnabled`, `toggleItem` — same structure as Fabric, MC types swapped per
   spec §2 (`Player`, `ServerPlayer`, `InteractionHand`, `TamableAnimal`, `InteractionResult`).
   Verify `isShiftKeyDown`, `isOwnedBy`/`isOwner`, `isTame` against the merged jar rather than
   guessing.
5. Wire `register()` into `McMMOMod`.
6. Run the mandatory `runServer` verification.
7. Port `PetCombatModeListenerTest.java`'s equivalent coverage from Fabric (package-private
   dispatch test driving `onEntityInteract`/`isToggleGesture` directly, per the Fabric test's own
   stated "respawn-stale-handle" rationale for testing the real dispatch, not just predicates) —
   at minimum: gesture fires only on main hand + sneaking + owned tamed pet + correct item;
   non-gesture clicks return without cancelling; missing profile still cancels with the
   pending-load message; missing `TamingManager` logs+cancels; happy path toggles and
   announces/plays sound.

**Acceptance:** compiles, mixin-or-event path verified live (`runServer`), tests pass, and the
task report states explicitly which of the two approaches (event vs. mixin fallback) was used and
why.

---

### Task C: Pet sweep + teleport-through — wired into `PlayerMovementTracker`

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/PetCombatSweep.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/PetFollowTeleport.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/PlayerMovementTracker.java`
  (replace the two `OMISSION` comment blocks at lines ~260-269 with real calls to
  `PetFollowTeleport.onPlayerMoved(player, previous, current, sameWorld)` and
  `PetCombatSweep.tick(player)`, in that order, matching Fabric's ordering and both positioned
  above the missing-profile early return — also update or remove the stale javadoc at lines
  ~102-105 that currently documents these as "not ported")

**Do:**
1. Read spec §3 and §4 in full. Read both Fabric originals (`git show
   d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/
   {PetCombatSweep,PetFollowTeleport}.java`).
2. Read `HusbandryListener#onLovePlayer`'s and `PlayerMovementTracker.java:391-414`'s existing
   `level.getEntities(EntityTypeTest.forClass(...), AABB, Predicate)` idiom — this is the direct
   precedent for both files' entity queries; do not reach for a different overload.
3. `javap` the merged jar (or grep the extracted sources) for every renamed type/method flagged
   "verify" in spec §3/§4 before writing code that depends on it: `Wolf`, `Mob`, `Monster`,
   `Warden`, `Attributes.FOLLOW_RANGE`, `Wolf#canAttackWithOwner`, `TamableAnimal#
   cannotFollowOwner`/`tryTeleportToOwner`/`shouldTryTeleportToOwner`, the 1.21.1
   `Entity#teleport` overload and its flag-set type, `ServerPlayer`'s level accessor (replacing
   Fabric's `getEntityWorld()`), `Player#isOnGround`, the water-check method name, and an `AABB`
   constructor equivalent to `Box.of(center, dx, dy, dz)`.
4. Port `PetCombatSweep.tick(ServerPlayer player)` — private constructor, `ASSUMED_BASE_FOLLOW_RANGE`
   constant, `applyEngageBoost`, `findCandidates`, `isWarden`, `acquire`, `resolveMode`, matching
   Fabric's structure and comments' stated intent (candidate query paid for once per pack, per-pet
   `canAttackWithOwner` filter kept, warden excluded explicitly, sitting pets get their boost
   zeroed).
5. Port `PetFollowTeleport` — `DEFAULT_ENABLED`/`DEFAULT_RADIUS` constants, `onPlayerMoved`,
   `isTeleport` (3D squared-distance against `PlayerMovementTracker.TELEPORT_DELTA` — confirm that
   constant exists and is accessible), `bringPetsFrom`, `isFollower`, `bring`, `isEnabled`,
   `radius`. Preserve the airborne-fallback refusal (`bring` must not drop a pet on an airborne
   owner) and the "vanilla's own placement search first, exact-position fallback second" ordering
   — these are stated safety properties in the Fabric javadoc, not incidental behavior.
6. Wire both calls into `PlayerMovementTracker` at the marked hook points; delete the stale
   `OMISSION` comments and the stale "not ported" javadoc claim at lines ~102-105.
7. Run the mandatory `runServer` verification.
8. Port both Fabric test files' equivalent coverage (`PetCombatSweepTest.java`,
   `PetFollowTeleportTest.java`): sweep — sitting pet zeroed, pet with live target gets boosted,
   passive mode never acquires, aggressive mode acquires nearest-to-player among eligible
   non-warden monsters, config-disabled short-circuits; teleport — non-teleport movement is a
   no-op, cross-world movement is a no-op, disabled-config is a no-op, a follower pet within
   radius gets moved, a non-follower (sitting/ridden/leashed) pet is skipped, an airborne owner
   with no landing spot leaves the pet behind rather than dropping it.

**Acceptance:** compiles, `runServer` clean, `PlayerMovementTracker`'s stale omission
documentation is gone, tests pass covering both files' stated safety properties (not just the
happy path).

---

### Final whole-branch review

After all three tasks: one `opus`-model final review of the whole plan's diff (all three tasks
together — this is a small enough plan that per-task review plus one final pass is sufcient, no
need for the task-review layer Husbandry used given the much smaller surface area... **however**,
if the SDD controller judges Task B's event-vs-mixin uncertainty or Task C's teleport-flag-type
uncertainty as high-risk enough to warrant a per-task review anyway, that is the controller's call
to make at dispatch time, not a fixed requirement of this plan). One fix wave + one scoped
re-review if the final review finds anything, per the standard SDD "no second wave" rule. Skip
`finishing-a-development-branch` — the shared `neoforge/1.21.1` branch stays open for the user's
own consolidated test pass, same ruling as the prior four plans.
