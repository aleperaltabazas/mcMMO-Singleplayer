package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.fishing.FishingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FishingListener#overrideVanillaTreasures} and {@link FishingListener#punishOverfishing}
 * -- the two catch/treasure/overfishing methods with pure-enough control flow to unit test without a
 * live mixin (the mixin seams themselves need a running game, same as every other mixin-driven
 * listener in this port -- see {@code HunterListenerTest}'s own note on that limit).
 */
class FishingListenerCatchTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000fc");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER);
        McMMOMod.setGeneralConfig(null);
        McMMOMod.setExperienceConfig(null);
    }

    private static GeneralConfig configWithOverrideTreasures(boolean enabled) {
        final GeneralConfig config = mock(GeneralConfig.class);
        when(config.getFishingOverrideTreasures()).thenReturn(enabled);
        return config;
    }

    private static ServerPlayer serverPlayer() {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(PLAYER);
        // luckOfTheSeaLevel (reached once onFishCaught gets as far as maybeCatchTreasure) reads both
        // hands looking for the rod the player is fishing with.
        when(handle.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(handle.getOffhandItem()).thenReturn(ItemStack.EMPTY);
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

    @Test
    void theFourExemptVanillaFishPassThroughUnchanged() {
        McMMOMod.setGeneralConfig(configWithOverrideTreasures(true));

        final List<ItemStack> caught = new ArrayList<>(List.of(
                new ItemStack(Items.SALMON), new ItemStack(Items.COD),
                new ItemStack(Items.TROPICAL_FISH), new ItemStack(Items.PUFFERFISH)));

        FishingListener.overrideVanillaTreasures(caught);

        assertEquals(4, caught.size());
        assertTrue(caught.get(0).is(Items.SALMON));
        assertTrue(caught.get(1).is(Items.COD));
        assertTrue(caught.get(2).is(Items.TROPICAL_FISH));
        assertTrue(caught.get(3).is(Items.PUFFERFISH));
    }

    @Test
    void everyNonFishItemBecomesASingleSalmonStack() {
        McMMOMod.setGeneralConfig(configWithOverrideTreasures(true));

        final List<ItemStack> caught = new ArrayList<>(
                List.of(new ItemStack(Items.LEATHER_BOOTS), new ItemStack(Items.NAME_TAG)));

        FishingListener.overrideVanillaTreasures(caught);

        assertEquals(2, caught.size());
        for (ItemStack stack : caught) {
            assertTrue(stack.is(Items.SALMON));
            assertEquals(1, stack.getCount());
        }
    }

    @Test
    void anAllFishCatchIsLeftUntouchedWithNoDefensiveCopyChurn() {
        McMMOMod.setGeneralConfig(configWithOverrideTreasures(true));

        final List<ItemStack> caught = spy(new ArrayList<>(
                List.of(new ItemStack(Items.SALMON), new ItemStack(Items.COD))));

        FishingListener.overrideVanillaTreasures(caught);

        // Nothing changed, so the collection must never be cleared/refilled -- only mutated (a real
        // clear+addAll churn) when a non-fish item actually needs replacing.
        verify(caught, never()).clear();
        verify(caught, never()).addAll(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void overrideIsANoOpWhenTheConfigSwitchIsOff() {
        McMMOMod.setGeneralConfig(configWithOverrideTreasures(false));

        final List<ItemStack> caught = new ArrayList<>(List.of(new ItemStack(Items.NAME_TAG)));

        FishingListener.overrideVanillaTreasures(caught);

        assertEquals(1, caught.size());
        assertTrue(caught.get(0).is(Items.NAME_TAG));
    }

    @Test
    void punishOverfishingClearsTheCatch() {
        McMMOMod.setGeneralConfig(configWithOverrideTreasures(true));
        final ExperienceConfig experienceConfig = mock(ExperienceConfig.class);
        when(experienceConfig.getFishingExploitingOptionMoveRange()).thenReturn(16);
        McMMOMod.setExperienceConfig(experienceConfig);

        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class); // useChatNotifications() -> false.
        final List<ItemStack> caught = new ArrayList<>(
                List.of(new ItemStack(Items.SALMON), new ItemStack(Items.COD)));

        FishingListener.punishOverfishing(mmoPlayer, caught);

        assertTrue(caught.isEmpty());
    }

    // --- onFishCaught: the three load-bearing orderings pinned by the mixin-facing entry point ---

    @Test
    void anExploitedCatchIsConfiscatedWithNoXpAndNoTreasureRoll() {
        McMMOMod.setGeneralConfig(configWithOverrideTreasures(false));
        final ExperienceConfig experienceConfig = mock(ExperienceConfig.class);
        when(experienceConfig.isFishingExploitingPrevented()).thenReturn(true);
        when(experienceConfig.getFishingExploitingOptionMoveRange()).thenReturn(16);
        McMMOMod.setExperienceConfig(experienceConfig);

        final ServerPlayer player = serverPlayer();
        final FishingHook hook = hookOwnedBy(player);
        final FishingManager fishingManager = mock(FishingManager.class);
        when(fishingManager.isFishingTooOften()).thenReturn(false);
        when(fishingManager.isExploitingFishing()).thenReturn(true);
        track(player, fishingManager);

        final List<ItemStack> caught = new ArrayList<>(List.of(new ItemStack(Items.COD)));

        FishingListener.onFishCaught(hook, caught);

        assertTrue(caught.isEmpty());
        verify(fishingManager, never()).awardFishingXP(anyString());
        verify(fishingManager, never()).rollFishingTreasure(anyDouble(), anyInt(), any());
    }

    @Test
    void aNonExploitedCatchIsXpAwardedOnThePostOverrideItemsAfterOverrideRunsFirst() {
        // Override_Vanilla_Treasures on: the exploit gate is off, so overrideVanillaTreasures running
        // before it is the only ordering left to observe -- the non-fish item must already be a salmon
        // by the time awardFishingXP is keyed off it.
        McMMOMod.setGeneralConfig(configWithOverrideTreasures(true));
        final ExperienceConfig experienceConfig = mock(ExperienceConfig.class);
        when(experienceConfig.isFishingExploitingPrevented()).thenReturn(false);
        McMMOMod.setExperienceConfig(experienceConfig);

        final ServerPlayer player = serverPlayer();
        final FishingHook hook = hookOwnedBy(player);
        final FishingManager fishingManager = mock(FishingManager.class);
        track(player, fishingManager);

        final List<ItemStack> caught = new ArrayList<>(
                List.of(new ItemStack(Items.NAME_TAG), ItemStack.EMPTY, new ItemStack(Items.COD)));

        FishingListener.onFishCaught(hook, caught);

        // overrideVanillaTreasures already ran: the name tag became a salmon before the XP loop saw it.
        assertTrue(caught.get(0).is(Items.SALMON));
        verify(fishingManager, times(1)).awardFishingXP("Salmon");
        verify(fishingManager, times(1)).awardFishingXP("Cod");
        verify(fishingManager, times(2)).awardFishingXP(anyString()); // the empty stack is skipped.
    }
}
