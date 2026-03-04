package mingovvv.endpointlens.idea.extractor

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.extension
import mingovvv.endpointlens.core.model.HttpEndpoint

class ProjectSourceScanner(
    private val fileExtractor: SourceFileEndpointExtractor = SourceFileEndpointExtractor()
) {
    fun scan(root: Path): List<HttpEndpoint> {
        if (!Files.exists(root) || !Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { stream ->
            val files = stream
                .filter { Files.isRegularFile(it) }
                .filter { path -> path.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .collect(Collectors.toList())
            return files.flatMap { fileExtractor.extractFromFile(it) }
        }
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("kt", "java")
    }
}

