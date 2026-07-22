package net.irisshaders.iris.vulkan.uniforms;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import net.irisshaders.iris.vulkan.VulkanFrameState;
import net.irisshaders.iris.vulkan.VulkanDiagnostics;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import net.minecraft.world.effect.MobEffects;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Writes the first backend-neutral subset of OptiFine/Iris uniforms into a std140 block. */
public final class VulkanShaderpackUniforms implements AutoCloseable {
	private static final Matrix4f LIGHTMAP_TEXTURE_MATRIX = new Matrix4f(
		0.00390625f, 0.0f, 0.0f, 0.0f,
		0.0f, 0.00390625f, 0.0f, 0.0f,
		0.0f, 0.0f, 0.00390625f, 0.0f,
		0.03125f, 0.03125f, 0.03125f, 1.0f
	);

	private final VulkanShaderTransformResult layout;
	private final Map<String, VulkanShaderTransformResult.UniformMember> members = new HashMap<>();
	private final VulkanCustomUniforms customUniforms;
	private final float alphaTestReference;
	private final boolean shadowPass;
	private final ByteBuffer staging;
	private final @Nullable String diagnosticName;
	private int diagnosticFramesRemaining;
	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f modelViewInverse = new Matrix4f();
	private final Matrix4f previousModelView = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();
	private final Matrix4f shadowModelView = new Matrix4f();
	private final Matrix4f shadowModelViewInverse = new Matrix4f();
	private final Matrix4f shadowProjection = new Matrix4f();
	private final Matrix4f shadowProjectionInverse = new Matrix4f();
	private final Matrix4f drawModelViewInverse = new Matrix4f();
	private final Matrix3f normalMatrix = new Matrix3f();

	public VulkanShaderpackUniforms(VulkanShaderTransformResult layout, VulkanCustomUniforms customUniforms) {
		this(layout, customUniforms, 0.0F, false);
	}

	public VulkanShaderpackUniforms(
		VulkanShaderTransformResult layout,
		VulkanCustomUniforms customUniforms,
		float alphaTestReference,
		boolean shadowPass
	) {
		this(layout, customUniforms, alphaTestReference, shadowPass, null);
	}

	public VulkanShaderpackUniforms(
		VulkanShaderTransformResult layout,
		VulkanCustomUniforms customUniforms,
		float alphaTestReference,
		boolean shadowPass,
		@Nullable String diagnosticName
	) {
		this.layout = layout;
		this.customUniforms = customUniforms;
		this.alphaTestReference = alphaTestReference;
		this.shadowPass = shadowPass;
		this.diagnosticName = diagnosticName;
		this.diagnosticFramesRemaining = diagnosticName == null || !VulkanDiagnostics.enabled() ? 0 : 3;
		layout.looseUniforms().forEach(member -> members.put(member.name(), member));
		this.staging = MemoryUtil.memCalloc(layout.looseUniformBufferSize());
	}

	public void update(CommandEncoder encoder, GpuBuffer target, int width, int height) {
		update(encoder, target, width, height, 0, 0, 0, 0);
	}

	public void update(
		CommandEncoder encoder,
		GpuBuffer target,
		int width,
		int height,
		int textureWidth,
		int textureHeight,
		int atlasWidth,
		int atlasHeight
	) {
		update(encoder, target, width, height, textureWidth, textureHeight, atlasWidth, atlasHeight,
			net.irisshaders.iris.vulkan.IrisVulkan.getRenderPhase().ordinal());
	}

