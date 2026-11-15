package com.altrexis.venus.runtime

import android.content.Context
import com.altrexis.venus.notifications.VenusNotifications
import com.altrexis.venus.terminal.TerminalManager

class RuntimeManager(context: Context) {
    val terminal = TerminalManager(context)
    val notifications = VenusNotifications(context)
    val installer = RuntimeInstaller(context)

    fun prepare() {
        installer.prepareAppFolders()
    }

    /** Local private workspace status only; it does not imply that Termux is installed. */
    fun isReady(): Boolean = installer.isWorkspaceReady()

    fun termuxStatus(): String = terminal.status()
}
