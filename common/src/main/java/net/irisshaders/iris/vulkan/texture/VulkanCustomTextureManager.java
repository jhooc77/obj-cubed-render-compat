package net.irisshaders.iris.vulkan.texture;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;

/** Owns shader-pack custom textures without touching OpenGL texture IDs. */
public final class VulkanCustomTextureManager implements AutoCloseable {
	private final GpuDevice device;
	private final EnumMap<TextureStage, Map<String, TextureSource>> stageTextures = new EnumMap<>(TextureStage.class);
	private final Map<String, TextureSource> irisTextures = new LinkedHashMap<>();
	private final List<GpuTexture> ownedTextures = new ArrayList<>();
	private final List<GpuTextureView> ownedViews = new ArrayList<>();
	private final List<GpuSampler> ownedSamplers = new ArrayList<>();
	private final TextureSource noise;

	public VulkanCustomTextureManager(GpuDevice device, ShaderPack pack, PackDirectives directives) {
		this.device = device;
		pack.getCustomTextureDataMap().forEach((stage, textures) -> {
			Map<String, TextureSource> compiled = new LinkedHashMap<>();
			textures.forEach((name, data) -> addTexture(compiled, stage + "/" + name, data));
			stageTextures.put(stage, Map.copyOf(compiled));
		});
		pack.getIrisCustomTextureDataMap().forEach((name, data) -> addTexture(irisTextures, name, data));
		noise = pack.getCustomNoiseTexture() == null
			? createNoise(directives.getNoiseTextureResolution())
			: createRequired("noisetex", pack.getCustomNoiseTexture());
	}

	public @Nullable Binding find(TextureStage stage, String samplerName) {
		if ("noisetex".equals(samplerName)) return noise.resolve();
		TextureSource source = stageTextures.getOrDefault(stage, Map.of()).get(samplerName);
		if (source == null) source = irisTextures.get(samplerName);
		return source == null ? null : source.resolve();
	}

	private void addTexture(Map<String, TextureSource> target, String name, CustomTextureData data) {
		try {
			target.put(name.substring(name.lastIndexOf('/') + 1), create(name, data));
		} catch (RuntimeException | IOException exception) {
			Iris.logger.error("Could not create Vulkan shader-pack texture '{}'", name, exception);
		}
	}

	private TextureSource createRequired(String name, CustomTextureData data) {
		try {
			return create(name, data);
		} catch (IOException exception) {
			throw new IllegalStateException("Could not create required Vulkan shader-pack texture " + name, exception);
		}
	}

	private TextureSource create(String name, CustomTextureData data) throws IOException {
		if (data instanceof CustomTextureData.PngData png) {
			try (NativeImage image = NativeImage.read(png.getContent())) {
				return createImage(name, image, png.getFilteringData());
			}
		}
		if (data instanceof CustomTextureData.RawData2D raw && !(data instanceof CustomTextureData.RawDataRect)) {
			return createRaw2D(name, raw);
		}
		if (data instanceof CustomTextureData.ResourceData resource) {
			Identifier id = Identifier.fromNamespaceAndPath(resource.getNamespace(), resource.getLocation());
			return () -> {
				AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(id);
				if (texture == null) {
					texture = Minecraft.getInstance().getTextureManager().getTexture(MissingTextureAtlasSprite.getLocation());
				}
				return texture == null ? null : new Binding(texture.getTextureView(), texture.getSampler());
			};
		}
		if (data instanceof CustomTextureData.LightmapMarker) {
			GpuSampler sampler = createSampler(new TextureFilteringData(false, true));
			ownedSamplers.add(sampler);
			return () -> new Binding(Minecraft.getInstance().gameRenderer.levelLightmap(), sampler);
		}
		throw new UnsupportedOperationException("Vulkan custom texture type is not supported: " + data.getClass().getSimpleName());
	}

	private TextureSource createImage(String name, NativeImage image, TextureFilteringData filtering) {
		GpuTexture texture = device.createTexture(
			"Iris Vulkan custom texture " + name,
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM,
			image.getWidth(), image.getHeight(), 1, 1
		);
		GpuTextureView view = device.createTextureView(texture);
		GpuSampler sampler = createSampler(filtering);
		device.createCommandEncoder().writeToTexture(texture, image);
		ownedTextures.add(texture);
		ownedViews.add(view);
		ownedSamplers.add(sampler);
		Binding binding = new Binding(view, sampler);
		return () -> binding;
	}

	private TextureSource createRaw2D(String name, CustomTextureData.RawData2D raw) {
		GpuFormat format = rawFormat(raw.getInternalFormat(), raw.getPixelFormat(), raw.getPixelType());
		GpuTexture texture = device.createTexture(
			"Iris Vulkan raw texture " + name,
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			format,
			raw.getSizeX(), raw.getSizeY(), 1, 1
		);
		GpuTextureView view = device.createTextureView(texture);
		GpuSampler sampler = createSampler(raw.getFilteringData());
		ByteBuffer content = MemoryUtil.memAlloc(raw.getContent().length);
		try {
			content.put(raw.getContent()).flip();
			device.createCommandEncoder().writeToTexture(texture, content, 0, 0, 0, 0,
				raw.getSizeX(), raw.getSizeY());
		} finally {
			MemoryUtil.memFree(content);
		}
		ownedTextures.add(texture);
		ownedViews.add(view);
		ownedSamplers.add(sampler);
		Binding binding = new Binding(view, sampler);
		return () -> binding;
	}

