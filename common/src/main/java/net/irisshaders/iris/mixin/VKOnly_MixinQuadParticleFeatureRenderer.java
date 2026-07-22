package net.irisshaders.iris.mixin;

import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Prevents the normal particle target from being written during entity shadow replay. */
@Mixin(QuadParticleFeatureRenderer.class)
public class VKOnly_MixinQuadParticleFeatureRenderer {
	@Inject(method = "executeGroup", at = @At("HEAD"), cancellable = true)
	private void iris$skipParticlesDuringShadow(
		FeatureFrameContext context,
		int groupIndex,
		List<? extends SubmitNode> submits,
		boolean strictlyOrdered,
		CallbackInfo ci
	) {
		if (IrisVulkan.getActiveShadowRenderState() != null) {
			ci.cancel();
			return;
		}
		IrisVulkan.setRenderPhase(WorldRenderingPhase.PARTICLES);
	}

	@Inject(method = "executeGroup", at = @At("RETURN"))
	private void iris$endParticlePhase(
		FeatureFrameContext context,
		int groupIndex,
		List<? extends SubmitNode> submits,
		boolean strictlyOrdered,
		CallbackInfo ci
	) {
		IrisVulkan.clearRenderPhase(WorldRenderingPhase.PARTICLES);
	}
}
