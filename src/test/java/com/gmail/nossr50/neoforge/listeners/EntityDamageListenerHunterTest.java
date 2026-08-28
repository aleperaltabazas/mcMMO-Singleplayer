package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.hunter.HunterManager;
import com.gmail.nossr50.skills.stealth.StealthManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the single most fragile ordering invariant in {@link EntityDamageListener#onModifyAppliedDamage}:
 * Stealth's Assassin must multiply the running melee total <em>before</em> Hunter Mob Mastery's flat
 * bonus is added on top — not the other way around. Assassin multiplies the whole running total; a
 * Hunter bonus added first would be silently multiplied by the backstab multiplier too, which is not
 * what "+3.0 damage against zombies" promises the player.
 *
 * <p>Re-creates {@code fabric.listeners.EntityDamageListenerHunterTest
 * #theMasteryBonusIsAddedAfterAssassinMultiplies}, deleted with the rest of {@code fabric/}'s tests in
 * Task 8 and never re-created on this branch — see the design spec's "Ordering invariants" section.
 *
 * <h2>The arithmetic that makes this test able to fail on a swap</h2>
 * Base damage 10.0F, Assassin multiplier 2.0x (a real achievable value off
 * {@link StealthManager#getAssassinDamageMultiplier()}'s {@code 1 + bonus} shape), Hunter Mastery flat
 * bonus 3.0F (a real achievable value off {@link HunterManager#masteryDamageBonusForHit}'s tier-3
 * {@code MASTERY_DAMAGE_BONUS} entry). The two candidate expressions diverge for these numbers:
 * <ul>
 *   <li>Correct order (Assassin then Hunter): {@code (10.0 * 2.0) + 3.0 = 23.0}</li>
 *   <li>Swapped order (Hunter then Assassin): {@code (10.0 + 3.0) * 2.0 = 26.0}</li>
 * </ul>
 * 23.0 != 26.0, so this test genuinely distinguishes the two call orders rather than merely exercising
 * both arms.
 */
class EntityDamageListenerHunterTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        McMMOMod.setGeneralConfig(null);
    }

    private static ServerPlayer attackingPlayer(UUID uuid) {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(uuid);
        // A vanilla tool (not a sword/axe/mace/trident/spear, and not an empty hand either -- with
        // the shipped Unarmed_Items_As_Unarmed default an empty hand classifies as UNARMED, dragging
        // in the whole weapon-bonus arm's mmoPlayer plumbing) so classifyMainHand resolves OTHER and
        // both applyAttackerWeaponBonus and applySprintSmash no-op, leaving only Assassin and Hunter
        // Mastery to touch the running total.
        when(handle.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_PICKAXE));
        when(handle.isShiftKeyDown()).thenReturn(true); // sneaking -- required for Assassin to fire.
        return handle;
    }

    private static McMMOPlayer trackedMmoPlayer(ServerPlayer handle, StealthManager stealth,
            HunterManager hunter) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getStealthManager()).thenReturn(stealth);
        when(mmoPlayer.getHunterManager()).thenReturn(hunter);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    @Test
    void theMasteryBonusIsAddedAfterAssassinMultiplies(@TempDir Path dir) throws Exception {
        // Default config.yml: every skill's Enabled_For_PVE/PVP switches ship true, which is all
        // applyHunterMastery's canCombatSkillsTrigger(HUNTER, target) gate needs here -- the target
        // below is neither a Player nor an OwnableEntity, so it reads the PVE switch.
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));

        final ServerPlayer attacker = attackingPlayer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);
        when(source.getDirectEntity()).thenReturn(attacker); // a direct melee swing.

        final LivingEntity target = mock(LivingEntity.class);
        doReturn(EntityType.ZOMBIE).when(target).getType();

        final StealthManager stealth = mock(StealthManager.class);
        // ticksSinceDamageTaken(attacker) reads Long.MAX_VALUE for a player never recorded as hit --
        // exactly the "has not been hit recently" state Assassin's window wants.
        when(stealth.assassinReady(true, Long.MAX_VALUE)).thenReturn(true);
        when(stealth.getAssassinDamageMultiplier()).thenReturn(2.0);

        final HunterManager hunter = mock(HunterManager.class);
        when(hunter.masteryDamageBonusForHit(anyString(), eq(true))).thenReturn(3.0);

        trackedMmoPlayer(attacker, stealth, hunter);

        final float result = EntityDamageListener.onModifyAppliedDamage(target, source, 10.0F);

        // (10.0 * 2.0) + 3.0 = 23.0, NOT (10.0 + 3.0) * 2.0 = 26.0.
        assertEquals(23.0F, result, 0.001F);
    }
}
