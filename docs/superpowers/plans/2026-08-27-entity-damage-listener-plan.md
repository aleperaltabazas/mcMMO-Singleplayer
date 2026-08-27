# EntityDamageListener Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `fabric/listeners/EntityDamageListener.java` (1901 lines) and its companion
mixin to NeoForge, unlocking combat XP/abilities for Swords, Axes, Unarmed, Maces, Spears,
Archery, Crossbows, Tridents, Taming's defender half, Stealth's Assassin, Hunter, and the
combat halves of Parkour (Roll/Graceful Roll/Dodge/Smash) and Unarmored (Thorny Skin/XP).

**Architecture:** The Fabric original is driven by a mixin with two injection points
(`modifyAppliedDamage` for the main bonus/reduction chain, `applyArmorToDamage` for a
pre-armor-damage capture used only by Unarmored) plus one cancel-only Fabric event for three
veto branches. The NeoForge port needs an equivalent mixin (retargeted to official 1.21.1
method names) plus a NeoForge cancellable damage event for the veto branches. Task A builds
this skeleton with every skill-specific bonus arm stubbed as a no-op; Tasks B/C/D fill the
stubs in, skill group by skill group.

**Tech Stack:** Java 21, NeoForge 21.1.x Mixin service (Sponge Mixin + MixinExtras), the
established javap-verification discipline from every prior task in this port.

**Spec:** `docs/superpowers/specs/2026-08-27-entity-damage-listener-design.md`

## Global Constraints

- Target exactly Minecraft 1.21.1 / NeoForge 21.1.x. Verify every Minecraft/NeoForge API claim
  live via javap against the compiled jar or NeoForge's own shipped/sources jar — never recall
  from training data. This is the discipline every prior task in this port has used; violations
  have been caught in review every time they were tried.
- `./gradlew compileJava` and the full test suite must be green after every task. Re-verify by
  summing `build/test-results/test/TEST-*.xml`, never trust Gradle's cached/console-only status.
- No commit may carry an AI co-author or "Generated with" trailer (`AGENTS.md`) — this repo's
  history was already cleaned of 23 such trailers once; don't reintroduce them.
- Preserve every documented ordering invariant from the Fabric original exactly. The full list
  with line numbers is in this plan's Spec and in `/tmp/entity-damage-listener-research.md`
  (a session research artifact, not committed — if it's unavailable when a task runs, recover
  the Fabric original per Task A's Step 1 and re-derive the invariants from its own comments,
  which state them explicitly).
- Recover the Fabric original via: find the deletion commit with
  `git log --all --diff-filter=D --name-only -- 'src/main/java/com/gmail/nossr50/fabric/listeners/EntityDamageListener.java'`
  and `git show <that commit>^:src/main/java/com/gmail/nossr50/fabric/listeners/EntityDamageListener.java`
  (same recovery method used for every deleted Fabric file so far in this port). The companion
  mixin recovers the same way, substituting
  `src/main/java/com/gmail/nossr50/fabric/mixin/LivingEntityDamageMixin.java`.
- Do NOT modify any file under `src/main/java/com/gmail/nossr50/skills/` — the MC-free skill
  logic this file delegates to (`MeleeDamageBonus`, `Archery`, `MovementManager`, etc.) already
  exists and is correct; this plan is translation/wiring only.

---

