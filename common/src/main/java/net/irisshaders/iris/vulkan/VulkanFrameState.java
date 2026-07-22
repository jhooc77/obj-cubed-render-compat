package net.irisshaders.iris.vulkan;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import net.irisshaders.iris.Iris;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;

/** Render-thread frame values shared by gbuffer, shadow and fullscreen uniform uploaders. */
public final class VulkanFrameState {
	private static final Matrix4f shadowModelView = new Matrix4f();
	private static final Matrix4f shadowProjection = new Matrix4f();
	private static float shadowAngle;
	private static float frameTime;
	private static float frameTimeSmooth;
	private static float frameTimeCounter;
	private static int frameCounter;
	private static Vec3 cameraPosition = Vec3.ZERO;
	private static Vec3 previousCameraPosition = Vec3.ZERO;
	private static float eyeBrightnessSmoothX;
	private static float eyeBrightnessSmoothY;
	private static float fogStart;
	private static float fogEnd;
	private static final Matrix4f gbufferModelView = new Matrix4f();
	private static final Matrix4f previousGbufferModelView = new Matrix4f();
	private static final Matrix4f gbufferProjection = new Matrix4f();
	private static final Matrix4f previousGbufferProjection = new Matrix4f();
	private static final Matrix4f frameModelViewScratch = new Matrix4f();
	private static final Matrix4f frameProjectionScratch = new Matrix4f();
	private static final Matrix4f frameInverseScratch = new Matrix4f();
	private static int matrixFrame = -1;
	private static boolean invalidProjectionLogged;
	private static boolean sodiumProjectionLogged;

	private VulkanFrameState() {
	}

	/** Restores the same temporal baseline used when Iris creates a new OpenGL pipeline. */
	public static void resetForPipeline() {
		SystemTimeUniforms.TIMER.reset();
		SystemTimeUniforms.COUNTER.reset();
		shadowModelView.identity();
		shadowProjection.identity();
		shadowAngle = 0.0F;
		frameTime = 0.0F;
		frameTimeSmooth = 0.0F;
		frameTimeCounter = 0.0F;
		frameCounter = 0;
		cameraPosition = Vec3.ZERO;
		previousCameraPosition = Vec3.ZERO;
		eyeBrightnessSmoothX = 0.0F;
		eyeBrightnessSmoothY = 0.0F;
		fogStart = 0.0F;
		fogEnd = 0.0F;
		gbufferModelView.identity();
		previousGbufferModelView.identity();
		gbufferProjection.identity();
		previousGbufferProjection.identity();
		matrixFrame = -1;
		invalidProjectionLogged = false;
		sodiumProjectionLogged = false;
	}

