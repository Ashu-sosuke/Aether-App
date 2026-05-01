package com.aether.client.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ActionType {
    TAP, LONG_TAP, TYPE, SCROLL_UP, SCROLL_DOWN, SWIPE, BACK, HOME
}
