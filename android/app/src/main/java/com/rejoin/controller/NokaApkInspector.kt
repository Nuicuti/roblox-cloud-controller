package com.rejoin.controller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Collects read-only diagnostics from installed Noka/Roblox APKs, non-sensitive layout
 * preferences, and Android WindowManager/InputDispatcher/SurfaceFlinger state so grid
 * and tab movement can align with Noka's own windowing behavior.
 */
object NokaApkInspector {
    private val SENSITIVE_KEY_RE = Regex(
        """(cookie|token|pass|pwd|secret|auth|session|ticket|csrf|email|phone|signature|hmac|rbx|api_?key|private_?key)""",
        RegexOption.IGNORE_CASE,
    )
    private val DEX_HINT_RE = Regex(
        """(noka|tab|sort|float|multi|freeform|window|drag|overlay|caption|menu|layout|resize|bounds|scale|xposed|lsplant|pine|hook|dobby|panel)""",
        RegexOption.IGNORE_CASE,
    )
    private val STD_PREFIX_RE = Regex(
        """^L(?:java|javax|kotlin|kotlinx|android|androidx|dalvik|sun|com/google|com/facebook|com/appsflyer|io/branch|org/json|org/apache|org/chromium)/""",
    )
    private val dexScannedPkgs = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun collect(context: Context, managedPackages: Collection<String>): JSONObject {
        val out = JSONObject()
        out.put("capturedAt", System.currentTimeMillis())

        val thirdParty = listThirdPartyPackages()
        out.put("thirdPartyPkgs", thirdParty.take(25).joinToString(", "))

        val samplePkg = managedPackages.firstOrNull { it.contains("noka", ignoreCase = true) }
            ?: managedPackages.firstOrNull()
            ?: thirdParty.firstOrNull { it.contains("noka", ignoreCase = true) || it.contains("roblox", ignoreCase = true) }

        out.put("samplePkg", samplePkg ?: "")
        out.put("env", collectSystemEnv())
        out.put("live", collectLiveWindows())
        if (!samplePkg.isNullOrEmpty()) {
            out.put("apk", inspectApk(context, samplePkg))
            out.put("appData", inspectAppData(samplePkg, managedPackages))
        }
        val extraNokaPkg = thirdParty.firstOrNull {
            it != samplePkg && !it.startsWith("premium.noka") && !it.startsWith("free.noka") &&
                (
                    it.contains("noka", ignoreCase = true) ||
                        it.contains("nclones", ignoreCase = true) ||
                        it.contains("roblox", ignoreCase = true)
                    )
        }
        if (!extraNokaPkg.isNullOrEmpty()) {
            out.put("extraApk", inspectApk(context, extraNokaPkg))
            out.put("extraAppData", inspectAppData(extraNokaPkg, emptyList()))
        }
        return sanitizeFirebaseObject(out)
    }

    fun sanitizeFirebaseObject(obj: JSONObject): JSONObject {
        val clean = JSONObject()
        val it = obj.keys()
        while (it.hasNext()) {
            val rawKey = it.next()
            val safeKey = rawKey.replace(Regex("""[.#$\[\]/]"""), "_").ifEmpty { "_k" }
            clean.put(safeKey, sanitizeFirebaseValue(obj.get(rawKey)))
        }
        return clean
    }

    private fun sanitizeFirebaseValue(value: Any?): Any? {
        return when (value) {
            is JSONObject -> sanitizeFirebaseObject(value)
            is JSONArray -> JSONArray().apply {
                for (i in 0 until value.length()) {
                    put(sanitizeFirebaseValue(value.get(i)))
                }
            }
            else -> value
        }
    }

    private fun listThirdPartyPackages(): List<String> {
        val out = RootShell.run(listOf("/system/bin/pm", "list", "packages", "-3"), check = false, timeoutSec = 8).output
        return out.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .sorted()
    }

