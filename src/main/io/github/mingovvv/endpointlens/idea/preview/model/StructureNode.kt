package mingovvv.endpointlens.idea.preview.model

data class StructureNode(
    val name: String,
    val type: String,
    val children: List<StructureNode> = emptyList()
)

