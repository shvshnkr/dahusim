package fr.husi.utils

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import fr.husi.BuildConfig
import fr.husi.ktx.Logs
import fr.husi.repository.resolveAndroidRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private object SimpleModeDebugLog {
    private const val APP_LOG_FILE = "simple_mode_app.log"
    private const val MAX_BYTES = 1 shl 20
    private const val KEEP_AFTER_TRIM = 786_432
    private const val MAX_EXPORT_COPIES = 20
    private val lock = Any()
    private val lineStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val DEBUG_ENDPOINT = "http://127.0.0.1:7587/ingest/83220b13-894a-4e68-8cf1-6a9d6c84cae7"
    private const val DEBUG_SESSION_ID = "c39a52"

    fun log(tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val appContext = resolveAndroidRepository().context.applicationContext
                val dir = File(appContext.cacheDir, "simple-mode").apply { mkdirs() }
                val file = File(dir, APP_LOG_FILE)
                if (!file.exists() || file.length() == 0L) {
                    file.appendText(
                        "${lineStamp.format(Date())} [SimpleMode] build=${BuildConfig.VERSION_NAME} " +
                            "code=${BuildConfig.VERSION_CODE}\n",
                        Charsets.UTF_8,
                    )
                }
                if (file.exists() && file.length() > MAX_BYTES) trimTail(file)
                val line = "${lineStamp.format(Date())} [$tag] $message\n"
                file.appendText(line, Charsets.UTF_8)
                while (file.exists() && file.length() > MAX_BYTES) {
                    trimTail(file)
                }
            }.onFailure {
                Logs.w("simple-mode log write failed", it)
            }
        }
    }

    fun emitDebugEvent(
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

    fun share() {
        synchronized(lock) {
            val appContext = resolveAndroidRepository().context.applicationContext
            val dir = File(appContext.cacheDir, "simple-mode").apply { mkdirs() }
            val source = File(dir, APP_LOG_FILE)
            if (!source.exists()) {
                source.writeText(
                    "${lineStamp.format(Date())} [SimpleMode] log file created for share\n",
                    Charsets.UTF_8,
                )
            }

            val fileName = "husi_simple_log_${fileStamp.format(Date())}.txt"
            val shareFile = File(dir, fileName)
            source.copyTo(shareFile, overwrite = true)
            pruneOldExports(dir)

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
    }

    private fun trimTail(file: File) {
        runCatching {
            val bytes = file.readBytes()
            val cut = bytes.size - KEEP_AFTER_TRIM
            if (cut <= 0) return
            val marker = "\n--- trimmed $cut bytes ---\n".toByteArray(Charsets.UTF_8)
            val tail = bytes.copyOfRange(cut, bytes.size)
            file.writeBytes(marker + tail)
        }.onFailure {
            Logs.w("simple-mode log trim failed", it)
        }
    }

    private fun pruneOldExports(dir: File) {
        runCatching {
            val exports = dir.listFiles { child ->
                child.isFile &&
                    child.name.startsWith("husi_simple_log_") &&
                    child.name.endsWith(".txt") &&
                    child.name != APP_LOG_FILE
            } ?: return
            if (exports.size <= MAX_EXPORT_COPIES) return
            exports.sortedBy { it.lastModified() }
                .take(exports.size - MAX_EXPORT_COPIES)
                .forEach { it.delete() }
        }
    }
}

internal actual fun simpleModeLog(tag: String, message: String) {
    SimpleModeDebugLog.log(tag, message)
}

internal actual fun simpleModeDebugEvent(
    runId: String,
    hypothesisId: String,
    location: String,
    message: String,
    data: Map<String, String>,
) {
    SimpleModeDebugLog.emitDebugEvent(runId, hypothesisId, location, message, data)
}

internal actual fun canShareSimpleModeLogs(): Boolean = true

internal actual suspend fun shareSimpleModeLogs() {
    SimpleModeDebugLog.share()
}
