package net.irisshaders.iris.mixin;

import net.irisshaders.iris.vulkan.IrisVulkan;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores shader-pack forward-Z while retaining Vulkan's zero-to-one clip range. */
@Mixin(Projection.class)
public class VKOnly_UndoReverseZProjection {
	@Shadow private boolean isMatrixDirty;
	@Unique private boolean iris$lastShaderState;

	@Inject(method = "setupPerspective", at = @At("HEAD"))
	private void iris$invalidatePerspective(float near, float far, float fov, float width, float height, CallbackInfo ci) {
		iris$invalidateWhenPackStateChanges();
	}

	@Inject(method = "setupOrtho", at = @At("HEAD"))
	private void iris$invalidateOrtho(float near, float far, float width, float height, boolean invertY, CallbackInfo ci) {
		iris$invalidateWhenPackStateChanges();
	}

	@Unique
	private void iris$invalidateWhenPackStateChanges() {
		boolean shaderPack = IrisVulkan.usesShaderPackDepthConvention();
		if (iris$lastShaderState != shaderPack) {
			iris$lastShaderState = shaderPack;
			isMatrixDirty = true;
		}
	}

	@Redirect(method = "getMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setPerspective(FFFFZ)Lorg/joml/Matrix4f;"))
	private Matrix4f iris$forwardPerspective(Matrix4f matrix, float fov, float aspect, float reversedNear, float reversedFar, boolean zeroToOne) {
		return IrisVulkan.usesShaderPackDepthConvention()
			// Shader-pack projection uniforms use OpenGL's -1..1 clip range. The
			// Vulkan shader transform converts only gl_Position.z to Vulkan's 0..1
			// range, leaving sampled depth compatible with existing pack math.
			? matrix.setPerspective(fov, aspect, reversedFar, reversedNear, false)
			: matrix.setPerspective(fov, aspect, reversedNear, reversedFar, zeroToOne);
	}

	@Redirect(method = "getMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setOrtho(FFFFFFZ)Lorg/joml/Matrix4f;"))
	private Matrix4f iris$forwardOrtho(
		Matrix4f matrix,
		float left,
		float right,
		float bottom,
		float top,
		float reversedNear,
		float reversedFar,
		boolean zeroToOne
	) {
		return IrisVulkan.usesShaderPackDepthConvention()
			? matrix.setOrtho(left, right, bottom, top, reversedFar, reversedNear, false)
			: matrix.setOrtho(left, right, bottom, top, reversedNear, reversedFar, zeroToOne);
	}
}
