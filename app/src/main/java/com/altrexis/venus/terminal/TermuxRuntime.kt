package com.altrexis.venus.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.altrexis.venus.runtime.RuntimeConfig

/**
 * Optional Termux RUN_COMMAND adapter. Commands are strictly allow-listed.
 * A successful return means Android accepted the service start, not that the command finished.
 */
class TermuxRuntime(private val context: Context) {
    fun isInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(RuntimeConfig.TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    fun hasRunCommandPermission(): Boolean =
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED

    fun status(): String = when {
        !isInstalled() -> "Termux is not installed."
        !hasRunCommandPermission() ->
            "Termux is installed, but RUN_COMMAND permission is not granted. Enable external app commands in Termux and grant VENUS access."
        else -> "Termux is installed and RUN_COMMAND permission is granted."
    }

    fun dispatch(binaryPath: String, arguments: Array<String>, workingDirectory: String): Boolean {
        if (!isInstalled() || !hasRunCommandPermission()) return false

        val valid = when (binaryPath) {
            "/data/data/com.termux/files/usr/bin/pwd" ->
                arguments.isEmpty() && workingDirectory == RuntimeConfig.TERMUX_HOME
            "/data/data/com.termux/files/usr/bin/ls" ->
                arguments.contentEquals(arrayOf("-la")) &&
                    workingDirectory == RuntimeConfig.TERMUX_WORKSPACE
            "/data/data/com.termux/files/usr/bin/mkdir" ->
                arguments.size == 2 && arguments[0] == "-p" &&
                    arguments[1].matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) &&
                    workingDirectory == RuntimeConfig.TERMUX_WORKSPACE
            else -> false
        }
        if (!valid) return false

        return try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName(
                    RuntimeConfig.TERMUX_PACKAGE,
                    "com.termux.app.RunCommandService"
                )
                putExtra("com.termux.RUN_COMMAND_PATH", binaryPath)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDirectory)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent) != null
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
