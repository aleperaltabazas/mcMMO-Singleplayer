package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.skills.movement.Medium;
import com.gmail.nossr50.skills.movement.MovementManager;
import com.gmail.nossr50.util.player.UserManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Agility's two remaining hooks, ported from Fabric's {@code AthleteListener} and
 * {@code GlideListener}: <b>Athlete</b> (sprinting costs less hunger) and <b>Fleet Footed (air)</b> /
 * <b>Glide</b> (elytra velocity bonuses). Roll, Graceful Roll, and Dodge already live in
 * {@link EntityDamageListener}.
 *
 * <p>Merged into one file, matching the Fabric originals' shared shape: both are small, stateless
 * static helpers for the same skill, called from a one-line mixin seam.
 */
public final class AgilityListener {

    private AgilityListener() {
    }

    /**
     * Agility → <b>Athlete</b>: scale one exhaustion event by the player's Athlete bonus.
     *
     * <p>{@code FoodData#addExhaustion} is every exhaustion source in the game funnelled into one
     * method — sprinting, jumping, swimming, mining, taking damage, regenerating. Scaling all of it
     * would turn a sprint perk into a general "you barely get hungry" perk, so the discount is gated
     * on the player <em>currently sprinting</em>.
     *
     * <p>{@link FoodData} carries no back-reference to its owner, so the player is resolved by
     * identity against the online list. In singleplayer that is a one-element scan; it also doubles
     * as the server-side gate, since a client-side food data matches nothing and is left alone (food
     * is server-authoritative and synced to the client, so discounting it twice would be wrong
     * anyway).
     *
     * @param foodData   the food data the exhaustion is being applied to
     * @param exhaustion the exhaustion vanilla wants to add
     * @return the reduced exhaustion, or {@code exhaustion} unchanged when Athlete does not apply
     */
    public static float scaleExhaustion(@NotNull FoodData foodData, float exhaustion) {
        if (exhaustion <= 0) {
            return exhaustion;
        }
        final ServerPlayer player = ownerOf(foodData);
        if (player == null || !player.isSprinting()) {
            return exhaustion;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(player.getUUID());
        if (mmoPlayer == null) {
            return exhaustion;
        }
        final MovementManager agility = mmoPlayer.getMovementManager();
        if (agility == null) {
            return exhaustion;
        }
        return (float) (exhaustion * agility.getAthleteExhaustionMultiplier());
    }

    /**
     * The online player this food data belongs to, or {@code null} if it belongs to none — which
     * covers the client-side data and anything that runs outside a world session.
     */
    private static @Nullable ServerPlayer ownerOf(@NotNull FoodData foodData) {
        final MinecraftServer server = McMMOMod.getServer();
        if (server == null) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getFoodData() == foodData) {
                return player;
            }
        }
        return null;
    }

    /**
     * Agility's air-domain velocity maths — <b>Fleet Footed (air)</b> scales the horizontal
     * components, and <b>Glide</b> scales the downward component. Both share this one hook because
     * they modify the same vector on the same tick; splitting them into separate injectors would
     * mean two passes over the same value for no gain.
     *
     * @param entity        the gliding entity — mobs can glide too, and have no mcMMO data
     * @param glideVelocity vanilla's computed velocity for this tick
     * @return the modified velocity, or {@code glideVelocity} unchanged when nothing applies
     */
    public static @NotNull Vec3 modifyGlideVelocity(@NotNull LivingEntity entity,
            @NotNull Vec3 glideVelocity) {
        if (!(entity instanceof Player)) {
            return glideVelocity;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(entity.getUUID());
        if (mmoPlayer == null) {
            return glideVelocity;
        }
        final MovementManager agility = mmoPlayer.getMovementManager();
        if (agility == null) {
            return glideVelocity;
        }

        final double forwardBonus = agility.getFleetFootedBonus(Medium.AIR);
        final double descentReduction = agility.getGlideDescentReduction();
        if (forwardBonus <= 0 && descentReduction <= 0) {
            return glideVelocity; // Nothing unlocked — the common case; don't allocate a Vec3.
        }

        final double scale = 1.0 + forwardBonus;
        // Only soften *downward* motion. Scaling upward motion too would let a maxed player climb on
        // a rising thermal indefinitely, and the sub-skill is "descend slower", not "fly upward".
        final double y = glideVelocity.y < 0
                ? glideVelocity.y * (1.0 - descentReduction)
                : glideVelocity.y;
        return new Vec3(glideVelocity.x * scale, y, glideVelocity.z * scale);
    }
}
