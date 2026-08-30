package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.AlchemyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Alchemy recipe-recognition + Catalysis brew-speed hooks (see
 * docs/superpowers/specs/2026-08-30-alchemy-listener-design.md). mcMMO's brewing tree includes
 * recipes vanilla does not know, and Catalysis has to shorten a running brew's timer — while
 * vanilla still owns the fuel, the brew timer, the progress bar, and the particles.
 *
 * <p>Two of the three injections below are the recipe-recognition/Catalysis hooks described in the
 * spec; both are into {@code static} vanilla methods (hence the {@code static} handlers):
 * <ul>
 *   <li>{@code isBrewable} (HEAD, cancellable) — forces the return value to {@code true} when the
 *       stand holds a recognised mcMMO brew ({@link AlchemyListener#isValidBrew}). This is what
 *       lets a custom (non-vanilla) recipe start and keep brewing; vanilla-valid recipes are
 *       unaffected (they still return true on their own). Direct analogue of the Fabric original's
 *       {@code canCraft} injector.</li>
 *   <li>{@code serverTick} (HEAD) — the Catalysis brew-speed hook:
 *       {@link AlchemyListener#applyCatalysis} burns extra ticks off the stand's brew timer
 *       <i>before</i> vanilla's own decrement, which is what replaces the legacy
 *       {@code AlchemyBrewTask}'s hand-rolled brew loop. Direct analogue of the Fabric original's
 *       {@code tick} injector.</li>
 * </ul>
 *
 * <p>The craft/XP seam (Fabric's {@code craft} injector) is <b>not</b> ported here — NeoForge's
 * {@code PotionBrewEvent.Pre}, fired from {@code doBrew}'s own head, replaces it outright with a
 * plain event listener (see {@link AlchemyListener#onPotionBrewPre}). See the spec doc's
 * "genuine simplification" section for the full rationale.
 *
 * <p>A third injector, {@code doBrew} (HEAD), <i>is</i> still needed here despite that
 * simplification: {@code PotionBrewEvent} — confirmed via {@code javap} and by reading its bundled
 * source — carries no {@code BlockPos}/{@code Level} at all (unlike the Fabric mixin's
 * {@code craft(World, BlockPos, ...)} parameters), so this stashes {@code doBrew}'s own
 * {@code BlockPos} into {@link AlchemyListener#rememberBrewPosition} before
 * {@code EventHooks.onPotionAttemptBrew} (and so {@link AlchemyListener#onPotionBrewPre}) fires —
 * see {@code AlchemyListener}'s {@code BREW_POSITION} field javadoc for the full rationale.
 *
 * <p>Signatures re-verified via {@code javap} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}: all three methods keep their
 * Fabric-era shape (just retyped/renamed — {@code DefaultedList} to {@code NonNullList},
 * {@code BrewingRecipeRegistry} to {@code PotionBrewing}), and all three injectors need only
 * {@code allow = 1} (there is exactly one {@code isBrewable}/{@code serverTick}/{@code doBrew} on
 * this class).
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandTickMixin {

    @Inject(method = "isBrewable", allow = 1, at = @At("HEAD"), cancellable = true)
    private static void mcmmo$forceMcMMOBrewRecognition(PotionBrewing potionBrewing,
            NonNullList<ItemStack> slots, CallbackInfoReturnable<Boolean> cir) {
        if (AlchemyListener.isValidBrew(slots)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "serverTick", allow = 1, at = @At("HEAD"))
    private static void mcmmo$applyCatalysisBrewSpeed(Level level, BlockPos pos, BlockState state,
            BrewingStandBlockEntity blockEntity, CallbackInfo ci) {
        AlchemyListener.applyCatalysis(pos, blockEntity);
    }

    @Inject(method = "doBrew", allow = 1, at = @At("HEAD"))
    private static void mcmmo$rememberBrewPosition(Level level, BlockPos pos,
            NonNullList<ItemStack> slots, CallbackInfo ci) {
        AlchemyListener.rememberBrewPosition(pos);
    }
}
