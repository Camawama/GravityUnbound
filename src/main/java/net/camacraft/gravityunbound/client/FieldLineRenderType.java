package net.camacraft.gravityunbound.client;

import java.util.OptionalDouble;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;

/**
 * The field visuals' line render type: vanilla's {@code RenderType.lines()} in every state but the
 * output target. Vanilla's lines write to the item-entity target, which only "Fabulous!" graphics
 * composites, and only at a fixed point of the level render; a level-stage renderer drawing into it
 * outside the entity pass can be dropped. This one draws straight into the main target, so the
 * visuals show in every graphics mode at the stage they are drawn.
 */
final class FieldLineRenderType extends RenderType {
    static final RenderType LINES = create("gravityunbound_field_lines",
        DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1024, false, false,
        CompositeState.builder()
            .setShaderState(RENDERTYPE_LINES_SHADER)
            .setLineState(new LineStateShard(OptionalDouble.empty()))
            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setOutputState(MAIN_TARGET)
            .setWriteMaskState(COLOR_DEPTH_WRITE)
            .setCullState(NO_CULL)
            .createCompositeState(false));

    private FieldLineRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                                boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }
}
