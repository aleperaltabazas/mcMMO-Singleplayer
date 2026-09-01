package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.neoforge.McMMOMod;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Taming quality-of-life (GitHub #2): tamed pets come with you through a long jump inside one world.
 *
 * <p>Faithful port of Fabric's {@code PetFollowTeleport} (see {@code git show
 * d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/
 * PetFollowTeleport.java}) — see that file's own class doc for why this rides the movement sweep
 * instead of a teleport mixin, and why the fallback is a bounded radius rather than "every pet you
 * own."
 *
 * <p><b>Renamed methods verified against the extracted 1.21.1 sources (not transcribed from
 * Yarn):</b> {@code TameableEntity} → {@link TamableAnimal}; Fabric's {@code cannotFollowOwner()} does
 * not exist under this name — the real 1.21.1 predicate is {@code TamableAnimal#unableToMoveToOwner()}
 * (confirmed to cover the identical set: ordered-to-sit, passenger, leashable, or a spectating owner);
 * {@code tryTeleportToOwner()} is actually named {@code tryToTeleportToOwner()} (verified — the
 * no-arg, no-return-value method that performs vanilla's own ten-attempt placement search);
 * {@code shouldTryTeleportToOwner()} survives unchanged. {@code Entity#teleport(ServerWorld, x, y, z,
 * EnumSet&lt;PositionFlag&gt;, yaw, pitch)} is Mojang-mapped as {@code Entity#teleportTo(ServerLevel,
 * double, double, double, Set&lt;RelativeMovement&gt;, float, float)} — same 7-arg shape, same
 * {@code boolean} return, {@code PositionFlag} renamed to {@link RelativeMovement}. {@code
 * player.getEntityWorld()} is the real 1.21.1 accessor {@code Entity#level()} (returns {@code Level}),
 * cast to {@link ServerLevel} — this branch has no older-band compatibility to preserve, so the
 * Fabric class's own multi-band cast warning does not apply here. {@code player.isOnGround()} is
 * actually {@code Entity#onGround()} (no {@code is} prefix — verified). {@code isTouchingWater()} is
 * Mojang's {@code Entity#isInWater()}. {@code Box.of(from, dx, dy, dz)} → {@link AABB#ofSize(Vec3,
 * double, double, double)}, the exact equivalent (center point + size, not two corners).
 */
public final class PetFollowTeleport {

    private PetFollowTeleport() {
    }

    /** {@code Skills.Taming.Pets_Follow_Teleport} default, mirrored for a missing config. */
    static final boolean DEFAULT_ENABLED = true;

    /**
     * {@code Skills.Taming.Pets_Follow_Teleport_Radius} default, mirrored for a missing config.
     *
     * <p>Raised from 32 to 128 for GitHub #12 on Fabric. 32 assumed the pet was at the owner's heels,
     * which is only true when the owner is walking: a wolf pathing after a sprinting or elytra-borne
     * player is routinely further back than that when the teleport lands, and it was then simply not
     * collected. 128 covers the whole band in which a pet could still have been ticking (vanilla's
     * default simulation distance is 10 chunks), which is the real boundary — beyond it the pet
     * stopped being ticked, which is the very condition this class exists to work around.
     *
     * <p>It is deliberately still bounded. Distance is not what keeps a pet you parked at your base
     * from being yanked across the world — {@code unableToMoveToOwner()} is, because such a pet is
     * sat. The bound is about predictability: past the loaded region the answer would depend on which
     * chunks happen to be resident, and a feature that works intermittently is worse than one with a
     * limit a player can learn.
     */
    static final double DEFAULT_RADIUS = 128.0;

    /**
     * Detect a teleport and bring this player's pets along.
     *
     * <p>Called from {@link PlayerMovementTracker#tickPlayer} with the baseline it already keeps.
     * Takes no {@code McMMOPlayer}: nothing here is level-gated or per-profile, and requiring one
     * would make the feature depend on an unrelated load having completed — the ordering trap that
     * class carries warnings about.
     *
     * @param player    the player who has just moved
     * @param previous  their position on the previous tick, or {@code null} if there is no baseline
     * @param current   their position now
     * @param sameWorld whether {@code previous} was measured in the world they are in now
     */
    public static void onPlayerMoved(@NotNull ServerPlayer player, @Nullable Vec3 previous,
            @NotNull Vec3 current, boolean sameWorld) {
        if (previous == null || !sameWorld || !isTeleport(previous, current)) {
            return;
        }
        if (!isEnabled()) {
            return;
        }
        bringPetsFrom(player, previous, radius());
    }

