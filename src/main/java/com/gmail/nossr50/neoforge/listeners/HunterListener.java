package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.neoforge.mixin.LivingEntityDropFromLootTableAccessor;
import com.gmail.nossr50.platform.CombatUtils;
import com.gmail.nossr50.platform.MobOrigins;
import com.gmail.nossr50.platform.MobTiers;
import com.gmail.nossr50.platform.PlatformSoundCategory;
import com.gmail.nossr50.skills.hunter.HunterManager;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.sounds.SoundManager;
import com.gmail.nossr50.util.sounds.SoundType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>PORT (NeoForge):</b> the kill-counter, the four kill-qualification gates, and Trophy Hunter's
 * bonus-loot hook, ported from the Fabric original's {@code fabric.listeners.HunterListener}
 * (deleted from this repo's {@code fabric/} tree; recoverable at {@code mc/1.21.1} commit
 * {@code ef5fd3d1a~1}) plus its companion mixin {@code fabric.mixin.LivingEntityTrophyHunterMixin}.
 *
 * <p>Where Fabric needed two separate mechanisms — {@code ServerLivingEntityEvents.AFTER_DEATH} for
 * counting/XP, and a {@code dropLoot} mixin for Trophy Hunter — NeoForge's {@link LivingDropsEvent}
 * fires once, after all of vanilla's own loot has already been generated (verified against the
 * patched jar: it is posted from {@code LivingEntity#dropAllDeathLoot}, strictly after
 * {@code dropFromLootTable}, {@code dropCustomDeathLoot}, {@code dropEquipment} and
 * {@code dropExperience} have all run). Both Fabric seams collapse into {@link #onLivingDrops}.
 *
 * <p>The Trophy Hunter reroll calls {@link LivingEntityDropFromLootTableAccessor} directly instead
 * of re-invoking the outer death/loot method the Fabric mixin had to re-enter: that accessor does
 * not itself post {@link LivingDropsEvent}, so there is no recursion to guard against and no
 * {@code mcmmo$inBonusRoll}-style re-entrancy flag is needed on this platform.
 *
 * <p>See docs/superpowers/specs/2026-08-28-hunter-listener-design.md for the full design rationale.
 */
public final class HunterListener {

    /** See the Fabric original's own javadoc on this field for the full rationale — ported verbatim. */
    private static final AtomicBoolean LOGGED_FIRST_KILL = new AtomicBoolean();

    /** See the Fabric original's own javadoc on this field for the full rationale — ported verbatim. */
    private static final AtomicBoolean LOGGED_FIRST_TROPHY = new AtomicBoolean();

    /**
     * The species a player <em>builds</em> rather than finds, keyed by the same registry id
     * {@link #masteryKeyOf} files a kill under. Narrower than the Fabric original's set: this exact
     * jar (Minecraft 1.21.1) has no {@code CopperGolemEntity} class at all, so there is no second
     * species to exclude — the iron golem stays its own {@code instanceof} arm below, since that
     * check is a behavior of the individual ({@code isPlayerCreated()}), not an identity of the
     * species.
     */
    private static final Set<String> MANUFACTURED_SPECIES = Set.of("minecraft:snow_golem");

    private HunterListener() {
    }

    /** Register the kill-counter and Trophy Hunter listener. Called once from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(HunterListener::onLivingDrops);
    }

    /**
     * A living entity's loot has just dropped: if a player killed it and the kill qualifies, count
     * it, award XP, and offer Trophy Hunter its reroll.
     *
     * <p><b>Trophy drops bypass {@code event.getDrops()}:</b> the reroll below goes straight to
     * {@code level().addFreshEntity} via {@code dropFromLootTable}, not through this event's own drop
     * list — loot capture is already off by the time this listener runs, so a later
     * {@link LivingDropsEvent} listener (there is only one today) can neither see nor suppress the
     * bonus items. Relatedly: {@link LivingDropsEvent} is cancellable and this listener is not
     * registered at {@code HIGHEST} priority, so a higher-priority listener that cancels the event
     * silently stops kill-counting and XP too, not just loot — a coupling the Fabric original did not
     * have, since its kill counter ran off a separate, uncancellable death event.
     *
     * @param event NeoForge's post-loot-drop event, carrying the victim, the damage source, and the
     *              "recently hit by a player" flag vanilla itself used to gate the first loot roll
     */
    static void onLivingDrops(@NotNull LivingDropsEvent event) {
        final LivingEntity victim = event.getEntity();
        final DamageSource source = event.getSource();

        final McMMOPlayer mmoPlayer = qualifyingHunterPlayer(victim, source);
        if (mmoPlayer == null) {
            return;
        }
        final HunterManager hunter = mmoPlayer.getHunterManager();

        recordKillAndAwardXp(mmoPlayer, victim, hunter);

        if (hunter.rollTrophyDrop(MobTiers.tierOf(victim))
                && LivingEntityDropFromLootTableAccessor.shouldDropLoot(victim)
                && victim.level().getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) {
            // event.isRecentlyHit() is exactly the boolean vanilla's own dropAllDeathLoot passed to
            // the FIRST dropFromLootTable call (both are literally `lastHurtByPlayerTime > 0` --
            // verified against the patched source) -- passing it through here reproduces the first
            // roll's loot conditions exactly, matching the Fabric original's causedByPlayer passthrough.
            //
            // The shouldDropLoot()/RULE_DOMOBLOOT gates above mirror the same two conditions vanilla's
            // dropAllDeathLoot wraps its own dropFromLootTable call in -- without them, the reroll would
            // still drop a full roll on a baby mob, or with /gamerule doMobLoot false set, even though
            // the FIRST roll correctly dropped nothing in either case.
            LivingEntityDropFromLootTableAccessor.invokeDropFromLootTable(victim, source,
                    event.isRecentlyHit());
            announceFirstTrophy(victim);
        }
    }

    /**
     * Test seam: drives the kill-counting/XP/announcement half of {@link #onLivingDrops} without
     * needing a real {@link LivingDropsEvent}.
     */
    static void onDeathForTesting(@NotNull LivingEntity victim, @NotNull DamageSource source) {
        final McMMOPlayer mmoPlayer = qualifyingHunterPlayer(victim, source);
        if (mmoPlayer == null) {
            return;
        }
        recordKillAndAwardXp(mmoPlayer, victim, mmoPlayer.getHunterManager());
    }

    /**
     * The shared preamble of {@link #onLivingDrops} and {@link #onDeathForTesting}: run the kill
     * qualification gates, then load the killer's mcMMO/Hunter data, returning {@code null} if either
     * step fails. Extracted so the two entry points cannot silently drift apart.
     */
    private static @Nullable McMMOPlayer qualifyingHunterPlayer(@NotNull LivingEntity victim,
            @NotNull DamageSource source) {
        final ServerPlayer killer = qualifyingKiller(victim, source);
        if (killer == null) {
            return null;
        }
        return hunterPlayer(killer);
    }

    private static void recordKillAndAwardXp(@NotNull McMMOPlayer mmoPlayer,
            @NotNull LivingEntity victim, @NotNull HunterManager hunter) {
        final String mobId = masteryKeyOf(victim);
        final int killsBefore = hunter.getKills(mobId);
        final int killsAfter = hunter.recordKill(mobId);
        announceFirstCountedKill(mobId, killsAfter);

        hunter.awardKillXp(MobTiers.tierOf(victim));

        if (hunter.crossedMasteryThreshold(killsBefore, killsAfter)) {
            announceMastery(mmoPlayer, victim, hunter.masteryTier(killsAfter), killsAfter);
        }
    }

    /**
     * Test seam: whether Trophy Hunter's gates (the shared kill-qualification chain plus the
     * manager's own roll gate) would let a bonus roll fire, without actually invoking the loot-table
     * accessor.
     */
    static boolean qualifiesForTrophyRoll(@NotNull LivingEntity victim, @NotNull DamageSource source,
            @NotNull HunterManager hunter) {
        return qualifyingKiller(victim, source) != null
                && hunter.rollTrophyDrop(MobTiers.tierOf(victim));
    }

    /**
     * The four gates, in the order they are cheapest and most selective, or {@code null} if this
     * death does not count as a hunt. Shared by kill-counting and Trophy Hunter — see the class
     * javadoc and docs/superpowers/specs/2026-08-28-hunter-listener-design.md's Global Constraints.
     */
    static @Nullable ServerPlayer qualifyingKiller(@NotNull LivingEntity victim,
            @NotNull DamageSource source) {
        // Gate 1: player attribution. getEntity() resolves a projectile back to its shooter, so an
        // arrow kill is the player's; a wolf's kill is the wolf's, and Taming owns that hit.
        if (!(source.getEntity() instanceof ServerPlayer killer)) {
            return null;
        }

        // Gate 2: the operator's Enabled_For_PVE / Enabled_For_PVP switches.
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.HUNTER, victim)) {
            return null;
        }

        // Gate 3: mobs the player manufactures at will.
        if (McMMOMod.getTransientEntityTracker().isTransient(victim.getUUID())) {
            return null;
        }
        if (isManufactured(victim)) {
            return null;
        }

        // Gate 4: the spawn-origin marker.
        if (!MobOrigins.countsTowardMastery(victim)) {
            return null;
        }

        return killer;
    }

    /**
     * The killer's loaded mcMMO data, or {@code null} when there is none to pay.
     */
    private static @Nullable McMMOPlayer hunterPlayer(@NotNull ServerPlayer killer) {
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(killer.getUUID());
        return mmoPlayer == null || mmoPlayer.getHunterManager() == null ? null : mmoPlayer;
    }

    /**
     * Whether this creature only exists because a player made it — the third half of gate 3. See
     * {@link #MANUFACTURED_SPECIES}'s own javadoc for why the iron golem stays a separate arm.
     */
    private static boolean isManufactured(@NotNull LivingEntity victim) {
        if (victim instanceof IronGolem golem) {
            return golem.isPlayerCreated();
        }
        return MANUFACTURED_SPECIES.contains(masteryKeyOf(victim));
    }

    /**
     * The key one creature's mastery is filed under: its <b>full</b> registry id, namespace included
     * ({@code minecraft:zombie}). Unchanged from the currently-shipped stub — see its own javadoc
     * (preserved below) for the "one function on purpose" rationale.
     *
     * <p>⚠️ <b>One function on purpose, and it is not pedantry.</b> Two places need this key —
     * here, where a kill is banked, and {@code EntityDamageListener#applyHunterMastery}, where the
     * resulting bonus is spent. They index the same map, so if the two ever disagreed about the key
     * the counters would keep climbing and the damage bonus would read {@code 0.0} forever, with no
     * error, no log and no failing test on either side alone.
     */
    static @NotNull String masteryKeyOf(@NotNull LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    /** Tell the player they have just crossed a mastery threshold against this creature. */
    private static void announceMastery(@NotNull McMMOPlayer mmoPlayer, @NotNull LivingEntity victim,
            int tier, int kills) {
        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_UNLOCKED,
                "Hunter.SubSkill.MobMastery.Proc",
                victim.getType().getDescription().getString(), String.valueOf(tier),
                String.valueOf(kills));
        SoundManager.sendCategorizedSound(mmoPlayer.getPlayer(), SoundType.SKILL_UNLOCKED,
                PlatformSoundCategory.MASTER);
    }

    /** See {@link #LOGGED_FIRST_KILL}. */
    private static void announceFirstCountedKill(@NotNull String mobId, int killsAfter) {
        if (LOGGED_FIRST_KILL.compareAndSet(false, true)) {
            McMMOMod.LOGGER.info("Hunter: mob-mastery counters are live — first counted kill this "
                    + "session was '{}' (now {}).", mobId, killsAfter);
        }
    }

    /** See {@link #LOGGED_FIRST_TROPHY}. */
    private static void announceFirstTrophy(@NotNull LivingEntity victim) {
        if (LOGGED_FIRST_TROPHY.compareAndSet(false, true)) {
            McMMOMod.LOGGER.info("Hunter: Trophy Hunter is live — first bonus loot roll this "
                    + "session was on '{}' (tier {}).", masteryKeyOf(victim), MobTiers.tierOf(victim));
        }
    }

    /** Test seam: forget both session-log flags, matching the Fabric original's own test hook. */
    static void resetFirstKillLogForTesting() {
        LOGGED_FIRST_KILL.set(false);
        LOGGED_FIRST_TROPHY.set(false);
    }
}
