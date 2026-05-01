package com.aether.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aether.client.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    alwaysConfirm: Boolean,
    tokenBalance: Int,
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    onServerUrlChange: (String) -> Unit,
    onAlwaysConfirmChange: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onBack: () -> Unit
) {
    var localUrl by remember { mutableStateOf(serverUrl) }
    LaunchedEffect(serverUrl) { localUrl = serverUrl }
    val saveUrl = { onServerUrlChange(localUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = localUrl,
                onValueChange = { localUrl = it },
                label = { Text(stringResource(R.string.server_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveUrl() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) saveUrl() }
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.always_require_confirmation))
                    Switch(checked = alwaysConfirm, onCheckedChange = onAlwaysConfirmChange)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.token_balance), style = MaterialTheme.typography.titleMedium)
                    Text(tokenBalance.coerceIn(0, 100).toString(), style = MaterialTheme.typography.headlineSmall)
                    LinearProgressIndicator(
                        progress = tokenBalance.coerceIn(0, 100) / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            PermissionCard(
                title = stringResource(R.string.accessibility_status),
                enabledText = if (accessibilityEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled),
                buttonText = stringResource(R.string.enable_accessibility),
                buttonEnabled = !accessibilityEnabled,
                onClick = onOpenAccessibilitySettings
            )
            PermissionCard(
                title = stringResource(R.string.overlay_status),
                enabledText = if (overlayGranted) stringResource(R.string.granted) else stringResource(R.string.not_granted),
                buttonText = stringResource(R.string.grant_permission),
                buttonEnabled = !overlayGranted,
                onClick = onRequestOverlayPermission
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    enabledText: String,
    buttonText: String,
    buttonEnabled: Boolean,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(enabledText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick, enabled = buttonEnabled) {
                Text(buttonText)
            }
        }
    }
}
