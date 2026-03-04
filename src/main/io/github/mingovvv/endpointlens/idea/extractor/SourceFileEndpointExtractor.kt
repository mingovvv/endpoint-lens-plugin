package mingovvv.endpointlens.idea.extractor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import mingovvv.endpointlens.core.model.HttpEndpoint

class SourceFileEndpointExtractor(
    private val delegate: HttpEndpointExtractor = SpringMappingExtractor()
) {
    fun extractFromFile(path: Path): List<HttpEndpoint> {
        val ext = path.extension.lowercase()
        if (ext != "kt" && ext != "java") return emptyList()
        if (!Files.exists(path) || !Files.isRegularFile(path)) return emptyList()

        val source = Files.readString(path)
        return delegate.extractFromSource(source, path.toString())
    }
}

