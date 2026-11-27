package com.rejoin.controller

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class UiNode(
    val pkg: String,
    val cls: String,
    val text: String,
    val desc: String,
    val hint: String,
    val resId: String,
    val password: Boolean,
    val editable: Boolean,
    val clickable: Boolean,
    val bounds: RectBounds,
) {
    fun center(): Pair<Int, Int> = ((bounds.left + bounds.right) / 2) to ((bounds.top + bounds.bottom) / 2)
    fun nonEmptyArea(): Boolean = bounds.right > bounds.left + 4 && bounds.bottom > bounds.top + 4
}

data class LoginFields(
    val usernameNode: UiNode?,
    val passwordNode: UiNode?,
    val submitButton: UiNode?,
    val landingLoginButton: UiNode?,
    val authChallenge: String?,
    val alreadyLoggedIn: Boolean,
)

data class FillLoginResult(
    val status: String,
    val message: String,
)

/**
 * Automates entering saved Roblox username and password into the login UI using Accessibility
 * interactive windows or root uiautomator + input commands. Always pauses safely if CAPTCHA
 * or 2-step verification appears.
 */
object LoginAutomator {
    private val BOUNDS_RE = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    private val AUTH_CHALLENGE_MARKERS = listOf(
        "arkose", "captcha", "verify you are human", "xác minh bạn là con người",
        "two-step verification", "2-step verification", "xác minh 2 bước",
        "authenticator", "security notification", "verification code", "mã xác minh",
    )

    private val LOGGED_IN_MARKERS = listOf(
        "home", "trang chủ", "charts", "bảng xếp hạng", "avatar", "hình đại diện",
        "marketplace", "thị trường", "robux", "friends", "bạn bè", "chat", "trò chuyện",
    )

    fun parseUiNodes(uiXml: String?, targetPkg: String, clipBounds: RectBounds? = null): List<UiNode> {
        if (uiXml.isNullOrEmpty() || !uiXml.contains("<hierarchy")) return emptyList()
        val out = mutableListOf<UiNode>()
        try {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(uiXml)))
            val nodes = doc.getElementsByTagName("node")
            for (i in 0 until nodes.length) {
                val elem = nodes.item(i) as? Element ?: continue
                val pkg = elem.getAttribute("package")
                if (pkg != targetPkg) continue
                val bm = BOUNDS_RE.matchEntire(elem.getAttribute("bounds")) ?: continue
                val bounds = RectBounds(
                    bm.groupValues[1].toInt(),
                    bm.groupValues[2].toInt(),
                    bm.groupValues[3].toInt(),
                    bm.groupValues[4].toInt(),
                )
                if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) continue
                if (clipBounds != null && !bounds.centerInside(clipBounds)) continue
                val cls = elem.getAttribute("class")
                val text = elem.getAttribute("text").trim()
                val desc = elem.getAttribute("content-desc").trim()
                val hint = elem.getAttribute("hint").trim()
                val resId = elem.getAttribute("resource-id").trim()
                val password = elem.getAttribute("password") == "true"
                val editable = cls.endsWith("EditText") || password || elem.getAttribute("editable") == "true"
                val clickable = elem.getAttribute("clickable") == "true"
                out.add(UiNode(pkg, cls, text, desc, hint, resId, password, editable, clickable, bounds))
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    fun analyzeScreen(nodes: List<UiNode>): LoginFields {
        val allLower = nodes.flatMap { listOf(it.text, it.desc, it.hint) }
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .lowercase()

        val challenge = AUTH_CHALLENGE_MARKERS.firstOrNull { it in allLower }
        if (challenge != null) {
            return LoginFields(null, null, null, null, authChallenge = challenge, alreadyLoggedIn = false)
        }

        val inputCandidates = nodes.filter { it.editable && it.nonEmptyArea() }
        val passwordNode = inputCandidates.firstOrNull { n ->
            n.password ||
                n.hint.contains("password", ignoreCase = true) ||
                n.hint.contains("mật khẩu", ignoreCase = true) ||
                n.desc.contains("password", ignoreCase = true) ||
                n.resId.contains("password", ignoreCase = true)
        } ?: if (inputCandidates.size >= 2) inputCandidates.maxByOrNull { it.bounds.top } else null

        val usernameNode = inputCandidates.firstOrNull { n ->
            n !== passwordNode && (
                n.hint.contains("username", ignoreCase = true) ||
                    n.hint.contains("tên người dùng", ignoreCase = true) ||
                    n.hint.contains("email", ignoreCase = true) ||
                    n.desc.contains("username", ignoreCase = true) ||
                    n.resId.contains("username", ignoreCase = true) ||
                    (passwordNode != null && n.bounds.top < passwordNode.bounds.top)
                )
        } ?: inputCandidates.firstOrNull { n -> n !== passwordNode }

        fun isLoginLabel(raw: String): Boolean {
            val l = raw.trim().lowercase()
            if (l.isEmpty()) return false
            val matches = l == "đăng nhập" || l == "log in" || l == "login" ||
                (l.contains("đăng nhập") && !l.contains("thiết bị") && !l.contains("nhanh") && !l.contains("quên")) ||
                (l.contains("log in") && !l.contains("device") && !l.contains("quick") && !l.contains("forgot") && !l.contains("another"))
            return matches
        }

        val loginButtons = nodes.filter { n ->
            !n.editable && n.nonEmptyArea() && (isLoginLabel(n.text) || isLoginLabel(n.desc))
        }.sortedWith(
            compareByDescending<UiNode> { it.clickable }
                .thenByDescending { it.cls.contains("Button") }
                .thenByDescending { it.bounds.top },
        )

        val isLoginForm = usernameNode != null && passwordNode != null
        val submitBtn = if (isLoginForm) {
            loginButtons.firstOrNull { it.bounds.top >= passwordNode!!.bounds.top - 12 } ?: loginButtons.firstOrNull()
        } else {
            null
        }
        val landingBtn = if (!isLoginForm) loginButtons.firstOrNull() else null

        val hasSignupOrForgot = listOf("sign up", "tạo tài khoản", "đăng ký", "forgot", "quên mật khẩu")
            .any { it in allLower }
        val loggedInCount = LOGGED_IN_MARKERS.count { it in allLower }
        val alreadyLoggedIn = !isLoginForm && landingBtn == null && !hasSignupOrForgot && loggedInCount >= 2

        return LoginFields(
            usernameNode = usernameNode,
            passwordNode = passwordNode,
            submitButton = submitBtn,
            landingLoginButton = landingBtn,
            authChallenge = null,
            alreadyLoggedIn = alreadyLoggedIn,
        )
    }

