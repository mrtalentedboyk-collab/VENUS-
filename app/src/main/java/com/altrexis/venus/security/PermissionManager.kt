package com.altrexis.venus.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

class PermissionManager(private val context: Context) {
    fun notificationsGranted(): Boolean = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    /** Files are accessed through the Android Storage Access Framework picker, not broad storage permission. */
    fun broadStoragePermissionRequired(): Boolean = false
}
