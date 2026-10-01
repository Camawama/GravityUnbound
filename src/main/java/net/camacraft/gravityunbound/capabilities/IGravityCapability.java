package net.camacraft.gravityunbound.capabilities;

import net.camacraft.gravityunbound.GravityAPI;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.common.util.INBTSerializable;

@AutoRegisterCapability
public interface IGravityCapability extends INBTSerializable<CompoundTag>
{
	ResourceLocation ID = new ResourceLocation(GravityAPI.MODID, "gravity");

	void setEntity(Entity entity);

	void tick();

	void applyGravityChange();

	void sync(boolean noAnimation, Vec3 baseGravityDirection, Vec3 currentGravityDirection, double baseGravityStrength, double currentGravityStrength, Quaternionf rotation, @org.jetbrains.annotations.Nullable Vec3 targetGravityVector);

	/**
	 * As {@link #sync(boolean, Vec3, Vec3, double, double, Quaternionf, Vec3)}, with the ship a non-living rider is
	 * attached to, its frame in that ship's coordinates and its heading ({@code attachShipId} -1: none).
	 */
	void sync(boolean noAnimation, Vec3 baseGravityDirection, Vec3 currentGravityDirection, double baseGravityStrength, double currentGravityStrength, Quaternionf rotation, @Nullable Vec3 targetVector, long attachShipId, @Nullable Quaternionf attachLocal, float attachYaw);

	/**
	 * Applies the server's running total of frame-yaw re-parametrizations.
	 *
	 * <p>A re-parametrization twists the frame and shifts the yaw by the same
	 * amount, so nothing actually moves. But only the FRAME is carried by
	 * {@link #sync}, and for an entity whose yaw the client owns -- a ridden
	 * vehicle -- the server's yaw half is discarded on the next
	 * {@code ServerboundMoveVehiclePacket}. The client is then holding a
	 * twisted frame against an unshifted yaw, and the vehicle's world heading
	 * turns by the whole twist. This is the missing half, delivered alongside.
	 *
	 * <p>A default no-op rather than an abstract method, so an implementation
	 * outside this mod keeps compiling and simply declines the correction.
	 *
	 * @param total radians, cumulative for the session; NaN means "unknown"
	 */
	default void syncFrameYawReparam(double total) {
	}
}
