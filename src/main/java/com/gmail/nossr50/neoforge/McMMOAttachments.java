package com.gmail.nossr50.neoforge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Task 3 STAND-IN for {@code com.gmail.nossr50.fabric.McMMOAttachments}. The Fabric original
 * persisted {@code MOB_ORIGIN} (and {@code BRED_BY}, not needed by {@code platform/}) into entity
 * NBT via {@code net.fabricmc.fabric.api.attachment.v1.AttachmentType}, a Fabric-API capability
 * with no NeoForge equivalent wired yet. {@code fabric/McMMOAttachments} itself cannot compile
 * under the NeoForge toolchain (no Fabric API on the classpath), so it stays excluded from
 * {@code sourceSets} pending its own deletion task.
 *
 * <p><b>What this class is NOT:</b> a persistence mechanism. It is an in-memory, UUID-keyed side
 * table -- exactly {@link com.gmail.nossr50.platform.MetadataStore}'s shape -- that keeps
 * {@link com.gmail.nossr50.platform.MobOrigins} compiling and functionally correct
 * <em>within a single server run</em>. A mob's origin marker does NOT currently survive a
 * chunk unload/reload or a world save/restart the way the Fabric original's NBT-backed attachment
 * did. Wiring real persistence -- NeoForge's {@code IAttachmentHolder}/{@code AttachmentType}, or
 * writing into entity NBT directly -- is out of scope for Task 3 (mapping-only translation of
 * {@code platform/}) and is flagged in the Task 3 report for a later task to pick up.
 */
public final class McMMOAttachments {

    private static final Map<UUID, String> MOB_ORIGIN = new ConcurrentHashMap<>();

    private McMMOAttachments() {
    }

    /** Stand-in for {@code entity.getAttached(McMMOAttachments.MOB_ORIGIN)}. */
    public static @Nullable String getMobOrigin(@org.jetbrains.annotations.NotNull Entity entity) {
        return MOB_ORIGIN.get(entity.getUUID());
    }

    /** Stand-in for {@code entity.setAttached(McMMOAttachments.MOB_ORIGIN, value)}. */
    public static void setMobOrigin(@org.jetbrains.annotations.NotNull Entity entity,
            @org.jetbrains.annotations.NotNull String value) {
        MOB_ORIGIN.put(entity.getUUID(), value);
    }
}
