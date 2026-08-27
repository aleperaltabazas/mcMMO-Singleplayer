package com.gmail.nossr50.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the Minecraft/NeoForge registry bootstrap (see {@link McTestRegistries} and
 * {@code build.gradle}): confirms the vanilla item registry populates and resolves real id paths
 * inside the test JVM. If this fails, the MC-typed {@code ItemUtils}/{@code BlockUtils} wrapper tests
 * can't run — the bootstrap/classpath setup regressed.
 */
class McRegistryBootstrapProbeTest {

    @BeforeAll
    static void bootstrap() {
        McTestRegistries.bootstrap();
    }

    @Test
    void itemRegistryResolvesVanillaIdPaths() {
        assertEquals("diamond_axe", BuiltInRegistries.ITEM.getKey(Items.DIAMOND_AXE).getPath());
        assertEquals("netherite_pickaxe",
                BuiltInRegistries.ITEM.getKey(Items.NETHERITE_PICKAXE).getPath());
        assertEquals("air", BuiltInRegistries.ITEM.getKey(Items.AIR).getPath());
    }
}
