package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.vulkan.VulkanFrameState;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/** Maintains Iris' temporally-smoothed one-pixel center-depth texture on Vulkan. */
public final class VulkanCenterDepthSampler implements AutoCloseable {
	private static final double LN2 = Math.log(2.0);
	private static final String UNIFORM_BLOCK = "CenterDepth";
	private static final String VERTEX_SOURCE = """
		#version 450
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec2 UV0;
		void main() {
		    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
		}
		""";
	private static final String FRAGMENT_SOURCE = """
		#version 450
		layout(location = 0) out float iris_CenterDepth;
		uniform sampler2D Depth;
		uniform sampler2D Previous;
		layout(std140) uniform CenterDepth {
		    float LastFrameTime;
		    float Decay;
		    int FirstSample;
		};
		void main() {
		    float currentDepth = texture(Depth, vec2(0.5)).r;
		    float oldDepth = texture(Previous, vec2(0.5)).r;
		    float blend = 1.0 - exp(-Decay * LastFrameTime);
		    iris_CenterDepth = FirstSample != 0 ? currentDepth : mix(oldDepth, currentDepth, blend);
		}
		""";

	private final GpuDevice device;
	private final GpuTexture[] textures = new GpuTexture[2];
	private final GpuTextureView[] views = new GpuTextureView[2];
	private final GpuBuffer fullscreenVertices;
	private final GpuBuffer uniforms;
	private final GpuSampler sampler;
	private final RenderPipeline pipeline;
	private final float decay;
	private int latestIndex = -1;

	public VulkanCenterDepthSampler(GpuDevice device, float halfLife) {
		this.device = device;
		this.decay = halfLife <= 0.0F ? Float.MAX_VALUE : (float)(LN2 / (halfLife * 0.1));
		for (int index = 0; index < textures.length; index++) {
			textures[index] = device.createTexture(
				"Iris Vulkan center depth " + index,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				GpuFormat.R32_FLOAT,
				1,
				1,
				1,
				1
			);
			views[index] = device.createTextureView(textures[index]);
		}
		CommandEncoder encoder = device.createCommandEncoder();
		encoder.clearColorTexture(textures[0], new Vector4f(1.0F));
		encoder.clearColorTexture(textures[1], new Vector4f(1.0F));

		this.fullscreenVertices = createFullscreenVertices();
		ByteBuffer zeroes = MemoryUtil.memCalloc(16);
		try {
			this.uniforms = device.createBuffer(
				() -> "Iris Vulkan center depth uniforms",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				zeroes
			);
		} finally {
			MemoryUtil.memFree(zeroes);
		}
		this.sampler = device.createSampler(
			AddressMode.CLAMP_TO_EDGE,
			AddressMode.CLAMP_TO_EDGE,
			FilterMode.NEAREST,
			FilterMode.NEAREST,
			1,
			OptionalDouble.of(0.0)
		);
		this.pipeline = compilePipeline();
	}

	public void sample(GpuTextureView depthTexture) {
		int targetIndex = latestIndex < 0 ? 0 : 1 - latestIndex;
		int previousIndex = latestIndex < 0 ? 1 : latestIndex;
		CommandEncoder encoder = device.createCommandEncoder();
		ByteBuffer data = MemoryUtil.memCalloc(16);
		try {
			data.putFloat(0, VulkanFrameState.frameTime());
			data.putFloat(4, decay);
			data.putInt(8, latestIndex < 0 ? 1 : 0);
			encoder.writeToBuffer(uniforms.slice(), data);
		} finally {
			MemoryUtil.memFree(data);
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris Vulkan center depth sample")
			.withColorAttachment(views[targetIndex], Optional.empty())
			.withRenderArea(new RenderPass.RenderArea(0, 0, 1, 1));
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setPipeline(pipeline);
			pass.setUniform(UNIFORM_BLOCK, uniforms);
			pass.bindTexture("Depth", depthTexture, sampler);
			pass.bindTexture("Previous", views[previousIndex], sampler);
			pass.setVertexBuffer(0, fullscreenVertices.slice());
			pass.draw(4, 1, 0, 0);
		}
		latestIndex = targetIndex;
	}

	public GpuTextureView textureView() {
		return views[latestIndex < 0 ? 0 : latestIndex];
	}

	public GpuSampler sampler() {
		return sampler;
	}

	public void resetForWorld() {
		CommandEncoder encoder = device.createCommandEncoder();
		encoder.clearColorTexture(textures[0], new Vector4f(1.0F));
		encoder.clearColorTexture(textures[1], new Vector4f(1.0F));
		latestIndex = -1;
	}

	private RenderPipeline compilePipeline() {
		Identifier id = Identifier.fromNamespaceAndPath("iris", "vulkan/internal/center_depth");
		RenderPipeline result = RenderPipeline.builder()
			.withLocation(id)
			.withVertexShader(id)
			.withFragmentShader(id)
			.withBindGroupLayout(BindGroupLayout.builder()
				.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER)
				.withSampler("Depth")
				.withSampler("Previous")
				.build())
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
			.withCull(false)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
			.build();
		CompiledRenderPipeline compiled = device.precompilePipeline(result, (requested, type) -> {
			if (!id.equals(requested)) return null;
			return type == ShaderType.VERTEX ? VERTEX_SOURCE : FRAGMENT_SOURCE;
		});
		if (!compiled.isValid()) {
			throw new IllegalStateException("Minecraft's Vulkan backend rejected the center-depth pipeline: "
				+ device.getLastDebugMessages());
		}
		return result;
	}

	private GpuBuffer createFullscreenVertices() {
		ByteBuffer data = MemoryUtil.memAlloc(4 * 5 * Float.BYTES);
		try {
			putVertex(data, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
			putVertex(data, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F);
			putVertex(data, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
			putVertex(data, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F);
			data.flip();
			return device.createBuffer(() -> "Iris Vulkan center depth quad", GpuBuffer.USAGE_VERTEX, data);
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private static void putVertex(ByteBuffer data, float x, float y, float z, float u, float v) {
		data.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
	}

	@Override
	public void close() {
		fullscreenVertices.close();
		uniforms.close();
		sampler.close();
		for (GpuTextureView view : views) view.close();
		for (GpuTexture texture : textures) texture.close();
	}
}
