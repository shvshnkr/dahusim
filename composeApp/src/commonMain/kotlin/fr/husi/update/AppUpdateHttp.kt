package fr.husi.update

import java.io.File

/**
 * HTTP for app-update manifest/APK. On Android while VPN is up, uses the underlying
 * default network so GitHub stays reachable when the tunnel is broken.
 */
internal expect object AppUpdateHttp {

    suspend fun fetchText(url: String): String

    suspend fun downloadToFile(url: String, destination: File)
}
