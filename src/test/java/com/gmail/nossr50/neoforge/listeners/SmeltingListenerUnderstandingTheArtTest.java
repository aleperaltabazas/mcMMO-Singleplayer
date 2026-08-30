package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.smelting.SmeltingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises Understanding the Art's extraction bracket ({@link SmeltingListener#beginFurnaceExtract}
 * / {@link SmeltingListener#boostVanillaXp} / {@link SmeltingListener#endFurnaceExtract}), the seam
 * {@code FurnaceResultSlotMixin} drives (Task B). Per the task brief's acceptance criteria: no
 * multiplier when unranked, the multiplier applies only to indexed ore-smelt products (not
 * arbitrary furnace output), and the thread-local is cleared after {@link
 * SmeltingListener#endFurnaceExtract}.
 */
class SmeltingListenerUnderstandingTheArtTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private SmeltingManager smeltingManager;
    private ServerPlayer serverPlayer;

    @BeforeEach
    void setUp() {
        smeltingManager = mock(SmeltingManager.class);
        final PlatformPlayer platformPlayer = mock(PlatformPlayer.class);
        when(platformPlayer.getUniqueId()).thenReturn(PLAYER_ID);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(platformPlayer);
        when(mmoPlayer.getSmeltingManager()).thenReturn(smeltingManager);
        UserManager.track(mmoPlayer);

        serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(PLAYER_ID);

        SmeltingListener.setSmeltedOreProductsForTesting(Set.of(Items.IRON_INGOT));
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        SmeltingListener.clearOwners(); // also resets the smelted-ore-product index.
        SmeltingListener.endFurnaceExtract(); // belt-and-suspenders: never leak into another test.
    }

    private static Integer readMultiplier() {
        try {
            final Field field = SmeltingListener.class.getDeclaredField("VANILLA_XP_MULTIPLIER");
            field.setAccessible(true);
            final ThreadLocal<?> threadLocal = (ThreadLocal<?>) field.get(null);
            return (Integer) threadLocal.get();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void unrankedPlayerGetsNoMultiplierAndNoXpBoost() {
        when(smeltingManager.getVanillaXpBoostMultiplier()).thenReturn(1); // unranked/off.

        SmeltingListener.beginFurnaceExtract(serverPlayer, new ItemStack(Items.IRON_INGOT));

        assertNull(readMultiplier(), "unranked players stash no multiplier");
        assertEquals(5, SmeltingListener.boostVanillaXp(5), "no multiplier -> vanilla amount");

        SmeltingListener.endFurnaceExtract();
    }

    @Test
    void rankedPlayerExtractingAnIndexedOreProductGetsTheMultiplier() {
        when(smeltingManager.getVanillaXpBoostMultiplier()).thenReturn(3);

        SmeltingListener.beginFurnaceExtract(serverPlayer, new ItemStack(Items.IRON_INGOT));

        assertEquals(3, readMultiplier(), "ranked, indexed product -> multiplier stashed");
        assertEquals(15, SmeltingListener.boostVanillaXp(5), "5 * 3 multiplier");

        SmeltingListener.endFurnaceExtract();
        assertNull(readMultiplier(), "cleared after endFurnaceExtract");
        assertEquals(5, SmeltingListener.boostVanillaXp(5), "no multiplier after clearing");
    }

    @Test
    void rankedPlayerExtractingANonIndexedItemGetsNoMultiplier() {
        when(smeltingManager.getVanillaXpBoostMultiplier()).thenReturn(3);
        // Bread is not in the smelted-ore-product index (not an ore-smelt output at all).
        SmeltingListener.beginFurnaceExtract(serverPlayer, new ItemStack(Items.BREAD));

        assertNull(readMultiplier(), "arbitrary furnace output (not an indexed ore product) -> no boost");
        assertEquals(5, SmeltingListener.boostVanillaXp(5));

        SmeltingListener.endFurnaceExtract();
    }

    @Test
    void endFurnaceExtractClearsTheThreadLocalEvenWithNoPriorBegin() {
        SmeltingListener.endFurnaceExtract();
        assertNull(readMultiplier());
    }

    @Test
    void beginFurnaceExtractIsANoOpForAClientSidePlayerOrEmptyStack() {
        when(smeltingManager.getVanillaXpBoostMultiplier()).thenReturn(3);
        final var clientPlayer = mock(net.minecraft.world.entity.player.Player.class);

        SmeltingListener.beginFurnaceExtract(clientPlayer, new ItemStack(Items.IRON_INGOT));
        assertNull(readMultiplier(), "not a ServerPlayer -> no-op");

        SmeltingListener.beginFurnaceExtract(serverPlayer, ItemStack.EMPTY);
        assertNull(readMultiplier(), "empty stack -> no-op");
    }
}
