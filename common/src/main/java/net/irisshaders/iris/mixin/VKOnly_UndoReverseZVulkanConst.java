package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vulkan.VulkanConst;
import net.irisshaders.iris.vulkan.IrisVulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Inverts Minecraft's reverse-Z compare operations for forward-Z shader-pack projections. */
@Mixin(VulkanConst.class)
public class VKOnly_UndoReverseZVulkanConst {
	@Inject(method = "toVk(Lcom/mojang/blaze3d/platform/CompareOp;)I", at = @At("HEAD"), cancellable = true, remap = false)
	private static void iris$forwardDepthCompare(CompareOp op, CallbackInfoReturnable<Integer> cir) {
		if (!IrisVulkan.usesShaderPackDepthConvention()) {
			return;
		}
		cir.setReturnValue(switch (op) {
			case ALWAYS_PASS -> 7;
			case LESS_THAN -> 4;
			case LESS_THAN_OR_EQUAL -> 6;
			case EQUAL -> 2;
			case NOT_EQUAL -> 5;
			case GREATER_THAN_OR_EQUAL -> 3;
			case GREATER_THAN -> 1;
			case NEVER_PASS -> 0;
		});
	}
}
