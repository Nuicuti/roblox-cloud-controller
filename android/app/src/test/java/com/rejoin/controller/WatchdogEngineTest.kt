package com.rejoin.controller

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class WatchdogEngineTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeAndroid : AndroidDevice {
        val opened = mutableListOf<Triple<String, String, String>>()
        val closed = mutableListOf<String>()
        var runningMap = mutableMapOf<String, Boolean>()
        var tasksOut = "ACTIVITY MANAGER ACTIVITIES\n"
        var windowsOut = "WINDOW MANAGER WINDOWS\n  Window #1 Window{1 u0 com.android.launcher/Home}\n"
        var surfacesOut = "SurfaceFlinger\nStatusBar#0\n"
        var uiXmlOut = """<hierarchy><node package="com.android.launcher" text="Home" /></hierarchy>"""
        var accMap: Map<String, List<String>>? = null
        var peersOut: Set<UdpPeer>? = emptySet()

        override fun packages() = listOf("com.roblox.client", "free.nokaA", "free.nokaB", "free.nokaC", "free.nokaD", "free.nokaE", "free.nokaF")
        override fun running(pkg: String) = runningMap[pkg] ?: false
        override fun tasks() = tasksOut
        override fun windows() = windowsOut
        override fun surfaces() = surfacesOut
        override fun uiTreeXml() = uiXmlOut
        override fun accessibilityTexts() = accMap
        override fun udpPeers(pkg: String) = peersOut
        override fun windowChecks(pkg: String, uiXml: String?, tasksText: String?, windowsText: String?, surfaceText: String?) =
            WindowChecks(
                taskList = AndroidInspector.checkTaskList(tasksText ?: tasksOut, pkg),
                windowManager = AndroidInspector.checkWindowManager(windowsText ?: windowsOut, pkg),
                surfaces = AndroidInspector.checkSurfaces(surfaceText ?: surfacesOut, pkg),
                screenTree = AndroidInspector.checkScreenTree(uiXml ?: uiXmlOut, pkg),
            )
        override fun displaySize() = 1280 to 720
        override fun freeformSupported() = true
        override fun open(pkg: String, freeform: Boolean, placeId: String, jobId: String) {
            opened.add(Triple(pkg, placeId, jobId))
        }
        override fun close(pkg: String) { closed.add(pkg) }
        override fun resize(pkg: String, bounds: RectBounds, launch: Boolean, placeId: String, jobId: String): Int {
            if (launch) opened.add(Triple(pkg, placeId, jobId))
            return 100
        }
        override fun setFreeformFlags(enabled: Boolean, previousBackup: Map<String, String>?) = emptyMap<String, String>()
        override fun deviceInfo() = "Redfinger Test" to "12"
    }

    @Test
    fun `package allowlist, deep link uri, bounds match, and udp uid parsing`() {
        assertTrue(ConfigLimits.isAllowedPackage("free.nokaA"))
        assertTrue(ConfigLimits.isAllowedPackage("com.roblox.client"))
        assertTrue(ConfigLimits.isAllowedPackage("com.roblox.clien1"))
        assertTrue(ConfigLimits.isAllowedPackage("com.noka.free1"))
        assertFalse(ConfigLimits.isAllowedPackage("com.android.settings"))
        assertFalse(ConfigLimits.isAllowedPackage("com.rejoin.controller"))
        assertEquals("roblox://experiences/start?placeId=1730877806", ConfigLimits.buildRobloxUri("1730877806"))
        assertEquals("roblox://placeId=1730877806&gameInstanceId=abc-123", ConfigLimits.buildRobloxUri("1730877806", "abc-123"))

        assertTrue(AndroidInspector.boundsMatch(RectBounds(6, 32, 634, 350), RectBounds(8, 32, 632, 350)))
        assertFalse(AndroidInspector.boundsMatch(RectBounds(6, 32, 800, 350), RectBounds(6, 32, 634, 350)))

        val stackDump = """
            Stack id=12 bounds=[256,162][1024,558] displayId=0 userId=0
             configuration={1.0 ?mcc?mnc [vi_VN] ldltr sw320dp w320dp h240dp 240dpi freeform mBounds=Rect(256, 162 - 1024, 558)}
              taskId=40: premium.nokaF/com.roblox.client.startup.ActivitySplash bounds=[256,162][1024,558] userId=0 visible=true
            Stack id=1 bounds=[0,0][1280,720] displayId=0 userId=0
              taskId=35: premium.nokaA/com.roblox.client.startup.ActivitySplash bounds=[0,0][1280,720] userId=0 visible=true
        """.trimIndent()
        val parsedStacks = AndroidInspector.parseStackList(stackDump)
        assertEquals(12, parsedStacks["premium.nokaF"]?.stackId)
        assertEquals(40, parsedStacks["premium.nokaF"]?.taskId)
        assertTrue(parsedStacks["premium.nokaF"]?.freeform == true)
        assertEquals(RectBounds(256, 162, 1024, 558), parsedStacks["premium.nokaF"]?.bounds)
        assertEquals(1, parsedStacks["premium.nokaA"]?.stackId)
        assertFalse(parsedStacks["premium.nokaA"]?.freeform == true)

        assertEquals(
            1280 to 720,
            AndroidInspector.parseDisplaySize(
                "Physical size: 720x1280",
                "mDisplayInfo=DisplayInfo{\"Built-in Screen\", real 720 x 1280}",
                "Display #0: init=720x1280 240dpi cur=1280x720 app=1280x720",
            ),
        )

        assertEquals(2 to 3, AndroidInspector.suggestGridShape(6, 1280, 720))
        assertEquals(3 to 2, AndroidInspector.suggestGridShape(6, 720, 1280))
        assertEquals(2 to 2, AndroidInspector.suggestGridShape(4, 1280, 720))
        assertEquals(1 to 2, AndroidInspector.suggestGridShape(2, 1280, 720))

        val winDump = """
            WINDOW MANAGER WINDOWS
              Window #3 Window{1111 u0 premium.nokaA/StatusOverlay}:
                mHasSurface=true
                mFrames=Frames{containing=[260,611][681,635] mFrame=[260,611][681,635]}
              Window #4 Window{a1b2 u0 premium.nokaA/com.roblox.client.MainActivity}:
                mHasSurface=true isReadyForDisplay()=true
                mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION}
                mFrames=Frames{containing=[4,24][427,346] parent=[4,24][427,346] display=[0,0][1280,720] mFrame=[262,161][685,483]}
                WindowStateAnimator{ mShownPosition=(262.0, 161.0) }
        """.trimIndent()
        val wf = AndroidInspector.parseWindowFrame(winDump, "premium.nokaA")
        assertEquals(RectBounds(262, 161, 685, 483), wf?.frame)
        assertEquals(RectBounds(4, 24, 427, 346), wf?.containing)
        assertEquals("(262.0,161.0)", wf?.shownPos)

        val negStackDump = """
            Stack id=58 bounds=[-252,-116][169,204] displayId=0 userId=0
              taskId=62: premium.nokaA/com.roblox.client.MainActivity bounds=[-252,-116][169,204] userId=0 visible=true
        """.trimIndent()
        val negStacks = AndroidInspector.parseStackList(negStackDump)
        assertEquals(RectBounds(-252, -116, 169, 204), negStacks["premium.nokaA"]?.bounds)

        val sfDump = """
            + Layer 0x7a (premium.nokaA/com.roblox.client.MainActivity#0)
              Region visibleRegion (count=1): [257, 172, 678, 492]
              pos=(257.0, 172.0)
        """.trimIndent()
        assertTrue(AndroidInspector.parseSurfaceLayerInfo(sfDump, "premium.nokaA")?.contains("257") == true)

        val udp = """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
             12: 0100007F:1234 0100007F:0035 01 0:0 0:0 0 10142 0 1
             13: 0A00020F:9001 01020304:D188 01 0:0 0:0 0 10142 0 2
             14: 0A00020F:9002 05060708:D189 01 0:0 0:0 0 0000279f 0 3
        """.trimIndent()
        assertEquals(setOf(UdpPeer("01020304", 0xD188)), AndroidInspector.parseProcNetUdp(udp, 10142))
        assertEquals(setOf(UdpPeer("05060708", 0xD189)), AndroidInspector.parseProcNetUdp(udp, 10143))
    }

    @Test
    fun `rsa oaep sha256 plus aes gcm roundtrip matches webcrypto format`() {
        val dir = tempFolder.newFolder("crypto")
        val crypto = CryptoStore(dir)
        val jwk = crypto.publicJwk()
        val n = BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString("n")))
        val e = BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString("e")))
        val pubKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))

        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val payload = JSONArray().put(JSONObject().apply {
            put("package", "free.nokaA")
            put("username", "tester")
            put("password", "pw")
            put("cookie", "cookie_val")
        }).toString().toByteArray()

        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        }
        val cipherBytes = aesCipher.doFinal(payload)
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, pubKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }
        val wrapped = rsaCipher.doFinal(aesKey.encoded)

        val sealed = JSONObject().apply {
            put("key", Base64.getEncoder().encodeToString(wrapped))
            put("iv", Base64.getEncoder().encodeToString(iv))
            put("data", Base64.getEncoder().encodeToString(cipherBytes))
        }
        val decoded = crypto.decryptAccounts(sealed)
        assertEquals(1, decoded.size)
        assertEquals("cookie_val", decoded[0].cookie)
    }

    @Test
    fun `four layer window check, unreadable dump protection, teleport grace, and captcha pause`() {
        val dir = tempFolder.newFolder("engine")
        val fa = FakeAndroid()
        val engine = WatchdogEngine(dir, fa, CryptoStore(dir))
        engine.scan()
        for ((pkg, p) in engine.profiles) p.enabled = (pkg == "free.nokaA")
        fa.runningMap["free.nokaA"] = true

        val cfg = RejoinConfig(
            enabled = true,
            placeId = "1730877806",
            checkIntervalSec = 5,
            delayAfterLaunchSec = 5,
            delayBetweenClonesSec = 15,
            bootGraceSec = 10,
            windowGraceSec = 15,
            disconnectConfirmSec = 10,
            teleportDetection = true,
            teleportGraceSec = 60,
            backoffSec = 5,
        )
        engine.execute(JSONObject().put("action", "set_rejoin_config").put("config", cfg.toJson()))

        // Unreadable UI dump -> never counts as closed window.
        fa.uiXmlOut = ""
        engine.watchdog(100_000)
        engine.watchdog(150_000)
        assertTrue(fa.opened.isEmpty())

        // All 4 readable and absent -> closes after windowGraceSec (15s).
        fa.uiXmlOut = """<hierarchy><node package="com.android.launcher" text="Home" /></hierarchy>"""
        engine.watchdog(200_000)
        assertTrue(fa.opened.isEmpty())
        engine.watchdog(215_000)
        assertEquals(1, fa.opened.size)
        assertEquals("free.nokaA", fa.opened[0].first)
        assertEquals("1730877806", fa.opened[0].second)
        assertEquals(listOf("free.nokaA"), fa.closed)

        // Accessibility CAPTCHA detection pauses auto-rejoin safely.
        fa.opened.clear()
        fa.accMap = mapOf("free.nokaA" to listOf("Verify you are human", "Arkose"))
        engine.watchdog(300_000)
        assertTrue(fa.opened.isEmpty())
        assertEquals("auth_required", engine.profiles["free.nokaA"]?.healthState)
        assertEquals("manual_verification_required", engine.profiles["free.nokaA"]?.loginStatus)
    }

    @Test
    fun `staggered queue spaces launches between clones`() {
        val dir = tempFolder.newFolder("queue")
        val fa = FakeAndroid()
        val engine = WatchdogEngine(dir, fa, CryptoStore(dir))
        engine.scan()
        for ((pkg, p) in engine.profiles) p.enabled = (pkg == "free.nokaA" || pkg == "free.nokaB")
        val cfg = RejoinConfig(enabled = true, placeId = "999", checkIntervalSec = 5, delayAfterLaunchSec = 5, delayBetweenClonesSec = 15)
        engine.execute(JSONObject().put("action", "set_rejoin_config").put("config", cfg.toJson()), nowMs = 1_000)
        val msg = engine.execute(JSONObject().put("action", "rejoin_all"), nowMs = 10_000)
        assertTrue(msg.contains("tuần tự"))
        assertEquals(1, fa.opened.size)
        assertFalse(engine.drainLaunchQueue(20_000))
        assertTrue(engine.drainLaunchQueue(25_000))
        assertEquals(2, fa.opened.size)
    }

    @Test
    fun `smart grid chooses optimal landscape shape for 6 active clones`() {
        val dir = tempFolder.newFolder("smart-grid")
        val fa = FakeAndroid()
        val engine = WatchdogEngine(dir, fa, CryptoStore(dir))
        engine.scan()
        // Disable com.roblox.client so 6 clones remain active
        engine.profiles["com.roblox.client"]?.enabled = false
        val msg = engine.execute(
            JSONObject()
                .put("action", "grid")
                .put("rows", 3)
                .put("cols", 2)
                .put("gap", 4)
                .put("top", 24)
                .put("bottom", 44)
                .put("smart", true),
        )
        assertTrue(msg.contains("2×3"))
        assertEquals(2, engine.grid?.rows)
        assertEquals(3, engine.grid?.cols)
        assertEquals(6, fa.opened.size)
    }

    @Test
    fun `self update reports version info and triggers updater`() {
        val dir = tempFolder.newFolder("self-update")
        var hookRan = false
        val engine = WatchdogEngine(
            storageDir = dir,
            android = FakeAndroid(),
            crypto = CryptoStore(dir),
            appVersionInfo = { 12L to "2.0.12" },
            selfUpdater = {
                "Đang cập nhật lên build #13" to { hookRan = true }
            },
        )
        val telemetry = engine.buildTelemetry()
        assertEquals("2.0.12", telemetry.getString("appVersion"))
        assertEquals(12L, telemetry.getLong("buildCode"))

        val msg = engine.execute(JSONObject().put("action", "self_update"))
        assertTrue(msg.contains("build #13"))
        assertFalse(hookRan) // Hook only runs after Firebase result + inbox ACK in processCommand
    }

    @Test
    fun `restore windowing clears grid and closes running clones without throwing`() {
        val dir = tempFolder.newFolder("restore-win")
        val fa = FakeAndroid()
        val engine = WatchdogEngine(dir, fa, CryptoStore(dir))
        engine.scan()
        engine.execute(
            JSONObject()
                .put("action", "grid")
                .put("rows", 2)
                .put("cols", 3)
                .put("gap", 4)
                .put("top", 24)
                .put("bottom", 44),
        )
        engine.profiles["free.nokaA"]?.error = "lỗi vị trí lưới cũ"
        val msg = engine.execute(JSONObject().put("action", "restore_windowing"))
        assertTrue(msg.contains("khôi phục"))
        assertEquals(null, engine.grid)
        assertEquals(null, engine.profiles["free.nokaA"]?.error)
        assertTrue(fa.closed.contains("free.nokaA"))
    }
}
