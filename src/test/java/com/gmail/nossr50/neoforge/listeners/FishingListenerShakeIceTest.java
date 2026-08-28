package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.util.McTestRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FishingListener#shearIfWool} and {@link FishingListener#sitsOverWater} -- the two
 * Shake/Ice-Fishing helpers with pure-enough control flow to unit test without a live mixin (the
 * mixin seams themselves need a running game, same as every other mixin-driven listener in this
 * port -- see {@code FishingListenerCatchTest}'s own note on that limit).
 */
class FishingListenerShakeIceTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    // --- shearIfWool ---

    @Test
    void anAlreadyShearedSheepRefusesTheShake() {
        final Sheep sheep = mock(Sheep.class);
        when(sheep.isSheared()).thenReturn(true);

        final boolean mayProceed = FishingListener.shearIfWool(sheep, "white_wool");

        assertFalse(mayProceed);
        verify(sheep, never()).setSheared(true);
    }

    @Test
    void anUnshearedSheepIsShearedAndTheShakeProceeds() {
        final Sheep sheep = mock(Sheep.class);
        when(sheep.isSheared()).thenReturn(false);

        final boolean mayProceed = FishingListener.shearIfWool(sheep, "blue_wool");

        assertTrue(mayProceed);
        verify(sheep).setSheared(true);
    }

    @Test
    void aNonSheepTargetIsANoOpPassThrough() {
        final LivingEntity zombie = mock(Zombie.class);

        final boolean mayProceed = FishingListener.shearIfWool(zombie, "white_wool");

        assertTrue(mayProceed);
    }

    @Test
    void aNonWoolDropOffASheepIsANoOpPassThrough() {
        final Sheep sheep = mock(Sheep.class);

        final boolean mayProceed = FishingListener.shearIfWool(sheep, "bone");

        assertTrue(mayProceed);
        verify(sheep, never()).isSheared();
        verify(sheep, never()).setSheared(true);
    }

    // --- sitsOverWater ---

    private static ServerLevel levelWhereOnlyThisPosIsWater(BlockPos waterPos) {
        final ServerLevel level = mock(ServerLevel.class);
        final FluidState empty = mock(FluidState.class);
        when(empty.is(FluidTags.WATER)).thenReturn(false);
        final FluidState water = mock(FluidState.class);
        when(water.is(FluidTags.WATER)).thenReturn(true);

        when(level.getFluidState(any(BlockPos.class))).thenReturn(empty);
        if (waterPos != null) {
            when(level.getFluidState(waterPos)).thenReturn(water);
        }
        return level;
    }

    @Test
    void waterOneBlockBeneathTheIceCountsAsSittingOverWater() {
        final BlockPos icePos = new BlockPos(0, 64, 0);
        final ServerLevel level = levelWhereOnlyThisPosIsWater(icePos.below(1));

        assertTrue(FishingListener.sitsOverWater(level, icePos));
    }

    @Test
    void waterFourBlocksBeneathTheIceStillCounts() {
        final BlockPos icePos = new BlockPos(0, 64, 0);
        final ServerLevel level = levelWhereOnlyThisPosIsWater(icePos.below(4));

        assertTrue(FishingListener.sitsOverWater(level, icePos));
    }

    @Test
    void waterFiveBlocksBeneathTheIceIsOutOfScanRange() {
        final BlockPos icePos = new BlockPos(0, 64, 0);
        final ServerLevel level = levelWhereOnlyThisPosIsWater(icePos.below(5));

        assertFalse(FishingListener.sitsOverWater(level, icePos));
    }

    @Test
    void noWaterAnywhereInTheScanReturnsFalse() {
        final BlockPos icePos = new BlockPos(0, 64, 0);
        final ServerLevel level = levelWhereOnlyThisPosIsWater(null);

        assertFalse(FishingListener.sitsOverWater(level, icePos));
    }
}
