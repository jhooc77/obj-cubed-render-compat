package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanGpuSampler.class)
public interface VKOnly_VulkanGpuSamplerAccessor {
	@Accessor("device")
	VulkanDevice iris$getDevice();

	@Mutable
	@Accessor("vkSampler")
	void iris$setVkSampler(long sampler);
}
