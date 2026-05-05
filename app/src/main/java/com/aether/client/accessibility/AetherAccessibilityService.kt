package com.aether.client.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aether.client.data.model.ActionCommand
import com.aether.client.data.model.ActionType
import com.aether.client.data.model.NodeData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.ArrayDeque

class AetherAccessibilityService : AccessibilityService() {

    private var lastScrapeTime = 0L
    private var lastNodeList: List<NodeData> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AetherAS", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Update active package even if we don't scrape
        event.packageName?.let {
            lastActivePackage = it.toString()
        }

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Throttle: only process if >200ms since last scrape
        val now = System.currentTimeMillis()
        if (now - lastScrapeTime < 200) return
        lastScrapeTime = now

        val root = rootInActiveWindow
        if (root == null) {
            Log.w("AetherAS", "rootInActiveWindow is null — skipping. eventType=$eventType")
            return
        }

        val activePackage = root.packageName?.toString() ?: ""

        // Never scrape our own app — it is always empty and causes issues
        if (activePackage == packageName) {
            root.recycle()
            return
        }

        Log.d("AetherAS", "Scraping package: $activePackage")

        try {
            val nodes = scrapeNodeTree(root)
            lastNodeList = nodes
            Log.d("AetherAS", "Emitting ${nodes.size} nodes")
            nodeTreeFlow.tryEmit(nodes)
        } catch (e: Exception) {
            Log.e("AetherAS", "Scrape failed: ${e.message}", e)
        } finally {
            // Always recycle root — critical to prevent memory leaks
            try { root.recycle() } catch (e: Exception) { }
        }
    }

    fun forceScrape() {
        val root = rootInActiveWindow ?: return
        val activePackage = root.packageName?.toString() ?: ""
        
        // Even for forced scrape, don't send our own app as it confuses the LLM
        if (activePackage == packageName) {
            root.recycle()
            return
        }

        try {
            val nodes = scrapeNodeTree(root)
            lastNodeList = nodes
            Log.d("AetherAS", "Forced Scrape: Emitting ${nodes.size} nodes from $activePackage")
            nodeTreeFlow.tryEmit(nodes)
        } catch (e: Exception) {
            Log.e("AetherAS", "Forced scrape failed: ${e.message}")
        } finally {
            try { root.recycle() } catch (e: Exception) { }
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
                    nodeId             = nodeId,
                    className          = node.className?.toString() ?: "",
                    text               = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    viewIdResourceName = node.viewIdResourceName?.toString(),
                    isClickable        = node.isClickable,
                    isScrollable       = node.isScrollable,
                    isEditable         = node.isEditable,
                    isVisible          = node.isVisibleToUser,
                    boundsLeft         = bounds.left,
                    boundsTop          = bounds.top,
                    boundsRight        = bounds.right,
                    boundsBottom       = bounds.bottom,
                    depth              = depth,
                    childCount         = node.childCount
                ))

                // Add children to stack
                for (i in node.childCount - 1 downTo 0) {
                    val child = node.getChild(i)
                    if (child != null) {
                        stack.addLast(child to depth + 1)
                    }
                }
            } catch (e: Exception) {
                Log.w("AetherAS", "Node processing error at depth $depth: ${e.message}")
            } finally {
                // Recycle every node except root (root is recycled by caller)
                if (node !== root) {
                    try { node.recycle() } catch (e: Exception) { }
                }
            }
        }
        return result
    }

    private fun buildStableNodeId(node: AccessibilityNodeInfo, bounds: Rect): String {
        val resourceId = node.viewIdResourceName?.toString()
        val text = node.text?.toString()?.trim()?.take(30)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"

        return when {
            // Resource IDs are always stable — prefer them
            !resourceId.isNullOrEmpty() -> resourceId

            // Text + class + position is stable enough
            !text.isNullOrEmpty() -> "${cls}_${text}_${bounds.left}_${bounds.top}"

            // Position-only fallback
            else -> "${cls}_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}"
        }
    }

    suspend fun performAction(command: ActionCommand): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            Log.e("AetherAS", "Cannot execute action — root window is null")
            return false
        }
        try {
            val node = findNodeById(root, command.nodeId)
            if (node == null) {
                Log.e("AetherAS", "Node not found: ${command.nodeId}")
                return false
            }
            val result = when (command.type) {
                ActionType.TAP        -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ActionType.LONG_TAP   -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                ActionType.SCROLL_UP  -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                ActionType.SCROLL_DOWN-> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ActionType.TYPE       -> {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        command.text ?: ""
                    )
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                ActionType.SWIPE      -> performSwipe(command.x ?: 0f, command.y ?: 0f,
                                                       command.x2 ?: 0f, command.y2 ?: 0f)
                ActionType.BACK       -> performGlobalAction(GLOBAL_ACTION_BACK)
                ActionType.HOME       -> performGlobalAction(GLOBAL_ACTION_HOME)
            }
            Log.d("AetherAS", "Action ${command.type} result=$result on ${command.nodeId}")
            return result
        } catch (e: Exception) {
            Log.e("AetherAS", "performAction crashed: ${e.message}", e)
            return false
        } finally {
            try { root.recycle() } catch (e: Exception) { }
        }
    }

    private fun findNodeById(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (buildStableNodeId(node, bounds) == targetId) {
                return node  // caller must recycle this
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) stack.addLast(child)
            }
        }
        return null
    }

    private fun performSwipe(x: Float, y: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 450L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("AetherAS", "Accessibility service destroyed")
    }

    companion object {
        var instance: AetherAccessibilityService? = null
        var lastActivePackage: String = ""
        fun isRunning(): Boolean = instance != null
        val nodeTreeFlow = MutableSharedFlow<List<NodeData>>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
}