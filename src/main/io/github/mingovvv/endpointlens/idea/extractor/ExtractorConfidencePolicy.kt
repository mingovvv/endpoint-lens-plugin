package mingovvv.endpointlens.idea.extractor

import mingovvv.endpointlens.core.model.EndpointConfidence

object ExtractorConfidencePolicy {
    fun fromLimitations(limitations: List<String>): EndpointConfidence {
        return when {
            limitations.isEmpty() -> EndpointConfidence.HIGH
            limitations.any { it.contains("Multiple mapping annotations", ignoreCase = true) } -> EndpointConfidence.LOW
            else -> EndpointConfidence.MEDIUM
        }
    }
}

