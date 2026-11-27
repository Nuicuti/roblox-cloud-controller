package com.rejoin.controller

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import org.json.JSONObject
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

data class TaskPlacement(
    val pkg: String,
    val taskId: Int,
    val stackId: Int?,
    val bounds: RectBounds?,
    val freeform: Boolean,
)

data class WindowFrameInfo(
    val frame: RectBounds?,
    val containing: RectBounds?,
    val shownPos: String?,
)

object AndroidInspector {
    private val HIST_RE = Regex("""\bHist\s+#\d+:\s+ActivityRecord\{""")
    private val TASK_PKG_RE = Regex("""\bu0\s+((?:[A-Za-z][A-Za-z0-9_]*\.)*[A-Za-z0-9_]*(?:noka|roblox|arceus)[A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*)/[^\s}]+\s+t(\d+)\b""", RegexOption.IGNORE_CASE)
    private val TASK_SPLIT_RE = Regex("""(?m)(?=^[ \t]*\*[ \t]*(?:Task|TaskRecord)\{)""")
    private val STACK_SPLIT_RE = Regex("""(?m)(?=^[ \t]*Stack\s+#\d+:)""")
    private val STACK_ID_RE = Regex("""\bStackId=(\d+)\b""")
    private val STACK_HEADER_ID_RE = Regex("""Stack\s+#(\d+):""")
    private val BOUNDS_RECT_RE = Regex("""(?:mBounds|bounds)=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)""")
    private val BOUNDS_BRACKET_RE = Regex("""(?:mBounds|bounds)=\[(-?\d+),\s*(-?\d+)\]\[(-?\d+),\s*(-?\d+)\]""")
    private val STACK_LIST_HEADER_RE = Regex("""^\s*Stack\s+id=(\d+)\b""")
    private val STACK_LIST_TASK_RE = Regex("""^\s*taskId=(\d+):\s*([A-Za-z0-9_.]+)/""")
    private val BRACKET_PAIR_RE = Regex("""\[(-?\d+),\s*(-?\d+)\]\[(-?\d+),\s*(-?\d+)\]""")

    private fun parseBoundsFromText(text: String): RectBounds? {
        val rm = BOUNDS_RECT_RE.find(text)
        if (rm != null) {
            return RectBounds(
                rm.groupValues[1].toInt(),
                rm.groupValues[2].toInt(),
                rm.groupValues[3].toInt(),
                rm.groupValues[4].toInt(),
            )
        }
        val bm = BOUNDS_BRACKET_RE.find(text)
        if (bm != null) {
            return RectBounds(
                bm.groupValues[1].toInt(),
                bm.groupValues[2].toInt(),
                bm.groupValues[3].toInt(),
                bm.groupValues[4].toInt(),
            )
        }
        return null
    }

    fun parseDisplaySize(wmSizeOut: String?, dumpsysDisplayOut: String?, dumpsysWindowDisplaysOut: String?): Pair<Int, Int>? {
        if (!dumpsysWindowDisplaysOut.isNullOrEmpty()) {
            val cur = Regex("""\bcur=(\d+)x(\d+)\b""").find(dumpsysWindowDisplaysOut)
            if (cur != null) {
                val w = cur.groupValues[1].toInt()
                val h = cur.groupValues[2].toInt()
                if (w > 0 && h > 0) return w to h
            }
        }
        if (!dumpsysDisplayOut.isNullOrEmpty()) {
            val real = Regex("""mDisplayInfo=DisplayInfo\{[^\n]*?\breal\s+(\d+)\s*x\s*(\d+)""").find(dumpsysDisplayOut)
            if (real != null) {
                val w = real.groupValues[1].toInt()
                val h = real.groupValues[2].toInt()
                if (w > 0 && h > 0) return w to h
            }
        }
        if (!wmSizeOut.isNullOrEmpty()) {
            val matches = Regex("""(?:Physical|Override) size:\s*(\d+)x(\d+)""").findAll(wmSizeOut).toList()
            val last = matches.lastOrNull()
            if (last != null) {
                val w = last.groupValues[1].toInt()
                val h = last.groupValues[2].toInt()
                if (w > 0 && h > 0) return w to h
            }
        }
        return null
    }

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

