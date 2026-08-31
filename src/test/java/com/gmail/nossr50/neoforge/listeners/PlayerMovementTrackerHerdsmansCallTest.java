package com.gmail.nossr50.neoforge.listeners;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Husbandry listener plan, Task E: {@code PlayerMovementTracker#applyHerdsmansCall}, the per-tick
 * port of the Fabric original's {@code callTheHerd} (found inside Fabric's own {@code
 * listeners/PlayerMovementTracker}, not {@code HusbandryListener} -- see that method's own javadoc
 * for the full search trail).
 *
 * <h2>Mocking strategy</h2>
 * {@code applyHerdsmansCall} is {@code private}; reached via reflection the same way this
 * codebase's other private-method structural tests do (see
 * {@code HusbandryListenerBreedRaiseTest#multiBreedCandidateFilterMirrorsVanillasOwnAcceptanceConditions}),
 * rather than widening its visibility just for testability. {@link ServerLevel#getEntitiesOfClass}
 * is verified with {@code any(Predicate.class)} rather than by supplying real dead/alive animals
 * and letting Mockito's stubbed return value run the real predicate -- Mockito never invokes the
 * predicate argument itself, it only records what was passed, so the {@code Animal::isAlive} filter
 * is a call-site fact this asserts on, not vanilla behaviour this test can exercise directly. This
 * mirrors {@code HusbandryListenerBreedRaiseTest#onLovePlayerDoesNothingWhenMultiBreedIsNotUnlocked}'s
 * own verification shape for the sibling Multi-Breed sweep.
 */
class PlayerMovementTrackerHerdsmansCallTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private static Method applyHerdsmansCall() throws NoSuchMethodException {
        final Method method = PlayerMovementTracker.class.getDeclaredMethod(
                "applyHerdsmansCall", ServerPlayer.class, McMMOPlayer.class);
        method.setAccessible(true);
        return method;
    }

    private static void invoke(ServerPlayer player, McMMOPlayer mmoPlayer) throws Exception {
        applyHerdsmansCall().invoke(null, player, mmoPlayer);
    }

    @Test
    void doesNothingWhenTheHusbandryManagerIsMissing() throws Exception {
        final ServerPlayer player = mock(ServerPlayer.class);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getHusbandryManager()).thenReturn(null);

        invoke(player, mmoPlayer);

        // No level/bounding-box read at all -- proves this returns before touching the world.
        verify(player, never()).level();
    }

    @Test
    void costsNothingWhileTheCallIsNotSounding() throws Exception {
        final ServerPlayer player = mock(ServerPlayer.class);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        when(mmoPlayer.getHusbandryManager()).thenReturn(husbandry);
        when(husbandry.getHerdRadius()).thenReturn(0.0);

        invoke(player, mmoPlayer);

        // The overwhelmingly common case per-tick: one boolean read, no entity sweep at all.
        verify(player, never()).level();
    }

    @Test
    void doesNothingOutsideAServerLevel() throws Exception {
        final ServerPlayer player = mock(ServerPlayer.class);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        when(mmoPlayer.getHusbandryManager()).thenReturn(husbandry);
        when(husbandry.getHerdRadius()).thenReturn(10.0);
        // A plain client-side Level, not a ServerLevel.
        final Level clientLevel = mock(Level.class);
        doReturn(clientLevel).when(player).level();

        invoke(player, mmoPlayer);

        verify(player, never()).getBoundingBox();
    }

    @SuppressWarnings("unchecked")
    @Test
    void sweepsAnAabbExpandedByTheHerdRadiusAndRedirectsOnlyIdleAnimals() throws Exception {
        final ServerPlayer player = mock(ServerPlayer.class);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        when(mmoPlayer.getHusbandryManager()).thenReturn(husbandry);
        when(husbandry.getHerdRadius()).thenReturn(12.0);

        final ServerLevel level = mock(ServerLevel.class);
        doReturn(level).when(player).level();
        final AABB playerBox = new AABB(0, 0, 0, 1, 2, 1);
        when(player.getBoundingBox()).thenReturn(playerBox);

        final Animal idle = mock(Cow.class);
        final PathNavigation idleNav = mock(PathNavigation.class);
        when(idle.getNavigation()).thenReturn(idleNav);
        when(idleNav.isDone()).thenReturn(true);

        final Animal busy = mock(Cow.class);
        final PathNavigation busyNav = mock(PathNavigation.class);
        when(busy.getNavigation()).thenReturn(busyNav);
        when(busyNav.isDone()).thenReturn(false);

        final List<Animal> found = List.of(idle, busy);
        Mockito.doReturn(found).when(level)
                .getEntities(any(EntityTypeTest.class), any(AABB.class), any(java.util.function.Predicate.class));

        invoke(player, mmoPlayer);

        // Idle animal is walked to the player at the tempt-goal-matching follow speed.
        verify(idleNav).moveTo(eq(player), eq(1.25));
        // An animal already mid-path (fleeing, or walking to its own mate) is left alone.
        verify(busyNav, never()).moveTo(any(ServerPlayer.class), org.mockito.ArgumentMatchers.anyDouble());

        // The search box really was the player's own box expanded by the configured radius.
        verify(level).getEntities(any(EntityTypeTest.class),
                eq(playerBox.inflate(12.0)), any(java.util.function.Predicate.class));
    }
}
