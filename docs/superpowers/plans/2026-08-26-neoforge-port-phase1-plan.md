# NeoForge 1.21.1 Port — Phase 1 (Walking Skeleton) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A NeoForge 1.21.1 build of mcMMO-Singleplayer, on a new `neoforge/1.21.1` branch cut
from `mc/1.21.1`, that loads cleanly in a fresh integrated-server world and proves the full
gameplay pipeline (config, persistence, XP, level-up, a super ability, save/reload, dimension
change, death/respawn) end to end, anchored on the Mining skill.

**Architecture:** Leave the 215 MC/Fabric-free core gameplay files untouched. Mechanically
re-map the 26 `platform/` files from Fabric-Loom's yarn mappings to NeoForge 1.21.1's
mapping set. Rewrite the 86 `fabric/` files (47 mixins + entrypoint/client/commands/listeners)
as a new `neoforge/` package, substituting each Fabric API call for its NeoForge equivalent
per the table in the spec. `BlockBreakListener` and `SuperAbilityListener` are shared across
five skills, not Mining-specific, so porting them is unavoidable for Phase 1 and happens to
carry Woodcutting/Excavation/Herbalism/Unarmed's plumbing along — only Mining is *verified*
in-game this phase.

**Tech Stack:** Java 21 (confirmed installed: OpenJDK 21.0.12), Gradle 9.6.0 (wrapper,
confirmed working), NeoForge 1.21.1 / `net.neoforged.moddev` (ModDevGradle), JUnit 5.11.4,
Mockito, SnakeYAML 2.3 (unchanged from the Fabric branch).

**Spec:** `docs/superpowers/specs/2026-08-26-neoforge-port-phase1-design.md`

## Global Constraints

- Target exactly Minecraft 1.21.1 / NeoForge 21.1.x. No newer Minecraft version anywhere.
- No Fabric Loader, Fabric API, or Bukkit/Spigot/Paper classes in the final runtime
  dependency graph.
- The 215-file core gameplay layer (`skills/`, `datatypes/`, `util/`, `commands/` minus
  `fabric/commands`, `config/`, `runnables/`, `event/`, `database/`, `locale/`) must NOT be
  modified in this phase — if a task seems to require changing one of these files, stop and
  re-derive why; the platform boundary is supposed to make this unnecessary.
- Every task ends with `./gradlew compileJava` (and `compileTestJava` once tests exist)
  passing before it is considered done. Never stack multiple tasks' edits before verifying.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` must be exported (or configured in
  `gradle.properties`/`org.gradle.java.home`) for every Gradle invocation in this environment.
- Never commit unless explicitly asked (this repo's `AGENTS.md`: "Only create commits when
  requested by the user" overrides the default plan-execution commit-per-task habit — stage
  and describe the diff at each step instead of running `git commit`, unless the user has
  said to commit as part of executing this plan).

---

### Task 1: Cut the `neoforge/1.21.1` branch

**Files:** none yet — this is a git operation.

**Interfaces:** N/A.

- [ ] **Step 1: Confirm the working tree is clean and on `mc/1.21.1`**

Run: `git -C /workspace/mcMMO-Singleplayer status --short && git -C /workspace/mcMMO-Singleplayer branch --show-current`
Expected: no output from `status --short`; `mc/1.21.1` from the branch check.

- [ ] **Step 2: Create the branch**

Run: `git -C /workspace/mcMMO-Singleplayer switch -c neoforge/1.21.1`
Expected: `Switched to a new branch 'neoforge/1.21.1'`.

- [ ] **Step 3: Confirm it has no upstream and is not accidentally tracking a Fabric band's remote**

Run: `git -C /workspace/mcMMO-Singleplayer branch -vv | grep neoforge/1.21.1`
Expected: a line for `neoforge/1.21.1` with no `[origin/...]` tracking ref shown.

No commit in this task — it's a checkout, nothing to stage.

---

### Task 2: NeoForge 1.21.1 project skeleton compiles

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle` (full replacement of the Loom configuration)
- Modify: `settings.gradle` (plugin repository for `net.neoforged.moddev`)
- Create: `src/main/resources/META-INF/neoforge.mods.toml`
- Create: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java`
- Delete: `src/main/resources/fabric.mod.json` (superseded by `neoforge.mods.toml`)

