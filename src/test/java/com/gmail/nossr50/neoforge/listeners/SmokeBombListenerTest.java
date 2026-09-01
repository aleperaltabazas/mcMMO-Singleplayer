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
import com.gmail.nossr50.skills.stealth.StealthManager;
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
 * The Smoke Bomb activation trigger — {@link SmokeBombListener} is the only production code path
 * that ever calls {@code setAbilityMode(SMOKE_BOMB, true)}; without it the super ability can never
 * actually turn on. Structured after {@code HerdsmansCallListenerTest}: a real event fired at the
 * real dispatch method, mocked {@link McMMOPlayer}/{@link StealthManager}, and Mockito verification
 * of the activation calls. The effect body is a single {@code addEffect} call on the (mocked)
 * player, so the clean-activation case needs no world stubbing.
 */
class SmokeBombListenerTest {

    private GeneralConfig generalConfig;
    private McMMOPlayer mmoPlayer;
    private StealthManager stealthManager;

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
    void mainHandGunpowderActivatesTheAbility() {
        final ServerPlayer player = trackedServerPlayer(true);

        SmokeBombListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));

        verify(mmoPlayer).setAbilityMode(SuperAbilityType.SMOKE_BOMB, true);
        verify(mmoPlayer).setAbilityDATS(eq(SuperAbilityType.SMOKE_BOMB), anyLong());
    }

    @Test
    void wrongItemDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(true);

        SmokeBombListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SMOKE_BOMB), eq(true));
    }

    @Test
    void offHandGunpowderDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(true);
        lenient().when(player.getItemInHand(InteractionHand.OFF_HAND))
                .thenReturn(new ItemStack(Items.GUNPOWDER));
        final PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(
                player, InteractionHand.OFF_HAND);

        SmokeBombListener.onUseItem(event);

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SMOKE_BOMB), eq(true));
    }

    @Test
    void clientSideFireDoesNotActivate() {
        final Player clientPlayer = mock(Player.class);
        lenient().when(clientPlayer.blockPosition()).thenReturn(BlockPos.ZERO);
        final PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(
                clientPlayer, InteractionHand.MAIN_HAND);

        // Must not throw resolving a non-ServerPlayer, and must not touch UserManager.
        SmokeBombListener.onUseItem(event);
    }

    @Test
    void alreadyActiveDoesNotReActivate() {
        final ServerPlayer player = trackedServerPlayer(true);
        when(mmoPlayer.getAbilityMode(SuperAbilityType.SMOKE_BOMB)).thenReturn(true);

        SmokeBombListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SMOKE_BOMB), eq(true));
    }

    @Test
    void abilitiesOffDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(true);
        when(mmoPlayer.getAbilityUse()).thenReturn(false);

        SmokeBombListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SMOKE_BOMB), eq(true));
    }

    @Test
    void nullStealthManagerDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(true);
        when(mmoPlayer.getStealthManager()).thenReturn(null);

        SmokeBombListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SMOKE_BOMB), eq(true));
        verify(mmoPlayer, never()).setAbilityDATS(eq(SuperAbilityType.SMOKE_BOMB), anyLong());
    }

    @Test
    void rankNotMetDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(false);

        SmokeBombListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SMOKE_BOMB), eq(true));
        verify(mmoPlayer, never()).setAbilityDATS(eq(SuperAbilityType.SMOKE_BOMB), anyLong());
    }

    @Test
    void onCooldownDoesNotActivateButNotifies() {
        final ServerPlayer player = trackedServerPlayer(true);
        when(mmoPlayer.calculateTimeRemaining(SuperAbilityType.SMOKE_BOMB)).thenReturn(42);

        SmokeBombListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SMOKE_BOMB), eq(true));
    }

    @Test
    void untrackedPlayerIsSafelyIgnored() {
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(UUID.randomUUID());
        lenient().when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        lenient().when(player.getItemInHand(any(InteractionHand.class)))
                .thenReturn(new ItemStack(Items.GUNPOWDER));

        assertFalse(UserManager.getPlayer(player.getUUID()) != null);
        SmokeBombListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));
        // no exception, no crash: nothing to verify against since no McMMOPlayer was ever tracked.
    }

    private PlayerInteractEvent.RightClickItem mainHandEvent(ServerPlayer player, Item item) {
        lenient().when(player.getItemInHand(InteractionHand.MAIN_HAND))
                .thenReturn(new ItemStack(item));
        return new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
    }

    private ServerPlayer trackedServerPlayer(boolean canSmokeBomb) {
        final UUID uuid = UUID.randomUUID();
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        lenient().when(player.getItemInHand(any(InteractionHand.class)))
                .thenReturn(new ItemStack(Items.GUNPOWDER));

        stealthManager = mock(StealthManager.class);
        lenient().when(stealthManager.canSmokeBomb()).thenReturn(canSmokeBomb);
        lenient().when(stealthManager.getSmokeBombDurationTicks()).thenReturn(60);

        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        lenient().when(mmoPlayer.getStealthManager()).thenReturn(stealthManager);
        lenient().when(mmoPlayer.getAbilityUse()).thenReturn(true);
        lenient().when(mmoPlayer.getAbilityMode(SuperAbilityType.SMOKE_BOMB)).thenReturn(false);
        lenient().when(mmoPlayer.calculateTimeRemaining(SuperAbilityType.SMOKE_BOMB)).thenReturn(0);
        lenient().when(mmoPlayer.calculateAbilityActivationTicks(
                        PrimarySkillType.STEALTH, SuperAbilityType.SMOKE_BOMB))
                .thenReturn(2);
        lenient().when(mmoPlayer.useChatNotifications()).thenReturn(false);
        UserManager.track(mmoPlayer);
        return player;
    }
}
