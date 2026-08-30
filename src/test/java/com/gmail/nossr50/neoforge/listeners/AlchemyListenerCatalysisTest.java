package com.gmail.nossr50.neoforge.listeners;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.neoforge.mixin.BrewingStandBrewTimeAccessor;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.alchemy.AlchemyManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pins {@link AlchemyListener#applyCatalysis}'s three branches. Mocks
 * {@link BrewingStandBrewTimeAccessor} directly (via Mockito's extra-interfaces support on a
 * {@link BrewingStandBlockEntity} mock) rather than a real block entity — the accessor mixin is
 * never woven under plain JUnit, see {@code BrewingStandBrewTimeAccessorTest}'s own javadoc for
 * why. Same technique {@code HunterListenerTest} uses for
 * {@code LivingEntityDropFromLootTableAccessor}.
 */
class AlchemyListenerCatalysisTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(OWNER_ID);
        AlchemyListener.clearOwners();
    }

    private static BrewingStandBlockEntity standWithBrewTime(int brewTime) {
        final BrewingStandBlockEntity stand = mock(BrewingStandBlockEntity.class,
                Mockito.withSettings().extraInterfaces(BrewingStandBrewTimeAccessor.class));
        when(((BrewingStandBrewTimeAccessor) stand).getBrewTime()).thenReturn(brewTime);
        return stand;
    }

    @Test
    void idleStandResetsAndDoesNothing() {
        final BlockPos pos = new BlockPos(1, 2, 3);
        final BrewingStandBlockEntity stand = standWithBrewTime(0);

        AlchemyListener.applyCatalysis(pos, stand);

        verify((BrewingStandBrewTimeAccessor) stand, never()).setBrewTime(anyInt());
    }

    @Test
    void runningBrewWithNoTrackedOwnerUsesVanillaBrewSpeed() {
        final BlockPos pos = new BlockPos(4, 5, 6);
        final BrewingStandBlockEntity stand = standWithBrewTime(100);

        // No owner tracked for this position -- resolveBrewSpeed falls back to
        // CatalysisTimer.VANILLA_BREW_SPEED (1.0), which never accumulates an extra tick.
        AlchemyListener.applyCatalysis(pos, stand);
        AlchemyListener.applyCatalysis(pos, stand);
        AlchemyListener.applyCatalysis(pos, stand);

        verify((BrewingStandBrewTimeAccessor) stand, never()).setBrewTime(anyInt());
    }

    @Test
    void trackedOwnerWithCatalysisEnabledUsesAlchemyManagerBrewSpeed() {
        final BlockPos pos = new BlockPos(7, 8, 9);
        final BrewingStandBlockEntity stand = standWithBrewTime(100);

        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(OWNER_ID);

        final AlchemyManager alchemyManager = mock(AlchemyManager.class);
        when(alchemyManager.calculateBrewSpeed(false)).thenReturn(3.0);

        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getAlchemyManager()).thenReturn(alchemyManager);
        UserManager.track(mmoPlayer);

        AlchemyListener.trackOwnerForTesting(pos, OWNER_ID);

        AlchemyListener.applyCatalysis(pos, stand);

        // speed 3.0 owes (3.0 - 1.0) = 2.0 extra ticks on the very first tick -> 2 whole extra
        // ticks burned off immediately, landing on CatalysisTimer.reducedBrewTime(100, 2) = 98.
        verify((BrewingStandBrewTimeAccessor) stand).setBrewTime(98);
    }
}