### Task A: Core damage-pipeline plumbing + Parkour's combat half + Unarmored

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/LivingEntityDamageMixin.java`
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/EntityDamageListener.java`
- Modify: `build.gradle` (MixinExtras dependency, if verification in Step 1 finds it's needed)
- Modify: `src/main/resources/mcmmo.mixins.json` (register the new mixin class)
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (wire
  `EntityDamageListener.register()` into the constructor, `EntityDamageListener.clear()` into
  `onServerStopping`, alongside the existing `.register()`/`.clear()` calls)
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/PlayerMovementTracker.java` —
  fill in the `EntityDamageListener.forgetPlayer(...)` omission this file already has a
  documented comment for (search for "OMISSION" near the `onQuit` method)

**Interfaces:**
- Consumes: `com.gmail.nossr50.skills.movement.MovementManager` (`processFallDamage`, already
  ported/used by `PlayerMovementTracker`), `com.gmail.nossr50.skills.unarmored.UnarmoredManager`,
  `com.gmail.nossr50.platform.MetadataStore` (already ported), `neoforge.listeners.BlastMiningListener.detonatorUuid`
  (already exists, Task 5 of the Phase 1 plan).
- Produces: `EntityDamageListener.register()`, `EntityDamageListener.clear()`,
  `EntityDamageListener.forgetPlayer(UUID)` (all `public static`, same signatures as the Fabric
  original — Tasks B/C/D and `PlayerMovementTracker` depend on these exact names existing).
  Also produces the dispatcher shell inside `onModifyAppliedDamage` with named stub-arm call
  sites that Tasks B/C/D will fill in — name them exactly `applyAttackerWeaponBonus`,
  `applyProjectileAttackBonus`, `applyWolfAttackBonus`, `applyAssassin`, `applyHunterMastery`
  (matching the Fabric original's method names) so later tasks' briefs can reference them
  unambiguously.

- [ ] **Step 1: Recover the Fabric original and its mixin; resolve the MixinExtras dependency**

Recover both files per the Global Constraints recovery method. Read
`LivingEntityDamageMixin.java` in full (it's short — the interesting content is its two
injection points and their javadoc explaining why `@ModifyReturnValue` and the ThreadLocal join
are safe).

Check whether MixinExtras is already usable on this branch:
```
grep -rn "mixinextras" build.gradle
find ~/.gradle/caches/modules-2/files-2.1/io.github.llamalad7 -maxdepth 2 2>/dev/null
```
If `build.gradle` has no `mixinextras` dependency line, add one. NeoForge 1.21.1's own MDK
template is the authority for the correct artifact coordinate and whether it needs to be a
`compileOnly`/`annotationProcessor`/`implementation`/`jarJar` dependency (mixin annotation
processors have historically needed different scopes than runtime deps) — fetch NeoForge's
current MDK example live (same discipline Task 2 of the Phase 1 plan used:
`https://raw.githubusercontent.com/neoforged/MDK/archive/1.21-mdg/build.gradle` was the correct
live template found there before) rather than guessing the coordinate/scope from memory. If the
jar already in the Gradle cache turns out to be pulled in transitively by
`net.neoforged.moddev`, confirm that with `./gradlew dependencies --configuration compileClasspath | grep -i mixinextras`
and skip adding a redundant explicit dependency — but write down in your report which case it
was, since this determines whether every future mixin using `@ModifyReturnValue` needs the same
treatment.

- [ ] **Step 2: Verify the two mixin target methods' official-mappings signatures**

`modifyAppliedDamage` and `applyArmorToDamage` are yarn names (on `LivingEntity`). Find their
official equivalents via javap against
`~/.gradle/caches/neoformruntime/intermediate_results/compiledWithNeoForge_*_output.jar` (same
jar every prior mixin/rename verification in this port has used) — search `LivingEntity`'s
method list for the closest-shaped candidates (a damage-modifying method returning `float`
taking a `DamageSource` and `float`, and an armor-application method with a similar shape).
Confirm by reading the decompiled bytecode/source if the name match is ambiguous, the same way
`BlockPlaceMixin`'s retarget was confirmed in the Phase 1 plan (a name-only match was
insufficient there; the actual bytecode shape settled it).

- [ ] **Step 3: Verify the NeoForge cancellable-damage event**

