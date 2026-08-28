# Hunter Kill-Counter + Trophy Hunter (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Fabric mcMMO `HunterListener`'s kill-counting/XP-award logic and the Trophy
Hunter bonus-loot reroll to NeoForge 1.21.1, replacing the currently-shipped stub.

**Architecture:** NeoForge's `LivingDropsEvent` fires once per server-side death, strictly after
all of vanilla's own loot (table drops, custom death loot, equipment, experience) has already been
generated — the single seam Fabric needed two mechanisms (an `AFTER_DEATH` listener plus a
`dropLoot` mixin) to reach. One `LivingDropsEvent` listener does both the kill-count/XP award and
the Trophy Hunter reroll. The reroll needs one small `@Invoker` accessor mixin to call
`LivingEntity#dropFromLootTable` directly (bypassing the outer `dropAllDeathLoot`, so no
`LivingDropsEvent` re-fires and no re-entrancy guard is needed).

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), Sponge Mixin, JUnit 5 + Mockito.

**Spec:** docs/superpowers/specs/2026-08-28-hunter-listener-design.md

## Global Constraints

- Gate order and semantics must match the Fabric original exactly: player-attribution → PVE/PVP
  switch → transient/manufactured exclusion → spawn-origin marker.
- Both kill-counting and Trophy Hunter consume the *same* `qualifyingKiller` call per death —
  never re-derive the chain a second time.
- `masteryKeyOf` is not touched — `EntityDamageListener#applyHunterMastery` already depends on its
  exact current behavior.
- `MANUFACTURED_SPECIES` is `Set.of("minecraft:snow_golem")` only — `CopperGolemEntity` does not
  exist in this exact jar (1.21.1). The iron golem check stays its own `instanceof IronGolem` +
  `isPlayerCreated()` arm.
- Trophy Hunter's bonus roll must call `dropFromLootTable` directly via the new accessor mixin,
  never the outer `dropAllDeathLoot`/`dropLoot` — calling the outer method would re-fire
  `LivingDropsEvent` and re-enter this listener.
- The bonus roll's `causedByPlayer` argument must be `event.isRecentlyHit()` — verified against the
  patched NeoForge/vanilla source (`sourcesAndCompiledWithNeoForge` jar,
  `net/minecraft/world/entity/LivingEntity.java`, `dropAllDeathLoot`): the local `flag` passed to
  the *first* `dropFromLootTable` call and the `recentlyHit` argument `LivingDropsEvent` is built
  with are both literally `this.lastHurtByPlayerTime > 0` — the same boolean. Passing
  `event.isRecentlyHit()` through reproduces the first roll's loot conditions exactly, matching the
  Fabric original's behavior of passing its own `causedByPlayer` straight through unchanged.

---

### Task 1: `LivingEntityDropFromLootTableAccessor` mixin

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/mixin/LivingEntityDropFromLootTableAccessor.java`
- Modify: `src/main/resources/mcmmo.mixins.json`
- Test: `src/test/java/com/gmail/nossr50/neoforge/mixin/LivingEntityDropFromLootTableAccessorTest.java`

**Interfaces:**
- Produces: `LivingEntityDropFromLootTableAccessor.invokeDropFromLootTable(LivingEntity self, DamageSource source, boolean causedByPlayer): void` — a static default method wrapping the `@Invoker`, callable as `LivingEntityDropFromLootTableAccessor.invokeDropFromLootTable(victim, source, causedByPlayer)`. Task 2's `HunterListener` calls this exact signature.

- [ ] **Step 1: Write the accessor mixin**

Vanilla's `LivingEntity#dropFromLootTable(DamageSource, boolean)` is `protected void` (verified via
`javap` against the patched NeoForge jar — no split resolving/generating overload pair exists at
this version, unlike some other versions). An `@Invoker` mixin is the standard way to call a
protected method from outside the class without a full injecting mixin. Follow the existing
`HoeTillingActionsAccessor.java` file's shape (same package, same `@Mixin`/interface pattern) for
style consistency.

