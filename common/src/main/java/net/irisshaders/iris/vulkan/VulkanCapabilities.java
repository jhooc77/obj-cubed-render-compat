package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_BASIC_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_QUAD_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUBGROUP_FEATURE_SHUFFLE_BIT;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * Capabilities exposed by the Iris Vulkan runtime.
 *
 * <p>This deliberately distinguishes Vulkan device support from support that
 * has actually been implemented by Iris. Shader-pack feature flags must never
 * advertise a feature merely because the physical device happens to expose it.</p>
 */
public final class VulkanCapabilities {
	private static final int MINECRAFT_COLOR_TARGET_LIMIT = 8;

	private static @Nullable DeviceInfo deviceInfo;
	private static int subgroupSize;
	private static int subgroupStages;
	private static int subgroupOperations;
	private static boolean vertexStageMismatchLogged;

	private VulkanCapabilities() {
	}

	public static void initialize(GpuDevice device) {
		deviceInfo = device.getDeviceInfo();
		querySubgroupProperties(device);
	}

	private static void querySubgroupProperties(GpuDevice device) {
		subgroupSize = 0;
		subgroupStages = 0;
		subgroupOperations = 0;
		vertexStageMismatchLogged = false;
		var backend = ((GpuDeviceAccessor)(Object)device).getBackend();
		if (!(backend instanceof VulkanDevice vulkan)) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack)
				.sType$Default();
			VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack)
				.sType$Default()
				.pNext(subgroup.address());
			vkGetPhysicalDeviceProperties2(vulkan.vkDevice().getPhysicalDevice(), properties);
			subgroupSize = subgroup.subgroupSize();
			subgroupStages = subgroup.supportedStages();
			subgroupOperations = subgroup.supportedOperations();
		}
		Iris.logger.info("Vulkan subgroup capabilities: size={}, stages=0x{}, operations=0x{}",
			subgroupSize, Integer.toHexString(subgroupStages), Integer.toHexString(subgroupOperations));
	}

	public static boolean isInitialized() {
		return deviceInfo != null;
	}

	public static DeviceInfo getDeviceInfo() {
		DeviceInfo info = deviceInfo;
		if (info == null) {
			throw new IllegalStateException("Iris Vulkan capabilities were queried before renderer initialization");
		}

		return info;
	}

	public static int maxColorAttachments() {
		return Math.min(MINECRAFT_COLOR_TARGET_LIMIT, getDeviceInfo().limits().maxColorAttachments());
	}

	public static boolean supportsCustomImages() {
		return false;
	}

	public static boolean supportsPerBufferBlending() {
		// Each Vulkan ColorTargetState carries its own blend function and the
		// pipeline compilers translate Iris' per-colortex overrides into it.
		return true;
	}

	public static boolean supportsComputeShaders() {
		return false;
	}

	public static boolean supportsTessellationShaders() {
		return false;
	}

	public static boolean supportsShaderStorageBuffers() {
		return false;
	}

	public static boolean supportsVertexSubgroupBasic() {
		return (subgroupStages & VK_SHADER_STAGE_VERTEX_BIT) != 0
			&& (subgroupOperations & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0;
	}

	public static boolean supportsVertexSubgroupShuffle() {
		return (subgroupStages & VK_SHADER_STAGE_VERTEX_BIT) != 0
			&& (subgroupOperations & VK_SUBGROUP_FEATURE_SHUFFLE_BIT) != 0;
	}

	public static boolean supportsVertexSubgroupQuad() {
		return (subgroupStages & VK_SHADER_STAGE_VERTEX_BIT) != 0
			&& (subgroupOperations & VK_SUBGROUP_FEATURE_QUAD_BIT) != 0;
	}

	public static int subgroupSize() {
		return subgroupSize;
	}

	public static boolean useNativeObjCubedQuad() {
		return subgroupSize >= 4 && subgroupSize % 4 == 0
			&& supportsSubgroupOperation(VK_SUBGROUP_FEATURE_BASIC_BIT)
			&& supportsSubgroupOperation(VK_SUBGROUP_FEATURE_QUAD_BIT);
	}

	public static void requireObjCubedSubgroups(String program) {
		if (subgroupSize >= 4 && subgroupSize % 4 == 0
			&& supportsSubgroupOperation(VK_SUBGROUP_FEATURE_BASIC_BIT)
			&& (supportsSubgroupOperation(VK_SUBGROUP_FEATURE_QUAD_BIT)
				|| supportsSubgroupOperation(VK_SUBGROUP_FEATURE_SHUFFLE_BIT))) {
			if ((subgroupStages & VK_SHADER_STAGE_VERTEX_BIT) == 0 && !vertexStageMismatchLogged) {
				// Some Android Vulkan stacks (notably Adreno through launchers) report
				// subgroup operations which their GLSL compiler accepts in terrain vertex
				// shaders while omitting VK_SHADER_STAGE_VERTEX_BIT here. Minecraft's own
				// resource-pack terrain pipeline is already the authoritative compile test,
				// so do not reject a working driver solely on this inconsistent stage mask.
				Iris.logger.warn(
					"Vulkan device does not advertise vertex subgroups (stages=0x{}) but exposes the required operations; attempting obj-cubed program '{}'",
					Integer.toHexString(subgroupStages), program
				);
				vertexStageMismatchLogged = true;
			}
			return;
		}
		DeviceInfo info = getDeviceInfo();
		throw new UnsupportedOperationException(
			"obj-cubed Vulkan program '" + program
				+ "' requires vertex subgroup basic plus quad or shuffle with a subgroup size divisible by 4; "
				+ "device='" + info.name() + "', driver='" + info.driverInfo() + "', subgroupSize=" + subgroupSize
				+ ", stages=0x" + Integer.toHexString(subgroupStages)
				+ ", operations=0x" + Integer.toHexString(subgroupOperations)
		);
	}

	private static boolean supportsSubgroupOperation(int operation) {
		return (subgroupOperations & operation) != 0;
	}
}
