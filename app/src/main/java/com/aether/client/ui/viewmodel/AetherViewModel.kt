package com.aether.client.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aether.client.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.client.data.datastore.SettingsDataStore
import com.aether.client.data.model.CommandPayload
import com.aether.client.data.model.HitlRequestPayload
import com.aether.client.data.model.InboundMessage
import com.aether.client.data.model.StatusPayload
import com.aether.client.data.model.TaskStatus
import com.aether.client.overlay.OverlayManager
import com.aether.client.websocket.AetherWebSocketClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

data class HitlUiState(
    val taskId: String,
    val description: String
)

@HiltViewModel
class AetherViewModel @Inject constructor(
    private val wsClient: AetherWebSocketClient,
    private val overlayMgr: OverlayManager,
    private val settingsDs: SettingsDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val taskStatus = MutableStateFlow(TaskStatus.IDLE)
    val actionLog = MutableStateFlow<List<String>>(emptyList())
    val hitlRequest = MutableStateFlow<HitlUiState?>(null)
    val errorMessage = MutableStateFlow<String?>(null)
    val serverUrl = settingsDs.serverUrl
    val alwaysConfirm = settingsDs.alwaysConfirm
    val tokenBalance = MutableStateFlow(100)
    val connectionState = wsClient.connectionState

    private val json = Json { ignoreUnknownKeys = true }
    private var inboundJob: Job? = null
    private val handler = CoroutineExceptionHandler { _, throwable ->
        errorMessage.value = throwable.message
        taskStatus.value = TaskStatus.ERROR
        appendLog(context.getString(R.string.task_failed_log, throwable.message ?: context.getString(R.string.unknown_error)))
    }

    fun connect() {
        viewModelScope.launch(handler) {
            taskStatus.value = TaskStatus.CONNECTING
            val url = serverUrl.first()
            wsClient.connect(url)
            taskStatus.value = TaskStatus.IDLE
            collectInboundMessages()
        }
    }

    fun runTask(goal: String) {
        viewModelScope.launch(handler) {
            val trimmedGoal = goal.trim()
            if (trimmedGoal.isBlank()) return@launch
            try {
                if (wsClient.connectionState.value !is AetherWebSocketClient.ConnectionState.CONNECTED) {
                    errorMessage.value = "Not connected to Brain. Please check your URL and try again."
                    taskStatus.value = TaskStatus.ERROR
                    return@launch
                }

                taskStatus.value = TaskStatus.THINKING
                val taskId = wsClient.startTask(trimmedGoal)
                appendLog(context.getString(R.string.task_started_log, trimmedGoal, taskId))
                
                // Show floating stop button
                overlayMgr.showStopButton {
                    stopTask()
                }
                
                // Auto-minimize the app to trigger accessibility events in other apps
                val startMain = Intent(Intent.ACTION_MAIN)
                startMain.addCategory(Intent.CATEGORY_HOME)
                startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(startMain)
                
            } catch (e: Exception) {
                Log.e("AetherVM", "Failed to start task: ${e.message}", e)
                errorMessage.value = "Connection failed: ${e.message}"
                taskStatus.value = TaskStatus.ERROR
            }
        }
    }

    fun approveHitl(taskId: String, approved: Boolean) {
        viewModelScope.launch(handler) {
            wsClient.sendHitlResponse(taskId, approved)
            hitlRequest.value = null
            taskStatus.value = if (approved) TaskStatus.EXECUTING else TaskStatus.IDLE
        }
    }

    fun stopTask() {
        viewModelScope.launch(handler) {
            val taskId = wsClient.activeTaskId
            if (taskId != null) {
                wsClient.stopTask(taskId)
                appendLog("Task stopped by user.")
                taskStatus.value = TaskStatus.IDLE
                overlayMgr.hideStopButton()
            }
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch(handler) { settingsDs.setServerUrl(url) }
    }

    fun updateAlwaysConfirm(value: Boolean) {
        viewModelScope.launch(handler) { settingsDs.setAlwaysConfirm(value) }
    }

    fun hasOverlayPermission(): Boolean = overlayMgr.hasOverlayPermission()

    private fun collectInboundMessages() {
        if (inboundJob?.isActive == true) return
        inboundJob = viewModelScope.launch(handler) {
            wsClient.inboundMessages.collect { msg ->
                when (msg.type) {
                    "hitl_required" -> {
                        val p = json.decodeFromJsonElement<HitlRequestPayload>(msg.payload)
                        hitlRequest.value = HitlUiState(msg.taskId, p.description)
                        taskStatus.value = TaskStatus.AWAITING_APPROVAL
                    }
                    "command" -> {
                        taskStatus.value = TaskStatus.EXECUTING
                        val p = json.decodeFromJsonElement<CommandPayload>(msg.payload)
                        appendLog(context.getString(R.string.action_log_entry, p.action.type.name, p.action.nodeId))
                    }
                    "task_complete" -> {
                        taskStatus.value = TaskStatus.DONE
                        appendLog(context.getString(R.string.task_completed_log))
                        overlayMgr.hideStopButton()
                    }
                    "task_failed" -> {
                        taskStatus.value = TaskStatus.ERROR
                        val p = json.decodeFromJsonElement<StatusPayload>(msg.payload)
                        errorMessage.value = p.message
                        appendLog(context.getString(R.string.task_failed_log, p.message))
                        overlayMgr.hideStopButton()
                    }
                    "token_update" -> {
                        val p = json.decodeFromJsonElement<StatusPayload>(msg.payload)
                        tokenBalance.value = p.message.toIntOrNull() ?: tokenBalance.value
                    }
                }
            }
        }
    }

    private fun appendLog(entry: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        actionLog.value = (listOf("[$ts] $entry") + actionLog.value).take(50)
    }
}
