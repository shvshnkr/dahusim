package fr.husi.update

import fr.husi.ktx.USER_AGENT
import fr.husi.libcore.Libcore
import java.io.File

internal actual object AppUpdateHttp {

    actual suspend fun fetchText(url: String): String {
        val client = Libcore.newHttpClient().apply { keepAlive() }
        return client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute().contentString
    }

    actual suspend fun downloadToFile(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        val client = Libcore.newHttpClient().apply { keepAlive() }
        client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute().writeTo(destination.absolutePath, null)
    }
}
