package com.aether.client.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aether.client.R
import com.aether.client.data.model.TaskStatus
import com.aether.client.ui.theme.AetherAmber
import com.aether.client.ui.theme.AetherGreen
import com.aether.client.ui.theme.AetherGrey
import com.aether.client.ui.theme.AetherPurple
import com.aether.client.ui.theme.AetherRed
import com.aether.client.ui.theme.AetherTeal

@Composable
fun StatusBanner(
    status: TaskStatus,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(180)) togetherWith
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(180))
        },
        label = "status_banner",
        modifier = modifier
    ) { current ->
        val color = when (current) {
            TaskStatus.IDLE -> AetherGrey
            TaskStatus.CONNECTING -> AetherAmber
            TaskStatus.THINKING -> AetherPurple
            TaskStatus.EXECUTING -> AetherTeal
            TaskStatus.AWAITING_APPROVAL -> AetherAmber
            TaskStatus.DONE -> AetherGreen
            TaskStatus.ERROR -> AetherRed
        }
        val icon = when (current) {
            TaskStatus.IDLE -> Icons.Filled.RadioButtonUnchecked
            TaskStatus.CONNECTING -> Icons.Filled.HourglassTop
            TaskStatus.THINKING -> Icons.Filled.Psychology
            TaskStatus.EXECUTING -> Icons.Filled.TouchApp
            TaskStatus.AWAITING_APPROVAL -> Icons.Filled.PauseCircle
            TaskStatus.DONE -> Icons.Filled.CheckCircle
            TaskStatus.ERROR -> Icons.Filled.Error
        }
        val label = when (current) {
            TaskStatus.IDLE -> stringResource(R.string.ready)
            TaskStatus.CONNECTING -> stringResource(R.string.connection_connecting)
            TaskStatus.THINKING -> stringResource(R.string.analysing_request)
            TaskStatus.EXECUTING -> stringResource(R.string.executing_actions)
            TaskStatus.AWAITING_APPROVAL -> stringResource(R.string.waiting_for_approval)
            TaskStatus.DONE -> stringResource(R.string.task_completed)
            TaskStatus.ERROR -> errorMessage ?: stringResource(R.string.task_error)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Text(label, color = Color.Unspecified, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
