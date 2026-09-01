# Taming Listener (NeoForge) Design

**Scope:** port the four Fabric-era Taming files that are not yet ported: `TamingListener` (K7 tame
XP), `PetCombatModeListener` (the sneak+bone stance toggle), `PetCombatSweep` (the per-player pet
aggression/engage-range sweep), `PetFollowTeleport` (pets follow through a long same-world
teleport). Source, recoverable at `d0764257671576525aedd97308be2f8c6d85e2fd`:
`src/main/java/com/gmail/nossr50/fabric/listeners/{TamingListener,PetCombatModeListener,
PetCombatSweep,PetFollowTeleport}.java`.

**Already ported / already exists on this branch — do not re-derive:**
- `TamingManager.awardTamingXP(String)`, `.getPetCombatMode()`, `.togglePetCombatMode()` — all
  MC-free, unchanged signatures (`src/main/java/com/gmail/nossr50/skills/taming/TamingManager.java`).
- `PetCombatMode` enum (`PASSIVE`/`AGGRESSIVE`, player-wide, stored in profile data — MC-free).
- `PetTargeting.nearestToPlayer(List, ToDoubleFunction)` — MC-free selection helper.
- `ConfigStringUtils.getConfigEntityTypeString(String)`, `Materials.item(String)` — platform
  utilities, unchanged.
- Every config knob: `GeneralConfig.isPetCombatModeEnabled()`, `.getPetCombatModeToggleItem()`,
  `.getPetAggressiveRadius()`, `.getPetEngageRange()`, `.getPetSweepIntervalTicks()`,
  `.arePetsFollowingTeleports()`, `.getPetFollowTeleportRadius()` — all already implemented on this
  branch (`src/main/java/com/gmail/nossr50/config/GeneralConfig.java:608-716`).
- `SkillAttributeService` (platform-shared, `src/main/java/com/gmail/nossr50/platform/
  SkillAttributeService.java`) with a `Managed.TAMING_PET_ENGAGE_RANGE` entry already present —
  confirm the entry exists before using it (implementer's own grep), do not add a second one.
- `PlayerMovementTracker` already has the two exact hook points marked with `OMISSION` comments
  (`src/main/java/com/gmail/nossr50/neoforge/listeners/PlayerMovementTracker.java:260-269`) —
  above the missing-profile early return, in the same relative position Fabric's originals used.
  This is not a coincidence: whoever ported Herdsman's Call there deliberately left these two
  markers as the wiring point for this exact plan.

**Nothing else exists.** `grep -rn "TamingManager\|PetCombatMode\|PetTargeting" src/main/java/
com/gmail/nossr50/neoforge/` turns up only `PlayerMovementTracker`, `CallOfTheWildHandler`, and
`EntityDamageListener` (unrelated matches) — confirmed via investigation for this spec.

## 1. TamingListener — tame XP (Task A)

Fabric drives this off two mixin entry points because vanilla has no "on tamed" event on either
Fabric or NeoForge (confirmed: `jar tf` the NeoForge userdev jar for `AnimalTameEvent` — nothing).
On this Mojang-mapped 1.21.1 tree the two methods are named differently from Fabric/Yarn:

- `TamableAnimal#tame(Player)` (Yarn: `TameableEntity#setTamedBy`) — wolves, cats, parrots.
- `AbstractHorse#tameWithName(Player)` (Yarn: `AbstractHorseEntity#bondWithPlayer`) — horses,
  donkeys, mules, llamas, camels. **Verify the exact call is `tameWithName`, not `tame`, by
  `javap`-ing the merged jar before writing the `@At` target** — Mojang mapping names shift between
  patch versions and this was checked against `neoforge-21.1.248-merged.jar` for this spec, not a
  more recent snapshot.

Both funnel into a single `TamingListener.onEntityTamed(Player owner, Entity tamed)` static method,
ported near-verbatim from Fabric (same body: `instanceof ServerPlayer` gate → `UserManager.getPlayer`
→ `TamingManager` null-guard → `ConfigStringUtils.getConfigEntityTypeString(BuiltInRegistries.
ENTITY_TYPE.getKey(tamed.getType()).getPath())` → `awardTamingXP`). `BuiltInRegistries.ENTITY_TYPE`
replaces Fabric's `Registries.ENTITY_TYPE`; `Player` replaces `PlayerEntity`; `ServerPlayer` replaces
`ServerPlayerEntity`.

Two thin mixins, `TameableAnimalTameMixin` (`@Inject` at `TAIL` of `tame`) and
`AbstractHorseTameMixin` (`@Inject` at `TAIL` of `tameWithName`), each forward `(this, player)` (or
however the local reads once tamed) to `TamingListener.onEntityTamed`. Both are pure `@Inject`
classes on concrete classes, not interfaces — no boot-crash risk.

## 2. PetCombatModeListener — the stance-toggle gesture (Task B)

Fabric's `UseEntityCallback` (fires on both logical sides, ahead of `Entity#interact`, and any
non-`PASS` result cancels further processing) maps to NeoForge's
`PlayerInteractEvent.EntityInteract` — **confirmed by reading
`net/neoforged/neoforge/event/entity/player/PlayerInteractEvent.java` in the extracted NeoForge
sources jar**: `EntityInteract` "is fired on both sides when the player right clicks an entity...
This event's state affects whether `Entity#interact(Player, InteractionHand)` ... are called," with
a settable `cancellationResult` (defaulting to `PASS`) exactly mirroring Fabric's `ActionResult`
contract. This is a genuine, direct event port — **no mixin needed** for the toggle gesture itself
(unlike Alchemy's brew seam, which looked like a direct port and was not; this one really is one —
confirm before implementing by re-reading the same source file, do not take this spec's word alone
as a substitute for the implementer's own check, since that exact overconfidence bit the Alchemy
plan).

