package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.mixin.IrisMixinPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Capability fallbacks for packs whose optional-feature guards predate Vulkan Iris. */
public final class VulkanShaderPackCompatibility {
	private VulkanShaderPackCompatibility() {
	}

	public static ImmutableList<StringPair> apply(Path root, ImmutableList<StringPair> environmentDefines) throws IOException {
		if (!IrisMixinPlugin.usingVulkan
			|| (VulkanCapabilities.supportsCustomImages() && VulkanCapabilities.supportsComputeShaders())) {
			return environmentDefines;
		}

		Path propertiesPath = root.resolve("shaders.properties");
		if (!Files.isRegularFile(propertiesPath)) return environmentDefines;

		String properties = Files.readString(propertiesPath, StandardCharsets.UTF_8);
		if (!needsSolasVoxelFallback(properties)) return environmentDefines;

		// Solas declares CUSTOM_IMAGES as optional, but its VX_SUPPORT macro is
		// based on Minecraft version/vendor rather than IRIS_FEATURE_CUSTOM_IMAGES.
		// Select the pack's own tested Intel fallback, which disables only the 3D
		// voxel flood-fill/compute chain while preserving its regular render passes.
		ArrayList<StringPair> compatible = new ArrayList<>(environmentDefines.size() + 2);
		for (StringPair define : environmentDefines) {
			if (define.key().startsWith("MC_GL_VENDOR_") || define.key().startsWith("MC_GL_RENDERER_")) continue;
			compatible.add(define);
		}
		compatible.add(new StringPair("MC_GL_VENDOR_INTEL", "1"));
		compatible.add(new StringPair("MC_GL_RENDERER_INTEL", "1"));
		Iris.logger.info("Iris Vulkan selected the shaderpack's non-voxel fallback because compute/custom 3D images are unavailable");
		return ImmutableList.copyOf(compatible);
	}

	static boolean needsSolasVoxelFallback(String properties) {
		String compact = properties.replaceAll("\\s+", "");
		return compact.contains("iris.features.optional=CUSTOM_IMAGES")
			&& compact.contains("image.voxel_img=voxelSampler")
			&& compact.contains("image.floodfill_img=floodfillSampler")
			&& compact.contains("program.world0/shadowcomp.enabled=VX_SUPPORT");
	}
}
