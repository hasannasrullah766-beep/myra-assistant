package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelperService : AccessibilityService() {

    companion object {
        var instance: AccessibilityHelperService? = null

        fun isEnabled(context: android.content.Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(
                "${context.packageName}/.service.AccessibilityHelperService"
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ─── Close Current App ────────────────────────
    fun closeCurrentApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ─── Go Back ──────────────────────────────────
    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ─── Click on Text ────────────────────────────
    fun clickOnText(text: String) {
        val root = rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                parent = parent.parent
            }
        }
    }

    // ─── Type Text ────────────────────────────────
    fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val editText = findEditText(root) ?: return
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ─── Scroll ───────────────────────────────────
    fun scrollDown() {
        val root = rootInActiveWindow ?: return
        findScrollableNode(root)?.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
    }

    fun scrollUp() {
        val root = rootInActiveWindow ?: return
        findScrollableNode(root)?.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditText(child)
            if (result != null) return result
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }
}
