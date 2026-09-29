package net.camacraft.gravityunbound.gametest;

import java.util.Collection;
import java.util.List;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Vanilla Backport's happy ghast under this mod's gravity. Generated only
 * when Vanilla Backport is installed (in dev: {@code -PvanillaBackport}),
 * so a plain run has none of these tests.
 *
 * <p>{@code happyghastsyncsthroughgravityhooks}: Vanilla Backport syncs the
 * ghast through its own copy of {@code ServerEntity.sendChanges}, which
 * skips this mod's hooks; {@code VanillaBackportServerEntityMixin} hands it
 * back to the vanilla method inside a gravity frame. Read off the ghast's
 * {@code ServerEntity}: the last velocity it sent must be the WORLD-space
 * motion (only this mod's hook converts it), and while the ghast holds
 * still its precise-position teleports must still go out (teleport delay
 * reset every update).
 *
 * <p>{@code mobstandsonstillhappyghastunderrotatedgravity}: the audit's
 * "fall through the happy ghast" case. A still happy ghast is solid
 * ({@code canBeCollidedWith}); a pig under gravity EAST falls onto its west
 * face and must stay there.
 */
@GameTestHolder("gravityunbound")
public class VanillaBackportGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("GravityUnbound/test");
    private static final String TEMPLATE = "gravityunbound:empty20";

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
    }

    @GameTestGenerator
    public static Collection<TestFunction> vanillaBackportTests() {
        if (!ModList.get().isLoaded("vanillabackport")) {
            return List.of();
        }
        return List.of(
            new TestFunction(EntityObstacleGameTests.BATCH, "happyghastsyncsthroughgravityhooks", TEMPLATE, 200, 0L, true,
                VanillaBackportGameTests::happyGhastSyncsThroughGravityHooks),
            new TestFunction(EntityObstacleGameTests.BATCH, "mobstandsonstillhappyghastunderrotatedgravity", TEMPLATE, 240, 0L, true,
                VanillaBackportGameTests::mobStandsOnStillHappyGhastUnderRotatedGravity)
        );
    }

    @Nullable
    private static EntityType<?> happyGhastType() {
        for (var entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            if (entry.getKey().location().getPath().equals("happy_ghast")) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Nullable
    private static Mob spawnGhast(GameTestHelper helper, Vec3 at) {
        EntityType<?> type = happyGhastType();
        if (type == null) {
            helper.fail("Vanilla Backport is loaded but registers no happy_ghast entity type");
            return null;
        }
        Entity spawned = helper.spawn(type, at);
        if (!(spawned instanceof Mob ghast)) {
            helper.fail("happy_ghast is not a Mob: " + spawned);
            return null;
        }
        ghast.setNoAi(true);
        return ghast;
    }

    /** Vanilla Backport's still timeout ("still_timeout" in its save data): solid, holding still, precise sync. */
    private static void holdStill(Mob ghast) {
        CompoundTag tag = ghast.saveWithoutId(new CompoundTag());
        tag.putInt("still_timeout", 100000);
        ghast.load(tag);
    }

    /** The ghast's tracker entry, read through the SRG names (remapped in dev by the helper). */
    private static ServerEntity serverEntity(GameTestHelper helper, Entity entity) {
        ChunkMap chunkMap = helper.getLevel().getChunkSource().chunkMap;
        it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> tracked =
            ObfuscationReflectionHelper.getPrivateValue(ChunkMap.class, chunkMap, "f_140150_");
        Object trackedEntity = tracked == null ? null : tracked.get(entity.getId());
        if (trackedEntity == null) {
            throw new IllegalStateException("entity is not tracked: " + entity);
        }
        try {
            return (ServerEntity) ObfuscationReflectionHelper.findField(trackedEntity.getClass(), "f_140471_")
                .get(trackedEntity);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Vec3 lastSentVelocity(ServerEntity serverEntity) {
        return ObfuscationReflectionHelper.getPrivateValue(ServerEntity.class, serverEntity, "f_8520_");
    }

    private static int teleportDelay(ServerEntity serverEntity) {
        Integer delay = ObfuscationReflectionHelper.getPrivateValue(ServerEntity.class, serverEntity, "f_8522_");
        return delay == null ? -1 : delay;
    }

    static void happyGhastSyncsThroughGravityHooks(GameTestHelper helper) {
        Mob ghast = spawnGhast(helper, new Vec3(10.0, 6.0, 10.0));
        if (ghast == null) {
            return;
        }
        helper.runAfterDelay(1, () -> GravityChangerAPI.setBaseGravityDirection(ghast, Direction.EAST));

        // MOVING: a LOCAL velocity along the frame's up, every tick. The
        // tracker reads it at the start of the next tick.
        Vec3 local = new Vec3(0.0, 0.1, 0.0);
        Vec3[] expectedWorld = new Vec3[1];
        for (int t = 40; t <= 60; t++) {
            helper.runAfterDelay(t, () -> {
                ghast.setDeltaMovement(local);
                expectedWorld[0] = GravityChangerAPI.getWorldVelocity(ghast);
            });
        }
        helper.runAfterDelay(61, () -> {
            Vec3 up = GravityChangerAPI.getUpVector(ghast);
            Vec3 sent = lastSentVelocity(serverEntity(helper, ghast));
            LOGGER.info("[GravityUnbound/test] happyghast moving: up={} local={} world={} sent={}",
                fmt(up), fmt(local), fmt(expectedWorld[0]), fmt(sent));
            if (up.x > -0.9) {
                helper.fail("the ghast's frame never turned to gravity EAST: up=" + fmt(up));
                return;
            }
            if (sent.distanceTo(expectedWorld[0]) > 1.0E-3) {
                helper.fail("the ghast's velocity packet is not world-space (Vanilla Backport's sync ran instead of "
                    + "vanilla's with the gravity hooks): sent=" + fmt(sent) + " world=" + fmt(expectedWorld[0])
                    + " local=" + fmt(local));
                return;
            }

            // STILL: Vanilla Backport's precise-position flag must keep
            // sending absolute teleports on the vanilla path (each resets the
            // teleport delay; delta updates count it up every tick)
            holdStill(ghast);
        });

        for (int t = 70; t <= 100; t += 10) {
            final int tick = t;
            helper.runAfterDelay(t, () -> LOGGER.info("[GravityUnbound/test] happyghast still t={} teleportDelay={} sent={}",
                tick, teleportDelay(serverEntity(helper, ghast)), fmt(lastSentVelocity(serverEntity(helper, ghast)))));
        }

        helper.runAfterDelay(110, () -> {
            int delay = teleportDelay(serverEntity(helper, ghast));
            if (delay != 0) {
                helper.fail("a still happy ghast in a gravity frame lost its precise-position sync: teleportDelay="
                    + delay + " (0 when every update is a teleport)");
                return;
            }
            if (GravityChangerAPI.getUpVector(ghast).x > -0.9) {
                helper.fail("the ghast left its gravity frame during the test");
                return;
            }
            helper.succeed();
        });
    }

    static void mobStandsOnStillHappyGhastUnderRotatedGravity(GameTestHelper helper) {
        // box x 10..14, y 4..8, z 8..12
        Mob ghast = spawnGhast(helper, new Vec3(12.0, 4.0, 10.0));
        if (ghast == null) {
            return;
        }
        helper.runAfterDelay(1, () -> holdStill(ghast));
        // what a pig passing through the ghast lands on instead (x = 17)
        for (int y = 3; y <= 9; y++) {
            for (int z = 6; z <= 13; z++) {
                helper.setBlock(new BlockPos(17, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));
        Vec3 park = new Vec3(7.0, 6.0, 10.0);
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, park);
        Vec3 absolute = helper.absoluteVec(park);
        for (int t = 1; t <= 40; t++) {
            final int tick = t;
            helper.runAfterDelay(t, () -> {
                if (tick == 1) {
                    GravityChangerAPI.setBaseGravityDirection(pig, Direction.EAST);
                }
                pig.setPos(absolute.x, absolute.y, absolute.z);
                pig.setDeltaMovement(Vec3.ZERO);
                pig.resetFallDistance();
            });
        }

        for (int t = 40; t <= 140; t += 20) {
            final int tick = t;
            helper.runAfterDelay(t, () -> LOGGER.info(
                "[GravityUnbound/test] ghastplatform t={} pig rel={} onGround={} up={} ghast rel={} solid={}",
                tick, fmt(pig.position().subtract(origin)), pig.onGround(), fmt(GravityChangerAPI.getUpVector(pig)),
                fmt(ghast.position().subtract(origin)), ghast.canBeCollidedWith()));
        }

        helper.runAfterDelay(150, () -> {
            Vec3 rel = pig.position().subtract(origin);
            if (GravityChangerAPI.getUpVector(pig).x > -0.9) {
                helper.fail("the pig's frame never turned to gravity EAST");
                return;
            }
            if (!ghast.canBeCollidedWith()) {
                helper.fail("the still happy ghast is not solid: the setup is wrong, not the collider");
                return;
            }
            if (rel.x > 10.5) {
                helper.fail("the pig fell THROUGH the happy ghast: rel=" + fmt(rel) + " (its west face is x = 10)");
                return;
            }
            if (rel.x < 9.8 || !pig.onGround()) {
                helper.fail("the pig is not standing on the ghast's west face: rel=" + fmt(rel)
                    + " onGround=" + pig.onGround());
                return;
            }
            helper.succeed();
        });
    }
}
