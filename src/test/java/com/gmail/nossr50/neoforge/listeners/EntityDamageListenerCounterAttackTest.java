package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.swords.SwordsManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link EntityDamageListener#maybeProcessCounterAttack}'s previously-fixed role-inversion bug:
 * the {@code canCombatSkillsTrigger} gate must be evaluated against the <em>assailant</em> (the
 * entity being counter-attacked), not the defending player. A revert back to passing the defending
 * player would make {@code Enabled_For_PVP} — a switch that has nothing to do with the mob being
 * countered — silently decide whether a PvE counter-attack fires at all, exactly the bug the Fabric
 * original's {@code maybeProcessCounterAttack} javadoc documents.
 *
 * <p>The assailant here is a plain {@link LivingEntity} mock (not a {@code Player}, not an {@code
 * OwnableEntity}), so {@code CombatUtils#canCombatSkillsTrigger} resolves it via the <b>PVE</b>
 * switch. {@code Skills.Swords.Enabled_For_PVE} is set {@code true} and
 * {@code Skills.Swords.Enabled_For_PVP} is set {@code false} — the one combination that only passes
 * the gate when it is checked against the assailant. If the gate were (re)checked against the
 * defending {@code ServerPlayer} instead, {@code isPlayerOrTamed} would resolve {@code true} and the
 * *PVP* switch (false) would block the roll instead.
 *
 * <p>Follows {@link PlayerSessionListenerTest}'s conventions: {@link McTestRegistries#bootstrap()}
 * once in {@code @BeforeAll}, Mockito for the {@link ServerPlayer}/{@link LivingEntity} handles, and
 * an {@code @AfterEach} that undoes the {@link UserManager} tracking and {@link McMMOMod} config
 * statics this test touches.
 *
 * <p>{@link EntityDamageListener#maybeProcessCounterAttack} is package-private specifically so this
 * test can drive it directly, the same "package-private for testing" convention
 * {@link EntityDamageListener#onAllowDamage} already uses in this file — proving the fix survived
 * the port needs the real gate call, not a re-implementation of it.
 */
class EntityDamageListenerCounterAttackTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e5");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        McMMOMod.setGeneralConfig(null);
    }

    /**
     * Swords enabled for PVE, disabled for PVP. The assailant below is neither a player nor a tamed
     * entity, so the *correct* gate call (against the assailant) reads the PVE switch and passes; a
     * gate call against the defending player would read the PVP switch and block.
     */
    private static void bindConfigGatedOnAssailant(Path dir) throws Exception {
        final GeneralConfig config = new GeneralConfig(dir);
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, Files.readString(file)
                .replace("""
                        Swords:
                            Enabled_For_PVP: true
                            Enabled_For_PVE: true""",
                        """
                        Swords:
                            Enabled_For_PVP: false
                            Enabled_For_PVE: true"""));
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
    }

    private static ServerPlayer defendingPlayer(UUID uuid) {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(uuid);
        when(handle.getMainHandItem()).thenReturn(new ItemStack(Items.IRON_SWORD));
        return handle;
    }

    private static McMMOPlayer trackedMmoPlayer(ServerPlayer handle, SwordsManager swords) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getSwordsManager()).thenReturn(swords);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    @Test
    void counterAttackGateIsCheckedAgainstTheAssailantNotTheDefendingPlayer(@TempDir Path dir)
            throws Exception {
        bindConfigGatedOnAssailant(dir);
        final ServerPlayer defender = defendingPlayer(PLAYER_ID);
        // A non-player, non-tamed assailant: canCombatSkillsTrigger must fall to the PVE switch for
        // this entity, and only for this entity -- not for the ServerPlayer being hit.
        final LivingEntity assailant = mock(LivingEntity.class);
        final DamageSource source = mock(DamageSource.class);
        when(source.getDirectEntity()).thenReturn(assailant);

        final SwordsManager swords = mock(SwordsManager.class);
        trackedMmoPlayer(defender, swords);

        assertDoesNotThrow(() ->
                EntityDamageListener.maybeProcessCounterAttack(defender, source, 4.0F));

        // Mutation check: if the gate were ever checked against `defender` instead of `assailant`,
        // canCombatSkillsTrigger would resolve the PVP switch (false) and block before ever reaching
        // the SwordsManager -- canUseCounterAttack() would never be invoked.
        verify(swords).canUseCounterAttack();
    }

    @Test
    void counterAttackGateBlocksWhenTheAssailantsOwnSwitchIsOff(@TempDir Path dir) throws Exception {
        // The mirror case: PVE off, PVP on. The assailant is still non-player/non-tamed, so the
        // correct (assailant-gated) call must now BLOCK -- proving this isn't just "the gate always
        // passes", but genuinely reads the assailant's applicable switch either way.
        final GeneralConfig config = new GeneralConfig(dir);
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, Files.readString(file)
                .replace("""
                        Swords:
                            Enabled_For_PVP: true
                            Enabled_For_PVE: true""",
                        """
                        Swords:
                            Enabled_For_PVP: true
                            Enabled_For_PVE: false"""));
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));

        final ServerPlayer defender = defendingPlayer(PLAYER_ID);
        final LivingEntity assailant = mock(LivingEntity.class);
        final DamageSource source = mock(DamageSource.class);
        when(source.getDirectEntity()).thenReturn(assailant);

        final SwordsManager swords = mock(SwordsManager.class);
        trackedMmoPlayer(defender, swords);

        assertDoesNotThrow(() ->
                EntityDamageListener.maybeProcessCounterAttack(defender, source, 4.0F));

        // If the gate were (mis)checked against `defender` (a Player), isPlayerOrTamed would be true
        // and the PVP switch (true, in this test) would let it through regardless of the assailant's
        // own PVE switch being off -- exactly the role-inversion bug this pins against.
        verify(swords, never()).canUseCounterAttack();
    }
}
