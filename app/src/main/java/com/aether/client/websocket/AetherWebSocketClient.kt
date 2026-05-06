package com.aether.client.websocket

import android.content.Context
import android.content.Intent
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
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AetherWebSocketClient @Inject constructor(
    private val context: Context,
    private val overlayManager: OverlayManager,
    private val settingsDs: SettingsDataStore
) {
    private val dnsResolver: Dns by lazy {
        val bootstrapClient = OkHttpClient.Builder().build()
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4")
            ))
            .build()
    }

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                dns(dnsResolver)
            }
        }
        install(WebSockets) {
            pingInterval = 15_000
            maxFrameSize = 1024 * 1024 * 10 // 10MB
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
    private val isConnecting = AtomicBoolean(false)

    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val inboundMessages = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 16)

    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object CONNECTING : ConnectionState()
        object CONNECTED : ConnectionState()
        data class ERROR(val msg: String) : ConnectionState()
    }

    private suspend fun wakeUpServer(baseUrl: String) {
        val httpUrl = baseUrl.replace("wss://", "https://").replace("ws://", "http://")
        val pingUrl = if (httpUrl.endsWith("/")) "${httpUrl}ping" else "$httpUrl/ping"
        Log.d("AetherWS", "Waking up server: $pingUrl")
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .dns(dnsResolver)
            .build()

        repeat(5) { attempt ->
            try {
                val request = Request.Builder().url(pingUrl).build()
                val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
                if (response.isSuccessful) {
                    Log.d("AetherWS", "Server is awake!")
                    return
                }
            } catch (e: Exception) {
                Log.w("AetherWS", "Ping attempt ${attempt + 1} failed: ${e.javaClass.simpleName} - ${e.message}")
                delay(3000L * (attempt + 1))
            }
        }
    }

    suspend fun connect(url: String) {
        if (connectionState.value == ConnectionState.CONNECTING ||
            connectionState.value == ConnectionState.CONNECTED) return
        if (!isConnecting.compareAndSet(false, true)) return

        try {
            connectionState.value = ConnectionState.CONNECTING
            var cleanUrl = url.trim()
            if (!cleanUrl.startsWith("ws://") && !cleanUrl.startsWith("wss://")) {
                cleanUrl = when {
                    cleanUrl.startsWith("https://") -> cleanUrl.replaceFirst("https://", "wss://")
                    cleanUrl.startsWith("http://") -> cleanUrl.replaceFirst("http://", "ws://")
                    else -> "wss://$cleanUrl"
                }
            }
            wakeUpServer(cleanUrl)

            serviceScope.launch {
                try {
                    val finalWsUrl = if (!cleanUrl.endsWith("/ws")) {
                        if (cleanUrl.endsWith("/")) "${cleanUrl}ws" else "$cleanUrl/ws"
                    } else cleanUrl

                    client.webSocket(finalWsUrl) {
                        session = this
                        connectionState.value = ConnectionState.CONNECTED
                        val senderJob = launch {
                            for (msg in sendChannel) {
                                try {
                                    val text = json.encodeToString(msg)
                                    send(Frame.Text(text))
                                } catch (e: Exception) { Log.e("WS", "Send failed") }
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) handleInbound(frame.readText())
                            }
                        } finally { senderJob.cancel() }
                    }
                } catch (e: Exception) {
                    connectionState.value = ConnectionState.ERROR(e.message ?: "Unknown Error")
                } finally {
                    connectionState.value = ConnectionState.DISCONNECTED
                    session = null
                }
            }
        } finally { isConnecting.set(false) }
    }

    private suspend fun handleInbound(text: String) {
        try {
            val msg = json.decodeFromString<InboundMessage>(text)
            inboundMessages.tryEmit(msg)
            if (msg.type == "command") {
                val payload = json.decodeFromJsonElement<CommandPayload>(msg.payload)
                payload.action.x?.let { x -> payload.action.y?.let { y -> overlayManager.showTap(x, y) } }
                val service = AetherAccessibilityService.instance
                if (service == null) {
                    send(AckMessage(taskId = msg.taskId, payload = AckPayload(payload.action.actionId, "failed")))
                    return
                }
                val success = service.performAction(payload.action)
                send(AckMessage(taskId = msg.taskId, payload = AckPayload(payload.action.actionId, if (success) "success" else "failed")))
                
                // Proactively trigger a scrape after the action to ensure the server gets the new state
                serviceScope.launch {
                    delay(800) // Small delay for UI animation/settle
                    service.forceScrapeNow()
                }
            } else if (msg.type == "task_complete" || msg.type == "task_failed") {
                stopStreaming()
            }
        } catch (e: Exception) { Log.e("WS", "Inbound Error") }
    }

    suspend fun startTask(goal: String): String {
        val taskId = UUID.randomUUID().toString()
        activeTaskId = taskId
        isStreaming = true

        // 1. Send start_task message immediately
        send(StartTaskMessage(
            taskId  = taskId,
            payload = StartTaskPayload(goal = goal, userId = "user_default")
        ))
        Log.d("AetherWS", "start_task sent — taskId=$taskId goal='$goal'")

        // 2. Start streaming observations
        startStreamingObservations(taskId)

        // 3. Trigger initial scrape immediately
        serviceScope.launch {
            AetherAccessibilityService.instance?.forceScrapeNow()
        }

        return taskId
    }

    private fun startStreamingObservations(taskId: String) {
        observationJob?.cancel()
        observationJob = serviceScope.launch {
            var lastSentMs = 0L
            AetherAccessibilityService.observationFlow.collect { obs ->
                if (!isStreaming || activeTaskId != taskId) return@collect
                
                // Allow 'blind' type messages to pass through even if nodes/screenshot are empty
                if (obs.nodes.isEmpty() && obs.screenshot == null && obs.type == "observation") return@collect
                
                val now = System.currentTimeMillis()
                if (now - lastSentMs < 500) return@collect
                lastSentMs = now
                
                Log.d("AetherWS", "Streaming observation: ${obs.nodes.size} nodes, type: ${obs.type}")
                try {
                    send(ObservationMessage(
                        taskId  = taskId,
                        payload = ObservationPayload(
                            nodes         = obs.nodes,
                            activePackage = AetherAccessibilityService.lastActivePackage,
                            screenWidth   = getScreenWidth(),
                            screenHeight  = getScreenHeight(),
                            screenshot    = obs.screenshot,
                            type          = obs.type,
                            reason        = obs.reason
                        )
                    ))
                } catch (e: Exception) { Log.e("AetherWS", "Obs send failed") }
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

    private fun getScreenWidth(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.width()
            } else { wm.defaultDisplay.width }
        } catch (e: Exception) { 1080 }
    }

    private fun getScreenHeight(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.height()
            } else { wm.defaultDisplay.height }
        } catch (e: Exception) { 1920 }
    }
}
