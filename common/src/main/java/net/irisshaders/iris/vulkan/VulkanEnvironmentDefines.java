package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisLimits;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Builds the OptiFine/Iris macro environment without touching OpenGL. */
public final class VulkanEnvironmentDefines {
	private VulkanEnvironmentDefines() {
	}

	public static ImmutableList<StringPair> create() {
		List<StringPair> defines = new ArrayList<>();

		define(defines, "MC_VERSION", StandardMacros.getMcVersion());
		define(defines, "MC_MIPMAP_LEVEL", String.valueOf(Minecraft.getInstance().options.mipmapLevels().get()));
		define(defines, "IRIS_VERSION", StandardMacros.getFormattedIrisVersion());

		// Shader packs use these as language/feature selectors. The Vulkan path
		// normalizes source to GLSL 4.50 before Mojang compiles it to SPIR-V.
		define(defines, "MC_GL_VERSION", "450");
		define(defines, "MC_GLSL_VERSION", "450");
		// Some packs use these macros as numeric #if operands rather than only
		// testing defined(...). Give them a conventional truthy value.
		define(defines, StandardMacros.getOsString(), "1");
		define(defines, StandardMacros.getVendor(), "1");
		define(defines, StandardMacros.getRenderer(), "1");

		define(defines, "IS_IRIS");
		define(defines, "IRIS_VULKAN");
		if (VulkanCapabilities.supportsVertexSubgroupBasic()) {
			define(defines, "IRIS_SUBGROUP_SUPPORTED");
		}
		if (VulkanCapabilities.supportsVertexSubgroupShuffle()) {
			define(defines, "IRIS_SUBGROUP_SHUFFLE_SUPPORTED");
		}
		if (VulkanCapabilities.supportsVertexSubgroupQuad()) {
			define(defines, "IRIS_SUBGROUP_QUAD_SUPPORTED");
		}
		define(defines, "IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS");
		// Logical colortex count is independent from the number writable in one MRT pass.
		define(defines, "MAX_COLOR_BUFFERS", String.valueOf(IrisLimits.MAX_COLOR_BUFFERS));
		define(defines, "IRIS_HAS_TRANSLUCENCY_SORTING");
		define(defines, "IRIS_TAG_SUPPORT", "2");

		if (Iris.getIrisConfig().shouldAllowUnknownShaders()) {
			define(defines, "ALLOWS_UNKNOWN_SHADERS");
		}

		// Do not advertise OptiFine PBR maps until the Vulkan path can bind the
		// matching normal/specular atlas views. Advertising these while binding
		// the white fallback texture makes packs decode an invalid normal.
		define(defines, "MC_RENDER_QUALITY", "1.0");
		define(defines, "MC_SHADOW_QUALITY", "1.0");
		define(defines, "MC_HAND_DEPTH", Float.toString(HandRenderer.DEPTH));

		for (WorldRenderingPhase phase : WorldRenderingPhase.values()) {
			define(defines, "MC_RENDER_STAGE_" + phase.name(), String.valueOf(phase.ordinal()));
		}

		for (String irisDefine : StandardMacros.getIrisDefines()) {
			define(defines, irisDefine);
		}

		return ImmutableList.copyOf(defines);
	}

	private static void define(List<StringPair> defines, String key) {
		defines.add(new StringPair(key, ""));
	}

	private static void define(List<StringPair> defines, String key, String value) {
		defines.add(new StringPair(key, value));
	}
}