**Interfaces:**
- Produces: `com.gmail.nossr50.neoforge.McMMOMod`, a minimal `@Mod("mcmmo")`-annotated class
  with a no-arg constructor, that later tasks extend. Nothing consumes it yet except the
  loader itself.

- [ ] **Step 1: Look up the current NeoForge 1.21.1 version**

Run: `curl -s https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml | grep -oE '21\.1\.[0-9]+' | sort -V | tail -1`
Record the result (e.g. `21.1.XX`) — this is `neo_version` below. Do not guess this number
from memory; the NeoForge 21.1.x line has had many patch releases.

- [ ] **Step 2: Replace the Fabric-specific keys in `gradle.properties`**

Remove these lines (Fabric/Loom-only):
```
minecraft_version=1.21.1
yarn_mappings=1.21.1+build.3
loader_version=0.19.3
fabric_version=0.116.15+1.21.1
supported_minecraft_versions=1.21,1.21.1
```

Add in their place (using the version found in Step 1):
```
# NeoForge toolchain — this branch targets exactly Minecraft 1.21.1.
minecraft_version=1.21.1
neo_version=21.1.XX
```

Leave every other key (`mod_version`, `maven_group`, `archives_base_name`, `junit_version`,
`mockito_version`, `snakeyaml_version`, `org.gradle.*`) unchanged — none of them are
loader-specific. Remove `modmenu_version` / `cloth_config_version` (Fabric-ecosystem client
integrations, out of scope for Phase 1 per the spec's deferred list).

- [ ] **Step 3: Replace `build.gradle`**

Write a `net.neoforged.moddev` build script. Reference NeoForge's official 1.21.1 MDK
`build.gradle` template (fetch it live rather than recalling it from training data — versions
and plugin config keys drift):

Run: `curl -s https://raw.githubusercontent.com/neoforged/MDK/1.21.1/build.gradle`

Adapt the fetched template to this project: keep `group`, `version` read from
`gradle.properties` (`maven_group`, `mod_version`), keep the existing `java { toolchain {
languageVersion = JavaLanguageVersion.of(21) } }` block, keep JUnit 5 + Mockito test
dependencies and `test { useJUnitPlatform() }` from the current file, and point
`neoForge { version = project.neo_version }` (or the fetched template's equivalent key —
follow what the live template actually names it, don't assume).

- [ ] **Step 4: Add the NeoForge Gradle plugin repository to `settings.gradle`**

Run: `curl -s https://raw.githubusercontent.com/neoforged/MDK/1.21.1/settings.gradle`

Merge its `pluginManagement { repositories { ... } }` block (the NeoForged Maven +
Gradle Plugin Portal) into the existing `settings.gradle`, keeping this project's
`rootProject.name` and any existing non-plugin-repository content.

- [ ] **Step 5: Write `neoforge.mods.toml`**

```toml
modLoader = "javafml"
loaderVersion = "[1,)"
license = "GPL-3.0-only"
issueTrackerURL = "https://github.com/Wulfic/mcMMO-Singleplayer/issues"

[[mods]]
modId = "mcmmo"
version = "${file.jarVersion}"
displayName = "mcMMO"
description = "mcMMO reborn as a singleplayer NeoForge mod: RPG skills, leveling, and active abilities for vanilla Minecraft."
authors = "nossr50, mcMMO contributors"

[[dependencies.mcmmo]]
    modId = "neoforge"
    type = "required"
    versionRange = "[21.1,21.2)"
    ordering = "NONE"
    side = "BOTH"

[[dependencies.mcmmo]]
    modId = "minecraft"
    type = "required"
    versionRange = "[1.21.1,1.21.2)"
    ordering = "NONE"
    side = "BOTH"
```

Delete `src/main/resources/fabric.mod.json` in the same step — the loader reads
`META-INF/neoforge.mods.toml` now, and a stray `fabric.mod.json` would misleadingly suggest
this branch is still Fabric-loadable.

- [ ] **Step 6: Write the minimal entrypoint**

```java
package com.gmail.nossr50.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Common (client + server) entry point for the mcMMO NeoForge mod. Replaces
 * {@code com.gmail.nossr50.fabric.McMMOMod}; subsystem wiring (config, persistence,
 * listeners, commands) is added in later tasks.
 */
@Mod("mcmmo")
public final class McMMOMod {

    public McMMOMod(IEventBus modEventBus) {
        // Subsystem registration lands here in later tasks.
    }
}
```

Verify against the fetched MDK template from Step 3/4 whether the constructor signature is
`(IEventBus modEventBus)` or `(IEventBus modEventBus, ModContainer container)` for NeoForge
21.1.x specifically — the constructor-injection shape has changed across NeoForge versions;
copy the exact signature the live template uses rather than the one shown here if they
differ.

- [ ] **Step 7: Compile**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`. This is the first NeoForge dependency resolution, so it will
download the NeoForge userdev artifacts — expect several minutes, run in the background and
wait for the notification rather than polling.

If it fails, do not guess-fix more than 3 times (this repo's own attempt-budget rule) —
stop, paste the exact error, and re-derive from NeoForge's current documentation rather than
patching blind.

---

### Task 3: Port the `platform/` package (26 files) to NeoForge 1.21.1's mappings

**Files:**
- Modify (mapping-only translation, one file per step; each is independently compilable
  once its own imports/signatures are fixed, though the package won't fully compile until
  all 26 are done): every file under `src/main/java/com/gmail/nossr50/platform/` and
  `src/main/java/com/gmail/nossr50/platform/scheduler/`, `.../platform/skills/`,
  `.../platform/text/` (26 files total, listed in the spec).
- The 9 files importing `com.gmail.nossr50.fabric.McMMOMod` (`ParticleEffectUtils.java`,
  `TreeFellerProcessor.java`, `Materials.java`, `MobOrigins.java`, `PlatformPlayer.java`,
  `SkillAttributeService.java`, `ExperienceBarWrapper.java`, `ItemUtils.java`,
  `CombatUtils.java`) get their import changed to `com.gmail.nossr50.neoforge.McMMOMod`
  (created in Task 2) instead.

**Interfaces:**
- Consumes: `com.gmail.nossr50.neoforge.McMMOMod` (Task 2's minimal class — these files may
  reference static accessors on it, e.g. `McMMOMod.getScheduler()`, `McMMOMod.LOGGER`,
  `McMMOMod.getGeneralConfig()`; those accessors must exist as no-op/throwing stubs by the
  end of this task if a `platform/` file needs one that Task 2 didn't add — add the stub with
  a `throw new UnsupportedOperationException("wired in Task 5")` body rather than skipping
  the file, so the package compiles and the gap is visible instead of silent.
- Produces: the same public API surface every `platform/` class had on `mc/1.21.1` (class
  names, method names/signatures unchanged) so the 215-file core gameplay layer needs no
  changes. This is the hard constraint of the task — a signature change here is a signature
  change the entire core layer must absorb, which is exactly what Global Constraints forbid.

- [ ] **Step 1: Confirm NeoForge 1.21.1's mapping set**

NeoForge ships against Mojang's official mappings (`net.minecraft.*` under the
`net.neoforged.moddev` toolchain resolves official names directly — there is no separate
"yarn-equivalent" mapping to fetch). Confirm this by inspecting what Task 2's `compileJava`
actually put on the classpath:

Run: `find ~/.gradle -iname "*client-extra*1.21.1*" -o -iname "*minecraft-*1.21.1*joined*" 2>/dev/null | head -5`

and cross-check one class the codebase uses heavily (`ServerPlayerEntity`, yarn's name for
what Mojang calls `ServerPlayer`):

Run: `find ~/.gradle -iname "*1.21.1*.jar" 2>/dev/null | xargs -I{} sh -c 'javap -cp {} net.minecraft.server.level.ServerPlayer 2>/dev/null | head -1' 2>/dev/null | grep -v '^$' | head -3`

Expected: a `public class net.minecraft.server.level.ServerPlayer` declaration line,
confirming official names are what's on the classpath. This tells you every yarn name in
`platform/` needs translating to its Mojang-official equivalent — there is no shortcut.

- [ ] **Step 2: Build the yarn→official rename table for the classes `platform/` actually uses**

Run, from each `platform/*.java` file's import block:
`grep -h '^import net\.minecraft' src/main/java/com/gmail/nossr50/platform/*.java src/main/java/com/gmail/nossr50/platform/**/*.java | sort -u`

For each distinct `net.minecraft.*` import, look up its official-mappings equivalent via
`javap` against the jar found in Step 1 (search by simple class name across common packages,
e.g. `net.minecraft.entity.player.PlayerEntity` → search for a class ending in
`.PlayerEntity` or `.Player` under `net.minecraft.world.entity.player`). Do not recall yarn↔
official name pairs from training data — this project's own `TODO.md` (§9, the `26.x` port)
found several non-obvious renames (`getEntityWorld` availability, `EntityAttributes` moving
under a prefix) that a memorized mapping would get wrong. Record the table before editing
any file.

- [ ] **Step 3: Port each `platform/` file**

For each of the 26 files, apply the rename table from Step 2 to its imports and any
fully-qualified `net.minecraft.*` references, fix method/field renames the table surfaces,
and (for the 9 files listed above) change the `fabric.McMMOMod` import to
`neoforge.McMMOMod`. Work one file at a time; after each file, run:

`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | grep -A3 "platform/<FileName>.java"`

to confirm that file's own errors are gone (the package as a whole won't compile clean until
all 26 are done — that's expected, don't treat remaining errors in *other* platform files as
this step's failure).

- [ ] **Step 4: Full package compiles**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`, zero errors under `src/main/java/com/gmail/nossr50/platform/`.

---

### Task 4: Port the `neoforge/` entrypoint, config bootstrap, and persistence wiring

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (extend Task 2's stub —
  same file, now fully wired)
- Create: `src/main/java/com/gmail/nossr50/neoforge/ConfigBootstrap.java` (port of
  `fabric/ConfigBootstrap.java`, translating any `net.fabricmc`/`net.minecraft` (yarn) calls;
  this file's own MC coupling, if any, follows the same rename-table approach as Task 3)
- Create: `src/main/java/com/gmail/nossr50/neoforge/McMMOAttachments.java` (port of
  `fabric/McMMOAttachments.java`, translating Fabric's `AttachmentRegistry`/`AttachmentType`
  to NeoForge's Data Attachment API — `DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "mcmmo")`)
- Reference (read, do not modify): `src/main/java/com/gmail/nossr50/fabric/McMMOMod.java`,
  `src/main/java/com/gmail/nossr50/fabric/ConfigBootstrap.java`,
  `src/main/java/com/gmail/nossr50/fabric/McMMOAttachments.java` — these stay on this branch
  as read-only reference until Task 8 deletes the whole `fabric/` package; do not edit them.

**Interfaces:**
- Consumes: `com.gmail.nossr50.database.{FlatFileProfileStore,PlacedBlockStore,ProfileStore}`,
  `com.gmail.nossr50.event.{EventBus,SimpleEventBus}`, `com.gmail.nossr50.config.*` (all
  MC-free, unchanged from `mc/1.21.1`), `com.gmail.nossr50.platform.MetadataStore` and
  `com.gmail.nossr50.platform.scheduler.TickScheduler` (ported in Task 3).
- Produces: `McMMOMod.getGeneralConfig()`, `getExperienceConfig()`, `getTreasureConfig()`,
  `getMaterialMapStore()`, `getScheduler()`, `getCallOfTheWild()`, `LOGGER` — the same static
  accessor surface `fabric.McMMOMod` had, since Task 3's `platform/` files already call these
  by exactly these names.

- [ ] **Step 1: Read the reference file end to end**

Read `src/main/java/com/gmail/nossr50/fabric/McMMOMod.java` in full (it was partially shown
during design — read the rest, especially `onServerStarting`/`onServerStopping` and every
static accessor) before writing the NeoForge version. List every public static accessor it
exposes; Task 3's ported `platform/` files are the ground truth for which ones are actually
called.

- [ ] **Step 2: Port the lifecycle wiring**

Translate `ServerLifecycleEvents.SERVER_STARTING`/`STOPPING` and `ServerTickEvents` to
NeoForge's `ServerStartingEvent`/`ServerStoppingEvent`/`ServerTickEvent.Post`, registered on
the event bus obtained via `NeoForge.EVENT_BUS.register(...)` (game bus, not the mod bus —
confirm which bus each of these three events lives on against NeoForge's current event
listing rather than assuming; `ServerTickEvent` and the two lifecycle events are NeoForge
game-bus events, `@Mod` constructor registration is mod-bus).

- [ ] **Step 3: Port the world-save-path lookup**

`fabric.McMMOMod` uses `net.minecraft.util.WorldSavePath` (yarn name) to locate
`<world save>/mcmmo/players/`. Translate to the official-mappings equivalent found via the
Task 3 rename-table method (search `javap` output for a class serving the same role —
Mojang's mappings keep this concept under `net.minecraft.world.level.storage`).

- [ ] **Step 4: Port `ConfigBootstrap` and `McMMOAttachments`**

Read both reference files fully, then write their NeoForge equivalents per the Interfaces
block above. `McMMOAttachments` is the one file in this task with a real API shape change
(Fabric's `AttachmentRegistry.create(...)` vs. NeoForge's `DeferredRegister`-based
`AttachmentType.builder(...)` — these are not a rename, they're a different registration
pattern) — read NeoForge's current Data Attachments documentation before writing this file
rather than guessing the builder API from memory.

- [ ] **Step 5: Compile**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`.

---

### Task 5: Port the block-break and activation pipeline (Mining + shared plumbing)

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/BlockBreakListener.java`
  (port of `fabric/listeners/BlockBreakListener.java`, 846 lines — translate the
  `PlayerBlockBreakEvents.BEFORE`/`AFTER` split per the spec's flagged open risk: NeoForge
  has no direct two-phase equivalent, so this needs a real design decision, not a rename —
  see Step 1)
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/SuperAbilityListener.java`
  (port of `fabric/listeners/SuperAbilityListener.java`, 740 lines — translate
  `UseBlockCallback`/`UseItemCallback`/`AttackBlockCallback` to
  `PlayerInteractEvent.RightClickBlock`/`RightClickItem`/`LeftClickBlock`)
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/BlastMiningListener.java`
  (port of `fabric/listeners/BlastMiningListener.java` — mostly MC-typed logic with no
  Fabric-event dependency of its own; called from the two listeners above and from Task 6's
  mixins)
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/BlockPlaceMixin.java`,
  `HoeTillingActionsAccessor.java` (ported as-is; NeoForge's Mixin service loads them the
  same way — see Task 6 for the mixin config wiring)
- Reference (read, do not modify): the three `fabric/listeners/*.java` files above and
  `fabric/mixin/BlockPlaceMixin.java`, `fabric/mixin/HoeTillingActionsAccessor.java`

**Interfaces:**
- Consumes: `com.gmail.nossr50.skills.mining.{MiningManager,BlastMining}`,
  `com.gmail.nossr50.skills.herbalism.*`, `com.gmail.nossr50.skills.woodcutting.*`,
  `com.gmail.nossr50.skills.excavation.*`, `com.gmail.nossr50.skills.unarmed.*` (all MC-free,
  unchanged), `com.gmail.nossr50.platform.{BlockUtils,BlockDrops,ItemUtils,Materials,
  MetadataStore}` (ported in Task 3), `com.gmail.nossr50.neoforge.McMMOMod` (Task 4).
- Produces: `BlockBreakListener.register()`, `SuperAbilityListener.register()` — called from
  `neoforge.McMMOMod`'s constructor/setup event in Step 4 below.

- [ ] **Step 1: Resolve the BEFORE/AFTER open risk from the spec**

Read `docs/superpowers/specs/2026-08-26-neoforge-port-phase1-design.md`'s "Open risk carried
into Task 4" section (Mining/Herbalism's use of a pre-break and a confirmed-post-break hook).
NeoForge's `BlockEvent.BreakEvent` fires once, cancellable, before the break — there is no
separate "confirmed after" event. Design decision to make and record (as a short comment at
the top of the ported `BlockBreakListener.java`, and in `.agent/memory/decisions.md` per this
repo's own convention): run the BEFORE-role logic (Hylian Luck, multi-block-plant snapshot)
in `BlockEvent.BreakEvent` before checking `isCanceled()`, then run the AFTER-role logic
(XP, bonus drops) in the same handler after confirming the event was not cancelled by anyone
else and immediately before returning — since NeoForge's `BreakEvent` firing at all already
implies the break will proceed unless something cancels it in the same pass, there is no
separate confirmation point. Note explicitly in the decision record that this changes the
original two-phase ordering guarantee (something else could theoretically cancel between
Fabric's BEFORE and AFTER, which can't happen with one NeoForge event) — call out that this
is a strictly *safer* collapse (fewer intermediate states), not a functional gap, before
proceeding.

- [ ] **Step 2: Port `BlockBreakListener.java`**

Read the reference file in full (already read once during design — re-read for the port
itself). Translate every `net.minecraft.*` (yarn) reference via the Task 3 rename table
(extend the table with any new classes this file introduces, e.g. `ExperienceOrbEntity`).
Replace the `PlayerBlockBreakEvents` registration with the single `BlockEvent.BreakEvent`
handler per Step 1's decision. Keep every private method's logic (Hylian Luck, multi-block
plant traversal, Green Thumb replant, Tree Feller trigger, Giga Drill Breaker, bonus drops,
Lake Raider) unchanged — this task is a mechanical event-API + mapping translation of
existing, already-correct gameplay logic, not a rewrite of it.

- [ ] **Step 3: Port `SuperAbilityListener.java`**

Read the reference file in full (already read once during design). Translate
`UseBlockCallback.EVENT`/`UseItemCallback.EVENT`/`AttackBlockCallback.EVENT` registrations to
`NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, ...)` etc. Fabric's
`ActionResult`/`TypedActionResult` return-value cancellation model maps to NeoForge's
`Event.setCanceled(true)` plus, where the original returned `ActionResult.FAIL`/`SUCCESS` to
suppress vanilla's own follow-up (e.g. "insta-break already destroyed the block, stop vanilla
starting a mining cycle"), `PlayerInteractEvent.RightClickBlock` has `setCancellationResult(...)`
for exactly this — use it rather than approximating with only `setCanceled`. Keep every
private method's logic unchanged (readying, activation gating, Green Terra/Block Cracker/
Berserk effects, the till-detection guard, the offhand rule) — mechanical translation only.

- [ ] **Step 4: Port `BlastMiningListener.java`, `BlockPlaceMixin.java`, `HoeTillingActionsAccessor.java`**

`BlastMiningListener` has no Fabric-event dependency of its own (it's called by the two
listeners above and by mixins) — port is purely the Task 3-style mapping translation.
`BlockPlaceMixin` and `HoeTillingActionsAccessor` are mixins with no Fabric-API surface
beyond the mixin annotations themselves (`@Mixin`, `@Inject`, `@Accessor` are Sponge Mixin,
not Fabric — they need no translation) — only their `net.minecraft.*` type references need
the mapping rename.

- [ ] **Step 5: Wire registration into `neoforge.McMMOMod`**

Add `BlockBreakListener.register()` and `SuperAbilityListener.register()` calls to the
constructor or `FMLCommonSetupEvent` handler (whichever the Task 4 lifecycle wiring uses for
one-time registration — match `fabric.McMMOMod.onInitialize`'s equivalent call site).

- [ ] **Step 6: Compile**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | tail -80`
Expected: `BUILD SUCCESSFUL`. Do not proceed to Task 6 with compile errors outstanding.

---

### Task 6: Wire the NeoForge Mixin service and the two remaining ability mixins

**Files:**
- Create: `src/main/resources/mcmmo.mixins.json` (NeoForge-side mixin config; NeoForge's
  Mixin service uses the same JSON config format Sponge Mixin/Fabric use)
- Modify: `src/main/resources/META-INF/neoforge.mods.toml` (register the mixin config)
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/TntExplodeMixin.java`,
  `ExplosionDropsMixin.java` (ported from `fabric/mixin/`, translating `net.minecraft.*`
  references only — the `@Mixin`/`@Inject` mechanics are unchanged)

**Interfaces:**
- Consumes: `com.gmail.nossr50.neoforge.listeners.BlastMiningListener` (Task 5) —
  `applyBiggerBombs`, `processBlastDrops`.
- Produces: nothing new consumed elsewhere; these are terminal mixin injectors.

- [ ] **Step 1: Confirm NeoForge's mixin config registration mechanism for 1.21.1**

Run: `curl -s https://raw.githubusercontent.com/neoforged/MDK/1.21.1/src/main/resources/META-INF/neoforge.mods.toml`

Check whether 1.21.1's MDK template shows a `[[mixins]]` table (recent NeoForge versions
register mixin configs directly in `neoforge.mods.toml` rather than via a separate
`fml.json`/manifest key some older docs describe). Use whatever the live template shows.

- [ ] **Step 2: Write `mcmmo.mixins.json`**

Port `src/main/resources/mcmmo.mixins.json`'s structure (package, compatibility level,
mixin class list) verbatim except the package prefix, which changes from
`com.gmail.nossr50.fabric.mixin` to `com.gmail.nossr50.neoforge.mixin`, and the mixin class
list, which for Phase 1 is exactly `BlockPlaceMixin`, `HoeTillingActionsAccessor`,
`TntExplodeMixin`, `ExplosionDropsMixin` (the other ~43 mixins are Phase 2+ and must not be
listed here yet — an entry for a class that doesn't exist on this branch fails mixin apply
at game start).

- [ ] **Step 3: Port `TntExplodeMixin.java` and `ExplosionDropsMixin.java`**

Read both reference files (`fabric/mixin/TntExplodeMixin.java`,
`fabric/mixin/ExplosionDropsMixin.java`) in full. Translate `net.minecraft.*` references via
the rename table; the injection points, `@Inject`/`@ModifyVariable`/`@Redirect` annotations
and callback logic are Sponge Mixin API and need no change beyond the type names inside them
resolving to official-mapped classes now instead of yarn ones.

- [ ] **Step 4: Compile and boot-check**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`. Mixin *application* is only verified at runtime (Task 9) — a
clean compile here does not prove the injectors bind correctly, matching this repo's own
documented lesson (`TODO.md`'s "five blind spots" — application is not correctness).

---

### Task 7: Port commands

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/commands/McMMOCommands.java` (port of
  `fabric/commands/McMMOCommands.java`)

**Interfaces:**
- Consumes: the Brigadier `CommandDispatcher<CommandSourceStack>` (NeoForge's context type;
  Fabric's is `CommandDispatcher<ServerCommandSource>` — same Brigadier library, different
  context-object name per loader) provided by `RegisterCommandsEvent`.
- Produces: `/mcmmo`, `/mcstats`, `/mcstats <skill>`, `/mcability`, `/mcrefresh`,
  `/addlevels`, `/addxp` registered on the server.

- [ ] **Step 1: Read the reference file and confirm command-tree portability**

Read `fabric/commands/McMMOCommands.java` in full. Since it already uses
`com.mojang.brigadier` directly (confirmed during design: 1 file, direct Brigadier usage),
the command tree construction (`literal(...)`, `argument(...)`, `requires(...)`,
`.executes(...)`) needs no rewrite — only the registration call site and the source-context
type name change.

- [ ] **Step 2: Port the file**

Replace `CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment)
-> {...})` with a `RegisterCommandsEvent` handler:
`NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {...
event.getDispatcher() ...})`. Replace `ServerCommandSource` with NeoForge's
`CommandSourceStack` throughout (same Brigadier permission-level (`requires(src ->
src.hasPermission(2))`) and player-resolution APIs — NeoForge's `CommandSourceStack` exposes
equivalent accessors; confirm exact method names against the compiler rather than assuming
1:1 naming with `ServerCommandSource`).

- [ ] **Step 3: Wire registration and compile**

Add the registration call to `neoforge.McMMOMod`. Run:
`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava --console=plain 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`.

---

### Task 8: Remove the Fabric-only source and resources from this branch

**Files:**
- Delete: `src/main/java/com/gmail/nossr50/fabric/` (entire package — the 86 files this
  phase's `neoforge/` package replaces; the ~43 not-yet-ported mixins and the ModMenu client
  integration are Phase 2+ work and belong back on `mc/1.21.1`, not carried as dead code here)
- Delete: `src/main/resources/mcmmo.client.mixins.json` (Fabric client-side mixin config;
  no client mixins ported this phase)

**Interfaces:** N/A — deletion only, and only after Task 7 confirms nothing in `neoforge/`
still references anything under `fabric/`.

- [ ] **Step 1: Confirm nothing outside `fabric/` still imports it**

Run: `grep -rl "com.gmail.nossr50.fabric" src/main/java/com/gmail/nossr50/neoforge src/main/java/com/gmail/nossr50/platform 2>/dev/null`
Expected: no output. If anything is found, fix that reference first — it means an earlier
task's port was incomplete.

- [ ] **Step 2: Delete**

Run: `git -C /workspace/mcMMO-Singleplayer rm -r src/main/java/com/gmail/nossr50/fabric src/main/resources/mcmmo.client.mixins.json`

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew compileJava compileTestJava --console=plain 2>&1 | tail -80`
Expected: `BUILD SUCCESSFUL`. Note `compileTestJava` is included here for the first time in
this plan — `src/test/java` may contain Fabric-coupled tests inherited from `mc/1.21.1`
(check `grep -rl "com.gmail.nossr50.fabric\|net.fabricmc" src/test/java` before this step and
delete/port any hits alongside the main-source deletion, so this doesn't surface as a
surprise test-compile failure).

---

### Task 9: In-game verification

**Files:** none — this task runs the built mod, it doesn't edit source. If verification
surfaces a bug, fix it in the file the bug is actually in and re-run this task's steps from
the top; do not special-case the fix into this task's checklist.

**Interfaces:** N/A.

- [ ] **Step 1: Build the jar**

Run: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && ./gradlew build --console=plain 2>&1 | tail -60`
Expected: `BUILD SUCCESSFUL`, a jar under `build/libs/`.

- [ ] **Step 2: Confirm no Fabric classes in the runtime jar**

Run: `unzip -l build/libs/*.jar | grep -i fabric`
Expected: no output (aside from possibly a benign string constant — inspect any hit by hand;
zero actual `net/fabricmc/**.class` entries).

- [ ] **Step 3: Boot a dedicated server**

Run (NeoForge 1.21.1's MDK exposes a `runServer` Gradle task, same as the Fabric branch's
`./gradlew runServer` — confirm this task exists via `./gradlew tasks --all | grep -i server`
first since ModDevGradle's task names may differ from Loom's):
`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /workspace/mcMMO-Singleplayer && timeout 180 ./gradlew runServer --console=plain 2>&1 | tee /tmp/neoforge-boot.log`
Expected: server reaches "Done" with no `ERROR`/`FATAL` lines, mod loaded, config files
generated under `run/config/mcmmo/` (or wherever the ported save-path lookup points), no
mixin application failures logged.

- [ ] **Step 4: Manual gameplay pass (requires a human or an automated player driver)**

Since this environment has no display and no existing headless-player harness on this
branch (the Fabric branch's `gameplay-smoke.sh` depends on fabric-carpet, which doesn't
exist for NeoForge — do not attempt to port that script in this phase), this step is
performed by the user in a real client, or deferred with an explicit note if the user wants
to run it themselves. Checklist to verify, matching the spec's Phase 1 deliverables:
  - Mod loads without errors on a brand-new single-player world.
  - Breaking ore blocks awards Mining XP (check with `/mcstats mining`).
  - A skill level-up fires (use `/addxp mining <amount>` to cross a threshold quickly).
  - Super Breaker: hold a pickaxe, right-click (ready), left-click ore (activate); confirm
    the ability message/sound and bonus-drop behavior.
  - Blast Mining: sneak-right-click aimed at placed TNT with the configured detonator;
    confirm remote detonation, ore yield, and XP award.
  - Save and reload the world; confirm XP/level persisted via `/mcstats mining`.
  - Change dimension (Nether portal); confirm no state reset.
  - Die and respawn; confirm no state reset or duplication.

- [ ] **Step 5: Record the outcome**

Write the verification results (pass/fail per checklist item, with any bugs found and fixed)
to `.agent/memory/state.md` per this repo's own memory convention, so a future session
resuming this work doesn't have to re-derive what Phase 1 actually proved.
