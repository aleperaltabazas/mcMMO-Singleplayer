package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.phys.HitResult;
import org.junit.jupiter.api.Test;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * Structural regression test for {@link ThrownEggHatchMixin} — Task D, one of the six mixins covered
 * by the final-review fix wave (Finding 3). Both handlers target the same {@code nextInt(I)} call
 * site inside {@code ThrownEgg#onHit}, distinguished only by {@code ordinal} — the one field that is
 * genuinely load-bearing here (swap the two ordinals and Brood's "does it hatch at all" roll would
 * silently apply to the "does it hatch as four" decision, and vice versa). Everything the class
 * javadoc claims about that ordinal assignment is annotation-visible, so reflection alone -- no ASM --
 * suffices: there is no {@code @Local} forwarding or expression-polarity risk in this mixin the way
 * there is in {@code BeehiveBlockUseItemOnMixin}.
 */
class ThrownEggHatchMixinTest {

    // @Inject/@ModifyExpressionValue's `method` value is the mixin's own full descriptor-qualified
    // constant (ThrownEggHatchMixin.ON_HIT); real reflective lookup needs the bare name instead.
    private static final String ON_HIT = "onHit(Lnet/minecraft/world/phys/HitResult;)V";
    private static final String ON_HIT_BARE_NAME = "onHit";
    private static final String NEXT_INT_TARGET = "Lnet/minecraft/util/RandomSource;nextInt(I)I";

    @Test
    void thrownEggDeclaresTheOnHitMethodTheMixinTargets() throws NoSuchMethodException {
        final Method real = ThrownEgg.class.getDeclaredMethod(ON_HIT_BARE_NAME, HitResult.class);
        assertNotNull(real);
    }

    @Test
    void broodHatchesMoreEggsModifiesTheFirstNextIntCall() throws NoSuchMethodException {
        final Method handler = ThrownEggHatchMixin.class.getDeclaredMethod(
                "mcmmo$broodHatchesMoreEggs", int.class, HitResult.class);
        final ModifyExpressionValue annotation = handler.getAnnotation(ModifyExpressionValue.class);
        assertNotNull(annotation, "mcmmo$broodHatchesMoreEggs must be @ModifyExpressionValue");
        assertEquals(ON_HIT, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(NEXT_INT_TARGET, annotation.at()[0].target());
        assertEquals(0, annotation.at()[0].ordinal(), "must bind the FIRST nextInt(8) call -- "
                + "\"does the egg hatch at all\" -- not the nested nextInt(32) that picks the clutch "
                + "size (ordinal 1)");
    }

    @Test
    void broodHatchesFullClutchesModifiesTheSecondNextIntCall() throws NoSuchMethodException {
        final Method handler = ThrownEggHatchMixin.class.getDeclaredMethod(
                "mcmmo$broodHatchesFullClutches", int.class, HitResult.class);
        final ModifyExpressionValue annotation = handler.getAnnotation(ModifyExpressionValue.class);
        assertNotNull(annotation, "mcmmo$broodHatchesFullClutches must be @ModifyExpressionValue");
        assertEquals(ON_HIT, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(NEXT_INT_TARGET, annotation.at()[0].target());
        assertEquals(1, annotation.at()[0].ordinal(), "must bind the SECOND, nested nextInt(32) call "
                + "-- \"does the hatch become a full clutch\" -- not the outer nextInt(8) that gates "
                + "whether it hatches at all (ordinal 0)");
    }

    @Test
    void theTwoHandlersTargetDistinctOrdinalsOfTheSameCallSite() throws NoSuchMethodException {
        // Belt-and-braces: were both handlers ever pinned to the same ordinal, one of Brood's two
        // rolls would silently never fire (the other would consume both nextInt calls in some
        // interpretations, or the two effects would collapse into one). Each of the two tests above
        // pins its own ordinal already; this test just makes the pairwise distinctness explicit.
        final ModifyExpressionValue first = ThrownEggHatchMixin.class
                .getDeclaredMethod("mcmmo$broodHatchesMoreEggs", int.class, HitResult.class)
                .getAnnotation(ModifyExpressionValue.class);
        final ModifyExpressionValue second = ThrownEggHatchMixin.class
                .getDeclaredMethod("mcmmo$broodHatchesFullClutches", int.class, HitResult.class)
                .getAnnotation(ModifyExpressionValue.class);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                first.at()[0].ordinal(), second.at()[0].ordinal(),
                "the two Brood rolls must not be pinned to the same nextInt ordinal");
    }
}
