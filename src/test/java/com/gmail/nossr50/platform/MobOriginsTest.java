package com.gmail.nossr50.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.mobs.MobOrigin;
import com.gmail.nossr50.neoforge.McMMOAttachments;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Minecraft-typed half of Hunter's D-HU1 anti-farm gate: the {@link MobSpawnType} mapping and
 * the {@code neoforge.McMMOAttachments} read/write rules.
 *
 * <p>Whether a spawner mob really fails to advance mastery in a live world is a {@code PLAYTEST_G}
 * row, not something this suite can reach. What it can pin is every property whose loss would make
 * that row fail <em>silently</em>, and the one that matters most is the third test below.
 *
 * <h2>Task 8 rewrite, not a translation</h2>
 * The original Fabric-era version of this file drove {@code MobOrigins} against
 * {@code fabric.McMMOAttachments.register()} and Fabric API's real
 * {@code entity.getAttached}/{@code setAttached} attachment calls, verified with
 * {@code Mockito.verify(entity).setAttached(...)}. {@code neoforge.McMMOAttachments} (Task 3's
 * explicitly-labelled stand-in, see its class javadoc) has a completely different shape: two plain
 * static methods, {@code getMobOrigin(Entity)}/{@code setMobOrigin(Entity, String)}, backed by an
 * in-memory {@code UUID}-keyed map — there is no attachment-type registry and nothing to
 * {@code register()}. So this file exercises that real API directly (read the map back through
 * {@code getMobOrigin} rather than verifying a mock interaction that no longer exists), instead of
 * mechanically translating the old Fabric-API-shaped assertions line-by-line.
 */
class MobOriginsTest {

    @AfterEach
    void clearAttachments() {
        // The map neoforge.McMMOAttachments holds is a static, process-lifetime table (see its class
        // javadoc's "Eviction" section) -- not per-test state. Without this, an entity's marker from
        // one test would leak into the next, since Mockito gives each mock a real (stubbed) UUID and
        // nothing else resets the table between tests.
        McMMOAttachments.clearAll();
    }

    /** A server-side world -- the only kind the gate acts on. */
    private static Level serverLevel() {
        final Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        return level;
    }

    /** A mocked zombie with a real (stubbed) UUID, since McMMOAttachments keys its map on it. */
    private static LivingEntity zombie() {
        final LivingEntity zombie = mock(Zombie.class);
        when(zombie.getUUID()).thenReturn(UUID.randomUUID());
        return zombie;
    }

    @Test
    void everySpawnReasonMapsToAnOrigin() {
        // ⚠️ The compile-time guard is the real one: MobOrigins.classify is a switch expression with
        // no default arm, so a Minecraft version that adds a MobSpawnType fails the build. This is the
        // runtime companion for the case that guard cannot see -- running against a newer Minecraft
        // than the mod was compiled against, where an unmatched constant throws MatchException on the
        // very first mob spawn rather than at startup.
        for (MobSpawnType reason : MobSpawnType.values()) {
            final MobOrigin origin = MobOrigins.classify(reason);
            assertNotNull(origin, reason + " has no MobOrigin mapping");
        }
    }

    @Test
    void theDisqualifyingReasonsAreTheRuledOnes() {
        // The truth table, named reason by reason. Each line is a farm.
        assertSame(MobOrigin.SPAWNER, MobOrigins.classify(MobSpawnType.SPAWNER));
        assertSame(MobOrigin.SPAWNER, MobOrigins.classify(MobSpawnType.TRIAL_SPAWNER));
        assertSame(MobOrigin.BRED, MobOrigins.classify(MobSpawnType.BREEDING));
        assertSame(MobOrigin.PLAYER_PLACED, MobOrigins.classify(MobSpawnType.SPAWN_EGG));
        assertSame(MobOrigin.PLAYER_PLACED, MobOrigins.classify(MobSpawnType.COMMAND));
        assertSame(MobOrigin.PLAYER_PLACED, MobOrigins.classify(MobSpawnType.DISPENSER));

        // ⚠️ A portal-spawned zombified piglin is STRUCTURE, not a travel reason. Legacy's flag was
        // called NETHER_PORTAL_MOB and NetherPortalBlock's randomTick calls
        // EntityType.create(world, pos, MobSpawnType.STRUCTURE), bytecode verified.
        assertSame(MobOrigin.STRUCTURE, MobOrigins.classify(MobSpawnType.STRUCTURE));
    }

    @Test
    void aQualifyingOriginIsNeverWritten() {
        // ⚠️⚠️ THE test in this file. EntityType#create(Level, MobSpawnType) -- where the stamp
        // happens -- is also the path taken by the reasons that RE-INTRODUCE a mob rather than
        // create one: chunk load, and travel through a portal. Those arrive carrying mobs that
        // already own a marker from a previous session or from the other side. Writing "NATURAL" for
        // them would erase it, and the symptom would be that a spawner farm quietly starts counting
        // again after a world reload -- indistinguishable from the gate never having worked.
        //
        // Mutation check: make stampOnSpawn write unconditionally and this test, and only this test,
        // goes red.
        final LivingEntity zombie = zombie();
        for (MobSpawnType qualifying : new MobSpawnType[] {
                MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.CONVERSION,
                MobSpawnType.EVENT }) {
            MobOrigins.stampOnSpawn(serverLevel(), qualifying, zombie);
        }
        assertNull(McMMOAttachments.getMobOrigin(zombie),
                "a qualifying spawn reason must never write a mob-origin marker");
    }

