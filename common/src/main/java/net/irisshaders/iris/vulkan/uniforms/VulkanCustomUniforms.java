package net.irisshaders.iris.vulkan.uniforms;

import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import net.irisshaders.iris.parsing.MatrixType;
import net.irisshaders.iris.parsing.VectorType;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Evaluates shader.properties custom uniforms and serializes them into Vulkan std140 storage. */
public final class VulkanCustomUniforms {
	private final FrameUpdateNotifier updateNotifier = new FrameUpdateNotifier();
	private final CustomUniforms uniforms;
	private final Set<String> warnedInputs = new HashSet<>();

	public VulkanCustomUniforms(ShaderPack pack, NamespacedId dimension) {
		var programSet = pack.getProgramSet(dimension);
		this.uniforms = pack.customUniforms.build(holder -> CommonUniforms.addNonDynamicUniforms(
			holder,
			pack.getIdMap(),
			programSet.getPackDirectives(),
			updateNotifier
		));
	}

	public void beginFrame() {
		updateNotifier.onNewFrame();
		uniforms.updateSafely((name, throwable) -> {
			if (warnedInputs.add(name)) {
				Iris.logger.warn("Vulkan custom-uniform input '{}' is unavailable; keeping its previous/default value", name, throwable);
			}
		});
	}

	public void writeTo(
		ByteBuffer target,
		Map<String, VulkanShaderTransformResult.UniformMember> members
	) {
		FunctionReturn value = new FunctionReturn();
		uniforms.forEachValue(uniform -> writeValue(target, members.get(uniform.getName()), uniform, value));
	}

	private static void writeValue(
		ByteBuffer target,
		VulkanShaderTransformResult.UniformMember member,
		CachedUniform uniform,
		FunctionReturn value
	) {
		if (member == null) return;
		uniform.writeTo(value);
		Type type = uniform.getType();
		int offset = member.offset();

		if (type.equals(Type.Boolean)) {
			if (member.size() >= 4) target.putInt(offset, value.booleanReturn ? 1 : 0);
		} else if (type.equals(Type.Int)) {
			if (member.size() >= 4) target.putInt(offset, value.intReturn);
		} else if (type.equals(Type.Float)) {
			if (member.size() >= 4) target.putFloat(offset, value.floatReturn);
		} else if (type.equals(VectorType.VEC2) && value.objectReturn instanceof Vector2f vector) {
			if (member.size() >= 8) {
				target.putFloat(offset, vector.x);
				target.putFloat(offset + 4, vector.y);
			}
		} else if (type.equals(VectorType.VEC3) && value.objectReturn instanceof Vector3f vector) {
			if (member.size() >= 12) {
				target.putFloat(offset, vector.x);
				target.putFloat(offset + 4, vector.y);
				target.putFloat(offset + 8, vector.z);
			}
		} else if (type.equals(VectorType.VEC4) && value.objectReturn instanceof Vector4f vector) {
			if (member.size() >= 16) {
				target.putFloat(offset, vector.x);
				target.putFloat(offset + 4, vector.y);
				target.putFloat(offset + 8, vector.z);
				target.putFloat(offset + 12, vector.w);
			}
		} else if (type.equals(VectorType.I_VEC2) && value.objectReturn instanceof Vector2i vector) {
			if (member.size() >= 8) {
				target.putInt(offset, vector.x);
				target.putInt(offset + 4, vector.y);
			}
		} else if (type.equals(VectorType.I_VEC3) && value.objectReturn instanceof Vector3i vector) {
			if (member.size() >= 12) {
				target.putInt(offset, vector.x);
				target.putInt(offset + 4, vector.y);
				target.putInt(offset + 8, vector.z);
			}
		} else if (type.equals(VectorType.I_VEC4) && value.objectReturn instanceof Vector4i vector) {
			if (member.size() >= 16) {
				target.putInt(offset, vector.x);
				target.putInt(offset + 4, vector.y);
				target.putInt(offset + 8, vector.z);
				target.putInt(offset + 12, vector.w);
			}
		} else if (type.equals(MatrixType.MAT4) && value.objectReturn instanceof Matrix4fc matrix && member.size() >= 64) {
			matrix.get(offset, target);
		}
	}
}
