package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SubSkillType;
import com.gmail.nossr50.datatypes.skills.subskills.movement.DodgeResult;
import com.gmail.nossr50.datatypes.skills.subskills.movement.RollResult;
import com.gmail.nossr50.locale.LocaleLoader;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.CombatUtils;
import com.gmail.nossr50.platform.ItemUtils;
import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.ParticleEffectUtils;
import com.gmail.nossr50.platform.PlatformLivingEntity;
import com.gmail.nossr50.platform.PlatformSoundCategory;
import com.gmail.nossr50.platform.MobTiers;
import com.gmail.nossr50.platform.text.TextUtils;
import com.gmail.nossr50.skills.LimitBreak;
import com.gmail.nossr50.skills.MeleeDamageBonus;
import com.gmail.nossr50.skills.MeleeDamageBonus.MeleeWeapon;
import com.gmail.nossr50.skills.archery.Archery;
import com.gmail.nossr50.skills.archery.ArcheryManager;
import com.gmail.nossr50.skills.axes.AxesManager;
import com.gmail.nossr50.skills.crossbows.CrossbowsManager;
import com.gmail.nossr50.skills.hunter.HunterManager;
import com.gmail.nossr50.skills.maces.MacesManager;
import com.gmail.nossr50.skills.movement.MovementManager;
import com.gmail.nossr50.skills.spears.SpearsManager;
import com.gmail.nossr50.skills.stealth.StealthManager;
import com.gmail.nossr50.skills.swords.SwordsManager;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.skills.tridents.TridentsManager;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * The K1/K2 damage hook: mcMMO's window into the vanilla damage pipeline.
 *
 * <p><b>PORT correction (review round 1):</b> the main damage-modification seam
 * ({@link #onModifyAppliedDamage}) is driven by a plain NeoForge event,
 * {@link LivingDamageEvent.Pre}, <em>not</em> a mixin. The first pass wired it to a
 * {@code @ModifyReturnValue} mixin on {@code LivingEntity#getDamageAfterMagicAbsorb} (the naive
 * translation of the Fabric original's yarn-named {@code modifyAppliedDamage} seam) — bytecode
 * review (via {@code javap -c} against the actual compiled, NeoForge-patched class) found that
 * NeoForge discards that method's return value outright (an {@code invokevirtual} immediately
 * followed by {@code pop} in both {@code LivingEntity#actuallyHurt} and
 * {@code Player#actuallyHurt}), so a mixin there would compile, apply, and do nothing at
 * runtime — see {@code neoforge.mixin.LivingEntityDamageMixin}'s class javadoc for the full
 * bytecode evidence. NeoForge's own damage pipeline instead derives the applied damage from
 * {@code CommonHooks.onLivingDamagePre(LivingEntity, DamageContainer)}, which posts
 * {@link LivingDamageEvent.Pre} on {@link NeoForge#EVENT_BUS} — that event's own javadoc states
 * it fires "after armor, and potion modifiers have already been applied to the damage value",
 * i.e. exactly yarn {@code modifyAppliedDamage}'s position in the pipeline, and it is mutable via
 * {@link LivingDamageEvent.Pre#setNewDamage(float)}. No mixin is needed for this seam.
 *
 * <p>The <em>pre-armor</em> capture (Unarmored's XP read) is still a mixin
 * ({@code neoforge.mixin.LivingEntityDamageMixin}, {@code @Inject} on
 * {@code getDamageAfterArmorAbsorb}'s {@code HEAD}) — unaffected by the return-value defect above,
 * since a HEAD injector only reads arguments, never a return value — because
 * {@code LivingDamageEvent.Pre} fires <em>after</em> armor mitigation, too late for that reading.
 *
 * <p><b>PORT (NeoForge, Phase 2 Task A):</b> this task lands the dispatcher shell plus every branch
 * that has no dependency on a not-yet-ported skill listener: Parkour's Roll/Graceful Roll/Dodge/
 * Smash, Unarmored's XP/Thorny Skin, Mining's Demolitions Expertise (Blast Mining self-damage), and
 * the three {@code ALLOW_DAMAGE}-veto branches (Unarmed's Arrow Deflect, Taming's Beast Lore half
 * of the bone-inspection dispatcher, and Taming's Environmentally-Aware FALL arm). Five attacker-side
 * arms are stubbed as no-op pass-throughs, clearly marked below, pending later tasks in
 * {@code docs/superpowers/plans/2026-08-27-entity-damage-listener-plan.md}: the wolf attack bonus
 * and Hunter's Quarry Sense half of bone-inspection and Assassin and Hunter Mastery (Task D), and
 * the projectile weapon bonus (Task C). One defender-side arm is likewise stubbed: the wolf-defense
 * dispatch — Thick Fur / Shock Proof / Holy Hound / Environmentally Aware's non-FALL arms (Task D).
 *
 * <p><b>PORT (NeoForge, Phase 2 Task B):</b> this task fills in the melee weapon arm — Swords /
 * Axes / Unarmed / Maces / Spears on-hit damage bonuses ({@link #applyAttackerWeaponBonus}), the
 * combat-side super-ability activation trigger ({@link #maybeActivateSuperAbility}), Serrated
 * Strikes, Skull Splitter, Rupture, Cripple, Momentum, and Counter Attack ({@link
 * #maybeProcessCounterAttack}) — preserving the two ordering invariants the Fabric original
 * documented at these seams: super-ability activation running before the damage-bonus calculation,
 * and Counter Attack's gate reading the assailant, not the defending player.
 *
 * <p>Every attacker arm also pays that skill's <b>per-hit combat XP</b> as its closing act, exactly
 * where legacy's {@code processXCombat} methods did (see {@link CombatUtils#processCombatXP}).
 * Damage mcMMO deals itself never reaches these arms — the {@code isProcessingMcMMODamage} guard
 * below turns it away — so a Serrated Strikes AoE or a Rupture tick pays no XP, matching legacy's
 * custom-damage marker.
 *
 * <p>Some branches do <em>not</em> ride {@link LivingDamageEvent.Pre} — Unarmed's <b>Arrow
 * Deflect</b> and Taming's <b>Beast Lore</b> and Environmentally Aware's FALL arm (dispatched from
 * {@link #onAllowDamage}) — because they cancel the hit outright, so they ride NeoForge's
 * cancel-only {@link LivingIncomingDamageEvent} veto instead (fired earlier, before any
 * mitigation) — hence this class registers two separate event listeners in {@link #register()}.
 *
 * <p>And one branch needs a reading the {@link LivingDamageEvent.Pre} seam cannot give it at all:
 * <b>Unarmored</b>'s XP is paid on the damage as it was <em>before</em> armor mitigation (see
 * {@link #maybeAwardUnarmoredXp}), because the skill's own Iron Skin bonus is armor and would
 * otherwise throttle the XP that grants it. That value is captured upstream by the mixin's
 * {@code getDamageAfterArmorAbsorb} injector and joined to this class through
 * {@link #recordPreArmorDamage} — the only piece of this listener still driven by a mixin rather
 * than an event.
 */
public final class EntityDamageListener {

    private EntityDamageListener() {
    }

    /**
     * Subscribe this listener's two NeoForge event entry points (the mixin's own registration is
     * automatic, via {@code mcmmo.mixins.json}, and needs no call here):
     * <ul>
     *   <li>{@link LivingIncomingDamageEvent} — the branches that need to <em>veto</em> a hit
     *       outright rather than reduce it: Unarmed's Arrow Deflect, Taming's Beast Lore and
     *       Environmentally Aware's FALL arm (see {@link #onAllowDamage}).</li>
     *   <li>{@link LivingDamageEvent.Pre} — the main damage-modification seam (see
     *       {@link #onModifyAppliedDamage}), which fires later, after armor/potion mitigation but
     *       before health is actually reduced.</li>
     * </ul>
     *
     * <p>{@link LivingIncomingDamageEvent} is posted on {@link NeoForge#EVENT_BUS} (the game bus —
     * confirmed via {@code CommonHooks#onEntityIncomingDamage}'s own source, which posts it there
     * directly rather than through any {@code IModBusEvent} path), is cancellable
     * ({@code ICancellableEvent}), and — per its own class javadoc — fires in
     * {@code LivingEntity#hurt(DamageSource, float)} "after invulnerability checks but before any
     * damage processing/mitigation": strictly before armor/magic absorption, knockback, the i-frame
     * window and the hurt sound, exactly like the Fabric original's {@code ALLOW_DAMAGE} veto.
     *
     * <p>{@link LivingDamageEvent.Pre} is likewise posted on {@link NeoForge#EVENT_BUS} (confirmed
     * via {@code CommonHooks#onLivingDamagePre}'s source, which also posts directly, not via
     * {@code IModBusEvent}), is <em>not</em> cancellable (only mutable, via
     * {@link LivingDamageEvent.Pre#setNewDamage(float)}), and — per its own class javadoc — fires
     * in {@code LivingEntity#actuallyHurt(DamageSource, float)} once "armor, and potion modifiers
     * have already been applied to the damage value" and before "any changes to the entity health
     * has been applied": exactly the seam the Fabric original's {@code modifyAppliedDamage} mixin
     * occupied, now reached through a real event instead of a return-value mixin (see this class's
     * own javadoc for why the mixin approach silently failed on NeoForge).
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(EntityDamageListener::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(EntityDamageListener::onLivingDamagePre);
    }

    private static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (!onAllowDamage(event.getEntity(), event.getSource(), event.getAmount())) {
            event.setCanceled(true);
        }
    }

    private static void onLivingDamagePre(LivingDamageEvent.Pre event) {
        event.setNewDamage(
                onModifyAppliedDamage(event.getEntity(), event.getSource(), event.getNewDamage()));
    }

    /**
     * NeoForge's cancel-only pre-mitigation veto: the dispatcher for every mcMMO damage branch that
     * must abort a hit outright rather than merely reduce it ({@link LivingDamageEvent.Pre} can only
     * modify, not cancel). Legacy expressed all of these as {@code event.setCancelled(true)}, and
     * like Bukkit's cancel this fires before knockback, i-frames and the hurt sound — merely zeroing
     * the damage in the later event would still knock back, burn the i-frame window and consume the
     * arrow, so the veto is the faithful seam, not a workaround.
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
     * <p>Hunter's <b>Quarry Sense</b> half was a Task A stub (hardcoded {@code false}, since it needs
     * {@code HunterListener.masteryKeyOf}, from a Fabric listener file not ported until this task);
     * Task D restores the real gate ({@code attacker.isShiftKeyDown() && !(entity instanceof
     * ArmorStand) && hunter.canQuarrySense()}) and the combined message. Note this gate is
     * deliberately different from Beast Lore's: Beast Lore has no sneak requirement at all, while
     * Quarry Sense requires it — carried over unchanged from the Fabric original.
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

        final HunterManager hunter = mmoPlayer.getHunterManager();
        final boolean quarrySense = attacker.isShiftKeyDown()
                && !(entity instanceof ArmorStand)
                && hunter != null && hunter.canQuarrySense();

        if (!beastLore && !quarrySense) {
            return false;
        }

        final StringBuilder message = new StringBuilder();
        if (beastLore) {
            message.append(beastLore(entity));
        }
        if (quarrySense) {
            if (!message.isEmpty()) {
                message.append('\n');
            }
            message.append(quarrySenseLore(hunter, entity.getType().getDescription().getString(),
                    HunterListener.masteryKeyOf(entity), MobTiers.tierOf(entity)));
        }
        attacker.sendSystemMessage(TextUtils.toText(message.toString()));
        return true;
    }

    /**
     * Builds Hunter <b>Quarry Sense</b>'s readout: what this player knows about this creature.
     *
     * <p>Ported verbatim (MC-free composition, four plain values) from the Fabric original's
     * {@code quarrySenseLore} — see that method's javadoc for why it takes plain values instead of the
     * entity. The mastery key is always {@link HunterListener#masteryKeyOf} (never a locally re-derived
     * id — see that method's own javadoc for why), and the tier is always {@link MobTiers#tierOf}
     * (never a live health read).
     *
     * @param hunter       the viewing player's Hunter manager
     * @param creatureName the creature's display name, e.g. {@code Zombie}
     * @param mobId        the creature's full registry id, the key its counter is filed under
     * @param mobTier      the creature's Hunter tier, 1-4
     */
    static String quarrySenseLore(HunterManager hunter, String creatureName, String mobId,
            int mobTier) {
        final int kills = hunter.getKills(mobId);
        final int tier = hunter.masteryTier(kills);

        final StringBuilder lore = new StringBuilder()
                .append(LocaleLoader.getString("Hunter.SubSkill.QuarrySense.Lore", creatureName))
                .append('\n')
                .append(LocaleLoader.getString("Hunter.SubSkill.QuarrySense.Lore.Slain", kills))
                .append(' ');

        lore.append(tier <= 0
                ? LocaleLoader.getString("Hunter.SubSkill.QuarrySense.Lore.Unmastered")
                : LocaleLoader.getString("Hunter.SubSkill.QuarrySense.Lore.Mastery", tier,
                        String.valueOf(hunter.masteryDamageBonus(kills))));

        final int toNext = hunter.killsToNextMasteryTier(kills);
        lore.append('\n').append(toNext <= 0
                ? LocaleLoader.getString("Hunter.SubSkill.QuarrySense.Lore.Capped")
                : LocaleLoader.getString("Hunter.SubSkill.QuarrySense.Lore.Next", toNext, tier + 1));

        // The tier line carries the trophy state because on its own the tier is trivia: what a player
        // wants to know standing in front of a creature is whether their rank reaches it.
        lore.append('\n').append(LocaleLoader.getString("Hunter.SubSkill.QuarrySense.Lore.Tier",
                mobTier,
                LocaleLoader.getString(hunter.canTrophyHunt(mobTier)
                        ? "Hunter.SubSkill.QuarrySense.Lore.Trophy.Unlocked"
                        : "Hunter.SubSkill.QuarrySense.Lore.Trophy.Locked")));

        return lore.toString();
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
     * Invoked from the {@link #onLivingDamagePre} {@link LivingDamageEvent.Pre} handler for every
     * living-entity hit. Returns the (possibly reduced/increased) damage to apply.
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
        // Pass 2: Parkour Smash. Rides the same melee seam rather than adding a second event
        // listener, but deliberately outside the weapon-classified arm above — Smash is about the
        // *sprint*, so it applies whatever is in the player's hand, including nothing.
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
                // back *after* Dodge has written to it, so a dodged hit counters for less.
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
     * that is not followed, within the same {@code actuallyHurt} frame, by a
     * {@link LivingDamageEvent.Pre} post for the same hit.
     */
    private record PreArmorDamage(LivingEntity entity, DamageSource source, float amount) {
    }

    /**
     * The most recent pre-armor reading on this thread, set by {@code LivingEntityDamageMixin}'s
     * {@code getDamageAfterArmorAbsorb} injector and consumed a few instructions later — by the
     * time {@link LivingDamageEvent.Pre} posts — by {@link #onModifyAppliedDamage}.
     *
     * <p>Thread-local rather than a field or a map because the whole lifetime of the value is one
     * mixin-then-event pair inside a single {@code actuallyHurt} frame — the same
     * {@code CombatUtils.IN_MCMMO_DAMAGE} shape this port uses everywhere it has to join two entry
     * points into the same hit.
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
     * K1 defender branch, Taming half: the player's wolf is taking damage, and Taming may soften,
     * heal back or shrug it off depending on what hurt it. Ports the {@code Tameable} arm of legacy
     * {@code EntityListener#onEntityDamage}, including {@code Taming.canPreventDamage}'s
     * {@code isTamed() && owner instanceof Player && pet instanceof Wolf} gate — {@code getOwner()}
     * is null unless tamed, so matching {@link Wolf} and a {@link ServerPlayer} owner is that whole
     * check.
     *
     * <p>Legacy switches on Bukkit's {@code DamageCause}, which has no modern counterpart; each arm is
     * mapped to the vanilla damage types Bukkit derived that cause from (see the helpers above/below).
     * The arms are mutually exclusive and every one of them {@code return}s, exactly as legacy's
     * {@code switch} did.
     *
     * <p>Environmentally Aware rides both seams: its {@code CONTACT}/{@code FIRE}/{@code HOT_FLOOR}/
     * {@code LAVA} arm teleports the wolf clear from here (see {@link #isEnvironmentallyAwareCause}),
     * while its {@code FALL} arm cancels the hit outright and so rides the {@code ALLOW_DAMAGE} veto
     * (see {@link #onAllowDamage} / {@link #isEnvironmentallyAwareFall}) rather than this reduce-only
     * seam.
     */
    private static float handleWolfDamage(Wolf wolf, DamageSource source, float amount) {
        if (!(wolf.getOwner() instanceof ServerPlayer owner)) {
            return amount; // wild wolf (getOwner() is null unless tamed).
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(owner.getUUID());
        if (mmoPlayer == null) {
            return amount;
        }
        final TamingManager taming = mmoPlayer.getTamingManager();
        if (taming == null) {
            return amount;
        }

        // ENTITY_ATTACK / PROJECTILE -> Thick Fur halves the hit.
        if (isEntityAttack(source) || source.is(DamageTypeTags.IS_PROJECTILE)) {
            if (taming.canUseThickFur()) {
                // Legacy additionally cancelled the event when the reduction bottomed out at 0; a
                // returned 0 is equivalent in effect (no health lost), as with Demolitions Expertise.
                return (float) Math.max(taming.processThickFur(amount), 0.0D);
            }
            return amount;
        }

        // FIRE_TICK -> Thick Fur snuffs the flames. Note this is vanilla ON_FIRE (*burning*), not the
        // IS_FIRE tag: that tag also covers IN_FIRE/CAMPFIRE, which are Bukkit's FIRE cause and
        // belong to the Environmentally Aware arm below, not to this one.
        if (source.is(DamageTypes.ON_FIRE)) {
            if (taming.canUseThickFur()) {
                new PlatformLivingEntity(wolf).extinguish();
            }
            return amount;
        }

        // CONTACT / FIRE / HOT_FLOOR / LAVA -> Environmentally Aware teleports the wolf back to its
        // owner (out of continued contact). The hit itself still lands — legacy's teleport arm neither
        // reduces nor cancels the damage; only the FALL arm cancels, and it rides the ALLOW_DAMAGE veto
        // (see onAllowDamage) since this seam cannot cancel. Note the FIRE half is IN_FIRE/CAMPFIRE,
        // distinct from the ON_FIRE (FIRE_TICK) burning DoT the Thick Fur arm above handles.
        if (isEnvironmentallyAwareCause(source)) {
            if (taming.canUseEnvironmentallyAware()) {
                taming.processEnvironmentallyAware(new PlatformLivingEntity(wolf), amount);
            }
            return amount;
        }

        // MAGIC / POISON / WITHER -> Holy Hound heals the wolf for what it took.
        if (isHolyHoundCause(source)) {
            if (taming.canUseHolyHound()) {
                taming.processHolyHound(new PlatformLivingEntity(wolf), amount);
            }
            return amount;
        }

        // BLOCK_EXPLOSION / ENTITY_EXPLOSION / LIGHTNING -> Shock Proof divides the hit down.
        if (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypeTags.IS_LIGHTNING)) {
            if (taming.canUseShockProof()) {
                return (float) Math.max(taming.processShockProof(amount), 0.0D);
            }
            return amount;
        }

        return amount;
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
        // TUNING (CONVERSION_TODO §F): as with the melee bonuses, LivingDamageEvent.Pre fires
        // POST-armor, so the reduction compounds with armor rather than preceding it as in legacy.
        // Legacy additionally cancelled the hit outright when the reduction took it to <= 0; a
        // returned 0 here is equivalent in effect (no health lost).
        return (float) Math.max(mmoPlayer.getMiningManager().processDemolitionsExpertise(amount), 0.0D);
    }

    // --- K1 attacker branch: melee weapon bonus -----------------------------------------------------

    /**
     * K1 attacker branch: when a player lands a direct melee swing on a living entity, add the on-hit
     * damage bonus for the weapon in their main hand (Swords Stab / Axe Mastery / Unarmed Steel Arm +
     * Berserk / Maces Crush / Spears Mastery). The bonus arithmetic lives MC-free in
     * {@link MeleeDamageBonus}; this method owns the MC-typed gating: attacker identity, the
     * direct-melee check, and held-item classification.
     */
    private static float applyAttackerWeaponBonus(LivingEntity target, DamageSource source,
            float amount) {
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return amount; // environmental / mob-dealt damage.
        }
        // Only a direct melee swing: the *direct* source of the damage is the player themselves. A
        // ranged hit's direct source is the projectile; reflected Thorns damage is not a weapon swing.
        if (source.getDirectEntity() != attacker || source.is(DamageTypes.THORNS)) {
            return amount;
        }
        if (isTargetDummy(target)) {
            return amount;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(attacker.getUUID());
        if (mmoPlayer == null) {
            return amount; // data not loaded (e.g. mid-join).
        }

        final MeleeWeapon weapon = classifyMainHand(attacker.getMainHandItem());
        if (weapon == MeleeWeapon.OTHER) {
            return amount;
        }
        // Legacy gates each weapon's branch on the skill's Enabled_For_PVE/PVP switch before doing
        // anything.
        if (!CombatUtils.canCombatSkillsTrigger(skillOf(weapon), target)) {
            return amount;
        }
        // Legacy's COMBAT-path super-ability activation. processSwordCombat / processAxeCombat /
        // processUnarmedCombat each open with `if (manager.canActivateAbility())
        // mmoPlayer.checkAbilityActivation(<skill>)`, which is what flips a *readied* tool into an
        // *active* super ability when the player strikes a mob rather than a block. Without it
        // Serrated Strikes and Skull Splitter can never activate at all — they have no block path,
        // unlike the five block-struck abilities in SuperAbilityListener#onAttackBlock.
        //
        // Position is legacy's and load-bearing: activation runs BEFORE the damage bonus, so the
        // activating hit is itself buffed (Berserk scales the very swing that turned it on) and is
        // itself eligible for the AoE arms below.
        maybeActivateSuperAbility(mmoPlayer, weapon);

        final PlatformLivingEntity platformTarget = new PlatformLivingEntity(target);
        final float boostedDamage = MeleeDamageBonus.applyBonus(mmoPlayer, weapon, amount,
                platformTarget);

        // Legacy's per-weapon ordering, preserved: the super-ability AoE fires after the damage
        // bonus is computed but before it is committed, and is passed the *unboosted* damage
        // (legacy hands it the pre-bonus damage, only overwriting it afterwards).
        //
        // PORT: legacy sequences the Axes AoE *between* Greater Impact and Critical Strikes rather
        // than after the whole chain as here. Equivalent: the AoE neither reads nor writes the
        // damage total (it is handed the unboosted amount either way) and it never touches the
        // primary target, so only the order of the player's own chat notifications differs.
        if (weapon == MeleeWeapon.SWORD) {
            maybeProcessSerratedStrikes(mmoPlayer, attacker, target, amount);
            maybeProcessRupture(mmoPlayer, target, boostedDamage);
        } else if (weapon == MeleeWeapon.AXE) {
            maybeProcessSkullSplitter(mmoPlayer, attacker, platformTarget, target, amount);
        } else if (weapon == MeleeWeapon.MACE) {
            maybeProcessCripple(mmoPlayer, target, boostedDamage);
        } else if (weapon == MeleeWeapon.SPEAR) {
            maybeProcessMomentum(mmoPlayer);
        }

        // Per-hit combat XP, paid on the *boosted* damage — legacy ends every processXCombat with
        // this, after the damage is set, and its health-diff measured what actually landed. No
        // multiplier on the melee path (legacy's 3-arg processCombatXP overload).
        CombatUtils.processCombatXP(mmoPlayer, target, skillOf(weapon), boostedDamage);
        return boostedDamage;
    }

    /**
     * The combat half of the super-ability activation trigger: a strike on a living entity with a
     * readied sword / axe / fist activates Serrated Strikes / Skull Splitter / Berserk. Ports the
     * {@code canActivateAbility()} guard that opens legacy's {@code processSwordCombat},
     * {@code processAxeCombat} and {@code processUnarmedCombat}.
     *
     * <p>The block half lives in {@code SuperAbilityListener#onAttackBlock} and covers the five
     * abilities that are struck onto a block (Green Terra, Tree Feller, Super Breaker, Giga Drill
     * Breaker, Berserk). Berserk is deliberately in <em>both</em>: legacy activates Unarmed off a
     * punched mob as well as a punched block, and {@code checkAbilityActivation} is idempotent — it
     * returns immediately when the ability is already running.
     *
     * <p>Only these three skills have a combat activation upstream. Maces and Tridents have no super
     * ability, and Archery/Crossbows are not melee.
     *
     * <p>Package-private rather than private so the weapon→skill dispatch can be unit-tested MC-free
     * (both parameters are MC-free).
     */
    static void maybeActivateSuperAbility(McMMOPlayer mmoPlayer, MeleeWeapon weapon) {
        switch (weapon) {
            case SWORD -> {
                final SwordsManager swords = mmoPlayer.getSwordsManager();
                if (swords != null && swords.canActivateAbility()) {
                    mmoPlayer.checkAbilityActivation(PrimarySkillType.SWORDS);
                }
            }
            case AXE -> {
                final AxesManager axes = mmoPlayer.getAxesManager();
                if (axes != null && axes.canActivateAbility()) {
                    mmoPlayer.checkAbilityActivation(PrimarySkillType.AXES);
                }
            }
            case UNARMED -> {
                final UnarmedManager unarmed = mmoPlayer.getUnarmedManager();
                if (unarmed != null && unarmed.canActivateAbility()) {
                    mmoPlayer.checkAbilityActivation(PrimarySkillType.UNARMED);
                }
            }
            case MACE, TRIDENT, SPEAR, OTHER -> {
                // No super ability on these skills — legacy has no activation call in their combat
                // paths either.
            }
        }
    }

    /**
     * Swords Serrated Strikes: while the super ability is active, a sword hit also strikes nearby
     * entities for a fraction of the damage. Mirrors legacy {@code CombatUtils#processSwordCombat}'s
     * {@code canUseSerratedStrike} arm.
     */
    private static void maybeProcessSerratedStrikes(McMMOPlayer mmoPlayer,
            ServerPlayer attacker, LivingEntity target, float damage) {
        final SwordsManager swords = mmoPlayer.getSwordsManager();
        if (swords == null || !swords.canUseSerratedStrike()) {
            return;
        }
        CombatUtils.applyAbilityAoE(attacker, mmoPlayer, target,
                swords.serratedStrikesDamage(damage), PrimarySkillType.SWORDS);
    }

    /**
     * Axes Skull Splitter: while the super ability is active, an axe hit also strikes nearby entities
     * for a fraction of the damage. Mirrors legacy {@code CombatUtils#processAxeCombat}'s
     * {@code canUseSkullSplitter} arm.
     */
    private static void maybeProcessSkullSplitter(McMMOPlayer mmoPlayer, ServerPlayer attacker,
            PlatformLivingEntity platformTarget, LivingEntity target, float damage) {
        final AxesManager axes = mmoPlayer.getAxesManager();
        if (axes == null || !axes.canUseSkullSplitter(platformTarget)) {
            return;
        }
        CombatUtils.applyAbilityAoE(attacker, mmoPlayer, target, axes.skullSplitterDamage(damage),
                PrimarySkillType.AXES);
    }

    /**
     * Swords Rupture: a sword hit that leaves the target alive may start a bleed. Mirrors legacy
     * {@code CombatUtils#processSwordCombat}, which calls {@code processRupture} only once the
     * boosted damage is settled and only when the target survives the hit — there is no point
     * bleeding something this swing already kills, and legacy's Rupture can never land a killing
     * blow anyway.
     *
     * <p>{@link LivingDamageEvent.Pre} fires before vanilla writes the new health, so reading
     * {@link LivingEntity#getHealth()} here gives the pre-hit health — the same value legacy's
     * {@code target.getHealth() - event.getFinalDamage()} check saw.
     */
    private static void maybeProcessRupture(McMMOPlayer mmoPlayer, LivingEntity target,
            float boostedDamage) {
        if (target.getHealth() - boostedDamage <= 0) {
            return; // the swing itself is lethal.
        }
        mmoPlayer.getSwordsManager().processRupture(new PlatformLivingEntity(target),
                mmoPlayer.getAttackStrength());
    }

    /**
     * Maces Cripple: a mace hit that leaves the target alive may apply Slowness. Mirrors legacy
     * {@code CombatUtils#processMacesCombat}, which calls {@code processCripple} only when
     * {@code target.getHealth() - event.getFinalDamage() > 0} — no point crippling something the
     * swing kills. As with Rupture, {@link LivingDamageEvent.Pre} fires before vanilla writes the new
     * health, so reading {@link LivingEntity#getHealth()} gives the pre-hit value that check compared
     * against.
     */
    private static void maybeProcessCripple(McMMOPlayer mmoPlayer, LivingEntity target,
            float boostedDamage) {
        if (target.getHealth() - boostedDamage <= 0) {
            return; // the swing itself is lethal.
        }
        final MacesManager maces = mmoPlayer.getMacesManager();
        if (maces == null) {
            return;
        }
        maces.processCripple(new PlatformLivingEntity(target), mmoPlayer.getAttackStrength());
    }

    /**
     * Spears <b>Momentum</b>: a spear hit may grant the attacker a short Speed burst. Ports the
     * {@code spearsManager.potentiallyApplyMomentum()} line that closes legacy
     * {@code CombatUtils#processSpearsCombat}, just before its combat XP.
     *
     * <p>Unlike Cripple there is no survival check and no target argument at all — Momentum buffs the
     * player who swung, so killing the mob with the same hit does not cancel it. That asymmetry is
     * legacy's: {@code processCripple(target)} is guarded by
     * {@code target.getHealth() - getFinalDamage() > 0}, {@code processMomentum()} is not guarded by
     * anything.
     */
    private static void maybeProcessMomentum(McMMOPlayer mmoPlayer) {
        final SpearsManager spears = mmoPlayer.getSpearsManager();
        if (spears == null) {
            return;
        }
        spears.processMomentum(mmoPlayer.getAttackStrength());
    }

    /** The primary skill a melee weapon's on-hit bonuses belong to (legacy's per-weapon dispatch). */
    private static PrimarySkillType skillOf(MeleeWeapon weapon) {
        return switch (weapon) {
            case SWORD -> PrimarySkillType.SWORDS;
            case AXE -> PrimarySkillType.AXES;
            case MACE -> PrimarySkillType.MACES;
            case TRIDENT -> PrimarySkillType.TRIDENTS;
            case SPEAR -> PrimarySkillType.SPEARS;
            case UNARMED -> PrimarySkillType.UNARMED;
            case OTHER -> throw new IllegalArgumentException("OTHER has no skill; gate it first");
        };
    }

    /**
     * Classify a held main-hand stack into the melee weapon whose bonus applies. The set and the
     * order are legacy's {@code processCombatAttack} dispatch chain, and the arms are mutually
     * exclusive, so the order is cosmetic — except that {@code isUnarmed} must come last, since with
     * {@code Unarmed_Items_As_Unarmed} on it matches any non-tool item and would otherwise swallow a
     * mace or a trident.
     *
     * <p>{@code OTHER} means "not a weapon mcMMO trains" (a pickaxe, a block, a bow used as a club),
     * and pays no bonus and no XP — matching legacy, whose dispatch simply has no arm for those.
     *
     * <p><b>Spears dispatch off the held item</b>, exactly like every other arm of this chain —
     * {@link ItemUtils#isSpear} resolves the classification through the same registry-id lookup
     * ({@link com.gmail.nossr50.util.MaterialMapStore}) every other weapon here uses, rather than a
     * compile-time {@code Items.WOODEN_SPEAR} reference. That indirection is deliberate, not
     * incidental: spear items do not exist in this branch's vanilla 1.21.1 registry at all — verified
     * via {@code javap} against the actual compiled
     * {@code compiledWithNeoForge_*_output.jar} (no {@code SPEAR} field on {@code Items}, no
     * {@code SPEAR} constant on {@code DamageTypes}) — so a hardcoded item reference would fail to
     * compile on this branch, and {@code ItemUtilsTest} resolves the constant optionally
     * ({@code McTestRegistries.optionalVanillaItem("iron_spear")}) for exactly this reason. Until a
     * future MC version (or this project) actually ships a spear item under one of
     * {@code MaterialMapStore}'s ids, this arm is reachable code with nothing yet on the classpath to
     * reach it — kept because dropping it is the exact mistake this comment's history already made
     * once (see below).
     *
     * <p>⚠️ This arm was previously missing entirely, on the belief — written into a comment that
     * used to sit here — that no spear item existed in the target MC version and the arm would
     * therefore be dead code. Whether that belief is true varies by MC version (false for the
     * original Fabric port's later target, confirmed true for this branch's 1.21.1 by the javap
     * check above): the arm must stay regardless, both because it is genuinely reachable on some
     * supported versions and because a spear held on an unreachable version simply falls through to
     * {@code OTHER} on its own — no version-specific branching is needed for that to be safe.
     */
    @VisibleForTesting
    static MeleeWeapon classifyMainHand(ItemStack held) {
        if (ItemUtils.isSword(held)) {
            return MeleeWeapon.SWORD;
        }
        if (ItemUtils.isAxe(held)) {
            return MeleeWeapon.AXE;
        }
        if (ItemUtils.isMace(held)) {
            return MeleeWeapon.MACE;
        }
        if (ItemUtils.isTrident(held)) {
            return MeleeWeapon.TRIDENT;
        }
        if (ItemUtils.isSpear(held)) {
            return MeleeWeapon.SPEAR;
        }
        if (ItemUtils.isUnarmed(held)) {
            return MeleeWeapon.UNARMED;
        }
        return MeleeWeapon.OTHER;
    }

    /**
     * K1 attacker branch, Taming half: a tamed wolf's bite carries its owner's Gore / Sharpened Claws
     * / Fast Food Service, plus Pummel and wolf-assisted Taming XP. Ports legacy
     * {@code CombatUtils#processTamingCombat} via the Fabric original's {@code applyWolfAttackBonus}.
     *
     * <p>Keys off the <em>direct</em> damager, matching legacy's {@code painSource}: a wolf's own
     * bite, not (say) an arrow that happens to have a wolf as its owner.
     *
     * <p>Fast Food Service and Pummel run before the damage bonuses, matching legacy's ordering:
     * Pummel flings the target along the wolf's look direction on a successful roll but does not feed
     * the damage total, so it runs as a side effect rather than contributing to {@code boostedDamage}.
     *
     * <p>Closes with legacy's {@code processCombatXP(mmoPlayer, target, TAMING, 3)} — the
     * wolf-assisted Taming XP the port's old per-kill XP model could not express, since that listener
     * only fired when the <em>killer</em> was a player, so a wolf's kill paid nothing at all.
     */
    private static float applyWolfAttackBonus(LivingEntity target, DamageSource source,
            float amount) {
        if (!(source.getDirectEntity() instanceof Wolf wolf)) {
            return amount;
        }
        if (!(wolf.getOwner() instanceof ServerPlayer master)) {
            return amount; // wild wolf, or one whose owner is not this player.
        }
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.TAMING, target)) {
            return amount;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(master.getUUID());
        if (mmoPlayer == null) {
            return amount; // data not loaded (e.g. mid-join).
        }
        final TamingManager taming = mmoPlayer.getTamingManager();
        if (taming == null) {
            return amount;
        }

        final PlatformLivingEntity platformWolf = new PlatformLivingEntity(wolf);
        if (taming.canUseFastFoodService()) {
            taming.fastFoodService(platformWolf, amount);
        }

        // Pummel: called unconditionally, matching legacy's processTamingCombat — the rank gate and
        // the static chance roll live inside the manager. It flings the target along the wolf's look
        // direction but never touches the damage total, so it sits between Fast Food Service and the
        // damage bonuses exactly as legacy sequences it.
        taming.processPummel(new PlatformLivingEntity(target), platformWolf);

        double boostedDamage = amount;
        if (taming.canUseSharpenedClaws()) {
            boostedDamage += taming.sharpenedClaws();
        }
        if (taming.canUseGore()) {
            boostedDamage += taming.gore(amount);
        }

        // Wolf-assisted Taming XP, at legacy's ×3 multiplier (processTamingCombat's closing line).
        CombatUtils.processCombatXP(mmoPlayer, target, PrimarySkillType.TAMING, boostedDamage,
                WOLF_ASSIST_XP_MULTIPLIER);
        return (float) boostedDamage;
    }

    /** Legacy's {@code processTamingCombat} closing multiplier for wolf-assisted Taming XP. */
    private static final double WOLF_ASSIST_XP_MULTIPLIER = 3.0;

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
     * K1 attacker branch, projectile half: routes a player-fired arrow, crossbow bolt or thrown
     * trident to the skill it trains (Archery Skill Shot / Crossbows Powered Shot / Trident Impale).
     * Mutually exclusive with the melee and wolf arms above — a hit's direct source is exactly one
     * entity type — and mutually exclusive with itself across the three sub-arms it dispatches to.
     *
     * <p>Reads the <em>direct</em> damager as {@link AbstractArrow} — narrower than
     * {@link #sicPetsOnRangedHit}'s {@link Projectile} check on purpose: this arm's maths (Archery's
     * distance/bow-force XP multiplier, Crossbows' weapon-item read) only makes sense for something
     * that is an arrow/bolt/trident, not a snowball or a firework. Do not widen this back to
     * {@code Projectile} to "match" the sic-pets call — see {@link #sicPetsOnRangedHit}'s own javadoc
     * for why the two arms are deliberately typed differently.
     */
    private static float applyProjectileAttackBonus(LivingEntity target, DamageSource source,
            float amount) {
        if (!(source.getDirectEntity() instanceof AbstractArrow projectile)) {
            return amount; // not a projectile hit.
        }
        if (!(projectile.getOwner() instanceof ServerPlayer shooter)) {
            return amount; // wild/dispenser projectile, or not fired by this player.
        }
        if (isTargetDummy(target)) {
            return amount;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(shooter.getUUID());
        if (mmoPlayer == null) {
            return amount; // data not loaded (e.g. mid-join).
        }

        if (projectile instanceof ThrownTrident) {
            return applyTridentImpale(mmoPlayer, target, amount);
        }

        if (isCrossbowShot(projectile)) {
            return applyPoweredShot(mmoPlayer, target, projectile, amount);
        }
        return applyArcheryBonus(mmoPlayer, target, projectile, amount);
    }

    /**
     * Whether this projectile was loosed from a crossbow rather than a bow (Crossbows vs Archery).
     * The yarn-named {@code isShotFromCrossbow()} the Fabric original called has no vanilla
     * equivalent it could lean on, so it hand-rolled the check off the weapon-item record. This
     * branch's official-mappings {@link AbstractArrow} ships the same logic natively as
     * {@link AbstractArrow#shotFromCrossbow()} — javap-verified (bytecode: {@code firedFromWeapon !=
     * null && firedFromWeapon.is(Items.CROSSBOW)}, byte-for-byte the same null-guarded check a
     * hand-written version would need) — so this delegates to it directly instead of
     * reimplementing it via {@code getWeaponItem()} + an {@code Items.CROSSBOW} check.
     */
    private static boolean isCrossbowShot(AbstractArrow projectile) {
        return projectile.shotFromCrossbow();
    }

    /**
     * Archery: a bow-fired arrow's <b>Skill Shot</b> damage bonus and <b>Arrow Retrieval</b> credit
     * (legacy {@code processArcheryCombat}).
     *
     * <p>Skill Shot, Arrow Retrieval and the XP award are independent, as they are upstream — each
     * sits in its own {@code if}, so a player whose Skill Shot is locked (or disabled) still collects
     * their arrows and still earns Archery XP. Retrieval only credits the target here; the arrows
     * themselves drop when it dies (Arrow Retrieval's drop-on-death half lives in a not-yet-ported
     * {@code ProjectileListener}, out of this task's scope per the design spec).
     */
    private static float applyArcheryBonus(McMMOPlayer mmoPlayer, LivingEntity target,
            AbstractArrow projectile, float amount) {
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.ARCHERY, target)) {
            return amount;
        }
        final ArcheryManager archery = mmoPlayer.getArcheryManager();
        if (archery == null) {
            return amount;
        }

        if (archery.canRetrieveArrows()) {
            archery.retrieveArrows(target.getUUID(), projectile.getUUID());
        }

        float boostedDamage = amount;
        if (archery.canSkillShot()) {
            boostedDamage = (float) archery.skillShot(amount); // not additive — Skill Shot replaces it.
        }
        // Limit Break is added AFTER Skill Shot, because Skill Shot replaces the damage rather than
        // adding to it — ahead of it the bonus would simply be discarded. Legacy's ordering, and
        // unlike the melee arms this one is NOT scaled by attack strength: an arrow already in
        // flight has no swing left to charge. Same asymmetry the ranged Impale arm below preserves.
        boostedDamage += LimitBreak.bonusDamage(mmoPlayer,
                SubSkillType.ARCHERY_ARCHERY_LIMIT_BREAK);
        // Legacy pays `forceMultiplier * distanceMultiplier`. Bow force is stamped at launch by the
        // bow-shoot hook (Archery#markBowForce); an arrow that skipped that hook (or whose mark aged
        // out) reads back the flat 1.0 legacy defaulted it to, so the product degrades to
        // distance-only rather than to zero.
        final double xpMultiplier = Archery.bowForceMultiplier(projectile.getUUID())
                * distanceXpMultiplier(target, projectile);
        CombatUtils.processCombatXP(mmoPlayer, target, PrimarySkillType.ARCHERY, boostedDamage,
                xpMultiplier);
        return boostedDamage;
    }

    /**
     * The fired-from-distance XP multiplier for a projectile hit — legacy's static
     * {@code ArcheryManager#distanceXpBonusMultiplier(target, arrow)}, which both the Archery and the
     * Crossbows arm call. This owns only the MC-typed reads (the struck entity's world and position);
     * the measurement itself is MC-free on {@link Archery}.
     */
    private static double distanceXpMultiplier(LivingEntity target, AbstractArrow projectile) {
        return Archery.distanceXpBonusMultiplier(projectile.getUUID(),
                target.level().dimension().location().toString(),
                target.getX(), target.getY(), target.getZ());
    }

    /**
     * Crossbows Powered Shot: a crossbow bolt's damage bonus (legacy {@code processCrossbowsCombat}),
     * plus the bolt's distance-scaled per-hit Crossbows XP.
     *
     * <p>The distance multiplier is the very same Archery static legacy calls from here. Legacy also
     * hardcodes {@code forceMultiplier = 1.0} on this arm — a crossbow is loosed at full power, so
     * there is no draw to scale by — which is why this arm is complete while Archery's still owes its
     * force half.
     */
    private static float applyPoweredShot(McMMOPlayer mmoPlayer, LivingEntity target,
            AbstractArrow projectile, float amount) {
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.CROSSBOWS, target)) {
            return amount;
        }
        final CrossbowsManager crossbows = mmoPlayer.getCrossbowsManager();
        if (crossbows == null) {
            return amount;
        }

        float boostedDamage = amount;
        if (crossbows.canPoweredShot()) {
            boostedDamage = (float) crossbows.poweredShot(amount); // not additive — it replaces it.
        }
        // After Powered Shot for the same reason as Archery's, and unscaled for the same reason.
        boostedDamage += LimitBreak.bonusDamage(mmoPlayer,
                SubSkillType.CROSSBOWS_CROSSBOWS_LIMIT_BREAK);
        CombatUtils.processCombatXP(mmoPlayer, target, PrimarySkillType.CROSSBOWS, boostedDamage,
                distanceXpMultiplier(target, projectile));
        return boostedDamage;
    }

    /**
     * Tridents Impale (ranged): a thrown trident's flat damage bonus (legacy
     * {@code processTridentCombatRanged}) plus its per-hit Tridents XP. Unlike the melee trident path,
     * the ranged bonus is <em>not</em> scaled by attack strength — a thrown trident has no swing to
     * charge.
     */
    private static float applyTridentImpale(McMMOPlayer mmoPlayer, LivingEntity target, float amount) {
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.TRIDENTS, target)) {
            return amount;
        }
        final TridentsManager tridents = mmoPlayer.getTridentsManager();
        if (tridents == null) {
            return amount;
        }

        float boostedDamage = amount;
        if (tridents.canImpale()) {
            boostedDamage = amount + (float) tridents.impaleDamageBonus();
        }
        // Unscaled here but attack-strength-scaled on the melee path — that split is legacy's own
        // (processTridentCombatRanged vs processTridentCombatMelee) and is the same reason Impale
        // itself is scaled in MeleeDamageBonus and flat here.
        boostedDamage += LimitBreak.bonusDamage(mmoPlayer,
                SubSkillType.TRIDENTS_TRIDENTS_LIMIT_BREAK);
        CombatUtils.processCombatXP(mmoPlayer, target, PrimarySkillType.TRIDENTS, boostedDamage);
        return boostedDamage;
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
        // so the only way one survives is a hit whose getDamageAfterArmorAbsorb mixin injector ran
        // and whose LivingDamageEvent.Pre never posted. That would strand a single entity + damage
        // source reference on the server thread, which in singleplayer outlives the world the
        // player just left.
        PRE_ARMOR_DAMAGE.remove();
    }

    /**
     * Stealth <b>Assassin</b>: a melee hit thrown while crouched, by someone who has not been hit
     * recently, is a backstab and lands for a multiple of its normal damage.
     *
     * <p>Gated like {@link #applySprintSmash} and for the same reason — the sub-skill is "you struck
     * from the shadows", not "you struck with a sword" — so it fires with whatever is in hand,
     * including nothing.
     *
     * <p><b>Multiplicative, and applied to the running total</b>, so it scales the weapon skill's
     * on-hit bonus and Smash along with the base swing. That is deliberate (a backstab multiplies the
     * whole blow) and it is also the most likely thing in this skill to be over-tuned: it compounds
     * with vanilla critical hits too.
     *
     * <p>The recency half of the gate is what stops it being a free permanent damage buff for anyone
     * willing to fight crouched: take a single hit and it is off for
     * {@code NoDamageWindowTicks}. Per-hit combat XP is not re-paid here — the weapon arm already
     * paid it on the pre-Assassin damage, and the extra came from Stealth, not from the weapon.
     */
    static float applyAssassin(LivingEntity target, DamageSource source, float amount) {
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return amount;
        }
        // Direct melee only, same test as the weapon arm: a projectile's direct source is the
        // projectile, and Thorns is not a swing.
        if (source.getDirectEntity() != attacker || source.is(DamageTypes.THORNS)) {
            return amount;
        }
        if (!attacker.isShiftKeyDown() || isTargetDummy(target)) {
            return amount;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(attacker.getUUID());
        if (mmoPlayer == null) {
            return amount;
        }
        final StealthManager stealth = mmoPlayer.getStealthManager();
        if (stealth == null
                || !stealth.assassinReady(true, ticksSinceDamageTaken(attacker))) {
            return amount;
        }

        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                "Stealth.SubSkill.Assassin.Proc");
        return (float) (amount * stealth.getAssassinDamageMultiplier());
    }

    /**
     * Hunter <b>Mob Mastery</b>: flat bonus damage against a creature this player has personally
     * killed enough of.
     *
     * <p>Third sibling of {@link #applySprintSmash} and {@link #applyAssassin} on this seam, and a
     * sibling rather than a new arm of {@code MeleeDamageBonus} for the reason those two are: that
     * class dispatches on the <em>weapon in hand</em>, and mastery has nothing to do with what you are
     * holding. It is in fact the only bonus here keyed on the <b>target's identity</b> — the same
     * creature is worth the same bonus to a fist as to a netherite sword, which is why the flat figure
     * is deliberately small.
     *
     * <h2>Which hits qualify</h2>
     * Melee swings and the player's own projectiles — arrows, bolts, thrown tridents — mirroring
     * exactly the two K1 arms above that already accept them, and no wider. Explicitly excluded:
     * <ul>
     *   <li><b>The wolf-bite arm.</b> A wolf's hit is credited to the wolf, so gate 1 drops it for
     *       free; Taming's Sharpened Claws and Gore already own that damage and adding Hunter would
     *       double-dip on one bite.</li>
     *   <li><b>Everything else a player can be blamed for</b> — their lit TNT, a splash potion, a
     *       Blast Mining charge. Each is attributable to the player and none is a hunt.</li>
     *   <li><b>Thorns</b>, for the same reason Smash and Assassin refuse it: being punched is not
     *       swinging.</li>
     * </ul>
     *
     * <p><b>Spawn origin is deliberately NOT re-checked here.</b> A spawner zombie is still a zombie,
     * and knowledge earned in the wild does not evaporate when the next one arrives on a mineshaft
     * floor. Refusing the bonus there would close no farm — the farm earns no mastery either way —
     * while making the damage a player sees depend on an invisible property of the mob they are
     * hitting.
     *
     * <p>No notification and no XP re-paid, matching Smash and Assassin — the weapon arm already paid
     * combat XP on the pre-bonus figure, and this damage came from Hunter, not the weapon.
     *
     * <p>⚠️ Must stay LAST in {@link #onModifyAppliedDamage}'s attacker chain — see that method's
     * comment on why.
     */
    static float applyHunterMastery(LivingEntity target, DamageSource source, float amount) {
        // Gate 1: the hit has to be the player's. For a projectile getEntity() resolves back to the
        // shooter, so this admits both halves at once — and keeping the two entry conditions
        // identical is what stops "the kill counted" and "the bonus applied" drifting apart from
        // HunterListener's own qualifyingKiller gate 1.
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return amount;
        }
        if (isTargetDummy(target)) {
            return amount;
        }

        // Melee is the direct-source test the weapon arm, Smash and Assassin all use; Thorns is
        // credited to the wearer but is not a swing.
        final boolean melee = source.getDirectEntity() == attacker && !source.is(DamageTypes.THORNS);
        if (!melee && !isProjectileFrom(source, attacker)) {
            return amount;
        }
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.HUNTER, target)) {
            return amount;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(attacker.getUUID());
        if (mmoPlayer == null) {
            return amount; // data not loaded (e.g. mid-join).
        }
        final HunterManager hunter = mmoPlayer.getHunterManager();
        if (hunter == null) {
            return amount;
        }

        final double bonus = hunter.masteryDamageBonusForHit(
                HunterListener.masteryKeyOf(target), melee);
        return bonus <= 0 ? amount : amount + (float) bonus;
    }

    /**
     * Whether this damage was delivered by a projectile {@code attacker} fired themselves.
     *
     * <p>Owner identity rather than a bare {@code instanceof ServerPlayer}: the responsible entity has
     * already been resolved, and requiring the two to agree means a projectile whose attribution and
     * ownership disagree (a mod re-crediting a shot mid-flight) contributes nothing rather than paying
     * the wrong player's mastery.
     */
    private static boolean isProjectileFrom(DamageSource source, ServerPlayer attacker) {
        return source.getDirectEntity() instanceof AbstractArrow projectile
                && projectile.getOwner() == attacker;
    }

    // --- Swords: Counter Attack ------------------------------------------------------------------

    /**
     * Swords Counter Attack: a player hit while holding a sword may reflect a fraction of the damage
     * back at their assailant. Ports legacy {@code CombatUtils#processCombatAttack}'s defender arm
     * plus {@code SwordsManager#counterAttackChecks}.
     *
     * <p>Only a <em>living, direct</em> damager can be countered — legacy passes {@code painSource}
     * (the damager itself, not the projectile's shooter) and its {@code canUseCounterAttack} requires
     * {@code instanceof LivingEntity}, so an arrow or a Blast Mining charge counters nothing.
     *
     * <p>⚠️ FIXED UPSTREAM BUG (a previously-fixed role-inversion bug, preserved verbatim): legacy
     * gates this on {@code canCombatSkillsTrigger(SWORDS, target)}, but in the defender arm
     * {@code target} would be the <em>player</em>, not the entity being acted upon. That makes
     * {@code isPlayerOrTamed} unconditionally true, so a PvE counter against a mob is decided by
     * {@code Enabled_For_PVP} — an operator disabling Swords for PvP silently kills counter-attacks
     * against mobs, and one disabling it for PvE does not. Every other call site in this file passes
     * the entity the skill acts upon; this one passes {@code assailant}, not {@code serverPlayer} —
     * do not "simplify" this call during any future edit, that IS the fix. Both switches default to
     * {@code true}, so the shipped config behaves identically either way.
     *
     * <p>Package-private rather than private so the gate can be exercised directly, the same
     * "package-private for testing" convention {@link #onAllowDamage} and
     * {@link #recordDamageTaken}/{@link #ticksSinceDamageTaken} already use in this file.
     *
     * @param damage the damage the player is taking, after any Dodge reduction
     */
    static void maybeProcessCounterAttack(ServerPlayer serverPlayer, DamageSource source,
            float damage) {
        // The *direct* damager, matching legacy's painSource (not painSourceRoot).
        if (!(source.getDirectEntity() instanceof LivingEntity assailant)) {
            return;
        }
        if (!ItemUtils.isSword(serverPlayer.getMainHandItem())) {
            return;
        }
        if (!CombatUtils.canCombatSkillsTrigger(PrimarySkillType.SWORDS, assailant)) {
            return;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return;
        }
        final SwordsManager swords = mmoPlayer.getSwordsManager();
        if (swords == null || !swords.canUseCounterAttack() || !swords.rollCounterAttack()) {
            return;
        }

        CombatUtils.safeDealDamage(assailant, swords.counterAttackDamage(damage), serverPlayer);
        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                "Swords.Combat.Countered");
        // PORT: legacy also notified the countered attacker ("Swords.Combat.Counter.Hit"), which only
        // fires `if (attacker instanceof Player)` — dead in singleplayer, where the only player is the
        // one countering. Dropped with the rest of PvP.
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
