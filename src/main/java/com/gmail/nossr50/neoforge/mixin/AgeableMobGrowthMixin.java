package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import net.minecraft.world.entity.AgeableMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Husbandry's growth hooks — the <b>raise</b> verb, the <b>feed</b> verb, and
 * {@code Accelerated Growth}'s double-feed roll. Mirrors the Fabric original's
 * {@code PassiveEntityGrowthMixin}, retargeted onto the Mojang-mapped 1.21.1 names.
 *
 * <h2>⚠️ Why {@code setAge(int)} and not {@code ageBoundaryReached()}</h2>
 * The design spec (§3) flagged this seam as needing independent re-verification, not transcription
 * — re-derived here directly from bytecode, not taken on the spec's word:
 * <ul>
 *   <li>{@code javap -p -c} on {@code net/minecraft/world/entity/animal/goat/Goat.class} shows
 *       {@code protected void ageBoundaryReached()} setting an attack-damage attribute and calling
 *       {@code removeHorns()}/{@code addHorns()} — <b>no {@code invokespecial} call to
 *       {@code super.ageBoundaryReached()} anywhere in the method body.</b></li>
 *   <li>{@code javap -p -c} on {@code net/minecraft/world/entity/monster/hoglin/Hoglin.class}
 *       shows the same shape for its own {@code ageBoundaryReached()} override — sets
 *       {@code xpReward} and an attack-damage attribute, again with no {@code super} call.</li>
 *   <li>By contrast, {@code Villager#ageBoundaryReached()} (control case) opens with
 *       {@code invokespecial AbstractVillager.ageBoundaryReached:()V} before its own body runs.</li>
 * </ul>
 * A mixin on {@code ageBoundaryReached} would therefore pay <b>zero</b> raise XP for goats and
 * hoglins — priced at 400 and 900 in {@code experience.yml} — for the identical reason the Fabric
 * port rejected {@code onGrowUp()} on {@code GoatEntity}/{@code HoglinEntity}. {@code setAge(int)}
 * is declared only on {@code AgeableMob}, overridden by nothing (confirmed: no subclass in this
 * jar declares its own {@code setAge}), and {@code javap -c} shows it is where the
 * {@code ageBoundaryReached()} call itself lives (bytecode: {@code putfield age} then, guarded on
 * the baby↔adult crossing, {@code invokevirtual ageBoundaryReached}) — so every path, including
 * both Goat's and Hoglin's broken overrides, arrives here regardless.
 *
 * <h2>Why {@code ageUp} carries the feed verb</h2>
 * {@code javap -c} on {@code AgeableMob#ageUp(int, boolean)} shows it computes a new age purely
 * from its own {@code int} parameter and then calls {@code setAge(int)} — the same shared-funnel
 * shape the Fabric doc found for {@code growUp}. Feeding is spread across several species-specific
 * paths ({@code Animal#mobInteract}, {@code Dolphin#mobInteract}, {@code Panda#mobInteract}, and
 * {@code receiveFood} on horse/camel/llama) whose only shared callee is {@code ageUp}; hooking it
 * is what covers all of them from one injection. But {@code ageUp} is a growth funnel, not a
 * feeding one — {@code Sheep} calls it while eating grass, and a baby ages itself through it too —
 * so it is gated on {@link HusbandryListener}'s interaction stash: growth only counts as a feed
 * when a player is mid-interaction with this very animal (see
 * {@code HusbandryListener#onGrowthApplied} and {@code PlayerInteractionStashMixin}).
 */
@Mixin(AgeableMob.class)
public abstract class AgeableMobGrowthMixin {

    /**
     * Read at the head of {@code setAge} to recover the age being replaced. Shadowed rather than
     * read back through {@code getAge()} so the value is exactly what vanilla is about to
     * overwrite, with no dependence on that getter staying unoverridden.
     */
    @Shadow
    protected int age;

    /**
     * Report every age change; the listener picks out the baby→adult crossing. Runs for babies
     * ageing up and for adults counting down their post-breeding cooldown, so at most a handful of
     * entities per tick — vanilla never calls this once an idle adult's age has settled at zero.
     */
    @Inject(method = "setAge(I)V", allow = 1, at = @At("HEAD"))
    private void mcmmo$onBreedingAgeChange(int newAge, CallbackInfo ci) {
        HusbandryListener.onBreedingAgeChange((AgeableMob) (Object) this, this.age, newAge);
    }

    /**
     * Pay the feed verb, and let {@code Accelerated Growth} double the growth this feed grants.
     *
     * <p>{@code argsOnly} with {@code ordinal = 0} targets the {@code int growthSeconds} parameter
     * itself rather than a call site, which is what covers every feeding path — including the
     * one-argument {@code ageUp(int)} overload, which delegates to this two-argument one — from a
     * single injection.
     */
    @ModifyVariable(method = "ageUp(IZ)V", allow = 1, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int mcmmo$onGrowthApplied(int growthSeconds) {
        return HusbandryListener.onGrowthApplied((AgeableMob) (Object) this, growthSeconds);
    }
}
