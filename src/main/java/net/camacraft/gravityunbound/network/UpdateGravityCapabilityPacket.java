package net.camacraft.gravityunbound.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.camacraft.gravityunbound.capabilities.GravityCapabilities;
import net.camacraft.gravityunbound.util.GCUtil;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

public class UpdateGravityCapabilityPacket
{
	private final UUID entityUUID;
	private final boolean noAnimation;
	private final Vec3 baseGravityDirection;
	private final Vec3 currentGravityDirection;
	private final double baseGravityStrength;
	private final double currentGravityStrength;
	private final Quaternionf rotation;
	// the continuous field vector (null: none resolved on the server). Remote
	// entities integrate their own gravity against it between position
	// updates — projectiles above all — and a rider's frame follows its
	// vehicle through it; without it they fell along the snapped cardinal
	// (or plain world-down) and every position packet corrected them.
	@Nullable
	private final Vec3 targetGravityVector;
	// RUNNING TOTAL of every frame-yaw re-parametrization the server has
	// applied to this entity, in radians.
	//
	// A re-parametrization twists the frame and shifts the yaw by the same
	// amount, so nothing moves — but only the FRAME crosses the wire, and for
	// an entity whose yaw the client owns (a ridden vehicle) the server's half
	// of the pair is discarded on the next ServerboundMoveVehiclePacket. The
	// client received a twisted frame with its old yaw and the vehicle turned.
	//
	// A TOTAL rather than a per-event delta, because a total is idempotent: a
	// coalesced, duplicated or reordered packet converges on the same answer,
	// where a sequence of deltas silently loses or double-applies one. The
	// client stores the last value it saw and applies the difference.
	private final double frameYawReparam;

	public UpdateGravityCapabilityPacket(boolean noAnimation, UUID entityUUID, Vec3 baseGravityDirection, Vec3 currentGravityDirection,
										 double baseGravityStrength, double currentGravityStrength, Quaternionf rotation,
										 @Nullable Vec3 targetGravityVector, double frameYawReparam)
	{
		this.frameYawReparam = frameYawReparam;
		this.noAnimation = noAnimation;
		this.entityUUID = entityUUID;
		this.baseGravityDirection = baseGravityDirection;
		this.currentGravityDirection = currentGravityDirection;
		this.baseGravityStrength = baseGravityStrength;
		this.currentGravityStrength = currentGravityStrength;
		this.rotation = rotation;
		this.targetGravityVector = targetGravityVector;
	}

	public UpdateGravityCapabilityPacket(FriendlyByteBuf buf)
	{
		this.noAnimation = buf.readBoolean();
		this.entityUUID = buf.readUUID();
		this.baseGravityDirection = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		this.currentGravityDirection = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		this.baseGravityStrength = buf.readDouble();
		this.currentGravityStrength = buf.readDouble();
		this.rotation = new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
		this.frameYawReparam = buf.readDouble();
		if (buf.readBoolean())
		{
			this.targetGravityVector = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		}
		else
		{
			this.targetGravityVector = null;
		}
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeBoolean(this.noAnimation);
		buf.writeUUID(this.entityUUID);
		buf.writeDouble(this.baseGravityDirection.x);
		buf.writeDouble(this.baseGravityDirection.y);
		buf.writeDouble(this.baseGravityDirection.z);
		buf.writeDouble(this.currentGravityDirection.x);
		buf.writeDouble(this.currentGravityDirection.y);
		buf.writeDouble(this.currentGravityDirection.z);
		buf.writeDouble(this.baseGravityStrength);
		buf.writeDouble(this.currentGravityStrength);
		buf.writeFloat(this.rotation.x());
		buf.writeFloat(this.rotation.y());
		buf.writeFloat(this.rotation.z());
		buf.writeFloat(this.rotation.w());
		buf.writeDouble(this.frameYawReparam);
		buf.writeBoolean(this.targetGravityVector != null);
		if (this.targetGravityVector != null)
		{
			buf.writeDouble(this.targetGravityVector.x);
			buf.writeDouble(this.targetGravityVector.y);
			buf.writeDouble(this.targetGravityVector.z);
		}
	}

	public static class Handler
	{
		public static boolean onMessage(UpdateGravityCapabilityPacket message, Supplier<NetworkEvent.Context> ctx)
		{
			ctx.get().enqueueWork(() ->
			{
				if(ctx.get().getDirection().getReceptionSide().isClient())
				{
					GCUtil.getClientLevel(level ->
					{
						Entity entity = GCUtil.getEntityByUUID(level, message.entityUUID);
						if(entity == null)
						{
							// the entity has not been spawned on this client yet;
							// it will receive its state from the start-tracking sync
							return;
						}
						entity.getCapability(GravityCapabilities.GRAVITY).ifPresent(cap ->
						{
							// BEFORE sync: the yaw pairing belongs with the frame
							// the server had when it re-parametrized, and sync
							// adopts the new one.
							cap.syncFrameYawReparam(message.frameYawReparam);
							cap.sync(message.noAnimation, message.baseGravityDirection, message.currentGravityDirection,
								message.baseGravityStrength, message.currentGravityStrength, message.rotation,
								message.targetGravityVector);
						});
					});
				}
			});

			ctx.get().setPacketHandled(true);
			return true;
		}
	}
}
