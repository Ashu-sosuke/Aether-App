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
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import android.view.accessibility.AccessibilityWindowInfo
import android.content.Intent
import android.net.Uri

data class FullObservation(
    val nodes: List<NodeData> = emptyList(),
    val screenshot: String? = null,
    val type: String = "observation",
    val reason: String? = null
)

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

    @Volatile private var cachedRootNode: AccessibilityNodeInfo? = null
    private val cacheUpdateLock = Any()

    private fun getRootNode(): AccessibilityNodeInfo? {
        // Method 1: Standard way
        rootInActiveWindow?.let { 
            return it 
        }

        // Method 2: Try finding input focus directly
        try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { 
                return it 
            }
        } catch (ignored: Exception) {}

        // Method 3: Iterate through windows
        try {
            val currentWindows = windows
            currentWindows.find { it.isFocused }?.root?.let { return it }
            currentWindows.find { it.isActive }?.root?.let { return it }
            currentWindows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.let { return it }
            currentWindows.forEach { window ->
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

        // Cache the event source as a fallback root if it's valid
        val source = event.source
        if (source != null) {
            val root = walkToRoot(source)
            if (root != null) {
                synchronized(cacheUpdateLock) {
                    cachedRootNode = root
                    Log.d("AetherAS", "Cache updated via event type=${event.eventType} pkg=${event.packageName}")
                }
            }
        }

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
            return
        }

        try {
            val nodes = scrapeNodeTree(root)
            observationFlow.tryEmit(FullObservation(nodes = nodes))
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    private fun walkToRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var parent: AccessibilityNodeInfo? = null
        return try {
            while (true) {
                parent = current?.parent ?: break
                current?.recycle()
                current = parent
            }
            current
        } catch (e: Exception) {
            Log.w("AetherAS", "walkToRoot failed: ${e.message}")
            null
        }
    }

    private var blindCount = 0

    suspend fun forceScrapeNow(): Boolean {
        // PRIORITY 1: Use event-cached root (fastest, most reliable)
        synchronized(cacheUpdateLock) {
            val cached = cachedRootNode
            if (cached != null) {
                Log.d("AetherAS", "forceScrapeNow: using event-cached root node")
                blindCount = 0
                return emitScrape(cached, null)
            }
        }

        // PRIORITY 2: Poll windows list
        Log.d("AetherAS", "forceScrapeNow: cache miss, polling windows...")
        for (attempt in 1..6) {
            Log.d("AetherAS", "forceScrapeNow: polling windows ($attempt/6)...")
            val root = getRootNode()
            if (root != null) {
                Log.d("AetherAS", "forceScrapeNow: got root from windows on attempt $attempt")
                blindCount = 0
                return emitScrape(root, null)
            }
            delay(500)
        }

        // PRIORITY 3: Screenshot via AccessibilityService fallback
        Log.d("AetherAS", "forceScrapeNow: attempting takeScreenshot...")
        val screenshot = try {
            takeScreenshotAsBase64()
        } catch (e: Exception) {
            Log.w("AetherAS", "Screenshot capability restricted or failed: ${e.message}")
            null
        }

        if (screenshot != null) {
            blindCount = 0
            observationFlow.tryEmit(FullObservation(nodes = emptyList(), screenshot = screenshot))
            return true
        }

        // TOTAL FAILURE — tell the brain
        blindCount++
        Log.e("AetherAS", "forceScrapeNow: all observation paths failed ($blindCount/3).")
        
        if (blindCount >= 3) {
            Log.w("AetherAS", "Blind too long. Triggering auto-recovery HOME action.")
            performGlobalAction(GLOBAL_ACTION_HOME)
            blindCount = 0
        }

        observationFlow.tryEmit(FullObservation(
            nodes = emptyList(), 
            screenshot = null,
            type = "blind",
            reason = "Observation blocked. App may be FLAG_SECURE or Service needs reset."
        ))
        return false
    }

    private fun emitScrape(root: AccessibilityNodeInfo, screenshot: String?): Boolean {
        return try {
            val nodes = scrapeNodeTree(root)
            observationFlow.tryEmit(FullObservation(nodes = nodes, screenshot = screenshot))
            Log.d("AetherAS", "Force scrape successful: ${nodes.size} nodes")
            true
        } catch (e: Exception) {
            Log.e("AetherAS", "Scrape failed: ${e.message}")
            false
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
            } catch (e: Exception) {
                Log.w("AetherAS", "Failed to scrape sub-node: ${e.message}")
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
        
        if (command.type == ActionType.OPEN_APP) {
            val pkg = command.text ?: return false
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            return if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else false
        }
        
        if (command.type == ActionType.OPEN_URL) {
            val url = command.text ?: return false
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.e("AetherAS", "Failed to open URL: $url")
                return false
            }
        }
        
        // Handle coordinate-based TAP/LONG_TAP if nodeId is missing
        if (command.nodeId == null && command.x != null && command.y != null) {
            val x = command.x
            val y = command.y
            return when (command.type) {
                ActionType.TAP -> performClick(x, y)
                ActionType.LONG_TAP -> performClick(x, y, duration = 1000L)
                ActionType.SWIPE -> performSwipe(x, y, command.x2 ?: 0f, command.y2 ?: 0f)
                else -> false
            }
        }

        if (command.type == ActionType.SWIPE) {
            return performSwipe(command.x ?: 0f, command.y ?: 0f, command.x2 ?: 0f, command.y2 ?: 0f)
        }

        val root = getRootNode() ?: return false
        val targetNodeId = command.nodeId ?: return false
        try {
            val node = findNodeById(root, targetNodeId) ?: return false
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
                else -> false
            }
            return result
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun performClick(x: Float, y: Float, duration: Long = 100L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build()
        return dispatchGesture(gesture, null, null)
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

    suspend fun takeScreenshotAsBase64(): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    if (bitmap == null) {
                        continuation.resume(null)
                        return
                    }
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                    val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    continuation.resume(base64)
                }
                override fun onFailure(errorCode: Int) {
                    Log.e("AetherAS", "Screenshot failed: $errorCode")
                    continuation.resume(null)
                }
            })
        }
    }

    companion object {
        var instance: AetherAccessibilityService? = null
        var lastActivePackage: String = "unknown"
        fun isRunning(): Boolean = instance != null
        val observationFlow = MutableSharedFlow<FullObservation>(
            replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
}
