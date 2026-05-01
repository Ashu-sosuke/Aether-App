package com.aether.client.data.model

import kotlinx.serialization.Serializable

@Serializable
data class NodeData(
    val nodeId: String = "",
    val className: String = "",
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val isVisible: Boolean = true,
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val depth: Int = 0,
    val childCount: Int = 0
)
