package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.AgilityListener;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Agility's air-domain hook — <b>Fleet Footed (air)</b> and <b>Glide</b>. Ports the Fabric
 * original's {@code LivingEntityGlideMixin} onto Mojang's names ({@code LivingEntity#travel} is
 * already a Mojang name, unchanged from Yarn). The maths lives in
 * {@link AgilityListener#modifyGlideVelocity}; this file is only the seam.
 *
 * <h2>The seam: the tail of {@code travel}</h2>
 * The gliding maths is inlined into {@code travel} itself, with no intermediate call to intercept,
 * so the seam is the <b>end of {@code travel}</b>, gated on {@code isFallFlying()}, rewriting the
 * velocity vanilla just stored.
 *
 * <p>🔑 <b>Bytecode-verified that {@code travel(Vec3)} has exactly one {@code return}</b> against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar} (one {@code return} opcode across the
 * whole method body). That is what makes {@code TAIL} reachable for a glider rather than silently
 * dead.
 *
 * <p>⚠️ <b>Single-side injector, and this is not a narrowing from Fabric's dual-side seam.</b>
 * Fabric's original ran on both logical sides because that mod ships separate client and server
 * jars even in singleplayer. This branch's {@code mcmmo.mixins.json} has no client/server split at
 * all — every mixin here targets the one JVM a singleplayer session runs in, where the integrated
 * server and the client share the same loaded {@link LivingEntity} class. One injector firing in
 * that shared process reaches every {@code travel} call the way Fabric's two injectors did
 * together; there is nothing to duplicate.
 *
 * <h2>One tick of latency, and why the steady state still matches</h2>
 * Injecting at the tail modifies the stored velocity <em>after</em> {@code travel}'s own
 * {@code move(...)} consumes it for this tick, so the first gliding tick moves the player by
 * vanilla's figure and the bonus lands from the following tick on. The bonus compounds identically
 * from there, since every subsequent tick's gliding maths starts from the value this injector wrote.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGlideMixin {

    @Inject(method = "travel", allow = 1, at = @At("TAIL"))
    private void mcmmo$applyGlideBonus(Vec3 travelVector, CallbackInfo ci) {
        final LivingEntity self = (LivingEntity) (Object) this;
        if (!self.isFallFlying()) {
            return;
        }
        final Vec3 vanilla = self.getDeltaMovement();
        final Vec3 boosted = AgilityListener.modifyGlideVelocity(self, vanilla);
        // modifyGlideVelocity returns the argument itself when nothing applies — the common case.
        if (boosted != vanilla) {
            self.setDeltaMovement(boosted);
        }
    }
}
