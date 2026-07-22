package net.irisshaders.iris.mixin;

import net.irisshaders.iris.uniforms.BiomeUniforms;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Populates the legacy OptiFine biome id map when the Vulkan-only mixin set is active. */
@Mixin(Biomes.class)
public class VKOnly_MixinBiomes {
	@Unique private static int iris$vulkanBiomeId;

	@Inject(method = "register", at = @At("TAIL"))
	private static void iris$registerVulkanBiome(String name, CallbackInfoReturnable<ResourceKey<Biome>> cir) {
		BiomeUniforms.getBiomeMap().put(cir.getReturnValue(), iris$vulkanBiomeId++);
	}
}
