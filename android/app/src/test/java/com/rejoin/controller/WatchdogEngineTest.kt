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
}