Port shape, 1:1 with Fabric:
- `register()`: `NeoForge.EVENT_BUS.addListener(PetCombatModeListener::onEntityInteract)`.
- `onEntityInteract(PlayerInteractEvent.EntityInteract event)`: pull `event.getEntity()` (the
  player — NeoForge's `PlayerEvent` base names it `getEntity()`, not `getPlayer()`; verify against
  `PlayerEvent`'s own source), `event.getHand()`, `event.getTarget()`. If `!isToggleGesture(...)`,
  return without touching the event (leave it un-cancelled — NeoForge's default "let vanilla handle
  it" is the un-cancelled state, the mirror of Fabric's `PASS`).
- If it is the gesture: `event.setCanceled(true)` and `event.setCancellationResult(InteractionResult.
  CONSUME)` on **both** logical sides (client and server) — same "claim on both or on neither" rule
  as Fabric, same reasoning as `RepairSalvageListener`'s already-ported click-claiming pattern
  (`src/main/java/com/gmail/nossr50/neoforge/listeners/RepairSalvageListener.java` — read it for the
  established both-sides idiom on this branch before writing this task).
- Server-side body (`event.getEntity() instanceof ServerPlayer`) is a direct transcription:
  `UserManager.getPlayer` null-guard (send `Profile.PendingLoad`, still cancel), `TamingManager`
  null-guard (log+cancel), then `announce(mmoPlayer, taming.togglePetCombatMode())` +
  `SoundManager.sendSound(..., SoundType.TOOL_READY)`.
- `isToggleGesture`: `hand == InteractionHand.MAIN_HAND`, `isFeatureEnabled()`, `player.isShiftKeyDown()`
  (Mojang name for Yarn's `isSneaking()` — verify), `entity instanceof TamableAnimal pet &&
  pet.isTame() && pet.isOwnedBy(player)` (Mojang names — verify against the merged-jar `javap`
  output in this spec's earlier taming-XP section: `isTame()` confirmed present; `isOwnedBy`
  needs the implementer's own check, Yarn's `isOwner` may or may not carry over verbatim).
- `toggleItem()` / `isFeatureEnabled()`: transcribe verbatim, `Materials.item(name)` unchanged.

## 3. PetCombatSweep — aggression + engage-range (Task C, part 1)

No event, no mixin — a plain per-tick method called from `PlayerMovementTracker`'s existing loop,
exactly where the `OMISSION` comment at line ~267 marks it (`PetCombatSweep.tick(player)`, called
unconditionally on every ticked player, same relative position as Fabric — above the
missing-profile return, because the engage-range reach fix must keep working during a fresh join).

Port is close to 1:1. MC-type renames to verify against the merged jar (do not transcribe Yarn
names):
- `WolfEntity` → `Wolf`; `MobEntity` → `Mob`; `Monster` interface — same name, confirm package
  (`net.minecraft.world.entity.monster.Monster`); `WardenEntity` → `Warden`.
- `world.getEntitiesByClass(Class, Box, Predicate)` → `level.getEntities(EntityTypeTest.forClass
  (...), AABB, Predicate)` — **this exact overload swap already has a precedent on this branch**:
  `HusbandryListener#onLovePlayer` uses `level.getEntities(EntityTypeTest.forClass(Animal.class),
  searchBox, Animal::isAlive)` (`PlayerMovementTracker.java:414`, referenced in its own javadoc at
  ~391-392). Follow that exact idiom for both the pet-pack query and the hostile-candidate query.
- `player.getBoundingBox()` (Mojang) vs Fabric's `getBoundingBox()` — same name here, confirm.
- `EntityAttributes.GENERIC_FOLLOW_RANGE` → confirm the Mojang constant name (`Attributes.
  FOLLOW_RANGE` on 1.21.1 — verify, do not assume Yarn's `GENERIC_` prefix survives).
- `pet.canAttackWithOwner(candidate, player)` — confirm this method still exists with this name on
  `Wolf` in 1.21.1 (it is the one exclusion-delegation the whole class leans on; if renamed or
  removed, that is an Important finding for task review, not a silent substitution).
- `SkillAttributeService.set(pet, Managed.TAMING_PET_ENGAGE_RANGE, value)` — unchanged, already
  exists.

`tick(ServerPlayer player)` signature (Mojang `ServerPlayer`, not `ServerPlayerEntity`); called
directly, not registered as an event listener — it has no `register()` method in Fabric either.

## 4. PetFollowTeleport — teleport-through pets (Task C, part 2)

Also a plain method, called from the same `PlayerMovementTracker.tickPlayer` call site, immediately
adjacent to the `PetCombatSweep.tick` call per the Fabric ordering and the `OMISSION` comment at
line ~260 (`PetFollowTeleport.onPlayerMoved(player, previous, current, sameWorld)`), likewise above
the missing-profile return.

- `TameableEntity` → `TamableAnimal`; `pet.cannotFollowOwner()` — verify name (this is the one
  vanilla predicate the whole class explicitly leans on to avoid re-implementing sit/ride/leash/
  spectator checks — if it is renamed, re-derive the equivalent rather than re-listing the
  conditions by hand, per the class's own stated design intent).
- `pet.tryTeleportToOwner()` / `pet.shouldTryTeleportToOwner()` — verify these survive on
  `TamableAnimal` (Mojang) with these names.
- `world.getEntitiesByClass(TameableEntity.class, Box, Predicate)` → same `EntityTypeTest`-mediated
  `level.getEntities(...)` swap as §3.
- `PositionFlag` (Yarn) → confirm Mojang's equivalent enum for the relocation-flags parameter of
  `Entity#teleport` (an 1.21.1-era 7-arg `teleport(ServerLevel, x, y, z, Set<...>, yaw, pitch)` —
  verify the exact overload and flag-type name against the merged jar; this shape has changed
  across MC versions per the Fabric class's own multi-band warnings, and this port only needs the
  1.21.1 answer).
- `ServerWorld` → `ServerLevel`; `player.getEntityWorld()` (Fabric-era accessor name) → on 1.21.1
  Mojang-mapped this is most likely `player.level()` returning `Level`, cast to `ServerLevel` — do
  **not** transcribe `getEntityWorld()`; that name is a Fabric/Yarn-band artifact per the Fabric
  class's own multi-version warning comment. Verify the real 1.21.1 accessor before writing this.
- `player.isOnGround()` / `player.isInWater()` (Mojang name for Yarn's `isTouchingWater()` — verify)
  — the airborne-fallback guard.
- `Box.of(from, dx, dy, dz)` → `AABB` equivalent constructor — verify (`new AABB(...)` from a
  center point and size, or `AABB.ofSize`).

`isTeleport`, `DEFAULT_ENABLED`, `DEFAULT_RADIUS`, `isEnabled()`, `radius()` are MC-shape-light and
transcribe close to verbatim (swap `Vec3d` → `Vec3`, `ServerPlayerEntity` → `ServerPlayer`).
`PlayerMovementTracker.TELEPORT_DELTA` — confirm this constant already exists on this branch (it is
used by the movement-sweep's own horizontal teleport detection; `isTeleport` here needs the 3D
squared-distance form against the same constant, per the Fabric javadoc's explicit note that
sharing the constant is deliberate).

## Risks / landmines carried over from this project's established list

- **No mixin-interface boot-crash risk here**: both new mixins (`TameableAnimalTameMixin`,
  `AbstractHorseTameMixin`) are `@Inject`-only on concrete classes, not interfaces with any
  concretely-implemented member. Confirmed safe by inspection of the planned shape; still verify
  with `runServer` per the mandatory check below — that check exists precisely because "looks safe
  by inspection" is not the same as "boots clean."
- **The EntityInteract event-vs-mixin question is the one place this spec could be wrong** the same
  way Alchemy's spec was wrong about "no mixin needed" — verify the event fires with the click still
  interceptable (not already resolved by the time it reaches the listener) before committing to the
  no-mixin approach in Task B; if `EntityInteract` turns out to fire too late or lack cancellation
  power in practice, the fallback is a mixin on `Player#interactOn` (`Entity`, `InteractionHand`) —
  the same target `PlayerInteractionStashMixin` already hooks for Husbandry — HEAD-injecting a
  pre-check that returns `InteractionResult.CONSUME` early. Do not silently fall back without
  flagging it in the task report; the plan's Task B acceptance criteria assume the event path works.
- **Two independent search queries per player per sweep** (pack + candidates) — already
  cost-bounded by `getPetSweepIntervalTicks()` in Fabric; carry the same interval-gate over, do not
  drop it "since it's simpler."
- Every mixin's `@At` target and every renamed MC method above needs the implementer's own
  `javap`/sources-jar re-verification, not transcription from this spec's best-effort names — this
  spec's job was to locate the seams and flag which renames are suspect, not to hand-verify every
  single one against bytecode (several are marked "verify" above precisely because they were not
  bytecode-checked for this spec).

## Mandatory verification

Every task ends with: `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee
/tmp/runserver-taming.log | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("` —
plain JUnit never applies mixins.
