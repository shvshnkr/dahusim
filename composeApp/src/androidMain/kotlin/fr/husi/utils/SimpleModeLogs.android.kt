package fr.husi.utils

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import fr.husi.repository.resolveAndroidRepository
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val DEBUG_ENDPOINT = "http://127.0.0.1:7587/ingest/83220b13-894a-4e68-8cf1-6a9d6c84cae7"
private const val DEBUG_SESSION_ID = "c39a52"

internal actual fun simpleModeLog(tag: String, message: String) {
    SimpleModeLogStore.log(tag, message)
}

internal actual fun simpleModeDebugEvent(
    runId: String,
    hypothesisId: String,
    location: String,
    message: String,
    data: Map<String, String>,
) {
    val payload = JSONObject().apply {
        put("sessionId", DEBUG_SESSION_ID)
        put("runId", runId)
        put("hypothesisId", hypothesisId)
        put("location", location)
        put("message", message)
        put("timestamp", System.currentTimeMillis())
        put("data", JSONObject(data))
    }.toString()
    Thread {
        runCatching {
            val connection = (URL(DEBUG_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Debug-Session-Id", DEBUG_SESSION_ID)
                connectTimeout = 1500
                readTimeout = 1500
                doOutput = true
            }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            connection.inputStream.close()
            connection.disconnect()
        }
    }.start()
}

internal actual fun canShareSimpleModeLogs(): Boolean = true

internal actual suspend fun shareSimpleModeLogs() {
    val appContext = resolveAndroidRepository().context.applicationContext
    val shareFile = SimpleModeLogStore.buildExportCopy()
    val fileName = shareFile.name
    val uri = FileProvider.getUriForFile(
        appContext,
        "${appContext.packageName}.cache",
        shareFile,
    )
    val sendIntent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, fileName)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .also { it.clipData = ClipData.newUri(appContext.contentResolver, fileName, uri) }

    val chooser = Intent.createChooser(sendIntent, "Share logs")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    appContext.startActivity(chooser)
}
