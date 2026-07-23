plugins {
    java
}

group = "io.github.jhooc77.objcubedcompat"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveBaseName.set("obj-cubed-optifine-patcher-mc26.1.2-k1")
    manifest.attributes["Main-Class"] = "io.github.jhooc77.objcubedoptifine.PatcherMain"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

tasks.test { enabled = false }

val selfTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.jhooc77.objcubedoptifine.ObjCubedOptiFineBridgeSelfTest")
}

tasks.check { dependsOn(selfTest) }
