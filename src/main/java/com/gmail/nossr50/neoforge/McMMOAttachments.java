package com.gmail.nossr50.neoforge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
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
 * table -- exactly {@link com.gmail.nossr50.platform.MetadataStore}'s shape, right down to the
 * {@link #clear} naming and the "call it when the entity is removed" contract -- that keeps
 * {@link com.gmail.nossr50.platform.MobOrigins} compiling and functionally correct
 * <em>within a single server run</em>. A mob's origin marker does NOT currently survive a
 * chunk unload/reload or a world save/restart the way the Fabric original's NBT-backed attachment
 * did. Wiring real persistence -- NeoForge's {@code IAttachmentHolder}/{@code AttachmentType}, or
 * writing into entity NBT directly -- is out of scope for Task 3 (mapping-only translation of
 * {@code platform/}) and is flagged in the Task 3 report for a later task to pick up.
 *
 * <p><b>Eviction (fixed 2026-08-26, code review):</b> {@link #clear} mirrors
 * {@link com.gmail.nossr50.platform.MetadataStore#clear}'s shape exactly, for the same reason --
 * without it, {@link #MOB_ORIGIN} grows for the process lifetime as mobs spawn and die, since
 * nothing ever removed an entry. {@code MetadataStore}'s own {@code clear} is not
 * self-triggering either; it depends on a caller invoking it on entity removal (today, only
 * {@code fabric.mixin.FireworkRocketEntityMixin} does, for its own key). No NeoForge equivalent
 * of that entity-removal hook is wired yet on this branch (mixins/listeners are a later task), so
 * {@link #clear} is likewise not yet called from anywhere -- it exists so the eviction path is
 * ready the moment that hook lands, rather than requiring a second pass through this class then.
 * Until that hook exists, this table has the same unbounded-growth-over-a-session shape
 * {@code MetadataStore} would have if nothing ever called its own {@code clear} either; tracked
 * here rather than silently left unmentioned.
 */
public final class McMMOAttachments {

    private static final Map<UUID, String> MOB_ORIGIN = new ConcurrentHashMap<>();

    private McMMOAttachments() {
    }

    /** Stand-in for {@code entity.getAttached(McMMOAttachments.MOB_ORIGIN)}. */
    public static @Nullable String getMobOrigin(@NotNull Entity entity) {
        return MOB_ORIGIN.get(entity.getUUID());
    }

    /** Stand-in for {@code entity.setAttached(McMMOAttachments.MOB_ORIGIN, value)}. */
    public static void setMobOrigin(@NotNull Entity entity, @NotNull String value) {
        MOB_ORIGIN.put(entity.getUUID(), value);
    }

    /**
     * Drop the stored origin marker for this entity. Call when the entity is removed/dies --
     * mirrors {@link com.gmail.nossr50.platform.MetadataStore#clear(Entity)}. See the class
     * javadoc for why nothing calls this yet on this branch.
     */
    public static void clear(@NotNull Entity entity) {
        clear(entity.getUUID());
    }

    /**
     * Drop the stored origin marker for this entity id, addressed by {@link UUID}. Mirrors
     * {@link com.gmail.nossr50.platform.MetadataStore#remove(UUID, String)}'s UUID-keyed shape --
     * for callers that hold an id rather than a live entity.
     */
    public static void clear(@NotNull UUID entityId) {
        MOB_ORIGIN.remove(entityId);
    }

    /** Clear the entire table (e.g. on server stop). Mirrors {@link com.gmail.nossr50.platform.MetadataStore#clearAll}. */
    public static void clearAll() {
        MOB_ORIGIN.clear();
    }
}
