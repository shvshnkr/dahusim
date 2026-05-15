package fr.husi.bg

import fr.husi.ktx.Logs
import fr.husi.ktx.blankAsNull
import fr.husi.platform.Platform
import fr.husi.platform.PlatformInfo
import java.io.File

/** Removes OS-scheduled tasks from legacy desktop builds (schtasks/systemd/launchd). */
internal object DesktopLegacySchedulerCleanup {

    private const val LEGACY_WINDOWS_PREFIX = "Husi-"
    private const val LEGACY_LINUX_UNIT_PREFIX = "fr.husi.desktop"
    private const val LEGACY_MAC_PREFIX = "fr.husi.desktop"
    private const val LEGACY_TASK_IDS = listOf(
        "subscription-auto-update",
        "route-asset-auto-update",
    )

    fun run() {
        when (PlatformInfo.platform) {
            Platform.Android -> Unit
            Platform.Linux -> cleanupLinux()
            Platform.MacOs -> cleanupMac()
            Platform.Windows -> cleanupWindows()
        }
    }

    private fun cleanupWindows() {
        for (taskId in LEGACY_TASK_IDS) {
            val taskName = LEGACY_WINDOWS_PREFIX + taskId
            runCatching {
                runCommand(
                    listOf("schtasks", "/delete", "/tn", taskName, "/f"),
                ) { exitCode, _ -> windowsSchtasksDeleteNotFound(exitCode) }
            }.onFailure {
                Logs.w("remove legacy scheduled task $taskName", it)
            }
        }
    }

    private fun cleanupLinux() {
        val unitDir = linuxSystemdUserDir()
        for (taskId in LEGACY_TASK_IDS) {
            val base = "$LEGACY_LINUX_UNIT_PREFIX.$taskId"
            val timerName = "$base.timer"
            runCatching {
                runCommand("systemctl", "--user", "disable", "--now", timerName)
            }.onFailure {
                Logs.w("disable legacy systemd timer $timerName", it)
            }
            deleteFileIfPresent(unitDir.resolve("$base.service"))
            deleteFileIfPresent(unitDir.resolve("$base.timer"))
        }
        runCatching {
            runCommand("systemctl", "--user", "daemon-reload")
        }.onFailure {
            Logs.w("reload systemd after legacy cleanup", it)
        }
    }

    private fun cleanupMac() {
        val agentsDir = File(System.getProperty("user.home"), "Library/LaunchAgents")
        for (taskId in LEGACY_TASK_IDS) {
            val label = "$LEGACY_MAC_PREFIX.$taskId"
            val agentFile = agentsDir.resolve("$label.plist")
            runCatching {
                runCommand("launchctl", "bootout", macUserDomainTarget(), agentFile.absolutePath)
            }.onFailure {
                Logs.w("bootout legacy launch agent $label", it)
            }
            deleteFileIfPresent(agentFile)
        }
    }

    private fun linuxSystemdUserDir(): File {
        val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")
            ?.blankAsNull()
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")
        return xdgConfigHome.resolve("systemd").resolve("user")
    }

    private fun macUserDomainTarget(): String {
        return "gui/${runCommand("id", "-u").trim()}"
    }

    private fun windowsSchtasksDeleteNotFound(exitCode: Int): Boolean = exitCode == 1

    private fun runCommand(vararg args: String): String {
        return runCommand(args.toList())
    }

    private fun runCommand(
        args: List<String>,
        acceptNonZero: ((exitCode: Int, output: String) -> Boolean)? = null,
    ): String {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 || acceptNonZero?.invoke(exitCode, output) == true) {
            return output
        }
        error(
            output.ifBlank {
                "${args.joinToString(" ")} failed with exit code $exitCode"
            },
        )
    }

    private fun deleteFileIfPresent(file: File) {
        if (!file.exists()) return
        runCatching { file.delete() }
    }
}
