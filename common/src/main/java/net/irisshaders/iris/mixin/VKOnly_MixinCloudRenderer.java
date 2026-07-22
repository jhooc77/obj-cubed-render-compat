package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.pipeline.VulkanEntityRenderState;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Routes the dedicated cloud draw through gbuffers_clouds on the Vulkan backend. */
@Mixin(CloudRenderer.class)
public class VKOnly_MixinCloudRenderer {
	@Unique private @Nullable RenderPipeline iris$currentPipeline;

	@Inject(method = "render", at = @At("HEAD"))
	private void iris$beginClouds(int color, CloudStatus cloudStatus, float cloudHeight, int renderDistance,
		Vec3 cameraPosition, long gameTime, float tickDelta, CallbackInfo ci) {
		iris$currentPipeline = cloudStatus == CloudStatus.FANCY ? RenderPipelines.CLOUDS : RenderPipelines.FLAT_CLOUDS;
		IrisVulkan.setRenderPhase(WorldRenderingPhase.CLOUDS);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void iris$endClouds(int color, CloudStatus cloudStatus, float cloudHeight, int renderDistance,
		Vec3 cameraPosition, long gameTime, float tickDelta, CallbackInfo ci) {
		iris$currentPipeline = null;
		IrisVulkan.clearRenderPhase(WorldRenderingPhase.CLOUDS);
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")
	)
	private RenderPass iris$createCloudPass(CommandEncoder encoder, Supplier<String> label, GpuTextureView color,
		Optional<Vector4fc> clearColor, @Nullable GpuTextureView depth, OptionalDouble clearDepth) {
		RenderPipeline pipeline = iris$currentPipeline;
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		return pipeline == null || state == null || !state.replaces(pipeline)
			? encoder.createRenderPass(label, color, clearColor, depth, clearDepth)
			: state.createRenderPass(encoder, label, color, depth, clearDepth, pipeline);
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void iris$setCloudPipeline(RenderPass pass, RenderPipeline original) {
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (state != null && state.replaces(original)) state.setPipeline(pass, original); else pass.setPipeline(original);
	}
}
