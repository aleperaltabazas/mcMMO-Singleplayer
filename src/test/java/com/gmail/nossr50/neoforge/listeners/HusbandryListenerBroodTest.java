package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.projectile.ThrownEgg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Husbandry listener plan, Task D: {@link HusbandryListener#onEggHatchRoll} and
 * {@link HusbandryListener#onFullClutchRoll} ({@code Brood}).
 *
 * <h2>Mocking strategy</h2>
 * {@code ThrownEgg} is mocked as a {@code Projectile}, since {@link HusbandryListener#onEggHatchRoll}
 * / {@link HusbandryListener#onFullClutchRoll} only ever need it as one — {@code getOwner()} is the
 * whole surface these methods read.
 */
class HusbandryListenerBroodTest {

    private static final UUID THROWER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(THROWER_ID);
    }

    private static ServerPlayer thrower() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(THROWER_ID);
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
    // onEggHatchRoll
    // =============================================================================================

    @Test
    void aNonZeroRollIsRescuedIntoAHatchOnAWinningBroodRoll() {
        final ServerPlayer player = thrower();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.rollEggHatch()).thenReturn(true);

        final ThrownEgg egg = mock(ThrownEgg.class);
        when(egg.getOwner()).thenReturn(player);

        assertEquals(0, HusbandryListener.onEggHatchRoll(egg, 3));
    }

    @Test
    void aNonZeroRollPassesThroughUnchangedOnALosingBroodRoll() {
        final ServerPlayer player = thrower();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.rollEggHatch()).thenReturn(false);

        final ThrownEgg egg = mock(ThrownEgg.class);
        when(egg.getOwner()).thenReturn(player);

        assertEquals(3, HusbandryListener.onEggHatchRoll(egg, 3));
    }

    @Test
    void aVanillaZeroRollIsLeftAloneWithNoRollAttempted() {
        // Vanilla is already hatching it (nextInt(8) == 0): nothing for Brood to add, and no roll
        // should even be attempted, real player or not.
        final ThrownEgg egg = mock(ThrownEgg.class);
        assertEquals(0, HusbandryListener.onEggHatchRoll(egg, 0));
        verify(egg, never()).getOwner();
    }

    // =============================================================================================
    // onFullClutchRoll
    // =============================================================================================

    @Test
    void aNonZeroRollBecomesAFullClutchOnAWinningRoll() {
        final ServerPlayer player = thrower();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.rollMultipleChicks()).thenReturn(true);

        final ThrownEgg egg = mock(ThrownEgg.class);
        when(egg.getOwner()).thenReturn(player);

        assertEquals(0, HusbandryListener.onFullClutchRoll(egg, 9));
    }

    @Test
    void aNonZeroRollPassesThroughUnchangedOnALosingClutchRoll() {
        final ServerPlayer player = thrower();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.rollMultipleChicks()).thenReturn(false);

        final ThrownEgg egg = mock(ThrownEgg.class);
        when(egg.getOwner()).thenReturn(player);

        assertEquals(9, HusbandryListener.onFullClutchRoll(egg, 9));
    }

    // =============================================================================================
    // The dispenser exclusion -- Projectile#getOwner()
    // =============================================================================================

    @Test
    void aDispensedEggWithNoPlayerOwnerNeverRollsBrood() {
        final ThrownEgg egg = mock(ThrownEgg.class);
        when(egg.getOwner()).thenReturn(null); // dispensed, not thrown by a player.

        assertEquals(5, HusbandryListener.onEggHatchRoll(egg, 5));
        assertEquals(5, HusbandryListener.onFullClutchRoll(egg, 5));
    }

    @Test
    void anEggOwnedByANonPlayerEntityNeverRollsBrood() {
        // getOwner() can return any Entity, not just a ServerPlayer -- e.g. a dispenser-thrown egg
        // whose owner tracking picked up something else, or a modded thrower. Only a real
        // ServerPlayer owner is eligible.
        final ThrownEgg egg = mock(ThrownEgg.class);
        final Entity nonPlayerOwner = mock(Cow.class);
        when(egg.getOwner()).thenReturn(nonPlayerOwner);

        assertEquals(5, HusbandryListener.onEggHatchRoll(egg, 5));
    }

    @Test
    void anEntityThatIsNotAProjectileAtAllNeverRollsBrood() {
        // Structural belt-and-braces: onEggHatchRoll/onFullClutchRoll are typed to take any Entity,
        // and must not throw or misbehave if handed something that is not a Projectile at all.
        final Entity notAProjectile = mock(Cow.class);
        assertEquals(5, HusbandryListener.onEggHatchRoll(notAProjectile, 5));
        assertEquals(5, HusbandryListener.onFullClutchRoll(notAProjectile, 5));
    }

    // =============================================================================================
    // The hatched chick carries no BRED_BY marker
    // =============================================================================================

    /**
     * The acceptance criterion this task must prove directly, per the brief: "a hatched chick is
     * deliberately unmarked" — proved structurally rather than by a vacuous mock verification.
     *
     * <p>The {@code Chicken} {@code ThrownEgg#onHit} spawns is created entirely inside vanilla's own
     * method body — see {@code ThrownEggHatchMixin}'s own javadoc for the decompiled bytecode: the
     * {@code EntityType.CHICKEN.create(...)} call happens <em>after</em> both
     * {@code @ModifyExpressionValue} injection points this class's methods are called from, and the
     * resulting {@code Chicken} is never passed to a mixin handler or back out of {@code onHit} at
     * all. So the direct, non-omission-based proof is a structural one: neither
     * {@link HusbandryListener#onEggHatchRoll} nor {@link HusbandryListener#onFullClutchRoll} even
     * <em>has</em> a parameter a hatched chick could arrive through — both take only the projectile
     * and vanilla's own {@code int} roll — which means neither method could call
     * {@code setData(McMMOAttachments.BRED_BY, ...)} on one without first being handed a reference it
     * structurally cannot receive. Contrast {@link HusbandryListener#claimOffspring} (Task B), whose
     * signature takes an {@code AgeableMob child} for exactly that purpose.
     *
     * <p>Reflection is used (not {@code instanceof} on a hand-read source string) so this fails loudly
     * if a future change ever widened either signature to accept an animal/chick parameter without
     * this test being updated to scrutinise what is then done with it.
     */
    @Test
    void aHatchedChickCarriesNoBredByMarker() throws NoSuchMethodException {
        final java.lang.reflect.Method onEggHatchRoll = HusbandryListener.class
                .getDeclaredMethod("onEggHatchRoll", Entity.class, int.class);
        final java.lang.reflect.Method onFullClutchRoll = HusbandryListener.class
                .getDeclaredMethod("onFullClutchRoll", Entity.class, int.class);

        for (java.lang.reflect.Method broodMethod : new java.lang.reflect.Method[] {onEggHatchRoll,
                onFullClutchRoll}) {
            for (Class<?> paramType : broodMethod.getParameterTypes()) {
                assertFalse(
                        net.minecraft.world.entity.AgeableMob.class.isAssignableFrom(paramType)
                                || Chicken.class.isAssignableFrom(paramType),
                        broodMethod.getName() + " must have no animal/chick parameter for a "
                                + "BRED_BY marker to be attached through -- a hatched chick is "
                                + "deliberately unmarked");
            }
        }

        // Behavioural half of the same proof: driving both rolls to their winning outcome (the only
        // two code paths Brood offers) never touches McMMOAttachments.BRED_BY on anything, since
        // neither method holds a reference to mark in the first place.
        final ServerPlayer player = thrower();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);
        when(husbandry.rollEggHatch()).thenReturn(true);
        when(husbandry.rollMultipleChicks()).thenReturn(true);

        final ThrownEgg egg = mock(ThrownEgg.class);
        when(egg.getOwner()).thenReturn(player);

        assertEquals(0, HusbandryListener.onEggHatchRoll(egg, 3));
        assertEquals(0, HusbandryListener.onFullClutchRoll(egg, 9));
        // No Chicken mock is ever constructed in this test at all -- there is nothing for either
        // call above to have marked, which is the point.
    }
}
