package net.camacraft.gravityunbound.mixin.client;


import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;

/**
 * Fishing line under a rotated gravity frame.
 *
 * Vanilla builds the line's rod-tip end (locals d4/d5/d6, then f3, a lift
 * added straight up the WORLD y axis) from world-axis offsets: the camera
 * near-plane point swung by the attack animation in first person, a
 * body-yaw arm offset in third person. Under a rotated frame both must be
 * expressed in the owner's frame. This used to HEAD-cancel render and draw
 * a full copy, which silently dropped every other mod's change to the same
 * method (Nice Catch's hook-in-mouth billboard tilt and rod-stand line
 * anchor) and flipped the arm for modded rods (it tested the vanilla rod
 * item instead of Forge's FISHING_ROD_CAST tool action).
 *
 * Now only the offsets are wrapped, BEFORE the tip coordinates are stored:
 * <ul>
 *   <li>first person: the near-plane point is taken into the player frame
 *       (getPointOnPlane), vanilla scales and swings it there unchanged, and
 *       the swung offset returns to the world frame (xRot) with the eye
 *       lift applied along the player's up — minus the world-y lift vanilla
 *       adds as f3 afterwards;</li>
 *   <li>third person: the frame-rotated arm offset minus vanilla's world-axis
 *       one is added to the tip's base terms (Mth.lerp x/z, getEyeHeight
 *       for y — f3's crouch dip included, since vanilla adds it on world y
 *       later).</li>
 * </ul>
 * The bobber billboard, f3 and everything else stay vanilla.
 *
 * COMPOSITION: Nice Catch rewrites d4/d5/d6 with @ModifyVariable at STORE,
 * which runs AFTER each store; these wraps change the value BEFORE it is
 * stored. So whatever the mixin order, a stand-parked line still snaps to
 * its stand (NC's anchor replaces the frame-correct hand tip), and an
 * ordinary line reaches NC's hook unchanged. NC pre-subtracts vanilla's
 * world-y f3 lift from its anchor, which stays valid because f3 is left
 * alone here.
 */
@Mixin(FishingHookRenderer.class)
public abstract class FishingHookEntityRendererMixin {

    // ------------------------------------------------------------------
    // first person (the local player's own line)
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/entity/FishingHookRenderer;render(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera$NearPlane;getPointOnPlane(FF)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 gravityunbound$nearPlaneToPlayerFrame(Camera.NearPlane plane, float left, float up, Operation<Vec3> original, FishingHook hook, float entityYaw, float partialTicks) {
        Vec3 point = original.call(plane, left, up);
        Quaternionf frame = gravityunbound$lineFrame(hook, partialTicks);
        return frame == null ? point : RotationUtil.vecWorldToPlayer(point, frame);
    }

    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/entity/FishingHookRenderer;render(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;xRot(F)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 gravityunbound$swungOffsetToWorld(Vec3 offset, float pitch, Operation<Vec3> original, FishingHook hook, float entityYaw, float partialTicks) {
        Vec3 swung = original.call(offset, pitch);
        Quaternionf frame = gravityunbound$lineFrame(hook, partialTicks);
        if (frame == null) {
            return swung;
        }
        // vanilla then adds the eye height (f3) up the WORLD y axis
        double eye = hook.getPlayerOwner().getEyeHeight();
        return RotationUtil.vecPlayerToWorld(swung.add(0.0D, eye, 0.0D), frame).subtract(0.0D, eye, 0.0D);
    }

    // ------------------------------------------------------------------
    // third person (every other line, and your own in F5)
    // Mth.lerp(DDD) calls in render: 0-2 first-person tip, 3 third-person
    // tip x, 4 third-person tip z, 5-7 bobber. getEyeHeight: 0 first
    // person (f3), 1 third-person tip y.
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/entity/FishingHookRenderer;render(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;lerp(DDD)D",
            ordinal = 3
        )
    )
    private double gravityunbound$thirdPersonTipX(double delta, double start, double end, Operation<Double> original, FishingHook hook, float entityYaw, float partialTicks) {
        double x = original.call(delta, start, end);
        Vec3 correction = gravityunbound$thirdPersonCorrection(hook, partialTicks);
        return correction == null ? x : x + correction.x;
    }

    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/entity/FishingHookRenderer;render(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getEyeHeight()F",
            ordinal = 1
        )
    )
    private float gravityunbound$thirdPersonTipY(Player player, Operation<Float> original, FishingHook hook, float entityYaw, float partialTicks) {
        float eye = original.call(player);
        Vec3 correction = gravityunbound$thirdPersonCorrection(hook, partialTicks);
        return correction == null ? eye : (float) (eye + correction.y);
    }

    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/entity/FishingHookRenderer;render(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;lerp(DDD)D",
            ordinal = 4
        )
    )
    private double gravityunbound$thirdPersonTipZ(double delta, double start, double end, Operation<Double> original, FishingHook hook, float entityYaw, float partialTicks) {
        double z = original.call(delta, start, end);
        Vec3 correction = gravityunbound$thirdPersonCorrection(hook, partialTicks);
        return correction == null ? z : z + correction.z;
    }

    /**
     * The owner's interpolated visual frame, or null when the line should
     * render exactly as vanilla. The player model renders in the smooth
     * interpolated visual frame, so the rod-tip offsets must use the SAME
     * frame — the snapped cardinal made the line detach from the rod during
     * transitions and on tilted ships.
     */
    @Unique
    @Nullable
    private static Quaternionf gravityunbound$lineFrame(FishingHook hook, float partialTicks) {
        Player player = hook.getPlayerOwner();
        if (player == null) {
            return null;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(player);
        if (comp == null || comp.isRenderDefault()) {
            return null;
        }
        return comp.getRenderRotation(partialTicks);
    }

    /**
     * Frame-rotated third-person rod-tip offset minus the world-axis one
     * vanilla uses (arm offset from body yaw, eye height less 0.45, crouch
     * dip), or null when the frame is default.
     */
    @Unique
    @Nullable
    private static Vec3 gravityunbound$thirdPersonCorrection(FishingHook hook, float partialTicks) {
        Quaternionf frame = gravityunbound$lineFrame(hook, partialTicks);
        if (frame == null) {
            return null;
        }
        Player player = hook.getPlayerOwner();
        int arm = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        if (!player.getMainHandItem().canPerformAction(ToolActions.FISHING_ROD_CAST)) {
            arm = -arm;
        }
        float bodyYaw = Mth.lerp(partialTicks, player.yBodyRotO, player.yBodyRot) * ((float) Math.PI / 180F);
        double sin = Mth.sin(bodyYaw);
        double cos = Mth.cos(bodyYaw);
        double armOffset = (double) arm * 0.35D;
        Vec3 local = new Vec3(
            -cos * armOffset - sin * 0.8D,
            player.getEyeHeight() + (player.isCrouching() ? -0.1875D : 0.0D) - 0.45D,
            -sin * armOffset + cos * 0.8D
        );
        return RotationUtil.vecPlayerToWorld(local, frame).subtract(local);
    }
}
