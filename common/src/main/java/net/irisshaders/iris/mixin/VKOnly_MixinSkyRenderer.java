package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.pipeline.VulkanEntityRenderState;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SkyRenderer;
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

/** Routes all vanilla sky primitives through their shader-pack Vulkan programs. */
@Mixin(SkyRenderer.class)
public class VKOnly_MixinSkyRenderer {
	@Unique private @Nullable RenderPipeline iris$currentPipeline;

	@Inject(method = {"renderSkyDisc", "renderDarkDisc"}, at = @At("HEAD"))
	private void iris$sky(CallbackInfo ci) { iris$currentPipeline = RenderPipelines.SKY; }

	@Inject(method = "renderSkyDisc", at = @At("HEAD"))
	private void iris$skyPhase(CallbackInfo ci) { IrisVulkan.setRenderPhase(WorldRenderingPhase.SKY); }

	@Inject(method = "renderDarkDisc", at = @At("HEAD"))
	private void iris$voidPhase(CallbackInfo ci) { IrisVulkan.setRenderPhase(WorldRenderingPhase.VOID); }

	@Inject(method = {"renderSun", "renderMoon", "renderEndFlash"}, at = @At("HEAD"))
	private void iris$celestial(CallbackInfo ci) { iris$currentPipeline = RenderPipelines.CELESTIAL; }

	@Inject(method = "renderSun", at = @At("HEAD"))
	private void iris$sunPhase(CallbackInfo ci) { IrisVulkan.setRenderPhase(WorldRenderingPhase.SUN); }

	@Inject(method = "renderMoon", at = @At("HEAD"))
	private void iris$moonPhase(CallbackInfo ci) { IrisVulkan.setRenderPhase(WorldRenderingPhase.MOON); }

	@Inject(method = "renderEndFlash", at = @At("HEAD"))
	private void iris$endFlashPhase(CallbackInfo ci) { IrisVulkan.setRenderPhase(WorldRenderingPhase.CUSTOM_SKY); }

	@Inject(method = "renderStars", at = @At("HEAD"))
	private void iris$stars(CallbackInfo ci) {
		iris$currentPipeline = RenderPipelines.STARS;
		IrisVulkan.setRenderPhase(WorldRenderingPhase.STARS);
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"))
	private void iris$sunrise(CallbackInfo ci) {
		iris$currentPipeline = RenderPipelines.SUNRISE_SUNSET;
		IrisVulkan.setRenderPhase(WorldRenderingPhase.SUNSET);
	}

	@Inject(method = "renderEndSky", at = @At("HEAD"))
	private void iris$endSky(CallbackInfo ci) {
		iris$currentPipeline = RenderPipelines.END_SKY;
		IrisVulkan.setRenderPhase(WorldRenderingPhase.SKY);
	}

	@Inject(method = {"renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon", "renderEndFlash", "renderStars", "renderSunriseAndSunset", "renderEndSky"}, at = @At("RETURN"))
	private void iris$clear(CallbackInfo ci) {
		iris$currentPipeline = null;
		IrisVulkan.setRenderPhase(WorldRenderingPhase.NONE);
	}

	@Redirect(
		method = {"renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon", "renderEndFlash", "renderStars", "renderSunriseAndSunset", "renderEndSky"},
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")
	)
	private RenderPass iris$createPass(CommandEncoder encoder, Supplier<String> label, GpuTextureView color,
		Optional<Vector4fc> clearColor, @Nullable GpuTextureView depth, OptionalDouble clearDepth) {
		RenderPipeline pipeline = iris$currentPipeline;
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		return pipeline == null || state == null || !state.replaces(pipeline)
			? encoder.createRenderPass(label, color, clearColor, depth, clearDepth)
			: state.createRenderPass(encoder, label, color, depth, clearDepth, pipeline);
	}

	@Redirect(
		method = {"renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon", "renderEndFlash", "renderStars", "renderSunriseAndSunset", "renderEndSky"},
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void iris$setPipeline(RenderPass pass, RenderPipeline original) {
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (state != null && state.replaces(original)) state.setPipeline(pass, original); else pass.setPipeline(original);
	}

	@Redirect(
		method = {"renderSun", "renderMoon", "renderEndFlash", "renderEndSky"},
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V")
	)
	private void iris$bindTexture(RenderPass pass, String name, GpuTextureView texture, GpuSampler sampler) {
		RenderPipeline pipeline = iris$currentPipeline;
		VulkanEntityRenderState state = IrisVulkan.getEntityRenderState();
		if (pipeline != null && state != null && state.replaces(pipeline)) state.bindTexture(pass, pipeline, name, texture, sampler);
		else pass.bindTexture(name, texture, sampler);
	}
}
