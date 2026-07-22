package net.irisshaders.iris.mixin;

import net.irisshaders.iris.mixinterface.ExtendedBiome;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Backend-neutral biome data needed by shader.properties custom uniforms on Vulkan. */
@Mixin(value = Biome.class, priority = 990)
public class VKOnly_MixinBiome implements ExtendedBiome {
	@Shadow @Final private Biome.ClimateSettings climateSettings;
	@Unique private int iris$vulkanBiomeCategory = -1;

	@Override
	public int getBiomeCategory() {
		return iris$vulkanBiomeCategory;
	}

	@Override
	public void setBiomeCategory(int biomeCategory) {
		iris$vulkanBiomeCategory = biomeCategory;
	}

	@Override
	public float getDownfall() {
		return climateSettings.downfall();
	}
}
