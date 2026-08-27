package com.gmail.nossr50.neoforge;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.config.CoreSkillsConfig;
import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.config.RankConfig;
import com.gmail.nossr50.config.SoundConfig;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.config.skills.alchemy.PotionConfig;
import com.gmail.nossr50.config.treasure.FishingTreasureConfig;
import com.gmail.nossr50.config.treasure.TreasureConfig;
import com.gmail.nossr50.database.FlatFileProfileStore;
import com.gmail.nossr50.database.PlacedBlockStore;
import com.gmail.nossr50.database.ProfileStore;
import com.gmail.nossr50.event.EventBus;
import com.gmail.nossr50.event.SimpleEventBus;
import com.gmail.nossr50.neoforge.commands.McMMOCommands;
import com.gmail.nossr50.neoforge.listeners.BlockBreakListener;
import com.gmail.nossr50.neoforge.listeners.PlayerSessionListener;
import com.gmail.nossr50.neoforge.listeners.SuperAbilityListener;
import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.scheduler.TickScheduler;
import com.gmail.nossr50.runnables.SaveTimerTask;
import com.gmail.nossr50.runnables.player.ClearRegisteredXPGainTask;
import com.gmail.nossr50.skills.repair.repairables.RepairableManager;
import com.gmail.nossr50.skills.salvage.salvageables.SalvageableManager;
import com.gmail.nossr50.skills.taming.CallOfTheWild;
import com.gmail.nossr50.util.MaterialMapStore;
import com.gmail.nossr50.util.PlacedBlockTracker;
import com.gmail.nossr50.util.TransientEntityTracker;
import com.gmail.nossr50.util.experience.FormulaManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.skills.SkillAvailability;
import com.gmail.nossr50.util.skills.SkillTools;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (client + server) entry point for the mcMMO NeoForge mod.
 *
 * <p>Replaces {@code com.gmail.nossr50.fabric.McMMOMod} and doubles as the central service
 * locator that the legacy code reached through the {@code mcMMO.p} singleton (and that the
 * Fabric port reached through {@code fabric.McMMOMod}). Keeping a single holder here lets the
 * ~279 originally-Bukkit-coupled files port mechanically ({@code mcMMO.getX()} ->
 * {@code McMMOMod.getX()}) instead of being rewired per-platform.
 *
 * <p>Lifecycle mapping (mirrors {@code fabric.McMMOMod}'s javadoc, translated to NeoForge):
 * <ul>
 *   <li>{@code JavaPlugin#onEnable}  -> {@link ServerStartingEvent} ({@link #onServerStarting}),
 *       registered on the game bus ({@link NeoForge#EVENT_BUS}), plus one-time mod-bus
 *       construction below.</li>
 *   <li>{@code JavaPlugin#onDisable} -> {@link ServerStoppingEvent} ({@link #onServerStopping}),
 *       also on the game bus.</li>
 * </ul>
 *
 * <p>In singleplayer the integrated server starts/stops each time a world is opened/closed, so
 * per-world manager init and data save/teardown belong in the lifecycle handlers, not in the
 * constructor (which fires once at mod load, on the mod bus).
 *
 * <p><b>Event bus choice:</b> {@link ServerStartingEvent}, {@link ServerStoppingEvent} and
 * {@link ServerTickEvent.Post} are all plain {@code net.neoforged.bus.api.Event} subclasses that
 * do NOT implement {@code net.neoforged.fml.event.IModBusEvent}; NeoForge's own
 * {@code ServerLifecycleHooks} posts the two lifecycle events to {@link NeoForge#EVENT_BUS} (the
 * game bus), confirmed by reading NeoForge 21.1.248's shipped sources
 * ({@code net/neoforged/neoforge/server/ServerLifecycleHooks.java}). They are therefore
 * registered on the game bus below, not the mod-constructor bus.
 */
@Mod(McMMOMod.MOD_ID)
public final class McMMOMod {

    public static final String MOD_ID = "mcmmo";
    public static final Logger LOGGER = LoggerFactory.getLogger("mcMMO");

    private static volatile MinecraftServer server;

    /**
     * mcMMO's internal event bus (Phase 3). Replaces Bukkit's event system for mcMMO's own
     * {@code events/*} events. Created once at mod load and lives for the whole JVM: it holds no
     * per-world state, so subscriptions registered by ported subsystems survive across
     * singleplayer world open/close cycles. Emitted events are fired on the server thread.
     */
    private static final EventBus eventBus = new SimpleEventBus();

    /**
     * mcMMO's server-tick scheduler (Phase 11). Replaces the legacy FoliaLib scheduler; every
     * ported {@code runnables/} task is submitted here. Created once at mod load and pumped by
     * {@link ServerTickEvent.Post}; its task queue is cleared at each server stop, so one
     * instance safely spans singleplayer world open/close cycles. Holds no per-world state.
     */
    private static final TickScheduler scheduler = new TickScheduler();

    /**
     * Per-player registry of Call-of-the-Wild summons (Taming §C). Created once at mod load and
     * lives for the JVM; it holds only transient in-memory summon handles (cleared per player on
     * logout and per summon on despawn), so one instance safely spans world open/close cycles.
     * Replaces legacy {@code mcMMO.getTransientEntityTracker()}.
     */
    private static final TransientEntityTracker transientEntityTracker = new TransientEntityTracker();

    /**
     * Registry of hand-placed blocks that must not give gathering rewards (the port's replacement
     * for legacy {@code mcMMO.getUserBlockTracker()} — see §A). Created once at mod load; its
     * flags are per-world, so they are loaded from the world save at server start and written
     * back and dropped at world close ({@link #onServerStarting} / {@link #onServerStopping}) via
     * {@link #placedBlockStore}.
     */
    private static final PlacedBlockTracker placedBlockTracker = new PlacedBlockTracker();

    /**
     * Cross-restart persistence for {@link #placedBlockTracker} (legacy's
     * {@code McMMOSimpleRegionFile} shard set, collapsed to one flat file). Bound at server start
     * once the world save path is known and cleared at server stop; {@code null} outside a world
     * session, in which case the flags are session-only — the pre-persistence behaviour.
     */
    private static volatile PlacedBlockStore placedBlockStore;

    /** Ticks in one real-time minute (20 tps x 60 s). Autosave interval is configured in minutes. */
    private static final long TICKS_PER_MINUTE = 20L * 60L;

    /**
     * Skill metadata/relationship registry (subskill<->parent, super-ability<->skill, tool maps,
     * localized name lists). Legacy code reached it via {@code mcMMO.p.getSkillTools()}. It holds
     * no per-world state and only reads the (English) locale bundle, so it is built lazily on
     * first access and lives for the whole JVM.
     */
    private static volatile SkillTools skillTools;

    /**
     * MC-free item/block classification tables (registry-path String membership). Legacy code
     * reached it via {@code mcMMO.getMaterialMapStore()}; it needs no world session (pure
     * hardcoded sets), so it is built lazily on first access and lives for the whole JVM.
     */
    private static volatile MaterialMapStore materialMapStore;

    /**
     * XP-curve engine (level <-> experience conversions). Legacy code reached it via
     * {@code mcMMO.getFormulaManager()}. Like {@link #skillTools} it holds no per-world state and
     * only reads {@link ExperienceConfig}/{@link GeneralConfig} on demand, so it is built lazily
     * on first access and lives for the whole JVM.
     */
    private static volatile FormulaManager formulaManager;

    /**
     * The per-world player-data store (Phase 5). Bound at server start once the world save path
     * is known and cleared at server stop; {@code null} outside a world session (and in unit
     * tests that don't exercise persistence, where
     * {@link com.gmail.nossr50.datatypes.player.PlayerProfile#save} degrades to a no-op).
     * Replaces the legacy {@code DatabaseManager} singleton.
     */
    private static volatile ProfileStore profileStore;

    private static volatile GeneralConfig generalConfig;
    private static volatile ExperienceConfig experienceConfig;
    private static volatile CoreSkillsConfig coreSkillsConfig;
    private static volatile RankConfig rankConfig;
    private static volatile SoundConfig soundConfig;
    private static volatile AdvancedConfig advancedConfig;
    private static volatile TreasureConfig treasureConfig;
    private static volatile FishingTreasureConfig fishingTreasureConfig;
    private static volatile PotionConfig potionConfig;
    private static volatile RepairableManager repairableManager;
    private static volatile SalvageableManager salvageableManager;
    private static volatile CallOfTheWild callOfTheWild;

    // Both constructor-injected parameters below are currently unused: nothing in this mod has
    // needed to register on the mod bus (modEventBus) or read mod metadata (modContainer) yet.
    // Kept as parameters because NeoForge's @Mod constructor-injection requires this signature
    // shape to be satisfied to receive either one, should a later task need them.
    public McMMOMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("mcMMO (NeoForge) initializing.");

        // Register lifecycle hooks once at mod load, on the GAME bus: ServerStartingEvent /
        // ServerStoppingEvent / ServerTickEvent.Post are not IModBusEvent subtypes and are posted
        // by NeoForge's ServerLifecycleHooks / server tick loop to NeoForge.EVENT_BUS, not the
        // mod-construction event bus passed in here. The handlers run every time the (integrated)
        // server starts/stops, which in singleplayer is per world session.
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        // Phase 11: pump the task scheduler once per server tick.
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> scheduler.tick());

        // Task 5: the block-break and activation pipeline (Mining + shared plumbing). Remaining
        // listeners land here as each subsystem's NeoForge wiring is ported in a later task; see
        // fabric.McMMOMod#onInitialize for the full list this entry point will grow to match.
        BlockBreakListener.register();
        SuperAbilityListener.register();

        // Whole-branch review fix: the join/respawn/quit lifecycle. This is what actually calls
        // UserManager.track(...); without it no player ever has tracked mcMMO data and every
        // gameplay listener above resolves UserManager.getPlayer(uuid) to null.
        PlayerSessionListener.register();

        // Task 7: in-game commands (/mcmmo, /mcstats, /mcability, /mcrefresh, /addlevels, /addxp).
        // RegisterCommandsEvent is not an IModBusEvent -- it is fired on the game bus by vanilla's
        // Commands construction (see RegisterCommandsEvent's javadoc), so it is registered on
        // NeoForge.EVENT_BUS from inside McMMOCommands.register(), same discipline as the
        // lifecycle hooks above.
        McMMOCommands.register();
    }

    /** Equivalent of {@code onEnable}: per-session init when a world's server starts. */
    private void onServerStarting(ServerStartingEvent event) {
        final MinecraftServer startingServer = event.getServer();
        server = startingServer;
        // Unbind the previous session's placed-block store before anything below can throw. A
        // world whose stop handler never ran (a crash) leaves the old store bound, and if this
        // start then failed part-way the autosave would write THIS world's flags into THAT
        // world's file.
        placedBlockStore = null;
        try {
            LOGGER.info("mcMMO enabling for server session.");
            // Phase 8: load config files from <configDir>/mcmmo. Resolved via FMLPaths so the
            // configs live alongside every other mod's config, not inside the world save.
            ConfigBootstrap.loadAll(FMLPaths.CONFIGDIR.get().resolve(MOD_ID));
            // Ask this Minecraft version which skills it can actually furnish, now that the item
            // registry is certainly populated. Deliberately probed once here rather than lazily
            // on the first XP award -- see SkillAvailability, which explains why an absence read
            // at the wrong moment is indistinguishable from a version that genuinely lacks the
            // items.
            SkillAvailability.probe();
            // TODO 5.5 / risk R5: same moment, same argument. The shipped configs name ~690 items
            // and blocks and the mod supports a band of Minecraft versions, so a row naming
            // something this version does not have is normal -- dropping it SILENTLY is not.
            pruneConfigEntriesUnavailableOnThisVersion();
            // Phase 5: bind the per-world profile store under <worldRoot>/mcmmo/players/. Player
            // profiles load lazily on join, via neoforge.listeners.PlayerSessionListener, not
            // eagerly here.
            final Path modDataDir = startingServer.getWorldPath(LevelResource.ROOT).resolve(MOD_ID);
            McMMOMod.setProfileStore(new FlatFileProfileStore(modDataDir.resolve("players")));
            // §A/K9: load this world's hand-placed-block flags before any block can be broken, so
            // a block placed in a previous session is still ineligible for gathering rewards.
            // Bound per world (the flags are world state, like the profiles above), and re-saved
            // on the autosave tick and at server stop.
            final PlacedBlockStore blockStore =
                    new PlacedBlockStore(modDataDir.resolve("placed_blocks.dat"));
            blockStore.load(placedBlockTracker);
            placedBlockStore = blockStore;
            // Phase 11: schedule the periodic autosave (interval = General.Save_Interval minutes,
            // floored at 1 minute). Cancelled in onServerStopping.
            final int saveIntervalMinutes = generalConfig != null ? generalConfig.getSaveInterval()
                    : 10;
            final long saveIntervalTicks = Math.max(TICKS_PER_MINUTE,
                    saveIntervalMinutes * TICKS_PER_MINUTE);
            scheduler.runTimer(new SaveTimerTask(), saveIntervalTicks, saveIntervalTicks);
            // Phase 11: expire stale diminished-returns XP records every 60 ticks (matches
            // legacy), so the rolling per-skill XP totals don't grow unbounded. Cancelled in
            // onServerStopping.
            scheduler.runTimer(new ClearRegisteredXPGainTask(), 60, 60);
        } catch (Throwable t) {
            LOGGER.error("Error while enabling mcMMO for the server session", t);
        }
    }

    /**
     * Drop (or, for the XP tables, name) every config row whose item/block this Minecraft version
     * does not have — TODO 5.5 / risk R5. See {@code fabric.McMMOMod}'s equivalent for the full
     * rationale; behavior here is unchanged from the reference.
     */
    private void pruneConfigEntriesUnavailableOnThisVersion() {
        try {
            if (treasureConfig != null) {
                treasureConfig.pruneUnavailableEntries();
            }
            if (fishingTreasureConfig != null) {
                fishingTreasureConfig.pruneUnavailableEntries();
            }
            if (experienceConfig != null) {
                experienceConfig.reportUnresolvableRows();
            }
        } catch (Exception e) {
            LOGGER.error("Could not audit shipped config ids against this Minecraft version;"
                    + " configs are left as loaded.", e);
        }
    }

    /** Equivalent of {@code onDisable}: per-session save + teardown when the server stops. */
    private void onServerStopping(ServerStoppingEvent event) {
        try {
            LOGGER.info("mcMMO server session stopping, saving and cleaning up data.");
            // Phase 11: stop the tick pump's queue before the final save so no autosave races the
            // shutdown flush below.
            scheduler.cancelAll();
            // Phase 5: flush every online player's profile to disk, then drop the registry so the
            // next world session starts clean.
            UserManager.saveAll();
            UserManager.clearAll();
            McMMOMod.setProfileStore(null);
            // Drop transient per-entity markers (Rupture bleeds, dodge-XP counters, tracked TNT).
            // Bukkit dropped plugin metadata on disable; our side-table has no such lifecycle, and
            // entity UUIDs persist to disk — so without this a marker outlives the session that
            // owned it. The tasks those markers point at were just killed by cancelAll() above,
            // and a leaked rupture marker would make its target permanently immune to Rupture.
            MetadataStore.clearAll();
            // Same argument, same table shape: MOB_ORIGIN persists entity UUIDs to disk too, so it
            // must not outlive the session that owned it either.
            McMMOAttachments.clearAll();
            // §A/K9: write this world's hand-placed-block flags back to its save, THEN drop them —
            // the order matters, since clearing first would persist an empty set and hand the
            // place -> mine -> repeat farm back to the player on the next load. The store
            // swallows and logs its own IO failures, so a bad disk costs the flags, not the rest
            // of this teardown.
            savePlacedBlocks();
            placedBlockStore = null;
            placedBlockTracker.clear();
            // In-progress brews need no explicit flush: mcMMO reuses vanilla's brew timer
            // (persisted in the block entity's NBT), so a half-done brew simply resumes on the
            // next world load.
            ConfigBootstrap.unload();
        } catch (Exception e) {
            LOGGER.error("Error while disabling mcMMO for the server session", e);
        } finally {
            server = null;
        }
    }

    /**
     * The active {@link MinecraftServer} for this world session, or {@code null} outside one
     * (e.g. at the title screen before a world is opened).
     */
    public static @Nullable MinecraftServer getServer() {
        return server;
    }

    /**
     * mcMMO's internal event bus. Never {@code null} — it exists from mod load onward,
     * independent of whether a world session is active.
     */
    public static @NotNull EventBus getEventBus() {
        return eventBus;
    }

    /**
     * mcMMO's server-tick scheduler (Phase 11). Never {@code null} — it exists from mod load
     * onward. Ported {@code runnables/} tasks and skill managers submit delayed/repeating work
     * here instead of the legacy FoliaLib scheduler. Its queue is cleared at each server stop.
     */
    public static @NotNull TickScheduler getScheduler() {
        return scheduler;
    }

    /**
     * The Call-of-the-Wild summon registry (Taming §C). Never {@code null} — created at mod load
     * and lives for the JVM. Replaces legacy {@code mcMMO.getTransientEntityTracker()}.
     */
    public static @NotNull TransientEntityTracker getTransientEntityTracker() {
        return transientEntityTracker;
    }

    /**
     * The hand-placed-block registry (§A anti-exploit). Never {@code null} — created at mod load
     * and lives for the JVM; its per-world flags are cleared at world close. Replaces legacy
     * {@code mcMMO.getUserBlockTracker()}.
     */
    public static @NotNull PlacedBlockTracker getPlacedBlockTracker() {
        return placedBlockTracker;
    }

    /**
     * Flush the hand-placed-block flags to the world save (§A/K9). Called on the autosave tick
     * for crash safety and once more at server stop; a no-op outside a world session, where there
     * is no save to write to. Never throws — {@link PlacedBlockStore#save} logs and swallows its
     * own IO failures so this can sit alongside the profile flush without being able to abort it.
     */
    public static void savePlacedBlocks() {
        final PlacedBlockStore store = placedBlockStore;
        if (store != null) {
            store.save(placedBlockTracker);
        }
    }

    /**
     * The skill metadata registry. Never {@code null} — built lazily on first access (it needs
     * no world session, only the locale bundle). Replaces legacy {@code mcMMO.p.getSkillTools()}.
     */
    public static @NotNull SkillTools getSkillTools() {
        SkillTools local = skillTools;
        if (local == null) {
            synchronized (McMMOMod.class) {
                local = skillTools;
                if (local == null) {
                    local = new SkillTools();
                    skillTools = local;
                }
            }
        }
        return local;
    }

    /**
     * The item/block classification tables. Never {@code null} — built lazily on first access
     * (pure hardcoded registry-path sets, no world session). Replaces legacy
     * {@code mcMMO.getMaterialMapStore()}.
     */
    public static @NotNull MaterialMapStore getMaterialMapStore() {
        MaterialMapStore local = materialMapStore;
        if (local == null) {
            synchronized (McMMOMod.class) {
                local = materialMapStore;
                if (local == null) {
                    local = new MaterialMapStore();
                    materialMapStore = local;
                }
            }
        }
        return local;
    }

    /**
     * The XP-curve engine. Never {@code null} — built lazily on first access (it needs no world
     * session; it reads the configs on demand). Replaces legacy {@code mcMMO.getFormulaManager()}.
     */
    public static @NotNull FormulaManager getFormulaManager() {
        FormulaManager local = formulaManager;
        if (local == null) {
            synchronized (McMMOMod.class) {
                local = formulaManager;
                if (local == null) {
                    local = new FormulaManager();
                    formulaManager = local;
                }
            }
        }
        return local;
    }

    /**
     * The active per-world {@link ProfileStore}, or {@code null} outside a world session.
     * Replaces the legacy {@code mcMMO.getDatabaseManager()}.
     */
    public static @Nullable ProfileStore getProfileStore() {
        return profileStore;
    }

    /** Binds the per-world {@link ProfileStore} at server start (Phase 5). */
    public static void setProfileStore(@Nullable ProfileStore store) {
        profileStore = store;
    }

    /**
     * The loaded {@link GeneralConfig}, or {@code null} outside a world session (before the
     * configs are wired in at server start). Replaces {@code mcMMO.p.getGeneralConfig()}.
     */
    public static @Nullable GeneralConfig getGeneralConfig() {
        return generalConfig;
    }

    /** Wires the loaded {@link GeneralConfig} at server start. */
    public static void setGeneralConfig(@Nullable GeneralConfig config) {
        generalConfig = config;
    }

    /**
     * Whether RetroMode (1-1000) scaling is enabled, or {@code false} when the config is not yet
     * loaded (outside a world session / in unit tests -> Standard scaling). Replaces the legacy
     * {@code mcMMO.isRetroModeEnabled()} static, which cached {@code generalConfig.getIsRetroMode()}
     * at enable time; a live null-safe read avoids a stale snapshot.
     */
    public static boolean isRetroModeEnabled() {
        final GeneralConfig config = generalConfig;
        return config != null && config.getIsRetroMode();
    }

    /**
     * The loaded {@link ExperienceConfig}, or {@code null} outside a world session (before the
     * configs are wired in at server start). Replaces {@code ExperienceConfig.getInstance()}.
     */
    public static @Nullable ExperienceConfig getExperienceConfig() {
        return experienceConfig;
    }

    /** Wires the loaded {@link ExperienceConfig} at server start. */
    public static void setExperienceConfig(@Nullable ExperienceConfig config) {
        experienceConfig = config;
    }

    /**
     * The loaded {@link CoreSkillsConfig}, or {@code null} outside a world session (before the
     * configs are wired in at server start). Replaces {@code CoreSkillsConfig.getInstance()}.
     */
    public static @Nullable CoreSkillsConfig getCoreSkillsConfig() {
        return coreSkillsConfig;
    }

    /** Wires the loaded {@link CoreSkillsConfig} at server start. */
    public static void setCoreSkillsConfig(@Nullable CoreSkillsConfig config) {
        coreSkillsConfig = config;
    }

    /**
     * The loaded {@link RankConfig}, or {@code null} outside a world session (before the configs
     * are wired in at server start). Replaces {@code RankConfig.getInstance()}.
     */
    public static @Nullable RankConfig getRankConfig() {
        return rankConfig;
    }

    /** Wires the loaded {@link RankConfig} at server start. */
    public static void setRankConfig(@Nullable RankConfig config) {
        rankConfig = config;
    }

    /**
     * The loaded {@link SoundConfig}, or {@code null} outside a world session (before the configs
     * are wired in at server start). Replaces {@code SoundConfig.getInstance()}.
     */
    public static @Nullable SoundConfig getSoundConfig() {
        return soundConfig;
    }

    /** Wires the loaded {@link SoundConfig} at server start. */
    public static void setSoundConfig(@Nullable SoundConfig config) {
        soundConfig = config;
    }

    /**
     * The loaded {@link AdvancedConfig}, or {@code null} outside a world session (before the
     * configs are wired in at server start). Replaces {@code AdvancedConfig.getInstance()}.
     */
    public static @Nullable AdvancedConfig getAdvancedConfig() {
        return advancedConfig;
    }

    /** Wires the loaded {@link AdvancedConfig} at server start. */
    public static void setAdvancedConfig(@Nullable AdvancedConfig config) {
        advancedConfig = config;
    }

    /**
     * The loaded {@link TreasureConfig}, or {@code null} outside a world session (before the
     * configs are wired in at server start). Replaces {@code TreasureConfig.getInstance()}.
     */
    public static @Nullable TreasureConfig getTreasureConfig() {
        return treasureConfig;
    }

    /** Wires the loaded {@link TreasureConfig} at server start. */
    public static void setTreasureConfig(@Nullable TreasureConfig config) {
        treasureConfig = config;
    }

    /**
     * The loaded {@link FishingTreasureConfig} (Fishing Treasure-Hunter loot + drop-rate curve),
     * or {@code null} outside a world session. Replaces legacy
     * {@code FishingTreasureConfig.getInstance()} (K8).
     */
    public static @Nullable FishingTreasureConfig getFishingTreasureConfig() {
        return fishingTreasureConfig;
    }

    /** Wires the loaded {@link FishingTreasureConfig} at server start (K8). */
    public static void setFishingTreasureConfig(@Nullable FishingTreasureConfig config) {
        fishingTreasureConfig = config;
    }

    /**
     * The Call-of-the-Wild lookup tables (item -> summon), built from {@link GeneralConfig} at
     * server start, or {@code null} outside a world session (before {@link ConfigBootstrap#loadAll}
     * has wired it, or after {@link #onServerStopping} clears the session's configs) — the same
     * lifecycle as every other session-bound config getter in this file. Not actually reachable as
     * {@code null} on an in-game code path (a summon can only be attempted inside a world session),
     * but annotated {@code @Nullable} rather than {@code @NotNull} to match that lifecycle and this
     * file's convention for it, matching e.g. {@link #getGeneralConfig()}/{@link
     * #getPotionConfig()}: whole-branch review previously found this getter mismatched
     * ({@code @NotNull} on a field that is genuinely {@code null} before load / after unload).
     */
    public static @Nullable CallOfTheWild getCallOfTheWild() {
        return callOfTheWild;
    }

    /** Wires the Call-of-the-Wild tables at server start (Taming §C). */
    public static void setCallOfTheWild(@Nullable CallOfTheWild config) {
        callOfTheWild = config;
    }

    /**
     * The loaded {@link PotionConfig} (Alchemy brewing tree + Concoctions ingredient tiers), or
     * {@code null} outside a world session. Replaces legacy {@code mcMMO.p.getPotionConfig()}
     * (K8).
     */
    public static @Nullable PotionConfig getPotionConfig() {
        return potionConfig;
    }

    /** Wires the loaded {@link PotionConfig} at server start (K8). */
    public static void setPotionConfig(@Nullable PotionConfig config) {
        potionConfig = config;
    }

    /**
     * The registry of Repair-able items built from {@code repair.vanilla.yml}, or {@code null}
     * outside a world session. Replaces legacy {@code mcMMO.getRepairableManager()}.
     */
    public static @Nullable RepairableManager getRepairableManager() {
        return repairableManager;
    }

    /** Wires the {@link RepairableManager} at server start (K8). */
    public static void setRepairableManager(@Nullable RepairableManager manager) {
        repairableManager = manager;
    }

    /**
     * The registry of Salvage-able items built from {@code salvage.vanilla.yml}, or {@code null}
     * outside a world session. Replaces legacy {@code mcMMO.getSalvageableManager()}.
     */
    public static @Nullable SalvageableManager getSalvageableManager() {
        return salvageableManager;
    }

    /** Wires the {@link SalvageableManager} at server start (K8). */
    public static void setSalvageableManager(@Nullable SalvageableManager manager) {
        salvageableManager = manager;
    }
}