	public static void beginFrame(Matrix4fc renderedModelView) {
		long now = System.nanoTime();
		SystemTimeUniforms.TIMER.beginFrame(now);
		SystemTimeUniforms.COUNTER.beginFrame();
		IrisTimeUniforms.updateTime();
		frameTime = Math.clamp(SystemTimeUniforms.TIMER.getLastFrameTime(), 0.0F, 1.0F);
		frameTimeSmooth += (frameTime - frameTimeSmooth) * Math.min(1.0F, frameTime * 5.0F);
		frameTimeCounter = SystemTimeUniforms.TIMER.getFrameTimeCounter();
		frameCounter = SystemTimeUniforms.COUNTER.getAsInt();
		Minecraft minecraft = Minecraft.getInstance();
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 nextCameraPosition = camera.position();
		// A newly joined/reloaded world commonly exposes an origin or stale camera for
		// its first extracted frame. Never feed that jump into temporal reprojection.
		previousCameraPosition = frameCounter <= 1 ? nextCameraPosition : cameraPosition;
		cameraPosition = nextCameraPosition;
		float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		CapturedRenderingState captured = CapturedRenderingState.INSTANCE;
		captured.setTickDelta(partialTicks);
		captured.setRealTickDelta(minecraft.getDeltaTracker().getRealtimeDeltaTicks());

		// Use the matrix actually passed to LevelRenderer. It contains Iris' model-view
		// bobbing transform; rebuilding from Camera rotation alone makes temporal packs
		// reproject against a different view and leaves a moving ghost while bobbing.
		Matrix4f modelView = frameModelViewScratch.set(renderedModelView);
		Matrix4f projection = frameProjectionScratch;
		if (minecraft.gameRenderer instanceof GameRendererStorage storage) {
			// Sodium captures the exact world projection uploaded by renderLevel. This
			// includes FOV/bobbing changes and, unlike Camera's cached projection, is
			// guaranteed to have been produced in the current renderLevel invocation.
			projection.set(storage.sodium$getProjectionMatrix());
		} else {
			camera.getViewRotationProjectionMatrix(projection)
				.mul(frameInverseScratch.set(modelView).invert());
		}
		sanitizeExtractedWorldProjection(projection);
		updateMatrices(modelView, projection);
		captured.setGbufferModelView(gbufferModelView);
		captured.setGbufferProjection(gbufferProjection);
		int fogColor = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, partialTicks);
		captured.setFogColor(ARGB.redFloat(fogColor), ARGB.greenFloat(fogColor), ARGB.blueFloat(fogColor));
		if (minecraft.level != null) {
			captured.setCloudTime(minecraft.level.getGameTime() + partialTicks);
		}
		Entity cameraEntity = minecraft.getCameraEntity();
		if (minecraft.level != null && cameraEntity != null) {
			BlockPos eye = BlockPos.containing(cameraEntity.getX(), cameraEntity.getEyeY(), cameraEntity.getZ());
			float targetX = minecraft.level.getBrightness(LightLayer.BLOCK, eye) * 16.0F;
			float targetY = minecraft.level.getBrightness(LightLayer.SKY, eye) * 16.0F;
			float factor = Math.min(1.0F, frameTime * 5.0F);
			eyeBrightnessSmoothX += (targetX - eyeBrightnessSmoothX) * factor;
			eyeBrightnessSmoothY += (targetY - eyeBrightnessSmoothY) * factor;
		}
	}

	public static void setShadow(Matrix4f modelView, Matrix4f projection, float angle) {
		shadowModelView.set(modelView);
		shadowProjection.set(projection);
		shadowAngle = angle;
	}

	public static Matrix4f shadowModelView() {
		return new Matrix4f(shadowModelView);
	}
	public static Matrix4f copyShadowModelView(Matrix4f destination) { return destination.set(shadowModelView); }

	public static Matrix4f shadowProjection() {
		return new Matrix4f(shadowProjection);
	}
	public static Matrix4f copyShadowProjection(Matrix4f destination) { return destination.set(shadowProjection); }

	public static float shadowAngle() {
		return shadowAngle;
	}

	public static float frameTime() { return frameTime; }
	public static float frameTimeSmooth() { return frameTimeSmooth; }
	public static float frameTimeCounter() { return frameTimeCounter; }
	public static int frameCounter() { return frameCounter; }
	public static Vec3 cameraPosition() { return cameraPosition; }
	public static Vec3 previousCameraPosition() { return previousCameraPosition; }

	public static void updateMatrices(Matrix4f modelView, Matrix4f projection) {
		if (matrixFrame == frameCounter) return;
		if (matrixFrame < 0 || frameCounter <= 2) {
			// Treat both startup frames as temporal keyframes. Frame one can contain a
			// pre-spawn camera orientation on mobile; frame two is the first stable view
			// and must not reproject through that stale orientation.
			previousGbufferModelView.set(modelView);
			previousGbufferProjection.set(projection);
		} else {
			previousGbufferModelView.set(gbufferModelView);
			previousGbufferProjection.set(gbufferProjection);
		}
		gbufferModelView.set(modelView);
		gbufferProjection.set(projection);
		matrixFrame = frameCounter;
	}

	public static Matrix4f gbufferModelView() { return new Matrix4f(gbufferModelView); }
	public static Matrix4f previousGbufferModelView() { return new Matrix4f(previousGbufferModelView); }
	public static Matrix4f gbufferProjection() { return new Matrix4f(gbufferProjection); }
	public static Matrix4f previousGbufferProjection() { return new Matrix4f(previousGbufferProjection); }
	public static Matrix4f copyGbufferModelView(Matrix4f destination) { return destination.set(gbufferModelView); }
	public static Matrix4f copyPreviousGbufferModelView(Matrix4f destination) { return destination.set(previousGbufferModelView); }
	public static Matrix4f copyGbufferProjection(Matrix4f destination) { return destination.set(gbufferProjection); }
	public static Matrix4f copyPreviousGbufferProjection(Matrix4f destination) { return destination.set(previousGbufferProjection); }

	public static float eyeBrightnessSmoothX() { return eyeBrightnessSmoothX; }
	public static float eyeBrightnessSmoothY() { return eyeBrightnessSmoothY; }

	public static void setFogRange(float start, float end) {
		fogStart = start;
		fogEnd = end;
	}

	public static float fogStart() { return fogStart; }
	public static float fogEnd() { return fogEnd; }

	/**
	 * Converts a cached Vulkan reverse-Z projection into the OpenGL forward-Z
	 * convention expected by shader packs. CameraRenderState is extracted before
	 * renderLevel enables a newly selected pack, so its first matrix can otherwise
	 * retain reverse-Z for exactly one frame and poison temporal render targets.
	 */
	public static Matrix4f ensureOpenGlForwardProjection(Matrix4f projection) {
		boolean reversePerspective = Math.abs(projection.m33()) < 0.0001F
			&& projection.m23() < -0.5F && projection.m32() > 0.0F;
		boolean reverseOrtho = Math.abs(projection.m33() - 1.0F) < 0.0001F
			&& Math.abs(projection.m23()) < 0.0001F && projection.m22() > 0.0F;
		if (!reversePerspective && !reverseOrtho) return projection;

		float z0 = projection.m02();
		float z1 = projection.m12();
		float z2 = projection.m22();
		float z3 = projection.m32();
		projection.m02(projection.m03() - 2.0F * z0);
		projection.m12(projection.m13() - 2.0F * z1);
		projection.m22(projection.m23() - 2.0F * z2);
		projection.m32(projection.m33() - 2.0F * z3);
		return projection;
	}

	/**
	 * Normalizes the projection extracted by GameRenderer and repairs the single
	 * startup frame where Minecraft can expose a zero-FOV (infinite-scale)
	 * matrix while Iris is rebuilding the Sodium world renderer.
	 */
	public static Matrix4f sanitizeExtractedWorldProjection(Matrix4f projection) {
		ensureOpenGlForwardProjection(projection);
		if (isUsableProjection(projection)) return projection;

		Minecraft minecraft = Minecraft.getInstance();
		int width = Math.max(1, minecraft.gameRenderer.mainRenderTarget().width);
		int height = Math.max(1, minecraft.gameRenderer.mainRenderTarget().height);
		float fov = minecraft.gameRenderer.mainCamera().getFov();
		if (!Float.isFinite(fov) || fov < 10.0F || fov >= 179.0F) {
			fov = minecraft.options.fov().get();
		}
		if (!Float.isFinite(fov) || fov <= 1.0F || fov >= 179.0F) fov = 70.0F;
		projection.setPerspective((float)Math.toRadians(fov), (float)width / (float)height,
			0.05F, 1000.0F, false);
		if (!invalidProjectionLogged) {
			Iris.logger.warn(
				"Iris Vulkan repaired an invalid startup world projection (fov={} size={}x{})",
				fov, width, height
			);
			invalidProjectionLogged = true;
		}
		return projection;
	}

	/**
	 * Sodium snapshots GameRenderer's matrix before another mixin may rewrite the
	 * ProjectionMatrixBuffer argument. Normalize the matrix again at the stable
	 * ChunkRenderMatrices boundary so u_Globals and entity DynamicTransforms use
	 * the same forward-Z convention.
	 */
	public static Matrix4f normalizeSodiumProjection(Matrix4fc projection) {
		Matrix4f normalized = new Matrix4f(projection);
		boolean reverse = isReverseProjection(normalized);
		sanitizeExtractedWorldProjection(normalized);
		if (!sodiumProjectionLogged) {
			Iris.logger.info(
				"Iris Vulkan Sodium projection bridge: input={} output=(m00={},m11={},m22={},m32={})",
				reverse ? "reverse-Z" : "forward-Z",
				normalized.m00(), normalized.m11(), normalized.m22(), normalized.m32()
			);
			sodiumProjectionLogged = true;
		}
		return normalized;
	}

	private static boolean isReverseProjection(Matrix4f projection) {
		return (Math.abs(projection.m33()) < 0.0001F
			&& projection.m23() < -0.5F && projection.m32() > 0.0F)
			|| (Math.abs(projection.m33() - 1.0F) < 0.0001F
			&& Math.abs(projection.m23()) < 0.0001F && projection.m22() > 0.0F);
	}

	private static boolean isUsableProjection(Matrix4f projection) {
		return Float.isFinite(projection.m00()) && Float.isFinite(projection.m01())
			&& Float.isFinite(projection.m02()) && Float.isFinite(projection.m03())
			&& Float.isFinite(projection.m10()) && Float.isFinite(projection.m11())
			&& Float.isFinite(projection.m12()) && Float.isFinite(projection.m13())
			&& Float.isFinite(projection.m20()) && Float.isFinite(projection.m21())
			&& Float.isFinite(projection.m22()) && Float.isFinite(projection.m23())
			&& Float.isFinite(projection.m30()) && Float.isFinite(projection.m31())
			&& Float.isFinite(projection.m32()) && Float.isFinite(projection.m33())
			&& Math.abs(projection.m00()) > 0.000001F
			&& Math.abs(projection.m11()) > 0.000001F
			// During a pipeline rebuild Minecraft can expose one finite but effectively
			// zero-FOV matrix (observed as m00=25/m11=54 on Adreno). It is numerically
			// invertible, yet poisons TAA just as surely as NaN. Reject that transient
			// only for the first frame; legitimate later zoom/spyglass projections stay.
			&& (frameCounter > 1
				|| (Math.abs(projection.m00()) <= 10.0F && Math.abs(projection.m11()) <= 10.0F));
	}
}
