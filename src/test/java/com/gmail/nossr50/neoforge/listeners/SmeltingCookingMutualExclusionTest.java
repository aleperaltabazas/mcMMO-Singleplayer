package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * Task D: cross-skill regression coverage.
 *
 * <p>Tasks A/B/C's own test files ({@link SmeltingListenerTest}, {@link CookingListenerTest}) each
 * exercise one skill's seams in isolation, mocking the other skill's manager out of the picture one
 * call at a time. That leaves a real gap: nothing proves the three furnace hooks that {@link
 * SmeltingListener} shares between Smelting and Cooking ({@link SmeltingListener#onFurnaceSmelt},
 * {@link SmeltingListener#onSmeltComplete}, {@link SmeltingListener#boostFuelTime} via the real
 * {@link FurnaceFuelBurnTimeEvent} bridge) actually <em>agree with each other</em> when exercised
 * together, for the same input, through the same owner and furnace state, on one real dual-priced or
 * dual-listed item.
 *
 * <p>Reuses {@code SmeltingListenerTest}'s established fixture pattern verbatim: per-test override
 * YAML layered on the shipped defaults via {@code ConfigLoader}'s back-fill, giving a real item
 * ({@code Kelp}, {@code Iron_Ingot}) genuine dual table membership without touching the shipped
 * configs' own (deliberate) mutual exclusivity.
 */
class SmeltingCookingMutualExclusionTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f5");
    private static final BlockPos FURNACE_POS = new BlockPos(70, 80, 90);

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private SmeltingManager smeltingManager;
    private CookingManager cookingManager;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        // Kelp: real shipped Cooking.Cook entry (60) + a fixture-only Smelting entry (50) -- dual
        // Experience_Values membership on a real item.
        Files.writeString(dir.resolve("experience.yml"), """
                Experience_Values:
                    Smelting:
                        Kelp: 50
                """);
        // Iron_Ingot: fixture-only dual Bonus_Drops membership for the Second Smelt / Master Chef
        // precedence scenario.
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

    /**
     * Scenario 1: an item priced under both {@code Experience_Values.Smelting} and {@code
     * Experience_Values.Cooking.Cook}. Exercises the XP seam ({@link
     * SmeltingListener#onFurnaceSmelt}) <em>and</em> the Fuel Efficiency seam through the real {@link
     * FurnaceFuelBurnTimeEvent} bridge ({@link SmeltingListener#rememberFuelBurnContext} +
     * {@link SmeltingListener#onFurnaceFuelBurnTime}, not a bare {@code boostFuelTime} call) on the
     * same owner and the same furnace position, in the same test -- proving the two independently
     * coded seams still agree that Smelting owns this input, not merely that each one individually
     * defers to Smelting when asked alone.
     */
    @Test
    void aDualPricedInputPaysSmeltingXpAndBoostsFuelViaSmeltingManagerOnlyAcrossBothSeams() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        when(smeltingManager.boostFuelTime(1600)).thenReturn(3200);

        // Seam 1: Smelting XP.
        SmeltingListener.onFurnaceSmelt(mock(ServerLevel.class), FURNACE_POS, kelp());

        // Seam 2: Fuel Efficiency, through the real event bridge a live furnace tick would use.
        final ItemStack fuel = new ItemStack(Items.COAL);
        SmeltingListener.rememberFuelBurnContext(FURNACE_POS, fuel, kelp());
        final FurnaceFuelBurnTimeEvent event = new FurnaceFuelBurnTimeEvent(fuel, 1600, null);
        SmeltingListener.onFurnaceFuelBurnTime(event);

        verify(smeltingManager).awardSmeltingXP("Kelp");
        assertEquals(3200, event.getBurnTime(),
                "Smelting's fuel boost applied, not vanilla's and not Cooking's");
        verifyNoInteractions(cookingManager);
    }

    /**
     * Scenario 2: a smelt result listed under both {@code Bonus_Drops.Smelting} and {@code
     * Bonus_Drops.Cooking}. The membership test must pick exactly one table and roll exactly once --
     * an item in both tables getting two independent rolls at the same bonus would be invisible in
     * every log.
     */
    @Test
    void aDualListedBonusDropResultRollsExactlyOnceViaSmeltingManager() {
        SmeltingListener.trackOwnerForTesting(FURNACE_POS, OWNER_ID);
        when(smeltingManager.canSecondSmelt("Iron_Ingot")).thenReturn(true);

        final ItemStack output = ironIngot();
        SmeltingListener.onSmeltComplete(FURNACE_POS, output);

        assertEquals(2, output.getCount(), "exactly one bonus copy granted");
        verify(smeltingManager, times(1)).canSecondSmelt("Iron_Ingot");
        verifyNoInteractions(cookingManager);
    }

    /**
     * Scenario 3: an item neither table prices at all (sand -- a real, non-mocked furnace input that
     * is a legal furnace-fuel-time lookup but neither an ore nor a food). Routed through the real
     * {@link PlayerInteractEvent.RightClickBlock} owner-tracking entry point (not the {@code
     * trackOwnerForTesting} shortcut) and the real {@link FurnaceFuelBurnTimeEvent} bridge, so this
     * proves the explicit non-blanket-else gate {@link SmeltingListener#boostFuelTime}'s javadoc
     * warns about holds under real owner + real furnace state, not just a unit-level function call.
     */
    @Test
    void aNonSmeltableNonCookableInputGetsNoFuelBonusFromEitherManagerThroughTheRealBridge() {
        final ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(OWNER_ID);
        final ServerLevel level = mock(ServerLevel.class);
        when(serverPlayer.level()).thenReturn(level);
        final AbstractFurnaceBlockEntity furnace = mock(AbstractFurnaceBlockEntity.class);
        when(level.getBlockEntity(FURNACE_POS)).thenReturn(furnace);
        final PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(
                serverPlayer, InteractionHand.MAIN_HAND, FURNACE_POS,
                BlockHitResult.miss(Vec3.ZERO, Direction.UP, FURNACE_POS));
        SmeltingListener.onUseBlock(click); // real owner tracking -- not the test-only shortcut.

        final ItemStack sand = new ItemStack(Items.SAND);
        final ItemStack fuel = new ItemStack(Items.COAL);
        SmeltingListener.rememberFuelBurnContext(FURNACE_POS, fuel, sand);
        final FurnaceFuelBurnTimeEvent event = new FurnaceFuelBurnTimeEvent(fuel, 1600, null);
        SmeltingListener.onFurnaceFuelBurnTime(event);

        assertEquals(1600, event.getBurnTime(), "vanilla burn time -- sand is neither skill's business");
        verifyNoInteractions(smeltingManager, cookingManager);
    }
}
