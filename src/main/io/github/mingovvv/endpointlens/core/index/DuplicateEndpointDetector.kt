package mingovvv.endpointlens.core.index

import mingovvv.endpointlens.core.model.EndpointDuplicate
import mingovvv.endpointlens.core.model.HttpEndpoint
import mingovvv.endpointlens.core.normalize.PathNormalizer

object DuplicateEndpointDetector {
    fun detect(endpoints: List<HttpEndpoint>): List<EndpointDuplicate> {
        return endpoints
            .groupBy { keyOf(it) }
            .filterValues { it.size > 1 }
            .map { (key, items) -> EndpointDuplicate(key, items) }
            .sortedBy { it.key }
    }

    fun keyOf(endpoint: HttpEndpoint): String {
        return "${endpoint.httpMethod.trim().uppercase()} ${PathNormalizer.normalize(endpoint.fullPath)}"
    }
}