	private TextureSource createNoise(int size) {
		try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false)) {
			Random random = new Random(0L);
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) image.setPixel(x, y, random.nextInt() | 0xff000000);
			}
			return createImage("noisetex", image, new TextureFilteringData(true, false));
		}
	}

	private GpuSampler createSampler(TextureFilteringData filtering) {
		AddressMode address = filtering.shouldClamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
		FilterMode filter = filtering.shouldBlur() ? FilterMode.LINEAR : FilterMode.NEAREST;
		return device.createSampler(address, address, filter, filter, 1, OptionalDouble.of(0.0));
	}

	private static GpuFormat rawFormat(InternalTextureFormat internal, PixelFormat pixels, PixelType type) {
		if (internal.getPixelFormat() != pixels) {
			throw new UnsupportedOperationException("Vulkan raw texture channel conversion is not implemented (" + pixels + " => " + internal + ")");
		}
		return switch (internal) {
			case RGBA, RGBA8 -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.RGBA8_UNORM);
			case R8 -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.R8_UNORM);
			case RG8 -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.RG8_UNORM);
			case RGB8 -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.RGB8_UNORM);
			case R8_SNORM -> require(type, PixelType.BYTE, GpuFormat.R8_SNORM);
			case RG8_SNORM -> require(type, PixelType.BYTE, GpuFormat.RG8_SNORM);
			case RGB8_SNORM -> require(type, PixelType.BYTE, GpuFormat.RGB8_SNORM);
			case RGBA8_SNORM -> require(type, PixelType.BYTE, GpuFormat.RGBA8_SNORM);
			case R16F -> require(type, PixelType.HALF_FLOAT, GpuFormat.R16_FLOAT);
			case RG16F -> require(type, PixelType.HALF_FLOAT, GpuFormat.RG16_FLOAT);
			case RGB16F -> require(type, PixelType.HALF_FLOAT, GpuFormat.RGB16_FLOAT);
			case RGBA16F -> require(type, PixelType.HALF_FLOAT, GpuFormat.RGBA16_FLOAT);
			case R32F -> require(type, PixelType.FLOAT, GpuFormat.R32_FLOAT);
			case RG32F -> require(type, PixelType.FLOAT, GpuFormat.RG32_FLOAT);
			case RGB32F -> require(type, PixelType.FLOAT, GpuFormat.RGB32_FLOAT);
			case RGBA32F -> require(type, PixelType.FLOAT, GpuFormat.RGBA32_FLOAT);
			case R8I -> require(type, PixelType.BYTE, GpuFormat.R8_SINT);
			case RG8I -> require(type, PixelType.BYTE, GpuFormat.RG8_SINT);
			case RGB8I -> require(type, PixelType.BYTE, GpuFormat.RGB8_SINT);
			case RGBA8I -> require(type, PixelType.BYTE, GpuFormat.RGBA8_SINT);
			case R8UI -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.R8_UINT);
			case RG8UI -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.RG8_UINT);
			case RGB8UI -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.RGB8_UINT);
			case RGBA8UI -> require(type, PixelType.UNSIGNED_BYTE, GpuFormat.RGBA8_UINT);
			case R16I -> require(type, PixelType.SHORT, GpuFormat.R16_SINT);
			case RG16I -> require(type, PixelType.SHORT, GpuFormat.RG16_SINT);
			case RGB16I -> require(type, PixelType.SHORT, GpuFormat.RGB16_SINT);
			case RGBA16I -> require(type, PixelType.SHORT, GpuFormat.RGBA16_SINT);
			case R16UI -> require(type, PixelType.UNSIGNED_SHORT, GpuFormat.R16_UINT);
			case RG16UI -> require(type, PixelType.UNSIGNED_SHORT, GpuFormat.RG16_UINT);
			case RGB16UI -> require(type, PixelType.UNSIGNED_SHORT, GpuFormat.RGB16_UINT);
			case RGBA16UI -> require(type, PixelType.UNSIGNED_SHORT, GpuFormat.RGBA16_UINT);
			case R32I -> require(type, PixelType.INT, GpuFormat.R32_SINT);
			case RG32I -> require(type, PixelType.INT, GpuFormat.RG32_SINT);
			case RGB32I -> require(type, PixelType.INT, GpuFormat.RGB32_SINT);
			case RGBA32I -> require(type, PixelType.INT, GpuFormat.RGBA32_SINT);
			case R32UI -> require(type, PixelType.UNSIGNED_INT, GpuFormat.R32_UINT);
			case RG32UI -> require(type, PixelType.UNSIGNED_INT, GpuFormat.RG32_UINT);
			case RGB32UI -> require(type, PixelType.UNSIGNED_INT, GpuFormat.RGB32_UINT);
			case RGBA32UI -> require(type, PixelType.UNSIGNED_INT, GpuFormat.RGBA32_UINT);
			default -> throw new UnsupportedOperationException("Vulkan raw texture format is not implemented: " + internal);
		};
	}

	private static GpuFormat require(PixelType actual, PixelType expected, GpuFormat format) {
		if (actual != expected) {
			throw new UnsupportedOperationException("Vulkan raw texture type conversion is not implemented: " + actual + " => " + expected);
		}
		return format;
	}

	@Override
	public void close() {
		ownedViews.forEach(GpuTextureView::close);
		ownedTextures.forEach(GpuTexture::close);
		ownedSamplers.forEach(GpuSampler::close);
		ownedViews.clear();
		ownedTextures.clear();
		ownedSamplers.clear();
		stageTextures.clear();
		irisTextures.clear();
	}

	private interface TextureSource {
		@Nullable Binding resolve();
	}

	public record Binding(GpuTextureView view, GpuSampler sampler) {
	}
}
