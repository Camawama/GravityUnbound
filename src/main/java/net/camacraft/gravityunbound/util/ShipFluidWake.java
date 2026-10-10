package net.camacraft.gravityunbound.util;

import net.minecraft.world.phys.AABB;
import org.joml.Quaterniondc;
import org.valkyrienskies.core.api.ships.Ship;

/**
 * When a ship-mounted field wakes the WORLD fluid it covers (see
 * {@code GravityCoreBlockEntity.wakeWorldFluidsUnderShip} and the plating's).
 *
 * Still water has no scheduled ticks of its own, so a field that moves over
 * it has to wake it; that wake used to run every period for as long as the
 * field existed. A field that has stopped changing has nothing new to tell
 * the water after the first few wakes (woken fluid that still has somewhere
 * to go keeps ticking by itself), yet every wake re-ticked every fluid block
 * in the field's box: a third of the server's tick beside a pool in the
 * owner's profile of 2026-10-04. So: while the field's box, the ship's turn
 * or the field's settings change, every period as before; once they have
 * held still for {@value #SETTLE_WAKES} periods, one period in
 * {@value #REST_EVERY} (a safety net, not a mechanism).
 *
 * {@code -Dgravityunbound.shipFluidWakeAlways=true} wakes every period as
 * before (A/B runs).
 */
public final class ShipFluidWake {

    /** Wakes still made, every period, after the field last changed. */
    public static final int SETTLE_WAKES = 5;
    /** After that: one period in this many. */
    public static final int REST_EVERY = 10;

    private long signature = Long.MIN_VALUE;
    private int periodsSinceChange;

    /**
     * Called once per wake period: whether to wake now. {@code settings}
     * is whatever of the block's own settings changes its field (range,
     * targets, direction...): a change counts like a move.
     */
    public boolean due(Ship ship, AABB worldBox, int settings) {
        if (Boolean.getBoolean("gravityunbound.shipFluidWakeAlways")) {
            return true;
        }
        long now = signature(ship, worldBox, settings);
        if (now != signature) {
            signature = now;
            periodsSinceChange = 0;
            return true;
        }
        periodsSinceChange++;
        if (periodsSinceChange <= SETTLE_WAKES) {
            return true;
        }
        return periodsSinceChange % REST_EVERY == 0;
    }

    /**
     * The field's place and turn, coarse enough that a ship lying still
     * (whose physics body still trembles by fractions of a millimetre)
     * reads the same: the box to a sixteenth of a block, the turn to
     * about a tenth of a degree.
     */
    private static long signature(Ship ship, AABB box, int settings) {
        long h = settings;
        h = h * 31 + Math.round(box.minX * 16.0);
        h = h * 31 + Math.round(box.minY * 16.0);
        h = h * 31 + Math.round(box.minZ * 16.0);
        h = h * 31 + Math.round(box.maxX * 16.0);
        h = h * 31 + Math.round(box.maxY * 16.0);
        h = h * 31 + Math.round(box.maxZ * 16.0);
        Quaterniondc q = ship.getTransform().getShipToWorldRotation();
        h = h * 31 + Math.round(q.x() * 512.0);
        h = h * 31 + Math.round(q.y() * 512.0);
        h = h * 31 + Math.round(q.z() * 512.0);
        h = h * 31 + Math.round(q.w() * 512.0);
        return h;
    }
}
