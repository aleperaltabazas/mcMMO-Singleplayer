package com.gmail.nossr50.neoforge.mixin;

import com.mojang.datafixers.util.Pair;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to {@code HoeItem#TILLABLES}, so mcMMO can tell a right-click that <i>tills</i>
 * from a right-click that merely readies the hoe (GitHub #1).
 *
 * <p>Tilling is a right-click with a hoe, and so is readying the hoe for Green Terra — the same
 * gesture on the same tool. Without a way to distinguish them, farming a row re-readied the tool on
 * every single till: a "you ready your hoe" message and sound every few seconds, and a hoe left
 * permanently armed, so the next left-click on any crop burned Green Terra's 240-second cooldown by
 * accident. Vanilla's own table is the only honest answer to "would this click have tilled?".
 *
 * <h2>Why the whole map and not {@code canTillFarmland}</h2>
 * {@code HoeItem#useOn} is public, but the question is answered by consulting the whole table: it
 * looks the block up in this map, returns {@code PASS} when the entry is missing, and otherwise runs
 * {@code pair.getFirst().test(context)} — so asking the map for the block's own pair and testing its
 * own predicate is exactly what vanilla does, and it stays correct when Mojang adds a tillable block
 * or changes a condition.
 *
 * <p><b>PORT (field name):</b> the yarn field this ported from was {@code TILLING_ACTIONS};
 * official mappings for 1.21.1 name the same field {@code TILLABLES} (verified via {@code javap} —
 * same declared type, same owning class, same usage shape in {@code HoeItem#useOn}'s bytecode).
 *
 * <p>The field is {@code protected static} with no getter, so an {@code @Accessor} is the only way
 * in — the same reasoning as {@code BrewingStandBrewTimeAccessor}, and a static field means a
 * {@code @Shadow} on an injecting mixin would not be reachable either.
 */
@Mixin(HoeItem.class)
public interface HoeTillingActionsAccessor {

    /**
     * Vanilla's block → (can-till predicate, till action) table.
     *
     * @return the live map; treat it as read-only
     */
    @Accessor("TILLABLES")
    static Map<Block, Pair<Predicate<UseOnContext>, Consumer<UseOnContext>>> getTillingActions() {
        throw new AssertionError("mixin did not apply");
    }
}
