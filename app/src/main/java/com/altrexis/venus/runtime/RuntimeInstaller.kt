package com.altrexis.venus.runtime

import android.content.Context
import java.io.File

class RuntimeInstaller(private val context: Context) {
    fun prepareAppFolders(): File {
        val root = File(context.filesDir, RuntimeConfig.APP_WORKSPACE)
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("Could not create the VENUS private workspace.")
        }
        listOf("files", "projects", "logs", "cache", "plugins", "skills").forEach { name ->
            val dir = File(root, name)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("Could not create workspace folder: $name")
            }
            check(dir.isDirectory) { "Workspace path is not a directory: $name" }
        }
        return root
    }

    fun isWorkspaceReady(): Boolean {
        val root = File(context.filesDir, RuntimeConfig.APP_WORKSPACE)
        return listOf("files", "projects", "logs", "cache", "plugins", "skills")
            .all { File(root, it).isDirectory }
    }
}