	public void update(
		CommandEncoder encoder,
		GpuBuffer target,
		int width,
		int height,
		int textureWidth,
		int textureHeight,
		int atlasWidth,
		int atlasHeight,
		int renderStage
	) {
		ByteBuffer data = staging;
		MemoryUtil.memSet(MemoryUtil.memAddress(data), 0, data.capacity());
		data.position(0).limit(data.capacity());
		try {
			customUniforms.writeTo(data, members);
			Minecraft minecraft = Minecraft.getInstance();
			VulkanFrameState.copyGbufferModelView(modelView);
			VulkanFrameState.copyPreviousGbufferModelView(previousModelView);
			VulkanFrameState.copyGbufferProjection(projection);
			VulkanFrameState.copyPreviousGbufferProjection(previousProjection);
			VulkanFrameState.copyShadowModelView(shadowModelView);
			VulkanFrameState.copyShadowProjection(shadowProjection);
			modelViewInverse.set(modelView).invert();
			projectionInverse.set(projection).invert();
			shadowModelViewInverse.set(shadowModelView).invert();
			shadowProjectionInverse.set(shadowProjection).invert();
			Matrix4f drawModelView = shadowPass ? shadowModelView : RenderSystem.getModelViewMatrixCopy();
			drawModelViewInverse.set(drawModelView).invert();
			Matrix4f drawProjectionInverse = shadowPass ? shadowProjectionInverse : projectionInverse;

			putMatrix(data, "gbufferModelView", modelView);
			putMatrix(data, "gbufferModelViewInverse", modelViewInverse);
			putMatrix(data, "gbufferPreviousModelView", previousModelView);
			putMatrix(data, "gbufferProjection", projection);
			putMatrix(data, "gbufferProjectionInverse", projectionInverse);
			putMatrix(data, "gbufferPreviousProjection", previousProjection);
			putMatrix(data, "shadowModelView", shadowModelView);
			putMatrix(data, "shadowModelViewInverse", shadowModelViewInverse);
			putMatrix(data, "shadowProjection", shadowProjection);
			putMatrix(data, "shadowProjectionInverse", shadowProjectionInverse);
			putMatrix(data, "iris_ModelViewMatInverse", drawModelViewInverse);
			putMatrix(data, "iris_ProjMatInverse", drawProjectionInverse);
			// Some transformed compatibility profiles retain Iris' long-form
			// aliases instead of the vanilla-core short names. They describe the
			// same per-draw matrices and must not remain as zero-filled mat4 values.
			putMatrix(data, "iris_ModelViewMatrixInverse", drawModelViewInverse);
			putMatrix(data, "iris_ProjectionMatrixInverse", drawProjectionInverse);
			putMatrix3(data, "iris_NormalMat", drawModelViewInverse.transpose3x3(normalMatrix));
			putMatrix(data, "iris_LightmapTextureMatrix", LIGHTMAP_TEXTURE_MATRIX);

			putFloat(data, "viewWidth", width);
			putFloat(data, "viewHeight", height);
			putFloat(data, "aspectRatio", height == 0 ? 1.0F : (float)width / height);
			putIntVec2(data, "atlasSize", atlasWidth, atlasHeight);
			putIntVec2(data, "gtextureSize", textureWidth, textureHeight);
			int entityId = CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
			int blockEntityId = CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
			int itemId = CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
			putInt(data, "entityId", entityId);
			putInt(data, "blockEntityId", blockEntityId);
			putInt(data, "currentRenderedItemId", itemId);
			putIntVec3(data, "iris_Entity",
				entityId, blockEntityId, itemId);
			putInt(data, "renderStage", renderStage);
			putVec4(data, "entityColor", 0.0F, 0.0F, 0.0F, 0.0F);
			putFloat(data, "centerDepthSmooth", 0.0F);
			var fogColor = CapturedRenderingState.INSTANCE.getFogColor();
			putVec3(data, "fogColor", (float)fogColor.x, (float)fogColor.y, (float)fogColor.z);
			float fogDensity = CapturedRenderingState.INSTANCE.getFogDensity();
			putFloat(data, "fogDensity", Math.max(0.0F, fogDensity));
			putFloat(data, "fogStart", VulkanFrameState.fogStart());
			putFloat(data, "fogEnd", VulkanFrameState.fogEnd());
			putVec4(data, "iris_FogColor", (float)fogColor.x, (float)fogColor.y, (float)fogColor.z, 1.0F);
			putFloat(data, "iris_FogDensity", Math.max(0.0F, fogDensity));
			putFloat(data, "iris_FogStart", VulkanFrameState.fogStart());
			putFloat(data, "iris_FogEnd", VulkanFrameState.fogEnd());
			putFloat(data, "iris_currentAlphaTest", alphaTestReference);
			putFloat(data, "alphaTestRef", alphaTestReference);
			putInt(data, "fogMode", fogDensity < 0.0F ? 9729 : 2049);
			putInt(data, "fogShape", 1);
			putInt(data, "textureReloadCount", CapturedRenderingState.INSTANCE.getTextureReloadCount());
			logFrameProbe(data);

			encoder.writeToBuffer(target.slice(), data);
		} finally {
			data.position(0).limit(data.capacity());
		}
	}

