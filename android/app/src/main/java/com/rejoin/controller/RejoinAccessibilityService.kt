package com.rejoin.controller

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RejoinAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Snapshots are pulled on demand across all interactive windows by WatchdogEngine.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun collectPackageTexts(): Map<String, List<String>> {
        val grouped = linkedMapOf<String, MutableList<String>>()
        val windowList = try { windows } catch (_: Exception) { emptyList() }
        for (win in windowList) {
            val root = win.root ?: continue
            traverseNode(root, grouped)
        }
        if (grouped.isEmpty()) {
            rootInActiveWindow?.let { traverseNode(it, grouped) }
        }
        return grouped
    }

    private fun traverseNode(node: AccessibilityNodeInfo, out: MutableMap<String, MutableList<String>>) {
        val pkg = node.packageName?.toString() ?: ""
        if (ConfigLimits.isAllowedPackage(pkg) || pkg == "android") {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val combined = listOf(text, desc).filter { it.isNotEmpty() }.joinToString(" ")
            if (combined.isNotEmpty()) {
                out.getOrPut(pkg) { mutableListOf() }.add(combined)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, out)
            }
        }
    }

    private fun collectPackageUiNodes(pkg: String): List<UiNode> {
        val result = mutableListOf<UiNode>()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (win in windows) {
                val r = win.root ?: continue
                if (r.packageName?.toString() == pkg) {
                    roots.add(r)
                }
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) roots.add(it) }
        }
        for (root in roots) {
            traverseUiNodes(root, pkg, result)
        }
        return result
    }

    private fun traverseUiNodes(node: AccessibilityNodeInfo, pkg: String, out: MutableList<UiNode>) {
        if (node.packageName?.toString() == pkg) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.right > rect.left && rect.bottom > rect.top) {
                out.add(
                    UiNode(
                        pkg = pkg,
                        cls = node.className?.toString().orEmpty(),
                        text = node.text?.toString()?.trim().orEmpty(),
                        desc = node.contentDescription?.toString()?.trim().orEmpty(),
                        hint = node.hintText?.toString()?.trim().orEmpty(),
                        resId = node.viewIdResourceName.orEmpty(),
                        password = node.isPassword,
                        editable = node.isEditable || node.className?.toString()?.endsWith("EditText") == true,
                        clickable = node.isClickable,
                        bounds = RectBounds(rect.left, rect.top, rect.right, rect.bottom),
                    ),
                )
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> traverseUiNodes(child, pkg, out) }
        }
    }

    private fun findAccessibilityNode(pkg: String, target: RectBounds): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (win in windows) {
                val r = win.root ?: continue
                if (r.packageName?.toString() == pkg) roots.add(r)
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) roots.add(it) }
        }
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (node.packageName?.toString() == pkg &&
                rect.left == target.left && rect.top == target.top &&
                rect.right == target.right && rect.bottom == target.bottom
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)?.let { return it }
            }
            return null
        }
        for (r in roots) {
            search(r)?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: RejoinAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun snapshot(): Map<String, List<String>>? {
            val svc = instance ?: return null
            return try {
                svc.collectPackageTexts()
            } catch (_: Exception) {
                null
            }
        }

        fun dumpNodes(pkg: String): List<UiNode>? {
            val svc = instance ?: return null
            return try {
                svc.collectPackageUiNodes(pkg)
            } catch (_: Exception) {
                null
            }
        }

        fun clickNode(pkg: String, bounds: RectBounds): Boolean {
            val svc = instance ?: return false
            return try {
                var node: AccessibilityNodeInfo? = svc.findAccessibilityNode(pkg, bounds) ?: return false
                while (node != null && !node.isClickable) {
                    node = node.parent
                }
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            } catch (_: Exception) {
                false
            }
        }

        fun setNodeText(pkg: String, bounds: RectBounds, text: String): Boolean {
            val svc = instance ?: return false
            return try {
                val node = svc.findAccessibilityNode(pkg, bounds) ?: return false
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Exception) {
                false
            }
        }
    }
}
