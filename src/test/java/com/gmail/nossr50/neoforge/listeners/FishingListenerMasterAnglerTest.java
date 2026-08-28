package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.config.RankConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.fishing.FishingManager;
import com.gmail.nossr50.skills.fishing.FishingManager.MasterAnglerWaitTimes;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link FishingListener#resolveWaitCountdown} and {@link FishingListener#masterAnglerWaitTimes}
 * -- the Master Angler wait-countdown seam (Task C) -- exercising the gate chain (rod-in-main-hand,
 * no-rod-in-off-hand, {@code canMasterAngler()}) and the arithmetic ({@code Mth.nextInt} draw plus the
 * Lure add-back that cancels vanilla's own subtraction) without needing a live mixin. Same MC-free-ish
 * mock/config pattern as {@code FishingListenerCatchTest}/{@code FishingListenerShakeIceTest}.
 */
class FishingListenerMasterAnglerTest {

    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000000000fa");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000000fb");

    private static ItemStack ROD;
    private static ItemStack NOT_A_ROD;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
        ROD = new ItemStack(Items.FISHING_ROD);
        NOT_A_ROD = ItemStack.EMPTY;
    }

    // RankUtils.getRank (invoked whenever a gate-passing test reaches the FishingManager call) reads
    // McMMOMod's RankConfig -- a real bundled one is enough, the resolved rank int itself does not
    // matter to these tests (the FishingManager mocks below accept any rank via anyInt()/eq(0), the
    // latter because a Mockito McMMOPlayer stub's unstubbed getSkillLevel() returns 0, which never
    // unlocks a rank against the bundled table).
    @BeforeEach
    void setUpRankConfig(@TempDir Path dataFolder) {
        McMMOMod.setGeneralConfig(new GeneralConfig(dataFolder));
        McMMOMod.setRankConfig(new RankConfig(dataFolder));
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_A);
        UserManager.remove(PLAYER_B);
        McMMOMod.setGeneralConfig(null);
        McMMOMod.setRankConfig(null);
    }

    private static ServerPlayer serverPlayer(UUID uuid, ItemStack mainHand, ItemStack offHand,
            net.minecraft.world.entity.Entity vehicle) {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(uuid);
        when(handle.getMainHandItem()).thenReturn(mainHand);
        when(handle.getOffhandItem()).thenReturn(offHand);
        when(handle.getVehicle()).thenReturn(vehicle);
        return handle;
    }

    private static FishingHook hookOwnedBy(ServerPlayer owner) {
        final FishingHook hook = mock(FishingHook.class);
        when(hook.getPlayerOwner()).thenReturn(owner);
        return hook;
    }

    private static void track(ServerPlayer handle, FishingManager fishingManager) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getFishingManager()).thenReturn(fishingManager);
        UserManager.track(mmoPlayer);
    }

    /** A {@link RandomSource} whose {@code nextInt(bound)} always draws the bottom of the range. */
    private static RandomSource randomAtFloor() {
        final RandomSource random = mock(RandomSource.class);
        when(random.nextInt(anyInt())).thenReturn(0);
        return random;
    }

    // --- gate chain: masterAnglerWaitTimes ---

    @Test
    void noFishingRodInMainHandFallsThroughToVanillaDraw() {
        final ServerPlayer player = serverPlayer(PLAYER_A, NOT_A_ROD, NOT_A_ROD, null);
        final FishingHook hook = hookOwnedBy(player);

        assertNull(FishingListener.masterAnglerWaitTimes(hook, 100, 600, 0));
    }

    @Test
    void aRodInTheOffHandTooFallsThroughToVanillaDraw() {
        final ServerPlayer player = serverPlayer(PLAYER_A, ROD, ROD, null);
        final FishingHook hook = hookOwnedBy(player);

        assertNull(FishingListener.masterAnglerWaitTimes(hook, 100, 600, 0));
    }

    @Test
    void canMasterAnglerFalseFallsThroughToVanillaDraw() {
        final ServerPlayer player = serverPlayer(PLAYER_A, ROD, NOT_A_ROD, null);
        final FishingHook hook = hookOwnedBy(player);
        final FishingManager fishingManager = mock(FishingManager.class);
        when(fishingManager.canMasterAngler()).thenReturn(false);
        track(player, fishingManager);

        assertNull(FishingListener.masterAnglerWaitTimes(hook, 100, 600, 0));
        verify(fishingManager, never()).resolveMasterAnglerWaitTimesFromLureTicks(
                anyInt(), anyInt(), anyInt(), anyBoolean(), anyInt());
    }

    @Test
    void aQualifyingPlayerWithABoatBonusProducesANarrowerRangeThanWithout() {
        final ServerPlayer boatPlayer = serverPlayer(PLAYER_A, ROD, NOT_A_ROD, mock(Boat.class));
        final FishingHook boatHook = hookOwnedBy(boatPlayer);
        final FishingManager boatManager = mock(FishingManager.class);
        when(boatManager.canMasterAngler()).thenReturn(true);
        when(boatManager.resolveMasterAnglerWaitTimesFromLureTicks(
                eq(100), eq(600), anyInt(), eq(true), eq(0)))
                .thenReturn(new MasterAnglerWaitTimes(300, 400, false));
        track(boatPlayer, boatManager);

        final ServerPlayer landPlayer = serverPlayer(PLAYER_B, ROD, NOT_A_ROD, null);
        final FishingHook landHook = hookOwnedBy(landPlayer);
        final FishingManager landManager = mock(FishingManager.class);
        when(landManager.canMasterAngler()).thenReturn(true);
        when(landManager.resolveMasterAnglerWaitTimesFromLureTicks(
                eq(100), eq(600), anyInt(), eq(false), eq(0)))
                .thenReturn(new MasterAnglerWaitTimes(150, 450, false));
        track(landPlayer, landManager);

        final MasterAnglerWaitTimes boatTimes =
                FishingListener.masterAnglerWaitTimes(boatHook, 100, 600, 0);
        final MasterAnglerWaitTimes landTimes =
                FishingListener.masterAnglerWaitTimes(landHook, 100, 600, 0);

        assertTrue(boatTimes.maxWaitTicks() - boatTimes.minWaitTicks()
                < landTimes.maxWaitTicks() - landTimes.minWaitTicks());
        // Confirms the vehicle check actually drove the boatBonus argument, not just the stub setup.
        // The rank argument uses anyInt() rather than a literal 0: RankUtils.getRank's resolved value
        // depends on a JVM-wide static cache this test does not control, so pinning it to a literal
        // would be a false-negative risk if another test in the same run left a different config
        // resolved into that cache.
        verify(boatManager).resolveMasterAnglerWaitTimesFromLureTicks(eq(100), eq(600), anyInt(),
                eq(true), eq(0));
        verify(landManager).resolveMasterAnglerWaitTimesFromLureTicks(eq(100), eq(600), anyInt(),
                eq(false), eq(0));
    }

    // --- arithmetic: resolveWaitCountdown ---

    @Test
    void resolveWaitCountdownFallsThroughToAnUnmodifiedVanillaDrawOnGateMiss() {
        final ServerPlayer player = serverPlayer(PLAYER_A, NOT_A_ROD, NOT_A_ROD, null);
        final FishingHook hook = hookOwnedBy(player);

        final int result = FishingListener.resolveWaitCountdown(hook, randomAtFloor(), 100, 600, 50);

        assertEquals(100, result); // Mth.nextInt at the floor of [100, 600] with a zero-draw RNG.
    }

    @Test
    void resolveWaitCountdownDrawsFromTheReducedRangeAndCancelsTheLureSubtraction() {
        final ServerPlayer player = serverPlayer(PLAYER_A, ROD, NOT_A_ROD, null);
        final FishingHook hook = hookOwnedBy(player);
        final FishingManager fishingManager = mock(FishingManager.class);
        when(fishingManager.canMasterAngler()).thenReturn(true);
        when(fishingManager.resolveMasterAnglerWaitTimesFromLureTicks(100, 600, 0, false, 50))
                .thenReturn(new MasterAnglerWaitTimes(200, 300, true));
        track(player, fishingManager);

        final int result = FishingListener.resolveWaitCountdown(hook, randomAtFloor(), 100, 600, 50);

        // Drawn at the floor of the reduced [200, 300] range (200), plus the lure add-back (50) since
        // disableLure is true -- mirrors the mixin's documented "cancel vanilla's own subtraction".
        assertEquals(250, result);
    }

    @Test
    void resolveWaitCountdownDoesNotAddBackTheLureWhenDisableLureIsFalse() {
        final ServerPlayer player = serverPlayer(PLAYER_A, ROD, NOT_A_ROD, null);
        final FishingHook hook = hookOwnedBy(player);
        final FishingManager fishingManager = mock(FishingManager.class);
        when(fishingManager.canMasterAngler()).thenReturn(true);
        when(fishingManager.resolveMasterAnglerWaitTimesFromLureTicks(100, 600, 0, false, 0))
                .thenReturn(new MasterAnglerWaitTimes(200, 300, false));
        track(player, fishingManager);

        final int result = FishingListener.resolveWaitCountdown(hook, randomAtFloor(), 100, 600, 0);

        assertEquals(200, result);
    }
}
