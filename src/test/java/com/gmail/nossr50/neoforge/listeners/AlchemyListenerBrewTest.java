package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.config.skills.alchemy.PotionConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.alchemy.AlchemyPotion;
import com.gmail.nossr50.datatypes.skills.alchemy.PotionStage;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformItem;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.alchemy.AlchemyManager;
import com.gmail.nossr50.skills.alchemy.AlchemyPotionBrewer;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.brewing.PotionBrewEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link AlchemyListener#onPotionBrewPre} — the craft/XP seam that replaces the Fabric
 * original's {@code craft} mixin outright (see the class javadoc and
 * docs/superpowers/specs/2026-08-30-alchemy-listener-design.md).
 *
 * <p>Uses a <b>real</b> {@link PotionBrewEvent.Pre} built over a real {@link NonNullList}, not a
 * Mockito mock: {@code getItem}/{@code setItem} read and write a private {@code stacks} field
 * directly rather than going through an overridable method chain, so a mock's stubbed
 * {@code getItem} would not reflect what {@code setItem} actually wrote — the same
 * real-instance-over-mock call {@code FishingListenerMagicHunterTest} made for {@code Enchantment}.
 *
 * <p>Same {@code POTION_OF_AWKWARD + SUGAR -> POTION_OF_SWIFTNESS} shipped transition
 * {@link com.gmail.nossr50.skills.alchemy.AlchemyPotionBrewerTest} pins, driven through a 5-slot
 * brewing-stand array (bottles 0-2, ingredient 3, fuel 4) exactly as {@code doBrew} hands it to
 * {@link PotionBrewEvent.Pre}.
 */
class AlchemyListenerBrewTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final BlockPos STAND_POS = new BlockPos(10, 20, 30);

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private PotionConfig potionConfig;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        McMMOMod.setExperienceConfig(new ExperienceConfig(dir));
        potionConfig = new PotionConfig(dir);
        McMMOMod.setPotionConfig(potionConfig);
    }

    @AfterEach
    void tearDown() {
        McMMOMod.setPotionConfig(null);
        McMMOMod.setExperienceConfig(null);
        McMMOMod.setGeneralConfig(null);
        AlchemyListener.clearOwners();
        UserManager.remove(OWNER_ID);
    }

    /** A 5-slot brewing stand: an Awkward potion in bottle slot 0 and {@code ingredient} in slot 3. */
    private NonNullList<ItemStack> awkwardStandWith(ItemStack ingredient) {
        final AlchemyPotion awkward = potionConfig.getPotion("POTION_OF_AWKWARD");
        assertNotNull(awkward, "POTION_OF_AWKWARD is in the bundled tree");
        final NonNullList<ItemStack> slots = NonNullList.withSize(5, ItemStack.EMPTY);
        slots.set(0, awkward.toItem(1).unwrap());
        slots.set(AlchemyPotionBrewer.INGREDIENT_SLOT, ingredient);
        return slots;
    }

    private static PotionBrewEvent.Pre eventOver(NonNullList<ItemStack> slots) {
        return new PotionBrewEvent.Pre(slots);
    }

    private static McMMOPlayer trackedOwner(AlchemyManager alchemyManager) {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(OWNER_ID);

        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getAlchemyManager()).thenReturn(alchemyManager);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    @Test
    void unrecognizedBrewLeavesTheEventUncancelled() {
        final NonNullList<ItemStack> slots = awkwardStandWith(new ItemStack(Items.DIRT));
        final PotionBrewEvent.Pre event = eventOver(slots);

        AlchemyListener.onPotionBrewPre(event);

        assertFalse(event.isCanceled(), "dirt is not a recognised mcMMO ingredient");
        assertTrue(event.getItem(AlchemyPotionBrewer.INGREDIENT_SLOT).is(Items.DIRT),
                "an unrecognized brew is left completely untouched");
    }

    @Test
    void recognizedBrewWithTrackedOwnerCancelsAndAwardsXp() {
        final AlchemyManager alchemyManager = mock(AlchemyManager.class);
        trackedOwner(alchemyManager);
        AlchemyListener.trackOwnerForTesting(STAND_POS, OWNER_ID);
        AlchemyListener.rememberBrewPosition(STAND_POS);

        final NonNullList<ItemStack> slots = awkwardStandWith(new ItemStack(Items.SUGAR));
        final PotionBrewEvent.Pre event = eventOver(slots);

        AlchemyListener.onPotionBrewPre(event);

        assertTrue(event.isCanceled(), "a recognised mcMMO brew takes over the craft");
        final AlchemyPotion brewed = potionConfig.getPotion(new PlatformItem(event.getItem(0)));
        assertNotNull(brewed, "the brewed bottle is still a recognised potion");
        assertEquals("POTION_OF_SWIFTNESS", brewed.getPotionConfigName(),
                "Awkward brewed into Swiftness, written back onto the event");
        assertTrue(event.getItem(AlchemyPotionBrewer.INGREDIENT_SLOT).isEmpty(),
                "the single sugar was consumed");
        // Awkward -> Swiftness is a stage-2 brew (see PotionConfigTest); one bottle -> amount 1.
        verify(alchemyManager).handlePotionBrewSuccesses(PotionStage.TWO, 1);
    }

    @Test
    void recognizedBrewWithNoTrackedOwnerStillFinishesButAwardsNoXp() {
        AlchemyListener.rememberBrewPosition(STAND_POS); // position known, but never tracked to an owner.

        final NonNullList<ItemStack> slots = awkwardStandWith(new ItemStack(Items.SUGAR));
        final PotionBrewEvent.Pre event = eventOver(slots);

        AlchemyListener.onPotionBrewPre(event);

        assertTrue(event.isCanceled(), "an unattended brew still completes and takes over the craft");
        final AlchemyPotion brewed = potionConfig.getPotion(new PlatformItem(event.getItem(0)));
        assertNotNull(brewed, "the brewed bottle is still a recognised potion");
        assertEquals("POTION_OF_SWIFTNESS", brewed.getPotionConfigName(),
                "the brew completes with no owner to credit");
        assertTrue(event.getItem(AlchemyPotionBrewer.INGREDIENT_SLOT).isEmpty(),
                "the single sugar was still consumed");
    }

    @Test
    void recognizedBrewWithUnknownPositionStillFinishesButAwardsNoXp() {
        // BREW_POSITION was never set for this call (the doBrew injector never ran) -- onBrewCraft
        // must tolerate a null BlockPos rather than NPE, and still complete the brew. Not reliant
        // on test execution order: onPotionBrewPre now consumes (get-then-clear) BREW_POSITION
        // unconditionally at its own head on every call, and tearDown()'s clearOwners() also clears
        // it, so no earlier test in this class can leave a stale value for this one to inherit.
        final NonNullList<ItemStack> slots = awkwardStandWith(new ItemStack(Items.SUGAR));
        final PotionBrewEvent.Pre event = eventOver(slots);

        AlchemyListener.onPotionBrewPre(event);

        assertTrue(event.isCanceled(), "a recognised brew completes even with no known position");
        final AlchemyPotion brewed = potionConfig.getPotion(new PlatformItem(event.getItem(0)));
        assertEquals("POTION_OF_SWIFTNESS", brewed.getPotionConfigName());
    }

    @Test
    void onBrewCraftAwardsXpDirectlyForATrackedOwner() {
        final AlchemyManager alchemyManager = mock(AlchemyManager.class);
        trackedOwner(alchemyManager);
        AlchemyListener.trackOwnerForTesting(STAND_POS, OWNER_ID);

        final NonNullList<ItemStack> slots = awkwardStandWith(new ItemStack(Items.SUGAR));

        AlchemyListener.onBrewCraft(STAND_POS, slots);

        verify(alchemyManager).handlePotionBrewSuccesses(PotionStage.TWO, 1);
    }
}
