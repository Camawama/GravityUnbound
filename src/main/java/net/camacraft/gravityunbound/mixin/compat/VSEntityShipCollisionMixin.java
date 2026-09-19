package net.camacraft.gravityunbound.mixin.compat;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Valkyrien Skies wraps the {@code Entity.collide} CALL inside
 * {@code Entity.move} and applies its own entity-vs-ship collision to the
 * result — using the entity's stored AABB against ship polygons.
 *
 * For capsule players that stored AABB is only a loose world-aligned ENVELOPE
 * of the rotated capsule; the capsule itself already collides with ships
 * EXACTLY, against the ship's real blocks in shipyard space. Letting VS
 * re-collide the envelope box fought the capsule every tick: the envelope's
 * corners hit ship geometry the spheres were nowhere near, holding players
 * afloat above plating on rotated ships and causing constant stutter.
 *
 * Skip VS's adjustment entirely while the capsule owns collision.
 */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public abstract class VSEntityShipCollisionMixin {

    @Inject(
        method = "adjustEntityMovementForShipCollisions",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void gravityunbound$skipForCapsulePlayers(
        Entity entity, Vec3 movement, AABB entityBoundingBox, Level world,
        CallbackInfoReturnable<Vec3> cir
    ) {
        if (entity == null) {
            return;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp != null && comp.useCapsuleCollision()) {
            cir.setReturnValue(movement);
        }
    }

    /**
     * Lets an entity standing on the WORLD step up onto a ship.
     *
     * <h2>The defect</h2>
     *
     * <p>Valkyrien Skies does implement a faithful, vanilla-shaped four-phase
     * step -- up, across, down, keep-whichever-went-further -- inside
     * {@code org.valkyrienskies.core.impl.shadow.Dk}. But it is gated on the
     * entity ALREADY RESTING ON SHIP GEOMETRY. Before stepping, it resolves a
     * fixed 0.2-block downward move against the ship's polygons and requires the
     * box, translated by that resolved move, to still touch one of them.
     *
     * <p>An entity standing on ordinary world ground beside a ship fails that:
     * nothing of the ship is underneath, so the probe resolves the full -0.2 and
     * the resulting foot wafer sits below the ship's bottom face, touching no
     * polygon. The step branch is abandoned and only a plain block-and-slide
     * resolve runs.
     *
     * <p>Vanilla cannot rescue it either. VS wraps the {@code Entity.collide}
     * CALL inside {@code Entity.move}, so it resolves the ship FIRST and hands
     * vanilla an already-blocked vector; vanilla's own step gate is
     * {@code pVec.x != vec3.x} on that adjusted vector, which is false once VS
     * has zeroed the horizontal component.
     *
     * <p>So from world ground onto a ship, NEITHER collision system ever
     * attempts a step -- for a vehicle or for a player on foot. Reported against
     * MKWMobility with the cleanest possible test: one block placed in the world
     * and one identical block made into a ship. The world block is stepped; the
     * ship block is collided with.
     *
     * <h2>The repair</h2>
     *
     * <p>Run vanilla's own step algorithm against ship polygons, by re-entering
     * VS's resolver for each phase, exactly as {@code Entity.collide} does
     * against block shapes. The gate is the entity's REAL ground state rather
     * than a ship-only foot test, which is the one thing the upstream version
     * gets wrong.
     *
     * <p>The re-entry latch matters: each phase calls the very method this is
     * injected into, and without it the first phase would recurse.
     */
    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<Boolean> gravityunbound$stepping =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
        method = "adjustEntityMovementForShipCollisions",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void gravityunbound$stepOntoShipsFromWorldGround(
        Entity entity, Vec3 movement, AABB entityBoundingBox, Level world,
        CallbackInfoReturnable<Vec3> cir
    ) {
        Vec3 adjusted = cir.getReturnValue();
        if (entity == null || adjusted == null || gravityunbound$stepping.get()) {
            return;
        }

        // Only when a ship actually stopped us, and only from the ground. Both
        // match vanilla's own preconditions for a step.
        boolean blocked = adjusted.x != movement.x || adjusted.z != movement.z;
        double step = entity.maxUpStep();
        if (!blocked || step <= 0.0 || !entity.onGround()) {
            return;
        }

        gravityunbound$stepping.set(Boolean.TRUE);
        try {
            Vec3 up = EntityShipCollisionUtils.INSTANCE.adjustEntityMovementForShipCollisions(
                    entity, new Vec3(0.0, step, 0.0), entityBoundingBox, world);
            if (up.y <= 0.0) {
                return;
            }

            AABB raised = entityBoundingBox.move(up);
            Vec3 across = EntityShipCollisionUtils.INSTANCE
                    .adjustEntityMovementForShipCollisions(
                            entity, new Vec3(movement.x, 0.0, movement.z), raised, world);

            AABB moved = raised.move(across);
            Vec3 down = EntityShipCollisionUtils.INSTANCE
                    .adjustEntityMovementForShipCollisions(
                            entity,
                            new Vec3(0.0, -up.y + Math.min(movement.y, 0.0), 0.0),
                            moved, world);

            Vec3 stepped = up.add(across).add(down);

            // Whichever travelled further along the ground, which is vanilla's
            // own tie-break and stops a step being taken when it gains nothing.
            if (stepped.horizontalDistanceSqr() > adjusted.horizontalDistanceSqr()) {
                cir.setReturnValue(stepped);
            }
        } finally {
            gravityunbound$stepping.set(Boolean.FALSE);
        }
    }
}
