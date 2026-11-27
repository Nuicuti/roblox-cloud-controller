package com.rejoin.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RejoinForegroundService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setExplicitStop(this, true)
            setServiceDesired(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        setExplicitStop(this, false)
        setServiceDesired(this, true)
        startForeground(NOTIF_ID, buildNotification("Đang khởi chạy dịch vụ giám sát Root..."))
        acquireWakeLock()
        if (!running) {
            running = true
            worker = Thread({ loop() }, "RejoinWatchdogWorker").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        isRunning = false
        appendLog("Đã dừng dịch vụ chạy ngầm.")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RejoinControl::WatchdogWakeLock").apply {
            acquire()
        }
    }

    private fun loop() {
        isRunning = true
        val dataDir = File(filesDir, "agent")
        val firebase = FirebaseRestClient(dataDir)
        if (!firebase.isConfigured()) {
            appendLog("[!] Chưa cấu hình Firebase API Key / Database URL.")
            stopSelf()
            return
        }
        var rootReady = false
        for (attempt in 1..8) {
            if (!running) break
            if (RootShell.hasRoot()) {
                rootReady = true
                break
            }
            if (attempt == 1) {
                updateNotification("Đang chờ quyền Root sau khi khởi động...")
            }
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (!rootReady) {
            appendLog("[!] Chưa được cấp quyền Root (su). Mở app và cấp quyền SuperUser.")
            stopSelf()
            return
        }

        RootShell.hardenRuntime(packageName)
        val crypto = CryptoStore(dataDir)
        crypto.ensureKeyPair()
        val engine = WatchdogEngine(
            storageDir = dataDir,
            android = RootAndroidDevice(applicationContext),
            crypto = crypto,
            cookieWriter = { acc -> CookieInstaller.installCookie(applicationContext, acc) },
            appVersionInfo = { AppUpdater.localVersionInfo(applicationContext) },
            selfUpdater = {
                val (msg, file) = AppUpdater.prepareUpdate(applicationContext, force = false)
                val hook: (() -> Unit)? = if (file.exists()) {
                    { AppUpdater.launchRootInstall(applicationContext, file, restartUi = false) }
                } else {
                    null
                }
                msg to hook
            },
        )
        try {
            val cleaned = engine.sanitizeBrokenTasks()
            if (cleaned > 0) appendLog("Đã dọn $cleaned cửa sổ bị lệch tọa độ âm.")
            appendLog(engine.scan())
            engine.refreshWindowDiag()
        } catch (e: Exception) {
            appendLog("[!] Quét clone ban đầu lỗi: ${e.message}")
        }

        var lastReportMs = 0L
        var retryStep = 0
        appendLog("Agent APK đang chạy ngầm ổn định.")
        updateNotification("Đang giám sát ${engine.profiles.size} clone · Root OK")

        while (running) {
            try {
                firebase.authenticate()
                currentUid = firebase.uid
                val meta = firebase.device("meta")
                if (meta == null) {
                    updateNotification("Chưa ghép máy trên Web · UID: ${firebase.uid.take(8)}...")
                    Thread.sleep(3000)
                    continue
                }

                val inbox = firebase.device("inbox")
                if (inbox != null) {
                    val commands = inbox.keys().asSequence()
                        .mapNotNull { inbox.optJSONObject(it) }
                        .sortedBy { it.optLong("createdAt", 0) }
                        .toList()
                    for (cmd in commands) {
                        if (!running) break
                        appendLog("-> Nhận lệnh: ${cmd.optString("action")}")
                        engine.processCommand(firebase, cmd)
                        lastReportMs = 0L
                    }
                }

                engine.watchdog(System.currentTimeMillis())

                val now = System.currentTimeMillis()
                if (now - lastReportMs > 12_000L) {
                    firebase.device("telemetry", "PUT", engine.buildTelemetry())
                    lastReportMs = now
                    val activeCount = engine.profiles.values.count { it.enabled && it.healthState == "running" }
                    updateNotification("Online · Watchdog: ${if (engine.watch) "BẬT" else "TẮT"} · Hoạt động: $activeCount/${engine.profiles.size}")
                }
                retryStep = 0
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                appendLog("[!] Cảnh báo: ${e.message}")
                try {
                    engine.watchdog(System.currentTimeMillis())
                } catch (_: Exception) {
                }
                retryStep = minOf(retryStep + 1, 5)
                try {
                    Thread.sleep(minOf(30_000L, (1L shl retryStep) * 1000L))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        engine.saveState()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Rejoin Root Watchdog", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Rejoin Control")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "rejoin_watchdog_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.rejoin.controller.STOP"
        private const val PREFS = "rejoin_prefs"
        private const val KEY_DESIRED = "service_desired"
        private const val KEY_EXPLICIT_STOP = "explicit_stop"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentUid: String = ""
            private set

        private val logLines = ArrayDeque<String>(40)

        fun appendLog(msg: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            synchronized(logLines) {
                if (logLines.size >= 40) logLines.removeFirst()
                logLines.addLast("[$ts] $msg")
            }
        }

        fun recentLogs(): String = synchronized(logLines) { logLines.joinToString("\n") }

        fun isServiceDesired(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DESIRED, false)

        fun setServiceDesired(ctx: Context, desired: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESIRED, desired).apply()
        }

        fun wasExplicitlyStopped(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EXPLICIT_STOP, false)

        fun setExplicitStop(ctx: Context, stopped: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EXPLICIT_STOP, stopped).apply()
        }

        fun createPairingTicket(ctx: Context, apiKey: String, dbUrl: String): Pair<String, String> {
            val dataDir = File(ctx.filesDir, "agent")
            val firebase = FirebaseRestClient(dataDir)
            firebase.saveConfig(apiKey, dbUrl)
            CryptoStore(dataDir).ensureKeyPair()
            firebase.authenticate()
            currentUid = firebase.uid
            val meta = firebase.device("meta")
            if (meta != null) return firebase.uid to "ALREADY_PAIRED"
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val code = bytes.joinToString("") { "%02x".format(it) }
            val payload = JSONObject().apply {
                put("deviceUid", firebase.uid)
                put("expiresAt", System.currentTimeMillis() + 600_000L)
            }
            firebase.request("pairingCodes/$code", "PUT", payload)
            return firebase.uid to code
        }
    }
}
