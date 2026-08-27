package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.subskills.movement.DodgeResult;
import com.gmail.nossr50.datatypes.skills.subskills.movement.RollResult;
import com.gmail.nossr50.locale.LocaleLoader;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.CombatUtils;
import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.ParticleEffectUtils;
import com.gmail.nossr50.platform.PlatformLivingEntity;
import com.gmail.nossr50.platform.PlatformSoundCategory;
import com.gmail.nossr50.platform.text.TextUtils;
import com.gmail.nossr50.skills.movement.MovementManager;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.skills.unarmed.UnarmedManager;
import com.gmail.nossr50.skills.unarmored.UnarmoredManager;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.sounds.SoundManager;
import com.gmail.nossr50.util.sounds.SoundType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * The K1/K2 damage hook: mcMMO's window into the vanilla damage pipeline. Driven by a mixin on
 * {@link LivingEntity#getDamageAfterMagicAbsorb(DamageSource, float)} (official mappings' name for
 * the Fabric original's yarn-named {@code modifyAppliedDamage}; see
 * {@code neoforge.mixin.LivingEntityDamageMixin}) rather than a NeoForge event, because mcMMO needs
 * to <em>modify</em> the applied damage (Parkour Roll reduces fall damage) and NeoForge's
 * {@link LivingIncomingDamageEvent} veto fires before mitigation, not after — it can cancel a hit
 * but cannot shrink one.
 *
 * <p><b>PORT (NeoForge, Phase 2 Task A):</b> this task lands the dispatcher shell plus every branch
 * that has no dependency on a not-yet-ported skill listener: Parkour's Roll/Graceful Roll/Dodge/
 * Smash, Unarmored's XP/Thorny Skin, Mining's Demolitions Expertise (Blast Mining self-damage), and
 * the three {@code ALLOW_DAMAGE}-veto branches (Unarmed's Arrow Deflect, Taming's Beast Lore half
 * of the bone-inspection dispatcher, and Taming's Environmentally-Aware FALL arm). Five attacker-side
 * arms are stubbed as no-op pass-throughs, clearly marked below, pending later tasks in
 * {@code docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md}: the melee weapon bonus
 * (Task B), the wolf attack bonus and Hunter's Quarry Sense half of bone-inspection and Assassin and
 * Hunter Mastery (Task D), and the projectile weapon bonus (Task C). Two defender-side arms are
 * likewise stubbed: Counter Attack (Task B) and the wolf-defense dispatch — Thick Fur / Shock Proof /
 * Holy Hound / Environmentally Aware's non-FALL arms (Task D).
 *
 * <p>Every attacker arm also pays that skill's <b>per-hit combat XP</b> as its closing act, exactly
 * where legacy's {@code processXCombat} methods did (see {@link CombatUtils#processCombatXP}).
 * Damage mcMMO deals itself never reaches these arms — the {@code isProcessingMcMMODamage} guard
 * below turns it away — so a Serrated Strikes AoE or a Rupture tick pays no XP, matching legacy's
 * custom-damage marker.
 *
 * <p>Some branches do <em>not</em> ride the mixin — Unarmed's <b>Arrow Deflect</b> and Taming's
 * <b>Beast Lore</b> and Environmentally Aware's FALL arm (dispatched from {@link #onAllowDamage}) —
 * because they cancel the hit outright, so they ride NeoForge's cancel-only
 * {@link LivingIncomingDamageEvent} veto — hence this class has a {@link #register()} as well as a
 * mixin entry point.
 *
 * <p>And one branch needs a reading the {@code getDamageAfterMagicAbsorb} seam cannot give it at
 * all: <b>Unarmored</b>'s XP is paid on the damage as it was <em>before</em> armor mitigation (see
 * {@link #maybeAwardUnarmoredXp}), because the skill's own Iron Skin bonus is armor and would
 * otherwise throttle the XP that grants it. That value is captured a few bytecodes upstream by a
 * second injector on {@code getDamageAfterArmorAbsorb} and joined to this one through
 * {@link #recordPreArmorDamage} — so the same mixin class has two entry points into this listener.
 */
public final class EntityDamageListener {

    private EntityDamageListener() {
    }

    /**
     * Subscribe the branches of this listener that need to <em>veto</em> a hit outright rather than
     * reduce it — Unarmed's Arrow Deflect, Taming's Beast Lore and Environmentally Aware's FALL arm
     * (see {@link #onAllowDamage}). Everything else here is driven by the
     * {@code getDamageAfterMagicAbsorb} mixin, which cannot cancel.
     *
     * <p>{@link LivingIncomingDamageEvent} is posted on {@link NeoForge#EVENT_BUS} (the game bus —
     * confirmed via {@code CommonHooks#onEntityIncomingDamage}'s own source, which posts it there
     * directly rather than through any {@code IModBusEvent} path), is cancellable
     * ({@code ICancellableEvent}), and — per its own class javadoc — fires in
     * {@code LivingEntity#hurt(DamageSource, float)} "after invulnerability checks but before any
     * damage processing/mitigation": strictly before armor/magic absorption, knockback, the i-frame
     * window and the hurt sound, exactly like the Fabric original's {@code ALLOW_DAMAGE} veto.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(EntityDamageListener::onLivingIncomingDamage);
    }

    private static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (!onAllowDamage(event.getEntity(), event.getSource(), event.getAmount())) {
            event.setCanceled(true);
        }
    }

    /**
     * NeoForge's cancel-only pre-mitigation veto: the dispatcher for every mcMMO damage branch that
     * must abort a hit outright rather than merely reduce it (the {@code getDamageAfterMagicAbsorb}
     * mixin can only reduce). Legacy expressed all of these as {@code event.setCancelled(true)}, and
     * like Bukkit's cancel this fires before knockback, i-frames and the hurt sound — returning
     * {@code 0} from the mixin would zero the damage but still knock back, burn the i-frame window and
     * consume the arrow, so the veto is the faithful seam, not a workaround.
     *
     * <p>Branches, in dispatch order: Unarmed's <b>Arrow Deflect</b> (a bare-handed player swats an
     * arrow; see {@link #isArrowDeflected}), the <b>bone-inspection</b> dispatcher's <b>Beast Lore</b>
     * half (see {@link #maybeInspect} — Hunter's Quarry Sense half is Task D territory, stubbed to
     * {@code false} there), and Taming's <b>Environmentally Aware</b> FALL arm (a tamed wolf's fall
     * damage is negated; see {@link #isEnvironmentallyAwareFall}). Environmentally Aware's other
     * environmental causes only teleport the wolf and leave the hit intact, so they ride the
     * reduce-only mixin instead (Task D's {@code handleWolfDamage}).
     *
     * <p>Package-private rather than private so the tests can drive the <b>real</b> dispatcher
     * instead of the branch methods it calls — otherwise a branch could be proved in full and then
     * quietly dropped from this method, which is the "gate proved, call site deleted" trap the port
     * has walked into before.
     *
     * @return {@code false} to cancel the hit, {@code true} to let it proceed
     */
    static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (entity instanceof ServerPlayer serverPlayer) {
            return !isArrowDeflected(serverPlayer, source);
        }
        if (maybeInspect(entity, source)) {
            return false; // inspected with a bone — the blow is cancelled.
        }
        if (entity instanceof Wolf wolf && isEnvironmentallyAwareFall(wolf, source)) {
            return false;
        }
        return true;
    }

    /**
     * Unarmed Arrow Deflect: a bare-handed player may swat an incoming arrow out of the air. Ports
     * legacy {@code EntityListener#onEntityDamageByEntity}'s deflect arm plus
     * {@code UnarmedManager#deflectCheck}. It lands earlier than Dodge, matching legacy: the deflect
     * arm sits ahead of {@code processCombatAttack}, so a deflected arrow is never also dodged.
     *
     * @return {@code true} if the arrow was deflected (the caller should cancel the hit)
     */
    private static boolean isArrowDeflected(ServerPlayer serverPlayer, DamageSource source) {
        // Legacy checks the *direct* damager (`event.getDamager()`) for `instanceof Arrow`, which in
        // Bukkit is specifically a regular/tipped arrow — its sibling types (SpectralArrow, Trident)
        // implement AbstractArrow, not Arrow, so they were never deflectable. Arrow draws that same
        // line here: its siblings extend AbstractArrow alongside it, not from it.
        if (!(source.getDirectEntity() instanceof Arrow)) {
            return false;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return false; // data not loaded (e.g. mid-join).
        }
        final UnarmedManager unarmed = mmoPlayer.getUnarmedManager();
        if (unarmed == null || !unarmed.canDeflect() || !unarmed.rollArrowDeflect()) {
            return false;
        }
        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                "Combat.ArrowDeflect");
        return true;
    }

    /**
     * Taming Environmentally Aware, FALL arm: a tamed wolf whose owner has the sub-skill takes no fall
     * damage at all (legacy's {@code case FALL: event.setCancelled(true)}). The wolf's other
     * environmental causes teleport it clear via Task D's {@code handleWolfDamage} instead; only FALL
     * cancels.
     *
     * @return {@code true} if the fall damage should be negated (the caller should cancel the hit)
     */
    private static boolean isEnvironmentallyAwareFall(Wolf wolf, DamageSource source) {
        if (!source.is(DamageTypeTags.IS_FALL)) {
            return false;
        }
        if (!(wolf.getOwner() instanceof ServerPlayer owner)) {
            return false; // wild wolf (getOwner() is null unless tamed).
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(owner.getUUID());
        if (mmoPlayer == null) {
            return false;
        }
        final TamingManager taming = mmoPlayer.getTamingManager();
        return taming != null && taming.canUseEnvironmentallyAware();
    }

    /**
     * The Beast Lore half of the shared bone-inspection entry point: a player who left-clicks a
     * tameable creature while holding a bone reads it instead of hitting it, and the blow is
     * cancelled.
     *
     * <p><b>PORT (Task A):</b> Hunter's <b>Quarry Sense</b> half of this dispatcher is Task D
     * territory — it needs {@code HunterListener.masteryKeyOf}, from a Fabric listener file not yet
     * ported to this branch. Rather than reach into a class that does not exist here, {@code
     * quarrySense} below is hardcoded {@code false}; Task D restores the real gate ({@code
     * attacker.isSneaking() && !(entity instanceof ArmorStand) && hunter.canQuarrySense()}) and the
     * combined message.
     *
     * @return {@code true} if the creature was inspected (the caller should cancel the hit)
     */
    private static boolean maybeInspect(LivingEntity entity, DamageSource source) {
        // Legacy's entry conditions, unchanged and shared: a *direct* melee swing (legacy's
        // `entityType == EntityType.PLAYER`, i.e. the player is the direct damager, so a bone cannot
        // inspect by proxy through a projectile) thrown by a player holding a bone.
        if (!(source.getEntity() instanceof ServerPlayer attacker) || source.getDirectEntity() != attacker) {
            return false;
        }
        if (!attacker.getMainHandItem().is(Items.BONE)) {
            return false;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(attacker.getUUID());
        if (mmoPlayer == null) {
            return false; // data not loaded (e.g. mid-join).
        }

        final TamingManager taming = mmoPlayer.getTamingManager();
        final boolean beastLore =
                entity instanceof OwnableEntity && taming != null && taming.canUseBeastLore();

        // Task D: Hunter's Quarry Sense half — see the class javadoc above.
        final boolean quarrySense = false;

        if (!beastLore && !quarrySense) {
            return false;
        }

        final StringBuilder message = new StringBuilder();
        if (beastLore) {
            message.append(beastLore(entity));
        }
        attacker.sendSystemMessage(TextUtils.toText(message.toString()));
        return true;
    }

    /**
     * Builds the Beast Lore stat readout, porting legacy {@code TamingManager#beastLore}.
     * MC-typed display glue: it reads the target's live health, tamed owner and (for the horse family)
     * movement-speed / jump-strength attributes, and hands the jump attribute to the already-extracted
     * pure conversion {@link TamingManager#beastLoreHorseJumpStrength}. The message is assembled as a
     * legacy {@code §}-coded string exactly as upstream did, and parsed into a {@code Component} by
     * the caller.
     *
     * <p>{@link OwnableEntity#getOwner()} — official mappings' equivalent of yarn's
     * {@code Tameable#getOwner()} (verified via javap: {@code OwnableEntity} is the interface both
     * {@code Wolf} and {@code AbstractHorse} implement, exposing the same default {@code getOwner()})
     * — returns {@code null} unless the animal is tamed and its owner is resolvable, so it stands in
     * for legacy's {@code isTamed() && getOwner() != null}. Llamas are excluded from the horse block
     * just as legacy excluded them (they carry no rideable jump/speed stats worth showing).
     */
    private static String beastLore(LivingEntity target) {
        final OwnableEntity beast = (OwnableEntity) target;
        String message = LocaleLoader.getString("Combat.BeastLore") + " ";

        final LivingEntity owner = beast.getOwner();
        if (owner != null) {
            message += LocaleLoader.getString("Combat.BeastLoreOwner", owner.getName().getString())
                    + " ";
        }

        message += LocaleLoader.getString("Combat.BeastLoreHealth", target.getHealth(),
                target.getMaxHealth());

        // Mules & donkeys share the horse's jump/speed stats; llamas do not.
        if (target instanceof AbstractHorse horse && !(target instanceof Llama)
                && horse.getAttribute(Attributes.JUMP_STRENGTH) != null) {
            final double jumpStrength = TamingManager.beastLoreHorseJumpStrength(
                    horse.getAttributeValue(Attributes.JUMP_STRENGTH));
            final double speed = horse.getAttributeValue(Attributes.MOVEMENT_SPEED) * 43;
            message += "\n" + LocaleLoader.getString("Combat.BeastLoreHorseSpeed", speed)
                    + "\n" + LocaleLoader.getString("Combat.BeastLoreHorseJumpStrength", jumpStrength);
        }

        return message;
    }

    /**
     * Invoked from the {@code getDamageAfterMagicAbsorb} mixin for every living-entity hit. Returns
     * the (possibly reduced/increased) damage to apply.
     *
     * @param entity the entity taking damage
     * @param source the damage source
     * @param amount the vanilla post-armor/enchantment damage that would be applied
     * @return the damage mcMMO wants applied instead (equal to {@code amount} when it does not act)
     */
    public static float onModifyAppliedDamage(LivingEntity entity, DamageSource source, float amount) {
        // Read (and clear) the pre-armor figure first, before anything below can deal nested damage
        // and overwrite the stash — a Serrated Strikes AoE, a Counter Attack and Thorny Skin all
        // re-enter the damage pipeline from inside this method, and each of those runs its own
        // getDamageAfterArmorAbsorb. Taking the reading here is what makes the join single-frame.
        final float preArmorDamage = consumePreArmorDamage(entity, source, amount);

        if (amount <= 0) {
            return amount;
        }
        // Damage mcMMO is dealing itself (a Serrated Strikes / Skull Splitter AoE) must not be fed
        // back through mcMMO's own on-hit processing: the AoE attributes its damage to the player,
        // so without this it would read as a fresh swing and re-fire the very ability that is
        // dealing it. Legacy guards its damage handlers the same way, via a custom-damage marker on
        // the target (see CombatUtils#isProcessingMcMMODamage for why a ThreadLocal replaces it).
        if (CombatUtils.isProcessingMcMMODamage()) {
            return amount;
        }

        float result = amount;

        // K1 attacker branch: a player landing a *melee* hit adds their weapon skill's on-hit damage
        // bonus. Runs first so a PvP defender's Dodge (below) reduces the already-boosted damage.
        // Task B stub.
        result = applyAttackerWeaponBonus(entity, source, result);
        // ...and the other half of legacy's attacker dispatch: the damager is the player's *wolf*,
        // which adds the owner's Taming bonuses. Legacy branches on the damager's type in one
        // if/else-if chain, so at most one of these two can ever fire. Task D stub.
        result = applyWolfAttackBonus(entity, source, result);
        // ...and the projectile arm of that same dispatch: the damager is the player's arrow,
        // crossbow bolt or thrown trident (Archery Skill Shot / Crossbows Powered Shot / Trident
        // Impale). Mutually exclusive with the two branches above — a hit's direct source is exactly
        // one entity type — so at most one of the three fires. Task C stub.
        result = applyProjectileAttackBonus(entity, source, result);
        // Call of the Wild's "sic your pets on it", for any ranged hit. ⚠️ DELIBERATELY ITS OWN
        // STATEMENT rather than a block inside applyProjectileAttackBonus — see sicPetsOnRangedHit
        // for why. It adds no damage, so it is not part of the running total and nothing below
        // depends on its position.
        sicPetsOnRangedHit(entity, source);
        // Pass 2: Parkour Smash. Rides the same melee seam rather than adding a second damage mixin,
        // but deliberately outside the weapon-classified arm above — Smash is about the *sprint*, so
        // it applies whatever is in the player's hand, including nothing.
        result = applySprintSmash(entity, source, result);

        // Pass 2: Stealth Assassin. Sibling of Smash on the same seam and mutually exclusive with it
        // by construction — a player cannot sprint and sneak at once — so at most one of the two
        // fires for any swing. Runs after Smash so a backstab multiplies the whole melee total.
        // Task D stub.
        result = applyAssassin(entity, source, result);

        // Pass 2: Hunter Mob Mastery. ⚠️ LAST IN THIS CHAIN, AND THE POSITION IS LOAD-BEARING.
        // Assassin above multiplies the *whole* running melee total, so a Hunter bonus added before
        // it would be multiplied too — "+3.0 damage against zombies" would silently become +3.0 ×
        // backstab × crit against a crouching player. Landing it here makes the number on the tin the
        // number that lands. It is also the only sibling keyed on the *target's* identity rather than
        // the attacker's state, so nothing below it could want to read a pre-Hunter figure.
        // Pinned by EntityDamageListenerHunterTest#theMasteryBonusIsAddedAfterAssassinMultiplies in
        // the Fabric original — Task D must re-create that test on this branch. Task D stub.
        result = applyHunterMastery(entity, source, result);

        // K1 defender / K2 branch: the entity *taking* damage is a player — fall damage feeds
        // Parkour Roll, an incoming entity hit feeds Parkour Dodge.
        if (entity instanceof ServerPlayer serverPlayer) {
            // Stamp the Assassin recency window before anything can reduce or cancel the damage
            // (D-S3). Deliberately every incoming source, not just combat: a player who just took
            // fall damage or stepped in lava is not lurking in the shadows either.
            recordDamageTaken(serverPlayer);
            // Pass 2: Unarmored XP. Sits outside the fall / blast / dodge dispatch below because it
            // is not one of those cases — it pays for *being hit while bare*, whatever hit you — and
            // it is paid on the pre-armor reading rather than on `result`, so neither Iron Skin nor
            // a successful Dodge can shrink the XP for the blow that earned it.
            maybeAwardUnarmoredXp(serverPlayer, source, preArmorDamage);
            // ...and its payoff half. Outside the dispatch below for the same reason, and it must
            // stay outside: a Dodge that halves the hit does not make the attacker any less punched,
            // and gating the sting on losing the dodge roll would make the sub-skill fire on a
            // condition the player cannot see.
            maybeProcessThornySkin(serverPlayer, source);
            if (source.is(DamageTypeTags.IS_FALL)) {
                result = handleFallDamage(serverPlayer, result);
            } else if (canReduceOwnBlast(serverPlayer, source)) {
                // Blast Mining self-damage. Legacy returns out of its combat handler once
                // Demolitions Expertise has taken the hit, so this must pre-empt Dodge below —
                // a player is not "dodging" their own charge.
                result = handleOwnBlastDamage(serverPlayer, result);
            } else {
                final Entity attacker = source.getEntity();
                if (attacker != null) {
                    result = handleDodge(serverPlayer, attacker, result);
                }
                // Counter Attack reflects damage but does not change what the player takes, so it
                // runs last and returns nothing. Legacy's ordering, preserved: it reads the damage
                // back *after* Dodge has written to it, so a dodged hit counters for less. Task B
                // stub.
                maybeProcessCounterAttack(serverPlayer, source, result);
            }
        } else if (entity instanceof Wolf wolf) {
            // Legacy's sibling `else if (livingEntity instanceof Tameable pet)` arm: the player's own
            // wolf is taking damage, and Taming may soften or undo it. Task D stub.
            result = handleWolfDamage(wolf, source, result);
        }
        return result;
    }

    // --- Unarmored: the pre-armor damage reading -------------------------------------------------

    /**
     * One entity's incoming damage as it was <em>before</em> vanilla's armor mitigation, together
     * with the identities it was captured against.
     *
     * <p>Both identities are held so the consumer can refuse a reading that is not demonstrably the
     * one it is looking at. Cheap insurance against the only thing that could break the join — some
     * other mod, or a future vanilla refactor, calling {@code getDamageAfterArmorAbsorb} somewhere
     * that is not immediately followed by {@code getDamageAfterMagicAbsorb} on the same hit.
     */
    private record PreArmorDamage(LivingEntity entity, DamageSource source, float amount) {
    }

    /**
     * The most recent pre-armor reading on this thread, set by {@code LivingEntityDamageMixin} and
     * consumed a few bytecodes later by {@link #onModifyAppliedDamage}.
     *
     * <p>Thread-local rather than a field or a map because the whole lifetime of the value is one
     * pair of adjacent calls inside a single {@code actuallyHurt} frame — the same
     * {@code CombatUtils.IN_MCMMO_DAMAGE} shape this port uses everywhere it has to join two
     * injectors.
     */
    private static final ThreadLocal<PreArmorDamage> PRE_ARMOR_DAMAGE = new ThreadLocal<>();

    /**
     * Stash the damage {@code entity} is about to have its armor applied to. Called from the
     * {@code getDamageAfterArmorAbsorb} HEAD injector; see that method for why the seam exists at
     * all.
     */
    public static void recordPreArmorDamage(LivingEntity entity, DamageSource source, float amount) {
        PRE_ARMOR_DAMAGE.set(new PreArmorDamage(entity, source, amount));
    }

    /**
     * Take the pre-armor reading for this hit, clearing it so it can never be read twice or leak
     * into an unrelated one.
     *
     * @param fallback what to report when no matching reading was captured
     * @return the pre-armor damage, or {@code fallback} if the stash is missing or belongs to some
     *         other entity or damage source
     */
    private static float consumePreArmorDamage(LivingEntity entity, DamageSource source,
            float fallback) {
        final PreArmorDamage stashed = PRE_ARMOR_DAMAGE.get();
        PRE_ARMOR_DAMAGE.remove();
        if (stashed == null || stashed.entity() != entity || stashed.source() != source) {
            // Degrading to the post-armor amount pays *less* XP rather than none, so a broken join
            // shows up as a skill that levels slowly — not one that silently never levels, which is
            // indistinguishable from the feature not being wired at all.
            return fallback;
        }
        return stashed.amount();
    }

    // --- Unarmored: XP ---------------------------------------------------------------------------

    /**
     * Unarmored XP: a player hit while every armor slot is empty is paid for the blow.
     *
     * <p>Three gates, in cost order — the cheap arithmetic first, the config read next, the profile
     * lookup last, because this runs on every hit any player takes.
     *
     * <p>Paid on {@code preArmorDamage} for the reason spelled out on
     * {@code UnarmoredManager#getUnarmoredXp}: Iron Skin is itself armor, so metering the XP after
     * armor would have the skill throttle its own progress exactly when the grind is longest.
     */
    static void maybeAwardUnarmoredXp(ServerPlayer serverPlayer, DamageSource source,
            float preArmorDamage) {
        if (preArmorDamage <= 0 || !isUnarmoredXpSource(serverPlayer, source)) {
            return;
        }
        if (!PlatformLivingEntity.isUnarmored(serverPlayer)) {
            return;
        }
        if (!unarmoredXpUncapped(source.getEntity())) {
            return; // this attacker has already paid out its share (see the cap below).
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return; // data not loaded (e.g. mid-join).
        }
        final UnarmoredManager unarmored = mmoPlayer.getUnarmoredManager();
        if (unarmored == null) {
            return;
        }
        if (unarmored.onDamageTaken(preArmorDamage) > 0) {
            incrementUnarmoredTracker(source.getEntity());
        }
    }

    /** Transient per-attacker counter of how many Unarmored XP awards it has paid out. */
    private static final String UNARMORED_TRACKER_KEY = "mcmmo:unarmored_tracker";

    /**
     * Whether {@code attacker} has any Unarmored XP awards left in it.
     *
     * <p><b>This is the gate that decides whether the skill's balance means anything</b>, and
     * {@code Require_Living_Attacker} does not do it: a zombie <em>is</em> a living attacker, so one
     * of them hitting a player through a slab — while a stack of golden carrots regenerates the
     * damage as fast as it lands — is a fully passive ~250 XP/s, which reaches RetroMode 1000 in
     * roughly twelve hours against a design budget of ninety-two. The XP has to come out of a fight
     * the player is actually having.
     *
     * <p>Capping <em>per attacker</em> rather than per second is what preserves the legitimate case.
     * A long, genuinely dangerous fight is a handful of hits from each of several mobs and never
     * comes near the cap; the farm is thousands of hits from the <em>same</em> mob, and it dies at
     * the first hit past the limit. What is left of the exploit — cycling fresh mobs onto yourself
     * fast enough to matter — is a thing a player has to stand there and survive, which is the
     * activity the skill exists to reward.
     *
     * <p>Same shape and the same {@link MetadataStore} mechanism as Parkour Dodge's cap, which is
     * legacy's own answer to this exact problem, but keyed on the {@link LivingEntity} attacker
     * rather than on {@code Mob}: Dodge only ever paid against mobs, whereas Unarmored pays for any
     * living attacker and would otherwise leave the non-mob ones uncapped.
     *
     * <p>The counter outlives the mob — nothing clears {@link MetadataStore} per entity, only
     * {@code clearAll} at server stop — so the store grows by one small entry per distinct attacker
     * for the length of a session. That is the same (deliberate, documented) trade Dodge makes: it
     * is a few dozen bytes per mob, and expiring the counter would make the cap <em>weaker</em>
     * rather than stronger. Legacy scheduled a one-minute cleanup task; not porting it leaves a
     * strictly tighter gate, which is the direction to err in for an anti-farm measure.
     *
     * @param attacker the damage source's attacker; {@code null} when the living-attacker gate is
     *                 switched off, in which case there is nothing to key a counter on and the cap
     *                 cannot apply
     */
    private static boolean unarmoredXpUncapped(Entity attacker) {
        final int max = McMMOMod.getExperienceConfig().getUnarmoredMaxAwardsPerAttacker();
        if (max <= 0 || attacker == null) {
            return true; // cap disabled, or no attacker identity to count against.
        }
        final Integer count = MetadataStore.get(attacker, UNARMORED_TRACKER_KEY, Integer.class);
        return count == null || count < max;
    }

    /** Bump the per-attacker Unarmored XP counter after an award that actually paid. */
    private static void incrementUnarmoredTracker(Entity attacker) {
        if (attacker == null
                || McMMOMod.getExperienceConfig().getUnarmoredMaxAwardsPerAttacker() <= 0) {
            return;
        }
        final Integer count = MetadataStore.get(attacker, UNARMORED_TRACKER_KEY, Integer.class);
        MetadataStore.set(attacker, UNARMORED_TRACKER_KEY, count == null ? 1 : count + 1);
    }

    // --- Unarmored: Thorny Skin ------------------------------------------------------------------

    /**
     * Unarmored Thorny Skin: something that punched a bare-skinned player gets a little of it back.
     *
     * <p><b>Melee only, and the gate that achieves that is the same one Counter Attack uses</b> —
     * requiring the <em>direct</em> damager ({@code getDirectEntity()}, not {@code getEntity()}) to
     * be a living entity. A skeleton's arrow, a ghast's fireball and a Blast Mining charge all arrive
     * with a non-living direct source, so they are excluded by construction rather than by an
     * ever-lengthening list of damage types to skip. Fall, fire, cactus and drowning have no direct
     * damager at all and fall out of the same test.
     *
     * <p><b>No proc roll and no notification, both deliberate.</b> The reflect is capped at half a
     * heart, so it does not need a chance gate to stay fair — and a sub-skill that fires on every hit
     * would spam the action bar into uselessness if it announced itself. The player sees it as the
     * attacker taking chip damage, which is what it is. (Counter Attack messages because it is a
     * <em>roll</em>: telling the player their gamble paid off is the whole point.)
     *
     * <p>No {@code canCombatSkillsTrigger} check, unlike Counter Attack: Unarmored is a
     * {@code MISC_SKILLS} defensive skill and deliberately not in {@code COMBAT_SKILLS}, so the
     * {@code Enabled_For_PVE} / {@code Enabled_For_PVP} switches do not address it. Consulting them
     * anyway would make an unrelated config toggle silently disable a defensive passive.
     *
     * <p>Recursion is closed upstream rather than here: {@link CombatUtils#safeDealDamage} refuses to
     * run inside an mcMMO-dealt hit, and {@link #onModifyAppliedDamage} turns such hits away before
     * this branch is reached. Two unarmored players could otherwise sting each other forever.
     */
    private static void maybeProcessThornySkin(ServerPlayer serverPlayer, DamageSource source) {
        // The *direct* damager: a projectile's shooter is not standing close enough to be stung.
        if (!(source.getDirectEntity() instanceof LivingEntity assailant) || assailant == serverPlayer) {
            return;
        }
        if (!PlatformLivingEntity.isUnarmored(serverPlayer)) {
            return;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return;
        }
        final UnarmoredManager unarmored = mmoPlayer.getUnarmoredManager();
        if (unarmored == null || !unarmored.thornsReady(true)) {
            return;
        }
        CombatUtils.safeDealDamage(assailant, unarmored.getThornsDamage(true), serverPlayer);
    }

    /**
     * Whether this hit is the kind Unarmored is willing to pay for — the skill's one real exploit
     * gate ({@code ExploitFix.Unarmored.Require_Living_Attacker}, on by default).
     *
     * <p>"XP for taking damage" is otherwise the most passive farm in the mod: stand in a cactus, a
     * fire or a berry bush with a stack of food and level up while doing something else. Requiring a
     * living attacker means the XP has to come out of an actual fight, which is the thing the skill
     * exists to reward.
     *
     * <p><b>The attacker must also not be the victim.</b> That clause is not decoration: a player is
     * a {@link LivingEntity}, so without it their own primed TNT — and, worse, their own Blast Mining
     * charge, which is a repeatable mining loop that Demolitions Expertise exists to make survivable
     * — would read as a living attacker and pay full XP for blowing yourself up on purpose.
     */
    private static boolean isUnarmoredXpSource(ServerPlayer victim, DamageSource source) {
        if (!McMMOMod.getExperienceConfig().isUnarmoredLivingAttackerRequired()) {
            return true; // gate off: every damage cause pays (play-testing / diagnosis only).
        }
        final Entity attacker = source.getEntity();
        return attacker instanceof LivingEntity && attacker != victim;
    }

    // --- Taming defender half: wolf-damage dispatch (Task D stub) ---------------------------------

    /**
     * Stub — filled in by Task D of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md.
     * Thick Fur / Shock Proof / Holy Hound / Environmentally Aware's non-FALL arms all live here in
     * the Fabric original.
     */
    private static float handleWolfDamage(Wolf wolf, DamageSource source, float amount) {
        return amount; // no-op until Task D lands
    }

    /**
     * Bukkit's {@code ENTITY_ATTACK}: a melee blow from a mob or a player. Bukkit derived that cause
     * from these damage types; the projectile ones it mapped to {@code PROJECTILE} instead, which the
     * caller tests separately via {@code IS_PROJECTILE}.
     *
     * <p>Shared plumbing: not yet called by anything in Task A's own scope (Task D's
     * {@code handleWolfDamage} is the only consumer, and that arm is still a stub), but implemented
     * now per the brief so Task D's dispatch has real, verified predicates to call rather than
     * needing to re-derive and re-verify them.
     */
    private static boolean isEntityAttack(DamageSource source) {
        return source.is(DamageTypeTags.IS_PLAYER_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO);
    }

    /**
     * Bukkit's {@code MAGIC}, {@code POISON} and {@code WITHER} causes, which Holy Hound treats
     * alike. Note the three collapse to two tests here: vanilla deals Poison's damage as
     * {@link DamageTypes#MAGIC}, so Bukkit's separate {@code POISON} cause has no distinct damage
     * type to match on and is already covered.
     */
    private static boolean isHolyHoundCause(DamageSource source) {
        return source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.WITHER);
    }

    /**
     * Bukkit's {@code CONTACT} / {@code FIRE} / {@code HOT_FLOOR} / {@code LAVA} causes, which
     * Environmentally Aware treats alike (teleport the wolf clear). {@code CONTACT} is cactus / sweet
     * berry bush / dripstone, and {@code FIRE} is the <em>standing-in-fire</em> cause
     * ({@link DamageTypes#IN_FIRE}/{@link DamageTypes#CAMPFIRE}) — deliberately not {@link
     * DamageTypes#ON_FIRE}, the burning DoT Bukkit called {@code FIRE_TICK} and that Task D's Thick
     * Fur snuff arm handles instead.
     */
    private static boolean isEnvironmentallyAwareCause(DamageSource source) {
        return source.is(DamageTypes.CACTUS)
                || source.is(DamageTypes.SWEET_BERRY_BUSH)
                || source.is(DamageTypes.STALAGMITE)
                || source.is(DamageTypes.IN_FIRE)
                || source.is(DamageTypes.CAMPFIRE)
                || source.is(DamageTypes.HOT_FLOOR)
                || source.is(DamageTypes.LAVA);
    }

    // --- Mining: Blast Mining self-damage (Demolitions Expertise) ---------------------------------

    /**
     * Whether this hit is the player's own Blast Mining charge going off <i>and</i> they have
     * Demolitions Expertise unlocked. Mirrors the gates in legacy
     * {@code BlastMining#processBlastMiningExplosion} that decide whether it handles the hit
     * (returns true) or lets normal combat processing continue (returns false).
     *
     * <p>Legacy's other branch — capping the damage another player's charge deals at 24 — is dropped
     * with the rest of PvP (see {@code BlastMining}'s javadoc): the only player a blast can hit here
     * is the one who set it off.
     */
    private static boolean canReduceOwnBlast(ServerPlayer serverPlayer, DamageSource source) {
        final UUID detonator = BlastMiningListener.detonatorUuid(source.getDirectEntity());
        if (detonator == null || !detonator.equals(serverPlayer.getUUID())) {
            return false; // not an mcMMO charge, or not this player's.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        return mmoPlayer != null && mmoPlayer.getMiningManager().canUseDemolitionsExpertise();
    }

    /**
     * Demolitions Expertise: reduce the damage the player's own Blast Mining charge deals to them,
     * by their rank's percentage (legacy {@code MiningManager#processDemolitionsExpertise}).
     */
    private static float handleOwnBlastDamage(ServerPlayer serverPlayer, float amount) {
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return amount;
        }
        // TUNING (CONVERSION_TODO §F): as with the melee bonuses, getDamageAfterMagicAbsorb is
        // POST-armor, so the reduction compounds with armor rather than preceding it as in legacy.
        // Legacy additionally cancelled the hit outright when the reduction took it to <= 0; a
        // returned 0 here is equivalent in effect (no health lost).
        return (float) Math.max(mmoPlayer.getMiningManager().processDemolitionsExpertise(amount), 0.0D);
    }

    // --- K1 attacker branch: melee weapon bonus (Task B stub) --------------------------------------

    /**
     * Stub — filled in by Task B/C/D of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md.
     * Swords Stab / Axe Mastery / Unarmed Steel Arm + Berserk / Maces Crush / Spear Mastery all live
     * here in the Fabric original.
     */
    private static float applyAttackerWeaponBonus(LivingEntity target, DamageSource source,
            float amount) {
        return amount; // no-op until Task B lands
    }

    /**
     * Stub — filled in by Task B/C/D of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md.
     * A tamed wolf's bite carrying its owner's Gore / Sharpened Claws / Fast Food Service (plus
     * Pummel and wolf-assisted Taming XP) lives here in the Fabric original.
     */
    private static float applyWolfAttackBonus(LivingEntity target, DamageSource source,
            float amount) {
        return amount; // no-op until a later task lands
    }

    /**
     * Whether this target is a decoration rather than a fight — an armour stand or a mannequin
     * ({@code ExploitFix.PreventArmorStandInteraction} / {@code PreventMannequinInteraction}, legacy
     * {@code EntityListener#onEntityDamageByEntity}'s two skips).
     *
     * <p>Both are {@link LivingEntity}s that stand still and never fight back, so without this every
     * combat skill would train on one: an armour stand in a hole is an XP source that needs no food,
     * no armour and no attention. <b>A mannequin is the newer and worse of the two</b> — legacy had
     * no handling for it at all, and it is a player-like entity, so it reaches the combat paths
     * looking far more like a real opponent than an armour stand does.
     *
     * <h2>The mannequin is matched by registry id, the armour stand by {@code instanceof}</h2>
     * Not an inconsistency. The mannequin does not exist in every Minecraft version this mod
     * supports, so a hypothetical {@code MannequinEntity} class name is not always nameable — and
     * where it cannot, {@code instanceof MannequinEntity} is a <b>compile error</b> that takes the
     * build down rather than a check that quietly answers false. An id keeps the question answerable
     * everywhere: on a version with no mannequin nothing is registered under that id, so nothing
     * matches, which is the right answer. The armour stand has shipped for a decade and needs no such
     * care.
     *
     * <p>Fails closed when no config is loaded: excluded, matching the shipped default of both keys.
     */
    private static boolean isTargetDummy(LivingEntity target) {
        final ExperienceConfig config = McMMOMod.getExperienceConfig();
        if (target instanceof ArmorStand) {
            return config == null || config.isArmorStandInteractionPrevented();
        }
        if (MANNEQUIN_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))) {
            return config == null || config.isMannequinInteractionPrevented();
        }
        return false;
    }

    /**
     * The mannequin's registry id — see {@link #isTargetDummy} for why this is an id and not a class.
     *
     * <p>Compared as a {@link ResourceLocation} rather than as a bare path string: {@code
     * "mannequin"} on its own would also match another mod's {@code othermod:mannequin}, which is not
     * the vanilla decoration this rule is about. Resolved the safe direction — entity to id, never id
     * to entity — so the defaulted-registry trap that once turned every unknown mob into a
     * {@code PIG} cannot apply here.
     */
    private static final ResourceLocation MANNEQUIN_ID = ResourceLocation.withDefaultNamespace("mannequin");

    /**
     * Call of the Wild's {@code attackTarget}: a ranged hit points the shooter's nearby pets at
     * whatever they just struck.
     *
     * <h2>Why this is its own method, and not folded into the projectile-bonus arm</h2>
     * The projectile-bonus arm (Task C's {@link #applyProjectileAttackBonus}) only sees arrows and
     * tridents, because its <em>maths</em> needs the narrower type — but a snowball, an egg, a splash
     * potion or a fired firework is still the player hitting a mob from a distance, and a thrown
     * trident is unambiguously ranged too. The Fabric original's own history records this as a
     * previously-fixed bug (folding the sic into the narrower arm silently starved both a thrown
     * trident and every non-arrow/trident projectile of it), so this is deliberately its own
     * top-level dispatcher statement rather than a block nested inside the narrower method.
     * {@code Projectile} is the common ancestor of arrows, tridents, snowballs, eggs, thrown potions
     * and firework rockets (verified via javap against the compiled 1.21.1 jar).
     *
     * <p>Two conditions carried over unchanged, both deliberate:
     * <ul>
     *   <li><b>The creeper skip.</b> Sending the pack at a creeper is sending the pack to be blown
     *       up, next to its owner. Vanilla's own {@code canAttackWithOwner} makes the same refusal.</li>
     *   <li><b>{@code isTargetDummy} first.</b> An armour stand or a mannequin is not a fight.</li>
     * </ul>
     *
     * <p>No Taming rank gate and no {@code McMMOPlayer} lookup: legacy gated this on a permission
     * that is always granted in singleplayer, so a resolved shooter is the whole condition — which
     * also means a pet still answers a shot fired during the window before a profile loads.
     */
    private static void sicPetsOnRangedHit(LivingEntity target, DamageSource source) {
        if (!(source.getDirectEntity() instanceof Projectile projectile)) {
            return; // A melee hit, or environmental damage.
        }
        if (!(projectile.getOwner() instanceof ServerPlayer shooter)) {
            return; // A dispenser, a skeleton, or a wild projectile — nobody's pets to sic.
        }
        if (isTargetDummy(target) || target instanceof Creeper) {
            return;
        }
        CallOfTheWildHandler.attackTarget(shooter, target);
    }

    /**
     * Stub — filled in by Task B/C/D of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md.
     * Archery Skill Shot, Crossbows Powered Shot and Trident Impale all live here in the Fabric
     * original.
     */
    private static float applyProjectileAttackBonus(LivingEntity target, DamageSource source,
            float amount) {
        return amount; // no-op until Task C lands
    }

    // --- Parkour: Smash ----------------------------------------------------------------------------

    /**
     * Parkour <b>Smash</b>: a sprinting player's melee hit can land extra damage and heavy knockback.
     *
     * <p>Shares the melee seam with Task B's {@code applyAttackerWeaponBonus} but is gated
     * differently on purpose. The weapon arm bails out for non-weapons, because a pickaxe has no
     * Swords bonus; Smash has nothing to do with what is being held — the sub-skill is "you hit hard
     * because you were running" — so it fires with a torch, a block, or an empty hand.
     *
     * <p>Runs <em>after</em> the weapon arm, which means the weapon skill's per-hit combat XP is paid
     * on the pre-Smash damage. That is the intended attribution: the extra damage came from Parkour,
     * so it should not inflate the weapon skill's XP for the same swing.
     *
     * <p>Deliberately no interaction with vanilla's own sprint-attack knockback — this stacks on top,
     * which is exactly what the sub-skill is for.
     */
    private static float applySprintSmash(LivingEntity target, DamageSource source, float amount) {
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return amount;
        }
        // Direct melee only, same test as the weapon arm: a projectile's direct source is the
        // projectile, and Thorns is not a swing.
        if (source.getDirectEntity() != attacker || source.is(DamageTypes.THORNS)) {
            return amount;
        }
        if (!attacker.isSprinting() || isTargetDummy(target)) {
            return amount;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(attacker.getUUID());
        if (mmoPlayer == null) {
            return amount;
        }
        final MovementManager agility = mmoPlayer.getMovementManager();
        if (agility == null || !agility.rollSmash()) {
            return amount;
        }

        final double knockback = agility.getSmashKnockback();
        if (knockback > 0) {
            // Away from the attacker: knockback's x/z are the vector *from* the source, and it
            // negates them internally, so pass the attacker-to-target direction as-is
            // (bytecode-verified against LivingEntity#knockback in the decompiled NeoForm source).
            target.knockback(knockback,
                    attacker.getX() - target.getX(), attacker.getZ() - target.getZ());
        }
        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                "Parkour.SubSkill.Smash.Proc");
        return amount + (float) agility.getSmashBonusDamage();
    }

    // --- Stealth: Assassin recency window, and the Task D stubs it feeds ---------------------------

    /**
     * Server tick at which each player last took damage — Assassin's "before taking damage" window
     * (D-S3).
     *
     * <p>A side table rather than an entity field because respawning and leaving the End both
     * construct a <em>new</em> {@code ServerPlayer}, and the window should survive neither of those
     * as entity state nor be lost by them. Keyed by UUID, which does survive both.
     */
    private static final Map<UUID, Integer> LAST_DAMAGED_TICK = new HashMap<>();

    /** Stamp the current server tick as this player's most recent damage. */
    static void recordDamageTaken(@NotNull ServerPlayer player) {
        final MinecraftServer server = player.level().getServer();
        if (server != null) {
            LAST_DAMAGED_TICK.put(player.getUUID(), server.getTickCount());
        }
    }

    /**
     * Ticks since this player last took damage, or {@link Long#MAX_VALUE} if they have not been hit
     * this session.
     *
     * <p>{@code MinecraftServer#getTickCount()} is the clock rather than {@code Level#getDayTime()},
     * which {@code /time set} moves backwards — that would hand a player a permanent backstab, or
     * withhold one for a whole in-game day, depending on which way they set it.
     */
    static long ticksSinceDamageTaken(@NotNull ServerPlayer player) {
        final Integer last = LAST_DAMAGED_TICK.get(player.getUUID());
        if (last == null) {
            return Long.MAX_VALUE;
        }
        final MinecraftServer server = player.level().getServer();
        if (server == null) {
            return Long.MAX_VALUE;
        }
        // Clamped at zero: getTickCount() is an int and wraps after ~3.4 years of uptime. A negative
        // window would silently disable the sub-skill rather than merely mistiming it once.
        return Math.max(0L, (long) server.getTickCount() - last);
    }

    /** Drop the Assassin damage-recency window for a player who has left. */
    public static void forgetPlayer(@NotNull UUID uuid) {
        LAST_DAMAGED_TICK.remove(uuid);
    }

    /** Drop every player's damage-recency window (server stop). */
    public static void clear() {
        LAST_DAMAGED_TICK.clear();
        // Belt-and-braces, not a fix for a known leak: the pre-armor stash is cleared on every read,
        // so the only way one survives is a hit whose getDamageAfterArmorAbsorb ran and whose
        // getDamageAfterMagicAbsorb did not. That would strand a single entity + damage source
        // reference on the server thread, which in singleplayer outlives the world the player just
        // left.
        PRE_ARMOR_DAMAGE.remove();
    }

    /**
     * Stub — filled in by Task D of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md.
     * A melee hit thrown while crouched, by someone who has not been hit recently (see
     * {@link #ticksSinceDamageTaken}), lands as a backstab for a multiple of its normal damage in
     * the Fabric original.
     */
    static float applyAssassin(LivingEntity target, DamageSource source, float amount) {
        return amount; // no-op until Task D lands
    }

    /**
     * Stub — filled in by Task D of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md.
     * Hunter Mob Mastery's flat per-tier damage bonus against a mob the player has fought before
     * lives here in the Fabric original. ⚠️ Must stay LAST in {@link #onModifyAppliedDamage}'s
     * attacker chain when implemented — see that method's comment on why.
     */
    static float applyHunterMastery(LivingEntity target, DamageSource source, float amount) {
        return amount; // no-op until Task D lands
    }

    // --- Swords: Counter Attack (Task B stub) -------------------------------------------------------

    /**
     * Stub — filled in by Task B of docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md.
     * Reflecting a fraction of an incoming melee hit back at the assailant, on a successful roll,
     * lives here in the Fabric original — including a previously-fixed upstream role-inversion bug
     * in its {@code canCombatSkillsTrigger} gating that Task B must preserve, not silently revert.
     */
    private static void maybeProcessCounterAttack(ServerPlayer serverPlayer, DamageSource source,
            float damage) {
        // no-op until Task B lands
    }

    // --- Parkour: fall damage (Roll / Graceful Roll) ------------------------------------------------

    private static float handleFallDamage(ServerPlayer serverPlayer, float amount) {
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return amount; // data not loaded (e.g. mid-join).
        }
        final MovementManager agility = mmoPlayer.getMovementManager();
        if (agility == null) {
            return amount;
        }

        // The manager awards XP + tracks the landing block internally; it hands back the outcome so we
        // can apply the damage reduction + feedback (the MC-typed half) here.
        final RollResult result = agility.processFallDamage(amount);
        if (result == null || !result.isRollSuccess()) {
            return amount;
        }

        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                result.isGraceful()
                        ? "Parkour.SubSkill.GracefulRoll.Proc"
                        : "Parkour.SubSkill.Roll.Proc");
        SoundManager.sendCategorizedSound(mmoPlayer.getPlayer(), SoundType.ROLL_ACTIVATED,
                PlatformSoundCategory.PLAYERS, 0.5F);
        return (float) result.getModifiedDamage();
    }

    // --- Parkour: Dodge ------------------------------------------------------------------------------

    /** Transient per-mob counter of how many dodge-XP awards it has handed out (anti-farm cap). */
    private static final String DODGE_TRACKER_KEY = "mcmmo:dodge_tracker";
    /** Legacy cap: a single mob only pays out dodge XP six times (count 0..5 inclusive). */
    private static final int DODGE_XP_MAX_AWARDS = 5;

    /**
     * K1 defender branch: a player taking a hit from an entity may Dodge, reducing the damage and
     * (against an eligible mob) gaining Parkour XP. Mirrors legacy
     * {@code CombatUtils.processCombatAttack}'s dodge path. (The attacker-side melee weapon bonuses
     * are handled separately in Task B's {@code applyAttackerWeaponBonus}.)
     */
    private static float handleDodge(ServerPlayer serverPlayer, Entity attacker, float amount) {
        // Lightning dodge can be excluded by config (legacy Agility.dodgeLightningDisabled).
        if (attacker instanceof LightningBolt
                && McMMOMod.getGeneralConfig().getDodgeLightningDisabled()) {
            return amount;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return amount;
        }
        final MovementManager agility = mmoPlayer.getMovementManager();
        if (agility == null) {
            return amount;
        }

        // Only mobs grant dodge XP, and only up to the per-mob cap; the manager still reduces damage
        // when the attacker is XP-ineligible, it just pays nothing.
        final boolean xpEligible = attacker instanceof Mob && dodgeXpUncapped((Mob) attacker);

        final DodgeResult result = agility.processDodge(amount, xpEligible);
        if (result == null) {
            return amount; // no dodge — leave the hit untouched.
        }

        if (result.getXpGain() > 0) {
            incrementDodgeTracker((Mob) attacker);
        }
        if (mmoPlayer.useChatNotifications()) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                    "Parkour.Combat.Proc");
        }
        ParticleEffectUtils.playDodgeEffect(mmoPlayer.getPlayer());
        // PORT: legacy also scheduled MobDodgeMetaCleanup to expire the tracker after a minute. That
        // is a refinement — without it the transient tracker just persists for the mob's session
        // lifetime, which is a stricter (still correct) anti-farm cap. Deferred.
        return (float) result.getModifiedDamage();
    }

    /** Whether {@code mob} has not yet hit the dodge-XP award cap. */
    private static boolean dodgeXpUncapped(Mob mob) {
        if (!McMMOMod.getExperienceConfig().isMovementExploitingPrevented()) {
            return true; // exploit prevention off → uncapped.
        }
        final Integer count = MetadataStore.get(mob, DODGE_TRACKER_KEY, Integer.class);
        return count == null || count <= DODGE_XP_MAX_AWARDS;
    }

    /** Bump the per-mob dodge-XP counter after a successful, XP-paying dodge. */
    private static void incrementDodgeTracker(Mob mob) {
        if (!McMMOMod.getExperienceConfig().isMovementExploitingPrevented()) {
            return;
        }
        final Integer count = MetadataStore.get(mob, DODGE_TRACKER_KEY, Integer.class);
        MetadataStore.set(mob, DODGE_TRACKER_KEY, count == null ? 1 : count + 1);
    }
}
