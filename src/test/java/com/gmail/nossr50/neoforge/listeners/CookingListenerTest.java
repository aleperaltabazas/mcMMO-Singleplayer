package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.cooking.CookingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link CookingListener}'s campfire owner tracking, the {@code input == result}
 * identity-check no-op path, Master Chef's roll on the campfire seam, crafting-grid batch-count
 * pricing, and the rate-cap notification firing once per window rather than once per cook/craft.
 * Mirrors {@code SmeltingListenerTest}'s structure and mocking discipline.
 */
class CookingListenerTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final BlockPos CAMPFIRE_POS = new BlockPos(4, 5, 6);

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private CookingManager cookingManager;
    private PlatformPlayer platformPlayer;

    @BeforeEach
    void setUp() {
        cookingManager = mock(CookingManager.class);
        platformPlayer = mock(PlatformPlayer.class);
        when(platformPlayer.getUniqueId()).thenReturn(OWNER_ID);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(platformPlayer);
        when(mmoPlayer.getCookingManager()).thenReturn(cookingManager);
        UserManager.track(mmoPlayer);
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(OWNER_ID);
        CookingListener.clearOwners();
        McMMOMod.setAdvancedConfig(null);
    }

    private static ItemStack beef() {
        return new ItemStack(Items.BEEF);
    }

    private static ItemStack cookedBeef() {
        return new ItemStack(Items.COOKED_BEEF);
    }

    // --- Campfire owner tracking round-trip --------------------------------------------------

    @Test
    void onUseBlockTracksTheRightClickingPlayerAsOwnerAndOnCampfireCookCreditsThem() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        final ServerLevel level = mock(ServerLevel.class);
        when(serverPlayer.level()).thenReturn(level);
        final CampfireBlockEntity campfire = mock(CampfireBlockEntity.class);
        when(level.getBlockEntity(CAMPFIRE_POS)).thenReturn(campfire);

        final PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                serverPlayer, InteractionHand.MAIN_HAND, CAMPFIRE_POS,
                BlockHitResult.miss(Vec3.ZERO, Direction.UP, CAMPFIRE_POS));

        CookingListener.onUseBlock(event);
        assertFalse(event.isCanceled(), "owner tracking never cancels the interaction");

        when(cookingManager.onCook(anyString(), anyLong()))
                .thenReturn(new CookingManager.CookAward(60F, 1, false));

        // Round trip: the owner just recorded by the real onUseBlock event handler above is who
        // onCampfireCook credits -- no test-only shortcut involved.
        final ItemStack result = CookingListener.onCampfireCook(level, CAMPFIRE_POS, beef(), cookedBeef());

        verify(cookingManager).onCook("Beef", 0L);
        assertEquals(1, result.getCount());
    }

    @Test
    void onUseBlockIgnoresNonCampfireBlocksAndOnCampfireCookThenCreditsNoOne() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        final ServerLevel level = mock(ServerLevel.class);
        when(serverPlayer.level()).thenReturn(level);
        when(level.getBlockEntity(CAMPFIRE_POS)).thenReturn(null); // not a campfire.

        final PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                serverPlayer, InteractionHand.MAIN_HAND, CAMPFIRE_POS,
                BlockHitResult.miss(Vec3.ZERO, Direction.UP, CAMPFIRE_POS));
        CookingListener.onUseBlock(event);

        CookingListener.onCampfireCook(level, CAMPFIRE_POS, beef(), cookedBeef());
        verify(cookingManager, never()).onCook(anyString(), anyLong());
    }

    // --- The input == result identity-check no-op path ---------------------------------------

    @Test
    void identicalInputAndResultInstanceIsANoOpEvenWithATrackedOwner() {
        CookingListener.trackOwnerForTesting(CAMPFIRE_POS, OWNER_ID);
        final ItemStack rawStack = beef();

        // A data-pack reload removed the recipe mid-cook -- cookTick's own map(craft).orElse(raw)
        // returns the SAME object, not an equal copy.
        final ItemStack result =
                CookingListener.onCampfireCook(mock(ServerLevel.class), CAMPFIRE_POS, rawStack, rawStack);

        assertSame(rawStack, result);
        verify(cookingManager, never()).onCook(anyString(), anyLong());
        verify(cookingManager, never()).canSecondHelping(anyString());
    }

    @Test
    void aContentEqualButDistinctResultInstanceIsNotTreatedAsTheIdentityNoOpCase() {
        // Guards against a content-equality regression (e.g. ItemStack.matches(input, result))
        // masquerading as the identity check: two distinct ItemStack instances of the same item
        // and count are NOT the identity no-op case, and must still be credited.
        CookingListener.trackOwnerForTesting(CAMPFIRE_POS, OWNER_ID);
        when(cookingManager.onCook(anyString(), anyLong()))
                .thenReturn(new CookingManager.CookAward(60F, 1, false));

        final ItemStack input = beef();
        final ItemStack result = beef(); // distinct instance, content-equal.
        assertTrue(ItemStack.matches(input, result),
                "sanity: the two stacks are content-equal but not the same instance");

        CookingListener.onCampfireCook(mock(ServerLevel.class), CAMPFIRE_POS, input, result);

        verify(cookingManager).onCook("Beef", 0L);
    }

    // --- Master Chef roll on the campfire path ------------------------------------------------

    @Test
    void masterChefGrowsTheScatteredResultOnTheCampfirePath() {
        CookingListener.trackOwnerForTesting(CAMPFIRE_POS, OWNER_ID);
        when(cookingManager.onCook(anyString(), anyLong()))
                .thenReturn(new CookingManager.CookAward(60F, 1, false));
        when(cookingManager.canSecondHelping("Cooked_Beef")).thenReturn(true);

        final ItemStack result = CookingListener.onCampfireCook(
                mock(ServerLevel.class), CAMPFIRE_POS, beef(), cookedBeef());

        assertEquals(2, result.getCount(), "Master Chef grew the scattered stack by one");
        verify(cookingManager).canSecondHelping("Cooked_Beef");
    }

    @Test
    void aMasterChefMissLeavesTheScatteredResultAtOneCount() {
        CookingListener.trackOwnerForTesting(CAMPFIRE_POS, OWNER_ID);
        when(cookingManager.onCook(anyString(), anyLong()))
                .thenReturn(new CookingManager.CookAward(60F, 1, false));
        when(cookingManager.canSecondHelping("Cooked_Beef")).thenReturn(false);

        final ItemStack result = CookingListener.onCampfireCook(
                mock(ServerLevel.class), CAMPFIRE_POS, beef(), cookedBeef());

        assertEquals(1, result.getCount());
    }

    // --- Crafting-grid batch-count pricing ------------------------------------------------------

    @Test
    void aShiftClickedBatchOfNPaysForNNotOne() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        final ServerLevel level = mock(ServerLevel.class);
        when(serverPlayer.level()).thenReturn(level);
        when(cookingManager.onCraft(anyString(), anyInt(), anyLong()))
                .thenReturn(new CookingManager.CookAward(64F * 8, 64, false));

        CookingListener.onCraftedItemTaken(serverPlayer, cookedBeef(), 64);

        verify(cookingManager).onCraft("Cooked_Beef", 64, 0L);
    }

    @Test
    void aSingleTakePaysForOne() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        final ServerLevel level = mock(ServerLevel.class);
        when(serverPlayer.level()).thenReturn(level);
        when(cookingManager.onCraft(anyString(), anyInt(), anyLong()))
                .thenReturn(new CookingManager.CookAward(8F, 1, false));

        CookingListener.onCraftedItemTaken(serverPlayer, cookedBeef(), 1);

        verify(cookingManager).onCraft("Cooked_Beef", 1, 0L);
    }

    @Test
    void zeroItemsTakenIsANoOp() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        when(serverPlayer.level()).thenReturn(mock(ServerLevel.class));

        CookingListener.onCraftedItemTaken(serverPlayer, cookedBeef(), 0);

        verify(cookingManager, never()).onCraft(anyString(), anyInt(), anyLong());
    }

    // --- Rate-cap notification: once per window, not once per cook/craft ----------------------

    @Test
    void rateCapNotificationFiresOnceAcrossMultipleCapReachedCraftsInTheSameWindow() {
        final AdvancedConfig advancedConfig = mock(AdvancedConfig.class);
        McMMOMod.setAdvancedConfig(advancedConfig);

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(OWNER_ID);
        when(mmoPlayer.useChatNotifications()).thenReturn(true);

        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        when(serverPlayer.level()).thenReturn(mock(ServerLevel.class));

        // First craft trips the cap (capReached=true); a second craft in the same window is still
        // capped but must not re-announce (capReached=false, the caller's own "first to trim" rule).
        when(cookingManager.onCraft(anyString(), anyInt(), anyLong()))
                .thenReturn(new CookingManager.CookAward(0F, 0, true))
                .thenReturn(new CookingManager.CookAward(0F, 0, false));

        CookingListener.onCraftedItemTaken(serverPlayer, cookedBeef(), 64);
        CookingListener.onCraftedItemTaken(serverPlayer, cookedBeef(), 64);

        // Both crafts route through NotificationManager, but only the first has capReached=true, so
        // the player-facing message is sent exactly once. sendMessage/sendActionBar are how
        // NotificationManager ultimately reaches the platform player, depending on advanced.yml
        // routing -- both are stubbed to default (false) here, so it lands on sendMessage.
        verify(platformPlayer, times(1)).sendMessage(anyString());
    }

    @Test
    void noNotificationWhenTheCapIsNeverReached() {
        final AdvancedConfig advancedConfig = mock(AdvancedConfig.class);
        McMMOMod.setAdvancedConfig(advancedConfig);

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(OWNER_ID);
        when(mmoPlayer.useChatNotifications()).thenReturn(true);

        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        when(serverPlayer.level()).thenReturn(mock(ServerLevel.class));

        when(cookingManager.onCraft(anyString(), anyInt(), anyLong()))
                .thenReturn(new CookingManager.CookAward(8F, 1, false));

        CookingListener.onCraftedItemTaken(serverPlayer, cookedBeef(), 1);

        verify(platformPlayer, never()).sendMessage(anyString());
        verify(platformPlayer, never()).sendActionBar(anyString());
    }
}
