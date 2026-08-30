package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.cooking.CookingManager;
import com.gmail.nossr50.skills.smelting.SmeltingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link SmeltingListener}'s owner tracking, the Smelting/Cooking mutual-exclusion
 * dispatch ({@link SmeltingListener#onFurnaceSmelt}), Second Smelt/Master Chef table-membership
 * precedence ({@link SmeltingListener#onSmeltComplete}), Fuel Efficiency's explicit-cookable gate
 * ({@link SmeltingListener#boostFuelTime}), and the {@link FurnaceFuelBurnTimeEvent} bridge this
 * task's investigation added ({@link SmeltingListener#rememberFuelBurnContext} /
 * {@link SmeltingListener#onFurnaceFuelBurnTime}).
 *
 * <p>The shipped {@code experience.yml}/{@code config.yml} deliberately keep Smelting and Cooking
 * mutually exclusive (no item is priced under both {@code Experience_Values.Smelting} and
 * {@code Experience_Values.Cooking.Cook}, nor listed under both {@code Bonus_Drops.Smelting} and
 * {@code Bonus_Drops.Cooking}) — that is the whole point of the gate this class gets to prove. To
 * pin the tie-break itself, {@link #setUp} writes minimal fixture overrides that deliberately
 * create the overlap on top of a real item ({@code Kelp}, {@code Iron_Ingot}), then lets
 * {@link com.gmail.nossr50.config.ConfigLoader}'s normal back-fill merge in every other shipped
 * default around them.
 */
class SmeltingListenerTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final BlockPos FURNACE_POS = new BlockPos(11, 22, 33);

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private SmeltingManager smeltingManager;
    private CookingManager cookingManager;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        // Kelp: real shipped Cooking.Cook entry (60) + a fixture-only Smelting entry (50) -> dual
        // table membership on a real item, without touching the shipped defaults' own exclusivity.
        Files.writeString(dir.resolve("experience.yml"), """
                Experience_Values:
                    Smelting:
                        Kelp: 50
                """);
        // Iron_Ingot: fixture-only dual Bonus_Drops membership for the Second Smelt precedence test.
        Files.writeString(dir.resolve("config.yml"), """
                Bonus_Drops:
                    Smelting:
                        Iron_Ingot: true
                    Cooking:
                        Iron_Ingot: true
                """);
        McMMOMod.setExperienceConfig(new ExperienceConfig(dir));
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));

        smeltingManager = mock(SmeltingManager.class);
        cookingManager = mock(CookingManager.class);
        final PlatformPlayer platformPlayer = mock(PlatformPlayer.class);
        when(platformPlayer.getUniqueId()).thenReturn(OWNER_ID);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(platformPlayer);
        when(mmoPlayer.getSmeltingManager()).thenReturn(smeltingManager);
        when(mmoPlayer.getCookingManager()).thenReturn(cookingManager);
        UserManager.track(mmoPlayer);
    }

    @AfterEach
    void tearDown() {
        McMMOMod.setExperienceConfig(null);
        McMMOMod.setGeneralConfig(null);
        UserManager.remove(OWNER_ID);
        SmeltingListener.clearOwners();
    }

    private static ItemStack kelp() {
        return new ItemStack(Items.KELP);
    }

    private static ItemStack ironIngot() {
        return new ItemStack(Items.IRON_INGOT);
    }

    // --- Owner tracking round-trip --------------------------------------------------------------

    @Test
    void onUseBlockTracksTheRightClickingPlayerAsOwnerAndOnFurnaceSmeltCreditsThem() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        final ServerLevel level = mock(ServerLevel.class);
        when(serverPlayer.level()).thenReturn(level);
        final AbstractFurnaceBlockEntity furnace = mock(AbstractFurnaceBlockEntity.class);
        when(level.getBlockEntity(FURNACE_POS)).thenReturn(furnace);

        final PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                serverPlayer, InteractionHand.MAIN_HAND, FURNACE_POS,
                BlockHitResult.miss(Vec3.ZERO, Direction.UP, FURNACE_POS));

        SmeltingListener.onUseBlock(event);
        assertFalse(event.isCanceled(), "owner tracking never cancels opening the furnace");

        // Round trip: the owner just recorded by the real onUseBlock event handler above is who
        // onFurnaceSmelt credits -- no test-only shortcut involved.
        SmeltingListener.onFurnaceSmelt(level, FURNACE_POS, new ItemStack(Items.IRON_ORE));

        verify(smeltingManager).awardSmeltingXP("Iron_Ore");
    }

    @Test
    void onUseBlockIgnoresNonFurnaceBlocksAndOnFurnaceSmeltThenCreditsNoOne() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        final ServerLevel level = mock(ServerLevel.class);
        when(serverPlayer.level()).thenReturn(level);
        when(level.getBlockEntity(FURNACE_POS)).thenReturn(null); // not a furnace.

        final PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                serverPlayer, InteractionHand.MAIN_HAND, FURNACE_POS,
                BlockHitResult.miss(Vec3.ZERO, Direction.UP, FURNACE_POS));
        SmeltingListener.onUseBlock(event);

        SmeltingListener.onFurnaceSmelt(level, FURNACE_POS, new ItemStack(Items.IRON_ORE));
        verify(smeltingManager, never()).awardSmeltingXP(anyString());
    }

    @Test
    void trackOwnerForTestingRoundTripsThroughOnFurnaceSmelt() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);

        SmeltingListener.onFurnaceSmelt(mock(ServerLevel.class), FURNACE_POS,
                new ItemStack(Items.IRON_ORE));

        // experience.yml default: Experience_Values.Smelting.Iron_Ore = 25.
        verify(smeltingManager).awardSmeltingXP("Iron_Ore");
    }

    // --- Smelting-vs-Cooking mutual exclusion -----------------------------------------------------

    @Test
    void anInputPricedUnderBothTablesPaysSmeltingOnly() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);

        // Kelp is priced under BOTH Smelting (50, fixture-only) and Cooking.Cook (60, shipped).
        assertTrue(SmeltingManager.isSmeltable("Kelp"), "fixture gives Kelp a Smelting price");
        assertTrue(CookingManager.isCookable("Kelp"), "Kelp is also a shipped Cooking.Cook input");

        SmeltingListener.onFurnaceSmelt(mock(ServerLevel.class), FURNACE_POS, kelp());

        verify(smeltingManager).awardSmeltingXP("Kelp");
        verify(cookingManager, never()).onCook(anyString(), anyLong());
    }

    @Test
    void anInputPricedUnderCookingOnlyPaysCooking() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        when(cookingManager.onCook(anyString(), anyLong()))
                .thenReturn(new CookingManager.CookAward(0F, 0, false));

        SmeltingListener.onFurnaceSmelt(mock(ServerLevel.class), FURNACE_POS, new ItemStack(Items.BEEF));

        verify(smeltingManager, never()).awardSmeltingXP(anyString());
        verify(cookingManager).onCook("Beef", 0L);
    }

    // --- Second Smelt / Master Chef table-membership precedence -----------------------------------

    @Test
    void aResultListedInBothBonusDropTablesRollsSmeltingOnly() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        assertTrue(SmeltingManager.isSecondSmeltMaterial("Iron_Ingot"),
                "fixture lists Iron_Ingot under Bonus_Drops.Smelting");
        assertTrue(CookingManager.isMasterChefMaterial("Iron_Ingot"),
                "fixture also lists Iron_Ingot under Bonus_Drops.Cooking");
        when(smeltingManager.canSecondSmelt("Iron_Ingot")).thenReturn(true);

        final ItemStack output = ironIngot();
        SmeltingListener.onSmeltComplete(FURNACE_POS, output);

        assertEquals(2, output.getCount(), "Smelting's roll granted the bonus copy");
        verify(smeltingManager).canSecondSmelt("Iron_Ingot");
        verify(cookingManager, never()).canSecondHelping(anyString());
    }

    @Test
    void aResultInNeitherBonusDropTableIsANoOpBeforeResolvingTheOwner() {
        // Stone (smelted from cobblestone) is not listed under either Bonus_Drops table by default
        // or by this test's fixtures -- the membership check must short-circuit before even
        // resolving the owner.
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        final ItemStack output = new ItemStack(Items.STONE);

        SmeltingListener.onSmeltComplete(FURNACE_POS, output);

        assertEquals(1, output.getCount(), "no bonus copy for an unlisted result");
        verify(smeltingManager, never()).canSecondSmelt(anyString());
        verify(cookingManager, never()).canSecondHelping(anyString());
    }

    // --- Fuel Efficiency: explicit cookable check, not a blanket else -----------------------------

    @Test
    void aNonSmeltableNonCookableInputGetsVanillaBurnTimeAndTouchesNeitherManager() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        // Sand: neither Smelting nor Cooking.Cook price it (per the class's own javadoc warning).
        final int boosted = SmeltingListener.boostFuelTime(1600, FURNACE_POS, new ItemStack(Items.SAND));

        assertEquals(1600, boosted, "vanilla burn time, unchanged");
        verify(smeltingManager, never()).boostFuelTime(anyInt());
        verify(cookingManager, never()).boostFuelTime(anyInt());
    }

    @Test
    void aSmeltableInputGetsSmeltingsFuelEfficiencyNotCookings() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        when(smeltingManager.boostFuelTime(1600)).thenReturn(3200);

        final int boosted = SmeltingListener.boostFuelTime(1600, FURNACE_POS, new ItemStack(Items.IRON_ORE));

        assertEquals(3200, boosted);
        verify(cookingManager, never()).boostFuelTime(anyInt());
    }

    @Test
    void aCookableInputGetsCookingsFuelEfficiencyNotSmeltings() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        when(cookingManager.boostFuelTime(1600)).thenReturn(2400);

        final int boosted = SmeltingListener.boostFuelTime(1600, FURNACE_POS, new ItemStack(Items.BEEF));

        assertEquals(2400, boosted);
        verify(smeltingManager, never()).boostFuelTime(anyInt());
    }

    // --- Fuel Efficiency: the FurnaceFuelBurnTimeEvent bridge --------------------------------------

    @Test
    void furnaceFuelBurnTimeEventAppliesTheBoostWhenTheBridgedContextMatches() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        when(smeltingManager.boostFuelTime(1600)).thenReturn(3200);
        final ItemStack fuel = new ItemStack(Items.COAL);

        SmeltingListener.rememberFuelBurnContext(FURNACE_POS, fuel, new ItemStack(Items.IRON_ORE));
        final FurnaceFuelBurnTimeEvent event = new FurnaceFuelBurnTimeEvent(fuel, 1600, null);
        SmeltingListener.onFurnaceFuelBurnTime(event);

        assertEquals(3200, event.getBurnTime());
    }

    @Test
    void furnaceFuelBurnTimeEventIgnoresAStaleOrForeignContext() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        when(smeltingManager.boostFuelTime(1600)).thenReturn(3200);

        // The bridged context describes a DIFFERENT fuel stack than the one the event carries --
        // e.g. AbstractFurnaceBlockEntity#canPlaceItem's direct ItemStack#getBurnTime call, which
        // bypasses the getBurnDuration mixin entirely. The stale context must not be trusted.
        SmeltingListener.rememberFuelBurnContext(FURNACE_POS, new ItemStack(Items.COAL),
                new ItemStack(Items.IRON_ORE));
        final FurnaceFuelBurnTimeEvent event =
                new FurnaceFuelBurnTimeEvent(new ItemStack(Items.CHARCOAL), 1600, null);
        SmeltingListener.onFurnaceFuelBurnTime(event);

        assertEquals(1600, event.getBurnTime(), "unmatched context is never applied");
        assertFalse(event.isCanceled(), "an untouched event is left for other listeners");
    }

    @Test
    void furnaceFuelBurnTimeEventWithNoBridgedContextIsANoOp() {
        final ItemStack fuel = new ItemStack(Items.COAL);
        final FurnaceFuelBurnTimeEvent event = new FurnaceFuelBurnTimeEvent(fuel, 1600, null);

        SmeltingListener.onFurnaceFuelBurnTime(event);

        assertEquals(1600, event.getBurnTime());
        assertFalse(event.isCanceled());
    }
}