    private fun collectSystemEnv(): JSONObject {
        val env = JSONObject()
        val wmSize = RootShell.run(listOf("/system/bin/wm", "size"), check = false, timeoutSec = 4).output
        val wmDensity = RootShell.run(listOf("/system/bin/wm", "density"), check = false, timeoutSec = 4).output
        val dispOut = RootShell.run(listOf("/system/bin/dumpsys", "window", "displays"), check = false, timeoutSec = 5).output
        env.put("wm", "${wmSize.replace('\n', ' ')} | ${wmDensity.replace('\n', ' ')}".take(250))
        env.put("displays", dispOut.lines().take(24).joinToString("\n").take(1400))

        val propsOut = RootShell.runRaw(
            "/system/bin/getprop | /system/bin/grep -iE 'redfinger|ld|freeform|multi|window|hw|noka|product\\.model|product\\.brand|build\\.display|density|emu|cloud' | /system/bin/head -n 35",
            check = false, timeoutSec = 5,
        ).output
        env.put("props", propsOut.take(1600))

        val settingsOut = RootShell.runRaw(
            "(/system/bin/settings list global; /system/bin/settings list system; /system/bin/settings list secure) | /system/bin/grep -iE 'freeform|resiz|multi|window|red|ld|compat|dpi|density|noka|roblox|scale|decor|pc|nav|immersive|force' | /system/bin/head -n 45",
            check = false, timeoutSec = 6,
        ).output
        env.put("settings", settingsOut.take(1800))

        val procsOut = RootShell.runRaw(
            "/system/bin/ps -A | /system/bin/grep -iE 'noka|roblox|red|ld|freeform|multi|window|launcher|hw' | /system/bin/head -n 35",
            check = false, timeoutSec = 5,
        ).output
        env.put("procs", procsOut.take(1800))

        val sysPkgsOut = RootShell.runRaw(
            "/system/bin/pm list packages | /system/bin/grep -iE 'noka|roblox|red|ld|freeform|multi|window|float|cloud|desktop' | /system/bin/head -n 35",
            check = false, timeoutSec = 5,
        ).output
        env.put("matchingPkgs", sysPkgsOut.take(1200))
        return env
    }

    private fun collectLiveWindows(): JSONObject {
        val live = JSONObject()
        val stacks = RootShell.runRaw(
            "/system/bin/am stack list || /system/bin/cmd activity stack list",
            check = false, timeoutSec = 6,
        ).output
        live.put("stacks", stacks.take(2200))

        val actOut = RootShell.run(listOf("/system/bin/dumpsys", "activity", "activities"), check = false, timeoutSec = 8).output
        val actLines = actOut.lines().filter { l ->
            l.contains("Stack #") ||
                l.contains("* Task") ||
                l.contains("TaskRecord") ||
                l.contains("mBounds=") ||
                l.contains("windowingMode") ||
                l.contains("mLastNonFullscreenBounds") ||
                l.contains("Hist #0:") ||
                l.contains("noka", ignoreCase = true) ||
                l.contains("roblox", ignoreCase = true)
        }
        live.put("activities", actLines.take(70).joinToString("\n").take(2800))

        val winOut = RootShell.run(listOf("/system/bin/dumpsys", "window", "windows"), check = false, timeoutSec = 8).output
        live.put("windows", extractInterestingWindows(winOut).take(4200))

        val inputOut = RootShell.run(listOf("/system/bin/dumpsys", "input"), check = false, timeoutSec = 8).output
        live.put("input", extractInputWindows(inputOut).take(3500))

        val sfOut = RootShell.run(listOf("/system/bin/dumpsys", "SurfaceFlinger"), check = false, timeoutSec = 8).output
        live.put("sf", extractSurfaceSummary(sfOut).take(3200))
        return live
    }

