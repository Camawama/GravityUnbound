package net.camacraft.gravityunbound.mixin.compat;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VMod's physgun turns a held ship with the holder's view: every server
 * tick it rebuilds the holder's rotation from the player's yaw and pitch
 * ({@code playerRotToQuat}) and applies the change since last tick to the
 * ship. Under this mod the yaw and pitch are FRAME-relative: the same
 * numbers describe a different world direction as the gravity frame turns,
 * and {@code normalizeTwist} moves a settled frame's twist into yaw in one
 * exact step. VMod saw neither the frame turning (the ship stayed put while
 * the holder's whole view rotated with the field) nor that the yaw jump was
 * not a turn (the ship snapped round by the twist). The world rotation VMod
 * wants is the frame's rotation composed with the local one - see
 * {@link VModPhysgunRotationMixin}, which needs to know WHOSE yaw and pitch
 * it was handed. VMod reads the state's player right before both calls, on
 * the same thread; this records it. Only applied when VMod is present.
 */
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "net.spaceeye.vmod.physgun.PlayerPhysgunState", remap = false)
public abstract class VModPhysgunStateMixin {

    @Inject(method = "getServerPlayer", at = @At("RETURN"), remap = false)
    private void gravityunbound$rememberPlayer(CallbackInfoReturnable<ServerPlayer> cir) {
        net.camacraft.gravityunbound.compat.VModPhysgunBridge.setCurrentPlayer(cir.getReturnValue());
    }
}
