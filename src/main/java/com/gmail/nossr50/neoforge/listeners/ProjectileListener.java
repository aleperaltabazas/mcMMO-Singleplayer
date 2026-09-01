package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.PlatformItem;
import com.gmail.nossr50.skills.archery.Archery;
import com.gmail.nossr50.skills.archery.ArcheryManager;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Both ends of Archery's <b>Arrow Retrieval</b>: the launch mark and the death drop, plus the
 * fired-from/bow-force launch stamps this task adds alongside it. Ports {@code fabric.listeners
 * .ProjectileListener} (replaces legacy {@code EntityListener#onProjectileLaunch}, driven here by
 * {@code neoforge.mixin.ProjectileSpawnMixin} since vanilla fires no launch event) and the
 * {@code Archery.arrowRetrievalCheck(entity)} line of legacy {@code EntityListener#onEntityDeath}.
 *
 * <p>The middle of the lifecycle — crediting a struck entity when a marked arrow hits it — belongs
 * to the damage pipeline and lives on {@code EntityDamageListener#applyArcheryBonus}, exactly where
 * the Fabric original put it.
 *
 * <p><b>Registered separately from any kill-XP hook, deliberately.</b> A kill-XP listener would
 * return early unless a <em>player</em> landed the killing blow; the arrows are owed regardless of
 * what finished the victim off (a wolf, fall damage, another mob), so this class registers its own
 * {@link LivingDeathEvent} listener rather than piggy-backing on one that gates on the attacker.
 */
public final class ProjectileListener {

    /**
     * How long a launch mark survives if the arrow never strikes a living entity. Legacy's
     * {@code CombatUtils#delayArrowMetaCleanup} used {@code 20 * 120} ticks; kept verbatim (it is
     * two minutes, not the one minute its comment claims). Without it every arrow ever fired would
     * leave entries on the {@link MetadataStore} side-table until server stop.
     */
    private static final long MARK_CLEANUP_DELAY_TICKS = 20 * 120;

    private ProjectileListener() {
    }

    /**
     * Register the death half of Arrow Retrieval. The launch half rides {@code ProjectileSpawnMixin}
     * (automatic via {@code mcmmo.mixins.json}) and needs no call here. Called once from
     * {@code McMMOMod}.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ProjectileListener::onDeath);
    }

    /**
     * Launch half: stamp the fired-from/bow-force marks and, on a winning roll, mark the arrow for
     * Arrow Retrieval. Driven from {@code ProjectileSpawnMixin} for <em>every</em> entity that
     * reaches {@code ServerLevel#addFreshEntity} — the confirmed single funnel every projectile
     * spawn path (bow, crossbow, dispenser, a skeleton's own attack) delegates through — so this
     * does the narrowing the Fabric original's mixin left to its listener: a player-owned
     * {@link Arrow}, and nothing else.
     *
     * <p>{@code instanceof Arrow} is legacy's {@code instanceof Arrow} (Bukkit), not a widening to
     * {@link net.minecraft.world.entity.projectile.AbstractArrow}: {@link
     * net.minecraft.world.entity.projectile.SpectralArrow} and {@link
     * net.minecraft.world.entity.projectile.ThrownTrident} are <em>siblings</em> under
     * {@code AbstractArrow} (javap-confirmed against the merged jar), mirroring Bukkit where
     * {@code SpectralArrow}/{@code Trident} implement {@code AbstractArrow} rather than
     * {@code Arrow}. Neither was ever retrievable upstream.
     *
     * <p><b>Stamp ordering, preserved from the Fabric reference.</b> The fired-from and bow-force
     * marks are unconditional — they happen for every arrow that clears the Infinity/Piercing skip,
     * regardless of the Arrow Retrieval roll's outcome — because they are not for retrieval at all
     * (they back the distance/force XP multipliers {@code EntityDamageListener} already reads).
     * Only {@link Archery#TRACKED_ARROW_KEY} is gated on {@link ArcheryManager#rollArrowRetrieval()}.
     */
    public static void onProjectileSpawn(Projectile projectile, ServerLevel level) {
        if (!(projectile instanceof Arrow arrow)) {
            return;
        }
        if (!(arrow.getOwner() instanceof ServerPlayer shooter)) {
            return; // wild/dispenser arrow -- legacy's `getShooter() instanceof Player` check.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(shooter.getUUID());
        if (mmoPlayer == null) {
            return; // data not loaded (e.g. mid-join).
        }
        final ArcheryManager archery = mmoPlayer.getArcheryManager();
        if (archery == null) {
            return;
        }
        if (isInfinityShot(arrow) || hasPiercingInHands(shooter)) {
            return;
        }

        final UUID arrowId = arrow.getUUID();
        Archery.markFiredFrom(arrowId, new Archery.FiredFrom(
                level.dimension().location().toString(), arrow.getX(), arrow.getY(), arrow.getZ()));
        final Double drawForce = Archery.currentBowShotForce();
        if (drawForce != null) {
            Archery.markBowForce(arrowId, drawForce);
        }
        if (archery.rollArrowRetrieval()) {
            MetadataStore.setFlag(arrow, Archery.TRACKED_ARROW_KEY);
        }

        McMMOMod.getScheduler().runLater(() -> {
            MetadataStore.remove(arrowId, Archery.TRACKED_ARROW_KEY);
            MetadataStore.remove(arrowId, Archery.FIRED_FROM_KEY);
            MetadataStore.remove(arrowId, Archery.BOW_FORCE_KEY);
        }, MARK_CLEANUP_DELAY_TICKS);
    }

    /**
     * Whether this arrow was fired from an Infinity bow, in which case retrieving (or crediting the
     * distance/force bonus to) it would duplicate ammo the shooter never spent.
     *
     * <p>Legacy reached the same conclusion by a longer route: a second handler stamped a metadata
     * marker from the bow at draw time, and the hit side read it back. The firing weapon is recorded
     * on the arrow itself here ({@link net.minecraft.world.entity.projectile.AbstractArrow
     * #getWeaponItem()} -- confirmed via source read against the merged jar's decompiled sources,
     * <em>not</em> the brief's guessed {@code getWeaponStack()}, which does not exist on this
     * mapping), so a separate marker is redundant.
     *
     * <p>{@code getWeaponItem()} is genuinely nullable (vanilla's own {@code readAdditionalSaveData}
     * restores it with {@code .orElse(null)}), hence the guard -- though on this path the arrow was
     * just built by {@code ProjectileWeaponItem#createProjectile}, which always records the weapon.
     */
    private static boolean isInfinityShot(Arrow arrow) {
        final ItemStack weapon = arrow.getWeaponItem();
        if (weapon == null || weapon.isEmpty()) {
            return false;
        }
        return new PlatformItem(weapon).getEnchantmentLevel(Enchantments.INFINITY) > 0;
    }

    /**
     * Legacy's {@code ItemUtils.doesPlayerHaveEnchantmentInHands(player, PIERCING)}: a Piercing shot
     * is never tracked. Checks both hands, as legacy does, rather than the arrow's recorded weapon --
     * the looser check is the ported behaviour.
     */
    private static boolean hasPiercingInHands(ServerPlayer shooter) {
        return hasPiercing(shooter.getMainHandItem()) || hasPiercing(shooter.getOffhandItem());
    }

    private static boolean hasPiercing(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && new PlatformItem(stack).getEnchantmentLevel(Enchantments.PIERCING) > 0;
    }

    /**
     * Death half: hand back every tracked arrow stuck in the entity that just died (legacy
     * {@code Archery.arrowRetrievalCheck}).
     *
     * <p>Registered separately from any kill-XP hook on purpose -- see this class's own javadoc for
     * why the arrows are owed regardless of what landed the killing blow.
     *
     * <p>PORT deviation (benign, matches the Fabric reference): legacy spawned {@code count}
     * separate one-arrow item entities; this drops a single stack of {@code count}. The player picks
     * up the same arrows either way -- vanilla would have merged the stacks on the ground within a
     * tick.
     */
    private static void onDeath(LivingDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        final int arrowCount = Archery.arrowRetrievalCheck(victim.getUUID());
        if (arrowCount <= 0) {
            return;
        }
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        final ItemEntity drop = new ItemEntity(level, victim.getX(), victim.getY(), victim.getZ(),
                new ItemStack(Items.ARROW, arrowCount));
        drop.setDefaultPickUpDelay(); // Bukkit's World#dropItem behaviour.
        level.addFreshEntity(drop);
    }
}
