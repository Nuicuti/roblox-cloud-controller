package com.rejoin.controller

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CookieInstaller {
    data class InstallResult(val cookieStatus: String, val message: String)

    fun installCookie(context: Context, account: AccountRecord): InstallResult {
        var cookie = account.cookie.trim()
        if (cookie.startsWith(".ROBLOSECURITY=")) cookie = cookie.removePrefix(".ROBLOSECURITY=").trim()
        if (cookie.contains('\r') || cookie.contains('\n')) {
            throw AgentException("Cookie chứa ký tự xuống dòng không hợp lệ.")
        }

        var cookieStatus = "unverified"
        var actualUser = account.username
        try {
            val conn = (URL("https://users.roblox.com/v1/users/authenticated").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Cookie", ".ROBLOSECURITY=$cookie")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            val code = conn.responseCode
            if (code == 200) {
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                val name = obj.optString("name", "")
                if (name.isNotEmpty()) {
                    actualUser = name
                    cookieStatus = "valid"
                }
            } else if (code == 401) {
                throw AgentException("Roblox xác nhận cookie đã hết hạn hoặc không hợp lệ (HTTP 401).")
            }
        } catch (e: AgentException) {
            throw e
        } catch (_: Exception) {
            cookieStatus = "unverified"
        }

        val pkg = account.packageName
        if (!ConfigLimits.isAllowedPackage(pkg)) throw AgentException("Package không hợp lệ.")
        RootShell.run(listOf("/system/bin/am", "force-stop", "--user", "0", pkg), check = false)

        val candidates = listOf(
            "/data/data/$pkg/app_webview/Default/Cookies",
            "/data/data/$pkg/app_webview/Cookies",
        )
        val remotePath = candidates.firstOrNull {
            RootShell.runRaw("[ -f ${RootShell.quote(it)} ]", check = false).exitCode == 0
        } ?: throw AgentException("Ứng dụng ($pkg) chưa tạo kho cookie WebView. Cần mở trước một lần.")

        val uidGid = RootShell.run(listOf("/system/bin/stat", "-c", "%u:%g", remotePath)).output.trim()
        val myUid = Process.myUid()
        val workDir = File(context.cacheDir, "ck_${pkg.replace('.', '_')}")
        workDir.mkdirs()
        val localDb = File(workDir, "Cookies")
        val localBackup = File(workDir, "Cookies.noka-backup")

        try {
            RootShell.runRaw(
                "cp ${RootShell.quote(remotePath)} ${RootShell.quote(localDb.absolutePath)} && " +
                    "(cp ${RootShell.quote("$remotePath-wal")} ${RootShell.quote("${localDb.absolutePath}-wal")} 2>/dev/null || true) && " +
                    "(cp ${RootShell.quote("$remotePath-shm")} ${RootShell.quote("${localDb.absolutePath}-shm")} 2>/dev/null || true) && " +
                    "chown $myUid:$myUid ${RootShell.quote(workDir.absolutePath)}/* && chmod 600 ${RootShell.quote(workDir.absolutePath)}/*",
            )
            localDb.copyTo(localBackup, overwrite = true)

            val db = SQLiteDatabase.openDatabase(localDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.use {
                val cols = mutableSetOf<String>()
                it.rawQuery("PRAGMA table_info(cookies)", null).use { cursor ->
                    while (cursor.moveToNext()) cols.add(cursor.getString(1))
                }
                if (cols.isEmpty()) throw AgentException("Định dạng kho cookie WebView chưa được hỗ trợ.")

                val nowUs = System.currentTimeMillis() * 1000L + 11644473600000000L
                val values = ContentValues().apply {
                    fun putIf(col: String, action: () -> Unit) {
                        if (col in cols) action()
                    }
                    putIf("creation_utc") { put("creation_utc", nowUs) }
                    putIf("host_key") { put("host_key", ".roblox.com") }
                    putIf("top_frame_site_key") { put("top_frame_site_key", "") }
                    putIf("name") { put("name", ".ROBLOSECURITY") }
                    putIf("value") { put("value", cookie) }
                    putIf("encrypted_value") { put("encrypted_value", ByteArray(0)) }
                    putIf("path") { put("path", "/") }
                    putIf("expires_utc") { put("expires_utc", nowUs + 31536000000000L) }
                    putIf("is_secure") { put("is_secure", 1) }
                    putIf("is_httponly") { put("is_httponly", 1) }
                    putIf("last_access_utc") { put("last_access_utc", nowUs) }
                    putIf("has_expires") { put("has_expires", 1) }
                    putIf("is_persistent") { put("is_persistent", 1) }
                    putIf("priority") { put("priority", 1) }
                    putIf("samesite") { put("samesite", -1) }
                    putIf("source_scheme") { put("source_scheme", 2) }
                    putIf("source_port") { put("source_port", 443) }
                    putIf("is_same_party") { put("is_same_party", 0) }
                    putIf("last_update_utc") { put("last_update_utc", nowUs) }
                    putIf("has_cross_site_ancestor") { put("has_cross_site_ancestor", 0) }
                }

                it.beginTransaction()
                try {
                    it.delete("cookies", "host_key LIKE ?", arrayOf("%.roblox.com%"))
                    it.insertOrThrow("cookies", null, values)
                    it.setTransactionSuccessful()
                } finally {
                    it.endTransaction()
                }
                it.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { c -> c.moveToFirst() }
                it.rawQuery("PRAGMA journal_mode=DELETE", null).use { c -> c.moveToFirst() }
                it.rawQuery("SELECT value FROM cookies WHERE host_key = '.roblox.com' AND name = '.ROBLOSECURITY'", null).use { c ->
                    if (!c.moveToFirst() || c.getString(0) != cookie) {
                        throw AgentException("Đọc lại cookie trong SQLite không khớp.")
                    }
                }
            }

            val remoteBackup = "$remotePath.noka-backup"
            RootShell.runRaw(
                "cp ${RootShell.quote(localBackup.absolutePath)} ${RootShell.quote(remoteBackup)} && " +
                    "cp ${RootShell.quote(localDb.absolutePath)} ${RootShell.quote(remotePath)} && " +
                    "rm -f ${RootShell.quote("$remotePath-wal")} ${RootShell.quote("$remotePath-shm")} ${RootShell.quote("$remotePath-journal")} && " +
                    "(rm -f /data/data/$pkg/shared_prefs/RBX_LOGIN* 2>/dev/null || true) && " +
                    "chown ${RootShell.quote(uidGid)} ${RootShell.quote(remotePath)} && chmod 660 ${RootShell.quote(remotePath)} && chmod 600 ${RootShell.quote(remoteBackup)}",
            )
            return InstallResult(cookieStatus, "Đã nạp cookie vào SQLite ($actualUser).")
        } finally {
            workDir.deleteRecursively()
        }
    }
}
