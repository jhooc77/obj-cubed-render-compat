package io.github.jhooc77.objcubedcompat.mixin;

import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class ShadowRendererMixin {
    @Shadow @Final private OutlineBufferSource outlineBuffers;

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures()V",
            shift = At.Shift.AFTER
        )
    )
    private void objCubed$flushOutlineBuffers(CallbackInfo ci) {
        outlineBuffers.endOutlineBatch();
    }
}
