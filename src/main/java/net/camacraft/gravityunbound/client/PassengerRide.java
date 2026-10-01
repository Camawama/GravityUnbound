package net.camacraft.gravityunbound.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * VALKYRIEN SKIES' RENDER-RIDE FOR PASSENGERS. Each frame VS draws an entity a ship carries on the ship's DRAWN pose
 * (its render transform) by rewriting the entity's {@code xo} interpolation base, so a car parked on a moving deck is
 * drawn exactly on the deck between two ticks. It leaves the car's passengers alone: a passenger is never dragged
 * (it has a vehicle), so its camera and model interpolate along the straight chord between the two places the car
 * seated it at, while the car and the deck are drawn along the ship's arc. On a fast or spinning ship the driver's
 * camera wobbled against its own car by a tick of ship motion (the owner's video of 2026-09-30, a car on plating in
 * the Great Unknown). This gives every passenger of a carried vehicle the same ride as the vehicle: the position it
 * would have had without the vehicle's carry is moved with the ship from the previous tick pose to the drawn pose,
 * exactly VS's own arithmetic, and the interpolation base is set so the frame lands there. Restored after the frame.
 */
public final class PassengerRide {
    private record Saved(Entity entity, double xo, double yo, double zo) {}

    private static final List<Saved> SAVED = new ArrayList<>();

    private PassengerRide() {
    }

    /** Before the level is drawn: place every passenger of a carried vehicle on the drawn ship pose for this frame. */
    public static void before(Minecraft mc, float partialTick) {
        ClientLevel level = mc.level;
        if (level == null || partialTick >= 0.99999F) return;
        Object worldObj = VSGameUtilsKt.getShipObjectWorld(level);
        for (Entity entity : level.entitiesForRendering()) {
            Entity root = entity.getRootVehicle();
            if (root == entity || !(root instanceof IEntityDraggingInformationProvider provider)) continue;
            EntityDraggingInformation info = provider.getDraggingInformation();
            Long shipId = info.getLastShipStoodOn();
            if (shipId == null || !info.isEntityBeingDraggedByAShip()) continue;
            ClientShip ship = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(shipId);
            if (ship == null) continue;
            Vector3dc added = info.getAddedMovementLastTick();
            // the passenger's own movement this tick, without the ship's carry of its vehicle
            double mx = entity.getX() - added.x() - entity.xo;
            double my = entity.getY() - added.y() - entity.yo;
            double mz = entity.getZ() - added.z() - entity.zo;
            Vector3d pre = new Vector3d(entity.xo + mx * partialTick, entity.yo + my * partialTick, entity.zo + mz * partialTick);
            Vector3d here = ship.getRenderTransform().getShipToWorldMatrix().transformPosition(
                    ship.getPrevTickShipTransform().getWorldToShipMatrix().transformPosition(pre, new Vector3d()));
            if (!Double.isFinite(here.x) || !Double.isFinite(here.y) || !Double.isFinite(here.z)) continue;
            SAVED.add(new Saved(entity, entity.xo, entity.yo, entity.zo));
            double rest = 1.0 - partialTick;
            entity.xo = (here.x - entity.getX() * partialTick) / rest;
            entity.yo = (here.y - entity.getY() * partialTick) / rest;
            entity.zo = (here.z - entity.getZ() * partialTick) / rest;
        }
    }

    /** After the frame: put the interpolation bases back, so the next tick's own arithmetic is untouched. */
    public static void after() {
        for (Saved s : SAVED) {
            s.entity().xo = s.xo();
            s.entity().yo = s.yo();
            s.entity().zo = s.zo();
        }
        SAVED.clear();
    }
}
