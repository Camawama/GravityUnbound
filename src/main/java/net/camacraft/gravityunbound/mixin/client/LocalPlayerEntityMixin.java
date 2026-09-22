package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerEntityMixin extends AbstractClientPlayer {
    public LocalPlayerEntityMixin(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }
    
    @Shadow
    protected abstract boolean suffocatesAt(BlockPos pos);

    @Redirect(
        method = "Lnet/minecraft/client/player/LocalPlayer;suffocatesAt(Lnet/minecraft/core/BlockPos;)Z",
        at = @At(
            value = "NEW",
            target = "(DDDDDD)Lnet/minecraft/world/phys/AABB;",
            ordinal = 0
        )
    )
    private AABB redirect_wouldCollideAt_new_0(double x1, double y1, double z1, double x2, double y2, double z2, BlockPos pos) {
        if (GravityChangerAPI.isGravityDefault(this)) {
            return new AABB(x1, y1, z1, x2, y2, z2);
        }
        org.joml.Quaternionf gravityRotation = GravityChangerAPI.getGravityRotation(this);

        AABB playerBox = this.getBoundingBox();
        Vec3 playerMask = RotationUtil.maskPlayerToWorld(new Vec3(0.0D, 1.0D, 0.0D), gravityRotation);
        AABB posBox = new AABB(pos);
        Vec3 posMask = RotationUtil.maskPlayerToWorld(new Vec3(1.0D, 0.0D, 1.0D), gravityRotation);

        return new AABB(
            playerMask.multiply(playerBox.minX, playerBox.minY, playerBox.minZ).add(posMask.multiply(posBox.minX, posBox.minY, posBox.minZ)),
            playerMask.multiply(playerBox.maxX, playerBox.maxY, playerBox.maxZ).add(posMask.multiply(posBox.maxX, posBox.maxY, posBox.maxZ))
        );
    }

    @Inject(
        method = "Lnet/minecraft/client/player/LocalPlayer;moveTowardsClosestSpace(DD)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_pushOutOfBlocks(double x, double z, CallbackInfo ci) {
        // THE CAPSULE PREDICATE, NOT THE CARDINAL.
        //
        // This used to stand down on isGravityDefault(), which is the snapped
        // cardinal (currGravityDirection == DOWN). The collider it exists to
        // defer to is switched by useCapsuleCollision(), which is the FRAME
        // (!isVisuallyDefault). The two disagree for the whole of a side-to-top
        // walk on a gravity cube: the cardinal snaps to DOWN the tick after the
        // top face is adopted, while the frame still has 60 degrees to chase and
        // the capsule stays on for the next several ticks.
        //
        // In that window vanilla ran against the STORED box, which in capsule
        // mode is the tilted envelope built from the previous move. Its bottom
        // sits 0.02*(1-cos tilt) below the floor the real spheres rest on (the
        // envelope uses width/2, the collider 14/15 of it), so suffocatesAt read
        // the cube's top layer as a suffocating block under every column, and
        // moveTowardsClosestSpace wrote +-0.1 on a WORLD axis toward the one
        // free neighbour -- the edge just crossed -- straight into
        // deltaMovement, which Entity.move reads in the LOCAL frame. The
        // transported frame still carries the side face's holonomy twist, so
        // that push came out along world Z whichever face was left: north from
        // the west and east faces, south from the north and south faces.
        // Reported from play as exactly that table.
        //
        // Gating on the collider's own predicate makes the cancel cover the whole
        // capsule window and nothing more: a bit-canonical identity frame keeps
        // vanilla's push-out exactly as before.
        net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(this);
        if (comp == null || !comp.useCapsuleCollision()) return;

        // the capsule collider keeps the player out of blocks; the vanilla
        // box-based push-out fights it and causes jitter
        ci.cancel();
    }

    // (the crouch/pose fit fix lives in EntityMixin.gravityunbound$capsuleCanEnterPose —
    // 1.20.1's LocalPlayer crouch logic goes through Entity.canEnterPose)
}
