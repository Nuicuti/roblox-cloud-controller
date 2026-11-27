package com.rejoin.controller

import android.content.Context
import android.os.Build
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

data class RectBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun centerInside(outer: RectBounds): Boolean {
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        return cx in outer.left..outer.right && cy in outer.top..outer.bottom
    }
}

data class WindowChecks(
    val taskList: Boolean?,
    val windowManager: Boolean?,
    val surfaces: Boolean?,
    val screenTree: Boolean?,
) {
    fun evaluate(): Pair<Boolean, Boolean> {
        val list = listOf(taskList, windowManager, surfaces, screenTree)
        if (list.any { it == true }) return false to true
        if (list.any { it == null }) return false to false
        return true to true
    }
}

data class UdpPeer(val addrHex: String, val port: Int)

object AndroidInspector {
    private val HIST_RE = Regex("""\bHist\s+#\d+:\s+ActivityRecord\{""")
    private val TASK_PKG_RE = Regex("""\bu0\s+((?:free\.noka[A-Za-z0-9_.]*|com\.roblox\.client(?:\.[A-Za-z0-9_]+)*))/[^\s}]+\s+t(\d+)\b""")
    private val TASK_SPLIT_RE = Regex("""(?m)(?=^[ \t]*\*[ \t]*(?:Task|TaskRecord)\{)""")
    private val BOUNDS_RECT_RE = Regex("""(?:mBounds|bounds)=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)""")
    private val BOUNDS_BRACKET_RE = Regex("""(?:mBounds|bounds)=\[(\d+),\s*(\d+)\]\[(\d+),\s*(\d+)\]""")

