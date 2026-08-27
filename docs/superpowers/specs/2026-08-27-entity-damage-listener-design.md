# EntityDamageListener Port — Design

## Goal

Port `fabric/listeners/EntityDamageListener.java` (1901 lines) to NeoForge, unlocking combat
XP and abilities for Swords, Axes, Unarmed, Maces, Spears, Archery, Crossbows, Tridents,
Taming (defender half), Stealth (Assassin), Hunter, and the combat half of Parkour (Roll,
Graceful Roll, Dodge, Smash) and Unarmored (Thorny Skin, XP). This is Phase 2's second slice,
following `PlayerMovementTracker` (Parkour/Swimming/Flying/Stealth's movement half).

## Why this isn't one bounded task

Research (recovering and reading the full Fabric original plus its companion mixin) found
this is not a single Fabric-event listener like every file ported so far. It is driven by
**three hookup points**:

1. A mixin (`fabric/mixin/LivingEntityDamageMixin.java`) with **two** injection points on
   `LivingEntity`:
   - `@ModifyReturnValue(method = "modifyAppliedDamage", allow = 4, at = @At("RETURN"))` — the
     main damage-modification seam. The only place mcMMO can reduce damage (Roll, Dodge, Thick
     Fur, Shock Proof, Demolitions Expertise) or increase it (every weapon-skill on-hit bonus,
     Smash, Assassin, Hunter Mastery).
   - `@Inject(method = "applyArmorToDamage", allow = 1, at = @At("HEAD"))` — the pre-armor
     capture seam, needed only for Unarmored's XP (paid on pre-mitigation damage). Joined to
     the main seam via a `ThreadLocal` stash, safe because the two methods are bytecode-verified
     to be adjacent, unconditional calls on the same entity/thread.
2. One real Fabric event, `ServerLivingEntityEvents.ALLOW_DAMAGE` — a cancel-only veto for the
   three branches that must abort a hit outright rather than reduce it (Unarmed's Arrow
   Deflect, the shared Beast Lore/Quarry Sense bone-inspection dispatcher, Taming's
   Environmentally-Aware FALL arm), because `modifyAppliedDamage` cannot cancel, only reduce.

A faithful NeoForge port needs: one NeoForge Mixin with the same two injection points
(retargeted to official 1.21.1 method names, verified via javap same as every prior mixin port
in this project), plus a NeoForge event registration for the cancel-only branches (candidate:
`LivingIncomingDamageEvent`, **not yet verified** — Task A must confirm the exact class/bus via
source, same discipline as every other event mapping in this project).

### Build prerequisite — unverified, must be resolved in Task A

The mixin uses `@ModifyReturnValue` from **MixinExtras**
(`com.llamalad7.mixinextras.injector.ModifyReturnValue`), a third-party annotation library, not
vanilla Sponge Mixin. On Fabric this ships bundled with Fabric Loader for free. **On this
NeoForge branch, `mixinextras` is not currently declared as a dependency anywhere in
`build.gradle`** (confirmed via grep). A `mixinextras-neoforge-0.5.3.jar` is present in the
local Gradle module cache, but it's unverified whether that's because NeoForge/ModDevGradle
bundles it transitively or because it was pulled down by something unrelated. **Task A's first
step is to determine which and add an explicit dependency if needed** — this is a real
technical prerequisite, not an assumption to carry forward silently.

## Ordering invariants that must survive the port

The dispatcher (`onModifyAppliedDamage`) is itself one long ordering invariant. The most
fragile individual piece: **Assassin must run before Hunter Mastery** in the bonus chain,
because Assassin multiplies the whole running total — a Hunter bonus added before it would be
silently multiplied by the backstab multiplier too. The Fabric source cites a specific test
that used to pin this (`EntityDamageListenerHunterTest#theMasteryBonusIsAddedAfterAssassinMultiplies`)
that was deleted with the rest of `fabric/`'s tests in Task 8 and never re-created. **Task D
must re-create this test as a real deliverable, not an optional nice-to-have.**

Seven more ordering/load-bearing comments exist in the source (Quarry Sense's sneaking gate
differing from Beast Lore's; Call of the Wild's sic-pets call needing to stay a top-level
dispatcher statement, not nested; the full attacker/defender dispatch order inside
`onModifyAppliedDamage`; super-ability activation running *before* the damage-bonus calculation
so the activating hit is itself buffed; a deliberate legacy-Bukkit-ordering deviation for Axes'
AoE that's equivalent-in-effect, not a defect; a previously-fixed upstream role-inversion bug in
Counter Attack's gating that must not be silently reverted during translation; and a
historically-missing Spears classification arm that must not be re-assumed missing without
re-verifying against the real 1.21.1 jar). Each task below inherits responsibility for the
invariants inside its own scope; the full list with line numbers lives in
`/tmp/entity-damage-listener-research.md` (this session's research artifact — not committed;
each task's brief will carry forward the specific invariants it owns).

## Task decomposition

**Task A — Core damage-pipeline plumbing + Parkour's combat half + Unarmored.**
Unlocks: Parkour's Roll/Graceful Roll/Dodge/Smash, Unarmored's XP/Thorny Skin, Mining's
Demolitions Expertise (dependency already satisfied — `BlastMiningListener.detonatorUuid`
exists), Taming's Environmentally-Aware-FALL/Beast-Lore/Arrow-Deflect (the ALLOW_DAMAGE veto
half — small, ships with this task since it rides the same event registration this task must
build anyway). Includes the mixin port, the MixinExtras dependency resolution, the event port,
and the full dispatcher shell with every not-yet-built arm (weapon bonus, wolf bonus,
projectile bonus, Assassin, Hunter Mastery) stubbed as **no-op pass-throughs returning the
damage amount unchanged**, clearly commented as later-task territory — so the ordering
skeleton is correct and independently reviewable before weapon-specific logic lands.

**Task B — Melee weapon skills.** Swords, Axes, Unarmed, Maces, Spears: weapon-bonus
calculation, super-ability activation triggers, Serrated Strikes, Skull Splitter, Rupture,
Cripple, Momentum, Counter Attack (preserving its role-inversion bug fix verbatim). Depends on
Task A's dispatcher shell.

**Task C — Ranged weapon skills.** Archery, Crossbows, Tridents' ranged half (melee Tridents
covered by Task B). Depends on Task A's dispatcher shell. Independent of Task B — either could
run first.

