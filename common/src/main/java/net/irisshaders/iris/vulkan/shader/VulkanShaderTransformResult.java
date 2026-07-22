package net.irisshaders.iris.vulkan.shader;

import net.irisshaders.iris.pipeline.transform.PatchShaderType;

import java.util.List;
import java.util.Map;

public record VulkanShaderTransformResult(
	Map<PatchShaderType, String> sources,
	List<String> uniformBlocks,
	List<String> samplers,
	List<String> texelBuffers,
	Map<String, String> texelBufferTypes,
	Map<String, String> samplerTypes,
	List<UniformMember> looseUniforms,
	int looseUniformBufferSize
) {
	public record UniformMember(String name, String declaration, int offset, int size) {
	}
}
