plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.15.4"
}

group = "io.github.jhooc77.objcubedcompat"
version = property("mod_version") as String

val minecraftVersion = property("minecraft_version").toString()
val isMinecraft26_2 = minecraftVersion == "26.2"
val compatibilityBuild = providers.gradleProperty("compatibility_build")
    .map(String::toBoolean)
    .orElse(false)
    .get()

base {
    archivesName.set("obj-cubed-iris-compat-${property("artifact_suffix")}")
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")

    compileOnly("maven.modrinth:YL57xq9U:${property("iris_version_id")}")
    compileOnly("maven.modrinth:AANobbMI:${property("sodium_version_id")}")
    compileOnly("io.github.douira:glsl-transformer:3.0.0-pre3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

sourceSets {
    main {
        if (compatibilityBuild && isMinecraft26_2) {
            java.exclude("io/github/jhooc77/objcubedcompat/mixin/ShadowRendererMixin.java")
            java.exclude("io/github/jhooc77/objcubedcompat/mixin/sodium/ShaderLoaderMixin.java")
        }
        if (!compatibilityBuild || isMinecraft26_2) {
            java.srcDir("src/mc26_2/java")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_dependency", project.property("minecraft_dependency"))
    inputs.property("iris_dependency", project.property("iris_dependency"))
    inputs.property("sodium_dependency", project.property("sodium_dependency"))
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_dependency" to project.property("minecraft_dependency").toString(),
            "iris_dependency" to project.property("iris_dependency").toString(),
            "sodium_dependency" to project.property("sodium_dependency").toString()
        )
    }
}

loom {
    mixin {
        defaultRefmapName.set("obj-cubed-iris-compat.refmap.json")
        useLegacyMixinAp = false
    }
}

val shaderInjectionSelfTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set("io.github.jhooc77.objcubedcompat.shader.ObjCubedShaderInjectorSelfTest")
    systemProperty("objcubed.test.minecraftVersion", minecraftVersion)
    systemProperty("objcubed.test.compatibilityBuild", compatibilityBuild)
}

tasks.test {
    enabled = false
}

tasks.check {
    dependsOn(shaderInjectionSelfTest)
}

evaluationDependsOn(":optifine-patcher")

val optifineSourceSets = project(":optifine-patcher").extensions.getByType<SourceSetContainer>()

tasks.register<JavaExec>("officialOptiFineProbe") {
    group = "verification"
    description = "Runs the shader bridge against an official local OptiFine JAR"
    dependsOn(":optifine-patcher:testClasses")
    classpath = optifineSourceSets.getByName("test").output +
        optifineSourceSets.getByName("main").output +
        configurations.getByName("minecraftNamedRuntime") +
        configurations.getByName("minecraftNatives")
    mainClass.set("io.github.jhooc77.objcubedoptifine.OfficialOptiFineRemapProbe")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val probeWorkingDirectory = layout.buildDirectory.dir("tmp/officialOptiFineProbe")
    workingDir(probeWorkingDirectory)
    doFirst {
        probeWorkingDirectory.get().asFile.mkdirs()
        val input = providers.gradleProperty("optifineJar").orNull
            ?: throw GradleException("Pass -PoptifineJar=<official OptiFine JAR>")
        args(input)
    }
}
