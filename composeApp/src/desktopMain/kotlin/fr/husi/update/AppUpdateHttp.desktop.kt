package fr.husi.update

import fr.husi.ktx.USER_AGENT
import fr.husi.libcore.Libcore
import fr.husi.utils.simpleModeLog
import java.io.File
import java.net.URL

internal actual object AppUpdateHttp {

    actual suspend fun fetchText(url: String): String {
        logRoute(url)
        val client = Libcore.newHttpClient().apply { keepAlive() }
        return client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute().contentString
    }

    actual suspend fun downloadToFile(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        logRoute(url)
        val client = Libcore.newHttpClient().apply { keepAlive() }
        client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute().writeTo(destination.absolutePath, null)
    }

    private fun logRoute(url: String) {
        simpleModeLog(
            "SimpleMode",
            "H36 app_update_http url=${URL(url).host} route=direct wlOnly=false vpnStarted=false",
        )
    }
}
