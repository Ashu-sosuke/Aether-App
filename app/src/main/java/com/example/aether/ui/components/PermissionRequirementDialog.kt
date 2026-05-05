package com.example.aether.ui.components


import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aether.client.R

@Composable
fun PermissionRequirementDialog(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!accessibilityEnabled || !overlayGranted) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Permissions Required") },
            text = {
                val message = when {
                    !accessibilityEnabled && !overlayGranted ->
                        "Aether needs both Accessibility and Overlay permissions to see the screen and perform actions."
                    !accessibilityEnabled ->
                        "Accessibility service is off. Aether cannot see your screen or click icons without it."
                    else ->
                        "Overlay permission is missing. Aether cannot show you where it is clicking."
                }
                Text(message)
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!accessibilityEnabled) onOpenAccessibility()
                        else onOpenOverlay()
                    }
                ) {
                    Text(if (!accessibilityEnabled) "Enable Accessibility" else "Grant Overlay")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Later")
                }
            }
        )
    }
}