The Fabric original's `ServerLivingEntityEvents.ALLOW_DAMAGE` needs a NeoForge equivalent for
the three veto branches (Arrow Deflect, Beast Lore/Quarry Sense's cancel case, Taming's
Environmentally-Aware FALL). Read NeoForge's own event source
(`find ~/.gradle/caches -iname "*neoforge*sources*.jar"`, extract
`net/neoforged/neoforge/event/entity/living/LivingIncomingDamageEvent.java` or search for
whatever the actual cancellable pre-damage event class is named — do not assume the name from
this plan, verify it) and confirm: it's cancellable, it fires before damage is applied, and
confirm its bus (game bus vs mod bus, same `IModBusEvent` discriminator every event mapping in
this port has used).

- [ ] **Step 4: Write `LivingEntityDamageMixin.java`**

Port the two injection points with the official-mappings target method signatures from Step 2.
Keep the `@ModifyReturnValue`/`@Inject` annotation shapes identical (NeoForge's Mixin service
supports both the same way Fabric's does — this is Sponge Mixin + MixinExtras, not a
Fabric-specific mechanism). Both injectors call into
`com.gmail.nossr50.neoforge.listeners.EntityDamageListener`'s corresponding public static
methods (`onModifyAppliedDamage`, `recordPreArmorDamage`) — write these method signatures now
even though their bodies land in Step 5, so the mixin compiles against real method signatures
rather than being written blind ahead of the listener.

- [ ] **Step 5: Write `EntityDamageListener.java` — the dispatcher shell**

Read the recovered Fabric original's `onModifyAppliedDamage` (the main dispatcher, ~L407-504)
and `onAllowDamage` (the veto dispatcher, ~L166) in full, along with the ordering-invariant
comments cataloged in this plan's Spec section "Ordering invariants that must survive the
port". Port the dispatcher shell preserving the exact call order documented there:
`consumePreArmorDamage` first → `CombatUtils.isProcessingMcMMODamage()` guard → attacker-side
bonuses in order (weapon bonus → wolf bonus → projectile bonus → sic-pets → Smash → Assassin →
Hunter Mastery, in that fixed order) → defender-side only for `ServerPlayer` instances
(`recordDamageTaken` first, before any reduction, then Unarmored XP + Thorny Skin, then the
fall/own-blast/dodge three-way dispatch with Counter Attack last).

