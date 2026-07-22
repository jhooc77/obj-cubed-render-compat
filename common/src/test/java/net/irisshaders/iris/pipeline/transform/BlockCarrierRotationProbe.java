package net.irisshaders.iris.pipeline.transform;

import com.mojang.math.OctahedralGroup;
import com.mojang.math.Quadrant;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.CuboidFace.UVs;
import net.minecraft.core.Direction;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Locale;

/** Prints the exact baked position/UV relationship of an obj-cubed BLOCK carrier. */
public final class BlockCarrierRotationProbe {
	private static final Vector3f FROM = new Vector3f(8.0F, 8.0F, 8.0F);
	private static final Vector3f TO = new Vector3f(24.0F, 24.0F, 8.0F);
	private static final Vector3f CENTER = new Vector3f(0.5F, 0.5F, 0.5F);
	private static final UVs UVS = new UVs(5.1F / 4.0F, 7.1F / 4.0F, 5.9F / 4.0F, 7.9F / 4.0F);

	private BlockCarrierRotationProbe() {
	}

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		for (Quadrant yaw : Quadrant.values()) {
			OctahedralGroup group = Quadrant.fromXYAngles(Quadrant.R0, yaw);
			ModelState state = BlockModelRotation.get(group).withUvLock();
			Direction facing = group.rotate(Direction.NORTH);
			Vector3f[] positions = new Vector3f[4];
			Vector3f[] uvs = new Vector3f[4];
			FaceInfo sourceFace = FaceInfo.fromFacing(Direction.NORTH);
			for (int corner = 0; corner < 4; corner++) {
				positions[corner] = sourceFace.getVertexInfo(corner).select(FROM, TO).div(16.0F);
				rotateAroundCenter(positions[corner], state.transformation());

				float u = CuboidFace.getU(UVS, Quadrant.R0, corner);
				float v = CuboidFace.getV(UVS, Quadrant.R0, corner);
				uvs[corner] = new Vector3f(u - 0.5F, v - 0.5F, 0.0F);
				state.inverseFaceTransformation(Direction.NORTH).transformPosition(uvs[corner]);
				uvs[corner].add(0.5F, 0.5F, 0.0F);
			}

			Vector3f[] bakedPositions = positions.clone();
			Vector3f[] bakedUvs = uvs.clone();
			recalculateWinding(bakedPositions, bakedUvs, facing);
			System.out.printf("yaw=%s group=%s facing=%s%n", yaw, group, facing);
			Vector3f anchor = null;
			Vector3f role1 = null;
			Vector3f role3 = null;
			for (int bakedCorner = 0; bakedCorner < 4; bakedCorner++) {
				Vector3f position = bakedPositions[bakedCorner];
				Vector3f uv = bakedUvs[bakedCorner];
				int uRole = fraction(uv.x * 64.0F) >= 0.5F ? 1 : 0;
				int vRole = fraction(uv.y * 64.0F) >= 0.5F ? 1 : 0;
				int role = uRole == 0 ? (vRole == 0 ? 0 : 1) : (vRole == 1 ? 2 : 3);
				if (role != bakedCorner) {
					throw new IllegalStateException("uvlock changed carrier role at " + yaw
						+ ": baked=" + bakedCorner + " role=" + role);
				}
				if (role == 1) role1 = position;
				if (role == 2) anchor = position;
				if (role == 3) role3 = position;
				System.out.printf("  baked=%d pos=(%.1f,%.1f,%.1f) delta=(%+.1f,%+.1f,%+.1f) uv=(%.4f,%.4f) role=%d%d%n",
					bakedCorner, position.x, position.y, position.z,
					position.x - CENTER.x, position.y - CENTER.y, position.z - CENTER.z,
					uv.x, uv.y, uRole, vRole);
			}
			if (anchor == null || anchor.distanceSquared(CENTER) > 1.0E-6F) {
				throw new IllegalStateException("role-2 carrier anchor moved for " + yaw + ": " + anchor);
			}
			if (role1 == null || role3 == null) {
				throw new IllegalStateException("Missing carrier basis roles for " + yaw);
			}
			Vector3f axisX = new Vector3f(role1).sub(anchor).normalize();
			Vector3f axisY = new Vector3f(role3).sub(anchor).normalize();
			Vector3f axisZ = new Vector3f(axisX).cross(axisY).normalize();
			Vector3f sample = new Vector3f(0.25F, 0.5F, 0.75F);
			Vector3f recovered = new Vector3f(axisX).mul(sample.x)
				.add(new Vector3f(axisY).mul(sample.y))
				.add(new Vector3f(axisZ).mul(sample.z));
			Vector3f expected = state.transformation().getMatrix().transformDirection(new Vector3f(sample));
			if (recovered.distanceSquared(expected) > 1.0E-6F) {
				throw new IllegalStateException("Recovered carrier rotation differs at " + yaw
					+ ": recovered=" + recovered + " expected=" + expected);
			}
		}
		System.out.println("Block carrier rotation probe passed: anchor and decoded offset basis match all four uvlocked y rotations");
	}

	private static void rotateAroundCenter(Vector3f position, Transformation transformation) {
		if (transformation != Transformation.IDENTITY) {
			position.sub(CENTER);
			transformation.getMatrix().transformPosition(position);
			position.add(CENTER);
		}
	}

	private static void recalculateWinding(Vector3f[] positions, Vector3f[] uvs, Direction facing) {
		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (Vector3f position : positions) {
			minX = Math.min(minX, position.x);
			minY = Math.min(minY, position.y);
			minZ = Math.min(minZ, position.z);
			maxX = Math.max(maxX, position.x);
			maxY = Math.max(maxY, position.y);
			maxZ = Math.max(maxZ, position.z);
		}
		FaceInfo bakedFace = FaceInfo.fromFacing(facing);
		for (int target = 0; target < 4; target++) {
			Vector3f wanted = bakedFace.getVertexInfo(target).select(
				new Vector3f(minX, minY, minZ), new Vector3f(maxX, maxY, maxZ));
			int found = -1;
			for (int candidate = target; candidate < 4; candidate++) {
				if (positions[candidate].distanceSquared(wanted) < 1.0E-6F) {
					found = candidate;
					break;
				}
			}
			if (found < 0) throw new IllegalStateException("Missing baked carrier vertex " + wanted);
			if (found != target) {
				Vector3f p = positions[target]; positions[target] = positions[found]; positions[found] = p;
				Vector3f uv = uvs[target]; uvs[target] = uvs[found]; uvs[found] = uv;
			}
		}
	}

	private static float fraction(float value) {
		return value - (float)Math.floor(value);
	}
}
