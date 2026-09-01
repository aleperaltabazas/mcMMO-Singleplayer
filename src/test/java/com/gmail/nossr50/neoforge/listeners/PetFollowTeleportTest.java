package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.util.McTestRegistries;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Taming listener plan, Task C part 2: {@link PetFollowTeleport}.
 *
 * <p>Faithful port of Fabric's {@code PetFollowTeleportTest} coverage. Mocking idiom matches
 * {@code PetCombatSweepTest}: {@code level.getEntities(...)} is stubbed with {@code doReturn}
 * rather than driven through a real predicate evaluation.
 */
class PetFollowTeleportTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private GeneralConfig generalConfig;
    private ServerPlayer player;
    private ServerLevel level;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        generalConfig = new GeneralConfig(dir);
        McMMOMod.setGeneralConfig(generalConfig);

        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        doReturn(level).when(player).level();
        when(player.position()).thenReturn(new Vec3(0, 64, 0));
        when(player.getYRot()).thenReturn(0.0f);
        when(player.getXRot()).thenReturn(0.0f);
        when(player.getName()).thenReturn(net.minecraft.network.chat.Component.literal("tester"));
    }

    @AfterEach
    void tearDown() {
        McMMOMod.setGeneralConfig(null);
    }

    @Test
    void nonTeleportMovementIsANoOp() {
        final Vec3 previous = new Vec3(0, 64, 0);
        final Vec3 current = new Vec3(1, 64, 0); // 1 block -- ordinary travel

        PetFollowTeleport.onPlayerMoved(player, previous, current, true);

        verify(player, never()).level();
    }

    @Test
    void crossWorldMovementIsANoOp() {
        final Vec3 previous = new Vec3(0, 64, 0);
        final Vec3 current = new Vec3(200, 64, 0); // far enough to be a teleport by distance alone

        PetFollowTeleport.onPlayerMoved(player, previous, current, false);

        verify(player, never()).level();
    }

    @Test
    void aMissingBaselineIsANoOp() {
        PetFollowTeleport.onPlayerMoved(player, null, new Vec3(0, 64, 0), true);

        verify(player, never()).level();
    }

    @Test
    void disabledConfigIsANoOp() {
        generalConfig = Mockito.spy(generalConfig);
        when(generalConfig.arePetsFollowingTeleports()).thenReturn(false);
        McMMOMod.setGeneralConfig(generalConfig);

        final Vec3 previous = new Vec3(0, 64, 0);
        final Vec3 current = new Vec3(200, 64, 0);

        PetFollowTeleport.onPlayerMoved(player, previous, current, true);

        verify(player, never()).level();
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFollowerPetWithinRadiusIsBroughtAlong() {
        final TamableAnimal pet = wolf();
        when(pet.isAlive()).thenReturn(true);
        when(pet.isTame()).thenReturn(true);
        when(pet.isOwnedBy(player)).thenReturn(true);
        when(pet.unableToMoveToOwner()).thenReturn(false);
        when(pet.shouldTryTeleportToOwner()).thenReturn(false); // vanilla's own search succeeded

        stubEntities(List.of(pet));

        final Vec3 previous = new Vec3(0, 64, 0);
        final Vec3 current = new Vec3(0, 64, 200);

        PetFollowTeleport.onPlayerMoved(player, previous, current, true);

        verify(pet).tryToTeleportToOwner();
        verify(pet, never()).teleportTo(
                any(ServerLevel.class), anyDouble(), anyDouble(), anyDouble(),
                Mockito.<Set<RelativeMovement>>any(), anyFloat(), anyFloat());
    }

    @Test
    void aNonFollowerPetIsSkipped() {
        final TamableAnimal sitting = wolf();
        when(sitting.isAlive()).thenReturn(true);
        when(sitting.isTame()).thenReturn(true);
        when(sitting.isOwnedBy(player)).thenReturn(true);
        when(sitting.unableToMoveToOwner()).thenReturn(true); // sat, ridden or leashed

        assertFalse(PetFollowTeleport.isFollower(sitting, player),
                "a sitting/ridden/leashed pet must not be brought along");
    }

    @Test
    void anAirborneOwnerWithNoLandingSpotLeavesThePetBehindRatherThanDropping() {
        final TamableAnimal pet = wolf();
        when(pet.shouldTryTeleportToOwner()).thenReturn(true); // vanilla's search found nothing
        when(player.onGround()).thenReturn(false);
        when(player.isInWater()).thenReturn(false);

        assertFalse(invokeBring(pet, player),
                "dropping a pet on an airborne owner trades one bug for a worse one");
    }

    @Test
    void isTeleportUsesThreeDimensionalDistance() {
        final Vec3 previous = new Vec3(0, 64, 0);
        // Purely vertical movement past TELEPORT_DELTA must count -- the tracker's own horizontal
        // check would miss this, which is exactly why this class measures in 3D.
        final Vec3 current = new Vec3(0, 64 + PlayerMovementTracker.TELEPORT_DELTA + 1, 0);

        assertTrue(PetFollowTeleport.isTeleport(previous, current));
    }

    @Test
    void isTeleportRejectsOrdinaryTravel() {
        final Vec3 previous = new Vec3(0, 64, 0);
        final Vec3 current = new Vec3(0.2, 64, 0);

        assertFalse(PetFollowTeleport.isTeleport(previous, current));
    }

    private TamableAnimal wolf() {
        final TamableAnimal pet = mock(TamableAnimal.class);
        final EntityType<?> type = mock(EntityType.class);
        when(type.toString()).thenReturn("minecraft:wolf");
        when(pet.getType()).thenReturn((EntityType) type);
        return pet;
    }

    @SuppressWarnings("unchecked")
    private void stubEntities(List<TamableAnimal> pets) {
        doReturn(pets).when(level).getEntities(
                any(EntityTypeTest.class), any(AABB.class), any(java.util.function.Predicate.class));
    }

    /**
     * Drives the private {@code bring(TamableAnimal, ServerPlayer)} path via its only caller,
     * {@link PetFollowTeleport#bringPetsFrom}, with a single stubbed candidate -- avoids widening
     * {@code bring}'s visibility just for testability.
     */
    private boolean invokeBring(TamableAnimal pet, ServerPlayer owner) {
        when(pet.isAlive()).thenReturn(true);
        when(pet.isTame()).thenReturn(true);
        when(pet.isOwnedBy(owner)).thenReturn(true);
        when(pet.unableToMoveToOwner()).thenReturn(false);
        stubEntities(List.of(pet));
        final int moved = PetFollowTeleport.bringPetsFrom(owner, new Vec3(0, 64, 0), 1.0);
        return moved == 1;
    }
}
