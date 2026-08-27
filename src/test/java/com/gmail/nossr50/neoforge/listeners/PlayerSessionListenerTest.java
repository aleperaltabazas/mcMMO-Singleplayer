package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.database.FlatFileProfileStore;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link PlayerSessionListener}'s join/respawn/quit handlers -- the only code in the NeoForge
 * port that calls {@link UserManager#track}. Without it, no joining player ever gets tracked and
 * every gameplay listener that resolves {@code UserManager.getPlayer(uuid)} silently sees
 * {@code null} (the whole-branch-review bug this file was added to fix -- see the class javadoc on
 * {@link PlayerSessionListener}).
 *
 * <p>Follows {@link com.gmail.nossr50.platform.MobOriginsTest}'s conventions: {@link
 * McTestRegistries#bootstrap()} once in {@code @BeforeAll}, Mockito for the {@link ServerPlayer}
 * handles, and an {@code @AfterEach} that undoes whatever process-lifetime static state a test
 * touched -- here that's {@link UserManager}'s tracked-player map plus the {@link McMMOMod} config/
 * profile-store statics {@link com.gmail.nossr50.database.FlatFileProfileStoreTest} also resets.
 */
class PlayerSessionListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID UNTRACKED_PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        // UserManager's map is a static, process-lifetime table (see MobOriginsTest's
        // clearAttachments for the same argument about neoforge.McMMOAttachments) -- without this a
        // player tracked by one test would leak into the next.
        UserManager.remove(PLAYER_ID);
        UserManager.remove(UNTRACKED_PLAYER_ID);
        McMMOMod.setProfileStore(null);
        McMMOMod.setGeneralConfig(null);
        McMMOMod.setAdvancedConfig(null);
    }

    private static ServerPlayer serverPlayer(UUID uuid, String name) {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(uuid);
        when(handle.getName()).thenReturn(Component.literal(name));
        return handle;
    }

    /** A minimal real config pair, bound so {@code onJoin}'s tail (the loaded-message + starting
     *  level reads) doesn't NPE -- {@code AdvancedConfig}/{@code GeneralConfig} against a temp data
     *  folder, same fixture shape as {@code GeneralConfigTest}/{@code AdvancedConfigTest}. */
    private static void bindMinimalConfig(Path dir) {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir.resolve("general")));
        McMMOMod.setAdvancedConfig(new AdvancedConfig(dir.resolve("advanced")));
    }

    // --- onJoin --------------------------------------------------------------------------------

    @Test
    void onJoinTracksThePlayer(@TempDir Path dir) {
        bindMinimalConfig(dir);
        McMMOMod.setProfileStore(new FlatFileProfileStore(dir.resolve("players")));
        final ServerPlayer vanilla = serverPlayer(PLAYER_ID, "Steve");

        PlayerSessionListener.onJoin(new PlayerEvent.PlayerLoggedInEvent(vanilla));

        // Mutation check: if UserManager.track were ever dropped from onJoin, this goes null and
        // the whole gameplay pipeline (XP, abilities, commands) would be silently dead on every
        // real join, exactly as the bug this file fixed.
        final McMMOPlayer tracked = UserManager.getPlayer(PLAYER_ID);
        assertNotNull(tracked, "onJoin must track the joining player via UserManager");
        assertSame(vanilla, tracked.getPlayer().unwrap(),
                "the tracked player's handle must be the joining entity");
    }

    @Test
    void onJoinWithNoBoundProfileStoreDoesNotTrack(@TempDir Path dir) {
        // McMMOMod.setProfileStore is never called here -- the "server hasn't finished starting
        // yet" case the handler's own null-store guard exists for.
        bindMinimalConfig(dir);
        final ServerPlayer vanilla = serverPlayer(PLAYER_ID, "Steve");

        assertDoesNotThrow(() -> PlayerSessionListener.onJoin(
                new PlayerEvent.PlayerLoggedInEvent(vanilla)));

        assertNull(UserManager.getPlayer(PLAYER_ID),
                "with no profile store bound, onJoin must not track anyone");
    }

    // Note: the "client-side event firing" guard (`!(event.getEntity() instanceof ServerPlayer)`)
    // is not exercised here. PlayerEvent's own constructor takes a plain vanilla `Player`, but the
    // only other production subclass in this codebase's reach is `ServerPlayer` itself -- there is
    // no cheap non-ServerPlayer `Player` to mock (`Player` is abstract with a large constructor
    // surface, and a client-side `LocalPlayer`/`RemotePlayer` mock would need Minecraft's client
    // registries this test's headless bootstrap doesn't set up). The guard is a one-line
    // `instanceof` check with nothing else behind it, so this is a coverage gap in form only.

    // --- onRespawn -------------------------------------------------------------------------------

    private static McMMOPlayer trackDirectly(UUID uuid, ServerPlayer handle, Path storeDir) {
        final PlayerProfile profile =
                new FlatFileProfileStore(storeDir).loadProfile(uuid, "Steve", 0);
        final McMMOPlayer mmoPlayer = new McMMOPlayer(new PlatformPlayer(handle), profile);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    @Test
    void onRespawnRebindsTheTrackedPlayerToTheNewEntity(@TempDir Path dir) {
        bindMinimalConfig(dir);
        final ServerPlayer original = serverPlayer(PLAYER_ID, "Steve");
        trackDirectly(PLAYER_ID, original, dir.resolve("players"));

        final ServerPlayer respawned = serverPlayer(PLAYER_ID, "Steve");
        PlayerSessionListener.onRespawn(new PlayerEvent.Clone(respawned, original, true));

        final McMMOPlayer tracked = UserManager.getPlayer(PLAYER_ID);
        assertNotNull(tracked);
        // Mutation check: if PlatformPlayer#rebind were a no-op, this would still equal `original`
        // -- every MC-typed call for the rest of the session would target the removed entity.
        assertSame(respawned, tracked.getPlayer().unwrap(),
                "onRespawn must rebind the tracked player's handle to the freshly constructed entity");
    }

    @Test
    void onRespawnForAnUntrackedPlayerLogsAndDoesNotThrow() {
        final ServerPlayer original = serverPlayer(UNTRACKED_PLAYER_ID, "Ghost");
        final ServerPlayer respawned = serverPlayer(UNTRACKED_PLAYER_ID, "Ghost");

        assertDoesNotThrow(() -> PlayerSessionListener.onRespawn(
                new PlayerEvent.Clone(respawned, original, false)));

        assertNull(UserManager.getPlayer(UNTRACKED_PLAYER_ID),
                "no mcMMO state should spring into existence for a player nothing tracked");
    }

    // --- onQuit ----------------------------------------------------------------------------------

    @Test
    void onQuitSavesAndUntracksThePlayer(@TempDir Path dir) {
        bindMinimalConfig(dir);
        final Path playersDir = dir.resolve("players");
        final FlatFileProfileStore store = new FlatFileProfileStore(playersDir);
        McMMOMod.setProfileStore(store);
        final ServerPlayer vanilla = serverPlayer(PLAYER_ID, "Steve");
        final McMMOPlayer mmoPlayer = trackDirectly(PLAYER_ID, vanilla, playersDir);
        // Dirty the profile so save() actually has something to persist -- otherwise a no-op save
        // would look identical to a broken one from this test's point of view.
        mmoPlayer.getProfile().addLevels(
                com.gmail.nossr50.datatypes.skills.PrimarySkillType.MINING, 5);

        PlayerSessionListener.onQuit(new PlayerEvent.PlayerLoggedOutEvent(vanilla));

        // Mutation check: if UserManager.remove were dropped from onQuit, this would still resolve
        // and the player's mcMMO state would silently outlive their session.
        assertNull(UserManager.getPlayer(PLAYER_ID), "onQuit must untrack the player");
        // And the save path actually ran: the dirtied level is on disk under a fresh load.
        final PlayerProfile reloaded = store.loadProfile(PLAYER_ID, "Steve", 0);
        assertEquals(5, reloaded.getSkillLevel(
                com.gmail.nossr50.datatypes.skills.PrimarySkillType.MINING),
                "onQuit must have saved the dirtied profile to the bound store");
    }

    @Test
    void onQuitForAnUntrackedPlayerIsANoOp() {
        final ServerPlayer vanilla = serverPlayer(UNTRACKED_PLAYER_ID, "Ghost");

        assertDoesNotThrow(() -> PlayerSessionListener.onQuit(
                new PlayerEvent.PlayerLoggedOutEvent(vanilla)));

        assertNull(UserManager.getPlayer(UNTRACKED_PLAYER_ID));
    }
}
