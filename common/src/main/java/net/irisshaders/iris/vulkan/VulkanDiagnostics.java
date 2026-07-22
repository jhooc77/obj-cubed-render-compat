package net.irisshaders.iris.vulkan;

/** Expensive Vulkan diagnostics kept available for field debugging, but disabled in production. */
public final class VulkanDiagnostics {
	private static final boolean ENABLED = Boolean.getBoolean("iris.vulkan.diagnostics");

	private VulkanDiagnostics() {
	}

	public static boolean enabled() {
		return ENABLED;
	}
}
