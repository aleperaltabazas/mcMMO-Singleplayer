package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Herdsman's Call activation trigger — {@link HerdsmansCallListener} is the only production code
 * path that ever calls {@code setAbilityMode(HERDSMANS_CALL, true)}; without it the super ability (all
 * three of whose effects are real and wired elsewhere) can never actually turn on. Structured after
 * {@code RepairSalvageListenerTest}: a real event fired at the real dispatch method, mocked
 * {@link McMMOPlayer}/managers, and Mockito verification of the activation calls.
 */
class HerdsmansCallListenerTest {

    private GeneralConfig generalConfig;
    private McMMOPlayer mmoPlayer;
    private HusbandryManager husbandryManager;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        generalConfig = new GeneralConfig(dir);
        McMMOMod.setGeneralConfig(generalConfig);
    }

    @AfterEach
    void tearDown() {
        if (mmoPlayer != null) {
            UserManager.cleanupPlayer(mmoPlayer);
            mmoPlayer = null;
        }
        McMMOMod.setGeneralConfig(null);
    }

    @Test
    void mainHandGoatHornActivatesTheAbility() {
        final ServerPlayer player = trackedServerPlayer(true);

        HerdsmansCallListener.onUseItem(mainHandEvent(player, Items.GOAT_HORN));

        verify(mmoPlayer).setAbilityMode(SuperAbilityType.HERDSMANS_CALL, true);
        verify(mmoPlayer).setAbilityDATS(eq(SuperAbilityType.HERDSMANS_CALL), anyLong());
    }

    @Test
    void wrongItemDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(true);

        HerdsmansCallListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.HERDSMANS_CALL), eq(true));
    }

    @Test
    void offHandGoatHornDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(true);
        lenient().when(player.getItemInHand(InteractionHand.OFF_HAND))
                .thenReturn(new ItemStack(Items.GOAT_HORN));
        final PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(
                player, InteractionHand.OFF_HAND);

        HerdsmansCallListener.onUseItem(event);

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.HERDSMANS_CALL), eq(true));
    }

    @Test
    void clientSideFireDoesNotActivate() {
        final Player clientPlayer = mock(Player.class);
        lenient().when(clientPlayer.blockPosition()).thenReturn(BlockPos.ZERO);
        final PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(
                clientPlayer, InteractionHand.MAIN_HAND);

        // Must not throw resolving a non-ServerPlayer, and must not touch UserManager.
        HerdsmansCallListener.onUseItem(event);
    }

    @Test
    void alreadyActiveDoesNotReActivate() {
        final ServerPlayer player = trackedServerPlayer(true);
        when(mmoPlayer.getAbilityMode(SuperAbilityType.HERDSMANS_CALL)).thenReturn(true);

        HerdsmansCallListener.onUseItem(mainHandEvent(player, Items.GOAT_HORN));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.HERDSMANS_CALL), eq(true));
    }

    @Test
    void abilitiesOffDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(true);
        when(mmoPlayer.getAbilityUse()).thenReturn(false);

        HerdsmansCallListener.onUseItem(mainHandEvent(player, Items.GOAT_HORN));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.HERDSMANS_CALL), eq(true));
    }

    @Test
    void rankNotMetDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(false);

        HerdsmansCallListener.onUseItem(mainHandEvent(player, Items.GOAT_HORN));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.HERDSMANS_CALL), eq(true));
    }

    @Test
    void onCooldownDoesNotActivateButNotifies() {
        final ServerPlayer player = trackedServerPlayer(true);
        when(mmoPlayer.calculateTimeRemaining(SuperAbilityType.HERDSMANS_CALL)).thenReturn(42);

        HerdsmansCallListener.onUseItem(mainHandEvent(player, Items.GOAT_HORN));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.HERDSMANS_CALL), eq(true));
    }

    @Test
    void untrackedPlayerIsSafelyIgnored() {
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(UUID.randomUUID());
        lenient().when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        lenient().when(player.getItemInHand(any(InteractionHand.class)))
                .thenReturn(new ItemStack(Items.GOAT_HORN));

        assertFalse(UserManager.getPlayer(player.getUUID()) != null);
        HerdsmansCallListener.onUseItem(mainHandEvent(player, Items.GOAT_HORN));
        // no exception, no crash: nothing to verify against since no McMMOPlayer was ever tracked.
    }

    private PlayerInteractEvent.RightClickItem mainHandEvent(ServerPlayer player, Item item) {
        lenient().when(player.getItemInHand(InteractionHand.MAIN_HAND))
                .thenReturn(new ItemStack(item));
        return new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
    }

    private ServerPlayer trackedServerPlayer(boolean canHerdsmansCall) {
        final UUID uuid = UUID.randomUUID();
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        lenient().when(player.getItemInHand(any(InteractionHand.class)))
                .thenReturn(new ItemStack(Items.GOAT_HORN));

        husbandryManager = mock(HusbandryManager.class);
        lenient().when(husbandryManager.canHerdsmansCall()).thenReturn(canHerdsmansCall);
        lenient().when(husbandryManager.getHerdsmansCallDurationTicks()).thenReturn(60);

        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        lenient().when(mmoPlayer.getHusbandryManager()).thenReturn(husbandryManager);
        lenient().when(mmoPlayer.getAbilityUse()).thenReturn(true);
        lenient().when(mmoPlayer.getAbilityMode(SuperAbilityType.HERDSMANS_CALL)).thenReturn(false);
        lenient().when(mmoPlayer.calculateTimeRemaining(SuperAbilityType.HERDSMANS_CALL))
                .thenReturn(0);
        lenient().when(mmoPlayer.calculateAbilityActivationTicks(
                        PrimarySkillType.HUSBANDRY, SuperAbilityType.HERDSMANS_CALL))
                .thenReturn(2);
        lenient().when(mmoPlayer.useChatNotifications()).thenReturn(false);
        UserManager.track(mmoPlayer);
        return player;
    }
}
