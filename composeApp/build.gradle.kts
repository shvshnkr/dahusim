import org.gradle.api.GradleException
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("kotlin-parcelize")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.aboutlibraries)
}

val metadata = requireMetadata()
enum class DesktopPlatform(
    val id: String,
    private val aliases: Set<String>,
    val composeDependencyId: String,
    val nativeNames: Set<String>,
    val jnaName: String,
) {
    Linux(
        id = "linux",
        aliases = setOf("linux"),
        composeDependencyId = "linux",
        nativeNames = setOf("linux"),
        jnaName = "linux",
    ),
    Darwin(
        id = "darwin",
        aliases = setOf("darwin", "macos", "mac", "osx"),
        composeDependencyId = "macos",
        nativeNames = setOf("osx", "darwin"),
        jnaName = "darwin",
    ),
    Windows(
        id = "windows",
        aliases = setOf("windows", "win"),
        composeDependencyId = "windows",
        nativeNames = setOf("windows"),
        jnaName = "win32",
    ),
    ;

    private fun matches(rawValue: String): Boolean = rawValue.trim().lowercase() in aliases

    companion object {
        fun parse(rawValue: String): DesktopPlatform =
            entries.firstOrNull { it.matches(rawValue) }
                ?: error("Unsupported desktop platform '$rawValue'. Use linux, darwin, or windows.")

        fun parseHost(rawValue: String): DesktopPlatform {
            val value = rawValue.trim().lowercase()
            return when {
                value.contains("linux") -> Linux
                value.contains("darwin") || value.contains("mac") || value.contains("osx") -> Darwin
                value.startsWith("win") -> Windows
                else -> error("Unsupported host desktop platform '$rawValue'. Use linux, darwin, or windows.")
            }
        }
    }
}

enum class DesktopArch(
    val id: String,
    private val aliases: Set<String>,
    val composeDependencyId: String,
    val packageJarArchToken: String,
    val nativeNames: Set<String>,
    val jnaName: String,
) {
    Amd64(
        id = "amd64",
        aliases = setOf("amd64", "x86_64"),
        composeDependencyId = "x64",
        packageJarArchToken = "x64",
        nativeNames = setOf("x64", "amd64"),
        jnaName = "x86-64",
    ),
    Arm64(
        id = "arm64",
        aliases = setOf("arm64", "aarch64"),
        composeDependencyId = "arm64",
        packageJarArchToken = "arm64",
        nativeNames = setOf("arm64", "aarch64"),
        jnaName = "aarch64",
    ),
    ;

    private fun matches(rawValue: String): Boolean = rawValue.trim().lowercase() in aliases

    companion object {
        fun parse(rawValue: String): DesktopArch =
            entries.firstOrNull { it.matches(rawValue) }
                ?: error("Unsupported desktop arch '$rawValue'. Use amd64 or arm64.")
    }
}

data class DesktopTarget(
    val platform: DesktopPlatform,
    val arch: DesktopArch,
) {
    val id: String = "${platform.id}/${arch.id}"
    val libcoreDesktopJarName: String = "libcore-desktop-${platform.id}-${arch.id}.jar"
    val composeDependencyNotation: String =
        "org.jetbrains.compose.desktop:desktop-jvm-${platform.composeDependencyId}-${arch.composeDependencyId}"
    val nativeKeepPrefixes: Set<String> =
        platform.nativeNames
            .flatMap { platformName ->
                arch.nativeNames.flatMap { archName ->
                    listOf("natives/${platformName}_${archName}/", "natives/${platformName}-${archName}/")
                }
            }.toSet()
    val jnaNativeKeepPrefixes: Set<String> =
        setOf(
            "com/sun/jna/${platform.jnaName}-${arch.jnaName}/",
        )
    val skikoNativeKeepEntries: Set<String> =
        buildSet {
            val baseName = "skiko-${platform.composeDependencyId}-${arch.composeDependencyId}"
            add("$baseName.dll")
            add("$baseName.dll.sha256")
            add("lib$baseName.so")
            add("lib$baseName.so.sha256")
            add("lib$baseName.dylib")
            add("lib$baseName.dylib.sha256")
        }

    override fun toString(): String = id

    companion object {
        val supported: Set<DesktopTarget> =
            DesktopPlatform.entries.flatMap { platform ->
                DesktopArch.entries.map { arch -> DesktopTarget(platform, arch) }
            }.toSet()

        fun parse(rawValue: String): DesktopTarget {
            val tokens = rawValue.trim().split("/", limit = 2)
            require(tokens.size == 2) {
                "Invalid desktopTarget '$rawValue'. Use <platform>/<arch>, e.g. linux/amd64."
            }
            val parsedTarget = DesktopTarget(platform = DesktopPlatform.parse(tokens[0]), arch = DesktopArch.parse(tokens[1]))
            require(parsedTarget in supported) {
                "Unsupported desktop target '$rawValue'. Supported targets: ${supported.joinToString()}."
            }
            return parsedTarget
        }

        fun parseHost(platformRawValue: String, archRawValue: String): DesktopTarget =
            DesktopTarget(
                platform = DesktopPlatform.parseHost(platformRawValue),
                arch = DesktopArch.parse(archRawValue),
            )
    }
}

