package com.rejoin.controller

import android.accessibilityservice.AccessibilityService
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
    }
}
