package com.aether.client.websocket

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.aether.client.accessibility.AetherAccessibilityService
import com.aether.client.data.datastore.SettingsDataStore
import com.aether.client.overlay.OverlayManager
import com.aether.client.data.model.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AetherWebSocketClient @Inject constructor(
    private val context: Context,
    private val overlayManager: OverlayManager,
    private val settingsDs: SettingsDataStore
) {
    private val client = HttpClient(OkHttp) {
        install(WebSockets) { 
            pingInterval = 15_000 
            maxFrameSize = 1024 * 1024 * 10 // 10MB to support large UI trees
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendChannel = Channel<OutboundMessage>(capacity = Channel.UNLIMITED)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var session: DefaultClientWebSocketSession? = null
    var activeTaskId: String? = null
        private set
    private var isStreaming = false
    private var observationJob: Job? = null

    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val inboundMessages = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 16)

    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object CONNECTING : ConnectionState()
        object CONNECTED : ConnectionState()
        data class ERROR(val msg: String) : ConnectionState()
    }

    private suspend fun wakeUpServer(baseUrl: String) {
        val httpUrl = baseUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")
        val pingUrl = if (httpUrl.endsWith("/")) "${httpUrl}ping" else "$httpUrl/ping"
        
        Log.d("AetherWS", "Waking up server: $pingUrl")
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        repeat(5) { attempt ->
            try {
                val request = Request.Builder().url(pingUrl).build()
                val response = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute()
                }
                if (response.isSuccessful) {
                    Log.d("AetherWS", "Server is awake!")
                    return
                }
            } catch (e: Exception) {
                Log.w("AetherWS", "Ping attempt ${attempt + 1} failed: ${e.message}")
                delay(3000L * (attempt + 1))
            }
        }
        Log.e("AetherWS", "Server did not wake up after 5 attempts")
    }

    suspend fun connect(url: String) {
        if (connectionState.value == ConnectionState.CONNECTING || 
            connectionState.value == ConnectionState.CONNECTED) return

        connectionState.value = ConnectionState.CONNECTING
        
        var cleanUrl = url.trim()
        if (!cleanUrl.startsWith("ws://") && !cleanUrl.startsWith("wss://")) {
            cleanUrl = if (cleanUrl.startsWith("https://")) {
                cleanUrl.replace("https://", "wss://")
            } else if (cleanUrl.startsWith("http://")) {
                cleanUrl.replace("http://", "ws://")
            } else {
                "wss://$cleanUrl"
            }
        }

        // Wake up server before WebSocket connection (Bug 10)
        wakeUpServer(cleanUrl)

        serviceScope.launch {
            try {
                val finalWsUrl = if (!cleanUrl.endsWith("/ws")) {
                    if (cleanUrl.endsWith("/")) "${cleanUrl}ws" else "$cleanUrl/ws"
                } else cleanUrl

                Log.d("WS", "Connecting to: $finalWsUrl")
                client.webSocket(finalWsUrl) {
                    session = this
                    connectionState.value = ConnectionState.CONNECTED

                    // Coroutine-safe Send Loop (Bug 4)
                    val senderJob = launch {
                        for (msg in sendChannel) {
                            try {
                                val text = json.encodeToString(msg)
                                send(Frame.Text(text))
                            } catch (e: Exception) {
                                Log.e("WS", "Send failed: ${e.message}")
                            }
                        }
                    }

                    // Reader loop
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleInbound(frame.readText())
                            }
                        }
                    } finally {
                        senderJob.cancel()
                    }
                }
            } catch (e: Exception) {
                Log.e("WS", "Connection error: ${e.message}")
                connectionState.value = ConnectionState.ERROR(e.message ?: "Unknown Error")
            } finally {
                connectionState.value = ConnectionState.DISCONNECTED
                session = null
            }
        }
    }

    private suspend fun handleInbound(text: String) {
        try {
            val msg = json.decodeFromString<InboundMessage>(text)
            inboundMessages.tryEmit(msg)
            if (msg.type == "command") {
                val payload = json.decodeFromJsonElement<CommandPayload>(msg.payload)
                
                // Show ripple
                payload.action.x?.let { x ->
                    payload.action.y?.let { y ->
                        overlayManager.showTap(x, y)
                    }
                }

                // Check service instance (Bug 8)
                val service = AetherAccessibilityService.instance
                if (service == null) {
                    Log.e("AetherWS", "AccessibilityService is null")
                    send(AckMessage(taskId = msg.taskId, payload = AckPayload(payload.action.actionId, "failed")))
                    return
                }

                val success = service.performAction(payload.action)
                send(AckMessage(taskId = msg.taskId, payload = AckPayload(payload.action.actionId, if (success) "success" else "failed")))
            } else if (msg.type == "task_complete" || msg.type == "task_failed") {
                stopStreaming()
            }
        } catch (e: Exception) { Log.e("WS", "Inbound Error: ${e.message}") }
    }

    suspend fun startTask(goal: String): String {
        val taskId = UUID.randomUUID().toString()
        activeTaskId = taskId
        isStreaming = true

        // 1. Send start_task
        send(StartTaskMessage(
            taskId  = taskId,
            payload = StartTaskPayload(goal = goal, userId = getUserIdSync())
        ))
        Log.d("AetherWS", "start_task sent — taskId=$taskId goal='$goal'")

        // 2. Immediately force-send cached node tree (Bug 3)
        val cachedNodes = AetherAccessibilityService.nodeTreeFlow.replayCache.firstOrNull()
        if (cachedNodes != null && cachedNodes.isNotEmpty()) {
            Log.d("AetherWS", "Force-sending initial observation: ${cachedNodes.size} nodes")
            send(ObservationMessage(
                taskId  = taskId,
                payload = ObservationPayload(
                    nodes         = cachedNodes,
                    activePackage = getActivePackage(),
                    screenWidth   = getScreenWidth(),
                    screenHeight  = getScreenHeight()
                )
            ))
        } else {
            Log.w("AetherWS", "No cached node tree yet — will send on next event")
        }

        // 3. Start continuous stream
        startStreamingObservations(taskId)

        // 4. Force a scrape after a short delay to account for the app minimizing
        serviceScope.launch {
            delay(1000L) // Wait for Home screen to be in focus
            AetherAccessibilityService.instance?.forceScrape()
        }

        return taskId
    }

    private fun startStreamingObservations(taskId: String) {
        observationJob?.cancel()
        observationJob = serviceScope.launch {
            var lastSentMs = 0L
            AetherAccessibilityService.nodeTreeFlow.collect { nodes ->
                if (!isStreaming || activeTaskId != taskId) return@collect
                val now = System.currentTimeMillis()
                if (now - lastSentMs < 500) return@collect   // throttle 500ms
                lastSentMs = now
                Log.d("AetherWS", "Streaming observation: ${nodes.size} nodes")
                try {
                    send(ObservationMessage(
                        taskId  = taskId,
                        payload = ObservationPayload(
                            nodes         = nodes,
                            activePackage = getActivePackage(),
                            screenWidth   = getScreenWidth(),
                            screenHeight  = getScreenHeight()
                        )
                    ))
                } catch (e: Exception) {
                    Log.e("AetherWS", "Observation send failed: ${e.message}")
                }
            }
        }
    }

    fun stopStreaming() {
        isStreaming = false
        activeTaskId = null
        observationJob?.cancel()
        observationJob = null
    }

    suspend fun stopTask(taskId: String) {
        send(StopTaskMessage(taskId = taskId))
        stopStreaming()
    }

    suspend fun sendHitlResponse(taskId: String, approved: Boolean) {
        send(HitlResponseMessage(taskId = taskId, payload = HitlResponsePayload(approved = approved)))
    }

    private suspend fun send(message: OutboundMessage) {
        sendChannel.send(message)
    }

    private fun getUserIdSync(): String {
        return "user_default"
    }

    private fun getActivePackage(): String {
        return AetherAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString() ?: "unknown"
    }

    private fun getScreenWidth(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.width()
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.width
            }
        } catch (e: Exception) { 1080 }
    }

    private fun getScreenHeight(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.height
            }
        } catch (e: Exception) { 1920 }
    }
}