fun resolveHostDesktopTarget(): DesktopTarget =
    DesktopTarget.parseHost(
        platformRawValue = System.getProperty("os.name"),
        archRawValue = System.getProperty("os.arch"),
    )

val requestedDesktopTargetRaw = project.findProperty("desktopTarget")?.toString()?.trim().orEmpty()
val requestedDesktopTarget =
    if (requestedDesktopTargetRaw.isNotEmpty()) {
        DesktopTarget.parse(requestedDesktopTargetRaw)
    } else {
        null
    }
val desktopTarget = requestedDesktopTarget ?: resolveHostDesktopTarget()
val composeDesktopVersion = libs.versions.composeMultiplatform.get()

val desktopJarName = desktopTarget.libcoreDesktopJarName
val desktopJarFile = layout.projectDirectory.file("libs/$desktopJarName").asFile
/** Desktop libcore. Must stay empty when the jar is absent — never use `files { require(...) }` (Gradle resolves it for Android compile). */
val libcoreDesktopJar = objects.fileCollection().apply {
    if (desktopJarFile.isFile) from(desktopJarFile)
}

val missingDesktopLibcoreMessage =
    "Missing desktop libcore jar '${desktopJarFile.path}'. Build it first, e.g. make libcore_desktop DESKTOP_TARGETS=$desktopTarget."

val skipDesktopLibPreCheck: Boolean =
    (project.findProperty("skipDesktopLibPreCheck")?.toString() == "true")

val desktopPackageName = metadata.getProperty("PACKAGE_NAME").trim()
val desktopVersion = metadata.effectiveVersionName()
val desktopTargetFormats = emptySet<TargetFormat>()

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildConfig/kotlin")
    val versionName = metadata.effectiveVersionName()
    val versionCode = metadata.getProperty("VERSION_CODE")
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("fr/husi")
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            """
            |package fr.husi
            |
            |object BuildConfig {
            |    const val VERSION_NAME = "$versionName"
            |    const val VERSION_CODE = $versionCode
            |    const val FLAVOR = ""
            |}
            """.trimMargin(),
        )
    }
}

val generateDesktopPlatformInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/platformInfo/desktop/${desktopTarget.id}")
    val platformInfoPackage = "fr.husi.platform"
    inputs.property("desktopTarget", desktopTarget.toString())
    outputs.dir(outputDir)
    doLast {
        writePlatformInfo(
            outputDir = outputDir.get().asFile,
            packageName = platformInfoPackage,
            fileName = "PlatformInfo.desktop.kt",
            platform = when (desktopTarget.platform) {
                DesktopPlatform.Linux -> "Linux"
                DesktopPlatform.Darwin -> "MacOs"
                DesktopPlatform.Windows -> "Windows"
            },
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        freeCompilerArgs.add("-Xexplicit-backing-fields")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }

    android {
        namespace = "fr.husi.lib"
        compileSdk = 36
        minSdk = 24
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateBuildConfig)
            dependencies {
                compileOnly(libcoreDesktopJar)

                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material3)
                implementation(libs.jetbrains.compose.animation.graphics)
                implementation(libs.jetbrains.compose.components.resources)
                implementation(libs.jetbrains.compose.ui.tooling.preview)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.jetbrains.lifecycle.viewmodel.navigation3)
                implementation(libs.jetbrains.lifecycle.runtime.compose)
                implementation(libs.jetbrains.navigation3.ui)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.androidx.datastore.preferences)
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                implementation(libs.ini4j)
                implementation(libs.kryo)
                implementation(libs.compose.preference)
                implementation(libs.fastscroller.core)
                implementation(libs.fastscroller.material3)
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs.compose)
                implementation(libs.aboutlibraries.compose.m3)
                implementation(libs.zxing.core)
                implementation(project(":library:compose-code-editor:codeeditor"))
                implementation(project(":library:DragDropSwipeLazyColumn"))

                implementation(project.dependencies.platform(libs.koin.bom))
                implementation(libs.koin.core)
                implementation(libs.koin.core.viewmodel)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.koin.compose.navigation3)
            }
        }
        val androidMain by getting {
            languageSettings.optIn("androidx.tv.material3.ExperimentalTvMaterial3Api")
            dependencies {
                implementation(
                    fileTree("libs") {
                        include("*.aar")
                    },
                )

                implementation(libs.kotlinx.coroutines.android)

                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.appcompat)
                implementation(libs.androidx.activity.ktx)


                implementation(libs.androidx.work.runtime.ktx)
                implementation(libs.androidx.work.multiprocess)

                implementation(libs.androidx.datastore)

                implementation(libs.androidx.compose.ui.viewbinding)
                implementation(libs.androidx.activity.compose)
                implementation(libs.koin.android)

                implementation(libs.accompanist.drawablepainter)

                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.compose)

                implementation(libs.smali.dexlib2.get().toString()) {
                    exclude(group = "com.google.guava", module = "guava")
                }
                implementation(libs.guava)

                implementation(libs.process.phoenix)

                implementation(libs.androidx.tv.material)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(libs.junit.jupiter.api)
                runtimeOnly(libs.junit.platform.launcher)
                runtimeOnly(libs.junit.jupiter.engine)
            }
        }
        val desktopMain by getting {
            kotlin.srcDir(generateDesktopPlatformInfo)
            dependencies {
                if (requestedDesktopTarget == null) {
                    implementation(compose.desktop.currentOs)
                } else {
                    implementation("${desktopTarget.composeDependencyNotation}:$composeDesktopVersion")
                }
                implementation(libs.clikt)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libcoreDesktopJar)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.jetbrains.compose.ui.test)
            }
        }
    }
}

