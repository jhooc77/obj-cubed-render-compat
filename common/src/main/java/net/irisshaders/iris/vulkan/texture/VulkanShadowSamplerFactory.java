package net.irisshaders.iris.vulkan.texture;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import net.irisshaders.iris.mixin.VKOnly_VulkanGpuSamplerAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.OptionalDouble;

/** Creates the comparison sampler missing from Minecraft 26.2's public sampler API. */
public final class VulkanShadowSamplerFactory {
	private VulkanShadowSamplerFactory() {
	}

	public static GpuSampler create(
		GpuDevice device,
		AddressMode addressMode,
		FilterMode minFilter,
		FilterMode magFilter,
		boolean compare,
		OptionalDouble maxLod
	) {
		GpuSampler generic = device.createSampler(addressMode, addressMode, minFilter, magFilter, 1, maxLod);
		if (!compare) {
			return generic;
		}
		if (!(generic instanceof VulkanGpuSampler sampler)) {
			generic.close();
			throw new IllegalStateException("Comparison shadows requested outside Minecraft's Vulkan backend");
		}

		VKOnly_VulkanGpuSamplerAccessor accessor = (VKOnly_VulkanGpuSamplerAccessor)(Object)sampler;
		VulkanDevice backend = accessor.iris$getDevice();
		long replacement;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
				.magFilter(VulkanConst.toVk(magFilter))
				.minFilter(VulkanConst.toVk(minFilter))
				.mipmapMode(maxLod.orElse(1000.0) > 0.25 ? 1 : 0)
				.addressModeU(VulkanConst.toVk(addressMode))
				.addressModeV(VulkanConst.toVk(addressMode))
				.addressModeW(VulkanConst.toVk(addressMode))
				.mipLodBias(0.0F)
				.minLod(0.0F)
				.maxLod(Math.max(0.25F, (float)maxLod.orElse(1000.0)))
				.anisotropyEnable(false)
				.maxAnisotropy(1.0F)
				.compareEnable(true)
				// Shader-pack shadow maps use forward-Z after the Vulkan compatibility conversion.
				.compareOp(3);
			LongBuffer pointer = stack.callocLong(1);
			VulkanUtils.crashIfFailure(backend, VK12.vkCreateSampler(backend.vkDevice(), info, null, pointer), "Can't create Iris shadow comparison sampler");
			replacement = pointer.get(0);
		}

		VK12.vkDestroySampler(backend.vkDevice(), sampler.vkSampler(), null);
		accessor.iris$setVkSampler(replacement);
		return sampler;
	}
}
