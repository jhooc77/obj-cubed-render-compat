package net.irisshaders.iris.vulkan.shader;

import io.github.douira.glsl_transformer.ast.node.Profile;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.VersionStatement;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.type.FullySpecifiedType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier.BuiltinType.TypeKind;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.EnumASTTransformer;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.token_filter.ChannelFilter;
import io.github.douira.glsl_transformer.token_filter.TokenChannel;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Final AST pass that makes Iris' OpenGL-oriented transformed GLSL legal Vulkan GLSL. */
public final class VulkanShaderTransformer {
	public static final String LOOSE_UNIFORM_BLOCK = "IrisShaderpackUniforms";
	private static final String LOOSE_UNIFORM_INSTANCE = "iris_VulkanUniforms";

	private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)", Pattern.DOTALL);
	private static final Pattern MATRIX_STAGE_OUTPUT = Pattern.compile(
		"(?m)^([\\t ]*)((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise|highp|mediump|lowp)[\\t ]+)*)out[\\t ]+(mat([2-4])(?:x([2-4]))?)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;"
	);
	private static final Pattern MATRIX_STAGE_INPUT = Pattern.compile(
		"(?m)^([\\t ]*)((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise|highp|mediump|lowp)[\\t ]+)*)in[\\t ]+(mat([2-4])(?:x([2-4]))?)[\\t ]+([A-Za-z_][A-Za-z0-9_]*)[\\t ]*;"
	);
	private static final Pattern VULKAN_VERTEX_WRAPPER = Pattern.compile(
		"(?m)(^void[\\t ]+main[\\t ]*\\([\\t ]*(?:void[\\t ]*)?\\)[\\t ]*\\{[\\t \\r\\n]*iris_vulkan_main\\(\\);)"
	);
	private static final Pattern FRAGMENT_MAIN = Pattern.compile(
		"(?m)^void[\\t ]+main[\\t ]*\\([\\t ]*(?:void[\\t ]*)?\\)[\\t ]*\\{"
	);
	private static final EnumASTTransformer<TransformParameters, PatchShaderType> TRANSFORMER;

	static {
		TRANSFORMER = new EnumASTTransformer<>(PatchShaderType.class) {
			{
				setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
				setParsingCacheStrategy(ParsingCacheStrategy.TWO_TIER);
			}

			@Override
			public TranslationUnit parseTranslationUnit(Root rootInstance, String input) {
				Matcher matcher = VERSION_PATTERN.matcher(input);
				if (!matcher.find()) {
					throw new IllegalArgumentException("No #version directive in transformed shader source");
				}

				getLexer().version = Version.fromNumber(Integer.parseInt(matcher.group(1)));
				return super.parseTranslationUnit(rootInstance, input);
			}
		};

		TRANSFORMER.setPrintType(PrintType.SIMPLE);
		TRANSFORMER.setTokenFilter(new ChannelFilter<TransformParameters>(TokenChannel.PREPROCESSOR) {
			@Override
			public boolean isTokenAllowed(Token token) {
				if (!super.isTokenAllowed(token)) {
					throw new IllegalArgumentException("Unparsed preprocessor directive in Vulkan shader: " + token.getText());
				}
				return true;
			}
		});

		TRANSFORMER.setTransformation((trees, parameters) -> {
			for (Map.Entry<PatchShaderType, TranslationUnit> entry : trees.entrySet()) {
				TranslationUnit tree = entry.getValue();
				if (tree == null) {
					continue;
				}

				VersionStatement versionStatement = Objects.requireNonNull(tree.getVersionStatement(), "Missing GLSL version statement");
				versionStatement.version = Version.GLSL45;
				versionStatement.profile = Profile.CORE;

				Root root = tree.getRoot();
				root.indexBuildSession(() -> {
					if (entry.getKey() == PatchShaderType.VERTEX) {
						// Sodium's packed chunk format deliberately keeps its a_* and
						// iris_Normal semantic names. Renaming iris_Normal to the vanilla
						// Normal semantic makes Mojang's Vulkan linker reject the pipeline.
						if (parameters.mode != TransformMode.SODIUM_TERRAIN) {
							renameVanillaVertexInputs(root);
						}
						adaptMissingVertexInputs(tree, root, parameters.vertexInputs);
						adaptOpenGlClipDepth(tree, root);
					}
					adaptVanillaUniformBlocks(root, parameters.mode == TransformMode.VANILLA_TERRAIN);
					if (parameters.mode == TransformMode.VANILLA_TERRAIN) {
						adaptTerrainBuiltins(tree, root, entry.getKey());
					} else if (parameters.mode == TransformMode.SODIUM_TERRAIN) {
						adaptSodiumTerrainBuiltins(tree, root, entry.getKey());
					}
					removeAutoMappedStageInterfaceLocations(root, entry.getKey());

					collectAndRemoveLooseUniforms(root, parameters);
				});
			}

			if (!parameters.uniformMembers.isEmpty()) {
				parameters.finalizeUniformLayout();
				String blockSource = createLooseUniformBlock(parameters);
				for (TranslationUnit tree : trees.values()) {
					if (tree != null) {
						tree.getRoot().indexBuildSession(() ->
							tree.parseAndInjectNode(TRANSFORMER, ASTInjectionPoint.BEFORE_DECLARATIONS, blockSource));
					}
				}
				parameters.uniformBlocks.add(LOOSE_UNIFORM_BLOCK);
			}

			for (TranslationUnit tree : trees.values()) {
				if (tree != null) {
					tree.getRoot().indexBuildSession(() -> collectInterfaceBlocks(tree.getRoot(), parameters));
				}
			}
		});
	}

	/**
	 * Keeps the projection matrices exposed to shader packs in their historical
	 * OpenGL -1..1 clip-depth convention while producing Vulkan-legal 0..1 clip
	 * coordinates. The resulting depth-buffer value is the same 0..1 window depth
	 * that OpenGL packs sample, so expressions such as {@code depth * 2.0 - 1.0}
	 * and inverse-projection reconstruction continue to work unchanged.
	 */
	private static void adaptOpenGlClipDepth(TranslationUnit tree, Root root) {
		if (!root.identifierIndex.has("main")) {
			throw new IllegalArgumentException("Vulkan vertex shader has no main function");
		}
		root.rename("main", "iris_vulkan_main");
		tree.parseAndInjectNode(TRANSFORMER, ASTInjectionPoint.END, """
			void main() {
			    iris_vulkan_main();
			    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
			}
			""");
	}

	private VulkanShaderTransformer() {
	}

	public static synchronized VulkanShaderTransformResult transform(Map<PatchShaderType, String> sources) {
		return transform(sources, false);
	}

	public static synchronized VulkanShaderTransformResult transform(Map<PatchShaderType, String> sources, int[] outputLocationMap) {
		return transform(sources, TransformMode.GENERAL, outputLocationMap, Set.of());
	}

	/** Keeps Iris' extended per-vertex inputs when the submitted buffer really contains them. */
	public static synchronized VulkanShaderTransformResult transform(
		Map<PatchShaderType, String> sources,
		int[] outputLocationMap,
		VertexFormat vertexFormat
	) {
		Set<String> inputs = new LinkedHashSet<>();
		vertexFormat.getElements().forEach(element -> inputs.add(element.name()));
		return transform(sources, TransformMode.GENERAL, outputLocationMap, inputs);
	}

	public static synchronized VulkanShaderTransformResult transformTerrain(Map<PatchShaderType, String> sources) {
		return transform(sources, TransformMode.VANILLA_TERRAIN, null, Set.of());
	}

	public static synchronized VulkanShaderTransformResult transformTerrain(Map<PatchShaderType, String> sources, int[] outputLocationMap) {
		return transform(sources, TransformMode.VANILLA_TERRAIN, outputLocationMap, Set.of());
	}

	public static synchronized VulkanShaderTransformResult transformTerrain(
		Map<PatchShaderType, String> sources,
		int[] outputLocationMap,
		VertexFormat vertexFormat
	) {
		Set<String> inputs = new LinkedHashSet<>();
		vertexFormat.getElements().forEach(element -> inputs.add(element.name()));
		return transform(sources, TransformMode.VANILLA_TERRAIN, outputLocationMap, inputs);
	}

	/** Adapts Iris' Sodium terrain patch to Sodium 0.9's Vulkan buffer and push-constant ABI. */
	public static synchronized VulkanShaderTransformResult transformSodiumTerrain(
		Map<PatchShaderType, String> sources,
		int[] outputLocationMap,
		VertexFormat vertexFormat
	) {
		Set<String> inputs = new LinkedHashSet<>();
		vertexFormat.getElements().forEach(element -> inputs.add(element.name()));
		return transform(sources, TransformMode.SODIUM_TERRAIN, outputLocationMap, inputs);
	}

	private static VulkanShaderTransformResult transform(Map<PatchShaderType, String> sources, boolean terrain) {
		return transform(sources, terrain ? TransformMode.VANILLA_TERRAIN : TransformMode.GENERAL, null, Set.of());
	}

	private static VulkanShaderTransformResult transform(
		Map<PatchShaderType, String> sources,
		TransformMode mode,
		int[] outputLocationMap,
		Set<String> vertexInputs
	) {
		TransformParameters parameters = new TransformParameters(mode, vertexInputs);
		Map<PatchShaderType, String> transformed = new EnumMap<>(
			TRANSFORMER.transform(new EnumMap<>(sources), parameters)
		);
		flattenMatrixStageInterfaces(transformed);
		EnumMap<PatchShaderType, String> nonNullSources = new EnumMap<>(PatchShaderType.class);
		transformed.forEach((type, source) -> {
			if (source != null) {
				nonNullSources.put(type, type == PatchShaderType.FRAGMENT && outputLocationMap != null
					? remapFragmentOutputLocations(source, outputLocationMap)
					: source);
			}
		});
		return new VulkanShaderTransformResult(
			Map.copyOf(nonNullSources),
			parameters.uniformBlocks.stream().sorted().toList(),
			parameters.samplers.stream().sorted().toList(),
			parameters.texelBuffers.stream().sorted().toList(),
			Map.copyOf(parameters.texelBufferTypes),
			Map.copyOf(parameters.samplerTypes),
			parameters.uniformMembers.values().stream().map(PendingUniform::finished).toList(),
			parameters.uniformBufferSize
		);
	}

	/**
	 * Mojang 26.2's Vulkan linker assigns one location per reflected stage variable,
	 * even though a matrix consumes one location per column. A matrix followed by
	 * another varying therefore overlaps that varying and produces driver-dependent
	 * corruption. Split matrix varyings into vec columns before shaderc sees them so
	 * the linker's one-variable/one-location assumption becomes valid.
	 */
	private static void flattenMatrixStageInterfaces(Map<PatchShaderType, String> sources) {
		String vertex = sources.get(PatchShaderType.VERTEX);
		String fragment = sources.get(PatchShaderType.FRAGMENT);
		if (vertex == null || fragment == null) return;

		Map<String, MatrixStageVarying> vertexMatrices = collectMatrixStageVaryings(vertex, MATRIX_STAGE_OUTPUT);
		Map<String, MatrixStageVarying> fragmentMatrices = collectMatrixStageVaryings(fragment, MATRIX_STAGE_INPUT);
		if (vertexMatrices.isEmpty() && fragmentMatrices.isEmpty()) return;
		if (!vertexMatrices.keySet().equals(fragmentMatrices.keySet())) {
			throw new IllegalArgumentException(
				"Vulkan matrix stage interfaces do not match: vertex=" + vertexMatrices.keySet()
					+ " fragment=" + fragmentMatrices.keySet()
			);
		}
		for (String name : vertexMatrices.keySet()) {
			MatrixStageVarying vertexVarying = vertexMatrices.get(name);
			MatrixStageVarying fragmentVarying = fragmentMatrices.get(name);
			if (!vertexVarying.type.equals(fragmentVarying.type)) {
				throw new IllegalArgumentException(
					"Vulkan matrix stage interface '" + name + "' has mismatched types: "
						+ vertexVarying.type + " vs " + fragmentVarying.type
				);
			}
		}
		vertex = replaceMatrixStageDeclarations(vertex, MATRIX_STAGE_OUTPUT, "out");
		fragment = replaceMatrixStageDeclarations(fragment, MATRIX_STAGE_INPUT, "in");

		StringBuilder vertexCopies = new StringBuilder();
		StringBuilder fragmentCopies = new StringBuilder();
		for (MatrixStageVarying varying : vertexMatrices.values()) {
			for (int column = 0; column < varying.columns; column++) {
				vertexCopies.append("\n    ").append(columnName(varying.name, column))
					.append(" = ").append(varying.name).append('[').append(column).append("]; ");
			}
			fragmentCopies.append("    ").append(varying.name).append(" = ")
				.append(varying.type).append('(');
			for (int column = 0; column < varying.columns; column++) {
				if (column > 0) fragmentCopies.append(", ");
				fragmentCopies.append(columnName(varying.name, column));
			}
			fragmentCopies.append(");\n");
		}

		Matcher vertexWrapper = VULKAN_VERTEX_WRAPPER.matcher(vertex);
		if (!vertexWrapper.find()) {
			throw new IllegalStateException("Vulkan vertex wrapper is missing while flattening matrix varyings");
		}
		vertex = vertexWrapper.replaceFirst(Matcher.quoteReplacement(vertexWrapper.group(1) + vertexCopies));

		Matcher fragmentMain = FRAGMENT_MAIN.matcher(fragment);
		if (!fragmentMain.find()) {
			throw new IllegalStateException("Vulkan fragment main is missing while flattening matrix varyings");
		}
		fragment = fragmentMain.replaceFirst("void iris_vulkan_matrix_fragment_main() {");
		fragment += "\nvoid main() {\n" + fragmentCopies + "    iris_vulkan_matrix_fragment_main();\n}\n";

		sources.put(PatchShaderType.VERTEX, vertex);
		sources.put(PatchShaderType.FRAGMENT, fragment);
	}

	private static Map<String, MatrixStageVarying> collectMatrixStageVaryings(String source, Pattern pattern) {
		Map<String, MatrixStageVarying> result = new LinkedHashMap<>();
		Matcher matcher = pattern.matcher(source);
		while (matcher.find()) {
			String type = matcher.group(3);
			int columns = Integer.parseInt(matcher.group(4));
			int rows = matcher.group(5) == null ? columns : Integer.parseInt(matcher.group(5));
			String name = matcher.group(6);
			result.put(name, new MatrixStageVarying(type, name, columns, rows));
		}
		return result;
	}

	private static String replaceMatrixStageDeclarations(
		String source,
		Pattern pattern,
		String direction
	) {
		Matcher matcher = pattern.matcher(source);
		StringBuffer replaced = new StringBuffer(source.length());
		while (matcher.find()) {
			String indent = matcher.group(1);
			String qualifiers = matcher.group(2);
			String type = matcher.group(3);
			int columns = Integer.parseInt(matcher.group(4));
			int rows = matcher.group(5) == null ? columns : Integer.parseInt(matcher.group(5));
			String name = matcher.group(6);
			StringBuilder declaration = new StringBuilder(indent).append(type).append(' ').append(name).append(';');
			for (int column = 0; column < columns; column++) {
				declaration.append('\n').append(indent).append(qualifiers).append(direction).append(' ')
					.append("vec").append(rows).append(' ').append(columnName(name, column)).append(';');
			}
			matcher.appendReplacement(replaced, Matcher.quoteReplacement(declaration.toString()));
		}
		matcher.appendTail(replaced);
		return replaced.toString();
	}

	private static String columnName(String varying, int column) {
		return "iris_vulkan_mvary_" + varying + "_col" + column;
	}

	private record MatrixStageVarying(String type, String name, int columns, int rows) {
	}

	private static String remapFragmentOutputLocations(String source, int[] outputLocationMap) {
		Pattern output = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\b");
		Matcher matcher = output.matcher(source);
		StringBuffer remapped = new StringBuffer(source.length());
		while (matcher.find()) {
			int oldLocation = Integer.parseInt(matcher.group(1));
			if (oldLocation >= outputLocationMap.length) {
				throw new IllegalArgumentException("Fragment output location " + oldLocation + " has no DRAWBUFFERS mapping");
			}
			matcher.appendReplacement(remapped, "layout(location = " + outputLocationMap[oldLocation] + ") out");
		}
		matcher.appendTail(remapped);
		return remapped.toString();
	}

	private static void renameVanillaVertexInputs(Root root) {
		root.rename("iris_Position", "Position");
		root.rename("iris_Color", "Color");
		root.rename("iris_UV0", "UV0");
		root.rename("iris_UV1", "UV1");
		root.rename("iris_UV2", "UV2");
		root.rename("iris_Normal", "Normal");
		root.rename("iris_LineWidth", "LineWidth");
	}

	/**
	 * Reuse Iris' extended immediate attributes when the submitted Vulkan buffer
	 * contains them. Pipelines which intentionally retain a vanilla format (for
	 * example the hand path) receive conservative fallbacks for missing bindings.
	 */
	private static void adaptMissingVertexInputs(TranslationUnit tree, Root root, Set<String> vertexInputs) {
		// VanillaTransformer declares this for every vertex program even though it
		// is only consumed by the dedicated line path. Terrain/entity buffers do
		// not carry it, and shaderc reflection retains the otherwise-unused input.
		if (!vertexInputs.contains("LineWidth")) {
			replaceAndRemoveInput(root, "LineWidth", "1.0");
		}
		if (!vertexInputs.contains("iris_Entity")) {
			convertInputToUniform(root, "iris_Entity");
		}
		if (!vertexInputs.contains("mc_Entity")) {
			// mc_Entity is a terrain material attribute. Some packs retain it in
			// shared entity-program includes, where OpenGL supplies the default
			// attribute value. Preserve that behavior as a zeroed draw uniform.
			convertInputToUniform(root, "mc_Entity");
		}
		if (!vertexInputs.contains("mc_midTexCoord")) {
			replaceAndRemoveInput(root, "mc_midTexCoord",
				root.identifierIndex.has("UV0") ? "vec4(UV0, 0.0, 1.0)" : "vec4(0.5, 0.5, 0.0, 1.0)");
		}
		if (!vertexInputs.contains("at_tangent")) {
			String tangent = root.identifierIndex.has("Normal")
				? "vec4(normalize(cross(abs(normalize(Normal).y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), normalize(Normal))), 1.0)"
				: "vec4(1.0, 0.0, 0.0, 1.0)";
			replaceAndRemoveInput(root, "at_tangent", tangent);
		}
		if (!vertexInputs.contains("at_midBlock")) {
			replaceAndRemoveInput(root, "at_midBlock", "vec4(0.0)");
		}
	}

	private static void convertInputToUniform(Root root, String name) {
		for (TypeAndInitDeclaration declaration : new ArrayList<>(root.nodeIndex.get(TypeAndInitDeclaration.class))) {
			boolean matches = declaration.getMembers().stream()
				.anyMatch(member -> member.getName().getName().equals(name));
			if (!matches) continue;
			TypeQualifier qualifier = declaration.getType().getTypeQualifier();
			if (qualifier == null) continue;
			for (TypeQualifierPart part : qualifier.getParts()) {
				if (part instanceof StorageQualifier storage && (storage.storageType == StorageType.IN
					|| storage.storageType == StorageType.ATTRIBUTE)) {
					storage.storageType = StorageType.UNIFORM;
				}
			}
		}
	}

	private static void replaceAndRemoveInput(Root root, String name, String replacement) {
		if (!root.identifierIndex.has(name)) return;
		root.replaceReferenceExpressions(TRANSFORMER, name, replacement);
		for (TypeAndInitDeclaration declaration : new ArrayList<>(root.nodeIndex.get(TypeAndInitDeclaration.class))) {
			boolean matches = declaration.getMembers().stream()
				.anyMatch(member -> member.getName().getName().equals(name));
			if (!matches) continue;
			DeclarationExternalDeclaration external = declaration.getAncestor(DeclarationExternalDeclaration.class);
			if (external != null) external.detachAndDelete();
		}
	}

	private static void adaptVanillaUniformBlocks(Root root, boolean terrain) {
		for (InterfaceBlockDeclaration block : new ArrayList<>(root.nodeIndex.get(InterfaceBlockDeclaration.class))) {
			String blockName = block.getBlockName().getName();
			switch (blockName) {
				case "iris_Fog" -> renameVanillaUniformBlock(root, block, "Fog");
				case "iris_Globals" -> renameVanillaUniformBlock(root, block, "Globals");
				case "iris_Projection" -> renameVanillaUniformBlock(root, block, "Projection");
				case "iris_CloudInfo" -> renameVanillaUniformBlock(root, block, "CloudInfo");
				case "iris_DynamicTransforms" -> {
					if (!terrain) {
						renameVanillaUniformBlock(root, block, "DynamicTransforms");
					}
				}
				default -> {
				}
			}
		}
	}

	private static void renameVanillaUniformBlock(Root root, InterfaceBlockDeclaration block, String targetName) {
		String replacement = "iris_PackFunction_" + targetName;
		boolean collidesWithFunction = root.nodeIndex.getStream(FunctionPrototype.class)
			.anyMatch(prototype -> prototype.getName().getName().equals(targetName));
		if (collidesWithFunction) {
			for (FunctionPrototype prototype : new ArrayList<>(root.nodeIndex.get(FunctionPrototype.class))) {
				if (prototype.getName().getName().equals(targetName)) {
					prototype.getName().setName(replacement);
				}
			}
			for (FunctionCallExpression call : new ArrayList<>(root.nodeIndex.get(FunctionCallExpression.class))) {
				if (call.getFunctionName() != null && call.getFunctionName().getName().equals(targetName)) {
					call.getFunctionName().setName(replacement);
				}
			}
		}
		block.getBlockName().setName(targetName);
	}

	/**
	 * Mojang's Vulkan compiler auto-maps stage interface locations and then links
	 * fragment inputs back to vertex outputs by name. Explicit locations produced
	 * by Iris' OpenGL compatibility pass only advance one slot per declaration,
	 * which overlaps the following varying when the declaration is a matrix or an
	 * array. Leave fragment output locations intact for DRAWBUFFERS remapping, but
	 * let shaderc assign every vertex input/output and fragment input location.
	 */
	private static void removeAutoMappedStageInterfaceLocations(Root root, PatchShaderType stage) {
		for (TypeAndInitDeclaration declaration : new ArrayList<>(root.nodeIndex.get(TypeAndInitDeclaration.class))) {
			FullySpecifiedType type = declaration.getType();
			TypeQualifier qualifier = type.getTypeQualifier();
			if (shouldAutoMapStageInterface(qualifier, stage) && removeLocationQualifier(qualifier)) {
				if (qualifier.getParts().isEmpty()) {
					type.setTypeQualifier(null);
				}
			}
		}

		for (InterfaceBlockDeclaration block : new ArrayList<>(root.nodeIndex.get(InterfaceBlockDeclaration.class))) {
			TypeQualifier qualifier = block.getTypeQualifier();
			if (shouldAutoMapStageInterface(qualifier, stage) && removeLocationQualifier(qualifier)) {
				if (qualifier.getParts().isEmpty()) {
					block.setTypeQualifier(null);
				}
			}
		}
	}

	private static boolean shouldAutoMapStageInterface(TypeQualifier qualifier, PatchShaderType stage) {
		if (qualifier == null) {
			return false;
		}

		if (stage == PatchShaderType.VERTEX) {
			return hasStorageQualifier(qualifier, StorageType.IN)
				|| hasStorageQualifier(qualifier, StorageType.OUT);
		}
		if (stage == PatchShaderType.FRAGMENT) {
			return hasStorageQualifier(qualifier, StorageType.IN);
		}
		return false;
	}

	private static boolean removeLocationQualifier(TypeQualifier qualifier) {
		boolean removed = false;
		for (LayoutQualifier layout : qualifier.getParts().stream()
			.filter(LayoutQualifier.class::isInstance)
			.map(LayoutQualifier.class::cast)
			.toList()) {
			boolean removedFromLayout = layout.getParts().removeIf(part ->
				part instanceof NamedLayoutQualifierPart named
					&& named.getName().getName().equals("location"));
			removed |= removedFromLayout;
			if (layout.getParts().isEmpty()) {
				qualifier.getParts().remove(layout);
			}
		}
		return removed;
	}

	private static void adaptTerrainBuiltins(TranslationUnit tree, Root root, PatchShaderType stage) {
		for (InterfaceBlockDeclaration block : new ArrayList<>(root.nodeIndex.get(InterfaceBlockDeclaration.class))) {
			String blockName = block.getBlockName().getName();
			switch (blockName) {
				case "iris_DynamicTransforms" -> {
					DeclarationExternalDeclaration external = block.getAncestor(DeclarationExternalDeclaration.class);
					if (external != null) {
						external.detachAndDelete();
					}
				}
				default -> {
				}
			}
		}

		root.rename("iris_ProjMat", "ProjMat");
		replaceMemberAccess(root, "iris_transforms", "ModelViewMat", "ModelViewMat");
		replaceMemberAccess(root, "iris_transforms", "ModelOffset", "(vec3(ChunkPosition - iris_globalInfo.CameraBlockPos) + iris_globalInfo.CameraOffset)");
		replaceMemberAccess(root, "iris_transforms", "ColorModulator", "vec4(1.0)");
		replaceMemberAccess(root, "iris_transforms", "TextureMat", "mat4(1.0)");

		if (stage == PatchShaderType.VERTEX) {
			tree.parseAndInjectNode(TRANSFORMER, ASTInjectionPoint.BEFORE_DECLARATIONS, """
				layout(std140) uniform ChunkSection {
				    mat4 ModelViewMat;
				    float ChunkVisibility;
				    ivec2 TextureSize;
				    ivec3 ChunkPosition;
				};
				""");
		}
	}

	/**
	 * Sodium's Vulkan draw context writes a fixed 20-byte push-constant payload
	 * for every render region. Iris' Sodium GLSL patch declares the same values as
	 * OpenGL loose uniforms, so restore Sodium's native Vulkan ABI before loose
	 * uniforms are packed into the Iris std140 block.
	 */
	private static void adaptSodiumTerrainBuiltins(TranslationUnit tree, Root root, PatchShaderType stage) {
		if (stage != PatchShaderType.VERTEX) return;

		removeUniformDeclarations(root, Set.of("u_RegionOffset", "u_CurrentTime", "u_RegionID"));
		tree.parseAndInjectNode(TRANSFORMER, ASTInjectionPoint.BEFORE_DECLARATIONS, """
			layout(push_constant) uniform IrisSodiumPushConstants {
			    vec3 u_RegionOffset;
			    int u_CurrentTime;
			    uint u_RegionID;
			};
			""");
	}

	private static void removeUniformDeclarations(Root root, Set<String> names) {
		for (TypeAndInitDeclaration declaration : new ArrayList<>(root.nodeIndex.get(TypeAndInitDeclaration.class))) {
			if (!hasStorageQualifier(declaration.getType().getTypeQualifier(), StorageType.UNIFORM)) continue;
			boolean matches = declaration.getMembers().stream()
				.anyMatch(member -> names.contains(member.getName().getName()));
			if (!matches) continue;
			DeclarationExternalDeclaration external = declaration.getAncestor(DeclarationExternalDeclaration.class);
			if (external != null) external.detachAndDelete();
		}
	}

	private static void replaceMemberAccess(Root root, String instance, String member, String replacement) {
		List<MemberAccessExpression> matches = root.nodeIndex.getStream(MemberAccessExpression.class)
			.filter(access -> access.getMember().getName().equals(member))
			.filter(access -> access.getOperand() instanceof ReferenceExpression reference
				&& reference.getIdentifier().getName().equals(instance))
			.toList();
		if (!matches.isEmpty()) {
			root.replaceExpressions(TRANSFORMER, matches.stream(), replacement);
		}
	}

	private static void collectAndRemoveLooseUniforms(Root root, TransformParameters parameters) {
		List<TypeAndInitDeclaration> declarations = new ArrayList<>(root.nodeIndex.get(TypeAndInitDeclaration.class));

		for (TypeAndInitDeclaration declaration : declarations) {
			if (!hasStorageQualifier(declaration.getType().getTypeQualifier(), StorageType.UNIFORM)) {
				continue;
			}

			DeclarationExternalDeclaration external = declaration.getAncestor(DeclarationExternalDeclaration.class);
			if (external == null) {
				continue;
			}

			if (declaration.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier fixed) {
				if (fixed.type.kind == TypeKind.SAMPLER) {
					collectSamplers(declaration, parameters);
					continue;
				}

				if (fixed.type.kind == TypeKind.IMAGE || fixed.type.kind == TypeKind.ATOMIC_UINT || fixed.type.kind == TypeKind.ACCELERATION_STRUCTURE) {
					throw new UnsupportedOperationException("Vulkan shader resource type is not implemented: " + fixed.type);
				}
			}

			FullySpecifiedType memberType = declaration.getType().clone();
			removeStorageQualifier(memberType, StorageType.UNIFORM);
			String typeSource = ASTPrinter.printSimple(memberType).trim();

			for (DeclarationMember member : declaration.getMembers()) {
				String name = member.getName().getName();
				String memberSource = member.getName().getName();
				if (member.getArraySpecifier() != null) {
					memberSource += ASTPrinter.printSimple(member.getArraySpecifier()).trim();
				}
				String declarationSource = typeSource + " " + memberSource + ";";
				Std140Layout layout = getStd140Layout(declaration.getType().getTypeSpecifier(), member.getArraySpecifier());
				parameters.addUniform(name, declarationSource, layout);
				replaceUnshadowedUniformReferences(root, declaration, name,
					LOOSE_UNIFORM_INSTANCE + "." + name);
			}

			external.detachAndDelete();
		}
	}

	/**
	 * Rewrites references to a global loose uniform without touching a local variable
	 * or function parameter which shadows it. Root.replaceReferenceExpressions is
	 * intentionally name based; using it directly changed BSL's local shadowFade into
	 * an assignment to iris_VulkanUniforms.shadowFade and made the shader illegal.
	 */
	private static void replaceUnshadowedUniformReferences(
		Root root,
		TypeAndInitDeclaration uniformDeclaration,
		String name,
		String replacement
	) {
		List<ReferenceExpression> references = root.identifierIndex.getReferenceExpressions(name)
			.filter(reference -> !isShadowed(reference, root, uniformDeclaration, name))
			.toList();
		root.replaceReferenceExpressions(TRANSFORMER,
			references.stream().map(ReferenceExpression::getIdentifier), replacement);
	}

	private static boolean isShadowed(
		ReferenceExpression reference,
		Root root,
		TypeAndInitDeclaration uniformDeclaration,
		String name
	) {
		FunctionDefinition function = reference.getAncestor(FunctionDefinition.class);
		if (function == null) return false;

		boolean parameter = function.getFunctionPrototype().getParameters().stream()
			.anyMatch(candidate -> candidate.getName() != null && candidate.getName().getName().equals(name));
		if (parameter) return true;

		return root.nodeIndex.getStream(TypeAndInitDeclaration.class)
			.filter(candidate -> candidate != uniformDeclaration)
			.filter(candidate -> candidate.getAncestor(FunctionDefinition.class) == function)
			.filter(candidate -> candidate.getMembers().stream()
				.anyMatch(member -> member.getName().getName().equals(name)))
			.anyMatch(candidate -> {
				CompoundStatement scope = candidate.getAncestor(CompoundStatement.class);
				if (scope == null || !reference.hasAncestor(scope)) return false;

				Statement declarationStatement = statementDirectlyInside(candidate, scope);
				Statement referenceStatement = statementDirectlyInside(reference, scope);
				if (declarationStatement == null || referenceStatement == null) return false;

				int declarationIndex = scope.getStatements().indexOf(declarationStatement);
				int referenceIndex = scope.getStatements().indexOf(referenceStatement);
				// A local name starts shadowing after its declaration statement. References
				// in that declaration's initializer still resolve to the outer uniform, as
				// used by BSL's `float moonPhase = ... moonPhase ...` pattern.
				return declarationIndex >= 0 && declarationIndex < referenceIndex;
			});
	}

	private static Statement statementDirectlyInside(ASTNode node, CompoundStatement scope) {
		ASTNode current = node;
		while (current != null && current.getParent() != scope) {
			current = current.getParent();
		}
		return current instanceof Statement statement ? statement : null;
	}

	private static String createLooseUniformBlock(TransformParameters parameters) {
		StringBuilder block = new StringBuilder("layout(std140) uniform ")
			.append(LOOSE_UNIFORM_BLOCK)
			.append(" {\n");
		for (PendingUniform member : parameters.uniformMembers.values()) {
			// Keep shaderc's SPIR-V member offsets byte-for-byte identical to the
			// CPU uploader, including vec3/scalar packing boundaries.
			block.append("    layout(offset = ").append(member.offset).append(") ")
				.append(member.declaration).append('\n');
		}
		return block.append("} ").append(LOOSE_UNIFORM_INSTANCE).append(";").toString();
	}

	private static void collectSamplers(TypeAndInitDeclaration declaration, TransformParameters parameters) {
		String samplerType = ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).trim();
		for (DeclarationMember member : declaration.getMembers()) {
			if (member.getArraySpecifier() != null) {
				throw new UnsupportedOperationException("Sampler arrays are not implemented by the Iris Vulkan runtime: " + member.getName().getName());
			}
			String name = member.getName().getName();
			if (samplerType.endsWith("samplerBuffer")) {
				parameters.texelBuffers.add(name);
				String previousType = parameters.texelBufferTypes.putIfAbsent(name, samplerType);
				if (previousType != null && !previousType.equals(samplerType)) {
					throw new UnsupportedOperationException(
						"Texel buffer '" + name + "' has conflicting sampler types: " + previousType + " and " + samplerType
					);
				}
			} else {
				parameters.samplers.add(name);
				parameters.samplerTypes.put(name, samplerType);
			}
		}
	}

	private static void collectInterfaceBlocks(Root root, TransformParameters parameters) {
		for (InterfaceBlockDeclaration block : new ArrayList<>(root.nodeIndex.get(InterfaceBlockDeclaration.class))) {
			if (hasStorageQualifier(block.getTypeQualifier(), StorageType.BUFFER)) {
				throw new UnsupportedOperationException("Shader storage buffers are not implemented by the Iris Vulkan runtime: " + block.getBlockName().getName());
			}
			if (hasStorageQualifier(block.getTypeQualifier(), StorageType.UNIFORM)
				&& !hasLayoutQualifier(block.getTypeQualifier(), "push_constant")) {
				parameters.uniformBlocks.add(block.getBlockName().getName());
			}
		}
	}

	private static boolean hasLayoutQualifier(TypeQualifier qualifier, String expected) {
		if (qualifier == null) return false;
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (!(part instanceof LayoutQualifier layout)) continue;
			for (var layoutPart : layout.getParts()) {
				if (layoutPart instanceof NamedLayoutQualifierPart named
					&& named.getName().getName().equals(expected)) return true;
			}
		}
		return false;
	}

	private static boolean hasStorageQualifier(TypeQualifier qualifier, StorageType expected) {
		if (qualifier == null) {
			return false;
		}
		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof StorageQualifier storage && storage.storageType == expected) {
				return true;
			}
		}
		return false;
	}

	private static void removeStorageQualifier(FullySpecifiedType type, StorageType storageType) {
		TypeQualifier qualifier = type.getTypeQualifier();
		if (qualifier == null) {
			return;
		}

		qualifier.getParts().removeIf(part -> part instanceof StorageQualifier storage && storage.storageType == storageType);
		if (qualifier.getParts().isEmpty()) {
			type.setTypeQualifier(null);
		}
	}

	private static Std140Layout getStd140Layout(TypeSpecifier typeSpecifier, ArraySpecifier memberArray) {
		if (!(typeSpecifier instanceof BuiltinNumericTypeSpecifier numeric)) {
			throw new UnsupportedOperationException("Non-numeric loose uniforms are not implemented by the Iris Vulkan std140 packer: " + ASTPrinter.printSimple(typeSpecifier));
		}

		Type type = numeric.type;
		if (type.getBitDepth() > 32) {
			throw new UnsupportedOperationException("64-bit loose uniforms are not implemented by the Iris Vulkan std140 packer: " + type);
		}

		int[] dimensions = type.getDimensions();
		Std140Layout layout;
		if (type.isMatrix()) {
			layout = new Std140Layout(16, dimensions[0] * 16);
		} else if (dimensions[0] <= 1) {
			layout = new Std140Layout(4, 4);
		} else if (dimensions[0] == 2) {
			layout = new Std140Layout(8, 8);
		} else {
			layout = new Std140Layout(16, 16);
		}

		layout = applyArrays(layout, typeSpecifier.getArraySpecifier());
		return applyArrays(layout, memberArray);
	}

	private static Std140Layout applyArrays(Std140Layout element, ArraySpecifier arraySpecifier) {
		if (arraySpecifier == null) {
			return element;
		}
		Std140Layout result = element;
		List<Expression> dimensions = arraySpecifier.getDimensions();
		for (int index = dimensions.size() - 1; index >= 0; index--) {
			Expression dimension = dimensions.get(index);
			if (!(dimension instanceof LiteralExpression literal) || !literal.isInteger() || literal.getInteger() <= 0 || literal.getInteger() > Integer.MAX_VALUE) {
				throw new UnsupportedOperationException("Loose uniform arrays require a positive constant size: " + ASTPrinter.printSimple(arraySpecifier));
			}
			int stride = align(result.size, 16);
			result = new Std140Layout(16, Math.multiplyExact(stride, (int)literal.getInteger()));
		}
		return result;
	}

	private static int align(int value, int alignment) {
		return Math.addExact(value, alignment - 1) / alignment * alignment;
	}

	private static final class TransformParameters implements JobParameters {
		private final TransformMode mode;
		private final Set<String> vertexInputs;
		private final Map<String, PendingUniform> uniformMembers = new LinkedHashMap<>();
		private final Set<String> uniformBlocks = new LinkedHashSet<>();
		private final Set<String> samplers = new LinkedHashSet<>();
		private final Set<String> texelBuffers = new LinkedHashSet<>();
		private final Map<String, String> texelBufferTypes = new LinkedHashMap<>();
		private final Map<String, String> samplerTypes = new LinkedHashMap<>();
		private int uniformBufferSize;

		private TransformParameters(TransformMode mode, Set<String> vertexInputs) {
			this.mode = mode;
			this.vertexInputs = Set.copyOf(vertexInputs);
		}

		private void addUniform(String name, String declaration, Std140Layout layout) {
			PendingUniform previous = uniformMembers.putIfAbsent(
				name,
				new PendingUniform(name, declaration, layout)
			);
			if (previous != null && (!previous.declaration.equals(declaration) || !previous.layout.equals(layout))) {
				throw new IllegalArgumentException("Uniform '" + name + "' has incompatible declarations across shader stages");
			}
		}

		private void finalizeUniformLayout() {
			List<Map.Entry<String, PendingUniform>> sorted = uniformMembers.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.toList();
			uniformMembers.clear();
			sorted.forEach(entry -> uniformMembers.put(entry.getKey(), entry.getValue()));
			int cursor = 0;
			for (PendingUniform uniform : uniformMembers.values()) {
				cursor = align(cursor, uniform.layout.alignment);
				uniform.offset = cursor;
				cursor = Math.addExact(cursor, uniform.layout.size);
			}
			uniformBufferSize = align(cursor, 16);
		}
	}

	private enum TransformMode {
		GENERAL,
		VANILLA_TERRAIN,
		SODIUM_TERRAIN
	}

	private record Std140Layout(int alignment, int size) {
	}

	private static final class PendingUniform {
		private final String name;
		private final String declaration;
		private final Std140Layout layout;
		private int offset;

		private PendingUniform(String name, String declaration, Std140Layout layout) {
			this.name = name;
			this.declaration = declaration;
			this.layout = layout;
		}

		private VulkanShaderTransformResult.UniformMember finished() {
			return new VulkanShaderTransformResult.UniformMember(name, declaration, offset, layout.size);
		}
	}
}
