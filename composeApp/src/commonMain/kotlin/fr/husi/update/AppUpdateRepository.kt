package fr.husi.update

import fr.husi.ktx.USER_AGENT
import fr.husi.ktx.kxs
import fr.husi.libcore.Libcore

class AppUpdateRepository(
    private val manifestUrl: String = AppUpdateConstants.MANIFEST_URL,
) {

    suspend fun fetchManifest(): AppUpdateManifest {
        val client = Libcore.newHttpClient().apply { keepAlive() }
        val response = client.newRequest().apply {
            setURL(manifestUrl)
            setUserAgent(USER_AGENT)
        }.execute()
        return kxs.decodeFromString(response.contentString)
    }
}
