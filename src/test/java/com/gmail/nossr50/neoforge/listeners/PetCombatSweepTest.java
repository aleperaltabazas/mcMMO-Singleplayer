package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.subskills.taming.PetCombatMode;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.platform.SkillAttributeService;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Taming listener plan, Task C part 1: {@link PetCombatSweep}.
 *
 * <p>Mirrors the mocking idiom {@code PlayerMovementTrackerHerdsmansCallTest} established for the
 * sibling {@code EntityTypeTest}-mediated sweep: {@code level.getEntities(...)} is stubbed with
 * {@code doReturn(...).when(level).getEntities(any(EntityTypeTest.class), any(AABB.class),
 * any(Predicate.class))} rather than by supplying real dead/alive entities and letting Mockito run
 * the predicate — Mockito never invokes a stubbed method's predicate argument itself, it only
 * records what was passed, so the eligibility predicates are call-site facts these tests assert on
 * (indirectly, via which pets end up boosted/targeted), not vanilla behaviour exercised directly.
 *
 * <p>The engage-range boost is observed through a real {@link AttributeInstance} (not a further
 * mock) attached via {@code when(pet.getAttribute(Attributes.FOLLOW_RANGE)).thenReturn(...)}, so
 * {@link SkillAttributeService#appliedValue} — the class's own test seam — can read the boost back
 * exactly as production code would.
 */
class PetCombatSweepTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private GeneralConfig generalConfig;
    private ServerPlayer player;
    private ServerLevel level;
    private McMMOPlayer mmoPlayer;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        generalConfig = new GeneralConfig(dir);
        McMMOMod.setGeneralConfig(generalConfig);

        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        level = mock(ServerLevel.class);
        doReturn(level).when(player).level();
        when(player.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 1, 2, 1));
        player.tickCount = 0; // divisible by every interval -- a real field, not stubbable

        mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
    }

    @AfterEach
    void tearDown() {
        if (mmoPlayer != null) {
            UserManager.cleanupPlayer(mmoPlayer);
        }
        McMMOMod.setGeneralConfig(null);
    }

    private void track(PetCombatMode mode) {
        final TamingManager taming = mock(TamingManager.class);
        when(taming.getPetCombatMode()).thenReturn(mode);
        when(mmoPlayer.getTamingManager()).thenReturn(taming);
        UserManager.track(mmoPlayer);
    }

    @SuppressWarnings("unchecked")
    private void stubPack(List<Wolf> pack) {
        doReturn(pack).when(level).getEntities(
                any(EntityTypeTest.class), any(AABB.class), any(Predicate.class));
    }

    @SuppressWarnings("unchecked")
    private void stubCandidatesAfterPack(List<Wolf> pack, List<Mob> candidates) {
        Mockito.doReturn(pack).doReturn(candidates).when(level).getEntities(
                any(EntityTypeTest.class), any(AABB.class), any(Predicate.class));
    }

    private Wolf wolfPet() {
        final Wolf pet = mock(Wolf.class);
        when(pet.isTame()).thenReturn(true);
        when(pet.isOwnedBy(player)).thenReturn(true);
        final AttributeInstance instance =
                new AttributeInstance(Attributes.FOLLOW_RANGE, i -> { });
        instance.setBaseValue(16.0);
        when(pet.getAttribute(Attributes.FOLLOW_RANGE)).thenReturn(instance);
        return pet;
    }

    @Test
    void configDisabledShortCircuitsBeforeTouchingTheWorld() {
        generalConfig = Mockito.spy(generalConfig);
        when(generalConfig.isPetCombatModeEnabled()).thenReturn(false);
        McMMOMod.setGeneralConfig(generalConfig);

        PetCombatSweep.tick(player);

        verify(player, never()).level();
    }

    @Test
    void offIntervalTicksSkip() {
        player.tickCount = 1; // default interval is 20; 1 % 20 != 0

        PetCombatSweep.tick(player);

        verify(player, never()).level();
    }

    @Test
    void aSittingPetIsZeroedRegardlessOfTargetOrMode() {
        track(PetCombatMode.PASSIVE);
        final Wolf pet = wolfPet();
        when(pet.isInSittingPose()).thenReturn(true);
        stubPack(List.of(pet));

        PetCombatSweep.tick(player);

        assertEquals(0.0,
                SkillAttributeService.appliedValue(pet,
                        SkillAttributeService.Managed.TAMING_PET_ENGAGE_RANGE));
    }

    @Test
    void aPetWithALiveTargetIsBoostedToTheConfiguredEngageRange() {
        track(PetCombatMode.PASSIVE); // reach fix applies in both stances
        final Wolf pet = wolfPet();
        when(pet.isInSittingPose()).thenReturn(false);
        final LivingEntity target = mock(Zombie.class);
        when(target.isAlive()).thenReturn(true);
        when(pet.getTarget()).thenReturn(target);
        stubPack(List.of(pet));

        PetCombatSweep.tick(player);

        // Default engage range is 32.0, pet's base FOLLOW_RANGE is 16.0 -> boost of 16.0.
        assertEquals(16.0,
                SkillAttributeService.appliedValue(pet,
                        SkillAttributeService.Managed.TAMING_PET_ENGAGE_RANGE),
                0.0001);
    }

    @Test
    void passiveModeNeverAcquiresANewTarget() {
        track(PetCombatMode.PASSIVE);
        final Wolf pet = wolfPet();
        when(pet.isInSittingPose()).thenReturn(false);
        when(pet.getTarget()).thenReturn(null);
        stubPack(List.of(pet));

        PetCombatSweep.tick(player);

        // Only the pack query ran -- the candidate query is paid for only when it can be used.
        verify(level, org.mockito.Mockito.times(1)).getEntities(
                any(EntityTypeTest.class), any(AABB.class), any(Predicate.class));
        verify(pet, never()).setTarget(any());
    }

    @Test
    void aggressiveModeAcquiresTheNearestEligibleMonsterToThePlayer() {
        track(PetCombatMode.AGGRESSIVE);
        final Wolf pet = wolfPet();
        when(pet.isInSittingPose()).thenReturn(false);
        when(pet.getTarget()).thenReturn(null);

        final Zombie far = mock(Zombie.class);
        when(far.isAlive()).thenReturn(true);
        when(pet.wantsToAttack(eq(far), eq(player))).thenReturn(true);
        when(player.distanceToSqr(far)).thenReturn(100.0);

        final Zombie near = mock(Zombie.class);
        when(near.isAlive()).thenReturn(true);
        when(pet.wantsToAttack(eq(near), eq(player))).thenReturn(true);
        when(player.distanceToSqr(near)).thenReturn(4.0);

        final Zombie ineligible = mock(Zombie.class);
        when(ineligible.isAlive()).thenReturn(true);
        when(pet.wantsToAttack(eq(ineligible), eq(player))).thenReturn(false);

        stubCandidatesAfterPack(List.of(pet), List.of(far, near, ineligible));

        PetCombatSweep.tick(player);

        verify(pet).setTarget(near);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theCandidateQueryExcludesTheWardenAndAnythingThatIsNotAMonster() {
        track(PetCombatMode.AGGRESSIVE);
        final Wolf pet = wolfPet();
        when(pet.isInSittingPose()).thenReturn(false);
        when(pet.getTarget()).thenReturn(null);
        stubPack(List.of(pet));

        PetCombatSweep.tick(player);

        final org.mockito.ArgumentCaptor<Predicate<Mob>> captor =
                org.mockito.ArgumentCaptor.forClass(Predicate.class);
        verify(level, org.mockito.Mockito.times(2)).getEntities(
                any(EntityTypeTest.class), any(AABB.class), captor.capture());
        final Predicate<Mob> candidatePredicate = captor.getAllValues().get(1);

        final Warden warden = mock(Warden.class);
        when(warden.isAlive()).thenReturn(true);
        final Zombie zombie = mock(Zombie.class);
        when(zombie.isAlive()).thenReturn(true);
        final Zombie dead = mock(Zombie.class);
        when(dead.isAlive()).thenReturn(false);
        final Mob notAMonster = mock(Mob.class);
        when(notAMonster.isAlive()).thenReturn(true);

        assertEquals(false, candidatePredicate.test(warden), "the warden must be excluded");
        assertEquals(true, candidatePredicate.test(zombie), "a live monster must be included");
        assertEquals(false, candidatePredicate.test(dead), "a dead monster must be excluded");
        assertEquals(false, candidatePredicate.test(notAMonster),
                "a non-Monster Mob must be excluded");
    }
}
