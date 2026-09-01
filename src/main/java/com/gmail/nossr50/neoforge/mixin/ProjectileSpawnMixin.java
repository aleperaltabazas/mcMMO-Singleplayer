package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.ProjectileListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The projectile-launch hook: mcMMO's replacement for Bukkit's {@code ProjectileLaunchEvent}, which
 * legacy used to mark arrows for Archery's Arrow Retrieval (see {@code EntityListener
 * #onProjectileLaunch}) and, on this branch, also to stamp the fired-from/bow-force XP marks (see
 * {@code ProjectileListener#onProjectileSpawn}).
 *
 * <p><b>No Mojang-mapped equivalent of the Fabric reference's static spawn funnel exists in
 * 1.21.1.</b> The plan's own Fabric reference ({@code fabric.mixin.ProjectileSpawnMixin}) targets
 * Yarn's four-argument static {@code ProjectileEntity#spawn(ProjectileEntity, ServerWorld,
 * ItemStack, Consumer)}. {@code javap -p} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}'s {@code Projectile} class shows it
 * declares no static member at all beyond a private {@code lerpRotation} helper and a lambda -- that
 * spawn-with-consumer utility does not exist on this mapping/version. Reading the actual
 * decompiled bytecode of every real spawn path instead:
 * <ul>
 *   <li>{@code BowItem#releaseUsing} &rarr; {@code ProjectileWeaponItem#shoot} &rarr; (per arrow)
 *       {@code createProjectile} then {@code ServerLevel.addFreshEntity(Entity): boolean}
 *       (confirmed via {@code javap -c}: the bow/crossbow firing path calls this directly, once per
 *       arrow).</li>
 *   <li>{@code ProjectileDispenseBehavior#execute} (a dispenser firing an arrow) also calls
 *       {@code level.addFreshEntity(projectile)} directly (confirmed by reading its decompiled
 *       source).</li>
 *   <li>{@code AbstractSkeleton}'s own ranged attack (a skeleton firing at a target with no player
 *       anywhere in the call stack) also reaches {@code addFreshEntity} (confirmed by source read).</li>
 * </ul>
 * {@code ServerLevel#addFreshEntity(Entity): boolean} is therefore the real single funnel every one
 * of those paths -- and every other mod's projectile spawn -- delegates through in this version;
 * it is a strictly <em>more</em> general analogue of the old Yarn static (it also catches the
 * skeleton path, which the Fabric reference's own listener narrows away downstream exactly as this
 * one does via the {@code Arrow}/{@code ServerPlayer} owner checks in
 * {@code ProjectileListener#onProjectileSpawn}).
 *
 * <p>Injected at {@code TAIL} rather than {@code HEAD}: {@code addFreshEntity} is a one-line
 * delegation to {@code addEntity(Entity)} (confirmed via {@code javap -c}), so by the time a TAIL
 * injector runs, the entity is both fully constructed and actually present in the level -- the
 * point at which Bukkit's {@code ProjectileLaunchEvent} fired, and the same reasoning the Fabric
 * reference gave for its own {@code TAIL} placement.
 *
 * <p><b>Single, unambiguous call site.</b> {@code addFreshEntity(Entity)} has no overload sharing
 * its name+arity on {@code ServerLevel} (only the unrelated {@code
 * tryAddFreshEntityWithPassengers(Entity)} is nearby), so this named-method {@code TAIL} injector
 * needs neither {@code allow} nor {@code require} beyond the {@code mcmmo.mixins.json} default of
 * {@code defaultRequire = 1} -- it matches exactly once, matching {@code
 * AbstractHorseChildAttributesMixin}/{@code ShearsItemInteractMixin}'s own documented convention of
 * spelling this out rather than leaving it implicit.
 */
@Mixin(ServerLevel.class)
public abstract class ProjectileSpawnMixin {

    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("TAIL"))
    private void mcmmo$onAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Projectile projectile) {
            ProjectileListener.onProjectileSpawn(projectile, (ServerLevel) (Object) this);
        }
    }
}
