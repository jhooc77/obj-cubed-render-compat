package net.irisshaders.iris.vulkan.texture;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;

/** Maps OptiFine/Iris render-target formats to Minecraft's backend-neutral formats. */
public final class VulkanTextureFormat {
	private VulkanTextureFormat() {
	}

	public static GpuFormat fromIris(InternalTextureFormat format) {
		return switch (format) {
			case RGBA, RGBA8, RGBA2, RGBA4 -> GpuFormat.RGBA8_UNORM;
			case R8 -> GpuFormat.R8_UNORM;
			case RG8 -> GpuFormat.RG8_UNORM;
			case RGB8, R3_G3_B2, RGB565 -> GpuFormat.RGB8_UNORM;
			case R8_SNORM -> GpuFormat.R8_SNORM;
			case RG8_SNORM -> GpuFormat.RG8_SNORM;
			case RGB8_SNORM -> GpuFormat.RGB8_SNORM;
			case RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
			case R16 -> GpuFormat.R16_UNORM;
			case RG16 -> GpuFormat.RG16_UNORM;
			case RGB16 -> GpuFormat.RGB16_UNORM;
			case RGBA16 -> GpuFormat.RGBA16_UNORM;
			case R16_SNORM -> GpuFormat.R16_SNORM;
			case RG16_SNORM -> GpuFormat.RG16_SNORM;
			case RGB16_SNORM -> GpuFormat.RGB16_SNORM;
			case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
			case R16F -> GpuFormat.R16_FLOAT;
			case RG16F -> GpuFormat.RG16_FLOAT;
			// Three-component floating-point render attachments are optional/poorly
			// supported on several mobile Vulkan drivers. Complementary's temporal
			// history (RGB16F colortex2) rendered correctly for two frames and then
			// returned all-NaN texels when linearly sampled on those drivers. Four-channel
			// storage is render/filter portable and preserves the shader-visible RGB data.
			case RGB16F -> GpuFormat.RGBA16_FLOAT;
			case RGBA16F -> GpuFormat.RGBA16_FLOAT;
			case R32F -> GpuFormat.R32_FLOAT;
			case RG32F -> GpuFormat.RG32_FLOAT;
			case RGB32F -> GpuFormat.RGBA32_FLOAT;
			case RGBA32F -> GpuFormat.RGBA32_FLOAT;
			case R8I -> GpuFormat.R8_SINT;
			case RG8I -> GpuFormat.RG8_SINT;
			case RGB8I -> GpuFormat.RGB8_SINT;
			case RGBA8I -> GpuFormat.RGBA8_SINT;
			case R8UI -> GpuFormat.R8_UINT;
			case RG8UI -> GpuFormat.RG8_UINT;
			case RGB8UI -> GpuFormat.RGB8_UINT;
			case RGBA8UI -> GpuFormat.RGBA8_UINT;
			case R16I -> GpuFormat.R16_SINT;
			case RG16I -> GpuFormat.RG16_SINT;
			case RGB16I -> GpuFormat.RGB16_SINT;
			case RGBA16I -> GpuFormat.RGBA16_SINT;
			case R16UI -> GpuFormat.R16_UINT;
			case RG16UI -> GpuFormat.RG16_UINT;
			case RGB16UI -> GpuFormat.RGB16_UINT;
			case RGBA16UI -> GpuFormat.RGBA16_UINT;
			case R32I -> GpuFormat.R32_SINT;
			case RG32I -> GpuFormat.RG32_SINT;
			case RGB32I -> GpuFormat.RGB32_SINT;
			case RGBA32I -> GpuFormat.RGBA32_SINT;
			case R32UI -> GpuFormat.R32_UINT;
			case RG32UI -> GpuFormat.RG32_UINT;
			case RGB32UI -> GpuFormat.RGB32_UINT;
			case RGBA32UI -> GpuFormat.RGBA32_UINT;
			case RGB5_A1, RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
			case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
			case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
			// Minecraft does not expose RGB9_E5. Widening preserves the HDR range and float sampling contract.
			case RGB9_E5 -> GpuFormat.RGBA16_FLOAT;
		};
	}
}