    fun parseStackList(stackListText: String?): Map<String, TaskPlacement> {
        if (stackListText.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, TaskPlacement>()
        var currentStackId: Int? = null
        var currentStackBounds: RectBounds? = null
        var currentStackFreeform = false

        for (line in stackListText.lines()) {
            val sh = STACK_LIST_HEADER_RE.find(line)
            if (sh != null) {
                currentStackId = sh.groupValues[1].toIntOrNull()
                currentStackBounds = parseBoundsFromText(line) ?: BRACKET_PAIR_RE.find(line)?.let {
                    RectBounds(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt())
                }
                currentStackFreeform = line.contains("freeform", ignoreCase = true) ||
                    line.contains("windowingMode=5") ||
                    line.contains("mode=5")
                continue
            }
            if (currentStackId != null && (line.contains("configuration=") || line.contains("mBounds="))) {
                if (line.contains("freeform", ignoreCase = true) || line.contains("windowingMode=5") || line.contains("mode=5")) {
                    currentStackFreeform = true
                }
                if (currentStackBounds == null) {
                    currentStackBounds = parseBoundsFromText(line)
                }
                continue
            }
            val tm = STACK_LIST_TASK_RE.find(line)
            if (tm != null) {
                val taskId = tm.groupValues[1].toIntOrNull() ?: continue
                val pkg = tm.groupValues[2]
                val tBounds = parseBoundsFromText(line) ?: BRACKET_PAIR_RE.find(line)?.let {
                    RectBounds(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt())
                } ?: currentStackBounds
                val isFreeform = currentStackFreeform ||
                    line.contains("freeform", ignoreCase = true) ||
                    line.contains("windowingMode=5") ||
                    line.contains("mode=5")
                result.putIfAbsent(
                    pkg,
                    TaskPlacement(
                        pkg = pkg,
                        taskId = taskId,
                        stackId = currentStackId,
                        bounds = tBounds,
                        freeform = isFreeform,
                    ),
                )
            }
        }
        return result
    }

    fun taskBounds(text: String?, taskId: Int): Pair<RectBounds?, Boolean> {
        if (text.isNullOrEmpty()) return null to false
        val idPattern = Regex("""#$taskId\b""")
        val modePattern = Regex("""(?:windowingMode|mode)=(?:5\b|freeform\b)""", RegexOption.IGNORE_CASE)

        for (stackBlock in STACK_SPLIT_RE.split(text)) {
            val stackHeader = stackBlock.substringBefore("* Task")
            val stackFreeform = modePattern.containsMatchIn(stackHeader)
            val stackRect = parseBoundsFromText(stackHeader)

            for (chunk in TASK_SPLIT_RE.split(stackBlock)) {
                val first = chunk.lineSequence().firstOrNull() ?: ""
                if (!idPattern.containsMatchIn(first)) continue
                val header = chunk.substringBefore("Hist #")
                val rect = parseBoundsFromText(header) ?: stackRect
                val freeform = stackFreeform || header.contains("freeform", ignoreCase = true) || modePattern.containsMatchIn(header)
                return rect to freeform
            }
        }
        return null to false
    }

    fun taskStackId(text: String?, taskId: Int): Int? {
        if (text.isNullOrEmpty()) return null
        val idPattern = Regex("""#$taskId\b""")
        for (stackBlock in STACK_SPLIT_RE.split(text)) {
            val stackNum = STACK_HEADER_ID_RE.find(stackBlock)?.groupValues?.get(1)?.toIntOrNull()
            for (chunk in TASK_SPLIT_RE.split(stackBlock)) {
                val first = chunk.lineSequence().firstOrNull() ?: ""
                if (!idPattern.containsMatchIn(first)) continue
                val header = chunk.substringBefore("Hist #")
                return STACK_ID_RE.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: stackNum
            }
        }
        return null
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

    fun suggestGridShape(count: Int, width: Int, height: Int): Pair<Int, Int> {
        val n = maxOf(1, count)
        val landscape = width >= height
        return when {
            n <= 1 -> 1 to 1
            n == 2 -> if (landscape) 1 to 2 else 2 to 1
            n == 3 -> if (landscape) (if (width >= 960) 1 to 3 else 2 to 2) else 3 to 1
            n == 4 -> 2 to 2
            n <= 6 -> if (landscape) 2 to 3 else 3 to 2
            n <= 9 -> 3 to 3
            else -> {
                val big = minOf(6, kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt())
                val small = minOf(6, (n + big - 1) / big)
                if (landscape) small to big else big to small
            }
        }
    }

    fun parseWindowFrame(windowsText: String?, pkg: String): WindowFrameInfo? {
        if (windowsText.isNullOrEmpty() || !windowsText.contains("Window{")) {
            return null
        }
        val blockSplit = Regex("""(?m)(?=^[ \t]*(?:Window\s+#\d+\s+)?Window\{)""")
        val pkgRe = Regex("""\b${Regex.escape(pkg)}(?:/|\}|\s)""")
        val primaryBracketRe = Regex("""\b(?:mFrame|frame|mShownFrame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
        val primaryRectRe = Regex("""\b(?:mFrame|frame|mShownFrame)=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)""")
        val secondaryBracketRe = Regex("""\b(?:mCompatFrame|mVisibleFrame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
        val surfRectRe = Regex("""\brect=\((-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\)""")
        val containingRe = Regex("""\bcontaining=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
        val shownPosRe = Regex("""\b(?:mShownPosition|pos)=\((-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\)""")

        var best: WindowFrameInfo? = null
        var bestScore = Long.MIN_VALUE
        for (block in blockSplit.split(windowsText)) {
            if (!pkgRe.containsMatchIn(block)) continue
            if (block.contains("mHasSurface=false") && block.contains("mViewVisibility=0x8")) continue

            val fm = primaryBracketRe.find(block) ?: primaryRectRe.find(block) ?: secondaryBracketRe.find(block)
            val frame = fm?.let {
                RectBounds(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt())
            } ?: surfRectRe.find(block)?.let {
                val x = it.groupValues[1].toInt()
                val y = it.groupValues[2].toInt()
                val w = it.groupValues[3].toInt()
                val h = it.groupValues[4].toInt()
                if (w > 0 && h > 0) RectBounds(x, y, x + w, y + h) else null
            }
            val cm = containingRe.find(block)
            val containing = cm?.let {
                RectBounds(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt())
            }
            val sp = shownPosRe.find(block)?.let { "(${it.groupValues[1]},${it.groupValues[2]})" }
            val refRect = frame ?: containing
            if (refRect == null && sp == null) continue

            var score = 0L
            if (block.contains("BASE_APPLICATION")) score += 1_000_000L
            if (block.contains("mHasSurface=true")) score += 200_000L
            if (refRect != null) {
                val rw = (refRect.right - refRect.left).coerceAtLeast(0)
                val rh = (refRect.bottom - refRect.top).coerceAtLeast(0)
                if (rw >= 100 && rh >= 80) score += 500_000L
                score += (rw.toLong() * rh.toLong()) / 10L
            }
            if (score > bestScore) {
                bestScore = score
                best = WindowFrameInfo(frame = frame, containing = containing, shownPos = sp)
            }
        }
        return best
    }

    fun parseSurfaceLayerInfo(sfText: String?, pkg: String): String? {
        if (sfText.isNullOrEmpty()) return null
        val lines = sfText.lines()
        val pkgEsc = Regex.escape(pkg)
        val headerRe = Regex("""(?:Layer|name=).*$pkgEsc""", RegexOption.IGNORE_CASE)
        val hwcRowRe = Regex("""$pkgEsc[^\n]*\|\s*[\d.\s-]+""", RegexOption.IGNORE_CASE)
        for (i in lines.indices) {
            val line = lines[i]
            if (headerRe.containsMatchIn(line)) {
                val snippet = lines.drop(i + 1).take(14)
                    .map { it.trim() }
                    .filter {
                        it.contains("bounds", ignoreCase = true) ||
                            it.contains("pos=", ignoreCase = true) ||
                            it.contains("position", ignoreCase = true) ||
                            it.contains("Region", ignoreCase = true) ||
                            it.contains("tx=", ignoreCase = true) ||
                            it.contains("frame", ignoreCase = true)
                    }
                    .take(3)
                    .joinToString("; ")
                if (snippet.isNotEmpty()) return snippet.take(140)
            }
            if (hwcRowRe.containsMatchIn(line)) {
                return line.trim().take(140)
            }
        }
        return null
    }

    fun summarizeWindowLines(windowsText: String?, pkg: String): String {
        if (windowsText.isNullOrEmpty()) return "win-empty"
        val blockSplit = Regex("""(?m)(?=^[ \t]*(?:Window\s+#\d+\s+)?Window\{)""")
        val pkgRe = Regex("""\b${Regex.escape(pkg)}(?:/|\}|\s)""")
        val candidates = blockSplit.split(windowsText).filter { pkgRe.containsMatchIn(it) }
        val block = candidates.maxByOrNull { b ->
            (if (b.contains("BASE_APPLICATION")) 100 else 0) +
                (if (b.contains("mHasSurface=true")) 20 else 0) +
                (if (b.contains("containing=")) 10 else 0)
        } ?: return "no-win-for-$pkg(hasWin=${windowsText.contains("Window{")},len=${windowsText.length})"
        val lines = block.lines().map { it.trim() }.filter {
            it.contains("Frame", ignoreCase = true) ||
                it.contains("containing=", ignoreCase = true) ||
                it.contains("mAttrs=", ignoreCase = true) ||
                it.contains("pos=", ignoreCase = true)
        }
        return lines.take(4).joinToString(" | ").ifEmpty { block.lines().take(3).joinToString(" ").trim() }.take(180)
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

    private val APP_CLONER_WIN_KEYS = setOf(
        "app_cloner_current_window_left",
        "app_cloner_current_window_top",
        "app_cloner_current_window_right",
        "app_cloner_current_window_bottom",
    )

    fun parseAppClonerWindowBounds(xml: String?): RectBounds? {
        if (xml.isNullOrEmpty()) return null
        fun readInt(name: String): Int? =
            Regex("""<int\s+name="${Regex.escape(name)}"\s+value="(-?\d+)"\s*/>""").find(xml)?.groupValues?.get(1)?.toIntOrNull()
        val l = readInt("app_cloner_current_window_left") ?: return null
        val t = readInt("app_cloner_current_window_top") ?: return null
        val r = readInt("app_cloner_current_window_right") ?: return null
        val b = readInt("app_cloner_current_window_bottom") ?: return null
        return RectBounds(l, t, r, b)
    }

    fun upsertAppClonerWindowXml(existingXml: String?, left: Int, top: Int, right: Int, bottom: Int): String {
        val keptLines = (existingXml ?: "")
            .lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("<?xml") &&
                    !line.startsWith("<map") &&
                    !line.startsWith("</map") &&
                    APP_CLONER_WIN_KEYS.none { key -> line.contains("""name="$key"""") }
            }
        val winEntries = listOf(
            """<int name="app_cloner_current_window_left" value="$left" />""",
            """<int name="app_cloner_current_window_top" value="$top" />""",
            """<int name="app_cloner_current_window_right" value="$right" />""",
            """<int name="app_cloner_current_window_bottom" value="$bottom" />""",
        )
        return buildString {
            appendLine("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
            appendLine("<map>")
            for (line in keptLines) {
                append("    ").appendLine(line)
            }
            for (entry in winEntries) {
                append("    ").appendLine(entry)
            }
            append("</map>")
        }
    }

    fun parseProcMeminfo(
        meminfoText: String?,
        totalHintMb: Int = 0,
        availHintMb: Int = 0,
        lowMemory: Boolean = false,
        cpuCores: Int = 1,
        loadAvgText: String? = null,
    ): SystemMemInfo? {
        val kb = mutableMapOf<String, Long>()
        if (!meminfoText.isNullOrEmpty()) {
            val re = Regex("""^([A-Za-z0-9_()]+):\s+(\d+)\s*kB""", RegexOption.MULTILINE)
            for (m in re.findAll(meminfoText)) {
                val value = m.groupValues[2].toLongOrNull() ?: continue
                kb[m.groupValues[1]] = value
            }
        }
        val totalMb = kb["MemTotal"]?.let { (it / 1024L).toInt() }?.takeIf { it > 0 } ?: totalHintMb
        if (totalMb <= 0) return null
        val availMb = kb["MemAvailable"]?.let { (it / 1024L).toInt() }
            ?: run {
                val free = kb["MemFree"]
                val cached = kb["Cached"] ?: 0L
                val buffers = kb["Buffers"] ?: 0L
                if (free != null) ((free + cached + buffers) / 1024L).toInt() else null
            }
            ?: availHintMb
        val usedMb = (totalMb - availMb).coerceAtLeast(0)
        val swapTotalMb = ((kb["SwapTotal"] ?: 0L) / 1024L).toInt()
        val swapFreeMb = ((kb["SwapFree"] ?: 0L) / 1024L).toInt()
        val load1m = loadAvgText?.trim()?.split(Regex("""\s+"""))?.firstOrNull()?.toDoubleOrNull() ?: 0.0
        return SystemMemInfo(
            totalMb = totalMb,
            availMb = availMb.coerceIn(0, totalMb),
            usedMb = usedMb.coerceIn(0, totalMb),
            swapTotalMb = swapTotalMb,
            swapFreeMb = swapFreeMb.coerceIn(0, swapTotalMb),
            cpuCores = cpuCores.coerceAtLeast(1),
            load1m = load1m,
            lowMemory = lowMemory,
        )
    }

    fun parsePsRssMb(psText: String?, pkgs: Collection<String>): Map<String, Int> {
        if (psText.isNullOrEmpty() || pkgs.isEmpty()) return emptyMap()
        val targetSet = pkgs.toSet()
        val totalsKb = mutableMapOf<String, Long>()
        val ws = Regex("""\s+""")
        for (rawLine in psText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val cols = line.split(ws)
            if (cols.size < 2 || cols[0].equals("USER", true) || cols[0].equals("RSS", true)) continue
            val pkg = cols.last().substringBefore(':')
            if (pkg !in targetSet) continue
            val rssKb = when {
                cols.size == 2 -> cols[0].toLongOrNull()
                cols.size >= 8 -> cols[4].toLongOrNull()
                else -> null
            } ?: continue
            if (rssKb > 0) totalsKb[pkg] = (totalsKb[pkg] ?: 0L) + rssKb
        }
        return totalsKb.mapValues { (_, kb) -> (kb / 1024L).toInt() }
    }
}

data class SystemMemInfo(
    val totalMb: Int,
    val availMb: Int,
    val usedMb: Int,
    val swapTotalMb: Int = 0,
    val swapFreeMb: Int = 0,
    val cpuCores: Int = 1,
    val load1m: Double = 0.0,
    val lowMemory: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("totalMb", totalMb)
        put("availMb", availMb)
        put("usedMb", usedMb)
        put("swapTotalMb", swapTotalMb)
        put("swapFreeMb", swapFreeMb)
        put("cpuCores", cpuCores)
        put("load1m", Math.round(load1m * 100.0) / 100.0)
        put("lowMemory", lowMemory)
    }
}

interface AndroidDevice {
    fun packages(): List<String>
    fun appLabel(pkg: String): String? = null
    fun scanDiagnostics(): String = ""
    fun running(pkg: String): Boolean
    fun tasks(): String
    fun windows(): String
    fun surfaces(): String
    fun uiTreeXml(): String
    fun accessibilityTexts(): Map<String, List<String>>?
    fun isAccessibilityEnabled(): Boolean = false
    fun memoryInfo(): SystemMemInfo? = null
    fun processRssMb(pkgs: Collection<String>): Map<String, Int> = emptyMap()
    fun clearPackageCache(pkg: String) {}
    fun trimMemoryAndClearCache(pkgs: Collection<String>): String = ""
    fun applyClientSettings(pkgs: Collection<String>, fpsCap: Int, lowGraphics: Boolean): String = ""
    fun udpPeers(pkg: String): Set<UdpPeer>?
    fun windowChecks(pkg: String, uiXml: String?, tasksText: String?, windowsText: String?, surfaceText: String?): WindowChecks
    fun displaySize(): Pair<Int, Int>
    fun systemBarInsets(): Pair<Int, Int> = 24 to 44
    fun windowFrame(pkg: String): WindowFrameInfo? = null
    fun beginGridPass() {}
    fun lastResizeNote(): String? = null
    fun sanitizeNegativeBounds(pkgs: Collection<String>): Int = 0
    fun collectDiagnostics(pkgs: Collection<String>): JSONObject? = null
    fun refreshDiagnostics(pkgs: Collection<String>): JSONObject? = null
    fun resetAppClonerWindows(pkgs: Collection<String>) {}
    fun focusPackage(pkg: String) {}
    fun showFullscreen(pkg: String, allPkgs: Collection<String>, placeId: String = "", jobId: String = "") {}
    fun minimizeAll(allPkgs: Collection<String>) {}
    fun freeformSupported(): Boolean
    fun open(pkg: String, freeform: Boolean = false, placeId: String = "", jobId: String = "")
    fun close(pkg: String)
    fun resize(pkg: String, bounds: RectBounds, launch: Boolean = true, placeId: String = "", jobId: String = ""): Int
    fun setFreeformFlags(enabled: Boolean, previousBackup: Map<String, String>?): Map<String, String>
    fun deviceInfo(): Pair<String, String>
}

class RootAndroidDevice(private val context: Context) : AndroidDevice {
    private val uidCache = mutableMapOf<String, Int>()
    private var flagsEnsured = false
    private var resizeDiag: String? = null
    @Volatile
    private var cachedDiag: JSONObject? = null
    @Volatile
    private var lastDiagMs: Long = 0L
    @Volatile
    private var diagRefreshing: Boolean = false

    override fun beginGridPass() {
        resizeDiag = null
    }

    override fun lastResizeNote(): String? = resizeDiag

    override fun isAccessibilityEnabled(): Boolean = RejoinAccessibilityService.isConnected()

    override fun memoryInfo(): SystemMemInfo? {
        var totalHint = 0
        var availHint = 0
        var lowMem = false
        try {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            if (am != null) {
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                totalHint = (mi.totalMem / (1024L * 1024L)).toInt()
                availHint = (mi.availMem / (1024L * 1024L)).toInt()
                lowMem = mi.lowMemory
            }
        } catch (_: Exception) {
        }
        val memText = try {
            File("/proc/meminfo").readText()
        } catch (_: Exception) {
            RootShell.run(listOf("/system/bin/cat", "/proc/meminfo"), check = false, timeoutSec = 3).output
        }
        val loadText = try {
            File("/proc/loadavg").readText()
        } catch (_: Exception) {
            ""
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return AndroidInspector.parseProcMeminfo(memText, totalHint, availHint, lowMem, cores, loadText)
    }

    override fun processRssMb(pkgs: Collection<String>): Map<String, Int> {
        if (pkgs.isEmpty()) return emptyMap()
        val psOut = RootShell.runRaw(
            "(/system/bin/ps -A -o RSS,NAME 2>/dev/null || /system/bin/ps -A)",
            check = false,
            timeoutSec = 4,
        ).output
        return AndroidInspector.parsePsRssMb(psOut, pkgs)
    }

    override fun clearPackageCache(pkg: String) {
        // Only trim safe WebView GPU/HTTP caches on explicit request; never wipe app assets/Delta setup cache.
        if (!ConfigLimits.isAllowedPackage(pkg)) return
        val dataDir = "/data/data/$pkg"
        val targets = listOf(
            "$dataDir/app_webview/Default/GPUCache",
            "$dataDir/app_webview/Default/Code Cache",
        )
        val cmd = targets.joinToString("; ") { dir ->
            val q = RootShell.quote(dir)
            "if [ -d $q ]; then /system/bin/find $q -mindepth 1 -delete 2>/dev/null || true; fi"
        }
        RootShell.runRaw(cmd, check = false, timeoutSec = 5)
    }

    override fun trimMemoryAndClearCache(pkgs: Collection<String>): String {
        val beforeUsed = memoryInfo()?.usedMb
        var trimmed = 0
        for (pkg in pkgs) {
            if (!ConfigLimits.isAllowedPackage(pkg)) continue
            if (running(pkg)) {
                RootShell.run(listOf("/system/bin/am", "send-trim-memory", "--user", "0", pkg, "RUNNING_LOW"), check = false, timeoutSec = 3)
                trimmed++
            }
        }
        Thread.sleep(350)
        val afterUsed = memoryInfo()?.usedMb
        val diffNote = if (beforeUsed != null && afterUsed != null) {
            val saved = (beforeUsed - afterUsed).coerceAtLeast(0)
            " (RAM máy: $beforeUsed -> $afterUsed MB, giảm ~$saved MB)"
        } else {
            ""
        }
        return "Đã gửi trim-memory tới $trimmed tab đang chạy (giữ nguyên game, cache & cookie)$diffNote."
    }

    override fun applyClientSettings(pkgs: Collection<String>, fpsCap: Int, lowGraphics: Boolean): String {
        val samplePkg = pkgs.firstOrNull { ConfigLimits.isAllowedPackage(it) }
            ?: throw AgentException("Chưa có bản clone nào để áp dụng ClientAppSettings.")
        val tmpGlobal = "/data/local/tmp/ClientAppSettings.json"
        val qGlobal = RootShell.quote(tmpGlobal)

        if (fpsCap == 0 && !lowGraphics) {
            RootShell.runRaw("/system/bin/rm -f $qGlobal", check = false, timeoutSec = 4)
            var cleaned = 0
            for (pkg in pkgs) {
                if (!ConfigLimits.isAllowedPackage(pkg)) continue
                val dataDir = "/data/data/$pkg"
                val f1 = RootShell.quote("$dataDir/files/exe/ClientSettings/ClientAppSettings.json")
                val f2 = RootShell.quote("$dataDir/files/ClientSettings/ClientAppSettings.json")
                RootShell.runRaw("/system/bin/rm -f $f1 $f2", check = false, timeoutSec = 4)
                cleaned++
            }
            return "Đã xóa file ghi đè ClientAppSettings trên $cleaned tab (khôi phục mặc định gốc Noka); bấm Rejoin tất cả để áp dụng."
        }

        val baseJson = try {
            val apkPath = RootShell.run(listOf("/system/bin/pm", "path", samplePkg), check = false, timeoutSec = 4).output
                .lines()
                .firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?.trim()
                ?: context.packageManager.getApplicationInfo(samplePkg, 0).sourceDir
            var loaded: JSONObject? = null
            if (!apkPath.isNullOrEmpty() && File(apkPath).canRead()) {
                java.util.zip.ZipFile(apkPath).use { zip ->
                    zip.getEntry("assets/ClientSettings/ClientAppSettings.json")?.let { entry ->
                        loaded = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
                    }
                }
            }
            loaded ?: JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }

        if (fpsCap > 0) {
            val safeFps = fpsCap.coerceAtLeast(15)
            baseJson.put("DFIntTaskSchedulerTargetFps", safeFps.toString())
            baseJson.put("DFIntPhysicsStepLimitMaxFPS", safeFps.toString())
        }
        if (lowGraphics) {
            baseJson.put("FFlagDisablePostFx", "True")
            baseJson.put("FIntRenderShadowIntensity", "0")
            baseJson.put("FIntFRMMaxGrassDistance", "0")
            baseJson.put("FIntFRMMinGrassDistance", "0")
        }

        val payload = baseJson.toString(2)
        val globalCmd = buildString {
            appendLine("cat <<'REJOIN_CS_EOF' > $qGlobal")
            append(payload).appendLine()
            appendLine("REJOIN_CS_EOF")
            appendLine("/system/bin/chmod 644 $qGlobal")
        }
        RootShell.runRaw(globalCmd, check = false, timeoutSec = 5)

        var appliedCount = 0
        for (pkg in pkgs) {
            if (!ConfigLimits.isAllowedPackage(pkg)) continue
            val dataDir = "/data/data/$pkg"
            val qData = RootShell.quote(dataDir)
            val dirs = listOf("$dataDir/files/exe/ClientSettings", "$dataDir/files/ClientSettings")
            val script = buildString {
                appendLine("OWNER=\$(/system/bin/stat -c '%u:%g' $qData 2>/dev/null)")
                for (dir in dirs) {
                    val qDir = RootShell.quote(dir)
                    val qFile = RootShell.quote("$dir/ClientAppSettings.json")
                    appendLine("/system/bin/mkdir -p $qDir")
                    appendLine("if [ -n \"\$OWNER\" ]; then /system/bin/chown \"\$OWNER\" $qDir; fi")
                    appendLine("/system/bin/chmod 755 $qDir")
                    appendLine("/system/bin/cp $qGlobal $qFile")
                    appendLine("if [ -n \"\$OWNER\" ]; then /system/bin/chown \"\$OWNER\" $qFile; fi")
                    appendLine("/system/bin/chmod 644 $qFile")
                    appendLine("/system/bin/restorecon $qFile 2>/dev/null || true")
                }
            }
            RootShell.runRaw(script, check = false, timeoutSec = 5)
            appliedCount++
        }
        val fpsLabel = if (fpsCap > 0) "${fpsCap.coerceAtLeast(15)} FPS" else "FPS mặc định Noka"
        val gfxLabel = if (lowGraphics) "tắt PostFX/bóng đổ" else "đồ họa gốc"
        return "Đã ghi ClientAppSettings ($fpsLabel, $gfxLabel) cho $appliedCount tab; bấm Rejoin tất cả để game áp dụng."
    }

    override fun collectDiagnostics(pkgs: Collection<String>): JSONObject? {
        // Only auto-collect full apk/dumpsys diagnostics once per process; avoid hammering SurfaceFlinger every 60s.
        if (cachedDiag == null && !diagRefreshing) {
            diagRefreshing = true
            Thread({
                try {
                    refreshDiagnostics(pkgs)
                } catch (_: Exception) {
                } finally {
                    diagRefreshing = false
                }
            }, "NokaDiagWorker").apply {
                isDaemon = true
                start()
            }
        }
        return cachedDiag
    }

    override fun refreshDiagnostics(pkgs: Collection<String>): JSONObject? {
        val diag = NokaApkInspector.collect(context, pkgs)
        cachedDiag = diag
        lastDiagMs = System.currentTimeMillis()
        return diag
    }

    override fun sanitizeNegativeBounds(pkgs: Collection<String>): Int {
        var fixed = 0
        val stacks = try { AndroidInspector.parseStackList(stackListText()) } catch (_: Exception) { emptyMap() }
        val tText = try { tasks() } catch (_: Exception) { "" }
        for (pkg in pkgs) {
            val sBounds = stacks[pkg]?.bounds
            val tId = stacks[pkg]?.taskId ?: AndroidInspector.parseTasks(tText)[pkg]
            val dBounds = tId?.let { AndroidInspector.taskBounds(tText, it).first }
            val b = sBounds ?: dBounds
            if (b != null && (b.left < 0 || b.top < 0 || b.right <= b.left || b.bottom <= b.top)) {
                close(pkg)
                fixed++
            }
        }
        return fixed
    }

    override fun windowFrame(pkg: String): WindowFrameInfo? = AndroidInspector.parseWindowFrame(windows(), pkg)

    override fun systemBarInsets(): Pair<Int, Int> {
        fun dimenPx(name: String, fallback: Int): Int {
            return try {
                val id = context.resources.getIdentifier(name, "dimen", "android")
                if (id > 0) context.resources.getDimensionPixelSize(id) else fallback
            } catch (_: Exception) {
                fallback
            }
        }
        val top = dimenPx("status_bar_height", 24).coerceIn(18, 80)
        val bottom = maxOf(dimenPx("navigation_bar_height", 48), 48).coerceIn(44, 120)
        return top to bottom
    }

    private fun collectInstalledPackages(): Set<String> {
        val found = linkedSetOf<String>()
        try {
            val apps = context.packageManager.getInstalledApplications(0)
            for (ai in apps) {
                found.add(ai.packageName)
            }
        } catch (_: Exception) {
        }
        for (args in listOf(
            listOf("/system/bin/pm", "list", "packages", "--user", "0"),
            listOf("/system/bin/pm", "list", "packages"),
        )) {
            try {
                val res = RootShell.run(args, check = false, timeoutSec = 10)
                if (res.exitCode == 0 && res.output.isNotEmpty()) {
                    res.output.lines()
                        .filter { it.startsWith("package:") }
                        .mapTo(found) { it.removePrefix("package:").trim() }
                }
            } catch (_: Exception) {
            }
        }
        return found
    }

    override fun packages(): List<String> {
        return collectInstalledPackages()
            .filter { ConfigLimits.isAllowedPackage(it) }
            .sorted()
    }

    override fun appLabel(pkg: String): String? {
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    override fun scanDiagnostics(): String {
        val all = collectInstalledPackages()
        val thirdParty = mutableListOf<String>()
        try {
            val pm = context.packageManager
            for (ai in pm.getInstalledApplications(0)) {
                val isSys = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSys && ai.packageName != context.packageName) {
                    val label = pm.getApplicationLabel(ai).toString().trim()
                    thirdParty.add("${ai.packageName} ($label)")
                }
            }
        } catch (_: Exception) {
        }
        val sample = thirdParty.sorted().take(10).joinToString(", ")
        return "Tổng gói=${all.size}; app ngoài=[${sample.ifEmpty { "không có" }}]"
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
        try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val disp = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (disp != null) {
                val p = Point()
                @Suppress("DEPRECATION")
                disp.getRealSize(p)
                if (p.x > 0 && p.y > 0) return p.x to p.y
            }
        } catch (_: Exception) {
        }
        val winDisplays = RootShell.run(listOf("/system/bin/dumpsys", "window", "displays"), check = false, timeoutSec = 6).output
        val display = RootShell.run(listOf("/system/bin/dumpsys", "display"), check = false, timeoutSec = 6).output
        val wm = RootShell.run(listOf("/system/bin/wm", "size"), check = false, timeoutSec = 5).output
        return AndroidInspector.parseDisplaySize(wm, display, winDisplays)
            ?: throw AgentException("Không đọc được kích thước màn hình.")
    }

    override fun freeformSupported(): Boolean {
        return try {
            val features = RootShell.run(listOf("/system/bin/pm", "list", "features"), check = false, timeoutSec = 8).output
            val setting = RootShell.run(listOf("/system/bin/settings", "get", "global", "enable_freeform_support"), check = false, timeoutSec = 5).output
            val help = RootShell.run(listOf("/system/bin/am", "help"), check = false, timeoutSec = 5).output
            (features.contains("android.software.freeform_window_management") || setting.trim() == "1") &&
                (help.contains("--windowingMode") || help.contains("resize <TASK_ID>") || Build.VERSION.SDK_INT >= 29)
        } catch (_: Exception) {
            false
        }
    }

    private fun stackListText(): String {
        val amOut = RootShell.run(listOf("/system/bin/am", "stack", "list"), check = false, timeoutSec = 6).output
        if (amOut.contains("taskId=") || amOut.contains("Stack id=")) return amOut
        return RootShell.run(listOf("/system/bin/cmd", "activity", "stack", "list"), check = false, timeoutSec = 6).output
    }

    private fun ensureRuntimeFreeformFlags() {
        if (flagsEnsured) return
        val cmds = listOf(
            "/system/bin/settings put global enable_freeform_support 1",
            "/system/bin/settings put global force_resizable_activities 1",
            "/system/bin/settings put global hidden_api_policy 1",
            "/system/bin/settings put global hidden_api_policy_p_apps 1",
            "/system/bin/settings put global hidden_api_policy_pre_p_apps 1",
        )
        for (c in cmds) {
            RootShell.runRaw(c, check = false, timeoutSec = 3)
        }
        flagsEnsured = true
    }

    private fun runWindowHelper(vararg helperArgs: String): ShellResult {
        val apkPath = context.packageCodePath
        val quotedArgs = helperArgs.joinToString(" ") { RootShell.quote(it) }
        val cmd = "CLASSPATH=${RootShell.quote(apkPath)} /system/bin/app_process /system/bin com.rejoin.controller.WindowHelper $quotedArgs"
        return RootShell.runRaw(cmd, check = false, timeoutSec = 8)
    }

    private fun inspectPlacement(pkg: String): TaskPlacement? {
        val tText = tasks()
        val sText = stackListText()
        val fromStacks = AndroidInspector.parseStackList(sText)[pkg]
        val taskIdFromDump = AndroidInspector.parseTasks(tText)[pkg]
        val taskId = taskIdFromDump ?: fromStacks?.taskId ?: return null
        val (dumpRect, dumpFreeform) = AndroidInspector.taskBounds(tText, taskId)
        val dumpStackId = AndroidInspector.taskStackId(tText, taskId)
        val stackId = fromStacks?.stackId ?: dumpStackId
        val rect = fromStacks?.bounds ?: dumpRect
        val freeform = dumpFreeform || (fromStacks?.freeform == true)
        return TaskPlacement(pkg = pkg, taskId = taskId, stackId = stackId, bounds = rect, freeform = freeform)
    }

    private fun waitForTaskId(pkg: String, timeoutMs: Long = 12_000L): TaskPlacement {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val placement = inspectPlacement(pkg)
            if (placement != null) return placement
            Thread.sleep(450)
        }
        throw AgentException("Chưa tìm thấy cửa sổ đang hoạt động của $pkg")
    }

    private fun switchTaskWindowingMode(taskId: Int, mode: Int) {
        runWindowHelper("mode", taskId.toString(), mode.toString())
        RootShell.run(listOf("/system/bin/am", "task", "set-windowing-mode", taskId.toString(), mode.toString()), check = false, timeoutSec = 5)
        RootShell.run(listOf("/system/bin/cmd", "activity", "task", "set-windowing-mode", taskId.toString(), mode.toString()), check = false, timeoutSec = 5)
    }

    private fun switchTaskToFreeform(taskId: Int) = switchTaskWindowingMode(taskId, 5)

    override fun showFullscreen(pkg: String, allPkgs: Collection<String>, placeId: String, jobId: String) {
        for (other in allPkgs) {
            if (other == pkg) continue
            try {
                val p = inspectPlacement(other)
                if (p != null && p.freeform) {
                    switchTaskWindowingMode(p.taskId, 1)
                }
            } catch (_: Exception) {
            }
        }
        val (w, h) = displaySize()
        val (barTop, _) = systemBarInsets()
        syncAppClonerWindowPrefs(pkg, 0, barTop, w, h, stopFirst = false)

        val isRunning = running(pkg)
        val existing = inspectPlacement(pkg)
        if (isRunning && existing != null) {
            if (existing.freeform) {
                switchTaskWindowingMode(existing.taskId, 1)
                Thread.sleep(220)
            }
            runWindowHelper("focus", existing.taskId.toString())
            try {
                val comp = resolveLauncher(pkg)
                RootShell.run(
                    listOf(
                        "/system/bin/am", "start", "-W", "--user", "0",
                        "--activity-reorder-to-front", "--windowingMode", "1",
                        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", comp,
                    ),
                    check = false, timeoutSec = 8,
                )
            } catch (_: Exception) {
                focusPackage(pkg)
            }
            return
        }

        if (isRunning) {
            close(pkg)
            Thread.sleep(250)
        }
        open(pkg, freeform = false, placeId = placeId, jobId = jobId)
        try {
            val started = waitForTaskId(pkg, timeoutMs = 8_000L)
            if (started.freeform) switchTaskWindowingMode(started.taskId, 1)
            runWindowHelper("focus", started.taskId.toString())
        } catch (_: Exception) {
        }
    }

    override fun minimizeAll(allPkgs: Collection<String>) {
        for (pkg in allPkgs) {
            try {
                val p = inspectPlacement(pkg)
                if (p != null && p.freeform) {
                    switchTaskWindowingMode(p.taskId, 1)
                }
            } catch (_: Exception) {
            }
        }
        RootShell.run(
            listOf(
                "/system/bin/am", "start", "-W", "--user", "0",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
            ),
            check = false, timeoutSec = 8,
        )
    }

    private fun placementSatisfied(p: TaskPlacement?, target: RectBounds, tolerance: Int = 24): Boolean {
        if (p == null || !p.freeform || p.bounds == null) return false
        return AndroidInspector.boundsMatch(p.bounds, target, tolerance = tolerance)
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
        args.addAll(listOf("--windowingMode", if (freeform) "5" else "1"))
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

    override fun focusPackage(pkg: String) {
        val placement = inspectPlacement(pkg) ?: return
        val res = runWindowHelper("focus", placement.taskId.toString())
        if (res.exitCode != 0) {
            val b = windowFrame(pkg)?.frame ?: placement.bounds
            if (b != null && b.right > b.left + 40 && b.bottom > b.top + 60) {
                val tx = (b.left + 20).coerceAtLeast(1)
                val ty = (b.top + 48).coerceAtMost(b.bottom - 10)
                RootShell.run(listOf("/system/bin/input", "tap", tx.toString(), ty.toString()), check = false, timeoutSec = 4)
            }
        }
    }

    private fun syncAppClonerWindowPrefs(
        pkg: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        stopFirst: Boolean,
    ): Boolean {
        val dataDir = "/data/data/$pkg"
        val prefsDir = "$dataDir/shared_prefs"
        val prefPath = "$prefsDir/${pkg}_preferences.xml"
        val markerCheck = RootShell.runRaw(
            "if [ -f ${RootShell.quote(prefPath)} ] || [ -f ${RootShell.quote("$dataDir/files/cloneSettings.json")} ]; then echo YES; fi",
            check = false,
            timeoutSec = 3,
        ).output
        if (!markerCheck.contains("YES")) return false

        val existing = RootShell.run(listOf("/system/bin/cat", prefPath), check = false, timeoutSec = 3).output
        val cur = AndroidInspector.parseAppClonerWindowBounds(existing)
        if (cur != null && cur.left == left && cur.top == top && cur.right == right && cur.bottom == bottom) {
            return false
        }

        if (stopFirst) {
            close(pkg)
            Thread.sleep(220)
        }
        val updated = AndroidInspector.upsertAppClonerWindowXml(existing, left, top, right, bottom)
        val tmpPath = "$prefsDir/.rejoin_win_tmp.xml"
        val qData = RootShell.quote(dataDir)
        val qPrefsDir = RootShell.quote(prefsDir)
        val qTmp = RootShell.quote(tmpPath)
        val qPref = RootShell.quote(prefPath)
        val cmd = buildString {
            appendLine("OWNER=\$(/system/bin/stat -c '%u:%g' $qData 2>/dev/null)")
            appendLine("/system/bin/mkdir -p $qPrefsDir")
            appendLine("if [ -n \"\$OWNER\" ]; then /system/bin/chown \"\$OWNER\" $qPrefsDir; fi")
            appendLine("/system/bin/chmod 771 $qPrefsDir")
            appendLine("cat <<'REJOIN_PREF_EOF' > $qTmp")
            append(updated.trimEnd()).appendLine()
            appendLine("REJOIN_PREF_EOF")
            appendLine("if [ -n \"\$OWNER\" ]; then /system/bin/chown \"\$OWNER\" $qTmp; fi")
            appendLine("/system/bin/chmod 660 $qTmp")
            appendLine("/system/bin/mv $qTmp $qPref")
            appendLine("/system/bin/restorecon $qPref 2>/dev/null || true")
        }
        RootShell.runRaw(cmd, check = false, timeoutSec = 5)
        return true
    }

    override fun resetAppClonerWindows(pkgs: Collection<String>) {
        for (pkg in pkgs) {
            try {
                syncAppClonerWindowPrefs(pkg, 256, 173, 1024, 583, stopFirst = false)
            } catch (_: Exception) {
            }
        }
    }

    override fun resize(pkg: String, bounds: RectBounds, launch: Boolean, placeId: String, jobId: String): Int {
        val safeBounds = RectBounds(
            left = bounds.left.coerceAtLeast(0),
            top = bounds.top.coerceAtLeast(0),
            right = maxOf(bounds.right, bounds.left.coerceAtLeast(0) + 160),
            bottom = maxOf(bounds.bottom, bounds.top.coerceAtLeast(0) + 120),
        )

        ensureRuntimeFreeformFlags()
        if (Build.VERSION.SDK_INT >= 30) {
            RootShell.run(listOf("/system/bin/am", "compat", "enable", "FORCE_RESIZE_APP", pkg), check = false, timeoutSec = 3)
        }
        val targetW = safeBounds.right - safeBounds.left
        val targetH = safeBounds.bottom - safeBounds.top
        val prefsUpdated = syncAppClonerWindowPrefs(pkg, 0, 0, targetW, targetH, stopFirst = launch)
        val existingPre = if (!prefsUpdated) inspectPlacement(pkg) else null
        if (existingPre?.bounds != null && (existingPre.bounds.left < 0 || existingPre.bounds.top < 0)) {
            close(pkg)
            Thread.sleep(250)
        }
        if (launch) open(pkg, freeform = true, placeId = placeId, jobId = jobId)

        var current = waitForTaskId(pkg)
        if (!launch && placementSatisfied(current, safeBounds, tolerance = 16)) {
            return current.taskId
        }
        val beforeRect = current.bounds

        if (!current.freeform) {
            switchTaskToFreeform(current.taskId)
            Thread.sleep(250)
            current = inspectPlacement(pkg) ?: current
            if (!current.freeform && launch) {
                // Existing fullscreen task refused to switch mode: relaunch cleanly into freeform stack.
                close(pkg)
                Thread.sleep(300)
                open(pkg, freeform = true, placeId = placeId, jobId = jobId)
                current = waitForTaskId(pkg)
            }
        }

        val trace = mutableListOf<String>()

        fun applyOnce(rect: RectBounds, initial: TaskPlacement): Pair<TaskPlacement, Boolean> {
            val taskId = initial.taskId
            val stackId = initial.stackId ?: 0
            val strategies: List<Pair<String, () -> ShellResult>> = buildList {
                add("helper" to {
                    runWindowHelper(
                        "resize", taskId.toString(),
                        rect.left.toString(), rect.top.toString(), rect.right.toString(), rect.bottom.toString(),
                        stackId.toString(),
                    )
                })
                add("am-task-space" to {
                    RootShell.run(
                        listOf("/system/bin/am", "task", "resize", taskId.toString(), rect.left.toString(), rect.top.toString(), rect.right.toString(), rect.bottom.toString()),
                        check = false, timeoutSec = 6,
                    )
                })
                add("am-task-comma" to {
                    RootShell.run(
                        listOf("/system/bin/am", "task", "resize", taskId.toString(), "${rect.left},${rect.top},${rect.right},${rect.bottom}"),
                        check = false, timeoutSec = 6,
                    )
                })
                add("cmd-task-space" to {
                    RootShell.run(
                        listOf("/system/bin/cmd", "activity", "task", "resize", taskId.toString(), rect.left.toString(), rect.top.toString(), rect.right.toString(), rect.bottom.toString()),
                        check = false, timeoutSec = 6,
                    )
                })
                if (stackId > 0) {
                    add("helper-stack" to {
                        runWindowHelper(
                            "stack-resize", stackId.toString(),
                            rect.left.toString(), rect.top.toString(), rect.right.toString(), rect.bottom.toString(),
                        )
                    })
                    add("am-stack-space" to {
                        RootShell.run(
                            listOf("/system/bin/am", "stack", "resize", stackId.toString(), rect.left.toString(), rect.top.toString(), rect.right.toString(), rect.bottom.toString()),
                            check = false, timeoutSec = 6,
                        )
                    })
                    add("am-stack-comma" to {
                        RootShell.run(
                            listOf("/system/bin/am", "stack", "resize", stackId.toString(), "${rect.left},${rect.top},${rect.right},${rect.bottom}"),
                            check = false, timeoutSec = 6,
                        )
                    })
                }
            }
            var latest = initial
            for ((name, step) in strategies) {
                val res = step()
                trace.add("$name(${res.exitCode}):${res.output.replace('\n', ' ').take(60)}")
                Thread.sleep(220)
                latest = inspectPlacement(pkg) ?: latest
                if (placementSatisfied(latest, rect, tolerance = 24)) {
                    return latest to true
                }
            }
            val r = latest.bounds
            val originMoved = latest.freeform && r != null && r != beforeRect &&
                abs(r.left - rect.left) <= 36 && abs(r.top - rect.top) <= 36
            return latest to originMoved
        }

        val (latest, ok) = applyOnce(safeBounds, current)
        if (!ok) {
            val afterRect = latest.bounds
            throw AgentException(
                "Không đổi được vị trí lưới cho $pkg (taskId=${current.taskId}, stack=${latest.stackId}, freeform=${latest.freeform}, " +
                    "đích=[${safeBounds.left},${safeBounds.top},${safeBounds.right},${safeBounds.bottom}], " +
                    "thực tế=${afterRect?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "null"}). " +
                    trace.joinToString(" | ").take(200),
            )
        }

        if (resizeDiag == null) {
            val winDump = windows()
            val wf = AndroidInspector.parseWindowFrame(winDump, pkg)
            val tb = latest.bounds?.let { "[${it.left},${it.top}..${it.right},${it.bottom}]" } ?: "null"
            val fb = wf?.frame?.let { "[${it.left},${it.top}..${it.right},${it.bottom}]" } ?: "null"
            val summary = AndroidInspector.summarizeWindowLines(winDump, pkg).take(120)
            resizeDiag = "Ô1 Task=$tb Win=$fb ($summary)"
        }
        return latest.taskId
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
        val restore = previousBackup ?: return emptyMap()
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
