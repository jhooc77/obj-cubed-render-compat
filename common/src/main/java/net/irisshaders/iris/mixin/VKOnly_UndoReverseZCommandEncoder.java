package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.vulkan.IrisVulkan;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;

import java.util.OptionalDouble;

/** Converts vanilla reverse-Z clear values to forward-Z at the backend-neutral command boundary. */
@Mixin(CommandEncoder.class)
public class VKOnly_UndoReverseZCommandEncoder {
	@WrapMethod(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V")
	private void iris$clearColorDepth(
		GpuTexture color,
		Vector4fc clearColor,
		GpuTexture depth,
		double clearDepth,
		Operation<Void> original
	) {
		original.call(color, clearColor, depth, iris$depth(clearDepth));
	}

	@WrapMethod(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;DIIII)V")
	private void iris$clearColorDepthRegion(
		GpuTexture color,
		Vector4fc clearColor,
		GpuTexture depth,
		double clearDepth,
		int x,
		int y,
		int width,
		int height,
		Operation<Void> original
	) {
		original.call(color, clearColor, depth, iris$depth(clearDepth), x, y, width, height);
	}

	@WrapMethod(method = "clearDepthTexture")
	private void iris$clearDepth(GpuTexture depth, double clearDepth, Operation<Void> original) {
		original.call(depth, iris$depth(clearDepth));
	}

	@WrapMethod(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;")
	private RenderPass iris$renderPassDepthClear(RenderPassDescriptor descriptor, Operation<RenderPass> original) {
		if (!IrisVulkan.usesShaderPackDepthConvention() || descriptor.depthAttachment() == null || descriptor.depthAttachment().clearValue().isEmpty()) {
			return original.call(descriptor);
		}
		return original.call(descriptor.withDepthAttachment(
			descriptor.depthAttachment().textureView(),
			OptionalDouble.of(iris$depth(descriptor.depthAttachment().clearValue().getAsDouble()))
		));
	}

	private static double iris$depth(double value) {
		return IrisVulkan.usesShaderPackDepthConvention() ? Math.clamp(1.0 - value, 0.0, 1.0) : value;
	}
}
