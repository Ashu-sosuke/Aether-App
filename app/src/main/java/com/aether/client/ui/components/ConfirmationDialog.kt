package com.aether.client.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.aether.client.R

@Composable
fun ConfirmationDialog(
    description: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.hitl_title)) },
        text = { Text(description) },
        confirmButton = {
            Button(onClick = onApprove) { Text(stringResource(R.string.approve)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.deny)) }
        }
    )
}
