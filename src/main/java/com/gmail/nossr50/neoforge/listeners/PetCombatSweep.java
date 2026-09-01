package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.subskills.taming.PetCombatMode;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.SkillAttributeService;
import com.gmail.nossr50.skills.taming.PetTargeting;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.util.player.UserManager;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-player pet sweep: what an aggressive pet picks a fight with, and how far <em>any</em> pet
 * will chase what it is already fighting.
 *
 * <p>Faithful port of Fabric's {@code PetCombatSweep} (see {@code git show
 * d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/
 * PetCombatSweep.java}). Fabric's {@code WolfEntity} → {@link Wolf}, {@code MobEntity} → {@link Mob},
 * {@code WardenEntity} → {@link Warden}, {@code EntityAttributes.GENERIC_FOLLOW_RANGE} → {@link
 * Attributes#FOLLOW_RANGE} (the {@code GENERIC_} prefix did not survive), {@code
 * world.getEntitiesByClass(Class, Box, Predicate)} → the {@code EntityTypeTest}-mediated overload
 * ({@code level.getEntities(EntityTypeTest.forClass(...), AABB, Predicate)}) already established by
 * {@code HusbandryListener#onLovePlayer} and {@code PlayerMovementTracker#applyHerdsmansCall}.
 *
 * <p><b>Renamed methods verified against the merged jar / extracted sources (not transcribed from
 * Yarn):</b> {@code WolfEntity#canAttackWithOwner(target, owner)} does not exist in 1.21.1 — the real
 * equivalent is {@code TamableAnimal#wantsToAttack(LivingEntity target, LivingEntity owner)}
 * (overridden on {@link Wolf}), confirmed to carry the identical exclusion list (Creeper, Ghast,
 * ArmorStand, and a tamed wolf owned by the same owner) by reading {@code Wolf.java}'s real body.
 * {@code WolfEntity#isSitting()} does not exist either — the real name is {@code
 * TamableAnimal#isInSittingPose()}. {@code EntityAttributeInstance#getBaseValue()} survives unchanged
 * as {@link AttributeInstance#getBaseValue()}, reached via {@code LivingEntity#getAttribute(Holder
 * &lt;Attribute&gt;)} (nullable), not the newer {@code getAttributeBaseValue} shortcut — kept nullable
 * so the {@link #ASSUMED_BASE_FOLLOW_RANGE} fallback for a theoretically-missing attribute instance
 * still has somewhere to apply, matching Fabric's defensive shape exactly.
 */
public final class PetCombatSweep {

    /**
     * A wolf's natural {@code FOLLOW_RANGE}, used only as the fallback when an entity somehow has no
     * such attribute instance.
     *
     * <p>⚠️ The live value is read from the entity every sweep and this constant is <em>not</em> the
     * number the boost is computed against in the normal path. That is deliberate: hard-coding 16
     * would silently stop being right the moment anything else — a mod, a datapack, a future vanilla
     * retune — touches the attribute, and the failure would be a wrong boost with nothing to notice
     * it. This is a fallback for an impossible state, not a shortcut.
     */
    private static final double ASSUMED_BASE_FOLLOW_RANGE = 16.0D;

    private PetCombatSweep() {
    }

    /**
     * One player's pet sweep. Called from {@link PlayerMovementTracker#tickPlayer}, which is the
     * mod's only per-tick per-player pass.
     *
     * <p>Package-private so the test can drive the whole body rather than the predicates alone.
     */
    static void tick(@NotNull ServerPlayer player) {
        final GeneralConfig config = McMMOMod.getGeneralConfig();
        if (config == null || !config.isPetCombatModeEnabled()) {
            // ⚠️ Returns without teardown, and that is safe rather than sloppy: every modifier this
            // class applies is TEMPORARY, so it dies with the entity and can never reach the save
            // file. The only way to observe a stranded boost is to edit config.yml mid-session and
            // then keep playing without a restart, which the config layer does not support anyway.
            return;
        }
        if (player.tickCount % config.getPetSweepIntervalTicks() != 0) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        final double engageRange = config.getPetEngageRange();
        final double aggressiveRadius = config.getPetAggressiveRadius();

        // One query for the pack. Sized by the LARGER of the two ranges: a pet that is off chasing
        // something at the engage range still has to be found, or its boost would never be taken
        // back off.
        final double petSearch = Math.max(engageRange, aggressiveRadius);
        final List<Wolf> pets = level.getEntities(EntityTypeTest.forClass(Wolf.class),
                player.getBoundingBox().inflate(petSearch),
                wolf -> wolf.isTame() && wolf.isOwnedBy(player));
        if (pets.isEmpty()) {
            return; // Nothing to do, and no reason to pay for the candidate query.
        }

        final PetCombatMode mode = resolveMode(player);

        // The candidate query is paid for once for the whole pack, and only when it can be used:
        // in PASSIVE nothing acquires, and a pack that is already fully engaged acquires nothing.
        List<Mob> candidates = null;

        for (Wolf pet : pets) {
            if (pet.isInSittingPose()) {
                // "Sit" is an explicit order to stay. A sitting pet fights nothing and chases
                // nothing, so it gets no boost either.
                SkillAttributeService.set(pet, SkillAttributeService.Managed.TAMING_PET_ENGAGE_RANGE,
                        0.0D);
                continue;
            }

            final LivingEntity target = pet.getTarget();
            if (target != null && target.isAlive()) {
                applyEngageBoost(pet, engageRange);
                continue;
            }

            // No live target: take the boost back off. Re-derived every sweep rather than tracked,
            // which is what makes a missed removal self-heal on the next pass instead of persisting.
            SkillAttributeService.set(pet, SkillAttributeService.Managed.TAMING_PET_ENGAGE_RANGE,
                    0.0D);

            if (!mode.acquiresOwnTargets()) {
                continue; // PASSIVE: a pet fights only what its owner fights.
            }
            if (candidates == null) {
                candidates = findCandidates(level, player, aggressiveRadius);
            }
            acquire(pet, player, candidates);
        }
    }

    /**
     * Raises this pet's follow range to {@code engageRange} for as long as it has a target.
     *
     * <p>The amount is derived from the entity's own <em>base</em> value every sweep, never cached
     * and never assumed: {@code getBaseValue()} excludes modifiers, so re-deriving is idempotent and
     * a pet whose base range differs (a mod, a datapack) still lands on the configured range rather
     * than on the configured range plus its own head start.
     *
     * <p>An engage range at or below the pet's natural one removes the modifier instead of applying
     * a zero or negative one — a "boost" that de-buffs would be a config edit quietly making pets
     * worse than vanilla.
     */
    private static void applyEngageBoost(@NotNull Wolf pet, double engageRange) {
        final AttributeInstance instance = pet.getAttribute(Attributes.FOLLOW_RANGE);
        final double base = instance == null ? ASSUMED_BASE_FOLLOW_RANGE : instance.getBaseValue();
        SkillAttributeService.set(pet, SkillAttributeService.Managed.TAMING_PET_ENGAGE_RANGE,
                Math.max(0.0D, engageRange - base));
    }

    /**
     * The hostiles near the <em>player</em> that this player's pets may attack (ruling R-5).
     *
     * <p>Measured from the player, so one query serves the whole pack and a pet that lagged behind
     * cannot drag something home from where it happens to be standing.
     */
    private static @NotNull List<Mob> findCandidates(@NotNull ServerLevel level,
            @NotNull ServerPlayer player, double radius) {
        return level.getEntities(EntityTypeTest.forClass(Mob.class),
                player.getBoundingBox().inflate(radius),
                mob -> mob instanceof Monster && mob.isAlive() && !isWarden(mob));
    }

    /**
     * The warden, excluded explicitly and on purpose.
     *
     * <p>It is a {@link Monster}, so nothing above filters it out. It is also unkillable by a wolf
     * pack, and the noise of them trying summons it onto the player who never chose the fight. This
     * is the one exclusion {@code wantsToAttack} does not make for us.
     */
    private static boolean isWarden(@NotNull Mob mob) {
        return mob instanceof Warden;
    }

    /**
     * Points one pet at the eligible candidate nearest its owner.
     *
     * <p>⚠️ Eligibility is asked <b>per pet</b> even though the candidate list is shared, because
     * {@code wantsToAttack} is a question about this wolf and this owner, not about the mob alone.
     *
     * <p>⚠️ A candidate that is <em>already</em> attacking something is deliberately NOT excluded.
     * The single most important candidate is the zombie currently hitting the player, and that
     * zombie has a target by definition. Excluding it would make aggressive mode ignore precisely
     * the threat the player most wants handled.
     */
    private static void acquire(@NotNull Wolf pet, @NotNull ServerPlayer player,
            @NotNull List<Mob> candidates) {
        final List<Mob> eligible = candidates.stream()
                .filter(candidate -> pet.wantsToAttack(candidate, player))
                .toList();

        final Optional<Mob> chosen =
                PetTargeting.nearestToPlayer(eligible, player::distanceToSqr);
        chosen.ifPresent(pet::setTarget);
    }

    /**
     * This player's stance, defaulting to {@link PetCombatMode#PASSIVE} when the profile has not
     * loaded.
     *
     * <p>Failing closed here matters for the same reason it does in {@code PetCombatMode}: a pack
     * that starts fights during a join window the player cannot yet control is a way to lose pets,
     * while a pack that waits a tick for its profile is invisible.
     */
    private static @NotNull PetCombatMode resolveMode(@NotNull ServerPlayer player) {
        final @Nullable McMMOPlayer mmoPlayer = UserManager.getPlayer(player.getUUID());
        if (mmoPlayer == null) {
            return PetCombatMode.PASSIVE;
        }
        final TamingManager taming = mmoPlayer.getTamingManager();
        return taming == null ? PetCombatMode.PASSIVE : taming.getPetCombatMode();
    }
}
