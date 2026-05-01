package com.aether.client.accessibility

import com.aether.client.data.model.NodeData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object NodeSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(nodes: List<NodeData>): String =
        json.encodeToString(ListSerializer(NodeData.serializer()), nodes)

    fun deserialize(jsonString: String): List<NodeData> =
        json.decodeFromString(ListSerializer(NodeData.serializer()), jsonString)
}
