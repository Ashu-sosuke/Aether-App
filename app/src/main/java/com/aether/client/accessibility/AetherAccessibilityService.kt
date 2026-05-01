package com.aether.client.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aether.client.data.model.ActionCommand
import com.aether.client.data.model.ActionType
import com.aether.client.data.model.NodeData
import java.util.ArrayDeque
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

class AetherAccessibilityService : AccessibilityService() {
    private var lastEmitMs = 0L

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastEmitMs <= 200L) return
        lastEmitMs = now
        activePackageName = event.packageName?.toString().orEmpty()

        val root = rootInActiveWindow ?: return
        val nodes = traverse(root)
        lastNodeTree = nodes
        nodeTreeFlow.tryEmit(nodes)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        lastNodeTree = emptyList()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        lastNodeTree = emptyList()
        return super.onUnbind(intent)
    }

    suspend fun performAction(command: ActionCommand): Boolean {
        return when (command.type) {
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.SWIPE -> performSwipe(command)
            else -> {
                val node = findNodeInfoById(command.nodeId) ?: return false
                try {
                    when (command.type) {
                        ActionType.TAP -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        ActionType.LONG_TAP -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                        ActionType.TYPE -> {
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    command.text.orEmpty()
                                )
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                        ActionType.SCROLL_DOWN -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        ActionType.SCROLL_UP -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        else -> false
                    }
                } finally {
                    node.recycle()
                }
            }
        }
    }

    private fun performSwipe(command: ActionCommand): Boolean {
        val startX = command.x ?: return false
        val startY = command.y ?: return false
        val endX = command.x2 ?: return false
        val endY = command.y2 ?: return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findNodeInfoById(nodeId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (buildNodeId(node) == nodeId) {
                while (stack.isNotEmpty()) stack.removeLast().recycle()
                return node
            }
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let(stack::add)
            }
            node.recycle()
        }
        return null
    }

    private fun traverse(root: AccessibilityNodeInfo): List<NodeData> {
        val result = mutableListOf<NodeData>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        val rect = Rect()

        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            try {
                node.getBoundsInScreen(rect)
                result.add(
                    NodeData(
                        nodeId = buildNodeId(node),
                        className = node.className?.toString().orEmpty(),
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        viewIdResourceName = node.viewIdResourceName,
                        isClickable = node.isClickable,
                        isScrollable = node.isScrollable,
                        isEditable = node.isEditable,
                        isVisible = node.isVisibleToUser,
                        boundsLeft = rect.left,
                        boundsTop = rect.top,
                        boundsRight = rect.right,
                        boundsBottom = rect.bottom,
                        depth = depth,
                        childCount = node.childCount
                    )
                )
                for (index in node.childCount - 1 downTo 0) {
                    node.getChild(index)?.let { child -> stack.add(child to depth + 1) }
                }
            } finally {
                node.recycle()
            }
        }
        return result
    }

    companion object {
        var instance: AetherAccessibilityService? = null
            private set

        val nodeTreeFlow = MutableSharedFlow<List<NodeData>>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        @Volatile
        var lastNodeTree: List<NodeData> = emptyList()
            private set

        @Volatile
        var activePackageName: String = ""
            private set

        fun isRunning(): Boolean = instance != null

        fun findCachedNodeById(nodeId: String): NodeData? =
            lastNodeTree.firstOrNull { it.nodeId == nodeId }

        private fun buildNodeId(node: AccessibilityNodeInfo): String =
            "${node.className ?: "Unknown"}#${node.hashCode()}"
    }
}
