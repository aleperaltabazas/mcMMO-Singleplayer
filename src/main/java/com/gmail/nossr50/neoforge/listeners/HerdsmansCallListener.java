package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.Materials;
import com.gmail.nossr50.runnables.skills.AbilityDisableTask;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.sounds.SoundManager;
import com.gmail.nossr50.util.sounds.SoundType;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Husbandry's super ability, <b>Herdsman's Call</b> — sound the horn and the herd answers.
 *
 * <p>Ports {@code fabric.listeners.HerdsmansCallListener} onto {@link PlayerInteractEvent.RightClickItem}
 * — the NeoForge seam {@link SuperAbilityListener#onUseItem} already rides for the tool-skill readying
 * and Blast Mining's remote detonation — rather than Fabric's {@code UseItemCallback}. A separate class
 * rather than another body on {@link SuperAbilityListener}: this ability has no {@code ToolType} and no
 * {@code checkAbilityActivation} path (Husbandry's harvest verbs use a mix of tools and bare hands), so
 * it hand-rolls its own activation exactly as the Fabric original did, and none of
 * {@link SuperAbilityListener}'s tool-preparation machinery applies to it.
 *
 * <p><b>⚠️ The trigger item must differ from any other tool-free active's.</b> mcMMO ships three
 * hand-rolled use-item actives — Second Wind ({@code FEATHER}), Smoke Bomb ({@code GUNPOWDER}) and this
 * one ({@code GOAT_HORN}) — and a shared item would fire whichever gate passes first and print the
 * others' refusal messages alongside it. Neither Second Wind nor Smoke Bomb has been wired to a NeoForge
 * listener yet (Fabric-only so far), so today this is the only occupant of {@code RightClickItem} outside
 * {@link SuperAbilityListener}; the distinct-item config guard stays in place regardless.
 *
 * <h2>What the ability actually does, and where each part lives</h2>
 * Three effects, and only one of them is here:
 * <ul>
 *   <li><b>The herd follows you</b> without you holding food — {@code PlayerMovementTracker}, because
 *       that is the mod's only per-tick per-player sweep and this needs to re-path every tick.</li>
 *   <li><b>Every harvest verb ignores its cooldown</b> — one check inside
 *       {@code HusbandryListener}'s cooldown gate, so it cannot be wired into milking and forgotten
 *       for brushing.</li>
 *   <li><b>Every harvest double-yields</b> — one check inside
 *       {@link HusbandryManager#rollBonusHarvestDrop()}, which all four verbs already route their
 *       bonus through, so the super reaches all four for free.</li>
 * </ul>
 * Nothing about the ability lives at a harvest call site, which is the point: the super multiplies four
 * verbs, and four copies of "if the super is active" is four chances to miss one.
 *
 * <p>This listener owns only the activation trigger: recognise the horn in the main hand, run the
 * already-active / abilities-off / rank-or-disabled / cooldown gates in order, and — on a clean pass —
 * notify, play a sound, arm {@link McMMOPlayer#setAbilityMode}, and schedule
 * {@link AbilityDisableTask}. The handler never cancels the event and never consumes the item: mcMMO
 * observes the click, it does not replace it, so the horn still sounds exactly as vanilla intends.
 */
public final class HerdsmansCallListener {

    private HerdsmansCallListener() {
    }

    /** Register the interaction hook. Called once at mod load from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(HerdsmansCallListener::onUseItem);
    }

    /**
     * Right-click the air with the trigger item in the main hand → try to sound the call.
     * Package-private so {@code HerdsmansCallListenerTest} can drive the real dispatch rather than
     * the predicates alone, mirroring {@code RepairSalvageListener#onUseBlock}.
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

    /** Whether {@code stack} is the configured Herdsman's Call trigger item (default {@code GOAT_HORN}). */
    private static boolean isHoldingTriggerItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final String configured = McMMOMod.getGeneralConfig() == null
                ? "GOAT_HORN"
                : McMMOMod.getGeneralConfig().getHerdsmansCallItem();
        final Optional<Item> triggerItem = Materials.item(configured);
        return triggerItem.isPresent() && stack.is(triggerItem.get());
    }

    private static void tryActivate(@NotNull McMMOPlayer mmoPlayer, @NotNull ServerPlayer player) {
        if (mmoPlayer.getAbilityMode(SuperAbilityType.HERDSMANS_CALL)) {
            return; // Already sounding.
        }
        if (!mmoPlayer.getAbilityUse()) {
            return; // Player toggled abilities off with /mcability.
        }

        final HusbandryManager husbandry = mmoPlayer.getHusbandryManager();
        if (husbandry == null || !husbandry.canHerdsmansCall()) {
            return; // Rank not reached, or the sub-skill is disabled. Silent, like every rank gate.
        }

        final int timeRemaining = mmoPlayer.calculateTimeRemaining(SuperAbilityType.HERDSMANS_CALL);
        if (timeRemaining > 0) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.ABILITY_COOLDOWN,
                    "Skills.TooTired", String.valueOf(timeRemaining));
            return;
        }

        activate(mmoPlayer, durationTicks(mmoPlayer, husbandry));
    }

    /**
     * How long the call lasts, in ticks.
     *
     * <p>Two knobs feed this and both are live, which is deliberate — a shipped setting that turns out
     * to change nothing is a recurring defect in this port rather than a harmless leftover. The
     * standard super-ability length machinery scales the ability with skill level and honours
     * {@code Ability_Length}, {@code Ability_Length_Cap} and {@code Max_Seconds.Herdsmans_Call};
     * {@code advanced.yml}'s {@code HerdsmansCall.DurationTicks} is a <b>floor</b>, so the ability is
     * worth pressing the moment its rank unlocks instead of lasting two seconds.
     */
    static int durationTicks(@NotNull McMMOPlayer mmoPlayer, @NotNull HusbandryManager husbandry) {
        final int seconds = mmoPlayer.calculateAbilityActivationTicks(PrimarySkillType.HUSBANDRY,
                SuperAbilityType.HERDSMANS_CALL);
        return Math.max(seconds * Misc.TICK_CONVERSION_FACTOR,
                husbandry.getHerdsmansCallDurationTicks());
    }

    private static void activate(@NotNull McMMOPlayer mmoPlayer, int durationTicks) {
        if (mmoPlayer.useChatNotifications()) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUPER_ABILITY,
                    SuperAbilityType.HERDSMANS_CALL.getAbilityOn());
        }
        SoundManager.worldSendSound(mmoPlayer.getPlayer(), SoundType.ABILITY_ACTIVATED_GENERIC);

        final long millis = (long) durationTicks * Misc.TIME_CONVERSION_FACTOR
                / Misc.TICK_CONVERSION_FACTOR;
        mmoPlayer.setAbilityDATS(SuperAbilityType.HERDSMANS_CALL, System.currentTimeMillis() + millis);
        mmoPlayer.setAbilityMode(SuperAbilityType.HERDSMANS_CALL, true);
        McMMOMod.getScheduler().runLater(
                new AbilityDisableTask(mmoPlayer, SuperAbilityType.HERDSMANS_CALL), durationTicks);
        // No effect is applied to the player: all three of this ability's effects are read from
        // getAbilityMode() by the code that needs them, so there is no state to unwind when it ends.
        // AbilityDisableTask clearing the mode IS the whole teardown.
    }
}
