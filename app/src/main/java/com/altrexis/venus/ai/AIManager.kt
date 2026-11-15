package com.altrexis.venus.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provider-aware API client. Secrets are encrypted with an Android Keystore AES-GCM key.
 * Supported adapters: OpenAI-compatible, Gemini Generative Language API, and Anthropic Messages API.
 */
class AIManager(context: Context) {
    private val prefs = context.getSharedPreferences("venus_ai_secure", Context.MODE_PRIVATE)
    private val alias = "venus_api_profiles_v1"
    private val lock = Any()

    private enum class Provider { OPENAI_COMPATIBLE, GEMINI, ANTHROPIC }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private fun readAll(): JSONArray = synchronized(lock) {
        val packed = prefs.getString("profiles_enc", null) ?: return@synchronized JSONArray()
        try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            require(bytes.size > 28) { "Stored credential data is invalid." }
            val iv = bytes.copyOfRange(0, 12)
            val cipherText = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONArray(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeAll(array: JSONArray) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(
            "profiles_enc",
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        ).apply()
    }

    private fun safeBase(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.length in 9..500) { "Enter a valid HTTPS API Base URL." }
        val uri = try { java.net.URI(value) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid API URL.")
        }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) {
            "Use a valid HTTPS API Base URL."
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "The Base URL must not contain credentials, query parameters, or a fragment."
        }
        return value
            .removeSuffix("/chat/completions")
            .removeSuffix("/messages")
            .removeSuffix("/models")
            .trimEnd('/')
    }

    private fun provider(base: String): Provider {
        val host = URL(base).host.lowercase()
        return when {
            host == "generativelanguage.googleapis.com" -> Provider.GEMINI
            host == "api.anthropic.com" -> Provider.ANTHROPIC
            else -> Provider.OPENAI_COMPATIBLE
        }
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun openConnection(url: String, method: String, apiKey: String, provider: Provider): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 18000
        c.readTimeout = 65000
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-store")
        c.setRequestProperty("Accept", "application/json")
        when (provider) {
            Provider.OPENAI_COMPATIBLE -> c.setRequestProperty("Authorization", "Bearer $apiKey")
            Provider.ANTHROPIC -> {
                c.setRequestProperty("x-api-key", apiKey)
                c.setRequestProperty("anthropic-version", "2023-06-01")
            }
            Provider.GEMINI -> Unit // Gemini authenticates with a key query parameter.
        }
        return c
    }

    private fun responseBody(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        if (body.length > 2_000_000) throw IllegalStateException("The API response is too large.")
        if (code !in 200..299) {
            throw IllegalStateException(diagnoseHttp(code, extractError(body)))
        }
        return body
    }

    fun fetchModels(baseUrl: String, apiKey: String): String {
        if (apiKey.isBlank() || apiKey.length > 4096) {
            return JSONObject().put("ok", false).put("error", "Enter a valid API key.").toString()
        }
        var connection: HttpURLConnection? = null
        return try {
            val base = safeBase(baseUrl)
            val p = provider(base)
            val endpoint = when (p) {
                Provider.OPENAI_COMPATIBLE -> "$base/models"
                Provider.ANTHROPIC -> "$base/models"
                Provider.GEMINI -> "$base/models?key=${encoded(apiKey)}"
            }
            connection = openConnection(endpoint, "GET", apiKey, p)
            val body = responseBody(connection)
            val source = JSONObject(body)
            val models = JSONArray()
            when (p) {
                Provider.OPENAI_COMPATIBLE, Provider.ANTHROPIC -> {
                    val data = source.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isNotEmpty() && id.length <= 300) models.put(id)
                    }
                }
                Provider.GEMINI -> {
                    val data = source.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        var supportsChat = false
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") supportsChat = true
                        }
                        val id = item.optString("name").removePrefix("models/").trim()
                        if (supportsChat && id.isNotEmpty() && id.length <= 300) models.put(id)
                    }
                }
            }
            if (models.length() == 0) {
                JSONObject().put("ok", false)
                    .put("error", "The provider returned no usable models. Check the API endpoint, key permissions, and provider model-list support.")
                    .toString()
            } else {
                JSONObject().put("ok", true).put("provider", p.name.lowercase())
                    .put("models", models).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", explain(e)).toString()
        } finally {
            connection?.disconnect()
        }
    }

    private fun explain(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Server not found. Check the Base URL and internet connection."
        is java.net.SocketTimeoutException -> "The provider took too long to respond. Try again later."
        is javax.net.ssl.SSLException -> "HTTPS certificate/connection failed. Use the provider's correct HTTPS Base URL."
        is IllegalArgumentException -> e.message ?: "Invalid API configuration."
        else -> e.message ?: "API request failed. Check the URL, key, provider status, and model access."
    }

    private fun diagnoseHttp(code: Int, detail: String): String {
        val hint = when (code) {
            400 -> "Check the request format and provider API version."
            401 -> "Authentication failed. Recheck the API key; it may be incorrect, expired, or missing permissions."
            403 -> "Access denied. Check key permissions, account access, and usage restrictions."
            404 -> "The API endpoint was not found. Check that the Base URL is the provider API root."
            408, 504 -> "The provider timed out. Try again later."
            429 -> "Rate limit or quota reached. Check provider usage and limits."
            in 500..599 -> "The provider server has an error. Try again later."
            else -> "Check the provider API documentation and response."
        }
        return "HTTP $code — $detail\nWhat to check: $hint"
    }

    fun saveNormalApi(nameRaw: String, keyRaw: String): String = try {
        val name = nameRaw.trim().ifBlank { "OpenAI API" }.take(60)
        val secret = keyRaw.trim()
        require(secret.isNotBlank() && secret.length <= 4096) { "Paste a valid API key first." }
        val checked = JSONObject(fetchModels("https://api.openai.com/v1", secret))
        require(checked.optBoolean("ok")) {
            checked.optString("error", "OpenAI API key validation failed.")
        }
        val models = checked.optJSONArray("models") ?: JSONArray()
        var hasDefault = false
        for (i in 0 until models.length()) if (models.optString(i) == "gpt-4o-mini") hasDefault = true
        require(hasDefault) {
            "The key is valid, but gpt-4o-mini is not available to this account. Use Fetch Models to choose an available model."
        }
        val id = "normal-" + java.util.UUID.randomUUID().toString()
        val selected = JSONArray().put("gpt-4o-mini")
        val profile = JSONObject()
            .put("id", id).put("name", name)
            .put("baseUrl", "https://api.openai.com/v1")
            .put("key", secret).put("models", models)
            .put("enabledModels", selected).put("normal", true)
            .put("provider", Provider.OPENAI_COMPATIBLE.name)
        val all = readAll()
        all.put(profile)
        writeAll(all)
        JSONObject().put("ok", true).put("id", id).put("model", "gpt-4o-mini").toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: "Could not validate or save API key.").toString()
    }

    fun saveProfile(profileJson: String): String = try {
        val incoming = JSONObject(profileJson)
        val id = incoming.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
        val name = incoming.optString("name").trim().take(60)
        val base = safeBase(incoming.optString("baseUrl"))
        val secret = incoming.optString("key").trim()
        val models = incoming.optJSONArray("models") ?: JSONArray()
        require(name.isNotBlank() && secret.isNotBlank() && models.length() > 0) {
            "Name, API key, and successfully fetched models are required."
        }
        require(secret.length <= 4096 && models.length() <= 2000) { "Profile is too large." }
        val enabled = incoming.optJSONArray("enabledModels") ?: models
        val clean = JSONObject()
            .put("id", id).put("name", name).put("baseUrl", base)
            .put("key", secret).put("models", models).put("enabledModels", enabled)
            .put("provider", provider(base).name)
        val all = readAll()
        var replaced = false
        for (i in 0 until all.length()) {
            if (all.optJSONObject(i)?.optString("id") == id) {
                all.put(i, clean)
                replaced = true
                break
            }
        }
        if (!replaced) all.put(clean)
        writeAll(all)
        JSONObject().put("ok", true).put("id", id).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: "Could not save API profile.").toString()
    }

    fun updateProfileMeta(profileJson: String): String = try {
        val incoming = JSONObject(profileJson)
        val id = incoming.optString("id")
        val all = readAll()
        var found = false
        for (i in 0 until all.length()) {
            val old = all.optJSONObject(i) ?: continue
            if (old.optString("id") == id) {
                val newName = incoming.optString("name", old.optString("name")).trim().take(60)
                if (newName.isNotBlank()) old.put("name", newName)
                old.put("enabledModels", incoming.optJSONArray("enabledModels")
                    ?: old.optJSONArray("models") ?: JSONArray())
                all.put(i, old)
                found = true
                break
            }
        }
        if (!found) throw IllegalArgumentException("API profile not found.")
        writeAll(all)
        JSONObject().put("ok", true).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: "Update failed.").toString()
    }

    fun profilesJson(): String {
        val result = JSONArray()
        val all = readAll()
        for (i in 0 until all.length()) {
            val p = all.optJSONObject(i) ?: continue
            result.put(
                JSONObject()
                    .put("id", p.optString("id"))
                    .put("name", p.optString("name"))
                    .put("baseUrl", p.optString("baseUrl"))
                    .put("models", p.optJSONArray("models") ?: JSONArray())
                    .put("enabledModels", p.optJSONArray("enabledModels")
                        ?: p.optJSONArray("models") ?: JSONArray())
                    .put("normal", p.optBoolean("normal", false))
            )
        }
        return result.toString()
    }

    fun deleteProfile(id: String): String = try {
        val old = readAll()
        val next = JSONArray()
        for (i in 0 until old.length()) {
            if (old.optJSONObject(i)?.optString("id") != id) next.put(old.get(i))
        }
        writeAll(next)
        JSONObject().put("ok", true).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: "Delete failed.").toString()
    }

    fun complete(profileId: String, requestJson: String): String {
        if (requestJson.length > 28_000_000) return apiError("The request with attachments is too large. Please use smaller files.")
        var connection: HttpURLConnection? = null
        return try {
            val all = readAll()
            var profile: JSONObject? = null
            for (i in 0 until all.length()) {
                if (all.optJSONObject(i)?.optString("id") == profileId) {
                    profile = all.optJSONObject(i)
                    break
                }
            }
            val p = profile ?: return apiError("API profile not found.")
            val body = JSONObject(requestJson)
            val model = body.optString("model").trim()
            val allowed = p.optJSONArray("models") ?: JSONArray()
            var valid = false
            for (i in 0 until allowed.length()) if (allowed.optString(i) == model) valid = true
            require(valid) { "The selected model is not in this API profile." }
            val base = safeBase(p.optString("baseUrl"))
            val secret = p.optString("key")
            when (provider(base)) {
                Provider.OPENAI_COMPATIBLE -> {
                    val c = openConnection("$base/chat/completions", "POST", secret, Provider.OPENAI_COMPATIBLE)
                    connection = c
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    responseBody(c)
                }
                Provider.ANTHROPIC -> {
                    val c = openConnection("$base/messages", "POST", secret, Provider.ANTHROPIC)
                    connection = c
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val messages = body.optJSONArray("messages") ?: JSONArray()
                    val converted = JSONArray()
                    var systemText = ""
                    for (i in 0 until messages.length()) {
                        val m = messages.optJSONObject(i) ?: continue
                        val role = m.optString("role")
                        val content = textContent(m.opt("content"))
                        if (role == "system") systemText = content
                        else if (role == "user" || role == "assistant") {
                            converted.put(JSONObject().put("role", role).put("content", anthropicContent(m.opt("content"))))
                        }
                    }
                    val request = JSONObject()
                        .put("model", model)
                        .put("max_tokens", 2048)
                        .put("messages", converted)
                    if (systemText.isNotBlank()) request.put("system", systemText)
                    c.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                    normalizeAnthropic(responseBody(c))
                }
                Provider.GEMINI -> {
                    val c = openConnection(
                        "$base/models/${encoded(model.removePrefix("models/"))}:generateContent?key=${encoded(secret)}",
                        "POST", secret, Provider.GEMINI
                    )
                    connection = c
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val messages = body.optJSONArray("messages") ?: JSONArray()
                    val contents = JSONArray()
                    var systemText = ""
                    for (i in 0 until messages.length()) {
                        val m = messages.optJSONObject(i) ?: continue
                        val role = m.optString("role")
                        val content = textContent(m.opt("content"))
                        if (role == "system") systemText = content
                        else if (role == "user" || role == "assistant") {
                            contents.put(
                                JSONObject()
                                    .put("role", if (role == "assistant") "model" else "user")
                                    .put("parts", geminiParts(m.opt("content")))
                            )
                        }
                    }
                    val request = JSONObject().put("contents", contents)
                    if (systemText.isNotBlank()) {
                        request.put("systemInstruction",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
                    }
                    request.put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
                    c.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                    normalizeGemini(responseBody(c))
                }
            }
        } catch (e: Exception) {
            apiError(explain(e))
        } finally {
            connection?.disconnect()
        }
    }

    private fun dataUrlParts(raw: String): Pair<String, String>? {
        if (!raw.startsWith("data:", true)) return null
        val comma = raw.indexOf(',')
        if (comma < 0) return null
        val header = raw.substring(5, comma)
        if (!header.contains(";base64", true)) return null
        val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
        return mime to raw.substring(comma + 1)
    }

    private fun anthropicContent(value: Any?): Any {
        if (value is String) return value
        val input = value as? JSONArray ?: return textContent(value)
        val output = JSONArray()
        for (i in 0 until input.length()) {
            val part = input.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> output.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                "image_url" -> {
                    val raw = part.optJSONObject("image_url")?.optString("url").orEmpty()
                    val decoded = dataUrlParts(raw)
                    if (decoded != null && decoded.first.startsWith("image/")) {
                        output.put(
                            JSONObject().put("type", "image")
                                .put("source", JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", decoded.first)
                                    .put("data", decoded.second))
                        )
                    } else {
                        output.put(JSONObject().put("type", "text")
                            .put("text", "[Image attachment is not a supported base64 image for this provider.]"))
                    }
                }
                "video_url" -> output.put(JSONObject().put("type", "text")
                    .put("text", "[Video input is not supported by the Anthropic Messages adapter. Use a compatible Gemini model for video input.]"))
                else -> output.put(JSONObject().put("type", "text")
                    .put("text", "[This attachment type is not supported by the Anthropic adapter.]"))
            }
        }
        return output
    }

    private fun geminiParts(value: Any?): JSONArray {
        if (value is String) return JSONArray().put(JSONObject().put("text", value))
        val input = value as? JSONArray
            ?: return JSONArray().put(JSONObject().put("text", textContent(value)))
        val output = JSONArray()
        for (i in 0 until input.length()) {
            val part = input.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> output.put(JSONObject().put("text", part.optString("text")))
                "image_url" -> {
                    val raw = part.optJSONObject("image_url")?.optString("url").orEmpty()
                    val decoded = dataUrlParts(raw)
                    if (decoded != null && decoded.first.startsWith("image/")) {
                        output.put(JSONObject().put("inline_data",
                            JSONObject().put("mime_type", decoded.first).put("data", decoded.second)))
                    } else {
                        output.put(JSONObject().put("text", "[Image URL could not be converted to Gemini inline image data.]"))
                    }
                }
                "video_url" -> {
                    val raw = part.optJSONObject("video_url")?.optString("url").orEmpty()
                    val decoded = dataUrlParts(raw)
                    if (decoded != null && decoded.first.startsWith("video/")) {
                        output.put(JSONObject().put("inline_data",
                            JSONObject().put("mime_type", decoded.first).put("data", decoded.second)))
                    } else {
                        output.put(JSONObject().put("text", "[Video attachment could not be converted to Gemini inline video data.]"))
                    }
                }
                "file" -> {
                    val raw = part.optJSONObject("file")?.optString("file_data").orEmpty()
                    val decoded = dataUrlParts(raw)
                    if (decoded != null && (decoded.first == "application/pdf" || decoded.first.startsWith("image/"))) {
                        output.put(JSONObject().put("inline_data",
                            JSONObject().put("mime_type", decoded.first).put("data", decoded.second)))
                    } else {
                        output.put(JSONObject().put("text", "[This file type is not supported by the Gemini inline-data adapter.]"))
                    }
                }
                else -> output.put(JSONObject().put("text", "[Unsupported attachment omitted.]"))
            }
        }
        return output
    }

    private fun textContent(value: Any?): String {
        if (value is String) return value
        if (value is JSONArray) {
            val out = StringBuilder()
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i)
                if (part != null && part.has("text")) out.append(part.optString("text"))
                else if (part != null) out.append("[Non-text attachment omitted by this provider adapter]")
            }
            return out.toString()
        }
        return value?.toString() ?: ""
    }

    private fun normalizeAnthropic(body: String): String {
        val root = JSONObject(body)
        val blocks = root.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") text.append(block.optString("text"))
        }
        return JSONObject().put("choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", text.toString())))
        ).toString()
    }

    private fun normalizeGemini(body: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: JSONArray()
        val first = candidates.optJSONObject(0)
            ?: throw IllegalStateException(root.optJSONObject("error")?.optString("message")
                ?: "Gemini returned no response candidate.")
        val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until parts.length()) text.append(parts.optJSONObject(i)?.optString("text") ?: "")
        return JSONObject().put("choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", text.toString())))
        ).toString()
    }

    private fun apiError(message: String): String =
        JSONObject().put("error", JSONObject().put("message", message.take(700))).toString()

    private fun extractError(body: String): String = try {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message")?.take(300)
            ?: root.optString("message").take(300).ifBlank { body.take(300) }
    } catch (_: Exception) {
        body.take(300)
    }
}
