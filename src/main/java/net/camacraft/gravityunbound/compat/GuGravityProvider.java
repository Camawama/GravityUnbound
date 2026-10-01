package net.camacraft.gravityunbound.compat;

import net.camacraft.camafy.compat.gravity.FrameKind;
import net.camacraft.camafy.compat.gravity.GravityFrame;
import net.camacraft.camafy.compat.gravity.GravityProvider;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.GravityFieldLookup;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.enchantment.SurfaceClingEnchantment;
import net.camacraft.gravityunbound.util.DimensionGravity;
import net.camacraft.gravityunbound.util.RotationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Gravity Unbound's answers to Camafy's gravity bridge, so Full Stop, WallJump and MKW read
 * gravity through Camafy without naming this mod. Registered once from GravityAPI's constructor:
 * {@code net.camacraft.camafy.compat.gravity.Gravity.registerProvider(new GuGravityProvider());}
 *
 * <p>Each method resolves the capability once and reads the impl directly, instead of chaining
 * GravityChangerAPI calls that would each walk the capability dispatcher again.
 */
public final class GuGravityProvider implements GravityProvider {
    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

    @Override
    public String name() {
        return "Gravity Unbound";
    }

    @Override
    public boolean isDefault(Entity entity, FrameKind kind) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        return comp == null || isDefault(comp, kind);
    }

    private static boolean isDefault(GravityCapabilityImpl comp, FrameKind kind) {
        return switch (kind) {
            case PHYSICS -> comp.isDefault();          // GravityChangerAPI.isGravityDefault
            case MOVEMENT -> comp.isVisuallyDefault(); // GravityChangerAPI.isAimDefault
            case RENDER -> comp.isRenderDefault();     // the CameraMixin / EntityRenderDispatcherMixin gate
        };
    }

    @Override
    public GravityFrame frame(Entity entity, FrameKind kind, float partialTick) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || isDefault(comp, kind)) {
            return GravityFrame.DEFAULT;
        }
        // GravityFrame.of copies, so passing the shared rotation instances is safe.
        return switch (kind) {
            case PHYSICS -> GravityFrame.of(comp.getCurrentRotation());      // getGravityRotation
            case MOVEMENT -> GravityFrame.of(comp.getVisualRotation());      // getAimRotation
            case RENDER -> GravityFrame.of(comp.getRenderRotation(partialTick));
        };
    }

    @Override
    public Vec3 up(Entity entity) {
        // GravityChangerAPI.getUpVector, without the second lookup
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        return comp == null || comp.isVisuallyDefault() ? WORLD_UP : comp.getUpVector();
    }

    @Override
    public Vec3 pullDirection(Entity entity) {
        return GravityChangerAPI.getFieldPullDirection(entity);
    }

    @Override
    public double strength(Entity entity) {
        return GravityChangerAPI.getGravityStrength(entity);
    }

    /**
     * Whether a field pulls this entity although it is flagged no-gravity (how VS Genesis makes
     * its space dimensions weightless), so that Camafy's strength is the field's alone instead of
     * the flag's zero. Everything else a field does already reaches Camafy's composition: the
     * strength through the attribute modifier and GravityMixins, and Ad Astra's gravity vetoed
     * through AdAstraCompat's event answer.
     * <ul>
     * <li>Living: applyFieldPullDeficit supplies the pull, except to riders, swimmers, gliders,
     *     slow fallers and creative flight.</li>
     * <li>Non-living: applyZeroGravityFieldForce pulls them where it runs (VS Genesis's space and
     *     the artificialGravityDimensions list); a mod integrating its own gravity from Camafy's
     *     strength (MKW's karts) is pulled there too.</li>
     * </ul>
     * Counted on the side that computes the entity's gravity: a client's copy of another entity
     * has no field state (see isInField).
     */
    @Override
    public boolean ownsGravity(Entity entity) {
        if (!entity.isNoGravity()) {
            return false;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || !comp.isInField()) {
            return false;
        }
        if (!(entity instanceof LivingEntity living)) {
            return GravityCapabilityImpl.pullsWeightlessThings(entity.level());
        }
        if (entity.isPassenger() || living.isFallFlying() || entity.isInWater() || entity.isInLava()
            || living.hasEffect(MobEffects.SLOW_FALLING)) {
            return false;
        }
        return !(entity instanceof Player player && player.getAbilities().flying);
    }

    /** The forge:entity_gravity modifier carrying the strength (GravityCapabilityImpl.applyGravityStrengthAttribute). */
    @Override
    public UUID strengthModifierId() {
        return GravityCapabilityImpl.strengthModifierId();
    }

    /** The configured multiplier times this dimension's ambient strength (DimensionGravity). */
    @Override
    public double ambientStrength(Level level, Vec3 pos) {
        double multiplier;
        try {
            multiplier = GravityConfig.gravityStrengthMultiplier.get();
        }
        catch (IllegalStateException e) {
            multiplier = 1.0; // config not loaded yet
        }
        return multiplier * DimensionGravity.strengthFor(level);
    }

    @Override
    public Direction cardinalDown(Entity entity) {
        return GravityChangerAPI.getGravityDirection(entity);
    }

    /**
     * Which way a liquid falls at a block position (Camafy's {@code LiquidGravity}, Flow Fun!):
     * the dominant field there as the fluid hooks already see it ({@link GravityFieldLookup#fluidDownAt}:
     * plating, cores and normalizers in the position's own grid, and ship-mounted fields over the
     * world's liquids when the config allows), DOWN outside every field or with
     * {@code gravityAffectsFluids} off. Cached per tick, never loads chunks.
     */
    @Override
    public Direction directionAt(Level level, BlockPos pos) {
        return GravityFieldLookup.fluidDownAt(level, pos);
    }

    /**
     * How hard gravity pulls a liquid at a block position: the covering field's own pull (a
     * plating side's {@code gravityAccel} over vanilla's), else the ambient strength.
     */
    @Override
    public double strengthAt(Level level, BlockPos pos) {
        if (GravityFieldLookup.hasFluidFieldAt(level, pos)) {
            return GravityFieldLookup.fluidStrengthAt(level, pos);
        }
        return ambientStrength(level, Vec3.atCenterOf(pos));
    }

    /**
     * EntityMixin's move() transform, gate included: the identity while the frame is visually
     * default (GravityChangerAPI.movementToWorld lacks that gate and would rotate by the sub-0.1
     * degree residual that Entity.move ignores), projectiles in world axes, the original entity
     * convention for a non-player settled on a cardinal (a mirror, not the frame, on UP), and
     * the visual frame otherwise.
     */
    @Override
    public Vec3 movementToWorld(Entity entity, Vec3 movement) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isVisuallyDefault() || entity instanceof Projectile) {
            return movement;
        }
        if (!(entity instanceof Player)) {
            Direction settled = comp.getSettledCardinal();
            if (settled != null) {
                return RotationUtil.vecEntityToWorld(movement, settled);
            }
        }
        return RotationUtil.vecPlayerToWorld(movement, comp.getVisualRotation());
    }

    /** The exact inverse of {@link #movementToWorld}. */
    @Override
    public Vec3 worldToMovement(Entity entity, Vec3 world) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isVisuallyDefault() || entity instanceof Projectile) {
            return world;
        }
        if (!(entity instanceof Player)) {
            Direction settled = comp.getSettledCardinal();
            if (settled != null) {
                return RotationUtil.vecWorldToEntity(world, settled);
            }
        }
        return RotationUtil.vecWorldToPlayer(world, comp.getVisualRotation());
    }

    @Override
    public boolean canChange(Entity entity) {
        return GravityChangerAPI.canChangeGravity(entity);
    }

    /**
     * How long (game ticks) a plating field's last hold still counts: plates refresh the
     * entities in their range every one or two ticks.
     */
    private static final long PLATING_HOLD_TICKS = 2;

    /**
     * Held fast to its surface: standing (grounded) in the primary column of a gravity plating
     * field, which GravityPlatingBlockEntity records on the capability every tick it applies,
     * or clinging to a surface with Surface Cling boots on. Camafy's Gravity.isAnchored; Full
     * Stop then never throws the entity off a ship that stops or swerves under it.
     */
    @Override
    public boolean isAnchored(Entity entity) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || entity.isRemoved()) {
            return false;
        }
        long now = entity.level().getGameTime();
        long sincePlating = now - comp.lastPlatingFieldTick;
        // A vehicle another client drives (an Automobility car on the plating) is placed on the
        // server from its driver's packets, never moved down onto the deck by the server itself, so
        // vanilla's onGround stays false for it; the field it is attached to holds it all the same.
        boolean held = entity.onGround()
            || (!(entity instanceof net.minecraft.world.entity.player.Player) && comp.isShipAttached());
        if (comp.lastPlatingFieldTick != Long.MIN_VALUE && sincePlating >= 0 && sincePlating <= PLATING_HOLD_TICKS
                && held) {
            return true;
        }
        return comp.clingHeldLastTick && entity instanceof LivingEntity living && SurfaceClingEnchantment.isWearing(living);
    }

    /**
     * Inside a field mounted on a ship (plating or a core on it): the capsule registers the ship
     * as ground every tick and updateShipAttachment keeps an airborne rider registered, so VS
     * carries the entity with that ship through jumps. Camafy's Gravity.ridesShipField; Full
     * Stop then leaves VS's carry alone instead of letting go of a jumper.
     */
    @Override
    public boolean ridesShipField(Entity entity) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        return comp != null && !entity.isRemoved() && comp.isInField() && comp.getFieldAnchorShip() != null;
    }

    @Override
    public void sustainHeldSurface(Entity entity) {
        GravityChangerAPI.sustainHeldSurface(entity);
    }

    @Override
    public void setBaseDirection(Entity entity, Direction down) {
        GravityChangerAPI.setBaseGravityDirection(entity, down);
    }

    @Override
    public void reset(Entity entity) {
        GravityChangerAPI.resetGravity(entity);
    }
}
