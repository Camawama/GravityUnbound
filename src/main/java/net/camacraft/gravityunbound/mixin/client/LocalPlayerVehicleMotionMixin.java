package net.camacraft.gravityunbound.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.network.GravityNetwork;
import net.camacraft.gravityunbound.network.ShipVehicleMotionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.EntityLerper;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * WHERE THE VEHICLE I DRIVE IS ON THE SHIP, told to the server in the ship's own frame each time
 * the vanilla vehicle move packet goes (see {@link ShipVehicleMotionPacket} for why). Sent for a
 * vehicle held in a ship's field (Gravity Unbound's attachment: plating, a core) or one VS is
 * carrying, from the vehicle's own record, never the player's.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerVehicleMotionMixin {

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void gravityunbound$sendVehicleShipMotion(ClientPacketListener listener, Packet<?> packet, Operation<Void> original) {
        if (packet instanceof ServerboundMoveVehiclePacket) {
            try {
                gravityunbound$sendShipRelative();
            } catch (Throwable t) {
                // never let a compat hook stop the vehicle packet
            }
        }
        original.call(listener, packet);
    }

    private void gravityunbound$sendShipRelative() {
        LocalPlayer self = (LocalPlayer) (Object) this;
        Entity vehicle = self.getRootVehicle();
        if (vehicle == self || !(vehicle instanceof IEntityDraggingInformationProvider provider)) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Long shipId = null;
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(vehicle);
        if (comp != null) shipId = comp.attachedShipId();
        if (shipId == null) {
            EntityDraggingInformation info = provider.getDraggingInformation();
            if (info.isEntityBeingDraggedByAShip()) shipId = info.getLastShipStoodOn();
        }
        if (shipId == null) return;
        ClientShip ship = VSGameUtilsKt.getShipObjectWorld(level).getAllShips().getById(shipId);
        if (ship == null) return;
        Vector3d rel = ship.getWorldToShip().transformPosition(new Vector3d(vehicle.getX(), vehicle.getY(), vehicle.getZ()));
        double relYaw = EntityLerper.INSTANCE.yawToShip(ship, vehicle.getYRot());
        GravityNetwork.sendToServer(new ShipVehicleMotionPacket(vehicle.getId(), ship.getId(), rel.x, rel.y, rel.z, relYaw, vehicle.getXRot()));
    }
}
