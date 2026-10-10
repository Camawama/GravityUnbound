package net.camacraft.gravityunbound.gametest;

import net.camacraft.gravityunbound.util.ShipFluidWake;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

import java.lang.reflect.Proxy;

/**
 * When a ship-mounted field wakes the world's liquid under it ({@link ShipFluidWake}): every period while the field
 * moves, turns or is changed, for a few periods after it last did, and then only now and then (it woke every period
 * for as long as it existed, which re-ticked every liquid block in its reach: the owner's server profile of
 * 2026-10-04). The ship is a stand-in that answers only its turn, which is all the rule reads of it.
 */
@GameTestHolder("gravityunbound")
@PrefixGameTestTemplate(false)
public class ShipFluidWakeGameTests {
    private static final String EMPTY = "empty20";

    private static Ship shipTurned(Quaterniond rotation) {
        ShipTransform transform = (ShipTransform) Proxy.newProxyInstance(ShipTransform.class.getClassLoader(),
            new Class<?>[] {ShipTransform.class}, (proxy, method, args) -> {
                if (method.getName().equals("getShipToWorldRotation")) {
                    return rotation;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        return (Ship) Proxy.newProxyInstance(Ship.class.getClassLoader(), new Class<?>[] {Ship.class}, (proxy, method, args) -> {
            if (method.getName().equals("getTransform")) {
                return transform;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void aFieldAtRestStopsWakingTheLiquidUnderIt(GameTestHelper helper) {
        ShipFluidWake wake = new ShipFluidWake();
        Ship level = shipTurned(new Quaterniond());
        AABB box = new AABB(100.0, 60.0, 100.0, 117.0, 77.0, 117.0);
        // the period it arrives and the periods right after: woken every time, as ever
        for (int period = 0; period <= ShipFluidWake.SETTLE_WAKES; period++) {
            helper.assertTrue(wake.due(level, box, 7), "period " + period + " after the field arrived wasn't woken");
        }
        // at rest: one period in REST_EVERY (trembling by a thousandth of a block is rest)
        int woken = 0;
        for (int period = 0; period < 4 * ShipFluidWake.REST_EVERY; period++) {
            if (wake.due(level, box.move(0.001 * (period % 2), 0.0, 0.0), 7)) {
                woken++;
            }
        }
        helper.assertTrue(woken == 4, "a field at rest was woken " + woken + " times in " + 4 * ShipFluidWake.REST_EVERY
            + " periods, not 4");
        // anything that changes what the field covers or does wakes it at once, and for the periods after
        helper.assertTrue(wake.due(level, box.move(1.0, 0.0, 0.0), 7), "a field that moved a block wasn't woken at once");
        helper.assertTrue(wake.due(level, box.move(1.0, 0.0, 0.0), 7), "the period after a move wasn't woken");
        Ship turned = shipTurned(new Quaterniond().rotateY(Math.toRadians(2.0)));
        helper.assertTrue(wake.due(turned, box.move(1.0, 0.0, 0.0), 7), "a field that turned two degrees wasn't woken at once");
        for (int period = 0; period < ShipFluidWake.SETTLE_WAKES + 3; period++) {
            wake.due(turned, box.move(1.0, 0.0, 0.0), 7);
        }
        helper.assertTrue(!wake.due(turned, box.move(1.0, 0.0, 0.0), 7), "a field long at rest was still woken every period");
        helper.assertTrue(wake.due(turned, box.move(1.0, 0.0, 0.0), 8), "a field whose settings changed wasn't woken at once");
        helper.succeed();
    }
}
