package com.altrexis.venus.terminal

import android.content.Context
import com.altrexis.venus.runtime.RuntimeConfig

class TerminalManager(context: Context) {
    private val runner = CommandRunner(context)

    fun status(): String = runner.status()

    fun runApprovedSafeCommand(command: String, argument: String): Boolean {
        return when (command) {
            "pwd" -> runner.run(
                "/data/data/com.termux/files/usr/bin/pwd",
                emptyArray(),
                RuntimeConfig.TERMUX_HOME
            )
            "list" -> runner.run(
                "/data/data/com.termux/files/usr/bin/ls",
                arrayOf("-la"),
                RuntimeConfig.TERMUX_WORKSPACE
            )
            "make_folder" -> {
                if (!argument.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}"))) return false
                runner.run(
                    "/data/data/com.termux/files/usr/bin/mkdir",
                    arrayOf("-p", argument),
                    RuntimeConfig.TERMUX_WORKSPACE
                )
            }
            else -> false
        }
    }
}