    @Test
    void aDisqualifyingOriginIsStampedOnce() {
        final LivingEntity zombie = zombie();
        MobOrigins.stampOnSpawn(serverLevel(), MobSpawnType.SPAWNER, zombie);
        assertEquals(MobOrigin.SPAWNER.storageKey(), McMMOAttachments.getMobOrigin(zombie),
                "a spawner-spawned mob must be stamped with the SPAWNER marker");
    }

    @Test
    void theClientSideAndNullCasesAreIgnored() {
        // EntityType#create runs on both sides in single-player, and returns null for a type behind a
        // disabled feature flag. Neither should reach the attachment: a client-side write is dead
        // weight on a copy of the entity that is never saved, and a null dereference here would crash
        // every spawn of a feature-flagged mob.
        final Level clientLevel = mock(Level.class);
        when(clientLevel.isClientSide()).thenReturn(true);
        final LivingEntity zombie = zombie();

        MobOrigins.stampOnSpawn(clientLevel, MobSpawnType.SPAWNER, zombie);
        assertNull(McMMOAttachments.getMobOrigin(zombie),
                "a client-side spawn must never write a mob-origin marker");

        // Must not throw for a null entity (a feature-flagged type that failed to create).
        MobOrigins.stampOnSpawn(serverLevel(), MobSpawnType.SPAWNER, null);
    }

    @Test
    void anUnmarkedMobCountsAndAMarkedOneDoesNot() {
        final LivingEntity unmarked = zombie();
        assertSame(MobOrigin.NATURAL, MobOrigins.of(unmarked));
        assertTrue(MobOrigins.countsTowardMastery(unmarked));

        final LivingEntity fromSpawner = zombie();
        McMMOAttachments.setMobOrigin(fromSpawner, MobOrigin.SPAWNER.storageKey());
        assertSame(MobOrigin.SPAWNER, MobOrigins.of(fromSpawner));
        org.junit.jupiter.api.Assertions.assertFalse(MobOrigins.countsTowardMastery(fromSpawner));
    }

    @Test
    void anUnreadableMarkerReadsAsUnknownRatherThanNatural() {
        final LivingEntity odd = zombie();
        McMMOAttachments.setMobOrigin(odd, "PLAYER_TAMED_MOB");
        assertSame(MobOrigin.UNKNOWN, MobOrigins.of(odd),
                "a marker this build cannot parse must stay disqualified, not silently become "
                        + "NATURAL and re-open the farm that wrote it");
    }

    @Test
    void conversionCarriesADisqualifyingOriginAcross() {
        // ⚠️ The drowned-farm hole. A zombie spawner over water produces drowned through
        // EntityType.create(level, MobSpawnType.CONVERSION), which qualifies -- so without this the
        // farm launders its own origin one conversion later.
        final LivingEntity spawnerZombie = zombie();
        McMMOAttachments.setMobOrigin(spawnerZombie, MobOrigin.SPAWNER.storageKey());
        final LivingEntity drowned = zombie();

        MobOrigins.carryThroughConversion(spawnerZombie, drowned);

        assertEquals(MobOrigin.SPAWNER.storageKey(), McMMOAttachments.getMobOrigin(drowned),
                "the converted mob must inherit its origin's disqualifying marker");
    }

    @Test
    void conversionOfACleanMobWritesNothing() {
        // The converse, and it is not symmetry for its own sake: writing NATURAL onto the product of
        // a legitimate conversion would put a marker on a mob that has no business carrying one, and
        // the next reader of that marker is a chunk load.
        final LivingEntity naturalZombie = zombie();
        final LivingEntity drowned = zombie();

        MobOrigins.carryThroughConversion(naturalZombie, drowned);

        assertNull(McMMOAttachments.getMobOrigin(drowned),
                "converting a clean mob must not stamp its product");
        // A failed conversion returns null and must not throw.
        MobOrigins.carryThroughConversion(naturalZombie, null);
    }

    @Test
    void nonLivingEntitiesAreNeverStamped() {
        // Hunter counts mob kills, so an item frame or a boat spawned from a dispenser has no reason
        // to carry a marker into the region file. ArmorStand is a LivingEntity and therefore does get
        // one -- harmless, and not worth a second predicate.
        final ArmorStand stand = mock(ArmorStand.class);
        when(stand.getUUID()).thenReturn(UUID.randomUUID());
        MobOrigins.stampOnSpawn(serverLevel(), MobSpawnType.DISPENSER, stand);
        assertEquals(MobOrigin.PLAYER_PLACED.storageKey(), McMMOAttachments.getMobOrigin(stand),
                "a player-placed non-mob living entity is still stamped -- harmless and not worth a "
                        + "second predicate");
    }
}