    fun parseTasks(text: String?): Map<String, Int> {
        if (text.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, Int>()
        for (line in text.lines()) {
            if (!HIST_RE.containsMatchIn(line)) continue
            val m = TASK_PKG_RE.find(line) ?: continue
            result.putIfAbsent(m.groupValues[1], m.groupValues[2].toInt())
        }
        return result
    }

    fun taskBounds(text: String?, taskId: Int): Pair<RectBounds?, Boolean> {
        if (text.isNullOrEmpty()) return null to false
        val idPattern = Regex("""#$taskId\b""")
        val modePattern = Regex("""(?:windowingMode|mode)=5\b""")
        for (chunk in TASK_SPLIT_RE.split(text)) {
            val first = chunk.lineSequence().firstOrNull() ?: ""
            if (!idPattern.containsMatchIn(first)) continue
            val header = chunk.substringBefore("Hist #")
            val m = BOUNDS_RECT_RE.find(header) ?: BOUNDS_BRACKET_RE.find(header)
            val rect = m?.let {
                RectBounds(
                    it.groupValues[1].toInt(),
                    it.groupValues[2].toInt(),
                    it.groupValues[3].toInt(),
                    it.groupValues[4].toInt(),
                )
            }
            val freeform = header.contains("freeform") || modePattern.containsMatchIn(header)
            return rect to freeform
        }
        return null to false
    }

    fun boundsMatch(actual: RectBounds?, target: RectBounds?, tolerance: Int = 6): Boolean {
        if (actual == null || target == null) return false
        return abs(actual.left - target.left) <= tolerance &&
            abs(actual.top - target.top) <= tolerance &&
            abs(actual.right - target.right) <= tolerance &&
            abs(actual.bottom - target.bottom) <= tolerance
    }

    fun gridBounds(width: Int, height: Int, rows: Int, cols: Int, gap: Int, top: Int, bottom: Int): List<RectBounds> {
        if (rows !in 1..6 || cols !in 1..6 || gap !in 0..40 || top !in 0..250 || bottom !in 0..250) {
            throw AgentException("Thông số lưới không hợp lệ.")
        }
        val availW = width - gap * (cols + 1)
        val availH = height - top - bottom - gap * (rows + 1)
        if (availW / cols < 160 || availH / rows < 120) {
            throw AgentException("Ô lưới quá nhỏ. Giảm hàng/cột hoặc khoảng cách.")
        }
        val cells = mutableListOf<RectBounds>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = gap + c * gap + availW * c / cols
                val right = gap + c * gap + availW * (c + 1) / cols
                val upper = top + gap + r * gap + availH * r / rows
                val lower = top + gap + r * gap + availH * (r + 1) / rows
                cells.add(RectBounds(left, upper, right, lower))
            }
        }
        return cells
    }

    fun checkTaskList(tasksText: String?, pkg: String): Boolean? {
        if (tasksText.isNullOrEmpty() || !tasksText.contains("ACTIVITY MANAGER")) return null
        return pkg in parseTasks(tasksText)
    }

    fun checkWindowManager(windowsText: String?, pkg: String): Boolean? {
        if (windowsText.isNullOrEmpty() || (!windowsText.contains("WINDOW MANAGER") && !windowsText.contains("Window{"))) {
            return null
        }
        val blockSplit = Regex("""(?m)(?=^[ \t]*Window\s+#\d+\s+Window\{)""")
        val pkgRe = Regex("""\bu0\s+${Regex.escape(pkg)}(?:/|\}|\s)""")
        for (block in blockSplit.split(windowsText)) {
            if (!pkgRe.containsMatchIn(block)) continue
            if (block.contains("mHasSurface=false") && block.contains("mViewVisibility=0x8")) continue
            return true
        }
        return false
    }

    fun checkSurfaces(surfaceText: String?, pkg: String): Boolean? {
        val lines = surfaceText?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (lines.size < 2) return null
        val pkgRe = Regex("""(?:^|[\s\[/])${Regex.escape(pkg)}(?:[/\]#:\s]|$)""")
        return lines.any { pkgRe.containsMatchIn(it) }
    }

    fun checkScreenTree(uiXml: String?, pkg: String): Boolean? {
        if (uiXml.isNullOrEmpty() || !uiXml.contains("<hierarchy")) return null
        return uiXml.contains("""package="$pkg"""")
    }

    fun inspectActivityAuthOrAnr(tasksText: String?, windowsText: String?, pkg: String): String {
        if (!windowsText.isNullOrEmpty()) {
            val anr = Regex("""Application Not Responding:\s*${Regex.escape(pkg)}\b""", RegexOption.IGNORE_CASE)
            if (anr.containsMatchIn(windowsText)) return "disconnected"
        }
        if (!tasksText.isNullOrEmpty()) {
            val actRe = Regex("""\bHist\s+#0:\s+ActivityRecord\{[^\n]*\bu0\s+${Regex.escape(pkg)}/([^\s}]+)""")
            val act = actRe.find(tasksText)?.groupValues?.get(1)?.lowercase() ?: ""
            if (listOf("arkose", "funcaptcha", "captcha", "twostep", "challenge").any { it in act }) {
                return "auth_required"
            }
        }
        return "unknown"
    }

    fun inspectUiTexts(pkgTexts: List<String>, systemTexts: List<String>): String {
        val sysJoined = systemTexts.joinToString(" | ").lowercase()
        if (listOf("isn't responding", "không phản hồi").any { it in sysJoined } &&
            listOf("close app", "đóng ứng dụng").any { it in sysJoined }
        ) {
            return "disconnected"
        }
        if (pkgTexts.isEmpty()) return "unknown"
        val joined = pkgTexts.joinToString(" | ").lowercase()

        val authMarkers = listOf(
            "arkose", "captcha", "verify you are human", "two-step verification",
            "2-step verification", "security notification", "xác minh 2 bước",
            "xác minh bạn là con người",
        )
        if (authMarkers.any { it in joined }) return "auth_required"
        val hasLogin = listOf("log in", "đăng nhập").any { it in joined }
        val hasLoginForm = listOf("sign up", "đăng ký", "forgot password", "quên mật khẩu", "username/email/phone", "mật khẩu").any { it in joined }
        if (hasLogin && hasLoginForm) return "auth_required"

        val disconnectMarkers = listOf(
            "disconnected", "lost connection", "connection failed",
            "same account launched", "you were kicked", "error code:", "error code ",
            "đã mất kết nối", "mất kết nối", "mã lỗi:", "mã lỗi ",
        )
        if (disconnectMarkers.any { it in joined }) return "disconnected"
        if (("reconnect" in joined && "leave" in joined) || ("kết nối lại" in joined && "rời" in joined)) {
            return "disconnected"
        }

        val teleportMarkers = listOf(
            "teleporting", "joining server", "waiting for an available server",
            "đang dịch chuyển", "đang tham gia",
        )
        if (teleportMarkers.any { it in joined }) return "teleporting"

        return "active"
    }

    fun inspectUiXml(uiXml: String?, pkg: String, bounds: RectBounds?): String {
        if (uiXml.isNullOrEmpty() || !uiXml.contains("<hierarchy")) return "unknown"
        val pkgTexts = mutableListOf<String>()
        val systemTexts = mutableListOf<String>()
        val boundsRe = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        try {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(uiXml)))
            val nodes = doc.getElementsByTagName("node")
            for (i in 0 until nodes.length) {
                val elem = nodes.item(i) as? Element ?: continue
                val nodePkg = elem.getAttribute("package")
                val textVal = elem.getAttribute("text").trim()
                val descVal = elem.getAttribute("content-desc").trim()
                val combined = listOf(textVal, descVal).filter { it.isNotEmpty() }.joinToString(" ")
                if (combined.isNotEmpty()) {
                    if (nodePkg == pkg) {
                        pkgTexts.add(combined)
                    } else if (nodePkg == "android" && bounds != null) {
                        val bStr = elem.getAttribute("bounds")
                        val bm = boundsRe.matchEntire(bStr)
                        if (bm != null) {
                            val r = RectBounds(
                                bm.groupValues[1].toInt(),
                                bm.groupValues[2].toInt(),
                                bm.groupValues[3].toInt(),
                                bm.groupValues[4].toInt(),
                            )
                            if (r.centerInside(bounds)) systemTexts.add(combined)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return "unknown"
        }
        return inspectUiTexts(pkgTexts, systemTexts)
    }

    fun parseProcNetUdp(text: String?, uid: Int?): Set<UdpPeer>? {
        if (text.isNullOrEmpty()) return null
        val lines = text.lines()
        if (lines.size < 2) return emptySet()
        val uidTokens = uid?.let { setOf(it.toString(), "%08x".format(it), Integer.toHexString(it)) }
        val peers = mutableSetOf<UdpPeer>()
        val ws = Regex("""\s+""")
        for (line in lines.drop(1)) {
            val cols = line.trim().split(ws)
            if (cols.size < 8) continue
            val sockUid = cols[7].lowercase()
            if (uidTokens != null && sockUid !in uidTokens) continue
            val rem = cols[2]
            val colon = rem.indexOf(':')
            if (colon <= 0) continue
            val addrHex = rem.substring(0, colon)
            val portHex = rem.substring(colon + 1)
            val port = portHex.toIntOrNull(16) ?: continue
            if (port == 0 || port == 53) continue
            if (addrHex.length == 8 && (addrHex == "00000000" || addrHex == "0100007F")) continue
            if (addrHex.length == 32 && (addrHex == "0".repeat(32) || addrHex == "0".repeat(30) + "0100" || addrHex.endsWith("0100007F"))) {
                continue
            }
            peers.add(UdpPeer(addrHex.uppercase(), port))
        }
        return peers
    }
}

interface AndroidDevice {
    fun packages(): List<String>
    fun running(pkg: String): Boolean
    fun tasks(): String
    fun windows(): String
    fun surfaces(): String
    fun uiTreeXml(): String
    fun accessibilityTexts(): Map<String, List<String>>?
    fun udpPeers(pkg: String): Set<UdpPeer>?
    fun windowChecks(pkg: String, uiXml: String?, tasksText: String?, windowsText: String?, surfaceText: String?): WindowChecks
    fun displaySize(): Pair<Int, Int>
    fun freeformSupported(): Boolean
    fun open(pkg: String, freeform: Boolean = false, placeId: String = "", jobId: String = "")
    fun close(pkg: String)
    fun resize(pkg: String, bounds: RectBounds, launch: Boolean = true, placeId: String = "", jobId: String = ""): Int
    fun setFreeformFlags(enabled: Boolean, previousBackup: Map<String, String>?): Map<String, String>
    fun deviceInfo(): Pair<String, String>
}

class RootAndroidDevice(private val context: Context) : AndroidDevice {
    private val uidCache = mutableMapOf<String, Int>()

    override fun packages(): List<String> {
        val r0 = RootShell.run(listOf("/system/bin/pm", "list", "packages", "--user", "0"), check = false, timeoutSec = 10)
        val out = if (r0.exitCode == 0 && r0.output.isNotEmpty()) r0.output
        else RootShell.run(listOf("/system/bin/pm", "list", "packages"), check = false, timeoutSec = 10).output
        return out.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { ConfigLimits.isAllowedPackage(it) }
            .distinct()
            .sorted()
    }

    override fun running(pkg: String): Boolean {
        val res = RootShell.run(listOf("/system/bin/pidof", pkg), check = false, timeoutSec = 5)
        if (res.exitCode !in listOf(0, 1)) throw AgentException("Không kiểm tra được tiến trình Android.")
        return res.exitCode == 0 && Regex("""[\d\s]+""").matches(res.output.trim())
    }

    override fun tasks(): String =
        RootShell.run(listOf("/system/bin/dumpsys", "activity", "activities"), check = false, timeoutSec = 10).output

    override fun windows(): String =
        RootShell.run(listOf("/system/bin/dumpsys", "window", "windows"), check = false, timeoutSec = 10).output

    override fun surfaces(): String {
        val listRes = RootShell.run(listOf("/system/bin/dumpsys", "SurfaceFlinger", "--list"), check = false, timeoutSec = 6)
        if (listRes.exitCode == 0 && listRes.output.lines().count { it.isNotBlank() } >= 2) {
            return listRes.output
        }
        return RootShell.run(listOf("/system/bin/dumpsys", "SurfaceFlinger"), check = false, timeoutSec = 8).output
    }

    override fun uiTreeXml(): String {
        val dumpPath = "/data/local/tmp/rejoin_ui_dump.xml"
        val dumpRes = RootShell.run(listOf("/system/bin/uiautomator", "dump", "--compressed", dumpPath), check = false, timeoutSec = 4)
        if (dumpRes.timedOut || dumpRes.exitCode != 0 || dumpRes.output.contains("ERROR:")) return ""
        val readRes = RootShell.run(listOf("/system/bin/cat", dumpPath), check = false, timeoutSec = 4)
        return if (readRes.output.contains("<hierarchy")) readRes.output else ""
    }

    override fun accessibilityTexts(): Map<String, List<String>>? = RejoinAccessibilityService.snapshot()

    private fun packageUid(pkg: String): Int? {
        uidCache[pkg]?.let { return it }
        try {
            val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
            uidCache[pkg] = uid
            return uid
        } catch (_: Exception) {
        }
        val res = RootShell.run(listOf("/system/bin/stat", "-c", "%u", "/data/data/$pkg"), check = false, timeoutSec = 5)
        val parsed = res.output.trim().toIntOrNull()
        if (parsed != null) uidCache[pkg] = parsed
        return parsed
    }

    override fun udpPeers(pkg: String): Set<UdpPeer>? {
        val uid = packageUid(pkg) ?: return null
        val peers = mutableSetOf<UdpPeer>()
        var readable = false
        for (path in listOf("/proc/net/udp", "/proc/net/udp6")) {
            val content = try {
                File(path).readText()
            } catch (_: Exception) {
                RootShell.run(listOf("/system/bin/cat", path), check = false, timeoutSec = 5).output
            }
            val parsed = AndroidInspector.parseProcNetUdp(content, uid)
            if (parsed != null) {
                readable = true
                peers.addAll(parsed)
            }
        }
        return if (readable) peers else null
    }

    override fun windowChecks(pkg: String, uiXml: String?, tasksText: String?, windowsText: String?, surfaceText: String?): WindowChecks {
        val tText = tasksText ?: tasks()
        val wText = windowsText ?: windows()
        val sText = surfaceText ?: surfaces()
        val aMap = accessibilityTexts()
        val treeCheck = if (aMap != null) {
            aMap.containsKey(pkg)
        } else {
            val xml = uiXml ?: uiTreeXml()
            AndroidInspector.checkScreenTree(xml, pkg)
        }
        return WindowChecks(
            taskList = AndroidInspector.checkTaskList(tText, pkg),
            windowManager = AndroidInspector.checkWindowManager(wText, pkg),
            surfaces = AndroidInspector.checkSurfaces(sText, pkg),
            screenTree = treeCheck,
        )
    }

    override fun displaySize(): Pair<Int, Int> {
        val out = RootShell.run(listOf("/system/bin/wm", "size")).output
        val matches = Regex("""(?:Physical|Override) size:\s*(\d+)x(\d+)""").findAll(out).toList()
        val last = matches.lastOrNull() ?: throw AgentException("Không đọc được kích thước màn hình.")
        val w = last.groupValues[1].toInt()
        val h = last.groupValues[2].toInt()
        val display = RootShell.run(listOf("/system/bin/dumpsys", "display"), check = false).output
        val real = Regex("""mDisplayInfo=DisplayInfo\{[^\n]*?\breal\s+(\d+)\s*x\s*(\d+)""").find(display)
        return if (real != null) real.groupValues[1].toInt() to real.groupValues[2].toInt() else w to h
    }

    override fun freeformSupported(): Boolean {
        return try {
            val features = RootShell.run(listOf("/system/bin/pm", "list", "features"), check = false, timeoutSec = 8).output
            val setting = RootShell.run(listOf("/system/bin/settings", "get", "global", "enable_freeform_support"), check = false, timeoutSec = 5).output
            val help = RootShell.run(listOf("/system/bin/am", "help"), check = false, timeoutSec = 5).output
            (features.contains("android.software.freeform_window_management") || setting.trim() == "1") &&
                help.contains("--windowingMode") && help.contains("resize <TASK_ID>")
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveLauncher(pkg: String): String {
        val out = RootShell.run(
            listOf(
                "/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", "0",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", pkg,
            ),
        ).output
        val lineRe = Regex("""${Regex.escape(pkg)}/[A-Za-z0-9_.${'$'}]+""")
        return out.lines().reversed().map { it.trim() }.firstOrNull { lineRe.matches(it) }
            ?: throw AgentException("Không tìm thấy màn hình mở ứng dụng: $pkg")
    }

    override fun open(pkg: String, freeform: Boolean, placeId: String, jobId: String) {
        val args = mutableListOf("/system/bin/am", "start", "-W", "--user", "0")
        if (freeform) args.addAll(listOf("--windowingMode", "5"))
        val uri = ConfigLimits.buildRobloxUri(placeId, jobId)
        if (uri.isNotEmpty()) {
            args.addAll(listOf("-a", "android.intent.action.VIEW", "-d", uri, "-p", pkg))
        } else {
            val comp = resolveLauncher(pkg)
            args.addAll(listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", comp))
        }
        RootShell.run(args, timeoutSec = 20)
    }

    override fun close(pkg: String) {
        RootShell.run(listOf("/system/bin/am", "force-stop", "--user", "0", pkg), check = false, timeoutSec = 10)
    }

    override fun resize(pkg: String, bounds: RectBounds, launch: Boolean, placeId: String, jobId: String): Int {
        if (launch) open(pkg, freeform = true, placeId = placeId, jobId = jobId)
        val deadline = System.currentTimeMillis() + 12_000L
        var task: Int? = null
        var lastTasks = ""
        while (System.currentTimeMillis() < deadline) {
            lastTasks = tasks()
            task = AndroidInspector.parseTasks(lastTasks)[pkg]
            if (task != null) break
            Thread.sleep(500)
        }
        val taskId = task ?: throw AgentException("Chưa tìm thấy cửa sổ đang hoạt động của $pkg")
        val (actual, isFreeform) = AndroidInspector.taskBounds(lastTasks, taskId)
        if (!launch && isFreeform && AndroidInspector.boundsMatch(actual, bounds)) {
            return taskId
        }
        RootShell.run(
            listOf("/system/bin/am", "task", "resize", taskId.toString(), bounds.left.toString(), bounds.top.toString(), bounds.right.toString(), bounds.bottom.toString()),
            check = false,
        )
        Thread.sleep(350)
        val (afterRect, afterFreeform) = AndroidInspector.taskBounds(tasks(), taskId)
        if (!afterFreeform || afterRect == null) {
            RootShell.run(
                listOf("/system/bin/am", "task", "resize", taskId.toString(), "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"),
                check = false,
            )
        }
        return taskId
    }

    override fun setFreeformFlags(enabled: Boolean, previousBackup: Map<String, String>?): Map<String, String> {
        val names = listOf("enable_freeform_support", "force_resizable_activities")
        if (enabled) {
            val backup = previousBackup ?: names.associateWith {
                RootShell.run(listOf("/system/bin/settings", "get", "global", it), check = false).output
            }
            for (name in names) {
                RootShell.run(listOf("/system/bin/settings", "put", "global", name, "1"))
            }
            return backup
        }
        val restore = previousBackup ?: throw AgentException("Chưa có cấu hình cửa sổ cũ để khôi phục.")
        for ((name, value) in restore) {
            if (value == "null" || value.isEmpty()) {
                RootShell.run(listOf("/system/bin/settings", "delete", "global", name), check = false)
            } else {
                RootShell.run(listOf("/system/bin/settings", "put", "global", name, value), check = false)
            }
        }
        return emptyMap()
    }

    override fun deviceInfo(): Pair<String, String> {
        val model = RootShell.run(listOf("/system/bin/getprop", "ro.product.model"), check = false, timeoutSec = 5).output
        return (model.ifEmpty { "Redfinger Device" }) to Build.VERSION.RELEASE
    }
}
