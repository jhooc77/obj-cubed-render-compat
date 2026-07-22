package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.texture.VulkanCustomTextureManager;
import net.irisshaders.iris.vulkan.uniforms.VulkanShaderpackUniforms;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Executes the shaderpack's shadowcomp vertex/fragment chain on shadowcolor targets. */
public final class VulkanShadowCompositeRenderState implements AutoCloseable {
	private final GpuDevice device;
	private final VulkanCompositePipelineCompiler.CompiledShadowCompositePipelines pipelines;
	private final VulkanShadowRenderState shadowTargets;
	private final VulkanTerrainRenderState worldTargets;
	private final Map<VulkanCompositePipelineCompiler.CompiledCompositePipeline, GpuBuffer> uniformBuffers = new HashMap<>();
	private final Map<VulkanCompositePipelineCompiler.CompiledCompositePipeline, VulkanShaderpackUniforms> uniformUploaders = new HashMap<>();
	private final GpuBuffer fullscreenVertices;
	private final VulkanMipmapGenerator mipmapGenerator;

	public VulkanShadowCompositeRenderState(
		GpuDevice device,
		VulkanCompositePipelineCompiler.CompiledShadowCompositePipelines pipelines,
		VulkanShadowRenderState shadowTargets,
		VulkanTerrainRenderState worldTargets
	) {
		this.device = device;
		this.pipelines = pipelines;
		this.shadowTargets = shadowTargets;
		this.worldTargets = worldTargets;
		for (VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline : pipelines.passes()) {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				uniformBuffers.put(pipeline, createZeroUniformBuffer(size, pipeline.source().getName()));
				uniformUploaders.put(pipeline, new VulkanShaderpackUniforms(pipeline.shaders(), worldTargets.customUniforms()));
			}
		}
		fullscreenVertices = createFullscreenVertices();
		mipmapGenerator = new VulkanMipmapGenerator(device);
	}

	public boolean hasPasses() {
		return !pipelines.passes().isEmpty();
	}

	public void renderAll() {
		if (pipelines.passes().isEmpty()) return;
		shadowTargets.applyCompositePreFlips(pipelines.preFlips());
		for (VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline : pipelines.passes()) {
			CommandEncoder encoder = device.createCommandEncoder();
			generateMipmaps(encoder, pipeline);
			GpuBuffer uniforms = uniformBuffers.get(pipeline);
			VulkanShaderpackUniforms uploader = uniformUploaders.get(pipeline);
			if (uniforms != null && uploader != null) {
				uploader.update(encoder, uniforms, shadowTargets.resolution(), shadowTargets.resolution());
			}
			try (RenderPass pass = shadowTargets.createCompositeRenderPass(
				encoder, () -> "Iris Vulkan " + pipeline.source().getName(), pipeline.layout())) {
				bindPipeline(pass, pipeline, uniforms);
				pass.draw(4, 1, 0, 0);
			}
			shadowTargets.finishCompositePass(pipeline.layout());
		}
	}

	private void bindPipeline(
		RenderPass pass,
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline,
		GpuBuffer uniforms
	) {
		pass.setPipeline(pipeline.pipeline());
		RenderSystem.bindDefaultUniforms(pass);
		if (uniforms != null && pipeline.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			pass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, uniforms);
		}
		boolean waterShadowEnabled = pipeline.shaders().samplers().contains("watershadow");
		for (String name : pipeline.shaders().samplers()) {
			VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.SHADOWCOMP, name);
			GpuTextureView view = custom == null ? shadowTargets.texture(name, waterShadowEnabled) : custom.view();
			if (view == null) view = worldTargets.fallbackTextureView();
			String type = pipeline.shaders().samplerTypes().get(name);
			boolean comparison = (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("shadow"))
				|| name.endsWith("HW");
			GpuSampler sampler = custom == null
				? shadowTargets.sampler(name, comparison, waterShadowEnabled) : custom.sampler();
			pass.bindTexture(name, view, sampler);
		}
		pass.setVertexBuffer(0, fullscreenVertices.slice());
	}

	private void generateMipmaps(
		CommandEncoder encoder,
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline
	) {
		for (int target : pipeline.layout().mipmappedBuffers()) {
			GpuTextureView view = shadowTargets.colorSampleView(target);
			if (view == null) {
				throw new IllegalStateException(pipeline.source().getName()
					+ " requests mipmaps for missing shadowcolor" + target);
			}
			mipmapGenerator.generateColor(encoder, view.texture());
		}
	}

	private GpuBuffer createFullscreenVertices() {
		ByteBuffer data = MemoryUtil.memAlloc(4 * 5 * Float.BYTES);
		try {
			putVertex(data, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
			putVertex(data, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
			putVertex(data, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f);
			putVertex(data, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f);
			data.flip();
			return device.createBuffer(() -> "Iris Vulkan shadowcomp quad", GpuBuffer.USAGE_VERTEX, data);
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private static void putVertex(ByteBuffer data, float x, float y, float z, float u, float v) {
		data.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
	}

	private GpuBuffer createZeroUniformBuffer(int size, String name) {
		ByteBuffer zeroes = MemoryUtil.memCalloc(size);
		try {
			return device.createBuffer(() -> "Iris Vulkan shadowcomp uniforms for " + name,
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, zeroes);
		} finally {
			MemoryUtil.memFree(zeroes);
		}
	}

	@Override
	public void close() {
		uniformBuffers.values().forEach(GpuBuffer::close);
		uniformBuffers.clear();
		uniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		uniformUploaders.clear();
		fullscreenVertices.close();
		mipmapGenerator.close();
	}
}