gradle.taskGraph.whenReady {
    if (skipDesktopLibPreCheck || desktopJarFile.isFile) return@whenReady
    val composePath = project.path
    val desktopWorkTasks =
        setOf(
            "compileKotlinDesktop",
            "compileDevKotlinDesktop",
            "compileTestKotlinDesktop",
            "kspKotlinDesktop",
            "kspDevKotlinDesktop",
            "kspTestKotlinDesktop",
        )
    if (allTasks.any { task ->
            task.path.startsWith("$composePath:") && task.name in desktopWorkTasks
        }) {
        throw GradleException(missingDesktopLibcoreMessage)
    }
}

compose.desktop {
    application {
        mainClass = "fr.husi.DesktopMainKt"
        nativeDistributions {
            if (desktopTargetFormats.isNotEmpty()) {
                targetFormats(*desktopTargetFormats.toTypedArray())
            }
            packageName = desktopPackageName
            packageVersion = desktopVersion
            description = "Husi desktop proxy integration tool"
            vendor = "Husi contributors"
            copyright = "GPL-3.0-or-later"
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
        }
    }
}

compose.resources {
    packageOfResClass = "fr.husi.resources"
}

aboutLibraries {
    // RU/restricted uplinks: license/funding enrichment fetches remote SPDX data and can hang
    // forever (HttpURLConnection without timeouts). Opt-in offline mode for local builds:
    // -PaboutLibraries.offlineMode=true or ABOUT_LIBRARIES_OFFLINE=true
    offlineMode.set(
        providers.gradleProperty("aboutLibraries.offlineMode")
            .orElse(providers.environmentVariable("ABOUT_LIBRARIES_OFFLINE"))
            .map { it.toBoolean() }
            .orElse(false),
    )
    collect {
        configPath = file("src/commonMain/aboutlibraries")
    }
    export {
        outputFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
    }
}

tasks.named("copyNonXmlValueResourcesForCommonMain") {
    dependsOn(tasks.named("exportLibraryDefinitions"))
}

