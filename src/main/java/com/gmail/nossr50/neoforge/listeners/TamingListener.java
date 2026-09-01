package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.text.ConfigStringUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * The K7 Taming XP hook: awards Taming XP when a player tames an animal. Ports
 * {@code fabric.listeners.TamingListener} — see docs/superpowers/specs/
 * 2026-09-01-taming-listener-design.md §1.
 *
 * <p>Vanilla has no NeoForge event for taming, so this is driven by two mixins on the tame-by-player
 * entry points — {@code TamableAnimal#tame(Player)} (wolves/cats/parrots) and
 * {@code AbstractHorse#tameWithName(Player)} (horses/donkeys/mules/llamas/camels). Both funnel into
 * {@link #onEntityTamed(Player, Entity)}. The tamed entity's registry path is turned into the
 * config-entity string ({@code minecraft:wolf} → {@code "Wolf"}) that
 * {@code ExperienceConfig.getTamingXP} is keyed on; the MC-free award itself lives on
 * {@link TamingManager#awardTamingXP(String)}.
 */
public final class TamingListener {

    private TamingListener() {
    }

    /**
     * Award Taming XP to the owner for a freshly tamed animal. Gated to server players with loaded
     * mcMMO data; a no-op otherwise. Called from the tame mixins.
     *
     * @param owner the player who tamed the animal (mixin passes the vanilla {@code Player})
     * @param tamed the entity that was tamed
     */
    public static void onEntityTamed(Player owner, Entity tamed) {
        if (!(owner instanceof ServerPlayer serverPlayer)) {
            return; // client-side / null owner — the authoritative award happens on the server.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return; // data not loaded (e.g. mid-join).
        }
        final TamingManager taming = mmoPlayer.getTamingManager();
        if (taming == null) {
            return;
        }

        final String entityConfigString = ConfigStringUtils.getConfigEntityTypeString(
                BuiltInRegistries.ENTITY_TYPE.getKey(tamed.getType()).getPath());
        taming.awardTamingXP(entityConfigString);
    }
}
