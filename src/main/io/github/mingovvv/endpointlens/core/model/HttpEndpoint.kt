package mingovvv.endpointlens.core.model

enum class EndpointConfidence {
    HIGH,
    MEDIUM,
    LOW
}

data class HttpEndpoint(
    val httpMethod: String,
    val fullPath: String,
    val controllerFqn: String,
    val methodName: String,
    val responseType: String? = null,
    val sourceFile: String,
    val line: Int,
    val consumes: List<String> = emptyList(),
    val produces: List<String> = emptyList(),
    val params: List<String> = emptyList(),
    val headers: List<String> = emptyList(),
    val confidence: EndpointConfidence = EndpointConfidence.MEDIUM,
    val limitations: List<String> = emptyList()
)

data class EndpointDuplicate(
    val key: String,
    val endpoints: List<HttpEndpoint>
)
