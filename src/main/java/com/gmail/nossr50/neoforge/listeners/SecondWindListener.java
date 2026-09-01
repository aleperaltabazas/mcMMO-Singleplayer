package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.Materials;
import com.gmail.nossr50.runnables.skills.AbilityDisableTask;
import com.gmail.nossr50.skills.movement.Medium;
import com.gmail.nossr50.skills.movement.MovementManager;
import com.gmail.nossr50.skills.movement.SecondWindResult;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.sounds.SoundManager;
import com.gmail.nossr50.util.sounds.SoundType;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.jetbrains.annotations.NotNull;

/**
 * Agility's super ability, <b>Second Wind</b> — one cooldowned active with three bodies, dispatched
 * on how the player is moving when they fire it.
 *
 * <table>
 *   <caption>Bodies</caption>
 *   <tr><th>State</th><th>Body</th><th>Effect</th></tr>
 *   <tr><td>sprinting on land</td><td>Dart</td>
 *       <td>forward lunge; entities in the path take damage and heavy knockback</td></tr>
 *   <tr><td>in water</td><td>Aquaman</td>
 *       <td>Strength + Regeneration + Night Vision for the ability's duration</td></tr>
 *   <tr><td>gliding</td><td>Limitless</td><td>upward and forward burst</td></tr>
 *   <tr><td>anything else</td><td>—</td>
 *       <td>refused, <b>without consuming the cooldown</b></td></tr>
 * </table>
 *
 * <p>Ports {@code fabric.listeners.SecondWindListener} onto {@link PlayerInteractEvent.RightClickItem}
 * — the same NeoForge seam {@link HerdsmansCallListener} rides — rather than Fabric's
 * {@code UseItemCallback}. Structurally a sibling of that listener: main hand only, server-side only,
 * hand-rolled activation sequence (Agility has no {@code ToolType}, so
 * {@code McMMOPlayer#checkAbilityActivation} does not apply), and no cancellation/consumption of the
 * trigger item — mcMMO observes the click, it does not replace it.
 *
 * <p>Unlike Herdsman's Call, this listener owns the <em>entire</em> ability: the trigger gate and all
 * three effect bodies. There is no separate manager sweep or per-tick hook to lean on, because the
 * effect is a one-shot burst rather than a standing buff read elsewhere.
 *
 * <p><b>⚠️ The trigger item must differ from Smoke Bomb's and Herdsman's Call's.</b> All three
 * hand-rolled use-item actives listen on the same event; a shared item would fire whichever gate
 * passes first and print the others' refusal messages alongside it.
 *
 * <p><b>Refusing must not burn the cooldown.</b> Every gate in {@link #tryActivate} returns before
 * {@code calculateTimeRemaining}/{@code setAbilityDATS} is stamped, exactly as the Fabric original.
 */
public final class SecondWindListener {

    private SecondWindListener() {
    }

    /** Vertical component of the Limitless burst, as a fraction of its forward magnitude. */
    private static final double LIMITLESS_LIFT = 0.5;

    /** How far either side of the lunge path an entity can be and still be caught by Dart. */
    private static final double DART_HIT_RADIUS = 1.5;

