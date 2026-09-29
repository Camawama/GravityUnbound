package net.camacraft.gravityunbound.util;

import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import net.camacraft.camafy.compat.vs.impl.ShipGravities;
import net.camacraft.camafy.compat.vs.impl.VsTyped;

import net.minecraft.server.level.ServerLevel;

/**
 * The gravity Valkyrien Skies (and the mods riding on it) actually apply
 * to a ship, in m/s^2 on the world axes — what a field that REPLACES
 * gravity has to cancel. Camafy (0.1.7) now owns the lookup
 * ({@link ShipGravities}): VMod's per-ship custom vector, Genesis's zeroed
 * space dimensions, VS's per-dimension parameters, plus VLib's per-ship
 * correction, so Full Stop's ship weight reads the same answer.
 */
public final class ShipGravity {

    /** The dimension's gravity vector as VS's parameter table has it (VS's default when it has none). */
    public static Vector3d dimension(ServerLevel level) {
        return VsTyped.dimensionGravity(level);
    }

    /** The gravity acting on this particular ship right now. */
    public static Vector3d actingOn(ServerLevel level, LoadedServerShip ship) {
        return ShipGravities.actingOn(level, ship);
    }

    private ShipGravity() {}
}
