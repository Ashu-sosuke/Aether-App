package com.aether.client.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class OutboundMessage {
    abstract val taskId: String
}

@Serializable
@SerialName("observation")
data class ObservationMessage(
    override val taskId: String,
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
@SerialName("ack")
data class AckMessage(
    override val taskId: String,
    val payload: AckPayload = AckPayload()
) : OutboundMessage()

@Serializable
data class AckPayload(
    val actionId: String = "",
    val status: String = "failed"
)

@Serializable
@SerialName("hitl_response")
data class HitlResponseMessage(
    override val taskId: String,
    val payload: HitlResponsePayload = HitlResponsePayload()
) : OutboundMessage()

@Serializable
data class HitlResponsePayload(
    val approved: Boolean = false
)

@Serializable
@SerialName("start_task")
data class StartTaskMessage(
    override val taskId: String,
    val payload: StartTaskPayload = StartTaskPayload()
) : OutboundMessage()

@Serializable
data class StartTaskPayload(
    val goal: String = "",
    val userId: String = "user_default"
)

@Serializable
@SerialName("stop_task")
data class StopTaskMessage(
    override val taskId: String,
    val payload: JsonObject = JsonObject(emptyMap())
) : OutboundMessage()

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
