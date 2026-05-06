package com.aether.client.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ActionCommand(
    val actionId: String = "",
    val nodeId: String? = null,
    val type: ActionType = ActionType.TAP,
    val text: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null
)
