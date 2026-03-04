package mingovvv.endpointlens.core.model

data class RawRequestMapping(
    val paths: List<String> = listOf(""),
    val methods: List<String> = listOf("ALL"),
    val consumes: List<String> = emptyList(),
    val produces: List<String> = emptyList(),
    val params: List<String> = emptyList(),
    val headers: List<String> = emptyList(),
    val limitations: List<String> = emptyList()
)

