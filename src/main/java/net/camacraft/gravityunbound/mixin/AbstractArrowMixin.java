package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin extends Entity {
    
    public AbstractArrowMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    
    
    // Vanilla ends tick() with: vec34 = getDeltaMovement(); setDeltaMovement(vec34.x, vec34.y - 0.05, vec34.z)
    // (the constant scaled by the ModifyConstant below). Re-aim that world-down
    // pull along the CONTINUOUS field vector (cardinal fallback): orbits around
    // a core curve smoothly instead of stepping through the six cardinal pulls.
    // Wraps the gravity write itself: the earlier form modified the velocity
    // local at EVERY store - including the one the position is integrated
    // with - so a rotated-gravity arrow drifted world-up 0.05 blocks a tick
    // (1 m/s) regardless of its velocity, and led its own acceleration by a tick.
    @WrapOperation(
        method = "Lnet/minecraft/world/entity/projectile/AbstractArrow;tick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;setDeltaMovement(DDD)V"
        )
    )
    private void gravityunbound$gravityAlongField(AbstractArrow self, double x, double y, double z, Operation<Void> original) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(self);
        if (gravityDirection == Direction.DOWN) {
            original.call(self, x, y, z);
            return;
        }
        double g = 0.05000000074505806D * GravityChangerAPI.getGravityStrength(self);
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
    private void inject_init_repositionForRotatedOwner(EntityType<? extends AbstractArrow> type, LivingEntity owner, Level level, CallbackInfo ci) {
        if (GravityChangerAPI.isAimDefault(owner)) {
            return;
        }

        Vec3 pos = owner.getEyePosition().subtract(
            RotationUtil.vecPlayerToWorld(0.0D, 0.10000000149011612D, 0.0D, GravityChangerAPI.getAimRotation(owner))
        );
        this.setPos(pos.x, pos.y, pos.z);
    }
    
    @ModifyConstant(method = "Lnet/minecraft/world/entity/projectile/AbstractArrow;tick()V", constant = @Constant(doubleValue = 0.05000000074505806))
    private double multiplyGravity(double constant) {
        return constant * GravityChangerAPI.getGravityStrength(this);
    }
}
