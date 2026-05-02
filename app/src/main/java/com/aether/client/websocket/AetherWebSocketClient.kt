package com.aether.client.websocket

import android.content.res.Resources
import com.aether.client.BuildConfig
import com.aether.client.accessibility.AetherAccessibilityService
import com.aether.client.data.datastore.SettingsDataStore
import com.aether.client.data.model.AckMessage
import com.aether.client.data.model.AckPayload
import com.aether.client.data.model.CommandPayload
import com.aether.client.data.model.HitlRequestPayload
import com.aether.client.data.model.HitlResponseMessage
import com.aether.client.data.model.HitlResponsePayload
import com.aether.client.data.model.InboundMessage
import com.aether.client.data.model.ObservationMessage
import com.aether.client.data.model.ObservationPayload
import com.aether.client.data.model.OutboundMessage
import com.aether.client.data.model.StartTaskMessage
import com.aether.client.data.model.StartTaskPayload
import com.aether.client.overlay.OverlayManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Singleton
class AetherWebSocketClient @Inject constructor(
    private val overlayManager: OverlayManager,
    private val settingsDs: SettingsDataStore
) {
    private val client = HttpClient(OkHttp) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendQueue = Channel<OutboundMessage>(Channel.BUFFERED)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "_kind"
    }

    private var session: DefaultClientWebSocketSession? = null
    private var activeTaskId: String? = null
    private var isStreaming: Boolean = false
    private var connectionJob: Job? = null
    private var streamJob: Job? = null
    private var writerJob: Job? = null

    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val inboundMessages = MutableSharedFlow<InboundMessage>(replay = 0)

    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object CONNECTING : ConnectionState()
        object CONNECTED : ConnectionState()
        data class ERROR(val message: String) : ConnectionState()
    }

    suspend fun connect(serverUrl: String) {
        disconnect()
        if (BuildConfig.USE_MOCK_WS) {
            connectionState.value = ConnectionState.CONNECTING
            delay(350L)
            connectionState.value = ConnectionState.CONNECTED
            startMockSender()
            return
        }

        var backoffMs = 1_000L
        var lastError: Throwable? = null
        repeat(5) {
            connectionState.value = ConnectionState.CONNECTING
            val connected = CompletableDeferred<Unit>()
            connectionJob = scope.launch {
                try {
                    val wsUrl = serverUrl.trim()
                        .replace("https://", "wss://")
                        .replace("http://", "ws://")
                        .trimEnd('/')
                    
                    val finalUrl = if (wsUrl.endsWith("/ws")) wsUrl else "$wsUrl/ws"
                    
                    client.webSocket(urlString = finalUrl) {
                        session = this
                        connectionState.value = ConnectionState.CONNECTED
                        writerJob = launchWriter(this)
                        connected.complete(Unit)
                        startReceiving()
                    }
                } catch (t: Throwable) {
                    lastError = t
                    if (!connected.isCompleted) connected.completeExceptionally(t)
                    connectionState.value = ConnectionState.ERROR(t.message ?: "WebSocket connection failed")
                } finally {
                    writerJob?.cancel()
                    writerJob = null
                    session = null
                    if (connectionState.value is ConnectionState.CONNECTED) {
                        connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }

            runCatching { withTimeout(10_000L) { connected.await() } }
                .onSuccess { return }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(32_000L)
        }
        connectionState.value = ConnectionState.ERROR(lastError?.message ?: "Unable to connect after retries")
    }

    private fun disconnect() {
        isStreaming = false
        activeTaskId = null
        streamJob?.cancel()
        writerJob?.cancel()
        connectionJob?.cancel()
        scope.launch { session?.close() }
        session = null
    }

    private fun launchWriter(wsSession: DefaultClientWebSocketSession): Job = scope.launch {
        for (message in sendQueue) {
            wsSession.send(Frame.Text(encodeOutbound(message)))
        }
    }

    private suspend fun startReceiving() {
        val current = session ?: return
        for (frame in current.incoming) {
            if (frame is Frame.Text) {
                val msg = json.decodeFromString<InboundMessage>(frame.readText())
                inboundMessages.emit(msg)
                handleInbound(msg)
            }
        }
    }

    private suspend fun handleInbound(msg: InboundMessage) {
        when (msg.type) {
            "command" -> {
                val payload = json.decodeFromJsonElement<CommandPayload>(msg.payload)
                val command = payload.action
                val node = AetherAccessibilityService.findCachedNodeById(command.nodeId)
                if (node != null) {
                    val cx = ((node.boundsLeft + node.boundsRight) / 2).toFloat()
                    val cy = ((node.boundsTop + node.boundsBottom) / 2).toFloat()
                    overlayManager.showTap(cx, cy)
                } else if (command.x != null && command.y != null) {
                    overlayManager.showTap(command.x, command.y)
                }

                val success = AetherAccessibilityService.instance?.performAction(command) ?: false
                sendAck(msg.taskId, command.actionId, if (success) "success" else "failed")
            }
            "hitl_required" -> {
                json.decodeFromJsonElement<HitlRequestPayload>(msg.payload)
            }
            "task_complete", "task_failed" -> {
                isStreaming = false
                activeTaskId = null
                streamJob?.cancel()
            }
        }
    }

    suspend fun send(message: OutboundMessage) {
        if (BuildConfig.USE_MOCK_WS) return
        sendQueue.send(message)
    }

    suspend fun sendAck(taskId: String, actionId: String, status: String) {
        send(AckMessage(taskId = taskId, payload = AckPayload(actionId, status)))
    }

    suspend fun sendHitlResponse(taskId: String, approved: Boolean) {
        send(HitlResponseMessage(taskId = taskId, payload = HitlResponsePayload(approved)))
        if (BuildConfig.USE_MOCK_WS) {
            val payload = buildJsonObject {
                put("status", if (approved) "done" else "cancelled")
                put("message", if (approved) "Mock approval accepted" else "Mock task denied")
            }
            inboundMessages.emit(
                InboundMessage(
                    type = if (approved) "task_complete" else "task_failed",
                    taskId = taskId,
                    payload = payload
                )
            )
        }
    }

    suspend fun startTask(goal: String): String {
        val taskId = UUID.randomUUID().toString()
        activeTaskId = taskId
        isStreaming = true
        val userId = settingsDs.ensureUserId()
        send(StartTaskMessage(taskId = taskId, payload = StartTaskPayload(goal = goal, userId = userId)))
        startStreamingObservations(taskId)
        if (BuildConfig.USE_MOCK_WS) simulateMockTask(taskId, goal)
        return taskId
    }

    private fun startStreamingObservations(taskId: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            var lastSent = 0L
            AetherAccessibilityService.nodeTreeFlow.collect { nodes ->
                if (!isStreaming || activeTaskId != taskId) return@collect
                val now = System.currentTimeMillis()
                if (now - lastSent < 500L) return@collect
                lastSent = now
                val metrics = Resources.getSystem().displayMetrics
                send(
                    ObservationMessage(
                        taskId = taskId,
                        payload = ObservationPayload(
                            nodes = nodes,
                            activePackage = AetherAccessibilityService.activePackageName,
                            screenWidth = metrics.widthPixels,
                            screenHeight = metrics.heightPixels
                        )
                    )
                )
            }
        }
    }

    private fun startMockSender() {
        scope.launch {
            while (isActive) delay(5_000L)
        }
    }

    private fun simulateMockTask(taskId: String, goal: String) {
        scope.launch {
            delay(700L)
            inboundMessages.emit(
                InboundMessage(
                    type = "token_update",
                    taskId = taskId,
                    payload = buildJsonObject {
                        put("status", "ok")
                        put("message", "97")
                    }
                )
            )
            delay(700L)
            val cachedNode = AetherAccessibilityService.lastNodeTree.firstOrNull { it.isClickable }
            if (cachedNode != null) {
                val action = com.aether.client.data.model.ActionCommand(
                    actionId = UUID.randomUUID().toString(),
                    nodeId = cachedNode.nodeId,
                    type = com.aether.client.data.model.ActionType.TAP
                )
                val command = InboundMessage(
                    type = "command",
                    taskId = taskId,
                    payload = json.encodeToJsonElement(CommandPayload(action))
                )
                inboundMessages.emit(command)
                handleInbound(command)
            }
            delay(800L)
            inboundMessages.emit(
                InboundMessage(
                    type = "hitl_required",
                    taskId = taskId,
                    payload = json.encodeToJsonElement(
                        HitlRequestPayload("Confirm completion of mock goal: $goal")
                    )
                )
            )
        }
    }

    private fun encodeOutbound(message: OutboundMessage): String = when (message) {
        is ObservationMessage -> json.encodeToString(message)
        is AckMessage -> json.encodeToString(message)
        is HitlResponseMessage -> json.encodeToString(message)
        is StartTaskMessage -> json.encodeToString(message)
    }
}
