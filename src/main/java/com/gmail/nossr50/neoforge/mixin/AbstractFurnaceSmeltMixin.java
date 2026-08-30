package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.SmeltingListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The K7/Cooking furnace-tick hooks. Vanilla fires no furnace events at all, so Smelting XP and
 * Second Smelt are injected into the static {@code AbstractFurnaceBlockEntity#serverTick}, routed
 * through {@link SmeltingListener}, which resolves the furnace's owner. Ports the Fabric
 * original's {@code AbstractFurnaceSmeltMixin} — see
 * docs/superpowers/specs/2026-08-30-cooking-smelting-listener-design.md for the full Mojang-
 * mapping verification and design rationale.
 *
 * <ul>
 *   <li><b>Smelting/Cooking XP</b> — at the {@code burn} call. That call is only reached when a
 *       cook finishes, so it is the faithful analogue of the legacy {@code FurnaceSmeltEvent}. The
 *       injection point is the invoke itself (default shift = before), so the input slot
 *       ({@code SLOT_INPUT}) still holds the item being smelted — {@code burn} is what decrements
 *       it.</li>
 *   <li><b>Second Smelt / Master Chef</b> — at the {@code setRecipeUsed} call immediately after
 *       it, reached <i>only</i> on the branch where {@code burn} returned {@code true}
 *       (bytecode-verified: {@code invokestatic burn} → {@code ifeq skip} →
 *       {@code invokevirtual setRecipeUsed}), which makes it a free "the smelt succeeded" marker.
 *       By then the result has been merged into the output slot ({@code SLOT_RESULT}) — exactly
 *       what the bonus item has to be added to.</li>
 * </ul>
 *
 * <p><b>Fuel Efficiency is deliberately NOT a third injector here.</b> The design doc's plan was a
 * plain {@code FurnaceFuelBurnTimeEvent} listener needing no mixin at all; investigating that
 * event's own bundled source during this task found it carries no {@code BlockPos}/block-entity
 * context, so a mixin bridge is still required — but it targets a different method
 * ({@code getBurnDuration}, an <em>instance</em> method, not part of {@code serverTick}) and lives
 * in its own {@code AbstractFurnaceGetBurnDurationMixin} rather than growing this class to three
 * injectors. See {@link SmeltingListener}'s class javadoc for the full rationale. This class stays
 * at exactly the two injectors below, leaving room for Task B's {@code createExperience}
 * (Understanding the Art) injector to land as this file's third.
 *
 * <p>Every injector carries {@code allow = 1}: each of these targets appears exactly once in its
 * target method today, and a silent second bind would double-apply the bonus rather than fail
 * loudly. {@code defaultRequire = 1} alone does not catch that — {@code require} is a minimum.
 *
 * <p>{@code serverTick}'s world parameter is {@link Level} in this version (re-verified via
 * {@code javap} against {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar} — the Fabric
 * original's own caution about this parameter's type varying across versions carries over), so
 * both handlers narrow it explicitly to {@link ServerLevel} rather than assuming a side. That
 * narrowing is also the client guard (a client-side world simply fails the {@code instanceof} and
 * the handler returns).
 *
 * <p>{@code SLOT_INPUT}/{@code SLOT_RESULT} are {@code protected static final int} fields on
 * {@link AbstractFurnaceBlockEntity} — verified via {@code javap} — but {@code protected static}
 * is not visible to a same-name-package-only class that does not itself extend the target (Mixin
 * classes are plain, independently-compiled Java sources at javac time; the target relationship is
 * woven in later), confirmed by a real compile attempt during implementation
 * ({@code "SLOT_INPUT has protected access in AbstractFurnaceBlockEntity"}). The raw literals
 * {@code 0}/{@code 2} are used instead, exactly as the Fabric original's own mixin did with its
 * {@code INPUT_SLOT_INDEX}/{@code OUTPUT_SLOT_INDEX} comments.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceSmeltMixin {

    @Inject(
            method = "serverTick",
            allow = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;"
                            + "burn(Lnet/minecraft/core/RegistryAccess;"
                            + "Lnet/minecraft/world/item/crafting/RecipeHolder;"
                            + "Lnet/minecraft/core/NonNullList;I"
                            + "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;)Z"))
    private static void mcmmo$onFurnaceSmelt(Level level, BlockPos pos, BlockState state,
            AbstractFurnaceBlockEntity blockEntity, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final ItemStack input = blockEntity.getItem(0); // SLOT_INPUT
        SmeltingListener.onFurnaceSmelt(serverLevel, pos, input);
    }

    @Inject(
            method = "serverTick",
            allow = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;"
                            + "setRecipeUsed(Lnet/minecraft/world/item/crafting/RecipeHolder;)V"))
    private static void mcmmo$onSmeltComplete(Level level, BlockPos pos, BlockState state,
            AbstractFurnaceBlockEntity blockEntity, CallbackInfo ci) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        final ItemStack output = blockEntity.getItem(2); // SLOT_RESULT
        SmeltingListener.onSmeltComplete(pos, output);
    }
}
