package net.camacraft.gravityunbound.client;

import net.camacraft.camafy.compat.genesis.Celestials;
import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.celestial.CelestialGravity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forgets the server's word on the local player's departure ({@code CelestialDeparturePacket}) once the player isn't
 * in VS Genesis's space any more (or has left the world), so an old one never holds a planet off on the next trip; the
 * server tells the client again every time its player comes into space.
 */
@Mod.EventBusSubscriber(modid = GravityAPI.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CelestialClientTracker {
    private CelestialClientTracker() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !Celestials.isSpace(mc.level)) {
            CelestialGravity.forgetClientDeparture();
        }
    }
}
