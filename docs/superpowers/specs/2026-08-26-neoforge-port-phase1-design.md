# NeoForge 1.21.1 Port — Phase 1 (Walking Skeleton) Design

## Goal

Stand up a NeoForge 1.21.1 build of mcMMO-Singleplayer that loads cleanly in a fresh
integrated-server world and proves the full gameplay pipeline — config load, persistence,
XP gain, level-up, a super ability, save/reload, dimension change, death/respawn — works
end to end, anchored on the Mining skill. Later phases port the remaining 24 skills and ~40
mixins against this proven skeleton.

This is Phase 1 of a larger effort (see the user's original task brief). It does **not**
attempt full feature parity — that is explicitly deferred.

## Source of truth

The `mc/1.21.1` branch (not `master`, which is now on Minecraft 26.2 with a different skill
roster and Mojang-official names). `mc/1.21.1` targets exactly the version this port targets:
`minecraft_version=1.21.1`, `yarn_mappings=1.21.1+build.3`, `fabric_version=0.116.15+1.21.1`.

## Where the port lives

A new branch `neoforge/1.21.1`, cut from `mc/1.21.1`. This keeps the Fabric bands (`master`,
`mc/**`) untouched and keeps full history/diff-ability against the source. The repo's
Fabric-only tooling (`drift-audit.py`, `mixin-allow-audit.py`, `probe-bands.py`, etc.) is not
expected to run on this branch and is not adapted for it in Phase 1.

## What the existing codebase already gives us

`src/test/java/.../platform/PlatformBoundaryGuardTest.java` enforces, as a hard-fail unit
test with no allowlist: **no `net.minecraft` or `net.fabricmc` import outside `fabric/` or
`platform/`.** Measuring the `mc/1.21.1` tree against that boundary:

| Layer | Files | Minecraft/Fabric coupling |
|---|---|---|
| Core gameplay (`skills/`, `datatypes/`, `util/`, `commands/` minus `fabric/commands`, `config/`, `runnables/`, `event/`, `database/`, `locale/`) | 215 | **None.** Pure Java, operates only on `platform/` types. Needs zero changes for this port. |
| `platform/` | 26 | Wraps vanilla Minecraft (yarn-mapped) types directly; 9 files also import the project's own `com.gmail.nossr50.fabric.McMMOMod` (not `net.fabricmc`) as a service locator. |
| `fabric/` | 86 (47 mixins + 5 top-level bootstrap/entrypoint files + `client/`, `commands/`, `listeners/`) | Genuinely Fabric-loader-specific: `ModInitializer`, Fabric API event callbacks, Fabric's Attachment API, Fabric mixin service, ModMenu/Cloth Config client integration. |

So "extract the platform-independent logic" (the user's brief) is **already done** in this
codebase. The port is: leave the 215 core files alone, mechanically re-map the 26
`platform/` files from yarn to NeoForge's mapping (NeoForge 1.21.1 also ships against
Mojang's official mappings, same as vanilla/Fabric-Loom's target namespace — verify exact
mapping set during Task 2), and rewrite the 86 `fabric/` files as a new `neoforge/` package.

No custom networking exists in `fabric/` (no `PayloadTypeRegistry`/`ServerPlayNetworking`
usage) — this is single-player-focused, all gameplay state is server-authoritative, and
nothing needs a custom packet. Persistence is a flat-file store
(`database/FlatFileProfileStore.java`, MC-free) under `<world save>/mcmmo/players/` — no
change needed there either; only the world-save-path lookup in `neoforge/McMMOMod`'s
lifecycle wiring needs a NeoForge equivalent of `WorldSavePath`.

## Fabric API surface actually used in `fabric/` (non-mixin), and its NeoForge equivalent

| Fabric API | Used for | NeoForge equivalent |
|---|---|---|
| `net.fabricmc.api.ModInitializer` | Common entrypoint | `@Mod` on the main class, registered via `neoforge.mods.toml` |
| `ServerLifecycleEvents.SERVER_STARTING` / `STOPPING` | Per-world manager init/teardown | `ServerStartingEvent` / `ServerStoppingEvent` on the NeoForge event bus |
| `ServerTickEvents` | Scheduler tick (`TickScheduler`) | `ServerTickEvent.Post` |
| `PlayerBlockBreakEvents.BEFORE` / `.AFTER` | Gathering XP, bonus drops, Hylian Luck, multi-block plants | No 1:1 NeoForge event; use `BlockEvent.BreakEvent` (cancellable, pre-break) for the BEFORE role, and — since NeoForge has no "after break succeeded" event — re-derive the AFTER role from `BlockEvent.BreakEvent` at `LOWEST` priority after checking `!isCanceled()`, confirming the block is actually gone. **Flagged as a real behavioral gap to resolve in Task 4, not a mechanical rename.** |
| `AttackBlockCallback` | Left-click activation (Berserk, Call of the Wild, ability effects) | `PlayerInteractEvent.LeftClickBlock` |
| `UseBlockCallback` | Right-click activation (ready tools, Herbalism interactions, Blast Mining TNT guard) | `PlayerInteractEvent.RightClickBlock` |
| `UseItemCallback` | Right-click-air activation, Blast Mining remote detonation | `PlayerInteractEvent.RightClickItem` |
| `AttachmentRegistry`/`AttachmentType` | Transient per-entity metadata (`MetadataStore`, e.g. tracked-TNT UUID) | NeoForge's own Data Attachment API (`AttachmentType`, `DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ...)`) — direct conceptual equivalent |
| `ServerLivingEntityEvents`, `ServerPlayerEvents`, `ServerPlayConnectionEvents` | Death/respawn/join/leave hooks (not yet reached in Phase 1's Mining slice, but present in `McMMOMod.java`) | `LivingDeathEvent`, `PlayerEvent.Clone`/`PlayerRespawnEvent`, `PlayerEvent.PlayerLoggedInEvent`/`PlayerLoggedOutEvent` |
| `CommandRegistrationCallback` | `/mcmmo`, `/mcstats`, `/mcability`, admin commands (Brigadier-based, loader-agnostic logic) | `RegisterCommandsEvent` — only the registration call site changes; the Brigadier command trees themselves need no rewrite |

## Mixins actually needed for Phase 1's Mining slice

Tracing `MiningManager`/`BlastMining`'s dependencies through `BlockBreakListener` and
`SuperAbilityListener` (both of which are **shared** across Mining, Woodcutting, Excavation,
Herbalism, and Unarmed — see below), Phase 1 needs:

- `BlockPlaceMixin` — marks a hand-placed block XP-ineligible (anti-farm). Injects into
  `BlockItem#place`. NeoForge has `BlockEvent.EntityPlaceEvent` / `BlockEvent.BlockToolModificationEvent`
  and a direct block-place hook via `PlayerInteractEvent`, but the mixin's precision (exactly
  the write vanilla itself performs, `allow=1`) is worth preserving as a mixin rather than
  reconstructing from a higher-level event that may fire on more paths than intended. **Audit
  in Task 5; keep as a mixin unless a clean event proves equivalent.**
- `TntExplodeMixin` / `ExplosionDropsMixin` — Blast Mining's Bigger Bombs radius and ore-yield
  override. No NeoForge event covers "about to compute explosion radius" or "about to destroy
  these blocks with custom loot" at the needed granularity — audit in Task 5, expect to keep
  as mixins.
- `HoeTillingActionsAccessor` — a `@Accessor` mixin reading vanilla's private tilling-action
  table (used only to detect a till so readying doesn't misfire). No event equivalent exists;
  an `@Accessor` mixin is the correct NeoForge-native choice too (NeoForge mixins support
  `@Accessor` identically).

**Finding, not yet resolved:** `BlockBreakListener` and `SuperAbilityListener` are NOT
Mining-specific — they are the shared gathering/activation dispatch for Mining, Woodcutting,
Excavation, Herbalism, and (for activation) Unarmed. Porting Mining's slice means porting
these two files close to whole. Phase 1 therefore ports the full BEFORE/AFTER block-break
pipeline and the full activation pipeline, but only *verifies* Mining's behaviors in-game
(Super Breaker, Blast Mining, ore bonus drops, anti-farm). Woodcutting/Excavation/
Herbalism/Unarmed will compile and likely function since the shared plumbing is real, but
their own in-game verification and any skill-specific mixins they still need are Phase 2+
work, not claimed as done by Phase 1.

## Build system

Add a NeoForge-targeting build alongside the existing Fabric/Loom one. NeoForge 1.21.1 uses
`net.neoforged.moddev` (ModDevGradle) rather than Loom. Since `neoforge/1.21.1` is a
dedicated branch (not a same-tree sibling module — see "Where the port lives"), `build.gradle`
on this branch is replaced outright with a ModDevGradle configuration targeting
`neoforge_version` matching 1.21.1 (the `21.1.x` line per the user's brief), Java 21 toolchain
(confirmed installed and working in this environment: OpenJDK 21.0.12, Gradle 9.6.0, NeoForge
Maven and the Gradle plugin portal both reachable). `gradle.properties` keeps `mod_version`,
`maven_group`, `archives_base_name`, `junit_version`, `mockito_version`, `snakeyaml_version`
unchanged (all loader-independent); `minecraft_version`, `yarn_mappings`, `loader_version`,
`fabric_version`, `supported_minecraft_versions` are replaced with NeoForge equivalents
(`minecraft_version=1.21.1`, `neo_version=<latest 21.1.x release>`).

## Phase 1 deliverables

1. `neoforge/1.21.1` branch cut from `mc/1.21.1`.
2. NeoForge 1.21.1 project skeleton: `neoforge.mods.toml`, `@Mod` entrypoint, ModDevGradle
   build, compiles clean.
3. `platform/` package ported (26 files): Minecraft API calls updated for NeoForge/1.21.1's
   mapping set; the 9 `fabric.McMMOMod`-coupled files repointed at the new
   `neoforge.McMMOMod` service locator.
4. New `neoforge/` package: entrypoint/lifecycle (`McMMOMod`), config bootstrap, persistence
   wiring (`FlatFileProfileStore`/`PlacedBlockStore` unchanged, only the save-path lookup
   ported), commands (`/mcmmo`, `/mcstats`, `/mcability`, admin commands), the full
   `BlockBreakListener`/`SuperAbilityListener`/`BlastMiningListener` pipeline, and the three
   mixins listed above (or their audited event-based replacements).
5. In-game verification (integrated single-player server, per the user's original checklist,
   scoped to what Phase 1 actually covers):
   - Mod loads without errors on a clean world.
   - Mining XP is awarded for breaking ore; level-up fires.
   - Super Breaker (ready/activate gesture) and Blast Mining (remote detonation, ore
     yield/XP) both work.
   - Save and reload the world; XP/level persist.
   - Dimension change does not reset or duplicate state.
   - Death/respawn does not reset or duplicate state.
   - No Fabric classes in the runtime dependency graph (`jar` task output inspected).
   - No client-only classes loaded server-side (dedicated/integrated server boot check).

## Explicitly deferred (not Phase 1)

- The other 24 skills' listeners/mixins and their in-game verification.
- The remaining ~44 mixins not listed above.
- ModMenu/Cloth Config → NeoForge-native config screen (`IConfigScreenFactory`).
- Full command parity beyond what Mining/admin commands need.
- Any Fabric-band multi-version tooling adaptation.

## Open risk carried into Task 4

`PlayerBlockBreakEvents.BEFORE`/`AFTER` has no exact NeoForge match (see table above). The
BEFORE/AFTER split is load-bearing for Hylian Luck (must fire before the block is gone) and
the multi-block-plant snapshot (must read neighbors before vanilla removes them
synchronously). This needs its own design pass at implementation time, not a rename —
flagged here so it isn't rediscovered cold mid-task.
