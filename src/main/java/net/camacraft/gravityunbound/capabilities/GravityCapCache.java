package net.camacraft.gravityunbound.capabilities;

import org.jetbrains.annotations.Nullable;

/**
 * Every entity (through {@code mixin.EntityMixin}): its gravity capability looked up once and kept, instead of a
 * Forge capability lookup per call (2026-10-17: {@code getCapability} walks every mod's providers, and this mod asked
 * for every entity several times a tick on both sides). The kept {@code LazyOptional} is re-fetched once Forge has
 * invalidated it (an entity removed, a player changing dimension and revived), so the answer is never stale.
 */
public interface GravityCapCache {
    /** The entity's gravity capability, or null when it has none (a capability-less entity, caps not attached yet). */
    @Nullable
    GravityCapabilityImpl gravityunbound$gravity();
}
