package com.altrexis.venus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.altrexis.venus.bridge.VenusBridge
import com.altrexis.venus.files.FileManager
import com.altrexis.venus.notifications.VenusNotifications
import com.altrexis.venus.runtime.RuntimeManager

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var fileManager: FileManager
    private lateinit var runtimeManager: RuntimeManager
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileManager = FileManager(this)
        runtimeManager = RuntimeManager(this)
        runtimeManager.prepare()
        VenusNotifications.createChannel(this)

        web = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Local app content is served through WebViewAssetLoader; direct file:// access is disabled.
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    return try {
                        startActivityForResult(intent, REQ_FILES)
                        true
                    } catch (_: Exception) {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                    request?.url?.let { assetLoader.shouldInterceptRequest(it) }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    val scheme = uri.scheme?.lowercase() ?: return true
                    if (scheme == "https" && uri.host == APP_ASSET_HOST &&
                        (uri.path ?: "").startsWith("/assets/")) return false
                    if (scheme == "https") {
                        openExternal(uri)
                        return true
                    }
                    if (scheme == "http" || scheme == "file" || scheme == "content" || scheme == "javascript") {
                        Toast.makeText(this@MainActivity, "This URL type is blocked for security.", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    return handleExternalUri(uri)
                }
            }
        }

        web.addJavascriptInterface(
            VenusBridge(this, web, fileManager, runtimeManager),
            "VenusAndroid"
        )
        setContentView(web)
        web.loadUrl(APP_ASSET_URL)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleExternalUri(uri: Uri): Boolean {
        return try {
            when (uri.scheme) {
                "tel", "sms", "market" -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }
                "intent" -> {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    val pkg = intent.`package`
                    val allowed = setOf(
                        "com.whatsapp", "com.google.android.youtube", "com.facebook.katana",
                        "com.instagram.android", "com.android.chrome", "com.google.android.apps.maps",
                        "com.google.android.gm", "org.telegram.messenger", "com.facebook.orca",
                        "com.zhiliaoapp.musically", "com.spotify.music", "com.android.vending",
                        "com.android.settings", "com.termux"
                    )
                    if (pkg != null && pkg !in allowed) return true
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    true
                }
                else -> true
            }
        } catch (_: Exception) {
            Toast.makeText(this, "This action could not be opened.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILES) {
            val result = if (resultCode == RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: data.data?.let { uris.add(it) }
                uris.toTypedArray()
            } else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else if (requestCode == REQ_WORKSPACE && resultCode == RESULT_OK) {
            data?.let { resultIntent -> resultIntent.data?.let { uri -> fileManager.persistWorkspace(uri, resultIntent.flags) } }
            web.evaluateJavascript("window.dispatchEvent(new Event('venus-files-updated'))", null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "Notifications enabled" else "Notifications not enabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    fun chooseWorkspace() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_WORKSPACE)
        } catch (_: Exception) {
            Toast.makeText(this, "The folder picker is unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            Toast.makeText(this, "Notifications are ready", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "if(document.body.classList.contains('sidebar-open')){" +
                "document.body.classList.remove('sidebar-open')" +
                "}else if(document.querySelector('#set.on')){" +
                "document.querySelector('#chat').classList.add('on');" +
                "document.querySelector('#set').classList.remove('on')" +
                "}else{history.back()}",
            null
        )
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (::web.isInitialized) {
            web.removeJavascriptInterface("VenusAndroid")
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val REQ_FILES = 101
        const val REQ_WORKSPACE = 102
        const val REQ_NOTIFICATIONS = 103
        private const val APP_ASSET_HOST = "appassets.androidplatform.net"
        private const val APP_ASSET_URL = "https://appassets.androidplatform.net/assets/venus.html"
    }
}
