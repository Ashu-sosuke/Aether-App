package com.aether.client.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class ActionType {
    @SerialName("tap")
    TAP,
    @SerialName("long_tap")
    LONG_TAP,
    @SerialName("type")
    TYPE,
    @SerialName("scroll_up")
    SCROLL_UP,
    @SerialName("scroll_down")
    SCROLL_DOWN,
    @SerialName("swipe")
    SWIPE,
    @SerialName("back")
    BACK,
    @SerialName("home")
    HOME
}
