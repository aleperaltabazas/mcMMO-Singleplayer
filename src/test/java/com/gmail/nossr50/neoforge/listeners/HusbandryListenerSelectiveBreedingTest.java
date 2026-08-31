package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.animal.horse.Horse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Husbandry listener plan, Task D: {@link HusbandryListener#beginSelectiveBreeding},
 * {@link HusbandryListener#endSelectiveBreeding} and
 * {@link HusbandryListener#applySelectiveBreedingBias}.
 *
 * <h2>Mocking strategy</h2>
 * Same shape as {@code HusbandryListenerBreedRaiseTest}: entities are Mockito mocks of the real
 * concrete classes ({@code Horse}, {@code Fox}, {@code Turtle}), not hand-rolled test doubles, so
 * the listener code under test is really calling {@code net.minecraft.*} method signatures.
 */
class HusbandryListenerSelectiveBreedingTest {

    private static final UUID BREEDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(BREEDER_ID);
        HusbandryListener.endSelectiveBreeding();
    }

    private static ServerPlayer breeder() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(BREEDER_ID);
        return player;
    }

    private static McMMOPlayer trackedMmoPlayer(ServerPlayer handle, HusbandryManager husbandry) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getHusbandryManager()).thenReturn(husbandry);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    // =============================================================================================
    // beginSelectiveBreeding / applySelectiveBreedingBias
    // =============================================================================================

    @Test
    void appliesTheBreedersBiasWhileTheStashIsOpen() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.applyStatBias(10.0, 0.0, 20.0)).thenReturn(15.0);

        final Animal parent = mock(Horse.class);
        when(parent.getLoveCause()).thenReturn(player);
        final Animal mate = mock(Horse.class);

        HusbandryListener.beginSelectiveBreeding(parent, mate);
        try {
            final double biased = HusbandryListener.applySelectiveBreedingBias(10.0, 0.0, 20.0);
            assertEquals(15.0, biased);
        } finally {
            HusbandryListener.endSelectiveBreeding();
        }
    }

    @Test
    void fallsBackToTheMateWhenTheFedParentHasNoLoveCause() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.applyStatBias(1.0, 0.0, 2.0)).thenReturn(2.0);

        final Animal parent = mock(Horse.class); // no getLoveCause stub -> null.
        final Animal mate = mock(Horse.class);
        when(mate.getLoveCause()).thenReturn(player);

        HusbandryListener.beginSelectiveBreeding(parent, mate);
        try {
            assertEquals(2.0, HusbandryListener.applySelectiveBreedingBias(1.0, 0.0, 2.0));
        } finally {
            HusbandryListener.endSelectiveBreeding();
        }
    }

    @Test
    void passesTheRolledValueThroughUnchangedOutsideAnyStashedBreeding() {
        // No beginSelectiveBreeding call at all -- vanilla's own AI-driven or command-driven
        // breeding, or any horse bred by a player without the sub-skill's data loaded.
        assertEquals(7.5, HusbandryListener.applySelectiveBreedingBias(7.5, 0.0, 100.0));
    }

    @Test
    void aiDrivenBreedingWithNoLovingPlayerOnEitherParentAppliesNoBias() {
        final Animal parent = mock(Horse.class); // no getLoveCause stub -> null on both.
        final Animal mate = mock(Horse.class);

        HusbandryListener.beginSelectiveBreeding(parent, mate);
        try {
            assertEquals(3.0, HusbandryListener.applySelectiveBreedingBias(3.0, 0.0, 10.0));
        } finally {
            HusbandryListener.endSelectiveBreeding();
        }
    }

    @Test
    void endSelectiveBreedingClearsTheStashSoALaterCallSeesNoBias() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.applyStatBias(any(Double.class), any(Double.class), any(Double.class)))
                .thenReturn(999.0);

        final Animal parent = mock(Horse.class);
        when(parent.getLoveCause()).thenReturn(player);
        final Animal mate = mock(Horse.class);

        HusbandryListener.beginSelectiveBreeding(parent, mate);
        HusbandryListener.endSelectiveBreeding();

        assertEquals(5.0, HusbandryListener.applySelectiveBreedingBias(5.0, 0.0, 10.0),
                "a closed stash must not leak a bias into a later, unrelated roll");
    }

    /**
     * The acceptance criterion this task must positively confirm, not assume by analogy to Task B's
     * breed-XP finding: {@code setOffspringAttributes} is declared exactly once, on
     * {@code AbstractHorse}, so every horse-family breeder — including species whose breeding is
     * otherwise driven through their own AI goal rather than the shared trigger, the same shape that
     * made Fox/Turtle bypass the breed-XP funnel — still reaches this stash uniformly. There is no
     * per-species override to route around it: {@link HusbandryListener#husbandryOfBreeder} (the
     * method under {@link HusbandryListener#beginSelectiveBreeding}) has no species branch of any
     * kind, unlike a hypothetical enumeration this port keeps rejecting. Exercised directly against
     * two horse-family mocks — the whole surface {@code setOffspringAttributes} is declared over —
     * rather than by inference from an unrelated skill's fix.
     */
    @Test
    void horseFamilyBreedingHasNoSpeciesBypassUnlikeTheBreedXpFunnel() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.applyStatBias(4.0, 0.0, 8.0)).thenReturn(6.0);

        final Animal parent = mock(Horse.class);
        when(parent.getLoveCause()).thenReturn(player);
        final Animal mate = mock(net.minecraft.world.entity.animal.horse.Donkey.class);

        HusbandryListener.beginSelectiveBreeding(parent, mate);
        try {
            assertEquals(6.0, HusbandryListener.applySelectiveBreedingBias(4.0, 0.0, 8.0),
                    "a horse/donkey pairing must reach the same bias path as a same-species pair, "
                            + "with no per-species branch to bypass it");
        } finally {
            HusbandryListener.endSelectiveBreeding();
        }
    }

    /**
     * Sanity check that {@code husbandryOfBreeder}'s null-safety extends to entity classes entirely
     * outside the horse family (Fox, Turtle) — confirming the method compiles and behaves the same
     * against {@code Animal} regardless of concrete species, since it takes {@code Animal} rather
     * than {@code AbstractHorse} parameters.
     */
    @Test
    void nonHorseAnimalsPassedInStillResolveByLoveCauseAlone() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.applyStatBias(1.0, 0.0, 1.0)).thenReturn(0.5);

        final Animal parent = mock(Fox.class);
        when(parent.getLoveCause()).thenReturn(player);
        final Animal mate = mock(Turtle.class);

        HusbandryListener.beginSelectiveBreeding(parent, mate);
        try {
            assertEquals(0.5, HusbandryListener.applySelectiveBreedingBias(1.0, 0.0, 1.0));
        } finally {
            HusbandryListener.endSelectiveBreeding();
        }
        verify(mate, never()).getLoveCause();
    }
}
