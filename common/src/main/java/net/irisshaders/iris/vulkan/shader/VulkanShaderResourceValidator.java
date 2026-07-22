package net.irisshaders.iris.vulkan.shader;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;

import java.util.LinkedHashSet;
import java.util.Set;

/** Rejects reflected resources that the current Minecraft Vulkan draw path cannot bind. */
public final class VulkanShaderResourceValidator {
	private static final Set<String> DEFAULT_BLOCKS = Set.of(
		VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK,
		"Globals",
		"Projection",
		"Fog",
		"Lighting"
	);
	private static final Set<String> SUPPORTED_SAMPLER_TYPES = Set.of(
		"sampler2D",
		"sampler2DShadow",
		"isampler2D",
		"usampler2D"
	);
	private static final Set<String> SUPPORTED_TEXEL_BUFFER_TYPES = Set.of(
		"samplerBuffer",
		"isamplerBuffer",
		"usamplerBuffer"
	);

	private VulkanShaderResourceValidator() {
	}

	public static void validateTerrain(VulkanShaderTransformResult result, String program) {
		validate(result, program, Set.of("ChunkSection"), Set.of("u_SectionTimeInfo"));
	}

	public static void validateSodiumTerrain(VulkanShaderTransformResult result, String program) {
		validate(result, program, Set.of("u_Globals"), Set.of("u_SectionTimeInfo"));
	}

	public static void validateDraw(VulkanShaderTransformResult result, String program) {
		validate(result, program, Set.of("DynamicTransforms", "CloudInfo"), Set.of("CloudFaces"));
	}

	public static void validateFullscreen(VulkanShaderTransformResult result, String program) {
		validate(result, program, Set.of(), Set.of());
	}

	public static GpuFormat texelBufferFormat(VulkanShaderTransformResult result, String name) {
		String samplerType = result.texelBufferTypes().get(name);
		return switch (samplerType) {
			case "samplerBuffer" -> GpuFormat.R32_FLOAT;
			case "isamplerBuffer" -> GpuFormat.R32_SINT;
			case "usamplerBuffer" -> GpuFormat.R32_UINT;
			case null -> throw new IllegalArgumentException("Missing sampler type for texel buffer '" + name + "'");
			default -> throw new IllegalArgumentException(
				"Unsupported sampler type '" + samplerType + "' for texel buffer '" + name + "'"
			);
		};
	}

	public static void addTexelBufferBinding(
		BindGroupLayout.Builder bindings,
		VulkanShaderTransformResult result,
		String name
	) {
		bindings.withUniform(name, UniformType.TEXEL_BUFFER, texelBufferFormat(result, name));
	}

	private static void validate(
		VulkanShaderTransformResult result,
		String program,
		Set<String> additionalBlocks,
		Set<String> externalTexelBuffers
	) {
		LinkedHashSet<String> unsupportedBlocks = new LinkedHashSet<>(result.uniformBlocks());
		unsupportedBlocks.removeAll(DEFAULT_BLOCKS);
		unsupportedBlocks.removeAll(additionalBlocks);
		if (!unsupportedBlocks.isEmpty()) {
			throw unsupported(program, "uniform blocks have no Vulkan binding provider", unsupportedBlocks);
		}

		LinkedHashSet<String> unsupportedTexelBuffers = new LinkedHashSet<>(result.texelBuffers());
		unsupportedTexelBuffers.removeAll(externalTexelBuffers);
		if (!unsupportedTexelBuffers.isEmpty()) {
			throw unsupported(program, "texel buffers have no Vulkan binding provider", unsupportedTexelBuffers);
		}

		LinkedHashSet<String> unsupportedTexelBufferTypes = new LinkedHashSet<>();
		result.texelBuffers().forEach(name -> {
			String type = result.texelBufferTypes().get(name);
			if (!SUPPORTED_TEXEL_BUFFER_TYPES.contains(type)) {
				unsupportedTexelBufferTypes.add(name + ":" + (type == null ? "<missing>" : type));
			}
		});
		if (!unsupportedTexelBufferTypes.isEmpty()) {
			throw unsupported(program, "texel buffer types are not implemented", unsupportedTexelBufferTypes);
		}

		LinkedHashSet<String> unsupportedSamplers = new LinkedHashSet<>();
		result.samplerTypes().forEach((name, type) -> {
			if (!SUPPORTED_SAMPLER_TYPES.contains(type)) unsupportedSamplers.add(name + ":" + type);
		});
		if (!unsupportedSamplers.isEmpty()) {
			throw unsupported(program, "sampled texture types are not implemented", unsupportedSamplers);
		}
	}

	private static UnsupportedOperationException unsupported(String program, String reason, Set<String> resources) {
		return new UnsupportedOperationException(
			"Vulkan shader program '" + program + "' cannot run because " + reason + ": "
				+ String.join(", ", resources)
		);
	}
}
