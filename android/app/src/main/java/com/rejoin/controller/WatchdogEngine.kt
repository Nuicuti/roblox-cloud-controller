package com.rejoin.controller

import org.json.JSONObject
import java.io.File

data class HealthTracker(
    var missing: Int = 0,
    var nextAllowedMs: Long = 0,
    val attempts: ArrayDeque<Long> = ArrayDeque(),
    var healthySinceMs: Long? = null,
    var lastCheckMs: Long? = null,
    var lastLaunchMs: Long? = null,
    var windowClosedSinceMs: Long? = null,
    var disconnectSinceMs: Long? = null,
    var teleportAtMs: Long? = null,
    val lastPeers: MutableSet<UdpPeer> = mutableSetOf(),
    var socketLostSinceMs: Long? = null,
    var authPaused: Boolean = false,
)

data class QueuedLaunch(val packageName: String, val forceStop: Boolean)

class WatchdogEngine(
    private val storageDir: File,
    private val android: AndroidDevice,
    private val crypto: CryptoStore,
    private val cookieWriter: (AccountRecord) -> CookieInstaller.InstallResult = {
        throw AgentException("Chưa gắn bộ nạp cookie.")
    },
    private val appVersionInfo: () -> Pair<Long, String> = { 1L to "2.0.0" },
    private val selfUpdater: (() -> Pair<String, (() -> Unit)?>)? = null,
) {
    private val stateFile = File(storageDir, "state.json")
    private val accountsFile = File(storageDir, "accounts.json")
    private val journalFile = File(storageDir, "journal.json")

    val profiles = linkedMapOf<String, CloneProfile>()
    val accounts = linkedMapOf<String, AccountRecord>()
    val journal = linkedMapOf<String, JSONObject>()
    val health = linkedMapOf<String, HealthTracker>()
    val launchQueue = ArrayDeque<QueuedLaunch>()

    var watch: Boolean = false
    var rejoinConfig: RejoinConfig = RejoinConfig()
    var grid: GridSettings? = null
    var windowBackup: Map<String, String>? = null

    private var lastAnyLaunchMs: Long? = null
    private var lastAnyLaunchPkg: String? = null
    private var lastUiReadMs: Long? = null
    private var postAckHook: (() -> Unit)? = null

    init {
        loadState()
        loadAccounts()
        loadJournal()
    }

    private fun loadState() {
        if (!stateFile.exists()) return
        try {
            val obj = JSONObject(stateFile.readText())
            watch = obj.optBoolean("watch", false)
            rejoinConfig = RejoinConfig.fromJsonLenient(obj.optJSONObject("rejoinConfig"), watch)
            grid = GridSettings.fromJson(obj.optJSONObject("grid"))
            obj.optJSONObject("windowBackup")?.let { wb ->
                windowBackup = wb.keys().asSequence().associateWith { wb.getString(it) }
            }
            obj.optJSONObject("profiles")?.let { profObj ->
                for (pkg in profObj.keys()) {
                    profiles[pkg] = CloneProfile.fromJson(profObj.getJSONObject(pkg))
                }
            }
        } catch (_: Exception) {
        }
    }

    fun saveState() {
        storageDir.mkdirs()
        val obj = JSONObject().apply {
            put("watch", watch)
            put("rejoinConfig", rejoinConfig.toJson())
            grid?.let { put("grid", it.toJson()) }
            windowBackup?.let { wb ->
                put("windowBackup", JSONObject().apply { wb.forEach { (k, v) -> put(k, v) } })
            }
            put("profiles", JSONObject().apply {
                profiles.forEach { (pkg, p) -> put(pkg, p.toStateJson()) }
            })
        }
        atomicWrite(stateFile, obj.toString())
    }

    private fun loadAccounts() {
        if (!accountsFile.exists()) return
        try {
            val obj = JSONObject(accountsFile.readText())
            for (pkg in obj.keys()) {
                val r = obj.getJSONObject(pkg)
                accounts[pkg] = AccountRecord(
                    packageName = r.getString("package"),
                    username = r.getString("username"),
                    password = r.optString("password", ""),
                    cookie = r.getString("cookie"),
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun saveAccounts() {
        storageDir.mkdirs()
        val obj = JSONObject()
        accounts.forEach { (pkg, a) ->
            obj.put(pkg, JSONObject().apply {
                put("package", a.packageName)
                put("username", a.username)
                put("password", a.password)
                put("cookie", a.cookie)
            })
        }
        atomicWrite(accountsFile, obj.toString())
    }

    private fun loadJournal() {
        if (!journalFile.exists()) return
        try {
            val obj = JSONObject(journalFile.readText())
            for (id in obj.keys()) {
                journal[id] = obj.getJSONObject(id)
            }
        } catch (_: Exception) {
        }
    }

    fun saveJournal() {
        if (journal.size > 1000) {
            val keep = journal.entries.sortedBy { it.value.optLong("createdAt", 0) }.takeLast(800).map { it.key }.toSet()
            journal.keys.retainAll(keep)
        }
        storageDir.mkdirs()
        val obj = JSONObject()
        journal.forEach { (k, v) -> obj.put(k, v) }
        atomicWrite(journalFile, obj.toString())
    }

    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun effectiveConfig(): RejoinConfig = rejoinConfig.copy(enabled = watch)

    private fun effectiveTarget(p: CloneProfile, cfg: RejoinConfig): Pair<String, String> {
        val placeId = p.placeId.ifEmpty { cfg.placeId }.trim()
        val jobId = (if (p.placeId.isNotEmpty()) p.jobId else cfg.jobId).trim()
        return placeId to jobId
    }

    fun sanitizeBrokenTasks(): Int {
        val fixed = android.sanitizeNegativeBounds(profiles.keys)
        var cleared = false
        for (p in profiles.values) {
            if (p.error?.contains("vị trí lưới") == true || p.error?.contains("Chưa tìm thấy cửa sổ") == true) {
                p.error = null
                p.healthMessage = "Đã đặt lại trạng thái cửa sổ."
                cleared = true
            }
        }
        if (fixed > 0 || cleared) {
            saveState()
        }
        return fixed
    }

    fun refreshWindowDiag(): JSONObject? = try {
        android.refreshDiagnostics(profiles.keys)
    } catch (_: Exception) {
        null
    }

    fun scan(): String {
        val installed = android.packages()
        val knownSlots = mapOf("free.nokaA" to 3, "free.nokaB" to 4, "free.nokaC" to 1, "free.nokaD" to 2)
        val occupied = profiles.values.map { it.slot }.toMutableSet()
        for (pkg in installed) {
            if (pkg in profiles) continue
            val desired = knownSlots[pkg]
            val slot = if (desired != null && desired !in occupied) desired else (1..99).first { it !in occupied }
            if (slot > 36) continue
            occupied.add(slot)
            val label = android.appLabel(pkg) ?: when {
                pkg.startsWith("free.noka") -> {
                    val suffix = pkg.removePrefix("free.noka")
                    if (suffix.length == 1 && suffix[0] in 'A'..'Z') "Noka ${suffix[0] - 'A' + 1}" else pkg
                }
                pkg == "com.roblox.client" -> "Roblox Quốc tế"
                pkg.startsWith("com.roblox.client.") -> "Roblox ${pkg.removePrefix("com.roblox.client.")}"
                else -> pkg
            }
            val user = accounts[pkg]?.username ?: ""
            profiles[pkg] = CloneProfile(packageName = pkg, label = label, username = user, slot = slot)
        }
        saveState()
        if (installed.isEmpty()) {
            val diag = android.scanDiagnostics()
            return if (diag.isNotEmpty()) {
                "Phát hiện 0 bản clone ($diag)."
            } else {
                "Phát hiện 0 bản clone. Cấu hình cũ được giữ nguyên."
            }
        }
        return "Phát hiện ${installed.size} bản clone. Cấu hình cũ được giữ nguyên."
    }

    private fun requireProfile(pkg: String): CloneProfile {
        if (!ConfigLimits.isAllowedPackage(pkg) || pkg !in profiles) {
            throw AgentException("Bản clone chưa được quét và thêm vào quản lý.")
        }
        return profiles.getValue(pkg)
    }

    fun layout(settings: GridSettings, launch: Boolean = true) {
        if (!android.freeformSupported()) {
            throw AgentException("Máy chưa hỗ trợ/bật freeform. Dùng Bật cửa sổ tự do, rồi kiểm tra lại.")
        }
        if (launch) android.beginGridPass()
        val (w, h) = android.displaySize()
        val cells = AndroidInspector.gridBounds(w, h, settings.rows, settings.cols, settings.gap, settings.top, settings.bottom)
        val selected = profiles.values.filter { it.enabled }
        if (selected.isEmpty()) throw AgentException("Chưa có bản nào bật quản lý.")
        if (selected.any { it.slot > cells.size }) throw AgentException("Có bản nằm ngoài số ô lưới; chỉnh vị trí hoặc tăng hàng/cột.")

        val failures = mutableListOf<String>()
        for (p in selected.sortedBy { it.slot }) {
            if (!launch && !android.running(p.packageName)) continue
            try {
                android.resize(p.packageName, cells[p.slot - 1], launch = launch)
                p.error = null
            } catch (e: AgentException) {
                p.error = e.message
                failures.add("${p.label}: ${e.message}")
            }
        }
        saveState()
        if (failures.isNotEmpty()) throw AgentException(failures.joinToString("; "))
    }

    private fun launchPackage(pkg: String, placeId: String, jobId: String, forceStop: Boolean) {
        if (forceStop) android.close(pkg)
        val currentGrid = grid
        if (currentGrid != null) {
            if (!android.freeformSupported()) throw AgentException("Không khôi phục được freeform.")
            val (w, h) = android.displaySize()
            val cells = AndroidInspector.gridBounds(w, h, currentGrid.rows, currentGrid.cols, currentGrid.gap, currentGrid.top, currentGrid.bottom)
            val profile = requireProfile(pkg)
            if (profile.slot > cells.size) throw AgentException("Vị trí bản nằm ngoài lưới.")
            android.resize(pkg, cells[profile.slot - 1], launch = true, placeId = placeId, jobId = jobId)
        } else {
            android.open(pkg, freeform = false, placeId = placeId, jobId = jobId)
        }
    }

    fun openProfile(pkg: String, forceStop: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        val profile = requireProfile(pkg)
        profile.enabled = true
        profile.error = null
        profile.healthState = "booting"
        profile.healthMessage = "Đang khởi chạy vào game."
        val cfg = effectiveConfig()
        val (placeId, jobId) = effectiveTarget(profile, cfg)
        saveState()
        launchPackage(pkg, placeId, jobId, forceStop)
        val h = HealthTracker(lastCheckMs = nowMs, lastLaunchMs = nowMs)
        health[pkg] = h
        lastAnyLaunchMs = nowMs
        lastAnyLaunchPkg = pkg
        profile.lastRejoinAt = System.currentTimeMillis()
        saveState()
    }

    fun enqueueLaunch(pkg: String, forceStop: Boolean = false) {
        launchQueue.removeAll { it.packageName == pkg }
        launchQueue.addLast(QueuedLaunch(pkg, forceStop))
        profiles[pkg]?.apply {
            healthState = "booting"
            healthMessage = "Đang xếp hàng khởi chạy tuần tự."
        }
    }

    fun drainLaunchQueue(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (launchQueue.isEmpty()) return false
        val cfg = effectiveConfig()
        repeat(launchQueue.size) {
            val item = launchQueue.first()
            val h = health.getOrPut(item.packageName) { HealthTracker() }
            if (canLaunch(item.packageName, h, nowMs, cfg)) {
                launchQueue.removeFirst()
                try {
                    openProfile(item.packageName, forceStop = item.forceStop, nowMs = nowMs)
                } catch (e: Exception) {
                    profiles[item.packageName]?.apply {
                        error = e.message
                        healthMessage = e.message
                    }
                }
                return true
            }
            launchQueue.addLast(launchQueue.removeFirst())
        }
        return false
    }

    fun execute(command: JSONObject, nowMs: Long = System.currentTimeMillis()): String {
        return when (val action = command.getString("action")) {
            "scan" -> scan()
            "set_accounts" -> {
                val records = crypto.decryptAccounts(command.getJSONObject("sealed"))
                for (r in records) requireProfile(r.packageName)
                for (r in records) {
                    accounts[r.packageName] = r
                    profiles.getValue(r.packageName).username = r.username
                }
                saveAccounts()
                saveState()
                "Đã lưu ${records.size} tài khoản riêng trên máy."
            }
            "profile" -> {
                val item = command.getJSONObject("profile")
                val pkg = item.getString("package")
                val target = requireProfile(pkg)
                val slot = item.getInt("slot")
                if (slot !in 1..36) throw AgentException("Vị trí slot không hợp lệ.")
                val placeId = item.optString("placeId", target.placeId).trim()
                val jobId = item.optString("jobId", target.jobId).trim()
                if (!ConfigLimits.PLACE_ID_REGEX.matches(placeId)) throw AgentException("Place ID không hợp lệ.")
                if (!ConfigLimits.JOB_ID_REGEX.matches(jobId)) throw AgentException("Job ID không hợp lệ.")
                val prevSlot = target.slot
                for (other in profiles.values) {
                    if (other.packageName != pkg && other.slot == slot) other.slot = prevSlot
                }
                target.label = item.getString("label").trim()
                target.username = item.getString("username").trim()
                target.slot = slot
                target.enabled = item.getBoolean("enabled")
                if (item.has("autoReopen")) target.autoReopen = item.getBoolean("autoReopen")
                target.placeId = placeId
                target.jobId = jobId
                saveState()
                "Đã lưu cấu hình bản clone."
            }
            "set_watch" -> {
                val enabled = command.getBoolean("enabled")
                watch = enabled
                rejoinConfig = rejoinConfig.copy(enabled = enabled)
                health.clear()
                launchQueue.clear()
                saveState()
                if (enabled) "Đã bật Auto-rejoin." else "Đã tắt Auto-rejoin."
            }
            "set_rejoin_config" -> {
                val cfg = RejoinConfig.fromJsonStrict(command.getJSONObject("config"))
                rejoinConfig = cfg
                watch = cfg.enabled
                for (h in health.values) {
                    h.lastCheckMs = null
                    h.windowClosedSinceMs = null
                    h.disconnectSinceMs = null
                    h.socketLostSinceMs = null
                }
                saveState()
                val target = if (cfg.placeId.isNotEmpty()) "Place ${cfg.placeId}" else "mặc định"
                "Đã lưu cấu hình Auto-rejoin (${if (cfg.enabled) "Bật" else "Tắt"}, $target)."
            }
            "close" -> {
                val pkg = command.getString("package")
                val p = requireProfile(pkg)
                p.enabled = false
                p.autoReopen = false
                p.healthState = "stopped"
                p.healthMessage = "Đã đóng thủ công từ dashboard."
                launchQueue.removeAll { it.packageName == pkg }
                saveState()
                android.close(pkg)
                "Đã đóng và tạm dừng tự mở lại cho bản này."
            }
            "open" -> {
                val pkg = command.getString("package")
                val p = requireProfile(pkg)
                p.enabled = true
                p.autoReopen = true
                openProfile(pkg, forceStop = false, nowMs = nowMs)
                "Đã mở ${p.label}."
            }
            "rejoin" -> {
                val pkg = command.getString("package")
                val p = requireProfile(pkg)
                p.enabled = true
                p.autoReopen = true
                openProfile(pkg, forceStop = true, nowMs = nowMs)
                "Đã rejoin ${p.label}."
            }
            "open_all", "rejoin_all" -> {
                val forceStop = action == "rejoin_all"
                val selected = profiles.values.filter { it.enabled }.sortedBy { it.slot }
                if (selected.isEmpty()) throw AgentException("Chưa có bản nào đang bật quản lý.")
                val cfg = effectiveConfig()
                if (cfg.delayBetweenClonesSec > 0) {
                    for (p in selected) {
                        health.remove(p.packageName)
                        enqueueLaunch(p.packageName, forceStop)
                    }
                    drainLaunchQueue(nowMs)
                    val verb = if (forceStop) "rejoin" else "mở"
                    "Đã xếp hàng $verb tuần tự ${selected.size} bản (giãn cách ${cfg.delayBetweenClonesSec}s)."
                } else {
                    for (p in selected) openProfile(p.packageName, forceStop = forceStop, nowMs = nowMs)
                    val verb = if (forceStop) "rejoin" else "mở"
                    "Đã $verb ${selected.size} bản đang bật."
                }
            }
            "grid" -> {
                val (w, h) = android.displaySize()
                val gs = if (command.optBoolean("smart", false)) {
                    val active = profiles.values.filter { it.enabled }.sortedBy { it.slot }
                    val targetCount = maxOf(active.size, 1)
                    val (sr, sc) = AndroidInspector.suggestGridShape(targetCount, w, h)
                    val (barTop, barBottom) = android.systemBarInsets()
                    val capacity = sr * sc
                    val uniqueSlots = active.map { it.slot }.distinct().size
                    if (active.any { it.slot < 1 || it.slot > capacity } || uniqueSlots != active.size) {
                        active.forEachIndexed { idx, p -> p.slot = idx + 1 }
                    }
                    GridSettings(rows = sr, cols = sc, gap = 4, top = barTop, bottom = barBottom)
                } else {
                    GridSettings(
                        rows = command.getInt("rows"),
                        cols = command.getInt("cols"),
                        gap = command.getInt("gap"),
                        top = command.getInt("top"),
                        bottom = command.getInt("bottom"),
                    )
                }
                layout(gs, launch = true)
                grid = gs
                saveState()
                val note = android.lastResizeNote()?.let { " $it" } ?: ""
                "Đã xếp lưới ${gs.rows}×${gs.cols} (${w}×${h}).$note"
            }
            "enable_freeform" -> {
                windowBackup = android.setFreeformFlags(true, windowBackup)
                saveState()
                "Đã bật cờ cửa sổ tự do. Một số máy cần khởi động lại Redfinger để áp dụng."
            }
            "restore_windowing" -> {
                if (windowBackup != null) {
                    android.setFreeformFlags(false, windowBackup)
                }
                for (p in profiles.values) {
                    android.close(p.packageName)
                    p.error = null
                    p.healthState = "stopped"
                    p.healthMessage = "Đã đóng và khôi phục cửa sổ mặc định."
                }
                android.resetAppClonerWindows(profiles.keys)
                launchQueue.clear()
                windowBackup = null
                grid = null
                saveState()
                "Đã đóng các clone đang mở, tắt lưới và khôi phục cửa sổ mặc định."
            }
            "login_all" -> {
                val messages = mutableListOf<String>()
                var failed = false
                for (p in profiles.values.sortedBy { it.slot }) {
                    val acc = accounts[p.packageName] ?: continue
                    if (!p.enabled) continue
                    try {
                        val res = cookieWriter(acc)
                        p.cookieStatus = res.cookieStatus
                        p.sqliteStatus = "written"
                        p.loginStatus = "unverified_ingame"
                        p.loginMessage = res.message
                        p.error = null
                        enqueueLaunch(p.packageName, forceStop = false)
                        messages.add("${p.label}: ${res.message}")
                    } catch (e: Exception) {
                        val msg = e.message ?: "Lỗi nạp cookie"
                        if ("401" in msg) p.cookieStatus = "invalid" else if ("SQLite" in msg || "cookie" in msg) p.sqliteStatus = "failed"
                        failed = true
                        p.loginMessage = msg
                        p.error = msg
                        messages.add("${p.label}: $msg")
                    }
                }
                saveState()
                if (messages.isEmpty()) throw AgentException("Chưa có tài khoản trên các bản đang bật.")
                drainLaunchQueue(nowMs)
                if (failed) throw AgentException(messages.joinToString("; "))
                messages.joinToString("; ")
            }
            "status" -> {
                try {
                    android.refreshDiagnostics(profiles.keys)
                } catch (_: Exception) {
                }
                "Đã cập nhật trạng thái và chẩn đoán APK."
            }
            "self_update" -> {
                val updater = selfUpdater ?: throw AgentException("Thiết bị chưa hỗ trợ tự cập nhật từ xa.")
                val (msg, hook) = updater.invoke()
                postAckHook = hook
                msg
            }
            else -> throw AgentException("Lệnh không được hỗ trợ.")
        }
    }

    private fun canLaunch(pkg: String, h: HealthTracker, nowMs: Long, cfg: RejoinConfig): Boolean {
        if (nowMs < h.nextAllowedMs) return false
        h.lastLaunchMs?.let {
            if (nowMs - it < cfg.delayAfterLaunchSec * 1000L) return false
        }
        if (cfg.delayBetweenClonesSec > 0 && lastAnyLaunchMs != null && lastAnyLaunchPkg != pkg) {
            if (nowMs - lastAnyLaunchMs!! < cfg.delayBetweenClonesSec * 1000L) return false
        }
        return true
    }

    private fun recoverPackage(pkg: String, p: CloneProfile, h: HealthTracker, nowMs: Long, cfg: RejoinConfig, reason: String, forceStop: Boolean): Boolean {
        if (!canLaunch(pkg, h, nowMs, cfg)) return false
        val windowMs = maxOf(600_000L, cfg.maxRejoinIntervalSec * 2000L)
        while (h.attempts.isNotEmpty() && nowMs - h.attempts.first() > windowMs) {
            h.attempts.removeFirst()
        }
        if (cfg.retryAttempts > 0 && h.attempts.size >= cfg.retryAttempts) {
            p.healthState = "paused_retries"
            p.healthMessage = "Đã đạt giới hạn ${cfg.retryAttempts} lần thử lại; tạm nghỉ để tránh lặp mở vô hạn."
            p.error = p.healthMessage
            h.nextAllowedMs = h.attempts.first() + maxOf(600_000L, cfg.maxRejoinIntervalSec * 1000L)
            return false
        }
        h.healthySinceMs = null
        h.attempts.addLast(nowMs)
        val step = minOf(h.attempts.size - 1, 10)
        val backoffMs = cfg.backoffSec * 1000L * (1L shl step)
        val waitMs = minOf(cfg.maxRejoinIntervalSec * 1000L, backoffMs)
        h.nextAllowedMs = nowMs + maxOf(waitMs, cfg.delayAfterLaunchSec * 1000L)
        h.windowClosedSinceMs = null
        h.disconnectSinceMs = null
        h.socketLostSinceMs = null
        h.missing = 0
        val (placeId, jobId) = effectiveTarget(p, cfg)
        return try {
            launchPackage(pkg, placeId, jobId, forceStop)
            h.lastLaunchMs = nowMs
            lastAnyLaunchMs = nowMs
            lastAnyLaunchPkg = pkg
            p.retryCount = h.attempts.size
            p.lastRejoinAt = System.currentTimeMillis()
            p.healthState = "booting"
            p.healthMessage = reason
            p.error = null
            true
        } catch (e: Exception) {
            p.error = e.message
            p.healthMessage = e.message
            false
        }
    }

    private data class CycleSnapshot(
        val tasksText: String?,
        val windowsText: String?,
        val surfacesText: String?,
        val uiXml: String?,
        val accessibilityMap: Map<String, List<String>>?,
    )

    private fun captureSnapshot(nowMs: Long, cfg: RejoinConfig): CycleSnapshot {
        val tasksText = try { android.tasks() } catch (_: Exception) { "" }
        val windowsText = try { android.windows() } catch (_: Exception) { "" }
        val surfacesText = try { android.surfaces() } catch (_: Exception) { "" }
        val accMap = android.accessibilityTexts()
        var xml: String? = null
        if (accMap == null) {
            val cadenceMs = if (lastUiReadMs == null) 0L else maxOf(cfg.checkIntervalSec, 15) * 1000L
            if (lastUiReadMs == null || nowMs - lastUiReadMs!! >= cadenceMs || cfg.checkIntervalSec >= 5) {
                xml = try { android.uiTreeXml() } catch (_: Exception) { "" }
                lastUiReadMs = nowMs
            }
        }
        return CycleSnapshot(tasksText, windowsText, surfacesText, xml, accMap)
    }

    private fun checkRunningHealth(pkg: String, p: CloneProfile, h: HealthTracker, nowMs: Long, cfg: RejoinConfig, snap: CycleSnapshot) {
        val inBootGrace = h.lastLaunchMs != null && nowMs - h.lastLaunchMs!! < cfg.bootGraceSec * 1000L
        val tid = AndroidInspector.parseTasks(snap.tasksText)[pkg]
        val bounds = tid?.let { AndroidInspector.taskBounds(snap.tasksText, it).first }

        val fastState = AndroidInspector.inspectActivityAuthOrAnr(snap.tasksText, snap.windowsText, pkg)
        val uiState = when {
            snap.accessibilityMap != null -> {
                val pkgTexts = snap.accessibilityMap[pkg].orEmpty()
                val sysTexts = snap.accessibilityMap["android"].orEmpty()
                AndroidInspector.inspectUiTexts(pkgTexts, sysTexts)
            }
            !snap.uiXml.isNullOrEmpty() -> AndroidInspector.inspectUiXml(snap.uiXml, pkg, bounds)
            else -> "unknown"
        }

        if (fastState == "auth_required" || uiState == "auth_required") {
            h.authPaused = true
            h.healthySinceMs = null
            p.loginStatus = "manual_verification_required"
            p.healthState = "auth_required"
            p.healthMessage = "Phát hiện màn hình đăng nhập hoặc CAPTCHA — tạm dừng tự rejoin để bạn xác minh thủ công."
            return
        }

        if (inBootGrace) {
            h.windowClosedSinceMs = null
            h.disconnectSinceMs = null
            p.healthState = "booting"
            p.healthMessage = "Đang trong thời gian chờ khởi động game (Boot grace)."
            return
        }

        if (uiState == "teleporting" && cfg.teleportDetection) {
            h.teleportAtMs = nowMs
            h.disconnectSinceMs = null
            p.healthState = "teleporting"
            p.healthMessage = "Phát hiện đang dịch chuyển server (Teleport grace)."
        }

        val peers = if (cfg.teleportDetection || cfg.networkTimeoutSec > 0) {
            try { android.udpPeers(pkg) } catch (_: Exception) { null }
        } else null

        if (peers != null) {
            if (peers.isNotEmpty() && h.lastPeers.isNotEmpty() && peers != h.lastPeers && cfg.teleportDetection) {
                h.teleportAtMs = nowMs
                h.disconnectSinceMs = null
                p.healthState = "teleporting"
                p.healthMessage = "Phát hiện chuyển kết nối server UDP (Teleport grace)."
            }
            if (peers.isNotEmpty()) {
                h.lastPeers.clear()
                h.lastPeers.addAll(peers)
                h.socketLostSinceMs = null
            } else if (cfg.networkTimeoutSec > 0 && h.lastPeers.isNotEmpty()) {
                val inTpGrace = cfg.teleportDetection && h.teleportAtMs != null && nowMs - h.teleportAtMs!! < cfg.teleportGraceSec * 1000L
                if (!inTpGrace) {
                    if (h.socketLostSinceMs == null) h.socketLostSinceMs = nowMs
                    else if (nowMs - h.socketLostSinceMs!! >= cfg.networkTimeoutSec * 1000L) {
                        p.healthState = "disconnected"
                        recoverPackage(pkg, p, h, nowMs, cfg, "Mất kết nối mạng UDP quá thời gian chờ; đang rejoin.", forceStop = true)
                        return
                    }
                }
            }
        }

        val effectiveDisconnect = fastState == "disconnected" || uiState == "disconnected"
        if (cfg.detectDisconnectUi && effectiveDisconnect) {
            val inTpGrace = cfg.teleportDetection && h.teleportAtMs != null && nowMs - h.teleportAtMs!! < cfg.teleportGraceSec * 1000L
            if (inTpGrace) {
                h.disconnectSinceMs = null
                p.healthState = "teleporting"
                p.healthMessage = "Bỏ qua ngắt kết nối ngắn trong thời gian Teleport grace."
            } else {
                if (h.disconnectSinceMs == null) h.disconnectSinceMs = nowMs
                p.healthState = "disconnected"
                p.healthMessage = "Phát hiện màn hình mất kết nối; đang chờ xác nhận."
                if (nowMs - h.disconnectSinceMs!! >= cfg.disconnectConfirmSec * 1000L) {
                    recoverPackage(pkg, p, h, nowMs, cfg, "Xác nhận mất kết nối; đang force-stop và rejoin.", forceStop = true)
                    return
                }
            }
        } else {
            h.disconnectSinceMs = null
        }

        if (cfg.rejoinOnCrash) {
            val checks = android.windowChecks(pkg, snap.uiXml, snap.tasksText, snap.windowsText, snap.surfacesText)
            val (closed, readable) = checks.evaluate()
            if (!readable) {
                h.windowClosedSinceMs = null
            } else if (closed) {
                if (h.windowClosedSinceMs == null) h.windowClosedSinceMs = nowMs
                p.healthState = "window_closed"
                p.healthMessage = "Cửa sổ game chưa hiển thị (4 lớp xác nhận)."
                if (nowMs - h.windowClosedSinceMs!! >= cfg.windowGraceSec * 1000L) {
                    val liveMin = !peers.isNullOrEmpty() && cfg.networkTimeoutSec > 0
                    val reason = if (liveMin) "Cửa sổ bị thu nhỏ với kết nối sống; đang mở lại." else "Cửa sổ game bị đóng quá Window grace; đang rejoin."
                    recoverPackage(pkg, p, h, nowMs, cfg, reason, forceStop = !liveMin)
                    return
                }
            } else {
                h.windowClosedSinceMs = null
            }
        }

        h.healthySinceMs = h.healthySinceMs ?: nowMs
        if (p.healthState !in setOf("teleporting", "disconnected", "window_closed")) {
            p.healthState = "running"
            p.healthMessage = "Đang hoạt động bình thường."
        }
        if (nowMs - h.healthySinceMs!! >= 60_000L) {
            h.attempts.clear()
            p.retryCount = 0
            p.error = null
        }
    }

    fun watchdog(nowMs: Long = System.currentTimeMillis()) {
        drainLaunchQueue(nowMs)
        val cfg = effectiveConfig()
        if (!watch || !cfg.enabled) return
        var snap: CycleSnapshot? = null
        for (p in profiles.values.sortedBy { it.slot }) {
            val pkg = p.packageName
            if (!p.enabled || !p.autoReopen) continue
            if (launchQueue.any { it.packageName == pkg }) continue
            val h = health.getOrPut(pkg) { HealthTracker() }
            if (h.authPaused) {
                p.healthState = "auth_required"
                continue
            }
            if (h.lastCheckMs != null && nowMs - h.lastCheckMs!! < cfg.checkIntervalSec * 1000L) continue
            h.lastCheckMs = nowMs

            if (android.running(pkg)) {
                h.missing = 0
                if (snap == null) snap = captureSnapshot(nowMs, cfg)
                checkRunningHealth(pkg, p, h, nowMs, cfg, snap)
                continue
            }

            h.healthySinceMs = null
            h.windowClosedSinceMs = null
            h.disconnectSinceMs = null
            h.socketLostSinceMs = null
            h.missing += 1
            if (p.healthState != "paused_retries" || nowMs >= h.nextAllowedMs) {
                p.healthState = if (h.missing >= 2) "crashed" else "stopped"
            }
            if (!cfg.rejoinOnCrash || h.missing < 2) continue
            recoverPackage(pkg, p, h, nowMs, cfg, "Tiến trình bị đóng; đang tự mở lại.", forceStop = false)
        }
    }

    fun buildTelemetry(includeDiag: Boolean = true): JSONObject {
        val tasks = try { AndroidInspector.parseTasks(android.tasks()) } catch (_: Exception) { emptyMap() }
        val profJson = JSONObject()
        for ((pkg, p) in profiles) {
            val isRunning = android.running(pkg)
            val item = p.toStateJson().apply {
                put("running", isRunning)
                put("hasAccount", pkg in accounts)
                put("appStatus", if (isRunning) "running" else "stopped")
                if (!has("healthState")) put("healthState", if (isRunning) "running" else "stopped")
                tasks[pkg]?.let { put("taskId", it) }
            }
            profJson.put(ConfigLimits.sha256Prefix16(pkg), item)
        }
        val (model, androidVer) = android.deviceInfo()
        val (buildCode, appVer) = appVersionInfo()
        return JSONObject().apply {
            put("lastSeen", System.currentTimeMillis())
            put("model", model)
            put("android", androidVer)
            put("appVersion", appVer)
            put("buildCode", buildCode)
            put("watch", watch)
            put("rejoinConfig", effectiveConfig().toJson())
            put("profiles", profJson)
            grid?.let { put("grid", it.toJson()) }
            put("freeform", android.freeformSupported())
            put("publicKey", crypto.publicJwk())
            if (includeDiag) {
                android.collectDiagnostics(profiles.keys)?.let { put("windowDiag", it) }
            }
        }
    }

    fun processCommand(firebase: FirebaseRestClient, command: JSONObject) {
        val id = command.optString("id", "")
        if (!ConfigLimits.UUID_REGEX.matches(id)) throw AgentException("Mã lệnh không hợp lệ.")
        val existing = journal[id]
        var result: JSONObject
        if (existing != null) {
            result = if (existing.optString("status") == "running") {
                JSONObject(existing.toString()).apply {
                    put("status", "failed")
                    put("message", "Agent đã dừng giữa lệnh; kiểm tra máy trước khi gửi lại.")
                    put("finishedAt", System.currentTimeMillis())
                }
            } else {
                existing
            }
        } else {
            val action = command.optString("action", "")
            val createdAt = command.getLong("createdAt")
            result = JSONObject().apply {
                put("id", id)
                put("action", action)
                put("createdAt", createdAt)
                put("status", "running")
            }
            journal[id] = result
            saveJournal()
            if (command.optLong("expiresAt", 0) < System.currentTimeMillis()) {
                result = JSONObject(result.toString()).apply {
                    put("status", "failed")
                    put("message", "Lệnh đã hết hạn trước khi máy nhận.")
                    put("finishedAt", System.currentTimeMillis())
                }
            } else {
                firebase.device("results/$id", "PUT", result)
                result = try {
                    val msg = execute(command)
                    JSONObject(result.toString()).apply {
                        put("status", "succeeded")
                        put("message", msg.take(3000))
                        put("finishedAt", System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    val msg = (e.message ?: e.javaClass.simpleName).take(3000)
                    JSONObject(result.toString()).apply {
                        put("status", "failed")
                        put("message", msg)
                        put("finishedAt", System.currentTimeMillis())
                    }
                }
            }
        }
        journal[id] = result
        saveJournal()
        firebase.device("results/$id", "PUT", result)
        firebase.device("inbox/$id", "DELETE")
        val hook = postAckHook
        postAckHook = null
        hook?.invoke()
    }
}
