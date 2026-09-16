package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(ThrowableProjectile.class)
public abstract class ThrowableProjectileMixin {
    
    @Shadow
    protected abstract float getGravity();

    /*@Override
    public Direction gravitychanger$getAppliedGravityDirection() {
        return GravityChangerAPI.getGravityDirection((ThrownEntity)(Object)this);
    }*/
    
    // Vanilla ends tick() with: vec31 = getDeltaMovement(); setDeltaMovement(vec31.x, vec31.y - gravity, vec31.z).
    // Re-aim that world-down pull along the CONTINUOUS field vector (cardinal
    // fallback) for smooth orbits. This wraps the gravity write itself: the
    // earlier form modified the tick's FIRST velocity local - the one the
    // POSITION is integrated with - so every rotated-gravity throwable also
    // drifted world-up by its gravity constant (0.03 blocks) each tick, on
    // top of leading its own acceleration by a tick.
    @WrapOperation(
        method = "Lnet/minecraft/world/entity/projectile/ThrowableProjectile;tick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/ThrowableProjectile;setDeltaMovement(DDD)V"
        )
    )
    private void gravityunbound$gravityAlongField(ThrowableProjectile self, double x, double y, double z, Operation<Void> original) {
        net.minecraft.core.Direction cardinal = GravityChangerAPI.getGravityDirection(self);
        if (cardinal == net.minecraft.core.Direction.DOWN) {
            original.call(self, x, y, z);
            return;
        }
        double g = this.getGravity();
        Vec3 pull = GravityChangerAPI.getFieldPullDirection(self);
        // cancel vanilla's world -y (already subtracted from y), apply along the field
        original.call(self, x + pull.x * g, y + g + pull.y * g, z + pull.z * g);
    }

    // Vanilla's (EntityType, LivingEntity, Level) constructor spawns at
    // (ownerX, ownerEyeY - 0.1, ownerZ), which assumes world-down gravity.
    // Recreate the eye-relative offset in the owner's aim frame (same pattern as
    // CrossbowItemMixin's eye-based spawn correction).
    @Inject(
        method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;)V",
        at = @At("TAIL")
    )
    private void inject_init_repositionForRotatedOwner(EntityType<? extends ThrowableProjectile> type, LivingEntity owner, Level level, CallbackInfo ci) {
        if (GravityChangerAPI.isAimDefault(owner)) {
            return;
        }

        Vec3 pos = owner.getEyePosition().subtract(
            RotationUtil.vecPlayerToWorld(0.0D, 0.10000000149011612D, 0.0D, GravityChangerAPI.getAimRotation(owner))
        );
        ((ThrowableProjectile) (Object) this).setPos(pos.x, pos.y, pos.z);
    }
    
    @ModifyReturnValue(method = "getGravity", at = @At("RETURN"))
    private float multiplyGravity(float original) {
        return original * (float) GravityChangerAPI.getGravityStrength(((Entity) (Object) this));
    }
}
