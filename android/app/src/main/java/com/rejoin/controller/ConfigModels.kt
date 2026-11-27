package com.rejoin.controller

import org.json.JSONObject
import java.security.MessageDigest

class AgentException(message: String) : Exception(message)

object ConfigLimits {
    val PACKAGE_REGEX = Regex("""^([A-Za-z][A-Za-z0-9_]*\.)*[A-Za-z0-9_]*(?:noka|roblox|arceus)[A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*$""", RegexOption.IGNORE_CASE)
    val PLACE_ID_REGEX = Regex("""^\d{0,20}$""")
    val JOB_ID_REGEX = Regex("""^[A-Za-z0-9-]{0,64}$""")
    val UUID_REGEX = Regex("""^[a-f0-9-]{36}$""")

    val BOUNDS: Map<String, IntRange> = mapOf(
        "checkIntervalSec" to 3..60,
        "delayAfterLaunchSec" to 0..120,
        "delayBetweenClonesSec" to 0..60,
        "bootGraceSec" to 10..300,
        "windowGraceSec" to 10..300,
        "disconnectConfirmSec" to 2..60,
        "networkTimeoutSec" to 0..300,
        "maxRejoinIntervalSec" to 30..900,
        "retryAttempts" to 0..50,
        "backoffSec" to 5..120,
        "teleportGraceSec" to 10..300,
        "maxRamMb" to 0..4096,
    )

    fun isAllowedPackage(pkg: String): Boolean = PACKAGE_REGEX.matches(pkg)

    fun buildRobloxUri(placeId: String, jobId: String = ""): String {
        val p = placeId.trim()
        val j = jobId.trim()
        if (p.isEmpty() || !Regex("""^\d{1,20}$""").matches(p)) return ""
        if (j.isNotEmpty() && Regex("""^[A-Za-z0-9-]{1,64}$""").matches(j)) {
            return "roblox://placeId=$p&gameInstanceId=$j"
        }
        return "roblox://experiences/start?placeId=$p"
    }

