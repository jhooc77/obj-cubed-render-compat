package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves a block quad's atlas UV while vanilla replaces UV0 with crack UVs. */
@Mixin(SheetedDecalTextureGenerator.class)
public abstract class MixinSheetedDecalTextureGenerator {
	@Shadow @Final private VertexConsumer delegate;

	@Unique private float iris$objCubedAtlasU;
	@Unique private float iris$objCubedAtlasV;
	@Unique private boolean iris$objCubedHasAtlasUv;

	@Inject(method = "addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("HEAD"))
	private void iris$beginObjCubedCrackVertex(float x, float y, float z,
		CallbackInfoReturnable<VertexConsumer> cir) {
		iris$objCubedHasAtlasUv = false;
	}

	@Inject(method = "setUv(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("HEAD"))
	private void iris$captureObjCubedAtlasUv(float u, float v,
		CallbackInfoReturnable<VertexConsumer> cir) {
		iris$objCubedAtlasU = u;
		iris$objCubedAtlasV = v;
		iris$objCubedHasAtlasUv = true;
	}

	@Inject(method = "setUv1(II)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("HEAD"), cancellable = true)
	private void iris$storeObjCubedAtlasUv(int u, int v,
		CallbackInfoReturnable<VertexConsumer> cir) {
		if (!iris$objCubedHasAtlasUv
			|| !Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))) {
			return;
		}
		int packedU = Math.round(Math.clamp(iris$objCubedAtlasU, 0.0F, 1.0F) * 32767.0F);
		int packedV = Math.round(Math.clamp(iris$objCubedAtlasV, 0.0F, 1.0F) * 32767.0F);
		delegate.setUv1(packedU, packedV);
		cir.setReturnValue((VertexConsumer) (Object) this);
	}
}