ksp {
    arg("room.incremental", "true")
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    kspAndroid(libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

tasks.matching { it.name == "packageUberJarForCurrentOS" }.configureEach {
    if (this is Jar) {
        // Exclude non-target native binaries from dependency family buckets.

        val nativeKeepPrefixes = desktopTarget.nativeKeepPrefixes
        val jnaNativeKeepPrefixes = desktopTarget.jnaNativeKeepPrefixes
        val skikoNativeKeepEntries = desktopTarget.skikoNativeKeepEntries
        val targetJarName =
            "$desktopPackageName-${desktopTarget.platform.id}-${desktopTarget.arch.packageJarArchToken}-$desktopVersion.jar"
        val targetJar = layout.buildDirectory.file("compose/jars/$targetJarName")

        outputs.file(targetJar)

        eachFile {
            val entryPath = path
            if (entryPath.startsWith("natives/") && nativeKeepPrefixes.none(entryPath::startsWith)) {
                exclude()
                return@eachFile
            }

            val isJnaNativeBinary =
                entryPath.startsWith("com/sun/jna/") &&
                    (entryPath.endsWith(".so") ||
                        entryPath.endsWith(".dll") ||
                        entryPath.endsWith(".jnilib") ||
                        entryPath.endsWith(".a"))
            if (isJnaNativeBinary && jnaNativeKeepPrefixes.none(entryPath::startsWith)) {
                exclude()
                return@eachFile
            }

            val fileName = entryPath.substringAfterLast('/')
            if (fileName.contains("skiko-", ignoreCase = false) && fileName !in skikoNativeKeepEntries) {
                exclude()
            }
        }

        includeEmptyDirs = false

        doLast {
            val sourceJar = archiveFile.get().asFile
            val requestedJar = targetJar.get().asFile
            require(sourceJar.isFile) {
                "Expected uberjar '${sourceJar.path}' was not generated."
            }

            if (sourceJar.path == requestedJar.path) {
                return@doLast
            }

            sourceJar.copyTo(requestedJar, overwrite = true)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (name == "desktopTest") {
        systemProperty("husi.unitTest", "true")
    }
}

val matrixTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run only matrix-style common tests"
    val desktopTest = tasks.named<Test>("desktopTest")
    dependsOn("desktopTestClasses")
    testClassesDirs = desktopTest.get().testClassesDirs
    classpath = desktopTest.get().classpath
    useJUnitPlatform()
    systemProperty("husi.unitTest", "true")
    filter {
        includeTestsMatching("*.NetworkSwitchMatrixTest")
        includeTestsMatching("*.PoolDegradationMatrixTest")
        includeTestsMatching("*.RecoveryMatrixTest")
        includeTestsMatching("*.InScanNetworkFlapMatrixTest")
        includeTestsMatching("fr.husi.bg.UnderlyingNetworkHandoffPolicyTest")
        includeTestsMatching("fr.husi.simplemode.SimpleModeSessionHealthPolicyTest")
        includeTestsMatching("fr.husi.group.SubscriptionHttpFetchTest")
        includeTestsMatching("fr.husi.group.WhitelistSubscriptionFetchTest")
        includeTestsMatching("fr.husi.scenario.network.NetworkScenarioMatrixTest")
    }
}

val networkScenarioTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run network scenario matrix (uplink, handoff, ruleset bootstrap)"
    val desktopTest = tasks.named<Test>("desktopTest")
    dependsOn("desktopTestClasses")
    testClassesDirs = desktopTest.get().testClassesDirs
    classpath = desktopTest.get().classpath
    useJUnitPlatform()
    systemProperty("husi.unitTest", "true")
    systemProperty("husi.scenarioTest", "true")
    filter {
        includeTestsMatching("fr.husi.scenario.network.*")
    }
}

val integrationScenarioTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration scenario matrix (catalog, route, connect glue)"
    val desktopTest = tasks.named<Test>("desktopTest")
    dependsOn("desktopTestClasses")
    testClassesDirs = desktopTest.get().testClassesDirs
    classpath = desktopTest.get().classpath
    useJUnitPlatform()
    systemProperty("husi.unitTest", "true")
    filter {
        includeTestsMatching("fr.husi.scenario.integration.*")
    }
}

val featureJourneyTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run feature journey matrix (user-facing import/settings/bootstrap flows)"
    val desktopTest = tasks.named<Test>("desktopTest")
    dependsOn("desktopTestClasses")
    testClassesDirs = desktopTest.get().testClassesDirs
    classpath = desktopTest.get().classpath
    useJUnitPlatform()
    systemProperty("husi.unitTest", "true")
    systemProperty("husi.journeyTest", "true")
    filter {
        includeTestsMatching("fr.husi.scenario.journey.*")
    }
}

val fieldLogScenarioTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run field-log scenario matrix (redacted log fingerprints, no libcore HTTP)"
    val desktopTest = tasks.named<Test>("desktopTest")
    dependsOn("desktopTestClasses")
    testClassesDirs = desktopTest.get().testClassesDirs
    classpath = desktopTest.get().classpath
    useJUnitPlatform()
    systemProperty("husi.unitTest", "true")
    filter {
        includeTestsMatching("fr.husi.scenario.fieldlog.*")
    }
}

val simpleModeUiTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run simple-mode screen Compose UI tests (statusTone render, banners, power button)"
    val desktopTest = tasks.named<Test>("desktopTest")
    dependsOn("desktopTestClasses")
    testClassesDirs = desktopTest.get().testClassesDirs
    classpath = desktopTest.get().classpath
    useJUnitPlatform()
    systemProperty("husi.unitTest", "true")
    filter {
        includeTestsMatching("fr.husi.ui.simple.*")
    }
}
