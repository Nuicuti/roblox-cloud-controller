package com.rejoin.controller

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var uidText: TextView
    private lateinit var codeText: TextView
    private lateinit var logText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var dbUrlInput: EditText
    private lateinit var toggleBtn: Button
    private var uiTimer: Timer? = null
    private var lastPairingCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        buildUi()
        loadSavedFirebase()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        uiTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    runOnUiThread { refreshStatus() }
                }
            }, 1500, 1500)
        }
    }

    override fun onPause() {
        uiTimer?.cancel()
        uiTimer = null
        super.onPause()
    }

    private fun loadSavedFirebase() {
        val fb = FirebaseRestClient(File(filesDir, "agent"))
        apiKeyInput.setText(fb.apiKey.ifEmpty { DEFAULT_API_KEY })
        dbUrlInput.setText(fb.databaseUrl.ifEmpty { DEFAULT_DB_URL })
        if (fb.uid.isNotEmpty()) uidText.text = "UID máy: ${fb.uid}"
    }

    private fun refreshStatus() {
        val rootOk = RootShell.hasRoot()
        val accOk = RejoinAccessibilityService.isConnected()
        val svcOk = RejoinForegroundService.isRunning
        statusText.text = "Root (su): ${if (rootOk) "OK" else "CHƯA CẤP"}  ·  " +
            "Dịch vụ ngầm: ${if (svcOk) "ĐANG CHẠY" else "ĐÃ DỪNG"}  ·  " +
            "Accessibility UI: ${if (accOk) "BẬT" else "TẮT (dùng uiautomator)"}"
        if (RejoinForegroundService.currentUid.isNotEmpty()) {
            uidText.text = "UID máy: ${RejoinForegroundService.currentUid}"
        }
        toggleBtn.text = if (svcOk) "DỪNG DỊCH VỤ NGẦM" else "BẬT DỊCH VỤ CHẠY NGẦM"
        logText.text = RejoinForegroundService.recentLogs().ifEmpty { "Chưa có log hoạt động." }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0D12")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
        }

        fun title(text: String) = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.parseColor("#EEF1F8"))
            typeface = Typeface.DEFAULT_BOLD
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#8E96A8"))
            setPadding(0, 20, 0, 6)
        }

        fun box(initial: String = "") = TextView(this).apply {
            text = initial
            textSize = 13f
            setTextColor(Color.parseColor("#D9DFEB"))
            setBackgroundColor(Color.parseColor("#14171F"))
            setPadding(24, 20, 24, 20)
        }

        fun input() = EditText(this).apply {
            setTextColor(Color.parseColor("#EEF1F8"))
            setHintTextColor(Color.parseColor("#677083"))
            setBackgroundColor(Color.parseColor("#14171F"))
            setPadding(24, 20, 24, 20)
            textSize = 13f
        }

        fun btn(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(if (primary) "#14200D" else "#D9DFEB"))
            setBackgroundColor(Color.parseColor(if (primary) "#B6F273" else "#1B202A"))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18 }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

        statusText = box("Đang kiểm tra trạng thái...")
        uidText = box("UID máy: (Bấm Tạo mã ghép)")
        codeText = box("Mã ghép 32 hex: ---")
        apiKeyInput = input()
        dbUrlInput = input()
        logText = box("").apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }

        toggleBtn = btn("BẬT DỊCH VỤ CHẠY NGẦM", primary = true) {
            if (RejoinForegroundService.isRunning) {
                startService(Intent(this, RejoinForegroundService::class.java).apply {
                    action = RejoinForegroundService.ACTION_STOP
                })
            } else {
                val key = apiKeyInput.text.toString().trim()
                val db = dbUrlInput.text.toString().trim()
                try {
                    FirebaseRestClient(File(filesDir, "agent")).saveConfig(key, db)
                    ContextCompat.startForegroundService(this, Intent(this, RejoinForegroundService::class.java))
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        val pairBtn = btn("TẠO MÃ GHÉP MÁY (10 PHÚT)") {
            val key = apiKeyInput.text.toString().trim()
            val db = dbUrlInput.text.toString().trim()
            Thread {
                try {
                    val (uid, code) = RejoinForegroundService.createPairingTicket(this, key, db)
                    lastPairingCode = if (code == "ALREADY_PAIRED") "" else code
                    runOnUiThread {
                        uidText.text = "UID máy: $uid"
                        codeText.text = if (code == "ALREADY_PAIRED") {
                            "Máy đã ghép trên Dashboard trước đó."
                        } else {
                            "Mã ghép (10 phút): $code"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        val copyBtn = btn("SAO CHÉP UID & MÃ GHÉP") {
            val uid = RejoinForegroundService.currentUid.ifEmpty {
                FirebaseRestClient(File(filesDir, "agent")).uid
            }
            val clip = if (lastPairingCode.isNotEmpty()) "UID: $uid\nCODE: $lastPairingCode" else uid
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Pairing", clip))
            Toast.makeText(this, "Đã sao chép vào bộ nhớ tạm.", Toast.LENGTH_SHORT).show()
        }

        val accBtn = btn("MỞ CÀI ĐẶT ACCESSIBILITY (ĐỌC UI NHANH)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val batBtn = btn("TẮT TỐI ƯU PIN CHO APP") {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } else {
                Toast.makeText(this, "App đã được miễn tối ưu pin.", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(title("Rejoin Control (Root Daemon)"))
        root.addView(label("Trạng thái hệ thống"))
        root.addView(statusText)
        root.addView(toggleBtn)
        root.addView(label("Thông tin ghép máy lên Web"))
        root.addView(uidText)
        root.addView(codeText)
        root.addView(pairBtn)
        root.addView(copyBtn)
        root.addView(label("Tối ưu chạy ngầm & đọc màn hình Disconnect/CAPTCHA"))
        root.addView(accBtn)
        root.addView(batBtn)
        root.addView(label("Cấu hình Firebase"))
        root.addView(apiKeyInput)
        root.addView(dbUrlInput)
        root.addView(label("Nhật ký hoạt động"))
        root.addView(logText)

        scroll.addView(root)
        setContentView(scroll)
    }

    companion object {
        private const val DEFAULT_API_KEY = "AIzaSyB1wXy5NNVPI6MT-Zp9fMTwchZdNFYOkic"
        private const val DEFAULT_DB_URL = "https://roblox-cloud-farm-default-rtdb.firebaseio.com"
    }
}
