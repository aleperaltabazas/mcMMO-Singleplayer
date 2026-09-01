package com.gmail.nossr50.neoforge.listeners;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Taming listener plan, Task A: {@link TamingListener#onEntityTamed}.
 *
 * <p>Covers the four gates the Fabric original established (non-{@code ServerPlayer} owner, no
 * loaded {@code McMMOPlayer}, no {@code TamingManager}, happy path) plus the registry-path lookup
 * for two distinct entity types (wolf, horse), the same species-uniformity concern
 * {@code HusbandryListenerBreedRaiseTest} exercises for breeding XP.
 */
class TamingListenerTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeAll
    static void bootstrapRegistries() throws Exception {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(OWNER_ID);
    }

    private static ServerPlayer owner() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(OWNER_ID);
        return player;
    }

    private static McMMOPlayer trackedMmoPlayer(ServerPlayer handle, TamingManager taming) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getTamingManager()).thenReturn(taming);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    @Test
    void nonServerPlayerOwnerIsANoOp() {
        final Player clientPlayer = mock(Player.class);
        final Wolf tamed = mock(Wolf.class);

        TamingListener.onEntityTamed(clientPlayer, tamed);

        // Nothing to verify on tamed/clientPlayer beyond "no exception" -- the instanceof gate
        // returns before touching UserManager at all, so there is no TamingManager to check either.
    }

    @Test
    void noLoadedMcMMOPlayerIsANoOp() {
        final ServerPlayer player = owner();
        // No UserManager.track call -- data not loaded (e.g. mid-join).
        final Wolf tamed = mock(Wolf.class);

        TamingListener.onEntityTamed(player, tamed);

        // No McMMOPlayer means no TamingManager was ever obtained; nothing further to verify.
    }

    @Test
    void noTamingManagerIsANoOp() {
        final ServerPlayer player = owner();
        trackedMmoPlayer(player, null);
        final Wolf tamed = mock(Wolf.class);
        when(tamed.getType()).thenReturn((EntityType) EntityType.WOLF);

        // Must not throw despite the null TamingManager.
        TamingListener.onEntityTamed(player, tamed);
    }

    @Test
    void happyPathAwardsTamingXpForAWolf() {
        final ServerPlayer player = owner();
        final TamingManager taming = mock(TamingManager.class);
        trackedMmoPlayer(player, taming);

        final Wolf tamed = mock(Wolf.class);
        when(tamed.getType()).thenReturn((EntityType) EntityType.WOLF);

        TamingListener.onEntityTamed(player, tamed);

        verify(taming).awardTamingXP("Wolf");
    }

    @Test
    void happyPathAwardsTamingXpForAHorseProvingTheRegistryPathLookupIsCorrect() {
        final ServerPlayer player = owner();
        final TamingManager taming = mock(TamingManager.class);
        trackedMmoPlayer(player, taming);

        final Horse tamed = mock(Horse.class);
        when(tamed.getType()).thenReturn((EntityType) EntityType.HORSE);

        TamingListener.onEntityTamed(player, tamed);

        verify(taming).awardTamingXP("Horse");
        verify(taming, never()).awardTamingXP("Wolf");
    }
}
