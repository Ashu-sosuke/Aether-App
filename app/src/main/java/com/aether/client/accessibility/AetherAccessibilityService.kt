package com.aether.client.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aether.client.data.model.ActionCommand
import com.aether.client.data.model.ActionType
import com.aether.client.data.model.NodeData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.ArrayDeque

class AetherAccessibilityService : AccessibilityService() {

    private var lastScrapeTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AetherAS", "Accessibility service connected")
        
        // Ensure flags are set programmatically as a fallback to XML
        serviceInfo = serviceInfo?.apply {
            flags = flags or 
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    private fun getRootNode(): AccessibilityNodeInfo? {
        // Method 1: Standard way
        rootInActiveWindow?.let { return it }

        // Method 2: Iterate through windows (more robust for some Android versions)
        try {
            val windows = windows
            // Try to find the focused window first
            windows.find { it.isFocused }?.root?.let { return it }
            // Try to find the active window
            windows.find { it.isActive }?.root?.let { return it }
            // Take the first window that has a root
            windows.forEach { window ->
                window.root?.let { return it }
            }
        } catch (e: Exception) {
            Log.e("AetherAS", "Error getting root from windows list: ${e.message}")
        }

        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        event.packageName?.let { lastActivePackage = it.toString() }

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastScrapeTime < 200) return
        lastScrapeTime = now

        val root = getRootNode()
        if (root == null) {
            Log.v("AetherAS", "onAccessibilityEvent: root node is null (skipping)")
            return
        }
        val activePkg = root.packageName?.toString() ?: ""

        // Skip scraping our own app for automatic events to avoid feedback loops
        if (activePkg == packageName) {
            root.recycle()
            return
        }

        try {
            val nodes = scrapeNodeTree(root)
            nodeTreeFlow.tryEmit(nodes)
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    fun forceScrapeNow(): Boolean {
        val root = getRootNode()
        if (root == null) {
            Log.w("AetherAS", "forceScrapeNow: root is null even after window search")
            return false
        }
        return try {
            val nodes = scrapeNodeTree(root)
            if (nodes.isNotEmpty()) {
                nodeTreeFlow.tryEmit(nodes)
                Log.d("AetherAS", "Force scrape complete: ${nodes.size} nodes")
                true
            } else {
                Log.w("AetherAS", "Force scrape: tree is empty")
                false
            }
        } catch (e: Exception) {
            Log.e("AetherAS", "Force scrape failed: ${e.message}")
            false
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    private fun scrapeNodeTree(root: AccessibilityNodeInfo): List<NodeData> {
        val result = mutableListOf<NodeData>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val nodeId = buildStableNodeId(node, bounds)

                result.add(NodeData(
                    nodeId = nodeId,
                    className = node.className?.toString() ?: "",
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    viewIdResourceName = node.viewIdResourceName?.toString(),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    isVisible = node.isVisibleToUser,
                    boundsLeft = bounds.left, boundsTop = bounds.top,
                    boundsRight = bounds.right, boundsBottom = bounds.bottom,
                    depth = depth, childCount = node.childCount
                ))

                for (i in node.childCount - 1 downTo 0) {
                    node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                }
            } finally {
                if (node !== root) {
                    try { node.recycle() } catch (e: Exception) {}
                }
            }
        }
        return result
    }

    private fun buildStableNodeId(node: AccessibilityNodeInfo, bounds: Rect): String {
        val resId = node.viewIdResourceName?.toString()
        val text = node.text?.toString()?.trim()?.take(30)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
        return when {
            !resId.isNullOrEmpty() -> resId
            !text.isNullOrEmpty() -> "${cls}_${text}_${bounds.left}_${bounds.top}"
            else -> "${cls}_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}"
        }
    }

    suspend fun performAction(command: ActionCommand): Boolean {
        if (command.type == ActionType.BACK) {
            return performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (command.type == ActionType.HOME) {
            return performGlobalAction(GLOBAL_ACTION_HOME)
        }
        if (command.type == ActionType.SWIPE) {
            return performSwipe(command.x ?: 0f, command.y ?: 0f, command.x2 ?: 0f, command.y2 ?: 0f)
        }

        val root = rootInActiveWindow ?: return false
        try {
            val node = findNodeById(root, command.nodeId) ?: return false
            val result = when (command.type) {
                ActionType.TAP -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ActionType.LONG_TAP -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                ActionType.SCROLL_UP -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                ActionType.SCROLL_DOWN -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ActionType.TYPE -> {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text ?: "")
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                ActionType.SWIPE, ActionType.BACK, ActionType.HOME -> false
            }
            return result
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    private fun findNodeById(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (buildStableNodeId(node, bounds) == targetId) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun performSwipe(x: Float, y: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 450L)).build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {}

    companion object {
        var instance: AetherAccessibilityService? = null
        var lastActivePackage: String = "unknown"
        fun isRunning(): Boolean = instance != null
        val nodeTreeFlow = MutableSharedFlow<List<NodeData>>(
            replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
}