**Task D — Taming (defender half) + Stealth + Hunter.** Wolf-damage defense (Thick Fur,
Environmentally Aware non-FALL, Holy Hound, Shock Proof), Assassin, Hunter Mastery, and the
bone-inspection dispatcher's Hunter half. Has one unresolved dependency: `applyHunterMastery`
and Quarry Sense's Hunter half both need `HunterListener.masteryKeyOf`, from a Fabric listener
file not yet examined or ported. Task D's brief must resolve this the same way
`PlayerMovementTracker` resolved its Taming/Husbandry dependencies: port the helper if it's
small and self-contained, or scope Hunter's damage-based bonus out (deferring to a dedicated
future Hunter task) while still landing Beast Lore (Taming's half, no `HunterListener`
dependency) and Assassin (Stealth, no `HunterListener` dependency). Must re-create the
Assassin-before-Hunter-Mastery ordering test.

## What's explicitly deferred beyond this plan

- `ProjectileListener` (Arrow Retrieval's drop-on-death half — not a real dependency of this
  file, lives elsewhere, out of scope regardless of this plan).
- Any skill this file doesn't touch at all (Fishing, Cooking, Smelting, Repair/Salvage,
  Alchemy, Husbandry's non-combat sub-skills, Taming's attacker-side/pet-follow/pet-combat
  pieces already deferred by `PlayerMovementTracker`).
- Full Hunter porting if Task D scopes it out per the dependency note above.

## Verification

Same discipline as every prior task in this port: every Minecraft API rename verified via
javap/NeoForge source, never recalled from training data. `./gradlew compileJava` and the full
test suite green after every task. Given this file's near-total lack of existing test coverage
on the Fabric side being carried forward (`EntityDamageListener*Test` family all deleted in
Task 8), each task should add focused tests for its own ordering-critical claims, not just
translate logic — Task D's Assassin/Hunter-Mastery test is the one explicitly required by a
source comment, but Tasks A/B/C should use their own judgment about which invariants are
fragile enough to deserve a pinning test versus which are safe to leave to review-time tracing
(matching how `PlayerMovementTracker`'s gap was accepted but flagged, not silently ignored).