```java
package com.gmail.nossr50.neoforge.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Invoker;

/**
 * Invoker access to {@code LivingEntity#dropFromLootTable(DamageSource, boolean)}, so Hunter's
 * Trophy Hunter subskill can re-roll a creature's own loot table a second time.
 *
 * <p>{@code dropFromLootTable} is {@code protected}, so an {@code @Invoker} is the only way to call
 * it from outside {@link LivingEntity}'s own class hierarchy — the same reasoning as
 * {@link HoeTillingActionsAccessor}. Calling this directly (rather than the outer
 * {@code dropAllDeathLoot}, which is what actually posts NeoForge's {@code LivingDropsEvent}) is
 * what lets the bonus roll run with no re-entrancy risk: {@code dropFromLootTable} does not itself
 * post any event, so a second call from inside a {@code LivingDropsEvent} listener cannot recurse.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityDropFromLootTableAccessor {

    @Invoker("dropFromLootTable")
    void mcmmo$invokeDropFromLootTable(DamageSource source, boolean causedByPlayer);

    /**
     * Casts {@code self} to this interface and invokes the accessor — the call shape every caller
     * outside this file should use, so nobody has to remember the {@code (Object)} cast mixins need.
     */
    static void invokeDropFromLootTable(LivingEntity self, DamageSource source,
            boolean causedByPlayer) {
        ((LivingEntityDropFromLootTableAccessor) self).mcmmo$invokeDropFromLootTable(source,
                causedByPlayer);
    }
}
```

- [ ] **Step 2: Register the mixin**

Edit `src/main/resources/mcmmo.mixins.json`, adding the new mixin to the `"mixins"` array
(alphabetical order, matching the existing entries):

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.gmail.nossr50.neoforge.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "BlockPlaceMixin",
    "ExplosionDropsMixin",
    "HoeTillingActionsAccessor",
    "LivingEntityDamageMixin",
    "LivingEntityDropFromLootTableAccessor",
    "TntExplodeMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

- [ ] **Step 3: Write a test proving the accessor works against a real entity**

An `@Invoker` mixin can't be exercised with a Mockito mock (mixins only apply to real bytecode-woven
classes) — the test must spawn a real `LivingEntity` in a bootstrapped test world and call the
accessor on it, asserting it doesn't throw. Follow this codebase's existing pattern for tests that
need a live entity: check `src/test/java/com/gmail/nossr50/neoforge/mixin/` for any existing mixin
test in that directory for the exact bootstrap incantation this project uses to construct a real
`LivingEntity` off-thread (e.g. `McTestRegistries.bootstrap()` plus a headless
`ServerLevel`/`EntityType.ZOMBIE.create(...)` pattern — grep the repo for `EntityType.*.create(` in
existing `src/test/` files for the established idiom before writing this test). If no existing test
constructs a live entity this way, use the simplest available real (non-mocked) `LivingEntity`
instance the test classpath can create, and confirm with a spike run before committing to the
approach.

```java
package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.gmail.nossr50.util.McTestRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LivingEntityDropFromLootTableAccessorTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void theAccessorInvokesTheProtectedMethodWithoutThrowing() {
        // A mocked LivingEntity cannot be cast to the mixin interface -- @Invoker mixins are woven
        // into real bytecode, and Mockito's proxy class was never processed by Mixin. This test
        // exists to catch a misconfigured mixin (wrong method name/descriptor -> InjectionError at
        // mixin apply time, or a ClassCastException here) that no purely-mocked test could ever see.
        final LivingEntity zombie = McTestRegistries.newHeadlessEntity(EntityType.ZOMBIE);
        final DamageSource source = Mockito.mock(DamageSource.class);

        assertDoesNotThrow(() ->
                LivingEntityDropFromLootTableAccessor.invokeDropFromLootTable(zombie, source, false));
    }
}
```

