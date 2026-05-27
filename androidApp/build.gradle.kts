@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
}

setupApp()

android {
    defaultConfig {
        // ABI splits задаются в buildSrc (setupApp): при задаче *Arm64* — только arm64-v8a.
        ndkVersion = "29.0.14206865"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = false
    }
    namespace = "fr.husi"

}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    debugImplementation(project.dependencies.platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