    fun sha256Prefix16(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

data class RejoinConfig(
    val enabled: Boolean = false,
    val placeId: String = "",
    val jobId: String = "",
    val checkIntervalSec: Int = 15,
    val delayAfterLaunchSec: Int = 10,
    val delayBetweenClonesSec: Int = 15,
    val bootGraceSec: Int = 150,
    val windowGraceSec: Int = 90,
    val disconnectConfirmSec: Int = 10,
    val networkTimeoutSec: Int = 0,
    val maxRejoinIntervalSec: Int = 300,
    val retryAttempts: Int = 0,
    val backoffSec: Int = 10,
    val teleportDetection: Boolean = true,
    val teleportGraceSec: Int = 120,
    val rejoinOnCrash: Boolean = true,
    val detectDisconnectUi: Boolean = true,
    val maxRamMb: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("placeId", placeId)
        put("jobId", jobId)
        put("checkIntervalSec", checkIntervalSec)
        put("delayAfterLaunchSec", delayAfterLaunchSec)
        put("delayBetweenClonesSec", delayBetweenClonesSec)
        put("bootGraceSec", bootGraceSec)
        put("windowGraceSec", windowGraceSec)
        put("disconnectConfirmSec", disconnectConfirmSec)
        put("networkTimeoutSec", networkTimeoutSec)
        put("maxRejoinIntervalSec", maxRejoinIntervalSec)
        put("retryAttempts", retryAttempts)
        put("backoffSec", backoffSec)
        put("teleportDetection", teleportDetection)
        put("teleportGraceSec", teleportGraceSec)
        put("rejoinOnCrash", rejoinOnCrash)
        put("detectDisconnectUi", detectDisconnectUi)
        put("maxRamMb", maxRamMb)
    }

    companion object {
        val EXPECTED_KEYS = setOf(
            "enabled", "placeId", "jobId", "checkIntervalSec", "delayAfterLaunchSec",
            "delayBetweenClonesSec", "bootGraceSec", "windowGraceSec", "disconnectConfirmSec",
            "networkTimeoutSec", "maxRejoinIntervalSec", "retryAttempts", "backoffSec",
            "teleportDetection", "teleportGraceSec", "rejoinOnCrash", "detectDisconnectUi",
            "maxRamMb",
        )

        fun fromJsonStrict(obj: JSONObject): RejoinConfig {
            val keys = obj.keys().asSequence().toSet()
            if (keys != EXPECTED_KEYS) throw AgentException("Cấu hình Auto-rejoin thiếu hoặc dư trường.")
            val placeId = obj.optString("placeId", "").trim()
            val jobId = obj.optString("jobId", "").trim()
            if (!ConfigLimits.PLACE_ID_REGEX.matches(placeId)) throw AgentException("Place ID phải là chữ số.")
            if (!ConfigLimits.JOB_ID_REGEX.matches(jobId)) throw AgentException("Job ID không hợp lệ.")
            for ((k, range) in ConfigLimits.BOUNDS) {
                val raw = obj.opt(k) as? Number ?: throw AgentException("Tham số $k không hợp lệ.")
                val v = raw.toInt()
                if (raw.toDouble() != v.toDouble() || v !in range) {
                    throw AgentException("Tham số $k nằm ngoài giới hạn (${range.first}–${range.last}).")
                }
            }
            for (b in listOf("enabled", "teleportDetection", "rejoinOnCrash", "detectDisconnectUi")) {
                if (obj.opt(b) !is Boolean) throw AgentException("Cấu hình Auto-rejoin không hợp lệ.")
            }
            return RejoinConfig(
                enabled = obj.getBoolean("enabled"),
                placeId = placeId,
                jobId = jobId,
                checkIntervalSec = obj.getInt("checkIntervalSec"),
                delayAfterLaunchSec = obj.getInt("delayAfterLaunchSec"),
                delayBetweenClonesSec = obj.getInt("delayBetweenClonesSec"),
                bootGraceSec = obj.getInt("bootGraceSec"),
                windowGraceSec = obj.getInt("windowGraceSec"),
                disconnectConfirmSec = obj.getInt("disconnectConfirmSec"),
                networkTimeoutSec = obj.getInt("networkTimeoutSec"),
                maxRejoinIntervalSec = obj.getInt("maxRejoinIntervalSec"),
                retryAttempts = obj.getInt("retryAttempts"),
                backoffSec = obj.getInt("backoffSec"),
                teleportDetection = obj.getBoolean("teleportDetection"),
                teleportGraceSec = obj.getInt("teleportGraceSec"),
                rejoinOnCrash = obj.getBoolean("rejoinOnCrash"),
                detectDisconnectUi = obj.getBoolean("detectDisconnectUi"),
                maxRamMb = obj.getInt("maxRamMb"),
            )
        }

        fun fromJsonLenient(obj: JSONObject?, watchFallback: Boolean): RejoinConfig {
            if (obj == null) return RejoinConfig(enabled = watchFallback)
            val d = RejoinConfig()
            fun intBound(k: String, def: Int): Int {
                val range = ConfigLimits.BOUNDS[k] ?: return def
                val v = obj.optInt(k, def)
                return if (v in range) v else def
            }
            val p = obj.optString("placeId", d.placeId).trim()
            val j = obj.optString("jobId", d.jobId).trim()
            return RejoinConfig(
                enabled = if (obj.has("enabled")) obj.optBoolean("enabled", watchFallback) else watchFallback,
                placeId = if (ConfigLimits.PLACE_ID_REGEX.matches(p)) p else "",
                jobId = if (ConfigLimits.JOB_ID_REGEX.matches(j)) j else "",
                checkIntervalSec = intBound("checkIntervalSec", d.checkIntervalSec),
                delayAfterLaunchSec = intBound("delayAfterLaunchSec", d.delayAfterLaunchSec),
                delayBetweenClonesSec = intBound("delayBetweenClonesSec", d.delayBetweenClonesSec),
                bootGraceSec = intBound("bootGraceSec", d.bootGraceSec),
                windowGraceSec = intBound("windowGraceSec", d.windowGraceSec),
                disconnectConfirmSec = intBound("disconnectConfirmSec", d.disconnectConfirmSec),
                networkTimeoutSec = intBound("networkTimeoutSec", d.networkTimeoutSec),
                maxRejoinIntervalSec = intBound("maxRejoinIntervalSec", d.maxRejoinIntervalSec),
                retryAttempts = intBound("retryAttempts", d.retryAttempts),
                backoffSec = intBound("backoffSec", d.backoffSec),
                teleportDetection = obj.optBoolean("teleportDetection", d.teleportDetection),
                teleportGraceSec = intBound("teleportGraceSec", d.teleportGraceSec),
                rejoinOnCrash = obj.optBoolean("rejoinOnCrash", d.rejoinOnCrash),
                detectDisconnectUi = obj.optBoolean("detectDisconnectUi", d.detectDisconnectUi),
                maxRamMb = intBound("maxRamMb", d.maxRamMb),
            )
        }
    }
}

data class GridSettings(
    val rows: Int,
    val cols: Int,
    val gap: Int,
    val top: Int,
    val bottom: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rows", rows)
        put("cols", cols)
        put("gap", gap)
        put("top", top)
        put("bottom", bottom)
    }

