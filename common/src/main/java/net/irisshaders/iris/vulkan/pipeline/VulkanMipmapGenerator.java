package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderType;
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
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** Generates color and depth mip chains using only Minecraft's public GPU API. */
public final class VulkanMipmapGenerator implements AutoCloseable {
	private static final String VERTEX_SOURCE = """
		#version 450
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec2 UV0;
		layout(location = 0) out vec2 iris_MipUv;
		void main() {
		    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
		    iris_MipUv = UV0;
		}
		""";

	private static final String FLOAT_FRAGMENT_SOURCE = """
		#version 450
		layout(location = 0) in vec2 iris_MipUv;
		layout(location = 0) out vec4 iris_MipColor;
		uniform sampler2D Source;
		void main() {
		    iris_MipColor = texture(Source, iris_MipUv);
		}
		""";

	private static final String UINT_FRAGMENT_SOURCE = """
		#version 450
		layout(location = 0) out uvec4 iris_MipColor;
		uniform usampler2D Source;
		void main() {
		    ivec2 base = ivec2(gl_FragCoord.xy) * 2;
		    ivec2 limit = textureSize(Source, 0) - 1;
		    uvec4 sum = texelFetch(Source, min(base, limit), 0)
		        + texelFetch(Source, min(base + ivec2(1, 0), limit), 0)
		        + texelFetch(Source, min(base + ivec2(0, 1), limit), 0)
		        + texelFetch(Source, min(base + ivec2(1, 1), limit), 0);
		    iris_MipColor = sum / 4u;
		}
		""";

	private static final String SINT_FRAGMENT_SOURCE = """
		#version 450
		layout(location = 0) out ivec4 iris_MipColor;
		uniform isampler2D Source;
		void main() {
		    ivec2 base = ivec2(gl_FragCoord.xy) * 2;
		    ivec2 limit = textureSize(Source, 0) - 1;
		    ivec4 sum = texelFetch(Source, min(base, limit), 0)
		        + texelFetch(Source, min(base + ivec2(1, 0), limit), 0)
		        + texelFetch(Source, min(base + ivec2(0, 1), limit), 0)
		        + texelFetch(Source, min(base + ivec2(1, 1), limit), 0);
		    iris_MipColor = sum / 4;
		}
		""";

	private static final String DEPTH_FRAGMENT_SOURCE = """
		#version 450
		layout(location = 0) in vec2 iris_MipUv;
		uniform sampler2D Source;
		void main() {
		    gl_FragDepth = texture(Source, iris_MipUv).r;
		}
		""";

	private final GpuDevice device;
	private final GpuBuffer fullscreenVertices;
	private final GpuSampler linearSampler;
	private final GpuSampler nearestSampler;
	private final Map<GpuFormat, RenderPipeline> colorPipelines = new EnumMap<>(GpuFormat.class);
	private RenderPipeline depthPipeline;