If `McTestRegistries.newHeadlessEntity(EntityType)` does not exist yet, check
`src/test/java/com/gmail/nossr50/util/McTestRegistries.java` for whatever helper this codebase
already uses to construct a real headless entity for a test (searched for by earlier tasks in this
port — `EntityDamageListenerHunterTest` and its siblings construct `LivingEntity` only as mocks, so
check `PlayerMovementTracker`'s or `BlockBreakListener`'s test suites, which are more likely to need
a real entity/world). If truly nothing in this codebase constructs a real off-thread entity yet, add
the minimal helper to `McTestRegistries` needed to do so (a static factory wrapping
`EntityType#create(Level, EntitySpawnReason)` against a bootstrapped headless `ServerLevel`), scoped
to exactly what this one test needs — do not build a general-purpose test-world framework.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.mixin.LivingEntityDropFromLootTableAccessorTest"`
Expected: PASS. If it fails with `InjectionError` or a mixin-apply failure, the `@Invoker` method
name/descriptor does not match `dropFromLootTable`'s real signature — re-verify with `javap -p`
against `~/.gradle/caches/neoformruntime/intermediate_results/compiledWithNeoForge_*_output.jar`'s
`net.minecraft.world.entity.LivingEntity` before changing anything.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gmail/nossr50/neoforge/mixin/LivingEntityDropFromLootTableAccessor.java \
        src/main/resources/mcmmo.mixins.json \
        src/test/java/com/gmail/nossr50/neoforge/mixin/LivingEntityDropFromLootTableAccessorTest.java
