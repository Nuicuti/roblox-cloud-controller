package com.rejoin.controller

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/Nuicuti/roblox-cloud-controller/releases/tags/latest"
    private const val DIRECT_APK_URL = "https://github.com/Nuicuti/roblox-cloud-controller/releases/latest/download/rejoin-control.apk"

    data class UpdateCheck(
        val localBuild: Long,
        val localVersion: String,
        val remoteBuild: Long?,
        val releaseName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val updateAvailable: Boolean,
    )

    fun localVersionInfo(context: Context): Pair<Long, String> {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            code to (pi.versionName ?: "2.0.0")
        } catch (_: Exception) {
            1L to "2.0.0"
        }
    }

    fun localVersionSummary(context: Context): String {
        val (code, name) = localVersionInfo(context)
        return "v$name (#$code)"
    }

    fun checkLatest(context: Context): UpdateCheck {
        val (localBuild, localName) = localVersionInfo(context)
        val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RejoinControl/${localBuild}")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw AgentException("Không kiểm tra được bản cập nhật (GitHub HTTP $code).")
        }
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val name = json.optString("name", "")
        val body = json.optString("body", "")
        val buildMatch = Regex("""\bbuild\s+#?(\d+)\b""", RegexOption.IGNORE_CASE).find("$name $body")
        val remoteBuild = buildMatch?.groupValues?.get(1)?.toLongOrNull()

        var assetUrl = DIRECT_APK_URL
        var assetSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == "rejoin-control.apk") {
                    val candidate = a.optString("browser_download_url", "")
                    if (isTrustedDownloadUrl(candidate)) assetUrl = candidate
                    assetSize = a.optLong("size", 0L)
                    break
                }
            }
        }
        val available = remoteBuild == null || remoteBuild > localBuild
        return UpdateCheck(
            localBuild = localBuild,
            localVersion = localName,
            remoteBuild = remoteBuild,
            releaseName = name.ifEmpty { "Latest" },
            downloadUrl = assetUrl,
            sizeBytes = assetSize,
            updateAvailable = available,
        )
    }

    private fun isTrustedDownloadUrl(url: String): Boolean {
        return url.startsWith("https://github.com/Nuicuti/roblox-cloud-controller/releases/") ||
            url.startsWith("https://objects.githubusercontent.com/") ||
            url.startsWith("https://release-assets.githubusercontent.com/")
    }

    fun prepareUpdate(context: Context, force: Boolean = false): Pair<String, File> {
        if (!RootShell.hasRoot()) {
            throw AgentException("Cần cấp quyền Root (su) để tự cài đặt APK.")
        }
        val info = checkLatest(context)
        if (!force && !info.updateAvailable) {
            return "Đang chạy bản mới nhất (${localVersionSummary(context)})." to File("")
        }
        val outDir = File(context.filesDir, "update").apply { mkdirs() }
        val tmpFile = File(outDir, "rejoin-update.apk.part")
        val finalFile = File(outDir, "rejoin-update.apk")
        tmpFile.delete()
        finalFile.delete()

        downloadFollowingRedirects(info.downloadUrl, tmpFile)
        if (!tmpFile.exists() || tmpFile.length() < 1_000_000L) {
            tmpFile.delete()
            throw AgentException("File APK tải về không hợp lệ hoặc quá nhỏ.")
        }
        val magic = ByteArray(2)
        tmpFile.inputStream().use { it.read(magic) }
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
            tmpFile.delete()
            throw AgentException("File tải về không đúng định dạng APK.")
        }
        @Suppress("DEPRECATION")
        val archiveInfo = context.packageManager.getPackageArchiveInfo(tmpFile.absolutePath, 0)
            ?: run {
                tmpFile.delete()
                throw AgentException("Không đọc được thông tin gói APK vừa tải.")
            }
        if (archiveInfo.packageName != context.packageName) {
            tmpFile.delete()
            throw AgentException("Package APK không khớp (${archiveInfo.packageName}).")
        }
        if (!tmpFile.renameTo(finalFile)) {
            tmpFile.copyTo(finalFile, overwrite = true)
            tmpFile.delete()
        }
        val label = info.remoteBuild?.let { "build #$it" } ?: info.releaseName
        return "Đã tải xong $label (${finalFile.length() / 1024} KB). Đang tự cài đặt và giữ nguyên dữ liệu ghép máy..." to finalFile
    }

    private fun downloadFollowingRedirects(startUrl: String, dest: File) {
        var currentUrl = startUrl
        repeat(6) {
            if (!isTrustedDownloadUrl(currentUrl)) {
                throw AgentException("Liên kết tải APK ngoài phạm vi GitHub Releases.")
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "RejoinControlUpdater")
            }
            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: throw AgentException("Thiếu chuyển hướng tải APK.")
                currentUrl = URL(URL(currentUrl), loc).toString()
                return@repeat
            }
            if (status !in 200..299) {
                throw AgentException("Tải APK thất bại (HTTP $status).")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 32_768)
                }
            }
            return
        }
        throw AgentException("Quá nhiều bước chuyển hướng khi tải APK.")
    }

    /**
     * Installs [verifiedApk] as root using a detached script.
     * - First attempts `pm install -r -d` (preserves data natively when signing key matches).
     * - If signature mismatches (e.g. upgrading from an older CI debug build), automatically backs up
     *   app `files/` and `shared_prefs/` (Firebase UID, E2EE key, clones), uninstalls, installs cleanly,
     *   restores files + new UID ownership, and restarts the foreground daemon without losing device pairing!
     */
    fun launchRootInstall(context: Context, verifiedApk: File, restartUi: Boolean) {
        if (!verifiedApk.exists() || verifiedApk.length() < 1_000_000L) {
            throw AgentException("Không tìm thấy tệp APK hợp lệ để cài đặt.")
        }
        val pkg = context.packageName
        val src = RootShell.quote(verifiedApk.absolutePath)
        val script = """
            #!/system/bin/sh
            PKG='$pkg'
            SRC=$src
            TMP_APK=/data/local/tmp/rejoin-control-update.apk
            BAK=/data/local/tmp/rejoin_update_bak
            DATA_DIR="/data/data/${'$'}PKG"
            [ -d "/data/user/0/${'$'}PKG" ] && DATA_DIR="/data/user/0/${'$'}PKG"

            sleep 1
            cp -f "${'$'}SRC" "${'$'}TMP_APK" || exit 1
            chmod 644 "${'$'}TMP_APK"

            OUT=${'$'}(/system/bin/pm install -r -d "${'$'}TMP_APK" 2>&1)
            RC=${'$'}?
            if [ "${'$'}RC" -ne 0 ]; then
              rm -rf "${'$'}BAK"
              mkdir -p "${'$'}BAK"
              [ -d "${'$'}DATA_DIR/files" ] && (cp -a "${'$'}DATA_DIR/files" "${'$'}BAK/files" || cp -r "${'$'}DATA_DIR/files" "${'$'}BAK/files")
              [ -d "${'$'}DATA_DIR/shared_prefs" ] && (cp -a "${'$'}DATA_DIR/shared_prefs" "${'$'}BAK/shared_prefs" || cp -r "${'$'}DATA_DIR/shared_prefs" "${'$'}BAK/shared_prefs")

              /system/bin/am force-stop "${'$'}PKG" >/dev/null 2>&1 || true
              /system/bin/pm uninstall "${'$'}PKG" >/dev/null 2>&1 || true
              /system/bin/pm install -g "${'$'}TMP_APK" >/data/local/tmp/rejoin_pm_install.log 2>&1 || /system/bin/pm install "${'$'}TMP_APK" >/data/local/tmp/rejoin_pm_install.log 2>&1

              sleep 1
              [ -d "/data/user/0/${'$'}PKG" ] && DATA_DIR="/data/user/0/${'$'}PKG"
              NEW_UID=${'$'}(/system/bin/stat -c %u "${'$'}DATA_DIR" 2>/dev/null)
              if [ -z "${'$'}NEW_UID" ]; then
                NEW_UID=${'$'}(/system/bin/dumpsys package "${'$'}PKG" 2>/dev/null | grep -m1 "userId=" | sed 's/.*userId=\([0-9]*\).*/\1/')
              fi
              /system/bin/am force-stop "${'$'}PKG" >/dev/null 2>&1 || true
              mkdir -p "${'$'}DATA_DIR"
              if [ -d "${'$'}BAK/files" ]; then
                rm -rf "${'$'}DATA_DIR/files"
                (cp -a "${'$'}BAK/files" "${'$'}DATA_DIR/files" || cp -r "${'$'}BAK/files" "${'$'}DATA_DIR/files")
              fi
              if [ -d "${'$'}BAK/shared_prefs" ]; then
                rm -rf "${'$'}DATA_DIR/shared_prefs"
                (cp -a "${'$'}BAK/shared_prefs" "${'$'}DATA_DIR/shared_prefs" || cp -r "${'$'}BAK/shared_prefs" "${'$'}DATA_DIR/shared_prefs")
              fi
              if [ -n "${'$'}NEW_UID" ]; then
                chown -R "${'$'}NEW_UID:${'$'}NEW_UID" "${'$'}DATA_DIR/files" "${'$'}DATA_DIR/shared_prefs" 2>/dev/null || true
              fi
              chmod 700 "${'$'}DATA_DIR/files" "${'$'}DATA_DIR/shared_prefs" 2>/dev/null || true
              /system/bin/restorecon -R "${'$'}DATA_DIR" >/dev/null 2>&1 || true
              rm -rf "${'$'}BAK"
            fi

            rm -f "${'$'}TMP_APK" "${'$'}SRC" /data/local/tmp/rejoin_do_update.sh
            sleep 1
            /system/bin/am start-foreground-service -n "${'$'}PKG/.RejoinForegroundService" >/dev/null 2>&1 || /system/bin/am startservice -n "${'$'}PKG/.RejoinForegroundService" >/dev/null 2>&1 || true
            ${if (restartUi) """/system/bin/am start -n "${'$'}PKG/.MainActivity" >/dev/null 2>&1 || true""" else ""}
        """.trimIndent()

        val encoded = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        RootShell.runRaw(
            "printf '%s' '$encoded' | base64 -d > /data/local/tmp/rejoin_do_update.sh && chmod 700 /data/local/tmp/rejoin_do_update.sh && nohup /system/bin/sh /data/local/tmp/rejoin_do_update.sh >/data/local/tmp/rejoin_update.log 2>&1 </dev/null &",
            check = true,
            timeoutSec = 8,
        )
    }
}
