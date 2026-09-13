package com.example.screenpet

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenReaderService : AccessibilityService() {

    companion object {
        @Volatile var latestScreenText: String = ""
        @Volatile var isConnected: Boolean = false
    }

    override fun onServiceConnected() {
        isConnected = true
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        ForegroundAppTracker.onAppChanged(pkg)

        val root = rootInActiveWindow ?: return
        val buffer = StringBuilder()
        collectText(root, buffer, 0)
        latestScreenText = buffer.toString().trim().take(1500)
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || sb.length > 1500 || depth > 25) return

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (sb.isNotEmpty()) sb.append(" | ")
            sb.append(it)
        }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (sb.isNotEmpty()) sb.append(" | ")
            sb.append(it)
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }

    override fun onInterrupt() {}
}