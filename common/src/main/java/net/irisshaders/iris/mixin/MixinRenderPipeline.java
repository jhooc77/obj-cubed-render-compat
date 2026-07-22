package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(RenderPipeline.class)
public class MixinRenderPipeline {
	@Inject(method = "getVertexFormat", at = @At("RETURN"), cancellable = true)
	private void iris$change(CallbackInfoReturnable<VertexFormat> cir) {
		RenderPipeline thiss = (RenderPipeline) (Object) this;
		if (thiss == RenderPipelines.CRUMBLING
			&& iris$objCubedCompatEnabled()) {
			cir.setReturnValue(IrisVertexFormats.CRUMBLING);
			return;
		}
		if (Iris.isPackInUseQuick() && ImmediateState.renderWithExtendedVertexFormat && ImmediateState.isRenderingLevel) {
			VertexFormat vf = cir.getReturnValue();
			if (vf == DefaultVertexFormat.BLOCK) {
				cir.setReturnValue(IrisVertexFormats.TERRAIN);
			} else if (vf == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
				cir.setReturnValue(IrisVertexFormats.GLYPH);
			} else if (vf == DefaultVertexFormat.ENTITY) {
				cir.setReturnValue(IrisVertexFormats.ENTITY);
			}
		}
	}

	@Inject(method = "getSamplers", at = @At("RETURN"), cancellable = true)
	private void iris$addObjCubedCrumblingAtlas(CallbackInfoReturnable<List<String>> cir) {
		if ((RenderPipeline) (Object) this != RenderPipelines.CRUMBLING || !iris$objCubedCompatEnabled()
			|| cir.getReturnValue().contains("Sampler1")) {
			return;
		}

		List<String> samplers = new ArrayList<>(cir.getReturnValue());
		samplers.add("Sampler1");
		cir.setReturnValue(List.copyOf(samplers));
	}

	private static boolean iris$objCubedCompatEnabled() {
		return Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"));
	}
}
