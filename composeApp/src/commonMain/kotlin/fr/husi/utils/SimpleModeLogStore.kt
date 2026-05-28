package fr.husi.utils

import fr.husi.BuildConfig
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.database.DataStore
import fr.husi.ktx.Logs
import fr.husi.repository.resolveRepository
import fr.husi.simplemode.SimpleModeConnectCoordinator
import fr.husi.simplemode.SimpleModeVpnSessionMarker
import fr.husi.simplemode.isSimpleModePrepareActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SimpleModeLogStore {
    private const val APP_LOG_FILE = "simple_mode_app.log"
    private const val BUILD_CODE_META_FILE = "simple_mode_build_code.txt"
    private const val MAX_BYTES = 1 shl 20
    private const val KEEP_AFTER_TRIM = 786_432
    private const val MAX_EXPORT_COPIES = 20
    private val lock = Any()
    private val lineStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var lastSessionBuildCode: Int? = null

    fun log(tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val file = appLogFile()
                ensureLogInitialized(file)
                appendSessionMarkerIfNeeded(file)
                if (file.exists() && file.length() > MAX_BYTES) {
                    trimTail(file)
                }
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

    fun buildExportCopy(): File {
        synchronized(lock) {
            val dir = logDir()
            val source = appLogFile()
            if (!source.exists()) {
                source.writeText(
                    "${lineStamp.format(Date())} [SimpleMode] log file created for share\n",
                    Charsets.UTF_8,
                )
            }
            val fileName = "husi_simple_log_${fileStamp.format(Date())}.txt"
            val shareFile = File(dir, fileName)
            val exportContent = buildExportHeader() + source.readText(Charsets.UTF_8)
            shareFile.writeText(exportContent, Charsets.UTF_8)
            pruneOldExports(dir)
            return shareFile
        }
    }

    fun clearAppLog() {
        synchronized(lock) {
            runCatching {
                val dir = logDir()
                val appLog = appLogFile()
                if (appLog.exists()) {
                    appLog.delete()
                }
                dir.listFiles { child ->
                    child.isFile &&
                        child.name.startsWith("husi_simple_log_") &&
                        child.name.endsWith(".txt")
                }?.forEach { it.delete() }
                lastSessionBuildCode = null
            }.onFailure {
                Logs.w("simple-mode log clear failed", it)
            }
        }
    }

    fun logDir(): File = File(resolveRepository().cacheDir, "simple-mode").apply { mkdirs() }

    private fun appLogFile(): File = File(logDir(), APP_LOG_FILE)

    private fun buildHeader(): String =
        "${lineStamp.format(Date())} [SimpleMode] build=${BuildConfig.VERSION_NAME} " +
            "code=${BuildConfig.VERSION_CODE}\n"

    private fun buildExportHeader(): String =
        "${lineStamp.format(Date())} [SimpleMode] export_meta build=${BuildConfig.VERSION_NAME} " +
            "code=${BuildConfig.VERSION_CODE} source=$APP_LOG_FILE\n"

    private fun ensureLogInitialized(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.appendText(buildHeader(), Charsets.UTF_8)
        }
    }

    private fun appendSessionMarkerIfNeeded(file: File) {
        val currentBuildCode = BuildConfig.VERSION_CODE
        if (lastSessionBuildCode == currentBuildCode) return
        val previousCode = readPersistedBuildCode()
        if (previousCode != null && previousCode != currentBuildCode) {
            SimpleModeVpnSessionMarker.markGracefulStop("session_upgrade")
            val upgrade = "${lineStamp.format(Date())} [SimpleMode] H0 session_upgrade " +
                "previousCode=$previousCode currentCode=$currentBuildCode " +
                "build=${BuildConfig.VERSION_NAME}\n"
            file.appendText(upgrade, Charsets.UTF_8)
        }
        val previousSuffix = previousCode?.let { " previousCode=$it" }.orEmpty()
        val marker = "${lineStamp.format(Date())} [SimpleMode] H0 session_start " +
            "build=${BuildConfig.VERSION_NAME} code=$currentBuildCode$previousSuffix\n"
        file.appendText(marker, Charsets.UTF_8)
        writePersistedBuildCode(currentBuildCode)
        lastSessionBuildCode = currentBuildCode
        clearStaleSimpleModeActivity()
    }

    private fun clearStaleSimpleModeActivity() {
        if (SimpleModeConnectCoordinator.isInFlight()) return
        val state = BackendState.status.value.state
        if (state == ServiceState.Stopped || state == ServiceState.Idle) {
            if (isSimpleModePrepareActivity(DataStore.simpleModeActivity)) {
                DataStore.simpleModeActivity = ""
            }
        }
    }

    private fun buildCodeMetaFile(): File = File(logDir(), BUILD_CODE_META_FILE)

    private fun readPersistedBuildCode(): Int? =
        buildCodeMetaFile().takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    private fun writePersistedBuildCode(code: Int) {
        buildCodeMetaFile().writeText(code.toString(), Charsets.UTF_8)
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
