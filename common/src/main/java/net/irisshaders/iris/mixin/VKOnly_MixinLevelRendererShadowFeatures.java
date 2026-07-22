package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.pathways.HandRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replays prepared entity/equipment/OBJ features into Iris' Vulkan shadow map. */
@Mixin(LevelRenderer.class)
public class VKOnly_MixinLevelRendererShadowFeatures {
	@Shadow @Final private LevelRenderState levelRenderState;
	@Unique private final Matrix4f iris$vulkanModelView = new Matrix4f();

	@Inject(method = "render", at = @At("HEAD"))
	private void iris$captureVulkanHandModelView(
		GraphicsResourceAllocator allocator,
		DeltaTracker deltaTracker,
		boolean renderOutline,
		CameraRenderState cameraState,
		Matrix4fc modelView,
		GpuBufferSlice terrainFog,
		Vector4f fogColor,
		boolean shouldRenderSky,
		CallbackInfo ci
	) {
		iris$vulkanModelView.set(modelView);
		// GameRenderer's shader-pack bobbing mixin has finished moving view bobbing
		// out of the projection by the time LevelRenderer receives this matrix. Begin
		// the Vulkan frame here so temporal uniforms use that exact rendered matrix.
		IrisVulkan.beginWorldFrame(
			Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView(),
			modelView
		);
		IrisVulkan.renderShadows((LevelRenderer)(Object)this);
	}

	@Inject(
		method = "lambda$addMainPass$0",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent()V")
	)
	private void iris$runDeferredBeforeTranslucents(CallbackInfo ci) {
		if (!IrisVulkan.isShaderPackDepthMode()) return;
		// Match Iris' OpenGL ordering: opaque hands must exist before depthtex1 is
		// captured and deferred/TAA runs. Rendering the vanilla hand after the entire
		// world made temporal packs erase it and turn its moving edge into a horizontal
		// screen-space streak.
		IrisVulkan.beginHand();
		Minecraft minecraft = Minecraft.getInstance();
		HandRenderer.INSTANCE.renderSolidVulkan(
			iris$vulkanModelView,
			minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true),
			minecraft.gameRenderer.mainCamera(),
			levelRenderState.cameraRenderState,
			minecraft.gameRenderer
		);
		IrisVulkan.beginTranslucents();
	}

	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;")
	)
	private void iris$renderTranslucentVulkanHand(
		GraphicsResourceAllocator allocator,
		DeltaTracker deltaTracker,
		boolean renderOutline,
		CameraRenderState cameraState,
		Matrix4fc modelView,
		GpuBufferSlice terrainFog,
		Vector4f fogColor,
		boolean shouldRenderSky,
		CallbackInfo ci
	) {
		if (!IrisVulkan.isShaderPackDepthMode()) return;
		Minecraft minecraft = Minecraft.getInstance();
		HandRenderer.INSTANCE.renderTranslucentVulkan(
			iris$vulkanModelView,
			deltaTracker.getGameTimeDeltaPartialTick(true),
			minecraft.gameRenderer.mainCamera(),
			levelRenderState.cameraRenderState,
			minecraft.gameRenderer
		);
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;")
	)
	private FeatureRenderDispatcher.PreparedFrame iris$renderVulkanShadowFeatures(
		FeatureRenderDispatcher dispatcher,
		SubmitNodeStorage storage
	) {
		FeatureRenderDispatcher.PreparedFrame frame = dispatcher.prepareFrame(storage);
		if (!IrisVulkan.isShaderPackDepthMode()) return frame;
		IrisVulkan.renderShadowFeatures(frame);
		IrisVulkan.renderPrepare();
		return frame;
	}
}
