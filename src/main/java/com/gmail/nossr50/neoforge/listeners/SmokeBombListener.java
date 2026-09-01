package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.Materials;
import com.gmail.nossr50.runnables.skills.AbilityDisableTask;
import com.gmail.nossr50.skills.stealth.StealthManager;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.sounds.SoundManager;
import com.gmail.nossr50.util.sounds.SoundType;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Stealth's super ability, <b>Smoke Bomb</b> — right-click the trigger item and vanish.
 *
 * <p>Ports {@code fabric.listeners.SmokeBombListener} onto {@link PlayerInteractEvent.RightClickItem}
 * — the same NeoForge seam {@link HerdsmansCallListener} and {@link SecondWindListener} ride —
 * rather than Fabric's {@code UseItemCallback}. Structurally a sibling of
 * {@link SecondWindListener}: the same hand-rolled activation sequence and the same rule that a
 * refusal must not burn the cooldown. It is a separate class rather than another body on that one
 * because the two abilities belong to different skills and share no state; folding them together
 * would mean one listener that has to decide which skill a click was meant for.
 *
 * <p><b>⚠️ The trigger item must differ from Second Wind's and Herdsman's Call's.</b> All three
 * hand-rolled use-item actives listen on the same event, so a shared item would activate one of them
 * and print the others' refusal messages alongside it. Shipped as {@code GUNPOWDER} against Second
 * Wind's {@code FEATHER} and Herdsman's Call's {@code GOAT_HORN}.
 *
 * <p><b>No firework, no particles.</b> The wiki version of this ability puffs smoke; vanilla
 * invisibility trails particles. Both are suppressed, and the particle half is not cosmetic: a
 * sparkling outline tells anyone looking exactly where the invisible player is, which defeats the
 * entire ability. The status icon is left on, since that is only visible to the player themselves.
 *
 * <p><b>Armour and held items stay visible</b> — that is vanilla invisibility's behaviour and it is
 * not worked around here. A player who wants to disappear properly takes their armour off first,
 * which is a real and interesting cost rather than a limitation to paper over.
 */
public final class SmokeBombListener {

    private SmokeBombListener() {
    }

    /** Register the interaction hook. Called once at mod load from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(SmokeBombListener::onUseItem);
    }

    /**
     * Right-click the air with the trigger item in the main hand → try to vanish.
     * Package-private so {@code SmokeBombListenerTest} can drive the real dispatch rather than the
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

    /** Whether {@code stack} is the configured Smoke Bomb trigger item (default {@code GUNPOWDER}). */
    private static boolean isHoldingTriggerItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final String configured = McMMOMod.getGeneralConfig() == null
                ? "GUNPOWDER"
                : McMMOMod.getGeneralConfig().getSmokeBombItem();
        final Optional<Item> triggerItem = Materials.item(configured);
        return triggerItem.isPresent() && stack.is(triggerItem.get());
    }

    private static void tryActivate(@NotNull McMMOPlayer mmoPlayer, @NotNull ServerPlayer player) {
        if (mmoPlayer.getAbilityMode(SuperAbilityType.SMOKE_BOMB)) {
            return; // Already vanished.
        }
        if (!mmoPlayer.getAbilityUse()) {
            return; // Player toggled abilities off with /mcability.
        }

        final StealthManager stealth = mmoPlayer.getStealthManager();
        if (stealth == null || !stealth.canSmokeBomb()) {
            return; // Rank not reached, or the sub-skill is disabled. Silent, like every rank gate.
        }

        final int timeRemaining = mmoPlayer.calculateTimeRemaining(SuperAbilityType.SMOKE_BOMB);
        if (timeRemaining > 0) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.ABILITY_COOLDOWN,
                    "Skills.TooTired", String.valueOf(timeRemaining));
            return;
        }

        activate(mmoPlayer, player, durationTicks(mmoPlayer, stealth));
    }

    /**
     * How long the bomb lasts, in ticks.
     *
     * <p>Two knobs feed this and both are live, which is deliberate — a shipped setting that turns
     * out to change nothing is a recurring defect in this port, not a harmless leftover: the standard
     * super-ability length machinery (so Smoke Bomb scales with skill level and honours
     * {@code Ability_Length}, {@code Ability_Length_Cap} and {@code Max_Seconds.Smoke_Bomb} exactly
     * like every other super ability) and {@code advanced.yml}'s {@code SmokeBomb.DurationTicks} as a
     * <b>floor</b>, so the ability is worth pressing the moment its rank unlocks rather than lasting a
     * couple of seconds at the level you first earn it.
     *
     * <p>The same number drives the invisibility <em>and</em> the ability's own lifetime.
     */
    static int durationTicks(@NotNull McMMOPlayer mmoPlayer, @NotNull StealthManager stealth) {
        final int seconds = mmoPlayer.calculateAbilityActivationTicks(PrimarySkillType.STEALTH,
                SuperAbilityType.SMOKE_BOMB);
        return Math.max(seconds * Misc.TICK_CONVERSION_FACTOR, stealth.getSmokeBombDurationTicks());
    }

    private static void activate(@NotNull McMMOPlayer mmoPlayer, @NotNull ServerPlayer player,
            int durationTicks) {
        if (mmoPlayer.useChatNotifications()) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUPER_ABILITY,
                    SuperAbilityType.SMOKE_BOMB.getAbilityOn());
        }
        SoundManager.worldSendSound(mmoPlayer.getPlayer(), SoundType.ABILITY_ACTIVATED_GENERIC);

        final long millis = (long) durationTicks * Misc.TIME_CONVERSION_FACTOR
                / Misc.TICK_CONVERSION_FACTOR;
        mmoPlayer.setAbilityDATS(SuperAbilityType.SMOKE_BOMB, System.currentTimeMillis() + millis);
        mmoPlayer.setAbilityMode(SuperAbilityType.SMOKE_BOMB, true);
        McMMOMod.getScheduler().runLater(
                new AbilityDisableTask(mmoPlayer, SuperAbilityType.SMOKE_BOMB), durationTicks);

        // (effect, duration, amplifier, ambient, showParticles, showIcon) — particles off so the
        // effect cannot betray the player it is hiding; icon on, since only they can see it.
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, durationTicks, 0, false, false,
                true));
    }
}