    fun summarizeNodes(nodes: List<UiNode>): String {
        if (nodes.isEmpty()) return "0-nodes"
        return nodes.take(12).joinToString("; ") { n ->
            val label = n.text.ifEmpty { n.desc }.ifEmpty { n.hint }.take(24)
            val clsShort = n.cls.substringAfterLast('.')
            "$clsShort($label@${n.bounds.left},${n.bounds.top})"
        }.take(240)
    }

    fun fillCredentials(android: AndroidDevice, account: AccountRecord): FillLoginResult {
        val pkg = account.packageName
        if (account.username.isEmpty() || account.password.isEmpty()) {
            return FillLoginResult("failed", "Chưa lưu đủ tài khoản và mật khẩu.")
        }

        android.focusPackage(pkg)
        Thread.sleep(500)

        var nodes = readPackageNodes(android, pkg)
        var screen = analyzeScreen(nodes)
        screen.authChallenge?.let {
            return FillLoginResult("auth_required", "Gặp màn hình xác minh ($it) — tạm dừng để bạn xử lý thủ công.")
        }
        if (screen.alreadyLoggedIn) {
            return FillLoginResult("logged_in", "Đã đăng nhập sẵn (${account.username}).")
        }

        if (screen.usernameNode == null && screen.landingLoginButton != null) {
            val btn = screen.landingLoginButton!!
            tapNode(pkg, btn)
            Thread.sleep(1800)
            nodes = readPackageNodes(android, pkg)
            screen = analyzeScreen(nodes)
            screen.authChallenge?.let {
                return FillLoginResult("auth_required", "Gặp màn hình xác minh ($it) — tạm dừng để bạn xử lý thủ công.")
            }
        }

        val uNode = screen.usernameNode
        val pNode = screen.passwordNode
        if (uNode == null || pNode == null) {
            val diag = summarizeNodes(nodes)
            return FillLoginResult(
                "failed",
                "Chưa thấy ô nhập tài khoản/mật khẩu trên màn hình $pkg ($diag).",
            )
        }

        enterTextIntoNode(pkg, uNode, account.username)
        Thread.sleep(300)
        enterTextIntoNode(pkg, pNode, account.password)
        Thread.sleep(350)

        val submit = screen.submitButton ?: analyzeScreen(readPackageNodes(android, pkg)).submitButton
        if (submit != null) {
            tapNode(pkg, submit)
        } else {
            // Press IME Enter as fallback if submit button wasn't exposed cleanly
            RootShell.run(listOf("/system/bin/input", "keyevent", "66"), check = false, timeoutSec = 4)
        }
        Thread.sleep(2400)

        val postNodes = readPackageNodes(android, pkg)
        val post = analyzeScreen(postNodes)
        if (post.authChallenge != null) {
            return FillLoginResult(
                "auth_required",
                "Đã điền TK/MK và bấm Đăng nhập — chờ bạn giải CAPTCHA / xác minh 2 bước.",
            )
        }
        val postText = postNodes.flatMap { listOf(it.text, it.desc) }.joinToString(" | ").lowercase()
        if (listOf("incorrect username or password", "sai tên người dùng hoặc mật khẩu", "wrong password").any { it in postText }) {
            return FillLoginResult("failed", "Roblox báo sai tài khoản hoặc mật khẩu.")
        }
        if (post.alreadyLoggedIn) {
            return FillLoginResult("logged_in", "Đã điền TK/MK và đăng nhập thành công (${account.username}).")
        }
        return FillLoginResult("submitted", "Đã điền tài khoản ${account.username}, mật khẩu và bấm Đăng nhập.")
    }

