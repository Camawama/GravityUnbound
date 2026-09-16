package net.camacraft.gravityunbound.compat;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;

/**
 * Hand-off between the two VMod physgun mixins (mixin classes cannot refer
 * to each other's members): the player whose physgun state VMod is
 * processing on the current thread, recorded from the state's getter and
 * read by the rotation composition. See
 * {@code mixin.compat.VModPhysgunRotationMixin}.
 */
public final class VModPhysgunBridge {

    private static final ThreadLocal<ServerPlayer> CURRENT_PLAYER = new ThreadLocal<>();

    public static void setCurrentPlayer(@Nullable ServerPlayer player) {
        CURRENT_PLAYER.set(player);
    }

    @Nullable
    public static ServerPlayer currentPlayer() {
        return CURRENT_PLAYER.get();
    }

    private VModPhysgunBridge() {}
}