    companion object {
        fun fromJson(obj: JSONObject?): GridSettings? {
            if (obj == null) return null
            return GridSettings(
                rows = obj.optInt("rows", 2),
                cols = obj.optInt("cols", 2),
                gap = obj.optInt("gap", 6),
                top = obj.optInt("top", 32),
                bottom = obj.optInt("bottom", 36),
            )
        }
    }
}

data class CloneProfile(
    val packageName: String,
    var label: String,
    var username: String = "",
    var slot: Int,
    var enabled: Boolean = true,
    var autoReopen: Boolean = true,
    var placeId: String = "",
    var jobId: String = "",
    var cookieStatus: String? = null,
    var sqliteStatus: String? = null,
    var loginStatus: String? = null,
    var loginMessage: String? = null,
    var healthState: String? = null,
    var healthMessage: String? = null,
    var retryCount: Int = 0,
    var lastRejoinAt: Long? = null,
    var error: String? = null,
) {
    fun toStateJson(): JSONObject = JSONObject().apply {
        put("package", packageName)
        put("label", label)
        put("username", username)
        put("slot", slot)
        put("enabled", enabled)
        put("autoReopen", autoReopen)
        if (placeId.isNotEmpty()) put("placeId", placeId)
        if (jobId.isNotEmpty()) put("jobId", jobId)
        cookieStatus?.let { put("cookieStatus", it) }
        sqliteStatus?.let { put("sqliteStatus", it) }
        loginStatus?.let { put("loginStatus", it) }
        loginMessage?.let { put("loginMessage", it) }
        healthState?.let { put("healthState", it) }
        healthMessage?.let { put("healthMessage", it) }
        if (retryCount > 0) put("retryCount", retryCount)
        lastRejoinAt?.let { put("lastRejoinAt", it) }
        error?.let { put("error", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject): CloneProfile = CloneProfile(
            packageName = obj.getString("package"),
            label = obj.optString("label", obj.getString("package")),
            username = obj.optString("username", ""),
            slot = obj.optInt("slot", 1),
            enabled = obj.optBoolean("enabled", true),
            autoReopen = obj.optBoolean("autoReopen", true),
            placeId = obj.optString("placeId", ""),
            jobId = obj.optString("jobId", ""),
            cookieStatus = obj.optString("cookieStatus", "").ifEmpty { null },
            sqliteStatus = obj.optString("sqliteStatus", "").ifEmpty { null },
            loginStatus = obj.optString("loginStatus", "").ifEmpty { null },
            loginMessage = obj.optString("loginMessage", "").ifEmpty { null },
            healthState = obj.optString("healthState", "").ifEmpty { null },
            healthMessage = obj.optString("healthMessage", "").ifEmpty { null },
            retryCount = obj.optInt("retryCount", 0),
            lastRejoinAt = if (obj.has("lastRejoinAt")) obj.optLong("lastRejoinAt") else null,
            error = obj.optString("error", "").ifEmpty { null },
        )
    }
}

data class AccountRecord(
    val packageName: String,
    val username: String,
    val password: String,
    val cookie: String,
)
