package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.util.McTestRegistries;
import java.util.ArrayList;
import java.util.List;
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

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        McMMOMod.setGeneralConfig(null);
        McMMOMod.setExperienceConfig(null);
    }

    private static GeneralConfig configWithOverrideTreasures(boolean enabled) {
        final GeneralConfig config = mock(GeneralConfig.class);
        when(config.getFishingOverrideTreasures()).thenReturn(enabled);
        return config;
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
}
