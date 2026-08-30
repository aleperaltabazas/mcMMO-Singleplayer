package com.gmail.nossr50.neoforge.mixin;

import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read/write access to {@code BrewingStandBlockEntity#brewTime}, which the Catalysis brew-speed
 * sub-skill has to shorten (see docs/superpowers/specs/2026-08-30-alchemy-listener-design.md).
 *
 * <p>The field is package-private with no getter or setter (confirmed via {@code javap} on the
 * real 21.1.248 merged jar: {@code int brewTime;} declared directly on the class), and vanilla's
 * {@code serverTick} — the only place a brew's timer is touched — is {@code static}, so a
 * {@code @Shadow} on the sibling {@link BrewingStandTickMixin} would not be reachable from its
 * static injection handlers. An accessor mixin sidesteps both problems: Mixin generates the two
 * methods onto the target class, so {@code neoforge/listeners/AlchemyListener} can cast the block
 * entity it is handed and adjust the timer without any of this leaking into the mixin's injection
 * handlers.
 *
 * <p><b>Pure accessor interface — no static/default members here.</b> See
 * {@link LivingEntityDropFromLootTableAccessor}'s own javadoc for why a {@code static} helper on a
 * {@code @Mixin}-annotated interface breaks Sponge Mixin's target-type inference and causes a real
 * boot crash ({@code InvalidMixinException}). This interface stays two abstract {@code @Accessor}
 * declarations and nothing else.
 */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandBrewTimeAccessor {

    /** Ticks left on the current brew, or {@code 0} when no brew is running. */
    @Accessor("brewTime")
    int getBrewTime();

    /** Overwrite the ticks left on the current brew. */
    @Accessor("brewTime")
    void setBrewTime(int brewTime);
}
