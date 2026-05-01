package com.aether.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aether.client.R
import com.aether.client.data.model.TaskStatus
import com.aether.client.ui.components.ActionLogList
import com.aether.client.ui.components.ConfirmationDialog
import com.aether.client.ui.components.StatusBanner
import com.aether.client.ui.viewmodel.HitlUiState
import com.aether.client.websocket.AetherWebSocketClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionState: AetherWebSocketClient.ConnectionState,
    taskStatus: TaskStatus,
    actionLog: List<String>,
    hitlRequest: HitlUiState?,
    errorMessage: String?,
    onConnect: () -> Unit,
    onRunTask: (String) -> Unit,
    onApproveHitl: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    var goal by rememberSaveable { mutableStateOf("") }
    val isConnected = connectionState is AetherWebSocketClient.ConnectionState.CONNECTED
    val canRun = isConnected && taskStatus == TaskStatus.IDLE && goal.isNotBlank()
    val submit = {
        if (canRun) {
            onRunTask(goal)
            goal = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConnectionChip(connectionState = connectionState, onConnect = onConnect)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    placeholder = { Text(stringResource(R.string.goal_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = submit, enabled = canRun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.run))
                }
            }

            StatusBanner(status = taskStatus, errorMessage = errorMessage)

            Text(
                text = stringResource(R.string.action_log),
                style = MaterialTheme.typography.titleMedium
            )
            ActionLogList(
                entries = actionLog,
                emptyText = stringResource(R.string.no_actions_yet),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    hitlRequest?.let { request ->
        ConfirmationDialog(
            description = request.description,
            onApprove = { onApproveHitl(request.taskId, true) },
            onDeny = { onApproveHitl(request.taskId, false) }
        )
    }
}

@Composable
private fun ConnectionChip(
    connectionState: AetherWebSocketClient.ConnectionState,
    onConnect: () -> Unit
) {
    val (label, icon) = when (connectionState) {
        AetherWebSocketClient.ConnectionState.DISCONNECTED ->
            stringResource(R.string.connection_disconnected) to Icons.Filled.CloudOff
        AetherWebSocketClient.ConnectionState.CONNECTING ->
            stringResource(R.string.connection_connecting) to Icons.Filled.Sync
        AetherWebSocketClient.ConnectionState.CONNECTED ->
            stringResource(R.string.connection_connected) to Icons.Filled.CloudDone
        is AetherWebSocketClient.ConnectionState.ERROR ->
            stringResource(R.string.connection_error) to Icons.Filled.Error
    }
    AssistChip(
        onClick = {
            if (connectionState is AetherWebSocketClient.ConnectionState.DISCONNECTED ||
                connectionState is AetherWebSocketClient.ConnectionState.ERROR
            ) {
                onConnect()
            }
        },
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) }
    )
}
