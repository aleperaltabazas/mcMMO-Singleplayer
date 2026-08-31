package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Husbandry's {@code Selective Breeding} — a foal that beats the dice. Ports the Fabric original's
 * {@code HorseChildAttributesMixin} onto Mojang's 1.21.1 names, re-verified against
 * {@code javap -p -c} on {@code net/minecraft/world/entity/animal/horse/AbstractHorse.class} in
 * this jar rather than transcribed from the spec's claims.
 *
 * <h2>Where vanilla's inheritance actually lives (renamed, not restructured)</h2>
 * {@code javap} confirms {@code AbstractHorseEntity#calculateAttributeBaseValue(double, double,
 * double, double, Random)} survives 1.21.1 as {@code AbstractHorse#createOffspringAttribute(double,
 * double, double, double, RandomSource)} — same package-private {@code static}, same five-argument
 * {@code (parentA, parentB, min, max, random)} shape. It takes the midpoint of the two parents,
 * widens a bell curve by their spread plus 15% of the attribute range, rolls three
 * {@code nextDouble()}s to shape it (confirmed: three {@code invokeinterface
 * RandomSource.nextDouble()} call sites in the bytecode), and reflects the result back inside
 * {@code [min, max]} if it lands outside.
 *
 * <p>The bias is applied to that method's <b>return value</b> rather than to the dice that produced
 * it, exactly as Fabric's version did — one hook, trivially monotonic, and it keeps the property
 * that matters: good parents still give better foals, because the bias moves you along the range
 * rather than replacing it.
 *
 * <h2>⚠️ One structural difference from Fabric, not a mixin-plan change</h2>
 * {@code setChildAttributes(PassiveEntity, AbstractHorseEntity)} is renamed
 * {@code setOffspringAttributes(AgeableMob, AbstractHorse)} — same HEAD/RETURN stash shape — but
 * unlike Fabric's version it does <b>not</b> call {@code createOffspringAttribute} directly: it
 * calls a new private instance helper, {@code setOffspringAttribute(AgeableMob, AbstractHorse,
 * Holder&lt;Attribute&gt;, double, double)}, three times (once per inherited attribute), and that
 * helper is what calls {@code createOffspringAttribute}. This extra layer of indirection does not
 * change where either injector attaches — the stash still opens/closes around
 * {@code setOffspringAttributes}, the bias still applies inside {@code createOffspringAttribute}'s
 * {@code @ModifyReturnValue} — it is only visible to someone reading the decompiled source, not to
 * this mixin.
 *
 * <h2>⚠️ {@code allow = 3}, verified by {@code javap -c}, not copied from the spec</h2>
 * {@code javap -p -c} on {@code createOffspringAttribute} shows exactly three {@code dreturn}
 * instructions — the in-range result plus the two branches that reflect an out-of-range roll back
 * inside {@code [min, max]} — all three the same logical answer, so all three must be biased. An
 * {@code allow = 1} copied from the injectors below fails Mixin's own load check ("3 succeeded of 1
 * allowed"); silently downgrading to a narrower mixin that only fires once (e.g. catching that
 * exception) would be worse than either extreme — it would let extreme rolls go unbiased with no
 * error at all, rather than failing loudly the way an unmet {@code allow} does.
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseChildAttributesMixin {

    private static final String SET_OFFSPRING_ATTRIBUTES =
            "setOffspringAttributes(Lnet/minecraft/world/entity/AgeableMob;"
                    + "Lnet/minecraft/world/entity/animal/horse/AbstractHorse;)V";

    /**
     * Open the stash: the horse whose {@code setOffspringAttributes} is running knows who bred it.
     *
     * <p>{@code this} is one of the two parents, which is all that is needed — {@code getLoveCause}
     * is set on whichever animal the player fed, and vanilla only reaches breeding when at least one
     * parent has one.
     */
    @Inject(method = SET_OFFSPRING_ATTRIBUTES, allow = 1, at = @At("HEAD"))
    private void mcmmo$beginSelectiveBreeding(AgeableMob child, AbstractHorse mate,
            CallbackInfo ci) {
        HusbandryListener.beginSelectiveBreeding((AbstractHorse) (Object) this, mate);
    }

    /** Close the stash on every exit, so it cannot outlive the breeding that opened it. */
    @Inject(method = SET_OFFSPRING_ATTRIBUTES, allow = 1, at = @At("RETURN"))
    private void mcmmo$endSelectiveBreeding(AgeableMob child, AbstractHorse mate, CallbackInfo ci) {
        HusbandryListener.endSelectiveBreeding();
    }

    /**
     * Nudge one rolled stat toward the best the species allows.
     *
     * <p>{@code min} and {@code max} arrive as target parameters, which is what lets the bias be
     * expressed as "a fraction of the gap remaining" instead of a flat addition — a flat bonus would
     * be enormous on jump strength and invisible on health, since the three attributes this covers
     * span completely different ranges.
     *
     * <p>See this class's own javadoc for why {@code allow = 3} — not {@code 1} — is correct here.
     */
    @ModifyReturnValue(method = "createOffspringAttribute(DDDDLnet/minecraft/util/RandomSource;)D",
            allow = 3, at = @At("RETURN"))
    private static double mcmmo$biasChildAttribute(double rolled, double parentA, double parentB,
            double min, double max, RandomSource random) {
        return HusbandryListener.applySelectiveBreedingBias(rolled, min, max);
    }
}
