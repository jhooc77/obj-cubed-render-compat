package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;

/** Converts shader-pack blend directives without mutating OpenGL state. */
final class VulkanBlendFunction {
	private VulkanBlendFunction() {
	}

	static BlendFunction fromIris(BlendMode mode) {
		return new BlendFunction(
			factor(mode.srcRgb()),
			factor(mode.dstRgb()),
			factor(mode.srcAlpha()),
			factor(mode.dstAlpha())
		);
	}

	private static BlendFactor factor(int glId) {
		for (BlendModeFunction function : BlendModeFunction.values()) {
			if (function.getGlId() == glId) {
				return switch (function) {
					case ZERO -> BlendFactor.ZERO;
					case ONE -> BlendFactor.ONE;
					case SRC_COLOR -> BlendFactor.SRC_COLOR;
					case ONE_MINUS_SRC_COLOR -> BlendFactor.ONE_MINUS_SRC_COLOR;
					case DST_COLOR -> BlendFactor.DST_COLOR;
					case ONE_MINUS_DST_COLOR -> BlendFactor.ONE_MINUS_DST_COLOR;
					case SRC_ALPHA -> BlendFactor.SRC_ALPHA;
					case ONE_MINUS_SRC_ALPHA -> BlendFactor.ONE_MINUS_SRC_ALPHA;
					case DST_ALPHA -> BlendFactor.DST_ALPHA;
					case ONE_MINUS_DST_ALPHA -> BlendFactor.ONE_MINUS_DST_ALPHA;
					case SRC_ALPHA_SATURATE -> BlendFactor.SRC_ALPHA_SATURATE;
				};
			}
		}
		throw new IllegalArgumentException("Unsupported shader-pack blend factor: " + glId);
	}
}
