package fr.husi.update

import android.net.Network
import android.os.Build
import fr.husi.bg.DefaultNetworkMonitor
import fr.husi.bg.NetworkReachabilityProbe
import fr.husi.database.DataStore
import fr.husi.ktx.USER_AGENT
import fr.husi.libcore.Libcore
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal actual object AppUpdateHttp {

    actual suspend fun fetchText(url: String): String =
        String(fetchBytes(url), Charsets.UTF_8)

    actual suspend fun downloadToFile(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        destination.writeBytes(fetchBytes(url))
    }

    private suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val reachability = NetworkReachabilityProbe.probe(fast = true)
        val vpnStarted = DataStore.serviceState.started
        val route = when {
            reachability.whitelistOnly && vpnStarted -> UpdateHttpRoute.TUNNEL
            vpnStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> UpdateHttpRoute.UNDERLYING
            else -> UpdateHttpRoute.DIRECT
        }
        when (route) {
            UpdateHttpRoute.TUNNEL -> fetchViaLibcore(url, route, reachability.whitelistOnly, vpnStarted)
            UpdateHttpRoute.UNDERLYING -> fetchViaUnderlying(url, route, reachability.whitelistOnly, vpnStarted)
            UpdateHttpRoute.DIRECT -> readUrl(url, network = null, route, reachability.whitelistOnly, vpnStarted)
        }
    }

    private fun fetchViaLibcore(
        url: String,
        route: UpdateHttpRoute,
        wlOnly: Boolean,
        vpnStarted: Boolean,
    ): ByteArray {
        logRoute(url, route, wlOnly, vpnStarted)
        val client = Libcore.newHttpClient().apply { keepAlive() }
        return client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute().contentString.toByteArray()
    }

    private suspend fun fetchViaUnderlying(
        url: String,
        route: UpdateHttpRoute,
        wlOnly: Boolean,
        vpnStarted: Boolean,
    ): ByteArray {
        DefaultNetworkMonitor.start()
        try {
            return DefaultNetworkMonitor.withDefaultNetwork { network ->
                readUrl(url, network, route, wlOnly, vpnStarted)
            }
        } finally {
            DefaultNetworkMonitor.stop()
        }
    }

    private fun readUrl(
        url: String,
        network: Network?,
        route: UpdateHttpRoute,
        wlOnly: Boolean,
        vpnStarted: Boolean,
    ): ByteArray {
        logRoute(url, route, wlOnly, vpnStarted)
        val connection = openConnection(url, network).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            simpleModeLog(
                "SimpleMode",
                "H36 app_update_http_fail url=${URL(url).host} route=${route.name.lowercase()} " +
                    "wlOnly=$wlOnly vpnStarted=$vpnStarted " +
                    "error=${e.message ?: e.javaClass.simpleName}",
            )
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun logRoute(url: String, route: UpdateHttpRoute, wlOnly: Boolean, vpnStarted: Boolean) {
        simpleModeLog(
            "SimpleMode",
            "H36 app_update_http url=${URL(url).host} route=${route.name.lowercase()} " +
                "wlOnly=$wlOnly vpnStarted=$vpnStarted",
        )
    }

    private fun openConnection(url: String, network: Network?): HttpURLConnection {
        val target = URL(url)
        return if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            network.openConnection(target) as HttpURLConnection
        } else {
            target.openConnection() as HttpURLConnection
        }
    }
}

private enum class UpdateHttpRoute {
    TUNNEL,
    UNDERLYING,
    DIRECT,
}
