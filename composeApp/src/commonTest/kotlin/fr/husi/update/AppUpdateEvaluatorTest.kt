package fr.husi.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppUpdateEvaluatorTest {

    @Test
    fun `up to date when offerUpdate false`() {
        val manifest = sampleManifest(offerUpdate = false, versionCode = 999)
        assertIs<AppUpdateCheckResult.UpToDate>(
            AppUpdateEvaluator.evaluate(manifest, installedVersionCode = 1),
        )
    }

    @Test
    fun `up to date when installed version is current`() {
        val manifest = sampleManifest(versionCode = 100)
        assertIs<AppUpdateCheckResult.UpToDate>(
            AppUpdateEvaluator.evaluate(manifest, installedVersionCode = 100),
        )
    }

    @Test
    fun `up to date when below minVersionCode`() {
        val manifest = sampleManifest(versionCode = 200, minVersionCode = 150)
        assertIs<AppUpdateCheckResult.UpToDate>(
            AppUpdateEvaluator.evaluate(manifest, installedVersionCode = 100),
        )
    }

    @Test
    fun `picks preferred android abi`() {
        val manifest = sampleManifest(
            versionCode = 200,
            androidAbis = mapOf(
                "armeabi-v7a" to AppUpdateBinary("https://example/armeabi.apk", "aa"),
                "arm64-v8a" to AppUpdateBinary("https://example/arm64.apk", "bb"),
            ),
        )
        val apk = AppUpdateEvaluator.pickAndroidApk(manifest)
        assertEquals("https://example/arm64.apk", apk?.url)
    }

    @Test
    fun `picks linux deb first`() {
        val manifest = sampleManifest(
            linuxAssets = mapOf(
                "jar" to AppUpdateBinary("https://example/app.jar", "11"),
                "deb" to AppUpdateBinary("https://example/app.deb", "22"),
            ),
        )
        val asset = AppUpdateEvaluator.pickLinuxAsset(manifest)
        assertEquals("deb", asset?.kind)
        assertEquals("https://example/app.deb", asset?.binary?.url)
    }

    private fun sampleManifest(
        offerUpdate: Boolean = true,
        versionCode: Int = 200,
        minVersionCode: Int = 0,
        androidAbis: Map<String, AppUpdateBinary> = emptyMap(),
        linuxAssets: Map<String, AppUpdateBinary> = emptyMap(),
    ) = AppUpdateManifest(
        offerUpdate = offerUpdate,
        versionCode = versionCode,
        versionName = "1.0.0",
        minVersionCode = minVersionCode,
        android = if (androidAbis.isEmpty()) null else AppUpdateAndroid(abis = androidAbis),
        linux = if (linuxAssets.isEmpty()) null else AppUpdateLinux(assets = linuxAssets),
    )
}
