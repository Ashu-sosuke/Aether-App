package com.aether.client.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class OutboundMessage {
    abstract val type: String
    abstract val taskId: String
}

@Serializable
data class ObservationMessage(
    override val type: String = "observation",
    override val taskId: String = "",
    val payload: ObservationPayload = ObservationPayload()
) : OutboundMessage()

@Serializable
data class ObservationPayload(
    val nodes: List<NodeData> = emptyList(),
    val activePackage: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0
)

@Serializable
data class AckMessage(
    override val type: String = "ack",
    override val taskId: String = "",
    val payload: AckPayload = AckPayload()
) : OutboundMessage()

@Serializable
data class AckPayload(
    val actionId: String = "",
    val status: String = "failed"
)

@Serializable
data class HitlResponseMessage(
    override val type: String = "hitl_response",
    override val taskId: String = "",
    val payload: HitlResponsePayload = HitlResponsePayload()
) : OutboundMessage()

@Serializable
data class HitlResponsePayload(
    val approved: Boolean = false
)

@Serializable
data class StartTaskMessage(
    override val type: String = "start_task",
    override val taskId: String = "",
    val payload: StartTaskPayload = StartTaskPayload()
) : OutboundMessage()

@Serializable
data class StartTaskPayload(
    val goal: String = "",
    val userId: String = "user_default"
)

@Serializable
data class InboundMessage(
    val type: String = "",
    val taskId: String = "",
    val payload: JsonElement = JsonObject(emptyMap())
)

@Serializable
data class CommandPayload(
    val action: ActionCommand = ActionCommand()
)

@Serializable
data class HitlRequestPayload(
    val description: String = ""
)

@Serializable
data class StatusPayload(
    val status: String = "",
    val message: String = ""
)
