package com.gmail.nossr50.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Common (client + server) entry point for the mcMMO NeoForge mod. Replaces
 * {@code com.gmail.nossr50.fabric.McMMOMod}; subsystem wiring (config, persistence,
 * listeners, commands) is added in later tasks.
 */
@Mod("mcmmo")
public final class McMMOMod {

    public McMMOMod(IEventBus modEventBus, ModContainer modContainer) {
        // Subsystem registration lands here in later tasks.
    }
}
