plugins {
    java
    application
}

group = "pbd"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Version to check / bump to the latest published one - unlike JOML and
// JUnit above in this project, this number has not been confirmed against
// Maven Central in this session (see README: this part of the code was
// written without tool-based verification, at explicit request).
val lwjglVersion = "3.3.4"

val osArch: String = System.getProperty("os.arch")

val lwjglNatives = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
        if (osArch.startsWith("aarch64")) "natives-macos-arm64" else "natives-macos"
    org.gradle.internal.os.OperatingSystem.current().isWindows ->
        if (osArch.contains("64")) {
            if (osArch.startsWith("aarch64")) "natives-windows-arm64" else "natives-windows"
        } else "natives-windows-x86"
    else -> // Linux
        if (osArch.startsWith("arm") || osArch.startsWith("aarch64")) {
            "natives-linux-" + if (osArch.contains("64") || osArch.startsWith("armv8")) "arm64" else "arm32"
        } else "natives-linux"
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-stb")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")

    // Same math library on the CPU side (HierarchyResolver) and in the GPU uploads.
    implementation("org.joml:joml:1.10.5")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("pbd.app.Main")
}

tasks.named<JavaExec>("run") {
    // Cocoa/GLFW constraint: on macOS, the window must be created on the
    // JVM's first thread, otherwise glfwInit()/glfwCreateWindow() crash
    // with an explicit error message about it (see GlWindow.java).
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Multiple "modes" on top of the same build, alongside the existing
// `run` task (untouched above) rather than replacing it - `run` still
// launches the interactive viewer exactly as before.
//
// Usage: ./gradlew convertTileGeometry -Pinput=path/to/tileGeometry.txt -Poutput=path/to/outputDir
//
// -P project properties (not --args, which is specifically an
// `application` plugin feature tied to the `run` task alone) is the
// standard Gradle mechanism for passing configuration into an arbitrary
// custom task like this one.
tasks.register<JavaExec>("convertTileGeometry") {
    group = "pbd"
    description = "Converts a Project Zomboid tileGeometry.txt into one .pbd file per tile"
    mainClass.set("pbd.pz.TileGeometryConverterMain")
    classpath = sourceSets["main"].runtimeClasspath

    doFirst {
        val input = project.findProperty("input") as String?
        val output = project.findProperty("output") as String?
        if (input == null || output == null) {
            throw GradleException(
                "Usage: ./gradlew convertTileGeometry -Pinput=path/to/tileGeometry.txt -Poutput=path/to/outputDir"
            )
        }
        args = listOf(input, output)
    }
}