    /**
     * Whether a single tick's movement is a teleport rather than travel.
     *
     * <p>Shares {@link PlayerMovementTracker#TELEPORT_DELTA} with the movement sweep on purpose: that
     * constant answers "how far is impossible in one tick", and two independent answers to that would
     * eventually disagree about the same jump. Measured in three dimensions here, where the tracker
     * measures horizontally only — the tracker is billing horizontal travel, whereas a vertical
     * {@code /tp} is every bit as much a teleport.
     */
    static boolean isTeleport(@NotNull Vec3 previous, @NotNull Vec3 current) {
        final double limit = PlayerMovementTracker.TELEPORT_DELTA;
        return current.distanceToSqr(previous) > limit * limit;
    }

    /**
     * Teleport every eligible pet within {@code radius} of {@code from} to the player.
     *
     * @return how many pets were moved, for the tests to assert on
     */
    static int bringPetsFrom(@NotNull ServerPlayer player, @NotNull Vec3 from, double radius) {
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        final AABB searchBox = AABB.ofSize(from, radius * 2, radius * 2, radius * 2);
        final List<TamableAnimal> pets = level.getEntities(EntityTypeTest.forClass(TamableAnimal.class),
                searchBox, pet -> isFollower(pet, player));

        int moved = 0;
        for (TamableAnimal pet : pets) {
            if (bring(pet, player)) {
                moved++;
            }
        }
        return moved;
    }

    /**
     * Whether this pet is one that would have followed the player had it still been ticking.
     *
     * <p>{@code unableToMoveToOwner()} is vanilla's own predicate and covers sitting, being ridden,
     * being leashed and a spectating owner. Reusing it rather than re-listing those conditions is what
     * makes "sit means stay" hold here without a second implementation to keep in step — a pet told to
     * wait somewhere must not be yanked across the world by its owner's ender pearl.
     */
    static boolean isFollower(@NotNull TamableAnimal pet, @NotNull ServerPlayer player) {
        return pet.isAlive() && pet.isTame() && pet.isOwnedBy(player) && !pet.unableToMoveToOwner();
    }

    /**
     * Move one pet to its owner. Returns whether it ended up there.
     *
     * <p>{@code shouldTryTeleportToOwner()} is re-asked <em>after</em> vanilla's attempt precisely
     * because it is the same question vanilla asked before it: a lingering yes means the placement
     * search found nowhere to put the pet, not that the pet did not need moving.
     */
    private static boolean bring(@NotNull TamableAnimal pet, @NotNull ServerPlayer player) {
        pet.tryToTeleportToOwner();
        if (!pet.shouldTryTeleportToOwner()) {
            return true; // Vanilla found it a spot beside the player.
        }

        if (!player.onGround() && !player.isInWater()) {
            // See the class doc: a fallback drop onto an airborne owner can kill the pet, so the pet
            // stays put — the vanilla outcome, and never worse than it. Logged because "sometimes my
            // wolf follows and sometimes it doesn't" is otherwise unexplainable from the outside.
            McMMOMod.LOGGER.debug(
                    "Pet follow: no landing spot for {} near airborne owner {}; left behind.",
                    pet.getType().toString(), player.getName().getString());
            return false;
        }

        final Vec3 destination = player.position();
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        final boolean placed = pet.teleportTo(level, destination.x, destination.y, destination.z,
                EnumSet.noneOf(RelativeMovement.class), pet.getYRot(), pet.getXRot());
        if (!placed) {
            McMMOMod.LOGGER.warn("Pet follow: could not place {} on owner {} at {}; left behind.",
                    pet.getType().toString(), player.getName().getString(), destination);
        }
        return placed;
    }

    private static boolean isEnabled() {
        final GeneralConfig config = McMMOMod.getGeneralConfig();
        return config == null ? DEFAULT_ENABLED : config.arePetsFollowingTeleports();
    }

    private static double radius() {
        final GeneralConfig config = McMMOMod.getGeneralConfig();
        return config == null ? DEFAULT_RADIUS : config.getPetFollowTeleportRadius();
    }
}
