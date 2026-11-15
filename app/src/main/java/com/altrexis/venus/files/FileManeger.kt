package com.altrexis.venus.files

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

class FileManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("venus_permissions", Context.MODE_PRIVATE)

    fun persistWorkspace(uri: Uri, grantedFlags: Int) {
        val flags = grantedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            if (flags != 0) context.contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString("workspace_uri", uri.toString()).apply()
        } catch (_: SecurityException) {
            prefs.edit().remove("workspace_uri").apply()
        } catch (_: Exception) {
            prefs.edit().remove("workspace_uri").apply()
        }
    }

    fun workspaceUri(): Uri? =
        prefs.getString("workspace_uri", null)?.let { Uri.parse(it) }

    fun hasWorkspace(): Boolean {
        val uri = workspaceUri() ?: return false
        return try {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && (it.isReadPermission || it.isWritePermission)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
