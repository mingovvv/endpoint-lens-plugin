package mingovvv.endpointlens.idea.extractor

import mingovvv.endpointlens.core.model.HttpEndpoint

interface HttpEndpointExtractor {
    fun extractFromSource(source: String, sourceFile: String): List<HttpEndpoint>
}

