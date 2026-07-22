package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class VKOnly_MixinGameRenderer {
	@ModifyArg(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
		),
		index = 0
	)
	private Matrix4f iris$normalizeExtractedWorldProjection(Matrix4f projection) {
		return IrisVulkan.isShaderPackDepthMode()
			? net.irisshaders.iris.vulkan.VulkanFrameState.sanitizeExtractedWorldProjection(projection)
			: projection;
	}

	@WrapOperation(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V")
	)
	private void iris$renderShaderpackHand(
		GameRenderer renderer,
		CameraRenderState state,
		float partialTicks,
		Matrix4fc modelView,
		Operation<Void> original
	) {
		// The shader-pack hand was already split into opaque/translucent passes inside
		// LevelRenderer, at the same points used by Iris' OpenGL pipeline.
		if (!IrisVulkan.isShaderPackDepthMode()) {
			original.call(renderer, state, partialTicks, modelView);
		}
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void iris$prepareVulkanShaderpack(DeltaTracker deltaTracker, CallbackInfo ci) {
		IrisVulkan.enterRenderLevel();
		IrisVulkan.ensurePipelineInitialized();
		IrisVulkan.prepareRenderLevel();
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void iris$renderVulkanShaderpackFinal(DeltaTracker deltaTracker, CallbackInfo ci) {
		GameRenderer renderer = (GameRenderer)(Object)this;
		IrisVulkan.renderFinal(
			renderer.mainRenderTarget().getColorTextureView(),
			renderer.mainRenderTarget().getDepthTextureView()
		);
	}
}
