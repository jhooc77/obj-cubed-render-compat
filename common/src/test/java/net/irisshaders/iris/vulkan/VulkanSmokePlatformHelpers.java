package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

/** Minimal platform service used only by the standalone shaderpack smoke test. */
public final class VulkanSmokePlatformHelpers implements IrisPlatformHelpers {
	@Override public boolean isModLoaded(String modId) { return false; }
	@Override public String getVersion() { return "1.11.2"; }
	@Override public boolean isDevelopmentEnvironment() { return false; }
	@Override public Path getGameDir() { return Path.of(".").toAbsolutePath(); }
	@Override public Path getConfigDir() { return getGameDir().resolve("config"); }
	@Override public int compareVersions(String currentVersion, String semanticVersion) { return currentVersion.compareTo(semanticVersion); }
	@Override public KeyMapping registerKeyBinding(KeyMapping keyMapping) { return keyMapping; }
	@Override public boolean useELS() { return false; }
	@Override public BlockState getBlockAppearance(BlockAndTintGetter level, BlockState state, Direction cullFace, BlockPos pos) { return state; }
}