For every arm this task doesn't implement yet (`applyAttackerWeaponBonus`,
`applyWolfAttackBonus`, `applyProjectileAttackBonus`, `applyAssassin`, `applyHunterMastery`),
write it as a stub with this exact shape (matching each method's real Fabric-original signature
for its parameters and return type, found by reading the original):
```java
/** Stub — filled in by Task B/C/D of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md. */
private static float applyAttackerWeaponBonus(LivingEntity target, DamageSource source, float amount) {
    return amount; // no-op until Task B lands
}
```
(Adjust the parameter list per the real method's signature from the Fabric original — do not
invent a different signature than the original had, since Tasks B/C/D will replace these
bodies wholesale and their briefs assume the original's exact signature.)

Implement fully (not stubbed) in this task: `register()`, `clear()`, `forgetPlayer(UUID)`,
`onAllowDamage` and its three real branches (`isArrowDeflected`, `isEnvironmentallyAwareFall`,
the Beast Lore half of `maybeInspect` — Quarry Sense's half needs `HunterListener`, deferred to
Task D per the Spec; if `maybeInspect`'s Beast-Lore-only path is awkward to split cleanly from
Quarry-Sense, stub Quarry Sense's specific branch the same way as the other Task-D-owned arms
rather than blocking this task on it), `isTargetDummy`/`MANNEQUIN_ID` (shared plumbing),
`consumePreArmorDamage`/`recordPreArmorDamage` (the ThreadLocal join), `maybeAwardUnarmoredXp`,
`unarmoredXpUncapped`/`incrementUnarmoredTracker`, `maybeProcessThornySkin`,
`isUnarmoredXpSource`, `canReduceOwnBlast`/`handleOwnBlastDamage`, `handleFallDamage`
(Parkour's Roll/Graceful Roll — calls `MovementManager.processFallDamage`, already ported),
`handleDodge`/`dodgeXpUncapped`/`incrementDodgeTracker` (Parkour's Dodge),
`applySprintSmash` (Parkour's Smash), `isEntityAttack`/`isHolyHoundCause`/`isEnvironmentallyAwareCause`
(shared plumbing used by both this task's real code and later stubs).

Translate every Minecraft API call via javap, the same as every prior task. Do not assume
Phase 1's existing rename tables cover every method this file calls — this file introduces
several new ones (`DamageSource`/`DamageTypes` API, `EntityAttributes`, wolf/horse/llama entity
classes, `ArmorStandEntity`, `Tameable`, projectile entity classes). Verify each one.

- [ ] **Step 6: Wire registration and the deferred `forgetPlayer` call**

Add `EntityDamageListener.register()` to `McMMOMod`'s constructor and
`EntityDamageListener.clear()` to `onServerStopping`, matching the pattern every other listener
in that file uses. In `PlayerMovementTracker.java`, find the `onQuit` method's existing
documented omission comment for `EntityDamageListener.forgetPlayer(...)` and replace it with
the real call now that the method exists.

- [ ] **Step 7: Register the mixin**

Add `LivingEntityDamageMixin` to `src/main/resources/mcmmo.mixins.json`'s `mixins` array
(alongside the 4 already there from Phase 1 — `BlockPlaceMixin`, `ExplosionDropsMixin`,
`HoeTillingActionsAccessor`, `TntExplodeMixin`).

- [ ] **Step 8: Compile and test**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | tail -80`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew test --console=plain` then sum `build/test-results/test/TEST-*.xml` — expect no
regressions from the current baseline (1404 tests at the time this plan was written; confirm
the actual current count first if it's drifted).

- [ ] **Step 9: Commit**

One commit or a few logical ones (e.g. mixin+build.gradle, then the listener shell, then the
wiring) — no AI co-author trailer.

---

### Task B: Melee weapon skills (Swords, Axes, Unarmed, Maces, Spears)

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/EntityDamageListener.java`
  (replace the `applyAttackerWeaponBonus` stub and add its full call tree)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/EntityDamageListenerCounterAttackTest.java`
  (or fold into a broader `EntityDamageListenerMeleeTest.java` — your call on the split, but
  the Counter Attack role-inversion fix below must have its own test, not just be traced by
  eye)

**Interfaces:**
- Consumes: Task A's `EntityDamageListener.java` shell — specifically the
  `applyAttackerWeaponBonus(LivingEntity target, DamageSource source, float amount): float`
  stub signature (read it from the file Task A produced; do not assume the signature, confirm
  it), and `MeleeDamageBonus`/`MeleeDamageBonus.MeleeWeapon` (MC-free, already exists in
  `src/main/java/com/gmail/nossr50/skills/MeleeDamageBonus.java`).
- Produces: the filled-in `applyAttackerWeaponBonus`, `maybeActivateSuperAbility`,
  `maybeProcessSerratedStrikes`, `maybeProcessSkullSplitter`, `maybeProcessRupture`,
  `maybeProcessCripple`, `maybeProcessMomentum`, `maybeProcessCounterAttack`,
  `skillOf`/`classifyMainHand` — Task D's Assassin/Hunter-Mastery work runs *after* this arm in
  the dispatch chain and doesn't call into these methods, so no other task depends on this
  task's internals beyond the stub being filled correctly.

- [ ] **Step 1: Read the Fabric original's melee weapon arm in full**

From the file recovered in Task A's Step 1, read `applyAttackerWeaponBonus` (~L1248-1336),
`maybeActivateSuperAbility` (~L1578), `maybeProcessSerratedStrikes`/`SkullSplitter` (~L1611-1646),
`maybeProcessRupture` (~L1647), `maybeProcessCripple` (~L1663), `maybeProcessMomentum`
(~L1686), `maybeProcessCounterAttack` (~L1714), `skillOf`/`classifyMainHand` (~L1745-1805).

Pay specific attention to two documented invariants:
- Super-ability activation (`maybeActivateSuperAbility`) must run **before** the damage-bonus
  calculation within `applyAttackerWeaponBonus` — the activating hit is itself buffed.
- `maybeProcessCounterAttack`'s gate call must pass `assailant`, not the player, to
  `canCombatSkillsTrigger` — a previously-fixed upstream bug (role inversion) that must not be
  silently reverted during translation. Read the original's comment at this call site (~L1703)
  for the full explanation before writing the ported version.
- `classifyMainHand`'s Spears arm: don't assume it's still missing from any stale comment in an
  older layer of history — verify against the real 1.21.1 merged jar that spear items exist and
  the arm is present and correct, the same way the original itself had to re-verify this once
  (its own comment warns about exactly this trap).

- [ ] **Step 2: Write a failing test for the Counter Attack ordering fix**

```java
package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// Imports of the real assailant/player types and mock setup follow this project's established
// pattern from other neoforge/listeners/*Test.java files — read one for the exact mock/fixture
// shape (e.g. PlayerSessionListenerTest.java) before writing this test's setup boilerplate.

class EntityDamageListenerCounterAttackTest {

    @org.junit.jupiter.api.Test
    void counterAttackGateIsCheckedAgainstTheAssailantNotTheDefendingPlayer() {
        // Arrange: a defending player wearing gear that would trigger Counter Attack, being hit
        // by a non-player assailant (e.g. a zombie), with the PvP-only combat-skills switch OFF
        // and the PvE switch ON (or vice versa — whichever combination distinguishes "gated on
        // the assailant" from "gated on the defender" in canCombatSkillsTrigger's real logic;
        // read that method, already MC-free and existing in this codebase, to pick the exact
        // config combination that makes this test fail if the arguments are swapped).
        //
        // Act: invoke EntityDamageListener's package-private Counter Attack entry point directly
        // (per this file's established "package-private for testing" convention from
        // PlayerMovementTracker/PlayerSessionListener) with the mocked assailant and defender.
        //
        // Assert: Counter Attack fires (or doesn't) according to the ASSAILANT's applicable
        // config switch, not the defending player's — proving the fix from the original's L1703
        // comment survived the port.
    }
}
```

(This test's exact body depends on reading `canCombatSkillsTrigger`'s real signature and the
config switches involved — write the concrete assertions once you've read that method, rather
than leaving this skeleton unfilled. The skeleton above establishes the shape and intent; fill
in real mock setup and real assertions before running it.)

- [ ] **Step 3: Run test to verify it fails or doesn't yet compile**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.EntityDamageListenerCounterAttackTest" --console=plain`
Expected: FAIL (method doesn't exist yet, or the stub returns `amount` unchanged so Counter
Attack never fires either way).

- [ ] **Step 4: Implement the melee weapon arm**

Replace the `applyAttackerWeaponBonus` stub with the real translated logic from Step 1,
preserving both documented invariants exactly. Translate every Minecraft API call via javap.

- [ ] **Step 5: Run test to verify it passes**

Run the same test command as Step 3. Expected: PASS.

- [ ] **Step 6: Compile and run the full suite**

`./gradlew compileJava test --console=plain`, sum JUnit XML, confirm no regressions plus your
new test(s) passing.

- [ ] **Step 7: Commit**

No AI co-author trailer.

---

### Task C: Ranged weapon skills (Archery, Crossbows, Tridents)

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/EntityDamageListener.java`
  (replace the `applyProjectileAttackBonus` stub and its call tree)

**Interfaces:**
- Consumes: Task A's `applyProjectileAttackBonus(LivingEntity target, DamageSource source, float amount): float`
  stub signature (confirm from the actual file, don't assume). `Archery`/`Archery`-adjacent
  MC-free classes already exist under `src/main/java/com/gmail/nossr50/skills/archery/`.
- Produces: the filled-in `applyProjectileAttackBonus`, `isCrossbowShot`, `applyArcheryBonus`,
  `distanceXpMultiplier`, `applyPoweredShot`, `applyTridentImpale`, `sicPetsOnRangedHit`.
  Independent of Task B — no shared call sites between the two arms other than both being
  invoked from Task A's already-fixed dispatcher order.

- [ ] **Step 1: Read the Fabric original's ranged arm in full**

From the Task A recovery, read `applyProjectileAttackBonus` (~L924-963, note the ⚠️ comment at
~L440-444 in the dispatcher about why the sic-pets call must stay a separate top-level
dispatcher statement rather than nested inside this method — that ordering lives in Task A's
file, but this task's implementer must not accidentally re-nest it while translating the
surrounding logic), `isCrossbowShot` (~L964), `applyArcheryBonus` (~L978-1017),
`distanceXpMultiplier` (~L1018-1033), `applyPoweredShot` (~L1034-1061), `applyTridentImpale`
(~L1062-1101), `sicPetsOnRangedHit` (~L911-923, calls `CallOfTheWildHandler.attackTarget`,
already ported).

- [ ] **Step 2: Implement the ranged weapon arm**

Translate every method, preserving the widened `ProjectileEntity` (not the narrower
`PersistentProjectileEntity`) type check the original explicitly widened for the sic-pets call
— re-narrowing it would reintroduce the exact bug the original's comment describes (thrown
tridents and non-arrow ranged weapons becoming invisible to Call of the Wild's aggro).
Translate every Minecraft API call via javap — this arm introduces `ArrowEntity`,
`PersistentProjectileEntity`, `ProjectileEntity`, `TridentEntity` as new types beyond what
Task A's shell needed.

- [ ] **Step 3: Compile and run the full suite**

`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && ./gradlew compileJava test --console=plain`,
sum JUnit XML, confirm no regressions.

- [ ] **Step 4: Commit**

No AI co-author trailer.

---

### Task D: Taming (defender half) + Stealth + Hunter

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/EntityDamageListener.java`
  (replace `applyWolfAttackBonus`, `applyAssassin`, `applyHunterMastery` stubs and their call
  trees; fill in `maybeInspect`'s Quarry Sense branch if Task A stubbed it per its Step 5 note)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/EntityDamageListenerHunterTest.java`

**Interfaces:**
- Consumes: Task A's `applyWolfAttackBonus`, `applyAssassin`, `applyHunterMastery` stub
  signatures (confirm from the actual file). `StealthManager` (already exists,
  `src/main/java/com/gmail/nossr50/skills/stealth/`).
- Produces: fully-wired `applyWolfAttackBonus` + `handleWolfDamage` + its 3 defender predicate
  helpers, `applyAssassin` + `recordDamageTaken`/`ticksSinceDamageTaken`, `applyHunterMastery`,
  `isProjectileFrom`, and (if deferred by Task A) Quarry Sense's Hunter half of `maybeInspect`.

- [ ] **Step 1: Resolve the `HunterListener.masteryKeyOf` dependency**

`applyHunterMastery` and Quarry Sense's Hunter half both call
`HunterListener.masteryKeyOf(...)`. Recover `fabric/listeners/HunterListener.java` the same way
as every other deleted file (`git log --all --diff-filter=D --name-only -- 'src/main/java/com/gmail/nossr50/fabric/listeners/HunterListener.java'`,
then `git show <commit>^:...`). Read `masteryKeyOf` specifically. If it's a small, self-contained
static helper with no further undeclared dependencies, port just that one method (and whatever
few lines of state/constants it needs) into
`src/main/java/com/gmail/nossr50/neoforge/listeners/HunterListener.java` — do not port the rest
of `HunterListener.java` (it's a separate file with its own scope, out of this plan). If
`masteryKeyOf` turns out to have deeper dependencies than expected, stop and report
NEEDS_CONTEXT rather than pulling in more of `HunterListener` than this task's scope covers —
in that case, leave `applyHunterMastery`/Quarry-Sense's-Hunter-half as Task A's stub (documented
as deferred to a future dedicated Hunter task) and land the rest of this task (Taming defender
half + Assassin) on its own.

- [ ] **Step 2: Read the Fabric original's Taming-defender and Stealth arms**

From the Task A recovery: `applyWolfAttackBonus` (~L753-851, attacker-side wolf bonus — wait,
confirm from the actual method signature/dispatcher call site whether this is attacker-side or
defender-side; the research this plan is based on found it dispatched from the attacker-bonus
chain, so double check against the real dispatcher order in Task A's shipped file rather than
this plan's summary), `handleWolfDamage` + 3 helper predicates (~L1102-1216, defender-side:
Thick Fur, Environmentally Aware non-FALL, Holy Hound, Shock Proof), `recordDamageTaken`/
`ticksSinceDamageTaken` (~L1379-1411), `applyAssassin` (~L1444-1507).

- [ ] **Step 3: Read the Fabric original's Hunter arm**

`applyHunterMastery` (~L1508-1551), `isProjectileFrom` (~L1552-1610). Read the ⚠️ comment at
~L455-462 in the dispatcher (Task A's file) again in place — confirm `applyHunterMastery` is
still called strictly after `applyAssassin` in the shipped dispatcher before writing this
task's logic, since that ordering is this task's single most important thing to get right and
the whole reason for the test in Step 4.

- [ ] **Step 4: Write the Assassin-before-Hunter-Mastery ordering test**

```java
package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityDamageListenerHunterTest {

    @org.junit.jupiter.api.Test
    void theMasteryBonusIsAddedAfterAssassinMultiplies() {
        // Arrange: a mocked attacking player with both Assassin's backstab multiplier active
        // and a qualifying Hunter Mastery bonus for the target's entity type, and a mocked
        // target entity. Pick concrete numbers: e.g. a base damage of 10.0f, an Assassin
        // multiplier of 2.0x (read StealthManager's real getter for the multiplier shape and
        // use a real achievable value), and a Hunter Mastery flat bonus of 3.0f (read
        // HunterManager's real getter for the bonus shape and use a real achievable value).
        //
        // Act: invoke EntityDamageListener's real onModifyAppliedDamage (or whatever
        // package-private entry point exposes the full dispatch chain for a test, matching this
        // project's established "package-private for testing" convention) with those mocks and
        // the base damage.
        //
        // Assert: the result equals (baseDamage * assassinMultiplier) + hunterMasteryBonus,
        // NOT (baseDamage + hunterMasteryBonus) * assassinMultiplier. The two expressions must
        // differ for the chosen numbers (verify algebraically before finalizing the test's
        // constants) — that's what makes this test able to fail if the two calls are swapped.
    }
}
```

Fill in real mock setup once you've read `StealthManager`/`HunterManager`'s actual bonus-getter
method names and shapes (both are MC-free, already exist in this codebase).

- [ ] **Step 5: Run test to verify it fails**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.EntityDamageListenerHunterTest" --console=plain`
Expected: FAIL (the arms are still stubs, so the result is just `baseDamage` unchanged, not
matching either expression).

- [ ] **Step 6: Implement the Taming-defender, Stealth, and Hunter arms**

Replace the three stubs with the real translated logic from Steps 2-3, preserving the
Assassin-before-Hunter-Mastery order and every other invariant. Translate every Minecraft API
call via javap — this arm introduces `WolfEntity`, `AbstractHorseEntity`, `LlamaEntity`,
`Tameable`, `CreeperEntity`, `LightningEntity` as new types.

- [ ] **Step 7: Run test to verify it passes**

Same command as Step 5. Expected: PASS.

- [ ] **Step 8: Compile and run the full suite**

`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && ./gradlew compileJava test --console=plain`,
sum JUnit XML, confirm no regressions plus new test(s) passing.

- [ ] **Step 9: Commit**

No AI co-author trailer.
