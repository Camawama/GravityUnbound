package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;

/**
 * Applies the gravity rotation to the level's model-view matrix.
 *
 * Injected right before vanilla derives the inverse view-rotation matrix from
 * the pose stack, so everything downstream (inverse view rotation, frustum,
 * level rendering, hand rendering, other mods' hooks) sees the rotated stack
 * without replacing any vanilla code.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Camera mainCamera;

    @Shadow
    @Final
    Minecraft minecraft;

    /**
     * Passengers of a vehicle a ship carries ride the ship's DRAWN pose like the vehicle itself
     * (see {@link net.camacraft.gravityunbound.client.PassengerRide}). After VS's own hook at the
     * head of this method (it updates the ships' render transforms and rides the carried
     * entities), before the level is drawn; put back at the end of the frame.
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V"
        )
    )
    private void gravityunbound$ridePassengers(float partialTick, long nanos, boolean renderLevel, CallbackInfo ci) {
        net.camacraft.gravityunbound.client.PassengerRide.before(this.minecraft, partialTick);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void gravityunbound$restorePassengers(float partialTick, long nanos, boolean renderLevel, CallbackInfo ci) {
        net.camacraft.gravityunbound.client.PassengerRide.after();
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "NEW",
            target = "(Lorg/joml/Matrix3fc;)Lorg/joml/Matrix3f;",
            remap = false
        )
    )
    private void gravityunbound$applyGravityRotation(float partialTicks, long nanos, PoseStack poseStack, CallbackInfo ci) {
        Entity focusedEntity = this.mainCamera.getEntity();
        if (focusedEntity == null) {
            return;
        }

        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(focusedEntity);
        if (comp == null || comp.isRenderDefault()) {
            return;
        }

        poseStack.mulPose(comp.getRenderRotation(partialTicks));

        if (comp.isVisuallyMoving()) {
            // The level renderer re-tests chunk visibility against the
            // frustum only when the camera's own pitch/yaw changed — a
            // rotating gravity frame turns the view without touching them,
            // so the frustum result went stale mid-transition. Request the
            // frustum re-test alone. The earlier fix requested a FULL render
            // chunk update (needsUpdate) every frame of every rotation: the
            // asynchronous breadth-first rebuild of the whole render chunk
            // graph, re-queued frame after frame — the frame-rate collapse
            // that made every surface snap and every spinning ship look
            // like it was running at a lower frame rate.
            ((LevelRendererAccessor) this.minecraft.levelRenderer).gravityunbound$needsFrustumUpdate().set(true);
        }
    }
}
