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
import com.gmail.nossr50.database.ProfileStore;
import com.gmail.nossr50.platform.scheduler.TickScheduler;
import com.gmail.nossr50.skills.repair.repairables.RepairableManager;
import com.gmail.nossr50.util.MaterialMapStore;
import com.gmail.nossr50.util.PlacedBlockTracker;
import com.gmail.nossr50.util.TransientEntityTracker;
import com.gmail.nossr50.util.experience.FormulaManager;
import com.gmail.nossr50.util.skills.SkillTools;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (client + server) entry point for the mcMMO NeoForge mod. Replaces
 * {@code com.gmail.nossr50.fabric.McMMOMod}; subsystem wiring (config, persistence,
 * listeners, commands) is added in later tasks.
 *
 * <p><b>Task 3 note:</b> the static accessors below are stubs. The Task 3 brief anticipated 9
 * {@code platform/} files needing them; widening {@code build.gradle}'s include filter to cover
 * the core-gameplay layer (per the controller's ruling for this task) surfaced 69 more files
 * across {@code skills/}, {@code datatypes/}, {@code util/}, {@code commands/}, {@code config/}
 * and {@code runnables/} with the identical {@code fabric.McMMOMod} import -- flagged in the
 * Task 3 report. {@link #LOGGER} and {@link #MOD_ID} are real (no subsystem dependency). Every
 * other accessor throws {@link UnsupportedOperationException} until the subsystem it exposes is
 * actually wired -- tracked for Task 5, per the Task 3 brief's Interfaces section.
 */
@Mod("mcmmo")
public final class McMMOMod {

    public static final String MOD_ID = "mcmmo";
    public static final Logger LOGGER = LoggerFactory.getLogger("mcMMO");

    public McMMOMod(IEventBus modEventBus, ModContainer modContainer) {
        // Subsystem registration lands here in later tasks.
    }

    public static @Nullable MinecraftServer getServer() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @NotNull TransientEntityTracker getTransientEntityTracker() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @NotNull SkillTools getSkillTools() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @NotNull MaterialMapStore getMaterialMapStore() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable GeneralConfig getGeneralConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable ExperienceConfig getExperienceConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable AdvancedConfig getAdvancedConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @NotNull TickScheduler getScheduler() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @NotNull PlacedBlockTracker getPlacedBlockTracker() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static void savePlacedBlocks() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static boolean isRetroModeEnabled() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable CoreSkillsConfig getCoreSkillsConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable RankConfig getRankConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable SoundConfig getSoundConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable TreasureConfig getTreasureConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable FishingTreasureConfig getFishingTreasureConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable PotionConfig getPotionConfig() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable RepairableManager getRepairableManager() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @NotNull FormulaManager getFormulaManager() {
        throw new UnsupportedOperationException("wired in Task 5");
    }

    public static @Nullable ProfileStore getProfileStore() {
        throw new UnsupportedOperationException("wired in Task 5");
    }
}