    /** Register the interaction hook. Called once at mod load from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(SecondWindListener::onUseItem);
    }

    /**
     * Right-click the air with the trigger item in the main hand → try to catch a second wind.
     * Package-private so {@code SecondWindListenerTest} can drive the real dispatch rather than the
     * predicates alone, mirroring {@code HerdsmansCallListenerTest}.
     */
    static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        final Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return; // client-side fire: ignore, same as SuperAbilityListener#resolve.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return;
        }
        if (!isHoldingTriggerItem(event.getItemStack())) {
            return;
        }
        tryActivate(mmoPlayer, serverPlayer);
        // No cancellation, no item consumption: this listener only observes the click.
    }

    /** Whether {@code stack} is the configured Second Wind trigger item (default {@code FEATHER}). */
    private static boolean isHoldingTriggerItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final String configured = McMMOMod.getGeneralConfig() == null
                ? "FEATHER"
                : McMMOMod.getGeneralConfig().getSecondWindItem();
        final Optional<Item> triggerItem = Materials.item(configured);
        return triggerItem.isPresent() && stack.is(triggerItem.get());
    }

    /**
     * How long a Second Wind fired in {@code medium} runs for, in seconds — scaled on the skill the
     * player earns by travelling through <em>that</em> medium.
     *
     * <p>Package-visible and pure over {@code (McMMOPlayer, Medium)} so the mapping from medium to
     * skill is testable without a world: mock a different length per skill and the answer names which
     * one was asked.
     */
    static int durationTicks(@NotNull McMMOPlayer mmoPlayer, @NotNull Medium medium) {
        return mmoPlayer.calculateAbilityActivationTicks(medium.primarySkill(),
                SuperAbilityType.SECOND_WIND);
    }

    private static void tryActivate(@NotNull McMMOPlayer mmoPlayer, @NotNull ServerPlayer player) {
        if (mmoPlayer.getAbilityMode(SuperAbilityType.SECOND_WIND)) {
            return; // Already running.
        }
        if (!mmoPlayer.getAbilityUse()) {
            return; // Player toggled abilities off with /mcability.
        }

        final Medium medium = PlayerMovementTracker.classifyMedium(player);
        if (medium == null) {
            // Standing still, walking, or riding something. Tell them why and leave the cooldown
            // untouched so the next press — while actually moving — works.
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                    "Movement.Skills.SecondWind.NotMoving");
            return;
        }

        final MovementManager agility = mmoPlayer.getMovementManager();
        if (agility == null) {
            return;
        }

        final int timeRemaining = mmoPlayer.calculateTimeRemaining(SuperAbilityType.SECOND_WIND);
        if (timeRemaining > 0) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.ABILITY_COOLDOWN,
                    "Skills.TooTired", String.valueOf(timeRemaining));
            return;
        }

        final int ticks = durationTicks(mmoPlayer, medium);
        final SecondWindResult result = agility.computeSecondWind(medium, ticks);
        if (result == null) {
            // This medium's rank is not unlocked — the player has Second Wind but not, say, its
            // water body yet. Also a free refusal.
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                    "Movement.Skills.SecondWind.Locked");
            return;
        }

        activate(mmoPlayer, player, result, ticks);
    }

    private static void activate(@NotNull McMMOPlayer mmoPlayer, @NotNull ServerPlayer player,
            @NotNull SecondWindResult result, int ticks) {
        if (mmoPlayer.useChatNotifications()) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUPER_ABILITY,
                    SuperAbilityType.SECOND_WIND.getAbilityOn());
        }
        SoundManager.worldSendSound(mmoPlayer.getPlayer(), SoundType.ABILITY_ACTIVATED_GENERIC);

        mmoPlayer.setAbilityDATS(SuperAbilityType.SECOND_WIND,
                System.currentTimeMillis() + ((long) ticks * Misc.TIME_CONVERSION_FACTOR));
        mmoPlayer.setAbilityMode(SuperAbilityType.SECOND_WIND, true);
        McMMOMod.getScheduler().runLater(
                new AbilityDisableTask(mmoPlayer, SuperAbilityType.SECOND_WIND),
                (long) ticks * Misc.TICK_CONVERSION_FACTOR);

        switch (result.medium()) {
            case LAND -> dart(player, result);
            case WATER -> aquaman(player, result);
            case AIR -> limitless(player, result);
        }
    }

    /**
     * Dart: launch the player along their look vector and hit whatever is in the way.
     *
     * <p>The lunge is applied first so the sweep and the movement describe the same path; entities
     * are collected from a box around that path rather than by a raycast, so a lunge past someone's
     * shoulder still connects instead of requiring pixel-accurate aim.
     */
    private static void dart(@NotNull ServerPlayer player, @NotNull SecondWindResult result) {
        final Vec3 look = player.getLookAngle().normalize();
        final Vec3 lunge = look.scale(result.magnitude());
        setVelocity(player, player.getDeltaMovement()
                .add(lunge.x, Math.max(0.1, lunge.y * 0.5), lunge.z));

        final ServerLevel level = player.serverLevel();
        final Vec3 from = player.position();
        final Vec3 to = from.add(look.scale(result.dartRange()));
        final AABB path = new AABB(from, to).inflate(DART_HIT_RADIUS);

        final List<LivingEntity> hits = level.getEntities(EntityTypeTest.forClass(LivingEntity.class),
                path, entity -> entity != player && entity.isAlive());
        for (LivingEntity target : hits) {
            target.hurt(level.damageSources().playerAttack(player), (float) result.dartDamage());
            // Knock the target away from the player, along the horizontal lunge direction.
            target.knockback(result.dartKnockback(), -look.x, -look.z);
        }
    }

    /** Aquaman: a timed underwater buff. Duration is the ability's own length. */
    private static void aquaman(@NotNull ServerPlayer player, @NotNull SecondWindResult result) {
        final int duration = result.durationTicks();
        final int amplifier = (int) result.magnitude();
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, duration, amplifier));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, duration, amplifier));
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration, 0));
        // Dolphin's Grace is the swim-speed half of the fantasy and is a status effect rather than an
        // attribute, so it composes with Fleet Footed's water modifier instead of fighting it.
        player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, duration, 0));
    }

    /** Limitless: a forward-and-up burst for a gliding player. */
    private static void limitless(@NotNull ServerPlayer player, @NotNull SecondWindResult result) {
        final Vec3 look = player.getLookAngle().normalize();
        final double boost = result.magnitude();
        setVelocity(player, player.getDeltaMovement()
                .add(look.x * boost, look.y * boost + LIMITLESS_LIFT, look.z * boost));
    }

    /**
     * Set a player's velocity <em>and make their own client honour it</em>.
     *
     * <p>Setting velocity server-side is not enough on its own: the velocity-update packet a moving
     * entity publishes goes to <em>other</em> nearby players, never back to the entity that moved, so
     * the player's own client would keep simulating the old velocity and the server would accept its
     * position — the impulse would silently do nothing. Sending the packet explicitly is what
     * Bukkit's {@code Player#setVelocity} does, and it is correct here because these are one-shot
     * impulses; the same approach per-tick would fight the client's own prediction, which is why the
     * continuous air bonuses go through a shared-movement mixin instead.
     */
    private static void setVelocity(@NotNull ServerPlayer player, @NotNull Vec3 velocity) {
        player.setDeltaMovement(velocity);
        player.hasImpulse = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }
}
