package com.altrexis.venus.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import com.altrexis.venus.MainActivity
import com.altrexis.venus.ai.AIManager
import com.altrexis.venus.files.FileManager
import com.altrexis.venus.runtime.RuntimeManager

class VenusBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val files: FileManager,
    private val runtime: RuntimeManager
) {
    private val ai = AIManager(activity)

    @JavascriptInterface fun chooseWorkspace() { activity.runOnUiThread { activity.chooseWorkspace() } }
    @JavascriptInterface fun requestNotifications() { activity.runOnUiThread { activity.requestNotifications() } }
    @JavascriptInterface fun hasPerm(name: String): Boolean = when (name.lowercase()) {
        "files" -> files.hasWorkspace()
        "notifications", "notif" -> files.notificationsGranted()
        else -> false
    }
    @JavascriptInterface fun runSafeCommand(command: String, argument: String): Boolean = runtime.terminal.runApprovedSafeCommand(command, argument)
    @JavascriptInterface fun termuxStatus(): String = runtime.termuxStatus()
    @JavascriptInterface fun findContact(query: String): String? = null
    @JavascriptInterface fun a11yReady(): Boolean = false
    @JavascriptInterface fun screen(): String = ""
    @JavascriptInterface fun act(action: String, id: Int, text: String, direction: String): String = "Accessibility automation is disabled in this build."

    // API credentials remain inside the Android app and are encrypted with Android Keystore.
    @JavascriptInterface fun fetchModels(baseUrl: String, apiKey: String): String = ai.fetchModels(baseUrl, apiKey)
    @JavascriptInterface fun saveApiProfile(profileJson: String): String = ai.saveProfile(profileJson)
    @JavascriptInterface fun saveNormalApi(name: String, apiKey: String): String = ai.saveNormalApi(name, apiKey)
    @JavascriptInterface fun updateApiProfile(profileJson: String): String = ai.updateProfileMeta(profileJson)
    @JavascriptInterface fun getApiProfiles(): String = ai.profilesJson()
    @JavascriptInterface fun deleteApiProfile(id: String): String = ai.deleteProfile(id)
    @JavascriptInterface fun completeChat(profileId: String, requestJson: String): String = ai.complete(profileId, requestJson)

    // Runs the network request on a background thread so the screen never freezes.
    @JavascriptInterface fun completeChatAsync(callId: String, profileId: String, requestJson: String) {
        Thread {
            val result = ai.complete(profileId, requestJson)
            val js = "window.__chatDone(" + JSONObject.quote(callId) + "," +
                JSONObject.quote(result).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")"
            webView.post { webView.evaluateJavascript(js, null) }
        }.start()
    }

    @JavascriptInterface fun notifyTaskDone(title: String, message: String) {
        activity.runOnUiThread { runtime.notifications.show(title, message) }
    }
}