    private fun readPackageNodes(android: AndroidDevice, pkg: String): List<UiNode> {
        RejoinAccessibilityService.dumpNodes(pkg)?.takeIf { it.isNotEmpty() }?.let { return it }
        val bounds = android.windowFrame(pkg)?.frame
        val xml = android.uiTreeXml()
        val scoped = parseUiNodes(xml, pkg, bounds)
        return scoped.ifEmpty { parseUiNodes(xml, pkg, null) }
    }

    private fun tapNode(pkg: String, node: UiNode) {
        if (RejoinAccessibilityService.clickNode(pkg, node.bounds)) return
        val (cx, cy) = node.center()
        RootShell.run(listOf("/system/bin/input", "tap", cx.toString(), cy.toString()), check = false, timeoutSec = 4)
    }

    private fun enterTextIntoNode(pkg: String, node: UiNode, value: String) {
        if (RejoinAccessibilityService.setNodeText(pkg, node.bounds, value)) {
            return
        }
        val (cx, cy) = node.center()
        RootShell.run(listOf("/system/bin/input", "tap", cx.toString(), cy.toString()), check = false, timeoutSec = 4)
        Thread.sleep(280)

        val existing = node.text
        if (existing.isNotEmpty() && existing != node.hint && !existing.equals("Username/Email/Phone", ignoreCase = true) &&
            !existing.contains("Tên người dùng", ignoreCase = true) && !existing.equals("Password", ignoreCase = true) &&
            !existing.contains("Mật khẩu", ignoreCase = true)
        ) {
            val delCount = minOf(existing.length + 6, 48)
            val keyArgs = mutableListOf("/system/bin/input", "keyevent", "123")
            repeat(delCount) { keyArgs.add("67") }
            RootShell.run(keyArgs, check = false, timeoutSec = 6)
            Thread.sleep(150)
        }

        // Send ASCII text segments split cleanly on spaces so special symbols stay intact.
        val parts = value.split(' ')
        for (idx in parts.indices) {
            val segment = parts[idx]
            if (segment.isNotEmpty()) {
                RootShell.runRaw(
                    "/system/bin/input text ${RootShell.quote(segment)}",
                    check = false,
                    timeoutSec = 6,
                )
            }
            if (idx < parts.lastIndex) {
                RootShell.run(listOf("/system/bin/input", "keyevent", "62"), check = false, timeoutSec = 4)
            }
        }
    }
}
