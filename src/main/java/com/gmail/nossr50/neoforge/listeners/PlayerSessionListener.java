package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.database.ProfileStore;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Drives the per-player session lifecycle (Phase 5 persistence + Phase 3 join/quit hooks): loads a
 * player's {@link PlayerProfile} from the bound {@link ProfileStore} when they join, tracks the
 * resulting {@link McMMOPlayer} in {@link UserManager}, and saves + untracks it on disconnect.
 *
 * <p>Replaces the legacy {@code PlayerListener} join/quit handling (which attached the data to the
 * Bukkit player via metadata and scheduled an async DB load), and ports {@code fabric}'s
 * {@code PlayerSessionListener} (deleted whole-cloth in Task 8 along with the rest of {@code
 * fabric/} and never re-created — this file closes that gap). In the integrated singleplayer server
 * the local player joins immediately after the server starts — after {@link McMMOMod#onServerStarting}
 * has bound the store — so the store is always present by the time {@link #onJoin} fires.
 *
 * <p>Also owns the respawn half of the lifecycle ({@link #onRespawn}), which legacy split across
 * {@code PlayerListener#onPlayerRespawn} and a Bukkit {@code Player} object that stayed valid for the
 * whole session. Vanilla gives no such guarantee, so the respawn hook is load-bearing here in a way
 * it never was upstream.
 *
 * <p><b>PORT (event-shape note):</b> Fabric's {@code ServerPlayerEvents.AFTER_RESPAWN} (old player,
 * new player, {@code alive}) has no single NeoForge analog by that name; the equivalent here is
 * {@link PlayerEvent.Clone}, confirmed by reading NeoForge 21.1.248's shipped/patched sources:
 * {@code ServerPlayer#restoreFrom} calls {@code EventHooks.onPlayerClone(this, oldPlayer, wasDeath)}
 * with {@code this} being the brand-new {@link ServerPlayer} instance vanilla just constructed, and
 * {@code restoreFrom} is invoked from {@code PlayerList#respawn} right after that instance is built.
 * {@code PlayerEvent.Clone}'s own javadoc confirms it covers exactly the same two cases Fabric's hook
 * did: "Either caused by death, or by traveling from the End to the overworld" (its {@code
 * isWasDeath()} flag distinguishes them, mirroring Fabric's {@code alive} parameter). Both {@code
 * PlayerEvent.Clone} and {@link PlayerEvent.PlayerLoggedInEvent}/{@link
 * PlayerEvent.PlayerLoggedOutEvent} are plain {@code PlayerEvent} subclasses (not {@code
 * IModBusEvent}); {@code PlayerEvent}'s own class javadoc states "All children of this event are
 * fired on the {@code NeoForge#EVENT_BUS}", and {@code EventHooks.onPlayerClone} /
 * {@code firePlayerLoggedIn} / {@code firePlayerLoggedOut} all post to {@link NeoForge#EVENT_BUS}
 * directly (bytecode/source-verified) — so, same discriminator every other listener in this plan
 * used, all three are registered on the game bus below, not the mod-constructor bus.
 */
public final class PlayerSessionListener {

    private PlayerSessionListener() {
    }

    /**
     * Register the join/respawn/disconnect handlers. Called once at mod load from
     * {@link McMMOMod}'s constructor.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(PlayerSessionListener::onJoin);
        NeoForge.EVENT_BUS.addListener(PlayerSessionListener::onRespawn);
        NeoForge.EVENT_BUS.addListener(PlayerSessionListener::onQuit);
    }

    private static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer vanilla)) {
            return; // client-side event firing: ignore.
        }
        final PlatformPlayer player = new PlatformPlayer(vanilla);
        try {
            final ProfileStore store = McMMOMod.getProfileStore();
            if (store == null) {
                McMMOMod.LOGGER.warn(
                        "No mcMMO profile store bound on join for {}; skill data will not load.",
                        player.getName());
                return;
            }

            final boolean isNew = !store.hasProfile(player.getUniqueId());
            final PlayerProfile profile = store.loadProfile(player.getUniqueId(), player.getName(),
                    startingLevel());
            profile.updateLastLogin();
            if (isNew) {
                // Force the fresh profile to be written on the first save so the file exists even
                // if the player never gains XP this session.
                profile.markProfileDirty();
            }

            final McMMOPlayer mmoPlayer = new McMMOPlayer(player, profile);
            UserManager.track(mmoPlayer);
            McMMOMod.getTransientEntityTracker().initPlayer(player.getUniqueId());
            McMMOMod.LOGGER.info("Loaded mcMMO data for {} ({} profile).",
                    player.getName(), isNew ? "new" : "existing");
            announceProfileLoaded(mmoPlayer);
        } catch (Exception e) {
            McMMOMod.LOGGER.error("Failed to load mcMMO data for {} on join.", player.getName(), e);
        }
    }

    /**
     * Tell the player their skill data is loaded, if {@code General.Show_Profile_Loaded} is on —
     * legacy's {@code PlayerProfileLoadingTask} tail, which sent {@code Profile.Loading.Success}
     * from the async load callback. Here the load is synchronous, so it rides the end of the join.
     *
     * <p>Routed through {@link NotificationManager} rather than legacy's raw {@code sendMessage} so
     * it obeys the player's chat-notification toggle like every other mcMMO chat line.
     */
    private static void announceProfileLoaded(McMMOPlayer mmoPlayer) {
        if (McMMOMod.getGeneralConfig().getShowProfileLoadedMessage()) {
            NotificationManager.sendPlayerInformationChatOnly(mmoPlayer, "Profile.Loading.Success");
        }
    }

    /**
     * Re-point the player's mcMMO state at the entity vanilla just built for them, and stamp the
     * respawn timestamp the exploit guards read.
     *
     * <p>Legacy only did the second half ({@code PlayerListener#onPlayerRespawn} →
     * {@code actualizeRespawnATS}) because a Bukkit {@code Player} was a stable session-long handle.
     * Vanilla's {@code PlayerList#respawn} removes the old {@link ServerPlayer} and constructs a
     * replacement, so without {@link PlatformPlayer#rebind} every MC-typed call for the rest of the
     * session would target a removed entity. This fires for the End-exit path too ({@code
     * isWasDeath() == false}), not just death — hence rebinding unconditionally rather than on death
     * only. See the class javadoc for how {@link PlayerEvent.Clone} was confirmed to cover both
     * cases with the new entity as {@link PlayerEvent.Clone#getEntity()}.
     *
     * @param event carries the new (freshly constructed) entity as {@code getEntity()} and the
     *              outgoing one as {@link PlayerEvent.Clone#getOriginal()}
     */
    private static void onRespawn(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer vanilla)) {
            return; // client-side event firing: ignore.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(vanilla.getUUID());
        if (mmoPlayer == null) {
            // Only reachable if the join load failed or was skipped; the player simply has no mcMMO
            // state to re-point, but log it because a silent miss here degrades the whole session.
            McMMOMod.LOGGER.warn(
                    "No mcMMO data tracked for {} on respawn; skill data will not follow them.",
                    vanilla.getName().getString());
            return;
        }
        mmoPlayer.getPlayer().rebind(vanilla);
        mmoPlayer.actualizeRespawnATS();
    }

    private static void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer vanilla)) {
            return; // client-side event firing: ignore.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(vanilla.getUUID());
        if (mmoPlayer == null) {
            return;
        }
        try {
            mmoPlayer.getProfile().save(true);
        } catch (Exception e) {
            McMMOMod.LOGGER.error("Failed to save mcMMO data for {} on quit.",
                    mmoPlayer.getPlayerName(), e);
        } finally {
            UserManager.remove(vanilla.getUUID());
            // Despawn the player's Call-of-the-Wild summons so persistent pets aren't orphaned in the
            // saved world. Ordered after UserManager.remove: the summon's despawn resolves its owner
            // through UserManager to notify them, which is correctly skipped for a leaving player.
            McMMOMod.getTransientEntityTracker().cleanupPlayer(vanilla.getUUID());
        }
    }

    /** The starting level for a brand-new profile, from {@link AdvancedConfig} (defaulting to 0). */
    private static int startingLevel() {
        final AdvancedConfig advanced = McMMOMod.getAdvancedConfig();
        return advanced == null ? 0 : advanced.getStartingLevel();
    }
}
