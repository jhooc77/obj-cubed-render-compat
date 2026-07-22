package net.irisshaders.iris.mixin;

import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Recompiles shader-pack pipelines after server/client resource packs finish reloading. */
@Mixin(Minecraft.class)
public class VKOnly_MixinMinecraftReload {
	@Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
	private void iris$rebuildVulkanPipelineAfterResources(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		cir.getReturnValue().thenRun(() -> {
			CapturedRenderingState.INSTANCE.incrementTextureReloadCount();
			IrisVulkan.requestPipelineRebuild();
		});
	}
}
