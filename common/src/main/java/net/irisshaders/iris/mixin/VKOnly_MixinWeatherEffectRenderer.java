package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.pipeline.VulkanEntityRenderState;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Routes rain and snow through gbuffers_weather on the Vulkan backend. */
@Mixin(WeatherEffectRenderer.class)
public class VKOnly_MixinWeatherEffectRenderer {
	@Inject(method = "render", at = @At("HEAD"))
	private void iris$beginWeatherPhase(CallbackInfo ci) {
		IrisVulkan.setRenderPhase(WorldRenderingPhase.RAIN_SNOW);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void iris$endWeatherPhase(CallbackInfo ci) {
		IrisVulkan.clearRenderPhase(WorldRenderingPhase.RAIN_SNOW);
	}

	@Unique
	private RenderPipeline iris$weatherPipeline() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()
			? RenderPipelines.WEATHER_DEPTH_WRITE : RenderPipelines.WEATHER_NO_DEPTH_WRITE;
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")
	)
	private RenderPass iris$createPass(CommandEncoder encoder, Supplier<String> label, GpuTextureView color,
		Optional<Vector4fc> clearColor, @Nullable GpuTextureView depth, OptionalDouble clearDepth) {
		RenderPipeline pipeline = iris$weatherPipeline();
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		return state == null || !state.replaces(pipeline)
			? encoder.createRenderPass(label, color, clearColor, depth, clearDepth)
			: state.createRenderPass(encoder, label, color, depth, clearDepth, pipeline);
	}

	@Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void iris$setPipeline(RenderPass pass, RenderPipeline original) {
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (state != null && state.replaces(original)) state.setPipeline(pass, original); else pass.setPipeline(original);
	}

	@Redirect(method = {"render", "renderWeather"}, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
	private void iris$bindTexture(RenderPass pass, String name, GpuTextureView texture, GpuSampler sampler) {
		RenderPipeline pipeline = iris$weatherPipeline();
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (state != null && state.replaces(pipeline)) state.bindTexture(pass, pipeline, name, texture, sampler);
		else pass.bindTexture(name, texture, sampler);
	}
}
