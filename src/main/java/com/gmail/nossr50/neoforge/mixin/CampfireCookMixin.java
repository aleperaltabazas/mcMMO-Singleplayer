package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.CookingListener;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The Cooking campfire hook — Cooking XP and Master Chef for a cook that finishes on a lit campfire
 * or soul campfire. Ports the Fabric original's {@code CampfireCookMixin}, retargeted to Mojang's
 * {@code cookTick}/{@code Containers.dropItemStack}.
 *
 * <p>It needs its own mixin because {@code CampfireBlockEntity} is not an
 * {@code AbstractFurnaceBlockEntity} (verified via {@code javap} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}: it extends {@code BlockEntity}), so
 * {@code AbstractFurnaceSmeltMixin} does not reach it. Both campfire variants share this one block
 * entity and one {@code CampfireBlock}, so both are covered by this single injector.
 *
 * <h2>The seam ({@code javap -c -p} on {@code CampfireBlockEntity})</h2>
 * {@code cookTick} (public static) walks the four cooking slots, and for a slot whose cooking
 * progress has reached its cooking time it does, in order:
 * <pre>
 *   SingleRecipeInput cooked = new SingleRecipeInput(rawStack);
 *   ItemStack result = quickCheck.getRecipeFor(cooked, level).map(craft).orElse(rawStack);
 *   if (result.isItemEnabled(level.enabledFeatures())) {
 *       Containers.dropItemStack(level, x, y, z, result);   // &lt;-- the injection point
 *       items.set(i, ItemStack.EMPTY);
 *       ...
 *   }
 * </pre>
 * {@code Containers.dropItemStack(Level, double, double, double, ItemStack)} is reached only when a
 * cook has actually finished, which makes it the campfire's analogue of the furnace's {@code burn}
 * invoke. It occurs exactly once in the method (confirmed by bytecode read: a single
 * {@code invokestatic Containers.dropItemStack}), so {@code allow = 1} pins it — a silent second
 * bind would pay twice.
 *
 * <p>A campfire has no output slot. The cooked item is thrown on the floor, which is why this is a
 * {@link ModifyArg} on the scattered stack (index 4, the last of the five arguments) rather than the
 * furnace's split "XP before / bonus after" pair of injectors: there is one moment, and it carries
 * both the raw input and the finished result at once.
 *
 * <h2>Every captured local is disambiguated by TYPE, never by a numeric LVT index</h2>
 * The two things this needs from the method body are the raw input and the finished result, and only
 * the input needs a capture — the result is the argument being modified. Capturing the input by a
 * numeric local-variable-table index would compile, bind, and boot cleanly with the wrong local, and
 * a swap would be invisible: the XP would be looked up under the wrong config key and the whole
 * feature would silently pay nothing. Instead, {@code cooked} is captured by implicit
 * {@code @Local} typing as the sole {@link SingleRecipeInput} local in {@code cookTick} (verified via
 * {@code javap}: it is the only {@code new SingleRecipeInput} in the method) — MixinExtras' implicit
 * mode requires exactly one match and fails loudly at apply time otherwise, so a future refactor that
 * introduces a second one breaks the build instead of breaking the skill.
 *
 * <p>The two {@code argsOnly} captures are likewise the only {@link Level} and {@link BlockPos}
 * among {@code cookTick}'s parameters. ({@code @ModifyArg} handlers do not receive the target's own
 * arguments, unlike {@code @Inject} — hence the sugar.)
 *
 * <h2>Capture the world as {@code Level} and narrow it here, never as {@code ServerLevel}</h2>
 * {@code cookTick}'s world parameter is {@link Level}, not {@link ServerLevel} — an {@code argsOnly}
 * capture is matched by type, so asking for {@code ServerLevel} where the parameter is {@code Level}
 * matches no argument and MixinExtras reports a scan failure at apply time rather than a compile
 * error. Capturing the broadest type and narrowing in the body is correct on every band and costs
 * one {@code instanceof}.
 */
@Mixin(CampfireBlockEntity.class)
public abstract class CampfireCookMixin {

    /**
     * Award the campfire's owner for a finished cook and let Master Chef add a second helping to the
     * stack about to be scattered.
     *
     * @param result the stack vanilla is about to throw on the floor; returned unchanged unless
     *               Master Chef procs
     * @param world  captured as {@link Level} and narrowed below — see the class note. Also the
     *               client guard: a campfire ticked on a client level pays nothing.
     */
    @ModifyArg(
            method = "cookTick",
            allow = 1,
            index = 4,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/Containers;dropItemStack("
                            + "Lnet/minecraft/world/level/Level;DDD"
                            + "Lnet/minecraft/world/item/ItemStack;)V"))
    private static ItemStack mcmmo$onCampfireCook(ItemStack result,
            @Local(argsOnly = true) Level world,
            @Local(argsOnly = true) BlockPos pos,
            @Local SingleRecipeInput cooked) {
        if (!(world instanceof ServerLevel serverLevel)) {
            return result;
        }
        return CookingListener.onCampfireCook(serverLevel, pos, cooked.item(), result);
    }
}
