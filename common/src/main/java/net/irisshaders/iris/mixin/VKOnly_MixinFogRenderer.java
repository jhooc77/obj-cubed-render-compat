package net.irisshaders.iris.mixin;

import net.irisshaders.iris.vulkan.VulkanFrameState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the actual 26.2 fog distances without querying OpenGL or Sodium state. */
@Mixin(FogRenderer.class)
public class VKOnly_MixinFogRenderer {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void iris$captureFogRange(
		Camera camera,
		int renderDistance,
		DeltaTracker deltaTracker,
		float darkness,
		ClientLevel level,
		CallbackInfoReturnable<FogData> cir
	) {
		FogData fog = cir.getReturnValue();
		VulkanFrameState.setFogRange(fog.environmentalStart, fog.environmentalEnd);
	}
}