	private void logFrameProbe(ByteBuffer data) {
		if (diagnosticFramesRemaining <= 0 || diagnosticName == null) return;
		Iris.logger.info(
			"Iris Vulkan frame probe [{}]: frame={} frameTime={} frameTimeCounter={} worldTime={} camera={} sun={} projection={}",
			diagnosticName,
			readInt(data, "frameCounter"),
			readFloat(data, "frameTime"),
			readFloat(data, "frameTimeCounter"),
			readInt(data, "worldTime"),
			readVec3(data, "cameraPosition"),
			readVec3(data, "sunPosition"),
			readProjection(data, "gbufferProjection")
		);
		diagnosticFramesRemaining--;
	}

	private String readFloat(ByteBuffer data, String name) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		return member == null || member.size() < 4
			? "n/a" : String.format(Locale.ROOT, "%.6f", data.getFloat(member.offset()));
	}

	private String readInt(ByteBuffer data, String name) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		return member == null || member.size() < 4 ? "n/a" : Integer.toString(data.getInt(member.offset()));
	}

	private String readVec3(ByteBuffer data, String name) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member == null || member.size() < 12) return "n/a";
		int offset = member.offset();
		return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)",
			data.getFloat(offset), data.getFloat(offset + 4), data.getFloat(offset + 8));
	}

	private String readProjection(ByteBuffer data, String name) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member == null || member.size() < 64) return "n/a";
		int offset = member.offset();
		return String.format(Locale.ROOT, "(m00=%.4f,m11=%.4f,m22=%.4f,m23=%.4f,m32=%.4f,m33=%.4f)",
			data.getFloat(offset), data.getFloat(offset + 20), data.getFloat(offset + 40),
			data.getFloat(offset + 44), data.getFloat(offset + 56), data.getFloat(offset + 60));
	}

	private void putMatrix(ByteBuffer data, String name, Matrix4f value) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 64) {
			value.get(member.offset(), data);
		}
	}

	private void putMatrix3(ByteBuffer data, String name, Matrix3f value) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 48) {
			int offset = member.offset();
			data.putFloat(offset, value.m00());
			data.putFloat(offset + 4, value.m01());
			data.putFloat(offset + 8, value.m02());
			data.putFloat(offset + 16, value.m10());
			data.putFloat(offset + 20, value.m11());
			data.putFloat(offset + 24, value.m12());
			data.putFloat(offset + 32, value.m20());
			data.putFloat(offset + 36, value.m21());
			data.putFloat(offset + 40, value.m22());
		}
	}

	private void putVec3(ByteBuffer data, String name, float x, float y, float z) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 12) {
			data.putFloat(member.offset(), x);
			data.putFloat(member.offset() + 4, y);
			data.putFloat(member.offset() + 8, z);
		}
	}

	private void putVec4(ByteBuffer data, String name, float x, float y, float z, float w) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 16) {
			data.putFloat(member.offset(), x);
			data.putFloat(member.offset() + 4, y);
			data.putFloat(member.offset() + 8, z);
			data.putFloat(member.offset() + 12, w);
		}
	}

	private void putVec2(ByteBuffer data, String name, float x, float y) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 8) {
			data.putFloat(member.offset(), x);
			data.putFloat(member.offset() + 4, y);
		}
	}

	private void putIntVec2(ByteBuffer data, String name, int x, int y) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 8) {
			data.putInt(member.offset(), x);
			data.putInt(member.offset() + 4, y);
		}
	}

	private void putIntVec3(ByteBuffer data, String name, int x, int y, int z) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 12) {
			data.putInt(member.offset(), x);
			data.putInt(member.offset() + 4, y);
			data.putInt(member.offset() + 8, z);
		}
	}

	private void putFloat(ByteBuffer data, String name, float value) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 4) {
			data.putFloat(member.offset(), value);
		}
	}

	private void putInt(ByteBuffer data, String name, int value) {
		VulkanShaderTransformResult.UniformMember member = members.get(name);
		if (member != null && member.size() >= 4) {
			data.putInt(member.offset(), value);
		}
	}

	@Override
	public void close() {
		MemoryUtil.memFree(staging);
	}
}