    private fun extractInterestingWindows(winOut: String): String {
        val sb = StringBuilder()
        for (line in winOut.lines()) {
            if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                sb.append(line.trim()).append('\n')
            }
        }
        val blocks = Regex("""(?m)(?=^[ \t]*(?:Window\s+#\d+\s+)?Window\{)""").split(winOut)
        for (block in blocks) {
            val firstLine = block.lineSequence().firstOrNull()?.trim() ?: continue
            val interesting = firstLine.contains("noka", ignoreCase = true) ||
                firstLine.contains("roblox", ignoreCase = true) ||
                firstLine.contains("launcher", ignoreCase = true) ||
                block.contains("isReadyForDisplay()=true")
            if (!interesting) continue
            sb.append("--- ").append(firstLine).append('\n')
            block.lines().drop(1).map { it.trim() }.filter {
                it.startsWith("mOwnerUid=") ||
                    it.startsWith("mAttrs=") ||
                    it.startsWith("mWindowingMode=") ||
                    it.startsWith("mHasSurface=") ||
                    it.startsWith("mFrames=") ||
                    it.startsWith("Frames{") ||
                    it.contains("containing=") ||
                    it.contains("mFrame=") ||
                    it.contains("mShownPosition") ||
                    it.contains("mGlobalScale") ||
                    it.contains("mRequestedWidth") ||
                    it.contains("mBounds") ||
                    it.contains("Bounds=") ||
                    it.contains("Surface") ||
                    it.contains("pos=")
            }.take(9).forEach { sb.append("  ").append(it).append('\n') }
            if (sb.length > 4200) break
        }
        return sb.toString()
    }

    private fun extractInputWindows(inputOut: String): String {
        val lines = inputOut.lines()
        val sb = StringBuilder()
        var inWindows = false
        var count = 0
        for (raw in lines) {
            val trimmed = raw.trim()
            if (!inWindows) {
                if (trimmed == "Windows:" || trimmed.startsWith("Windows:")) {
                    inWindows = true
                    sb.append(trimmed).append('\n')
                }
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("MonitoringChannels:") || trimmed.startsWith("RecentQueue:")) {
                if (count > 0) break
                continue
            }
            sb.append(trimmed).append('\n')
            count++
            if (count >= 140 || sb.length >= 5200) break
        }
        return if (sb.isNotEmpty()) sb.toString() else inputOut.lines().filter {
            it.contains("touchableRegion", ignoreCase = true) ||
                it.contains("noka", ignoreCase = true) ||
                it.contains("roblox", ignoreCase = true)
        }.take(40).joinToString("\n")
    }

    private fun extractSurfaceSummary(sfOut: String): String {
        val lines = sfOut.lines()
        val sb = StringBuilder()
        var i = 0
        while (i < lines.size && sb.length < 2200) {
            val l = lines[i]
            if ((l.contains("Layer ", ignoreCase = true) || l.contains("name=", ignoreCase = true)) &&
                (l.contains("noka", ignoreCase = true) || l.contains("roblox", ignoreCase = true))
            ) {
                sb.append(l.trim()).append('\n')
                val end = minOf(lines.size, i + 14)
                for (j in i + 1 until end) {
                    val sub = lines[j].trim()
                    if (sub.contains("Region") || sub.contains("bounds") || sub.contains("pos=") ||
                        sub.contains("frame") || sub.contains("crop") || sub.contains("transform") ||
                        sub.contains("activeBuffer") || sub.contains("z=")
                    ) {
                        sb.append("  ").append(sub).append('\n')
                    }
                }
                i = end
                continue
            }
            i++
        }
        val hwcIdx = lines.indexOfFirst { it.contains("HWC layers", ignoreCase = true) }
        if (hwcIdx >= 0) {
            sb.append("--- HWC ---\n")
            lines.drop(hwcIdx).take(35).forEach { sb.append(it.trim()).append('\n') }
        }
        return sb.toString()
    }

    private fun inspectApk(context: Context, pkg: String): JSONObject {
        val apkObj = JSONObject()
        val pmOut = RootShell.run(listOf("/system/bin/pm", "path", pkg), check = false, timeoutSec = 5).output
        val apkPath = pmOut.lines()
            .firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")
            ?.trim()
            ?: try { context.packageManager.getApplicationInfo(pkg, 0).sourceDir } catch (_: Exception) { "" }
        apkObj.put("path", apkPath)

        val pkgDump = RootShell.run(listOf("/system/bin/dumpsys", "package", pkg), check = false, timeoutSec = 8).output
        val pkgLines = pkgDump.lines().filter { l ->
            l.contains("versionName=") ||
                l.contains("versionCode=") ||
                l.contains("flags=") ||
                l.contains("privateFlags=") ||
                l.contains("resizeable", ignoreCase = true) ||
                l.contains("Aspect", ignoreCase = true) ||
                l.contains("minWidth", ignoreCase = true) ||
                l.contains("Activity") ||
                l.contains("Service") ||
                l.contains("Receiver") ||
                l.contains("Provider") ||
                l.contains("SYSTEM_ALERT") ||
                l.contains("noka", ignoreCase = true) ||
                l.contains("meta-data", ignoreCase = true)
        }
        apkObj.put("manifest", pkgLines.take(70).joinToString("\n").take(2600))

        if (apkPath.isNotEmpty() && File(apkPath).canRead() && dexScannedPkgs.add(pkg)) {
            try {
                ZipFile(apkPath).use { zip ->
                    val dexNames = mutableListOf<String>()
                    val libs = mutableListOf<String>()
                    val assets = mutableListOf<String>()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val name = e.name
                        when {
                            name.endsWith(".dex") && !name.contains('/') -> dexNames.add("$name(${e.size / 1024}KB)")
                            name.startsWith("lib/") && name.endsWith(".so") -> libs.add("$name(${e.size / 1024}KB)")
                            name.startsWith("assets/") && !name.startsWith("assets/ExtraContent/") && !name.startsWith("assets/content/") -> assets.add("$name(${e.size / 1024}KB)")
                            (name.startsWith("res/layout") || name.startsWith("res/xml")) && DEX_HINT_RE.containsMatchIn(name) -> assets.add(name)
                        }
                    }
                    apkObj.put("dexFiles", dexNames.joinToString(", "))
                    apkObj.put("libs", libs.take(35).joinToString(", "))
                    apkObj.put("assets", assets.take(80).joinToString(", "))

                    val classes = linkedSetOf<String>()
                    val strings = linkedSetOf<String>()
                    val deadline = System.currentTimeMillis() + 3500L
                    val dexList = mutableListOf<java.util.zip.ZipEntry>()
                    val dexEntries = zip.entries()
                    while (dexEntries.hasMoreElements()) {
                        val entry = dexEntries.nextElement()
                        if (entry.name.endsWith(".dex") && !entry.name.contains('/')) dexList.add(entry)
                    }
                    // Scan smallest dexes (App Cloner / pack wrappers) first, then main dexes.
                    for (entry in dexList.sortedBy { it.size }) {
                        if (System.currentTimeMillis() > deadline) break
                        if (classes.size >= 90 && strings.size >= 90) break
                        scanDexStrings(zip.getInputStream(entry), deadline, classes, strings)
                    }
                    apkObj.put("dexClasses", JSONArray(classes.take(90)))
                    apkObj.put("dexStrings", JSONArray(strings.take(120)))

                    zip.getEntry("assets/ClientSettings/ClientAppSettings.json")?.let { csEntry ->
                        try {
                            val raw = zip.getInputStream(csEntry).bufferedReader().readText()
                            val csJson = JSONObject(raw)
                            val fpsObj = JSONObject()
                            val it = csJson.keys()
                            var total = 0
                            while (it.hasNext()) {
                                total++
                                val k = it.next()
                                if (k.contains("fps", ignoreCase = true) ||
                                    k.contains("frame", ignoreCase = true) ||
                                    k.contains("taskscheduler", ignoreCase = true) ||
                                    k.contains("vsync", ignoreCase = true) ||
                                    k.contains("throttle", ignoreCase = true)
                                ) {
                                    fpsObj.put(k, csJson.get(k))
                                }
                            }
                            fpsObj.put("_totalKeys", total)
                            apkObj.put("clientAppSettingsFps", fpsObj)
                        } catch (_: Exception) {
                        }
                    }

                    val nativeFpsHints = linkedSetOf<String>()
                    val nativeDeadline = System.currentTimeMillis() + 1800L
                    val soEntries = mutableListOf<java.util.zip.ZipEntry>()
                    val libEnum = zip.entries()
                    while (libEnum.hasMoreElements()) {
                        val entry = libEnum.nextElement()
                        val n = entry.name
                        if (n.startsWith("lib/") && n.endsWith(".so") &&
                            entry.size in 1L..6_000_000L &&
                            !n.contains("roblox", ignoreCase = true) &&
                            !n.contains("zstd", ignoreCase = true) &&
                            !n.contains("persona", ignoreCase = true)
                        ) {
                            soEntries.add(entry)
                        }
                    }
                    for (entry in soEntries.sortedBy { it.size }) {
                        if (System.currentTimeMillis() > nativeDeadline || nativeFpsHints.size >= 80) break
                        scanFpsAsciiHints(zip.getInputStream(entry), nativeDeadline, nativeFpsHints)
                    }
                    apkObj.put("nativeFpsHints", JSONArray(nativeFpsHints.take(80)))
                }
            } catch (e: Exception) {
                apkObj.put("zipErr", e.message ?: "zip error")
            }
        }
        return apkObj
    }

    private val FPS_HINT_RE = Regex(
        """(autoexec|setfpscap|fps_?cap|max_?fps|fps_?limit|ClientAppSettings|TaskSchedulerTargetFps|/files/exe|/sdcard/|noka_?config|unlock_?fps)""",
        RegexOption.IGNORE_CASE,
    )

    private fun scanFpsAsciiHints(
        rawStream: java.io.InputStream,
        deadlineMs: Long,
        out: MutableSet<String>,
    ) {
        BufferedInputStream(rawStream, 65536).use { input ->
            val buf = ByteArray(65536)
            val token = ByteArray(180)
            var tLen = 0
            var truncated = false
            var reads = 0
            while (true) {
                if ((reads++ and 0x1F) == 0 && (System.currentTimeMillis() > deadlineMs || out.size >= 80)) break
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    if (b in 0x20..0x7E) {
                        if (!truncated) {
                            if (tLen < token.size) token[tLen++] = b.toByte() else truncated = true
                        }
                    } else {
                        if (!truncated && tLen >= 5 && out.size < 80) {
                            val s = String(token, 0, tLen, Charsets.US_ASCII)
                            if (FPS_HINT_RE.containsMatchIn(s)) {
                                out.add(s)
                            }
                        }
                        tLen = 0
                        truncated = false
                    }
                }
            }
        }
    }

    private fun scanDexStrings(
        rawStream: java.io.InputStream,
        deadlineMs: Long,
        classes: MutableSet<String>,
        strings: MutableSet<String>,
    ) {
        BufferedInputStream(rawStream, 65536).use { input ->
            val buf = ByteArray(65536)
            val token = ByteArray(180)
            var tLen = 0
            var truncated = false
            var reads = 0
            while (true) {
                if ((reads++ and 0x1F) == 0 && System.currentTimeMillis() > deadlineMs) break
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    if (b in 0x20..0x7E) {
                        if (!truncated) {
                            if (tLen < token.size) {
                                token[tLen++] = b.toByte()
                            } else {
                                truncated = true
                            }
                        }
                    } else {
                        if (!truncated && tLen >= 5) {
                            val s = String(token, 0, tLen, Charsets.US_ASCII)
                            if (s.startsWith('L') && s.endsWith(';')) {
                                val isInterestingCls = s.contains("applisto", ignoreCase = true) ||
                                    s.contains("noka", ignoreCase = true) ||
                                    (!STD_PREFIX_RE.containsMatchIn(s) && !s.startsWith("Lcom/roblox/") && DEX_HINT_RE.containsMatchIn(s))
                                if (classes.size < 90 && isInterestingCls) {
                                    classes.add(s)
                                }
                            } else if (strings.size < 120 &&
                                !s.contains("Lcom/applisto/", ignoreCase = false) &&
                                !s.contains("FloatingActionButton", ignoreCase = true) &&
                                !s.contains("AppCompatTheme", ignoreCase = true) &&
                                (
                                    s.contains("app_cloner", ignoreCase = true) ||
                                        s.contains("FloatingApp", ignoreCase = true) ||
                                        s.contains("floatingApp", ignoreCase = true) ||
                                        s.contains("noka", ignoreCase = true) ||
                                        s.contains("nclones", ignoreCase = true) ||
                                        s.contains("window_left", ignoreCase = true) ||
                                        s.contains("window_top", ignoreCase = true) ||
                                        s.contains("DecorCaption", ignoreCase = true) ||
                                        s.contains("windowingMode", ignoreCase = true) ||
                                        s.contains("autoexec", ignoreCase = true) ||
                                        s.contains("setfpscap", ignoreCase = true) ||
                                        s.contains("ClientAppSettings", ignoreCase = true) ||
                                        s.contains("/files/exe", ignoreCase = true) ||
                                        s.contains("fps_cap", ignoreCase = true)
                                    )
                            ) {
                                strings.add(s)
                            }
                        }
                        tLen = 0
                        truncated = false
                    }
                }
            }
        }
    }

    private fun inspectAppData(pkg: String, allPkgs: Collection<String>): JSONObject {
        val dataObj = JSONObject()
        val dataDir = "/data/data/$pkg"
        val lsOut = RootShell.runRaw(
            "/system/bin/ls -la ${RootShell.quote(dataDir)} ${RootShell.quote("$dataDir/files")} ${RootShell.quote("$dataDir/files/libpack")} ${RootShell.quote("$dataDir/files/appData")} 2>/dev/null | /system/bin/head -n 80",
            check = false, timeoutSec = 5,
        ).output
        dataObj.put("ls", lsOut.take(2400))

        val exeTree = RootShell.runRaw(
            "/system/bin/find ${RootShell.quote("$dataDir/files/exe")} ${RootShell.quote("$dataDir/files/datastore")} ${RootShell.quote("$dataDir/files/libpack")} ${RootShell.quote("$dataDir/app_assets")} -maxdepth 4 2>/dev/null | /system/bin/head -n 140",
            check = false, timeoutSec = 5,
        ).output
        dataObj.put("exeTree", exeTree.take(3200))

        val sdcardTree = RootShell.runRaw(
            "(/system/bin/ls -la /sdcard 2>/dev/null | /system/bin/head -n 45; echo '---match---'; /system/bin/find /sdcard -maxdepth 3 \\( -iname '*noka*' -o -iname '*autoexec*' -o -iname '*roblox*' -o -iname '*executor*' -o -iname '*script*' -o -iname '*fps*' \\) 2>/dev/null | /system/bin/head -n 90; echo '---ext---'; /system/bin/find ${RootShell.quote("/sdcard/Android/data/$pkg")} -maxdepth 4 2>/dev/null | /system/bin/head -n 80)",
            check = false, timeoutSec = 6,
        ).output
        dataObj.put("sdcardTree", sdcardTree.take(3200))

        val cfgFiles = RootShell.runRaw(
            "/system/bin/find ${RootShell.quote("$dataDir/files/exe")} ${RootShell.quote("$dataDir/app_assets")} /sdcard -maxdepth 3 -type f \\( -iname '*.json' -o -iname '*.cfg' -o -iname '*.ini' -o -iname '*.properties' -o -iname '*fps*.txt' -o -iname '*config*.txt' -o -iname '*setting*.txt' \\) -size -16k 2>/dev/null | /system/bin/grep -iE 'noka|exe|fps|setting|config|pref|ClientAppSettings|state' | /system/bin/head -n 12",
            check = false, timeoutSec = 5,
        ).output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val exeConfigs = JSONArray()
        for (path in cfgFiles.take(8)) {
            if (SENSITIVE_KEY_RE.containsMatchIn(path)) continue
            val raw = RootShell.run(listOf("/system/bin/cat", path), check = false, timeoutSec = 3).output
            val safe = raw.lines()
                .filter { line -> !SENSITIVE_KEY_RE.containsMatchIn(line) && !line.contains("http", ignoreCase = true) }
                .joinToString("\n")
                .take(900)
            if (safe.isNotBlank()) {
                exeConfigs.put(JSONObject().put("path", path).put("body", safe))
            }
        }
        dataObj.put("exeConfigs", exeConfigs)

        val cloneSettingsRaw = RootShell.run(listOf("/system/bin/cat", "$dataDir/files/cloneSettings.json"), check = false, timeoutSec = 4).output
        if (cloneSettingsRaw.trim().startsWith("{")) {
            try {
                val cs = JSONObject(cloneSettingsRaw)
                val allKeys = mutableListOf<String>()
                val windowSubset = JSONObject()
                val it = cs.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    allKeys.add(k)
                    if (SENSITIVE_KEY_RE.containsMatchIn(k) || k.contains("proxy", ignoreCase = true) || k.contains("socks", ignoreCase = true)) {
                        continue
                    }
                    if (k.contains("float", ignoreCase = true) ||
                        k.contains("window", ignoreCase = true) ||
                        k.contains("screen", ignoreCase = true) ||
                        k.contains("size", ignoreCase = true) ||
                        k.contains("scale", ignoreCase = true) ||
                        k.contains("display", ignoreCase = true) ||
                        k.contains("multi", ignoreCase = true) ||
                        k.contains("dpi", ignoreCase = true) ||
                        k.contains("aspect", ignoreCase = true) ||
                        k.contains("rotation", ignoreCase = true) ||
                        k.contains("orientation", ignoreCase = true) ||
                        k.contains("cloner", ignoreCase = true) ||
                        k.contains("menu", ignoreCase = true) ||
                        k.contains("sort", ignoreCase = true) ||
                        k.contains("tab", ignoreCase = true) ||
                        k.contains("freeform", ignoreCase = true)
                    ) {
                        windowSubset.put(k, cs.get(k))
                    }
                }
                dataObj.put("cloneSettingsKeys", allKeys.sorted().joinToString(", ").take(2500))
                dataObj.put("cloneSettingsWindow", windowSubset)
            } catch (e: Exception) {
                dataObj.put("cloneSettingsErr", e.message ?: "parse error")
            }
        }

        val allWinPrefs = JSONArray()
        for (p in allPkgs.sorted()) {
            val prefFile = "/data/data/$p/shared_prefs/${p}_preferences.xml"
            val raw = RootShell.run(listOf("/system/bin/cat", prefFile), check = false, timeoutSec = 3).output
            if (raw.contains("app_cloner_") || raw.contains("<map")) {
                allWinPrefs.put(JSONObject().put("pkg", p).put("xml", raw.take(900)))
            }
        }
        dataObj.put("allWindowPrefs", allWinPrefs)

        val prefsList = RootShell.runRaw(
            "/system/bin/ls ${RootShell.quote("$dataDir/shared_prefs")} 2>/dev/null",
            check = false, timeoutSec = 4,
        ).output.lines().map { it.trim() }.filter { it.endsWith(".xml") }

        val safePrefs = JSONArray()
        for (fileName in prefsList) {
            if (fileName.contains("cookie", ignoreCase = true) ||
                fileName.contains("auth", ignoreCase = true) ||
                fileName.contains("appsflyer", ignoreCase = true) ||
                fileName.contains("firebase", ignoreCase = true) ||
                fileName.contains("google", ignoreCase = true) ||
                fileName.contains("admob", ignoreCase = true) ||
                fileName.contains("chromium", ignoreCase = true) ||
                fileName.contains("webview", ignoreCase = true) ||
                fileName.contains("crash", ignoreCase = true) ||
                fileName.contains("Backtrace", ignoreCase = true) ||
                fileName.contains("APPS_FLYER", ignoreCase = true) ||
                fileName.contains("ota_state", ignoreCase = true) ||
                fileName.contains("cached_app_settings", ignoreCase = true) ||
                fileName == "prefs.xml"
            ) {
                continue
            }
            val raw = RootShell.run(listOf("/system/bin/cat", "$dataDir/shared_prefs/$fileName"), check = false, timeoutSec = 3).output
            val safeLines = raw.lines().filter { line ->
                val t = line.trim()
                t.startsWith("<") && !t.startsWith("<?") && !t.startsWith("<map") && !t.startsWith("</map")
            }.map { line ->
                val nameMatch = Regex("""\bname="([^"]+)"""").find(line)
                val keyName = nameMatch?.groupValues?.get(1) ?: ""
                if (SENSITIVE_KEY_RE.containsMatchIn(keyName)) {
                    "<redacted name=\"$keyName\" />"
                } else {
                    line.trim().take(160)
                }
            }
            if (safeLines.isNotEmpty()) {
                safePrefs.put(
                    JSONObject()
                        .put("file", fileName)
                        .put("xml", safeLines.take(30).joinToString("\n").take(1000)),
                )
            }
        }
        dataObj.put("sharedPrefs", safePrefs)
        return dataObj
    }
}
