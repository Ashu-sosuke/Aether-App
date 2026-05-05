package com.aether.client.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aether.client.accessibility.AetherAccessibilityService
import com.aether.client.overlay.OverlayManager
import com.aether.client.ui.screens.HomeScreen
import com.aether.client.ui.screens.SettingsScreen
import com.aether.client.ui.theme.AetherTheme
import com.aether.client.ui.viewmodel.AetherViewModel
import com.aether.client.ui.components.PermissionRequirementDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var overlayManager: OverlayManager

    private val viewModel: AetherViewModel by viewModels()

    // Observables for permission states
    private var accessibilityEnabled by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var requestedOverlayThisLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()
            val taskStatus by viewModel.taskStatus.collectAsState()
            val actionLog by viewModel.actionLog.collectAsState()
            val hitlRequest by viewModel.hitlRequest.collectAsState()
            val errorMessage by viewModel.errorMessage.collectAsState()
            val serverUrl by viewModel.serverUrl.collectAsState(initial = "")
            val alwaysConfirm by viewModel.alwaysConfirm.collectAsState(initial = false)
            val tokenBalance by viewModel.tokenBalance.collectAsState()
            val connectionState by viewModel.connectionState.collectAsState()

            // State to handle the one-time visibility of the permission dialog
            var showPermissionDialog by remember { mutableStateOf(true) }

            AetherTheme {
                // Show permission requirement dialog if something is missing
                if (showPermissionDialog && (!accessibilityEnabled || !overlayGranted)) {
                    PermissionRequirementDialog(
                        accessibilityEnabled = accessibilityEnabled,
                        overlayGranted = overlayGranted,
                        onOpenAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenOverlay = {
                            overlayManager.requestOverlayPermission(this@MainActivity)
                        },
                        onDismiss = { showPermissionDialog = false }
                    )
                }

                NavHost(navController = navController, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            connectionState = connectionState,
                            taskStatus = taskStatus,
                            actionLog = actionLog,
                            hitlRequest = hitlRequest,
                            errorMessage = errorMessage,
                            accessibilityEnabled = accessibilityEnabled, // Pass to screen
                            overlayGranted = overlayGranted,             // Pass to screen
                            onConnect = viewModel::connect,
                            onRunTask = viewModel::runTask,
                            onApproveHitl = viewModel::approveHitl,
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            serverUrl = serverUrl,
                            alwaysConfirm = alwaysConfirm,
                            tokenBalance = tokenBalance,
                            accessibilityEnabled = accessibilityEnabled,
                            overlayGranted = overlayGranted,
                            onServerUrlChange = viewModel::updateServerUrl,
                            onAlwaysConfirmChange = viewModel::updateAlwaysConfirm,
                            onOpenAccessibilitySettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onRequestOverlayPermission = {
                                overlayManager.requestOverlayPermission(this@MainActivity)
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission states whenever the user returns to the app
        accessibilityEnabled = AetherAccessibilityService.isRunning()
        overlayGranted = overlayManager.hasOverlayPermission()

        // Auto-request overlay on first launch if missing
        if (!overlayGranted && !requestedOverlayThisLaunch) {
            requestedOverlayThisLaunch = true
            overlayManager.requestOverlayPermission(this)
        }
    }

    private object Routes {
        const val HOME = "home"
        const val SETTINGS = "settings"
    }
}