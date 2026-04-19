import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Top-level build file where you can add configuration options common to all sub-projects/modules.
allprojects {
    apply(from = "${rootProject.projectDir}/repositories.gradle.kts")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory.get().asFile.path)
}

/**
 * `requireTargetAbi()` в `buildSrc` смотрит на **единственную** запрошенную задачу:
 * подстроки `arm64`, `arm`, `x64`, `x86` → один ABI; иначе → все четыре ABI.
 * См. [BUILD.md](BUILD.md).
 */
tasks.register("assemblePlayDebugArm64") {
    group = "build"
    description = "Локально: Play + debug + только arm64-v8a"
    dependsOn(":androidApp:assemblePlayDebug")
}

tasks.register("assemblePlayDebugArm") {
    group = "build"
    description = "Локально: Play + debug + только armeabi-v7a"
    dependsOn(":androidApp:assemblePlayDebug")
}

tasks.register("assemblePlayDebugX64") {
    group = "build"
    description = "Локально: Play + debug + только x86_64"
    dependsOn(":androidApp:assemblePlayDebug")
}

tasks.register("assemblePlayDebugX86") {
    group = "build"
    description = "Локально: Play + debug + только x86"
    dependsOn(":androidApp:assemblePlayDebug")
}

tasks.register("assemblePlayReleaseArm64") {
    group = "build"
    description = "Локально: Play + release + только arm64-v8a (нужен keystore в local.properties)"
    dependsOn(":androidApp:assemblePlayRelease")
}

tasks.register("assemblePlayReleaseArm") {
    group = "build"
    description = "Локально: Play + release + только armeabi-v7a"
    dependsOn(":androidApp:assemblePlayRelease")
}

tasks.register("assemblePlayReleaseX64") {
    group = "build"
    description = "Локально: Play + release + только x86_64"
    dependsOn(":androidApp:assemblePlayRelease")
}

tasks.register("assemblePlayReleaseX86") {
    group = "build"
    description = "Локально: Play + release + только x86"
    dependsOn(":androidApp:assemblePlayRelease")
}

tasks.register("assemblePlayDebugAllAbi") {
    group = "build"
    description = "Play + debug + все ABI (arm64-v8a, armeabi-v7a, x86_64, x86)"
    dependsOn(":androidApp:assemblePlayDebug")
}

tasks.register("assemblePlayReleaseAllAbi") {
    group = "build"
    description = "Play + release + все ABI (нужен keystore в local.properties)"
    dependsOn(":androidApp:assemblePlayRelease")
}

tasks.register("assembleFossDebugAllAbi") {
    group = "build"
    description = "Foss + debug + все ABI"
    dependsOn(":androidApp:assembleFossDebug")
}

tasks.register("assembleFossReleaseAllAbi") {
    group = "build"
    description = "Foss + release + все ABI (нужен keystore в local.properties)"
    dependsOn(":androidApp:assembleFossRelease")
}

tasks.register("assembleFossDebugArm64") {
    group = "build"
    description = "Локально: Foss + debug + только arm64-v8a"
    dependsOn(":androidApp:assembleFossDebug")
}

tasks.register("assembleFossDebugArm") {
    group = "build"
    description = "Локально: Foss + debug + только armeabi-v7a"
    dependsOn(":androidApp:assembleFossDebug")
}

tasks.register("assembleFossDebugX64") {
    group = "build"
    description = "Локально: Foss + debug + только x86_64"
    dependsOn(":androidApp:assembleFossDebug")
}

tasks.register("assembleFossDebugX86") {
    group = "build"
    description = "Локально: Foss + debug + только x86"
    dependsOn(":androidApp:assembleFossDebug")
}

tasks.register("assembleFossReleaseArm64") {
    group = "build"
    description = "Локально: Foss + release + только arm64-v8a"
    dependsOn(":androidApp:assembleFossRelease")
}

tasks.register("assembleFossReleaseArm") {
    group = "build"
    description = "Локально: Foss + release + только armeabi-v7a"
    dependsOn(":androidApp:assembleFossRelease")
}

tasks.register("assembleFossReleaseX64") {
    group = "build"
    description = "Локально: Foss + release + только x86_64"
    dependsOn(":androidApp:assembleFossRelease")
}

tasks.register("assembleFossReleaseX86") {
    group = "build"
    description = "Локально: Foss + release + только x86"
    dependsOn(":androidApp:assembleFossRelease")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add(
            "-Xbackend-threads=${Runtime.getRuntime().availableProcessors()}"
        )
    }
}
