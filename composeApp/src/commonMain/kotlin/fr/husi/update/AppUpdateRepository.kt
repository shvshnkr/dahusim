package fr.husi.update

import fr.husi.ktx.kxs

class AppUpdateRepository(
    private val manifestUrl: String = AppUpdateConstants.MANIFEST_URL,
) {

    suspend fun fetchManifest(): AppUpdateManifest {
        val text = AppUpdateHttp.fetchText(manifestUrl)
        return kxs.decodeFromString(text)
    }
}
