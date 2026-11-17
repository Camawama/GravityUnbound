package net.camacraft.gravityunbound.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

/**
 * Soft Vanilla Backport support (reflective; no build dependency; nothing is
 * touched when Vanilla Backport is absent).
 *
 * <p>Vanilla Backport runs its own copy of {@code ServerEntity.sendChanges}
 * for its {@code PositionAwareEntity} mobs (the happy ghast), which skips
 * this mod's sync hooks; see {@code mixin.compat.VanillaBackportServerEntityMixin},
 * which hands those mobs back to the vanilla method inside a gravity frame.
 * The one thing that copy adds is its 1.21.6 "precise position" flag: while
 * set (a happy ghast holding still with players on or around it), every
 * position update goes out as an absolute teleport instead of a delta, so
 * the platform players stand on never drifts on their screens. This reads
 * that flag so the vanilla path can keep honouring it.
 */
public final class VanillaBackportCompat {
    private static final boolean LOADED = ModList.get().isLoaded("vanillabackport");
    /** The interface's name in Vanilla Backport 1.2 and in 1.1. */
    private static final String[] POSITION_AWARE = {
        "com.blackgear.vanillabackport.common.api.extensions.entity.movement.PositionAwareEntity",
        "com.blackgear.vanillabackport.common.api.extensions.PositionAwareEntity"
    };
    private static boolean resolved;
    private static Class<?> positionAware;
    private static MethodHandle requiresPrecisePosition;

    private VanillaBackportCompat() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!LOADED) {
            return;
        }
        try {
            for (String name : POSITION_AWARE) {
                try {
                    positionAware = Class.forName(name, false, VanillaBackportCompat.class.getClassLoader());
                    break;
                }
                catch (ClassNotFoundException missing) {
                    // the other Vanilla Backport version's package
                }
            }
            if (positionAware == null) {
                return;
            }
            requiresPrecisePosition = MethodHandles.publicLookup()
                .findVirtual(positionAware, "getRequiresPrecisePosition", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Entity.class));
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            positionAware = null;
            requiresPrecisePosition = null;
        }
    }

    /** Whether Vanilla Backport syncs this entity through its own copy of {@code sendChanges}. */
    public static boolean isPositionAware(Entity entity) {
        resolve();
        return positionAware != null && positionAware.isInstance(entity);
    }

    /** Vanilla Backport's precise-position flag for {@code entity}; false without Vanilla Backport. */
    public static boolean requiresPrecisePosition(Entity entity) {
        if (!isPositionAware(entity) || requiresPrecisePosition == null) {
            return false;
        }
        try {
            return (boolean) requiresPrecisePosition.invokeExact(entity);
        }
        catch (Throwable t) {
            return false;
        }
    }
}