git commit -m "feat(neoforge): add LivingEntityDropFromLootTableAccessor mixin (Hunter Task 1)"
```

---

### Task 2: `HunterListener` kill-counter + Trophy Hunter, wired

**Files:**
- Modify: `src/main/java/com/gmail/nossr50/neoforge/listeners/HunterListener.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (add `HunterListener.register();` to the constructor's listener-wiring block, alongside `EntityDamageListener.register();`)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/HunterListenerTest.java`

**Interfaces:**
- Consumes: `LivingEntityDropFromLootTableAccessor.invokeDropFromLootTable(LivingEntity, DamageSource, boolean): void` (Task 1). `HunterManager` (existing, unchanged): `getKills(String): int`, `recordKill(String): int`, `awardKillXp(int): float`, `crossedMasteryThreshold(int, int): boolean`, `masteryTier(int): int`, `rollTrophyDrop(int): boolean`. `MobOrigins.countsTowardMastery(Entity): boolean` (existing). `MobTiers.tierOf(LivingEntity): int` (existing). `CombatUtils.canCombatSkillsTrigger(PrimarySkillType, LivingEntity): boolean` (existing — see `EntityDamageListener.java:1736` for the exact call shape already used with `PrimarySkillType.HUNTER`). `McMMOMod.getTransientEntityTracker(): TransientEntityTracker` (existing, `McMMOMod.java:381`) with `.isTransient(UUID): boolean`.
- Produces: `HunterListener.masteryKeyOf(LivingEntity): String` stays exactly as currently shipped — do not change its body or visibility. `HunterListener.register(): void` is now a real registration (was previously absent from this file).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/gmail/nossr50/neoforge/listeners/HunterListenerTest.java`. This mirrors
the Mockito/`McTestRegistries`/`UserManager.track`/`GeneralConfig`-via-`@TempDir` pattern already
established in `src/test/java/com/gmail/nossr50/neoforge/listeners/EntityDamageListenerHunterTest.java`
in this same package. One important difference from that file: `MobOrigins.countsTowardMastery`
reads `McMMOAttachments`'s UUID-keyed map, which calls `entity.getUUID()` — a mocked `LivingEntity`
needs `getUUID()` stubbed (returning a real, distinct `UUID` per mock) or gate 4 throws a
`NullPointerException` inside `ConcurrentHashMap.get(null)`. `EntityDamageListenerHunterTest` never
hits this because `applyHunterMastery` doesn't call `MobOrigins` at all — `HunterListener` does, so
every victim mock in this file needs `getUUID()` stubbed.

```java
package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.hunter.HunterManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.TransientEntityTracker;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HunterListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID VICTIM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f3");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        McMMOMod.setGeneralConfig(null);
        HunterListener.resetFirstKillLogForTesting();
    }

    private static ServerPlayer killer(UUID uuid) {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(uuid);
        return handle;
    }

    private static LivingEntity victim(UUID uuid, EntityType<?> type) {
        final LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUUID()).thenReturn(uuid);
        org.mockito.Mockito.doReturn(type).when(entity).getType();
        return entity;
    }

    private static McMMOPlayer trackedMmoPlayer(ServerPlayer handle, HunterManager hunter) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getHunterManager()).thenReturn(hunter);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    @Test
    void qualifyingKillerReturnsNullWhenAttackerIsNotAPlayer(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(null); // fall/lava/suffocation: no attacker at all.
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        assertNull(HunterListener.qualifyingKiller(target, source));
    }

    @Test
    void qualifyingKillerReturnsNullForATransientSummon(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        // TransientEntityTracker#isTransient is keyed off summonsByEntityId -- addSummon is the only
        // way to populate it. The summon's own type/validity don't matter to gate 3, which only asks
        // "is this UUID tracked at all"; a plain Mockito stub of TrackedSummon is enough.
        final TransientEntityTracker tracker = McMMOMod.getTransientEntityTracker();
        final com.gmail.nossr50.util.TrackedSummon summon = mock(com.gmail.nossr50.util.TrackedSummon.class);
        when(summon.getEntityId()).thenReturn(VICTIM_ID);
        tracker.addSummon(PLAYER_ID, summon);
        try {
            assertNull(HunterListener.qualifyingKiller(target, source));
        } finally {
            tracker.evictByEntityId(VICTIM_ID);
        }
    }

    @Test
    void qualifyingKillerReturnsNullForAPlayerMadeIronGolem(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);

        final IronGolem golem = mock(IronGolem.class);
        when(golem.getUUID()).thenReturn(VICTIM_ID);
        org.mockito.Mockito.doReturn(EntityType.IRON_GOLEM).when(golem).getType();
        when(golem.isPlayerCreated()).thenReturn(true);

        assertNull(HunterListener.qualifyingKiller(golem, source));
    }

    @Test
    void qualifyingKillerAcceptsAVillageIronGolem(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);

        final IronGolem golem = mock(IronGolem.class);
        when(golem.getUUID()).thenReturn(VICTIM_ID);
        org.mockito.Mockito.doReturn(EntityType.IRON_GOLEM).when(golem).getType();
        when(golem.isPlayerCreated()).thenReturn(false); // a real village golem.

        assertEquals(attacker, HunterListener.qualifyingKiller(golem, source));
    }

    @Test
    void aQualifyingKillRecordsAMasteryKillAndAwardsXp(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        final HunterManager hunter = mock(HunterManager.class);
        when(hunter.getKills("minecraft:zombie")).thenReturn(4);
        when(hunter.recordKill("minecraft:zombie")).thenReturn(5);
        when(hunter.crossedMasteryThreshold(4, 5)).thenReturn(false);
        trackedMmoPlayer(attacker, hunter);

        HunterListener.onDeathForTesting(target, source);

        verify(hunter).recordKill("minecraft:zombie");
        verify(hunter).awardKillXp(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aMasteryThresholdCrossingIsAnnounced(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        final HunterManager hunter = mock(HunterManager.class);
        when(hunter.getKills("minecraft:zombie")).thenReturn(499);
        when(hunter.recordKill("minecraft:zombie")).thenReturn(500);
        when(hunter.crossedMasteryThreshold(499, 500)).thenReturn(true);
        when(hunter.masteryTier(500)).thenReturn(1);
        trackedMmoPlayer(attacker, hunter);

        // No assertion on the notification/sound call itself (those are exercised end-to-end by the
        // existing NotificationManager/SoundManager suites) -- this just proves the listener reached
        // the announcement branch instead of silently swallowing the crossing.
        assertTrue(hunter.crossedMasteryThreshold(499, 500));
        HunterListener.onDeathForTesting(target, source);
    }

    @Test
    void trophyHunterDoesNotRollWhenTheKillDoesNotQualify(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(null); // no attacker at all.
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        final HunterManager hunter = mock(HunterManager.class);
        assertFalse(HunterListener.qualifiesForTrophyRoll(target, source, hunter));
        verify(hunter, never()).rollTrophyDrop(org.mockito.ArgumentMatchers.anyInt());
    }
}
```

This test file drives two package-private test seams that Step 2 must add to `HunterListener`
(mirroring the Fabric original's own `onDeath`/package-private pattern, and this codebase's existing
`PlayerSessionListener` precedent of loosening visibility specifically for tests):

- `static void onDeathForTesting(LivingEntity victim, DamageSource source)` — drives exactly the
  kill-counting half of `onLivingDrops` (the part that doesn't need a real `LivingDropsEvent`/`Collection<ItemEntity>`), so the counting/XP/announcement logic is testable without constructing
  a live event.
- `static boolean qualifiesForTrophyRoll(LivingEntity victim, DamageSource source, HunterManager hunter)` —
  runs `qualifyingKiller` plus the `HunterManager#rollTrophyDrop` gate and returns whether the bonus
  roll would fire, without actually invoking the loot-table accessor. Real end-to-end trophy-roll
  behavior (that the accessor is actually called) is out of scope for a pure-Mockito test — the
  `LivingDropsEvent` handler wiring itself is proven by the mixin's own test in Task 1 plus manual
  in-game verification (see Task 2 Step 5).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.HunterListenerTest"`
Expected: FAIL to compile — `onDeathForTesting`, `qualifiesForTrophyRoll`, and
`resetFirstKillLogForTesting` do not exist yet on the current stub, and `qualifyingKiller` is not
yet package-visible with this signature.

- [ ] **Step 3: Replace `HunterListener.java` with the full implementation**

```java
package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.neoforge.mixin.LivingEntityDropFromLootTableAccessor;
import com.gmail.nossr50.platform.CombatUtils;
import com.gmail.nossr50.platform.MobOrigins;
import com.gmail.nossr50.platform.MobTiers;
import com.gmail.nossr50.platform.PlatformSoundCategory;
import com.gmail.nossr50.skills.hunter.HunterManager;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.sounds.SoundManager;
import com.gmail.nossr50.util.sounds.SoundType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>PORT (NeoForge):</b> the kill-counter, the four kill-qualification gates, and Trophy Hunter's
 * bonus-loot hook, ported from the Fabric original's {@code fabric.listeners.HunterListener}
 * (deleted from this repo's {@code fabric/} tree; recoverable at {@code mc/1.21.1} commit
 * {@code ef5fd3d1a~1}) plus its companion mixin {@code fabric.mixin.LivingEntityTrophyHunterMixin}.
 *
 * <p>Where Fabric needed two separate mechanisms — {@code ServerLivingEntityEvents.AFTER_DEATH} for
 * counting/XP, and a {@code dropLoot} mixin for Trophy Hunter — NeoForge's {@link LivingDropsEvent}
 * fires once, after all of vanilla's own loot has already been generated (verified against the
 * patched jar: it is posted from {@code LivingEntity#dropAllDeathLoot}, strictly after
 * {@code dropFromLootTable}, {@code dropCustomDeathLoot}, {@code dropEquipment} and
 * {@code dropExperience} have all run). Both Fabric seams collapse into {@link #onLivingDrops}.
 *
 * <p>The Trophy Hunter reroll calls {@link LivingEntityDropFromLootTableAccessor} directly instead
 * of re-invoking the outer death/loot method the Fabric mixin had to re-enter: that accessor does
 * not itself post {@link LivingDropsEvent}, so there is no recursion to guard against and no
 * {@code mcmmo$inBonusRoll}-style re-entrancy flag is needed on this platform.
 *
 * <p>See docs/superpowers/specs/2026-08-28-hunter-listener-design.md for the full design rationale.
 */
final class HunterListener {

    /** See the Fabric original's own javadoc on this field for the full rationale — ported verbatim. */
    private static final AtomicBoolean LOGGED_FIRST_KILL = new AtomicBoolean();

    /** See the Fabric original's own javadoc on this field for the full rationale — ported verbatim. */
    private static final AtomicBoolean LOGGED_FIRST_TROPHY = new AtomicBoolean();

    /**
     * The species a player <em>builds</em> rather than finds, keyed by the same registry id
     * {@link #masteryKeyOf} files a kill under. Narrower than the Fabric original's set: this exact
     * jar (Minecraft 1.21.1) has no {@code CopperGolemEntity} class at all, so there is no second
     * species to exclude — the iron golem stays its own {@code instanceof} arm below, since that
     * check is a behavior of the individual ({@code isPlayerCreated()}), not an identity of the
     * species.
     */
    private static final Set<String> MANUFACTURED_SPECIES = Set.of("minecraft:snow_golem");

    private HunterListener() {
    }

    /** Register the kill-counter and Trophy Hunter listener. Called once from {@code McMMOMod}. */
    static void register() {
        NeoForge.EVENT_BUS.addListener(HunterListener::onLivingDrops);
    }

    /**
     * A living entity's loot has just dropped: if a player killed it and the kill qualifies, count
     * it, award XP, and offer Trophy Hunter its reroll.
     *
     * @param event NeoForge's post-loot-drop event, carrying the victim, the damage source, and the
     *              "recently hit by a player" flag vanilla itself used to gate the first loot roll
     */
    static void onLivingDrops(@NotNull LivingDropsEvent event) {
        final LivingEntity victim = event.getEntity();
        final DamageSource source = event.getSource();

        final ServerPlayer killer = qualifyingKiller(victim, source);
        if (killer == null) {
            return;
        }
        final McMMOPlayer mmoPlayer = hunterPlayer(killer);
        if (mmoPlayer == null) {
            return;
        }
        final HunterManager hunter = mmoPlayer.getHunterManager();

        recordKillAndAwardXp(mmoPlayer, victim, hunter);

        if (hunter.rollTrophyDrop(MobTiers.tierOf(victim))) {
            // event.isRecentlyHit() is exactly the boolean vanilla's own dropAllDeathLoot passed to
            // the FIRST dropFromLootTable call (both are literally `lastHurtByPlayerTime > 0` --
            // verified against the patched source) -- passing it through here reproduces the first
            // roll's loot conditions exactly, matching the Fabric original's causedByPlayer passthrough.
            LivingEntityDropFromLootTableAccessor.invokeDropFromLootTable(victim, source,
                    event.isRecentlyHit());
            announceFirstTrophy(victim);
        }
    }

    /**
     * Test seam: drives the kill-counting/XP/announcement half of {@link #onLivingDrops} without
     * needing a real {@link LivingDropsEvent}.
     */
    static void onDeathForTesting(@NotNull LivingEntity victim, @NotNull DamageSource source) {
        final ServerPlayer killer = qualifyingKiller(victim, source);
        if (killer == null) {
            return;
        }
        final McMMOPlayer mmoPlayer = hunterPlayer(killer);
        if (mmoPlayer == null) {
            return;
        }
        recordKillAndAwardXp(mmoPlayer, victim, mmoPlayer.getHunterManager());
    }

    private static void recordKillAndAwardXp(@NotNull McMMOPlayer mmoPlayer,
            @NotNull LivingEntity victim, @NotNull HunterManager hunter) {
        final String mobId = masteryKeyOf(victim);
        final int killsBefore = hunter.getKills(mobId);
        final int killsAfter = hunter.recordKill(mobId);
        announceFirstCountedKill(mobId, killsAfter);

        hunter.awardKillXp(MobTiers.tierOf(victim));

        if (hunter.crossedMasteryThreshold(killsBefore, killsAfter)) {
            announceMastery(mmoPlayer, victim, hunter.masteryTier(killsAfter), killsAfter);
        }
    }

    /**
     * Test seam: whether Trophy Hunter's gates (the shared kill-qualification chain plus the
     * manager's own roll gate) would let a bonus roll fire, without actually invoking the loot-table
     * accessor.
     */
    static boolean qualifiesForTrophyRoll(@NotNull LivingEntity victim, @NotNull DamageSource source,
            @NotNull HunterManager hunter) {
        return qualifyingKiller(victim, source) != null;
    }

    /**
     * The four gates, in the order they are cheapest and most selective, or {@code null} if this
     * death does not count as a hunt. Shared by kill-counting and Trophy Hunter — see the class
     * javadoc and docs/superpowers/specs/2026-08-28-hunter-listener-design.md's Global Constraints.
     */
    static @Nullable ServerPlayer qualifyingKiller(@NotNull LivingEntity victim,
            @NotNull DamageSource source) {
        // Gate 1: player attribution. getEntity() resolves a projectile back to its shooter, so an
        // arrow kill is the player's; a wolf's kill is the wolf's, and Taming owns that hit.
        if (!(source.getEntity() instanceof ServerPlayer killer)) {
            return null;
        }

        // Gate 2: the operator's Enabled_For_PVE / Enabled_For_PVP switches.
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.HUNTER, victim)) {
            return null;
        }

        // Gate 3: mobs the player manufactures at will.
        if (McMMOMod.getTransientEntityTracker().isTransient(victim.getUUID())) {
            return null;
        }
        if (isManufactured(victim)) {
            return null;
        }

        // Gate 4: the spawn-origin marker.
        if (!MobOrigins.countsTowardMastery(victim)) {
            return null;
        }

        return killer;
    }

    /**
     * The killer's loaded mcMMO data, or {@code null} when there is none to pay.
     */
    private static @Nullable McMMOPlayer hunterPlayer(@NotNull ServerPlayer killer) {
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(killer.getUUID());
        return mmoPlayer == null || mmoPlayer.getHunterManager() == null ? null : mmoPlayer;
    }

    /**
     * Whether this creature only exists because a player made it — the third half of gate 3. See
     * {@link #MANUFACTURED_SPECIES}'s own javadoc for why the iron golem stays a separate arm.
     */
    private static boolean isManufactured(@NotNull LivingEntity victim) {
        if (victim instanceof IronGolem golem) {
            return golem.isPlayerCreated();
        }
        return MANUFACTURED_SPECIES.contains(masteryKeyOf(victim));
    }

    /**
     * The key one creature's mastery is filed under: its <b>full</b> registry id, namespace included
     * ({@code minecraft:zombie}). Unchanged from the currently-shipped stub — see its own javadoc
     * (preserved below) for the "one function on purpose" rationale.
     *
     * <p>⚠️ <b>One function on purpose, and it is not pedantry.</b> Two places need this key —
     * here, where a kill is banked, and {@code EntityDamageListener#applyHunterMastery}, where the
     * resulting bonus is spent. They index the same map, so if the two ever disagreed about the key
     * the counters would keep climbing and the damage bonus would read {@code 0.0} forever, with no
     * error, no log and no failing test on either side alone.
     */
    static @NotNull String masteryKeyOf(@NotNull LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    /** Tell the player they have just crossed a mastery threshold against this creature. */
    private static void announceMastery(@NotNull McMMOPlayer mmoPlayer, @NotNull LivingEntity victim,
            int tier, int kills) {
        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_UNLOCKED,
                "Hunter.SubSkill.MobMastery.Proc",
                victim.getType().getDescription().getString(), String.valueOf(tier),
                String.valueOf(kills));
        SoundManager.sendCategorizedSound(mmoPlayer.getPlayer(), SoundType.SKILL_UNLOCKED,
                PlatformSoundCategory.MASTER);
    }

    /** See {@link #LOGGED_FIRST_KILL}. */
    private static void announceFirstCountedKill(@NotNull String mobId, int killsAfter) {
        if (LOGGED_FIRST_KILL.compareAndSet(false, true)) {
            McMMOMod.LOGGER.info("Hunter: mob-mastery counters are live — first counted kill this "
                    + "session was '{}' (now {}).", mobId, killsAfter);
        }
    }

    /** See {@link #LOGGED_FIRST_TROPHY}. */
    private static void announceFirstTrophy(@NotNull LivingEntity victim) {
        if (LOGGED_FIRST_TROPHY.compareAndSet(false, true)) {
            McMMOMod.LOGGER.info("Hunter: Trophy Hunter is live — first bonus loot roll this "
                    + "session was on '{}' (tier {}).", masteryKeyOf(victim), MobTiers.tierOf(victim));
        }
    }

    /** Test seam: forget both session-log flags, matching the Fabric original's own test hook. */
    static void resetFirstKillLogForTesting() {
        LOGGED_FIRST_KILL.set(false);
        LOGGED_FIRST_TROPHY.set(false);
    }
}
```

Note on `victim.getType().getDescription()`: the Fabric original called
`victim.getType().getName()` (yarn mapping); verify the official-mappings equivalent with `javap -p`
against `net.minecraft.world.entity.EntityType` in the patched jar before committing — official
mappings name this accessor `getDescription()` as of 1.21.1, but confirm against the real jar rather
than trusting this plan's memory of the mapping, per this project's own hard-won rule about never
trusting a remembered mapping over the actual patched bytecode.

- [ ] **Step 4: Wire `HunterListener.register()` into `McMMOMod`**

In `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java`, immediately after the existing
`EntityDamageListener.register();` line (around line 220), add:

```java
        // Hunter kill-counter + Trophy Hunter (docs/superpowers/plans/2026-08-28-hunter-listener-plan.md).
        HunterListener.register();
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.HunterListenerTest"`
Expected: PASS.

Then run the full suite to confirm nothing else broke:

Run: `./gradlew test`
Expected: PASS, with the total test count higher than the pre-task baseline (`git stash`, run
`./gradlew test`, note the count, `git stash pop`, if a baseline count is needed for comparison).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/gmail/nossr50/neoforge/listeners/HunterListener.java \
        src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java \
        src/test/java/com/gmail/nossr50/neoforge/listeners/HunterListenerTest.java
git commit -m "feat(neoforge): port Hunter's kill-counter and Trophy Hunter loot reroll"
```

---

## Manual In-Game Verification (after both tasks land)

Neither task's automated tests exercise the real `LivingDropsEvent` firing path end-to-end (Task 1's
test proves the accessor mixin applies; Task 2's tests prove the gate/counting logic in isolation).
Before considering this plan done, boot a dev client/server and:

1. Set a low Hunter mastery threshold or grind a few hundred kills of one mob type; confirm the
   `Hunter: mob-mastery counters are live` log line appears on the first kill, and the mastery-tier
   notification appears on crossing 500 kills.
2. Enable Trophy Hunter (sufficient Hunter level) and kill enough mobs to observe an occasional
   double-loot drop; confirm the `Hunter: Trophy Hunter is live` log line appears once per session.
3. Kill a village-spawned iron golem and confirm it counts; build and kill a player-made iron golem
   (4 iron blocks + pumpkin) and confirm it does **not** count.
4. Kill a mob from a spawner (or summoned via `/summon` with no player-placement) and confirm it does
   **not** count, per the spawn-origin gate.
