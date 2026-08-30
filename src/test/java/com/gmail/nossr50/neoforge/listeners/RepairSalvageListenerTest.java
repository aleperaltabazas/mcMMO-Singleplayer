package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.repair.RepairManager;
import com.gmail.nossr50.skills.repair.repairables.Repairable;
import com.gmail.nossr50.skills.repair.repairables.RepairableManager;
import com.gmail.nossr50.skills.salvage.salvageables.Salvageable;
import com.gmail.nossr50.skills.salvage.salvageables.SalvageableManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The anvil dispatch in {@link RepairSalvageListener} — specifically <em>who the click belongs to</em>,
 * ported from the Fabric original's regression test. See that class's own javadoc for the mechanism:
 * a client-side fire must set {@link TriState#FALSE} on {@code event.getUseItem()} or the client
 * falls through to using the item (equipping armor being repaired).
 */
class RepairSalvageListenerTest {

    private static final BlockPos ANVIL_POS = new BlockPos(4, 64, -7);

    private GeneralConfig generalConfig;
    private Level world;
    private McMMOPlayer mmoPlayer;
    private RepairManager repairManager;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        generalConfig = new GeneralConfig(dir);
        McMMOMod.setGeneralConfig(generalConfig);

        final RepairableManager repairables = mock(RepairableManager.class);
        lenient().when(repairables.getRepairable("iron_chestplate"))
                .thenReturn(mock(Repairable.class));
        McMMOMod.setRepairableManager(repairables);

        final SalvageableManager salvageables = mock(SalvageableManager.class);
        lenient().when(salvageables.getSalvageable("iron_chestplate"))
                .thenReturn(mock(Salvageable.class));
        McMMOMod.setSalvageableManager(salvageables);

        world = mock(Level.class);
        placeAnvil(Blocks.STONE);
    }

    @AfterEach
    void tearDown() {
        if (mmoPlayer != null) {
            UserManager.cleanupPlayer(mmoPlayer);
            mmoPlayer = null;
        }
        McMMOMod.setGeneralConfig(null);
        McMMOMod.setRepairableManager(null);
        McMMOMod.setSalvageableManager(null);
    }

    @Test
    void clientSideFireOnTheRepairAnvilClaimsTheClick() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(damagedChestplate()));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.FALSE, event.getUseItem(),
                "the client fire must claim the anvil click, or the client falls through to "
                        + "\"use item\" and equips the armour being repaired");
    }

    @Test
    void clientSideFireOnTheSalvageAnvilClaimsTheClick() {
        placeAnvil(Blocks.GOLD_BLOCK);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(damagedChestplate()));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.FALSE, event.getUseItem());
    }

    @Test
    void clientSideFireWithAnItemMcmmoDoesNotWorkOnPasses() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(new ItemStack(Items.GOLDEN_APPLE)));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
    }

    @Test
    void clientSideFireWithAnEmptyHandPasses() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event = anvilEvent(clientPlayer(ItemStack.EMPTY));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
    }

    @Test
    void clientSideFireOnAnOrdinaryBlockPasses() {
        placeAnvil(Blocks.STONE);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(damagedChestplate()));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
    }

    @Test
    void offHandFirePasses() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                clientPlayer(damagedChestplate()), InteractionHand.OFF_HAND, ANVIL_POS, anvilHit());

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
        assertFalse(event.isCanceled());
    }

    @Test
    void serverSideFireOnTheRepairAnvilArmsTheConfirmation() {
        placeAnvil(Blocks.IRON_BLOCK);
        final ServerPlayer player = trackedServerPlayer(damagedChestplate());
        when(repairManager.checkConfirmation(true)).thenReturn(false);
        final PlayerInteractEvent.RightClickBlock event = anvilEvent(player);

        RepairSalvageListener.onUseBlock(event);

        verify(repairManager).checkConfirmation(true);
    }

    private void placeAnvil(Block block) {
        final BlockState state = block.defaultBlockState();
        lenient().when(world.getBlockState(ANVIL_POS)).thenReturn(state);
    }

    private static BlockHitResult anvilHit() {
        return new BlockHitResult(Vec3.atCenterOf(ANVIL_POS), Direction.UP, ANVIL_POS, false);
    }

    private PlayerInteractEvent.RightClickBlock anvilEvent(Player player) {
        return new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, ANVIL_POS,
                anvilHit());
    }

    private static ItemStack damagedChestplate() {
        final ItemStack stack = new ItemStack(Items.IRON_CHESTPLATE);
        stack.setDamageValue(100);
        return stack;
    }

    private Player clientPlayer(ItemStack mainHand) {
        final Player player = mock(Player.class);
        lenient().when(player.getMainHandItem()).thenReturn(mainHand);
        lenient().when(player.level()).thenReturn(world);
        return player;
    }

    private ServerPlayer trackedServerPlayer(ItemStack mainHand) {
        final UUID uuid = UUID.randomUUID();
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.getMainHandItem()).thenReturn(mainHand);
        lenient().when(player.level()).thenReturn(world);

        repairManager = mock(RepairManager.class);
        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        lenient().when(mmoPlayer.getRepairManager()).thenReturn(repairManager);
        UserManager.track(mmoPlayer);
        return player;
    }
}
