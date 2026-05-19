package fr.husi

import fr.husi.ktx.Logs
import fr.husi.ktx.blankAsNull
import fr.husi.platform.PlatformInfo
import java.io.File

internal object DesktopSystemd {
    private const val UNIT_NAME = "fr.dahusim.daemon.service"

    fun run(
        command: String,
        scope: String,
        launcherCommand: List<String>,
    ): Int {
        if (!PlatformInfo.isLinux) {
            System.err.println("--systemd is only available on Linux")
            return 2
        }
        val normalizedScope = scope.lowercase()
        if (normalizedScope != "user" && normalizedScope != "system") {
            System.err.println("Unsupported --systemd-scope: $scope")
            return 2
        }
        val normalizedCommand = command.trim().lowercase()
        return runCatching {
            when (normalizedCommand) {
                "install" -> {
                    val unitFile = writeUnitFile(normalizedScope, launcherCommand)
                    println("Wrote unit file: ${unitFile.absolutePath}")
                    systemctl(normalizedScope, "daemon-reload")
                    println("systemd daemon reloaded")
                }
                "uninstall" -> {
                    runCatching { systemctl(normalizedScope, "disable", UNIT_NAME) }
                    runCatching { systemctl(normalizedScope, "stop", UNIT_NAME) }
                    val unitFile = unitFile(normalizedScope)
                    if (unitFile.exists() && !unitFile.delete()) {
                        error("failed to delete ${unitFile.absolutePath}")
                    }
                    systemctl(normalizedScope, "daemon-reload")
                    println("Removed unit file and reloaded systemd")
                }
                "enable" -> {
                    systemctl(normalizedScope, "enable", UNIT_NAME)
                    println("Enabled $UNIT_NAME")
                }
                "disable" -> {
                    systemctl(normalizedScope, "disable", UNIT_NAME)
                    println("Disabled $UNIT_NAME")
                }
                "start" -> {
                    systemctl(normalizedScope, "start", UNIT_NAME)
                    println("Started $UNIT_NAME")
                }
                "stop" -> {
                    systemctl(normalizedScope, "stop", UNIT_NAME)
                    println("Stopped $UNIT_NAME")
                }
                "restart" -> {
                    systemctl(normalizedScope, "restart", UNIT_NAME)
                    println("Restarted $UNIT_NAME")
                }
                "status" -> {
                    val output = systemctl(normalizedScope, "status", UNIT_NAME, "--no-pager", "--full")
                    println(output)
                }
                else -> {
                    System.err.println("Unknown --systemd command: $command")
                    return 2
                }
            }
            0
        }.getOrElse { error ->
            Logs.e("systemd command failed", error)
            System.err.println(error.message ?: error.toString())
            1
        }
    }

    private fun writeUnitFile(scope: String, launcherCommand: List<String>): File {
        val file = unitFile(scope)
        file.parentFile?.mkdirs()
        val exec = launcherCommand.plus("--daemon")
            .joinToString(separator = " ", transform = ::quoteForUnit)
        val home = System.getProperty("user.home")
        val content = buildString {
            appendLine("[Unit]")
            appendLine("Description=daHusiM headless daemon")
            appendLine("After=network-online.target")
            appendLine("Wants=network-online.target")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=simple")
            appendLine("ExecStart=$exec")
            appendLine("Restart=on-failure")
            appendLine("RestartSec=2")
            appendLine("WorkingDirectory=$home")
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=default.target")
        }
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun unitFile(scope: String): File {
        return if (scope == "system") {
            File("/etc/systemd/system").resolve(UNIT_NAME)
        } else {
            val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")
                ?.blankAsNull()
                ?.let(::File)
                ?: File(System.getProperty("user.home"), ".config")
            xdgConfigHome.resolve("systemd").resolve("user").resolve(UNIT_NAME)
        }
    }

    private fun systemctl(scope: String, vararg args: String): String {
        val command = mutableListOf("systemctl")
        if (scope == "user") {
            command.add("--user")
        }
        command.addAll(args)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            output.ifBlank {
                "systemctl ${command.drop(1).joinToString(" ")} failed with exit code $exitCode"
            }
        }
        return output
    }

    private fun quoteForUnit(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.none { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' }) return value
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
