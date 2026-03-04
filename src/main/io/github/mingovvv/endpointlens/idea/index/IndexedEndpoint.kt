package mingovvv.endpointlens.idea.index

import mingovvv.endpointlens.core.model.HttpEndpoint

data class IndexedEndpoint(
    val endpoint: HttpEndpoint,
    val moduleName: String
)

