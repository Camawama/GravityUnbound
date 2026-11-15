package net.camacraft.gravityunbound.network;

import java.util.function.Supplier;

import net.camacraft.camafy.compat.vs.ShipEntities;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityLerper;

/**
 * WHERE THE VEHICLE I DRIVE IS ON THE SHIP, in the ship's own frame (client to server, every tick
 * the vanilla vehicle move packet goes).
 *
 * A vehicle its driver's client simulates (an Automobility car, a boat, a minecart) reports its
 * position with vanilla's vehicle move packet in WORLD coordinates, from a client whose ship pose
 * trails the server's by the network's delay. The server re-simulates that move against ITS pose,
 * finds the vehicle a tick of ship motion away from where the packet says, rejects the move as
 * "moved wrongly" and sends the client a correction: the driven car jumped back and forth by the
 * ship's carry every tick, never counted as on the ground or anchored, and Full Stop read the
 * jumps as the deck jolting under it and threw it off (the owner's car on plating, 2026-09-30).
 * Valkyrien Skies solves this for players with ship-relative positions; its vehicle packet is
 * only sent while the PLAYER's own dragging record names a ship, which lapses within a second of
 * getting in. This is the vehicle's own report: its place and heading in the ship's axes, placed
 * by the server's current pose (exact whatever the delay), registered with VS's dragging so the
 * end-of-tick carry keeps it there, and the vanilla move for it is then skipped
 * ({@code ServerGamePacketListenerImplMixin}).
 *
 * The report is a client's word, and it replaces the vanilla move with its checks (2.0.0.7): it is taken only for a
 * place near that ship and no further from where the server has the vehicle than a driven vehicle travels between two
 * reports ({@link #MAX_STEP}, vanilla's own limit for a vehicle move, plus the ship's travel there), and its heading is
 * wrapped and its pitch kept to a quarter turn. Before, any ship id and any numbers moved the vehicle and everyone in
 * it anywhere in the world, and a pitch of 1e30 on a ridden horse never left vanilla's angle loop on its next tick.
 * A report that fails is dropped, and the vanilla move that follows it is judged as any other.
 */
public class ShipVehicleMotionPacket {
    private final int vehicleId;
    private final long shipId;
    private final double x;
    private final double y;
    private final double z;
    /** The heading in the ship's axes, radians, VS's own convention ({@code EntityLerper.yawToShip}). */
    private final double relYaw;
    private final float pitch;
    /**
     * The heading is the vehicle's own yaw, unconverted: for a vehicle Gravity Unbound holds to the ship it is its heading
     * in a frame that turns with the ship, the same frame on both sides (otherwise {@link #relYaw} is in VS's ship axes).
     */
    private final boolean frameHeading;

    /** Blocks a report may move the vehicle from where the server has it: vanilla's limit for one vehicle move. */
    static final double MAX_STEP = 10.0;
    /** How far outside the ship's bounds a reported place may lie: a vehicle its field holds rides well off its hull. */
    static final double NEAR_SHIP = 128.0;

    public ShipVehicleMotionPacket(int vehicleId, long shipId, double x, double y, double z, double relYaw, float pitch) {
        this(vehicleId, shipId, x, y, z, relYaw, pitch, false);
    }

    public ShipVehicleMotionPacket(int vehicleId, long shipId, double x, double y, double z, double relYaw, float pitch,
                                   boolean frameHeading) {
        this.vehicleId = vehicleId;
        this.shipId = shipId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.relYaw = relYaw;
        this.pitch = pitch;
        this.frameHeading = frameHeading;
    }

    public ShipVehicleMotionPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readLong(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(),
            buf.readBoolean());
    }

    public static void encode(ShipVehicleMotionPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeLong(msg.shipId);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeDouble(msg.relYaw);
        buf.writeFloat(msg.pitch);
        buf.writeBoolean(msg.frameHeading);
    }

    public static class Handler {
        public static void onMessage(ShipVehicleMotionPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null) {
                    return;
                }
                // untrusted input: only the vehicle the sender drives, only finite numbers
                Entity vehicle = sender.getRootVehicle();
                if (vehicle == sender || vehicle.getId() != msg.vehicleId || vehicle.getControllingPassenger() != sender) {
                    return;
                }
                if (!Double.isFinite(msg.x) || !Double.isFinite(msg.y) || !Double.isFinite(msg.z)
                    || !Double.isFinite(msg.relYaw) || !Float.isFinite(msg.pitch)) {
                    return;
                }
                ServerLevel level = sender.serverLevel();
                Ship ship = VSGameUtilsKt.getShipObjectWorld(level).getAllShips().getById(msg.shipId);
                if (ship == null) {
                    return;
                }
                Vector3d world = ship.getShipToWorld().transformPosition(new Vector3d(msg.x, msg.y, msg.z));
                if (!Double.isFinite(world.x) || !Double.isFinite(world.y) || !Double.isFinite(world.z)) {
                    return;
                }
                // yawToWorld takes radians and gives DEGREES (yawToShip is the other way round); a heading in the
                // ship-held frame crosses as it is
                double yaw = msg.frameHeading ? msg.relYaw : EntityLerper.INSTANCE.yawToWorld(ship, msg.relYaw);
                if (!Double.isFinite(yaw) || !plausible(vehicle, ship, world)) {
                    return;
                }
                vehicle.setPos(world.x, world.y, world.z);
                vehicle.setYRot(Mth.wrapDegrees((float) Mth.wrapDegrees(yaw)));
                vehicle.setXRot(Mth.clamp(Mth.wrapDegrees(msg.pitch), -90.0f, 90.0f));
                ShipEntities.keepRiding(vehicle, msg.shipId);
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(vehicle);
                if (comp != null) {
                    comp.noteClientPlaced(level.getGameTime());
                }
            });
            ctx.get().setPacketHandled(true);
        }

        /**
         * Whether a reported place is one the vehicle can have reached: near the ship, and within a move's reach of where
         * the server has the vehicle, the ship's own travel at that place allowed for three ticks over.
         */
        static boolean plausible(Entity vehicle, Ship ship, Vector3d world) {
            AABBdc bounds = ship.getWorldAABB();
            if (world.x < bounds.minX() - NEAR_SHIP || world.x > bounds.maxX() + NEAR_SHIP
                || world.y < bounds.minY() - NEAR_SHIP || world.y > bounds.maxY() + NEAR_SHIP
                || world.z < bounds.minZ() - NEAR_SHIP || world.z > bounds.maxZ() + NEAR_SHIP) {
                return false;
            }
            Vector3dc v = ship.getVelocity();
            Vector3dc w = ship.getOmega();
            Vector3dc centre = ship.getTransform().getPositionInWorld();
            Vector3d carry = new Vector3d(world).sub(centre);
            carry = new Vector3d(w).cross(carry).add(v);
            double perTick = carry.isFinite() ? carry.length() / 20.0 : 0.0;
            double reach = MAX_STEP + 3.0 * perTick;
            double dx = world.x - vehicle.getX(), dy = world.y - vehicle.getY(), dz = world.z - vehicle.getZ();
            return dx * dx + dy * dy + dz * dz <= reach * reach;
        }
    }
}
