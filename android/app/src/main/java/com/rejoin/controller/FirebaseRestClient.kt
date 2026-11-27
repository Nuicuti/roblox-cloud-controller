package com.rejoin.controller

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FirebaseRestClient(private val storageDir: File) {
    private val configFile = File(storageDir, "firebase_config.json")
    private val authFile = File(storageDir, "firebase_auth.json")

    private var token: String = ""
    private var expiryMs: Long = 0
    var uid: String = ""
        private set

    var apiKey: String = ""
        private set
    var databaseUrl: String = ""
        private set

    init {
        loadConfig()
    }

    fun loadConfig() {
        if (configFile.exists()) {
            val obj = JSONObject(configFile.readText())
            apiKey = obj.optString("apiKey", "")
            databaseUrl = obj.optString("databaseURL", "").trimEnd('/')
        }
        if (authFile.exists()) {
            val obj = JSONObject(authFile.readText())
            uid = obj.optString("uid", "")
        }
    }

    fun saveConfig(key: String, dbUrl: String) {
        val cleanUrl = dbUrl.trim().trimEnd('/')
        val parsed = URL(cleanUrl)
        if (parsed.protocol != "https" ||
            (!parsed.host.endsWith(".firebaseio.com") && !parsed.host.endsWith(".firebasedatabase.app"))
        ) {
            throw AgentException("URL Firebase Database không hợp lệ.")
        }
        storageDir.mkdirs()
        if (configFile.exists() && databaseUrl.isNotEmpty() && databaseUrl != cleanUrl) {
            authFile.delete()
            clearSharedAuth(storageDir)
            uid = ""
            token = ""
            expiryMs = 0
        }
        apiKey = key.trim()
        databaseUrl = cleanUrl
        configFile.writeText(JSONObject().apply {
            put("apiKey", apiKey)
            put("databaseURL", databaseUrl)
        }.toString())
    }

    fun isConfigured(): Boolean = apiKey.isNotEmpty() && databaseUrl.isNotEmpty()

    @Synchronized
    fun authenticate() {
        if (!isConfigured()) throw AgentException("Chưa cấu hình Firebase API key / Database URL.")
        synchronized(AUTH_LOCK) {
            val cached = sharedAuth[storageDir.absolutePath]
            if (cached != null && cached.dbUrl == databaseUrl && System.currentTimeMillis() < cached.expiryMs) {
                uid = cached.uid
                token = cached.token
                expiryMs = cached.expiryMs
                return
            }
            if (token.isNotEmpty() && System.currentTimeMillis() < expiryMs) return
            val refreshToken = if (authFile.exists()) JSONObject(authFile.readText()).optString("refreshToken", "") else ""
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            if (refreshToken.isNotEmpty()) {
                val body = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}"
                val data = httpJson("https://securetoken.googleapis.com/v1/token?key=$encodedKey", "POST", body, form = true)
                    ?: throw AgentException("Không làm mới được token Firebase.")
                token = data.getString("id_token")
                uid = data.getString("user_id")
                saveAuth(uid, data.getString("refresh_token"))
                expiryMs = System.currentTimeMillis() + (data.getLong("expires_in") - 120L) * 1000L
            } else {
                val payload = JSONObject().put("returnSecureToken", true).toString()
                val data = httpJson("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$encodedKey", "POST", payload)
                    ?: throw AgentException("Không tạo được danh tính thiết bị Firebase.")
                token = data.getString("idToken")
                uid = data.getString("localId")
                saveAuth(uid, data.getString("refreshToken"))
                expiryMs = System.currentTimeMillis() + (data.getLong("expiresIn") - 120L) * 1000L
            }
            sharedAuth[storageDir.absolutePath] = CachedSession(databaseUrl, uid, token, expiryMs)
        }
    }

    private fun saveAuth(deviceUid: String, refreshToken: String) {
        storageDir.mkdirs()
        authFile.writeText(JSONObject().apply {
            put("uid", deviceUid)
            put("refreshToken", refreshToken)
        }.toString())
    }

    companion object {
        private data class CachedSession(val dbUrl: String, val uid: String, val token: String, val expiryMs: Long)
        private val AUTH_LOCK = Any()
        private val sharedAuth = mutableMapOf<String, CachedSession>()

        fun clearSharedAuth(storageDir: File) {
            synchronized(AUTH_LOCK) {
                sharedAuth.remove(storageDir.absolutePath)
            }
        }
    }

    fun request(path: String, method: String = "GET", payload: Any? = null): JSONObject? {
        val raw = requestRaw(path, method, payload) ?: return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONObject().put("_value", trimmed)
    }

    fun requestRaw(path: String, method: String = "GET", payload: Any? = null): String? {
        authenticate()
        val encodedPath = ("nokaController/v1/$path").split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val url = "$databaseUrl/$encodedPath.json?auth=${URLEncoder.encode(token, "UTF-8")}"
        val body = when (payload) {
            null -> null
            is String -> payload
            else -> payload.toString()
        }
        return httpRaw(url, method, body, form = false)
    }

    fun device(suffix: String, method: String = "GET", payload: Any? = null): JSONObject? {
        authenticate()
        return request("devices/$uid/$suffix", method, payload)
    }

    fun streamInbox(
        shouldContinue: () -> Boolean,
        onChange: () -> Unit,
        onStreamState: (Boolean) -> Unit,
    ) {
        while (shouldContinue()) {
            var conn: HttpURLConnection? = null
            try {
                authenticate()
                val encodedPath = ("nokaController/v1/devices/$uid/inbox").split("/").joinToString("/") {
                    URLEncoder.encode(it, "UTF-8")
                }
                val url = "$databaseUrl/$encodedPath.json?auth=${URLEncoder.encode(token, "UTF-8")}"
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    clearSharedAuth(storageDir)
                    token = ""
                    expiryMs = 0L
                    onStreamState(false)
                    Thread.sleep(1500)
                    continue
                }
                if (code !in 200..299) {
                    onStreamState(false)
                    Thread.sleep(2500)
                    continue
                }
                onStreamState(true)
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var eventName = ""
                    val dataBuf = StringBuilder()
                    while (shouldContinue()) {
                        if (System.currentTimeMillis() >= expiryMs) break
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> dataBuf.append(line.removePrefix("data:").trim())
                            line.isEmpty() -> {
                                val data = dataBuf.toString()
                                dataBuf.setLength(0)
                                when (eventName) {
                                    "put", "patch" -> {
                                        if (data.isNotEmpty() && !data.endsWith("\"data\":null}")) {
                                            onChange()
                                        }
                                    }
                                    "auth_revoked", "cancel" -> {
                                        clearSharedAuth(storageDir)
                                        token = ""
                                        expiryMs = 0L
                                        eventName = ""
                                        throw RuntimeException("SSE $eventName")
                                    }
                                }
                                eventName = ""
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            } finally {
                onStreamState(false)
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
            if (!shouldContinue()) break
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun httpJson(url: String, method: String, body: String?, form: Boolean = false): JSONObject? {
        val raw = httpRaw(url, method, body, form) ?: return null
        return if (raw.trim().startsWith("{")) JSONObject(raw) else null
    }

    private fun httpRaw(url: String, method: String, body: String?, form: Boolean): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val detail = try {
                conn.errorStream?.bufferedReader()?.readText()?.replace('\n', ' ')?.trim()?.take(180)
            } catch (_: Exception) {
                null
            }
            throw AgentException("Dịch vụ trả HTTP $code${detail?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "; kiểm tra quyền và cấu hình."}")
        }
        return conn.inputStream.bufferedReader().readText()
    }
}
