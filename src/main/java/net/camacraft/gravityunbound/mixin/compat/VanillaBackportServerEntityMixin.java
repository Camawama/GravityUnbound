package net.camacraft.gravityunbound.mixin.compat;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.compat.VanillaBackportCompat;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;

/**
 * Vanilla Backport HEAD-cancels {@code ServerEntity.sendChanges} for its
 * {@code PositionAwareEntity} mobs (the happy ghast) and runs its own copy
 * of the method instead. That copy skips everything this mod hooks into
 * {@code sendChanges} (see {@code mixin.ServerEntityMixin}): the every-tick
 * sync inside a gravity field, the forced velocity sync, and above all the
 * conversion of the velocity packet to WORLD space. So a happy ghast in a
 * field sent its LOCAL-frame motion, which the watching client rotated a
 * second time, and it drifted and snapped for everyone but its rider.
 *
 * <p>Inside a non-default gravity frame this cancels Vanilla Backport's
 * handler (MixinSquared) before it does anything, so the vanilla method runs
 * with this mod's hooks. Outside a frame, and for every other entity,
 * Vanilla Backport's copy runs exactly as before.
 *
 * <p>What the copy adds over vanilla, and what is kept: its 1.21.6 "precise
 * position" flag, set while a happy ghast holds still for players standing
 * on it, which turns every position update into an absolute teleport so the
 * platform never drifts on the watchers' screens. On the vanilla path that
 * flag now forces vanilla's own teleport branch (the same packet Vanilla
 * Backport sends). The rest of the copy is vanilla's method with small
 * bookkeeping differences, including the passenger and hurt-velocity
 * packets, which vanilla sends too.
 *
 * <p>Applied only when Vanilla Backport is installed (see
 * {@code GravityMixinPlugin}), and both injections are optional: a Vanilla
 * Backport update that renames its handler loses this fix, never the game.
 */
@Mixin(value = ServerEntity.class, priority = 1500)
public abstract class VanillaBackportServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Unique
    private boolean gravityunbound$inFrame() {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this.entity);
        return comp != null && !comp.isVisuallyDefault();
    }

    @TargetHandler(
        mixin = "com.blackgear.vanillabackport.core.mixin.common.entities.ServerEntityMixin",
        name = "onSendChanges"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true, require = 0)
    private void gravityunbound$vanillaSyncInField(CallbackInfo vanillaBackportCallback, CallbackInfo ci) {
        if (VanillaBackportCompat.isPositionAware(this.entity) && gravityunbound$inFrame()) {
            ci.cancel();
        }
    }

    /**
     * Vanilla Backport's precise-position flag on the vanilla path: vanilla
     * sends an absolute teleport instead of a delta when the entity was
     * riding last update; reading the flag in as "was riding" takes exactly
     * that branch. Only an entity handed back by the injection above can
     * reach this with the flag set: otherwise Vanilla Backport's copy has
     * already cancelled the method.
     */
    @ModifyExpressionValue(
        method = "sendChanges",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/level/ServerEntity;wasRiding:Z",
            opcode = Opcodes.GETFIELD
        ),
        require = 0
    )
    private boolean gravityunbound$keepPrecisePosition(boolean wasRiding) {
        return wasRiding || VanillaBackportCompat.requiresPrecisePosition(this.entity);
    }
}
