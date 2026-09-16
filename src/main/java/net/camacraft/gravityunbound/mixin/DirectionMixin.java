package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.PlacementFrame;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

/**
 * Look-direction ordering in the player's gravity frame. Inside the ship-
 * placement window the raw angles are already ship-grid look angles (see
 * {@link PlacementFrame#inShipPlacement()}) and pass through untouched.
 */
@Mixin(value = Direction.class, priority = 1001)
public abstract class DirectionMixin {
    @WrapOperation(
        method = "orderedByNearest",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F",
            ordinal = 0
        )
    )
    private static float wrapOperation_getEntityFacingOrder_getYaw_0(Entity entity, float tickDelta, Operation<Float> original) {
        if (GravityChangerAPI.isAimDefault(entity) || PlacementFrame.inShipPlacement()) {
            return original.call(entity, tickDelta);
        }

        return RotationUtil.rotPlayerToWorld(original.call(entity, tickDelta), entity.getViewXRot(tickDelta), GravityChangerAPI.getAimRotation(entity)).x;
    }
    
    @WrapOperation(
        method = "orderedByNearest",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F",
            ordinal = 0
        )
    )
    private static float wrapOperation_getEntityFacingOrder_getPitch_0(Entity entity, float tickDelta, Operation<Float> original) {
        if (GravityChangerAPI.isAimDefault(entity) || PlacementFrame.inShipPlacement()) {
            return original.call(entity, tickDelta);
        }

        return RotationUtil.rotPlayerToWorld(entity.getViewYRot(tickDelta), original.call(entity, tickDelta), GravityChangerAPI.getAimRotation(entity)).y;
    }
    
    @WrapOperation(
        method = "getFacingAxis",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F",
            ordinal = 0
        )
    )
    private static float wrapOperation_getLookDirectionForAxis_getYaw_0(Entity entity, float tickDelta, Operation<Float> original) {
        if (GravityChangerAPI.isAimDefault(entity) || PlacementFrame.inShipPlacement()) {
            return original.call(entity, tickDelta);
        }

        return RotationUtil.rotPlayerToWorld(original.call(entity, tickDelta), entity.getViewXRot(tickDelta), GravityChangerAPI.getAimRotation(entity)).x;
    }
    
    @WrapOperation(
        method = "getFacingAxis",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F",
            ordinal = 1
        )
    )
    private static float wrapOperation_getLookDirectionForAxis_getYaw_1(Entity entity, float tickDelta, Operation<Float> original) {
        if (GravityChangerAPI.isAimDefault(entity) || PlacementFrame.inShipPlacement()) {
            return original.call(entity, tickDelta);
        }

        return RotationUtil.rotPlayerToWorld(original.call(entity, tickDelta), entity.getViewXRot(tickDelta), GravityChangerAPI.getAimRotation(entity)).x;
    }
    
    @WrapOperation(
        method = "getFacingAxis",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F",
            ordinal = 0
        )
    )
    private static float wrapOperation_getLookDirectionForAxis_getPitch_0(Entity entity, float tickDelta, Operation<Float> original) {
        if (GravityChangerAPI.isAimDefault(entity) || PlacementFrame.inShipPlacement()) {
            return original.call(entity, tickDelta);
        }

        return RotationUtil.rotPlayerToWorld(entity.getViewYRot(tickDelta), original.call(entity, tickDelta), GravityChangerAPI.getAimRotation(entity)).y;
    }
}
