package net.camacraft.gravityunbound.mixin.compat;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;

/**
 * VMod's {@code playerRotToQuat(pitch, yaw)} builds the holder's rotation
 * as {@code rotateY(-yaw) * rotateX(pitch)}: the rotation that carries the
 * local forward (+Z) onto the look direction, in WORLD axes when gravity is
 * vanilla. Under a rotated gravity frame the player's yaw and pitch live in
 * the frame, so the world rotation is the frame's player-to-world rotation
 * composed with that local one. Returning the composed rotation makes
 * VMod's tick-to-tick delta include the frame turning (a held ship follows
 * the holder round a gravity core) and cancels the twist-to-yaw
 * renormalization exactly (the local yaw jump and the frame's twist change
 * are the same rotation, so the composition is continuous - no snap).
 *
 * The player is the one {@link VModPhysgunStateMixin} recorded from the
 * state's getter just before this call. Only applied when VMod is present.
 */
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "net.spaceeye.vmod.physgun.ServerPhysgunStateKt", remap = false)
public abstract class VModPhysgunRotationMixin {

    @Inject(method = "playerRotToQuat", at = @At("RETURN"), cancellable = true, remap = false)
    private static void gravityunbound$composeWithFrame(double pitch, double yaw, CallbackInfoReturnable<Quaterniond> cir) {
        ServerPlayer player = net.camacraft.gravityunbound.compat.VModPhysgunBridge.currentPlayer();
        if (player == null) {
            return;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(player);
        if (comp == null || comp.isVisuallyDefault()) {
            return;
        }
        // the capability's frame is world-to-player; player-to-world is its conjugate
        Quaternionf frame = comp.getVisualRotation();
        Quaterniond playerToWorld = new Quaterniond(frame).conjugate().normalize();
        Quaterniond local = cir.getReturnValue();
        cir.setReturnValue(playerToWorld.mul(local, new Quaterniond()).normalize());
    }
}