	public VulkanMipmapGenerator(GpuDevice device) {
		this.device = device;
		this.fullscreenVertices = createFullscreenVertices();
		this.linearSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
			FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.25));
		this.nearestSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
			FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.25));
	}

	public void generateColor(CommandEncoder encoder, GpuTexture texture) {
		if (texture.getMipLevels() <= 1) return;
		RenderPipeline pipeline = colorPipelines.computeIfAbsent(texture.getFormat(), this::compileColorPipeline);
		GpuSampler sampler = isInteger(texture.getFormat()) ? nearestSampler : linearSampler;
		generate(encoder, texture, pipeline, sampler, false);
	}

	public void generateDepth(CommandEncoder encoder, GpuTexture texture) {
		if (texture.getMipLevels() <= 1) return;
		if (!texture.getFormat().hasDepthAspect()) {
			throw new IllegalArgumentException("Mipmap depth source is not a depth texture: " + texture.getFormat());
		}
		if (depthPipeline == null) depthPipeline = compileDepthPipeline(texture.getFormat());
		generate(encoder, texture, depthPipeline, linearSampler, true);
	}

	private void generate(
		CommandEncoder encoder,
		GpuTexture texture,
		RenderPipeline pipeline,
		GpuSampler sampler,
		boolean depth
	) {
		for (int level = 1; level < texture.getMipLevels(); level++) {
			int currentLevel = level;
			try (GpuTextureView source = device.createTextureView(texture, level - 1, 1);
				 GpuTextureView target = device.createTextureView(texture, level, 1)) {
				RenderPassDescriptor descriptor = RenderPassDescriptor.create(
					() -> "Iris Vulkan mip " + texture.getLabel() + " level " + currentLevel
				);
				if (depth) {
					descriptor.withDepthAttachment(target, OptionalDouble.empty());
				} else {
					descriptor.withColorAttachment(target, Optional.empty());
				}
				descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, target.getWidth(0), target.getHeight(0)));
				try (RenderPass pass = encoder.createRenderPass(descriptor)) {
					pass.setPipeline(pipeline);
					pass.bindTexture("Source", source, sampler);
					pass.setVertexBuffer(0, fullscreenVertices.slice());
					pass.draw(4, 1, 0, 0);
				}
			}
		}
	}

	private RenderPipeline compileColorPipeline(GpuFormat format) {
		String suffix = format.name().toLowerCase(java.util.Locale.ROOT);
		Identifier id = Identifier.fromNamespaceAndPath("iris", "vulkan/internal/mipmap_color_" + suffix);
		String fragment = fragmentSource(format);
		RenderPipeline pipeline = RenderPipeline.builder()
			.withLocation(id)
			.withVertexShader(id)
			.withFragmentShader(id)
			.withBindGroupLayout(BindGroupLayout.builder().withSampler("Source").build())
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
			.withCull(false)
			.withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
			.build();
		precompile(pipeline, id, fragment);
		return pipeline;
	}

	private RenderPipeline compileDepthPipeline(GpuFormat format) {
		Identifier id = Identifier.fromNamespaceAndPath("iris", "vulkan/internal/mipmap_depth_"
			+ format.name().toLowerCase(java.util.Locale.ROOT));
		RenderPipeline pipeline = RenderPipeline.builder()
			.withLocation(id)
			.withVertexShader(id)
			.withFragmentShader(id)
			.withBindGroupLayout(BindGroupLayout.builder().withSampler("Source").build())
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
			.withCull(false)
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
			.build();
		precompile(pipeline, id, DEPTH_FRAGMENT_SOURCE);
		return pipeline;
	}

	private void precompile(RenderPipeline pipeline, Identifier id, String fragment) {
		CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, (requested, type) -> {
			if (!id.equals(requested)) return null;
			return type == ShaderType.VERTEX ? VERTEX_SOURCE : fragment;
		});
		if (!compiled.isValid()) {
			throw new IllegalStateException("Minecraft's Vulkan backend rejected the mipmap pipeline for "
				+ id + ": " + device.getLastDebugMessages());
		}
	}

	private static String fragmentSource(GpuFormat format) {
		return switch (format.componentType()) {
			case UINT_8, UINT_16, UINT_32 -> UINT_FRAGMENT_SOURCE;
			case SINT_8, SINT_16, SINT_32 -> SINT_FRAGMENT_SOURCE;
			default -> FLOAT_FRAGMENT_SOURCE;
		};
	}

	private static boolean isInteger(GpuFormat format) {
		return switch (format.componentType()) {
			case UINT_8, UINT_16, UINT_32, SINT_8, SINT_16, SINT_32 -> true;
			default -> false;
		};
	}

	private GpuBuffer createFullscreenVertices() {
		ByteBuffer data = MemoryUtil.memAlloc(4 * 5 * Float.BYTES);
		try {
			putVertex(data, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
			putVertex(data, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
			putVertex(data, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f);
			putVertex(data, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f);
			data.flip();
			return device.createBuffer(() -> "Iris Vulkan mipmap quad", GpuBuffer.USAGE_VERTEX, data);
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
		linearSampler.close();
		nearestSampler.close();
		colorPipelines.clear();
		depthPipeline = null;
	}
}
