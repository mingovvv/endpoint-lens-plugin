package mingovvv.endpointlens.idea.preview.model

import mingovvv.endpointlens.core.model.EndpointConfidence

data class EndpointStructurePreview(
    val request: StructureNode?,
    val response: StructureNode?,
    val confidence: EndpointConfidence,
    val limitations: List<String> = emptyList()
)

