package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.pipeline.VulkanEntityRenderState;
import net.irisshaders.iris.vulkan.pipeline.VulkanShadowRenderState;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.List;
import java.util.function.Supplier;

@Mixin(PreparedRenderType.class)
public class VKOnly_MixinPreparedRenderType {
	@Shadow @Final private RenderPipeline pipeline;
	@Shadow @Final private List<PreparedRenderType.Texture> textures;

	@Inject(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At("HEAD"), cancellable = true
	)
	private void iris$skipUnsupportedShadowFeature(
		GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType,
		int baseVertex, int firstIndex, int indexCount, CallbackInfo ci
	) {
		if (IrisVulkan.getRenderPhase() == WorldRenderingPhase.NONE) {
			IrisVulkan.setRenderPhase(WorldRenderingPhase.ENTITIES);
		}
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null && !shadow.replacesEntity(pipeline)) ci.cancel();
	}

	@Inject(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At("RETURN")
	)
	private void iris$endEntityFeature(
		GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType,
		int baseVertex, int firstIndex, int indexCount, CallbackInfo ci
	) {
		IrisVulkan.clearRenderPhase(WorldRenderingPhase.ENTITIES);
	}

	@Redirect(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")
	)
	private RenderPass iris$createEntityRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		GpuTextureView colorTexture,
		Optional<Vector4fc> clearColor,
		@Nullable GpuTextureView depthTexture,
		OptionalDouble clearDepth
	) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) return shadow.createEntityRenderPass(encoder, label, pipeline);
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (state == null || !state.replaces(pipeline)) {
			return encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth);
		}
		return state.createRenderPass(encoder, label, colorTexture, depthTexture, clearDepth, pipeline, textures);
	}

	@Redirect(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void iris$setEntityPipeline(RenderPass renderPass, RenderPipeline original) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) {
			shadow.setEntityPipeline(renderPass, original);
			return;
		}
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (state == null || !state.replaces(original)) {
			renderPass.setPipeline(original);
		} else {
			state.setPipeline(renderPass, original);
		}
	}

	@Redirect(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V")
	)
	private void iris$bindEntityTexture(RenderPass renderPass, String name, GpuTextureView textureView, GpuSampler sampler) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) {
			shadow.bindEntityTexture(renderPass, pipeline, name, textureView, sampler);
			return;
		}
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (state == null || !state.replaces(pipeline)) {
			renderPass.bindTexture(name, textureView, sampler);
		} else {
			state.bindTexture(renderPass, pipeline, name, textureView, sampler);
		}
	}

	@Redirect(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V")
	)
	private void iris$setEntityUniform(RenderPass pass, String name, GpuBufferSlice value) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		pass.setUniform(name, shadow != null && name.equals("DynamicTransforms")
			? shadow.shadowDynamicTransforms(value) : value);
	}
}
