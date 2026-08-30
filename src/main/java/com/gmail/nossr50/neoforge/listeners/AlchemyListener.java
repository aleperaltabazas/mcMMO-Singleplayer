package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SubSkillType;
import com.gmail.nossr50.neoforge.mixin.BrewingStandBrewTimeAccessor;
import com.gmail.nossr50.skills.alchemy.CatalysisTimer;
import com.gmail.nossr50.util.Permissions;
import com.gmail.nossr50.util.player.UserManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.brewing.PotionBrewEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The Alchemy XP hook: awards Alchemy XP when a brewing stand the player owns completes an mcMMO
 * brew. Ports {@code fabric.listeners.AlchemyListener} — see
 * docs/superpowers/specs/2026-08-30-alchemy-listener-design.md for the full design rationale,
 * including why {@code PotionBrewEvent.Pre} replaces the Fabric original's {@code craft} mixin
 * outright while recipe recognition ({@link #isValidBrew}, wired from
 * {@link com.gmail.nossr50.neoforge.mixin.BrewingStandTickMixin}) and Catalysis
 * ({@link #applyCatalysis}) still need mixins.
 *
 * <p>Like the Smelting hook, vanilla exposes no brew-owner concept, so this has three parts:
 * <ul>
 *   <li><b>Owner tracking</b> — {@link #onUseBlock} records the last player to right-click each
 *       brewing stand (keyed by {@link BlockPos#asLong()}), mirroring the furnace-owner map.
 *       Without an owner a brew still completes (custom potions are not left stuck) but earns no
 *       one XP.</li>
 *   <li><b>Brew detection + craft</b> — {@link #isValidBrew} is called from
 *       {@code BrewingStandTickMixin}'s {@code isBrewable} injector; {@link #onPotionBrewPre} is a
 *       plain listener on {@link PotionBrewEvent.Pre}. <b>Stubbed in this task</b> — see the
 *       method javadocs.</li>
 *   <li><b>Catalysis brew speed</b> — {@link #applyCatalysis}, called from
 *       {@code BrewingStandTickMixin}'s {@code serverTick} injector, shortens the owner's brew by
 *       the Catalysis multiplier. This is what replaces the legacy {@code AlchemyBrewTask}, whose
 *       only other job (running the brew itself) vanilla already does. Fraction carrying lives in
 *       the MC-free {@link CatalysisTimer}.</li>
 * </ul>
 *
 * <p><b>Port caveat</b> (same as {@code SmeltingListener}): owners are keyed by block position only
 * (not dimension). In singleplayer the owner is always the one player, so this is harmless. The
 * map is cleared on server stop via {@link #clearOwners()}.
 */
public final class AlchemyListener {

    /** Brewing-stand {@link BlockPos#asLong()} → owner UUID. See the class doc for the single-key caveat. */
    private static final Map<Long, UUID> BREWING_STAND_OWNERS = new ConcurrentHashMap<>();

    /** Per-stand Catalysis state: the speed captured at brew start, plus its carried fraction. */
    private static final CatalysisTimer CATALYSIS_TIMER = new CatalysisTimer();

    private AlchemyListener() {
    }

    /** Register the real event listeners. Called once at mod load from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(AlchemyListener::onUseBlock);
        NeoForge.EVENT_BUS.addListener(AlchemyListener::onPotionBrewPre);
    }

    /** Drop all tracked brewing-stand owners (called on server stop so the next world starts clean). */
    public static void clearOwners() {
        BREWING_STAND_OWNERS.clear();
        CATALYSIS_TIMER.clear();
    }

    /** Right-click a brewing stand → remember this player as its owner for XP-award purposes. */
    private static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        final Player player = event.getEntity();
        if (!(player instanceof ServerPlayer)) {
            return; // client-side fire — the server copy does the bookkeeping.
        }
        final Level world = event.getLevel();
        final BlockPos pos = event.getPos();
        if (world.getBlockEntity(pos) instanceof BrewingStandBlockEntity) {
            BREWING_STAND_OWNERS.put(pos.asLong(), player.getUUID());
        }
        // observe only; never cancel opening the brewing stand.
    }

    /**
     * Whether the brewing stand's contents form a valid mcMMO brew. Called from
     * {@code BrewingStandTickMixin}'s {@code isBrewable} injector to force vanilla to
     * start/continue a brew for recipes it does not itself recognise.
     *
     * <p><b>Stub — Task B fills this in.</b> Always returns {@code false} for now, so the stand
     * simply won't recognize mcMMO-only brews until then; vanilla-valid recipes are unaffected
     * either way, since {@code isBrewable}'s own vanilla logic still runs regardless of this
     * return value.
     */
    public static boolean isValidBrew(NonNullList<ItemStack> slots) {
        return false;
    }

    /**
     * The craft/XP seam: a plain listener on {@link PotionBrewEvent.Pre}, fired from vanilla's
     * {@code doBrew} at its own head. See the spec doc for why this replaces the Fabric original's
     * {@code craft} mixin outright.
     *
     * <p><b>Stub — Task B fills this in.</b> Empty body for now.
     */
    private static void onPotionBrewPre(PotionBrewEvent.Pre event) {
    }

    /**
     * Shorten a running brew by the stand owner's Catalysis brew speed. Called at the head of every
     * {@code BrewingStandBlockEntity#serverTick}, i.e. before vanilla's own one-tick decrement, so
     * the two together burn {@code brewSpeed} timer ticks per game tick — the rate the legacy
     * {@code AlchemyBrewTask} drove its own timer at.
     *
     * <p>No validity re-check is needed here: vanilla zeroes {@code brewTime} itself the moment the
     * recipe stops being craftable, so a non-zero timer already means a brew is genuinely in
     * progress — one this mod either recognises itself or let vanilla keep (both were sped up by
     * legacy too, since its potion tree subsumes the vanilla recipes).
     *
     * <p>This runs every tick for every brewing stand in a loaded chunk, so the owner lookup and
     * the speed calculation sit behind {@link CatalysisTimer}'s supplier and happen once per brew
     * rather than once per tick — which is also exactly when legacy resolved them.
     *
     * @param pos   the brewing stand position (the key the owner map is built on)
     * @param stand the ticking brewing stand
     */
    public static void applyCatalysis(BlockPos pos, BrewingStandBlockEntity stand) {
        final long standKey = pos.asLong();
        final BrewingStandBrewTimeAccessor timer = (BrewingStandBrewTimeAccessor) stand;
        final int brewTime = timer.getBrewTime();

        if (brewTime <= 0) {
            // Idle stand (or a brew that just completed) — forget it, so the next brew re-resolves
            // the owner's speed instead of inheriting this one's.
            CATALYSIS_TIMER.reset(standKey);
            return;
        }

        final int extraTicks = CATALYSIS_TIMER.extraTicks(standKey,
                () -> resolveBrewSpeed(standKey));
        if (extraTicks > 0) {
            timer.setBrewTime(CatalysisTimer.reducedBrewTime(brewTime, extraTicks));
        }
    }

    /**
     * Test seam: record a brewing-stand owner directly, without needing a real
     * {@link PlayerInteractEvent.RightClickBlock}. See {@link #onUseBlock} for the real entry
     * point this bypasses.
     */
    static void trackOwnerForTesting(BlockPos pos, UUID ownerId) {
        BREWING_STAND_OWNERS.put(pos.asLong(), ownerId);
    }

    /**
     * The Catalysis brew speed for whoever owns this stand, or
     * {@link CatalysisTimer#VANILLA_BREW_SPEED} when there is no owner to credit. Legacy fell back
     * to the same 1.0 whenever it could not resolve the container's owner.
     */
    private static double resolveBrewSpeed(long standKey) {
        final UUID ownerId = BREWING_STAND_OWNERS.get(standKey);
        if (ownerId == null) {
            return CatalysisTimer.VANILLA_BREW_SPEED;
        }
        final McMMOPlayer owner = UserManager.getPlayer(ownerId);
        if (owner == null) {
            return CatalysisTimer.VANILLA_BREW_SPEED;
        }
        if (!Permissions.isSubSkillEnabled(owner.getPlayer(), SubSkillType.ALCHEMY_CATALYSIS)) {
            return CatalysisTimer.VANILLA_BREW_SPEED;
        }
        return owner.getAlchemyManager().calculateBrewSpeed(
                Permissions.lucky(owner.getPlayer(), PrimarySkillType.ALCHEMY));
    }
}
