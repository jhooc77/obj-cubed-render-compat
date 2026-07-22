package io.github.jhooc77.objcubedcompat.mixin;

import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisSamplers.class, remap = false)
public abstract class IrisSamplersMixin {
    @Inject(method = "addLevelSamplers", at = @At("HEAD"))
    private static void objCubed$addAtlasSampler(
        SamplerHolder samplers,
        WorldRenderingPipeline pipeline,
        AbstractTexture whitePixel,
        boolean hasTexture,
        boolean hasLightmap,
        boolean hasOverlay,
        CallbackInfo ci
    ) {
        samplers.addDynamicSampler(
            () -> ((GpuTextureInterface) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture()).iris$getGlId(),
            GlSampler.NEAREST,
            "iris_ObjCubedAtlasSampler"
        );

        if (hasTexture) {
            samplers.addExternalSampler(IrisSamplers.ALBEDO_TEXTURE_UNIT, "iris_ObjCubedSampler");
        } else {
            samplers.addDynamicSampler(
                () -> ((GpuTextureInterface) whitePixel.getTexture()).iris$getGlId(),
                GlSampler.NEAREST,
                "iris_ObjCubedSampler"
            );
        }
    }
}
