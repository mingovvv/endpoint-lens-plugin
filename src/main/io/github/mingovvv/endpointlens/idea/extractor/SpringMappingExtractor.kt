package mingovvv.endpointlens.idea.extractor

import mingovvv.endpointlens.core.compose.EndpointComposer
import mingovvv.endpointlens.core.model.HttpEndpoint
import mingovvv.endpointlens.core.model.RawRequestMapping

/**
 * M1 extractor scaffold.
 * This uses source-text parsing so core logic can be tested before PSI/UAST wiring.
 */
class SpringMappingExtractor : HttpEndpointExtractor {
    override fun extractFromSource(source: String, sourceFile: String): List<HttpEndpoint> {
        val lines = source.lines()
        val pkg = parsePackage(lines)
        val methods = mutableListOf<HttpEndpoint>()
        val pendingAnnotations = mutableListOf<String>()

        var className = "UnknownController"
        var classMapping = RawRequestMapping()
        var idx = 0
        while (idx < lines.size) {
            val rawLine = lines[idx]
            val line = rawLine.trim()
            if (line.startsWith("@")) {
                val (annotationText, next) = readAnnotation(lines, idx)
                pendingAnnotations += annotationText
                idx = next
                continue
            }

            val classMatch = CLASS_REGEX.find(line)
            if (classMatch != null) {
                className = classMatch.groupValues[1]
                classMapping = parseMapping(pendingAnnotations) ?: RawRequestMapping()
                pendingAnnotations.clear()
                idx++
                continue
            }

            val methodName = parseMethodName(lines, idx)
            if (methodName != null) {
                if (methodName.isBlank()) {
                    pendingAnnotations.clear()
                    idx++
                    continue
                }
                val methodMapping = parseMapping(pendingAnnotations)
                pendingAnnotations.clear()
                if (methodMapping != null) {
                    val fqn = if (pkg.isBlank()) className else "$pkg.$className"
                    val limitations = (classMapping.limitations + methodMapping.limitations).distinct()
                    val responseType = runCatching { parseResponseType(lines, idx, methodName) }.getOrNull()
                    methods += EndpointComposer.compose(
                        controllerFqn = fqn,
                        methodName = methodName,
                        responseType = responseType,
                        sourceFile = sourceFile,
                        line = idx + 1,
                        classMapping = classMapping,
                        methodMapping = methodMapping,
                        confidence = ExtractorConfidencePolicy.fromLimitations(limitations)
                    )
                }
                idx++
                continue
            }

            if (line.isNotEmpty()) {
                pendingAnnotations.clear()
            }
            idx++
        }

        return methods
    }

    private fun parsePackage(lines: List<String>): String {
        return lines.asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")
            ?.removeSuffix(";")
            ?.trim()
            .orEmpty()
    }

    private fun parseMethodName(lines: List<String>, start: Int): String? {
        val single = lines[start].trim()
        METHOD_REGEX.find(single)?.let { match ->
            return match.groupValues.firstOrNull { it.isNotBlank() && it != match.value }
        }

        // Java method declarations are often split across lines.
        val joined = joinMethodDeclaration(lines, start)

        METHOD_REGEX.find(joined)?.let { match ->
            return match.groupValues.firstOrNull { it.isNotBlank() && it != match.value }
        }
        return METHOD_START_REGEX.find(single)?.groupValues?.getOrNull(1)
    }

    private fun parseResponseType(lines: List<String>, start: Int, methodName: String): String? {
        val normalized = joinMethodDeclaration(lines, start).replace('\n', ' ').trim()
        if (normalized.isBlank()) return null

        val kotlin = Regex(
            """fun\s+""" + Regex.escape(methodName) + """\s*\([^)]*\)\s*(?::\s*([A-Za-z0-9_<>\[\].?, ]+))?"""
        ).find(normalized)?.groupValues?.getOrNull(1)?.trim()
        if (!kotlin.isNullOrBlank()) return kotlin
        if (normalized.contains("fun $methodName(") && kotlin.isNullOrBlank()) return "Unit"

        val java = Regex(
            """(?:public|protected|private|static|final|synchronized|abstract|default|native|strictfp|\s)+([A-Za-z0-9_<>\[\].?, ]+)\s+""" +
                Regex.escape(methodName) + """\s*\("""
        ).find(normalized)?.groupValues?.getOrNull(1)?.trim()
        return java?.takeIf { it.isNotBlank() }
    }

    private fun joinMethodDeclaration(lines: List<String>, start: Int): String {
        return buildString {
            var i = start
            var guard = 0
            while (i < lines.size && guard < 16) {
                val part = lines[i].trim()
                if (guard > 0 && part.startsWith("@")) break
                append(part).append(' ')
                if (part.contains("{") || part.endsWith(";")) break
                i++
                guard++
            }
        }.trim()
    }

    private fun readAnnotation(lines: List<String>, start: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var depth = 0
        var idx = start

        while (idx < lines.size) {
            val part = lines[idx].trim()
            builder.append(part)
            part.forEach { ch ->
                if (ch == '(') depth++
                if (ch == ')') depth--
            }
            idx++
            if (depth <= 0) break
        }
        return builder.toString() to idx
    }

    private fun parseMapping(annotations: List<String>): RawRequestMapping? {
        val mappingAnnotations = annotations.mapNotNull { parseSingleMapping(it) }
        if (mappingAnnotations.isEmpty()) return null
        if (mappingAnnotations.size == 1) return mappingAnnotations.first()

        val first = mappingAnnotations.first()
        return first.copy(
            limitations = (first.limitations + "Multiple mapping annotations found; first one used").distinct()
        )
    }

    private fun parseSingleMapping(annotation: String): RawRequestMapping? {
        val name = ANNOTATION_NAME_REGEX.find(annotation)?.groupValues?.get(1)?.substringAfterLast('.') ?: return null
        val body = annotation.substringAfter("(", "").substringBeforeLast(")", "")

        return when (name) {
            "GetMapping" -> parseComposed(body, "GET")
            "PostMapping" -> parseComposed(body, "POST")
            "PutMapping" -> parseComposed(body, "PUT")
            "PatchMapping" -> parseComposed(body, "PATCH")
            "DeleteMapping" -> parseComposed(body, "DELETE")
            "RequestMapping" -> parseRequestMapping(body)
            else -> null
        }
    }

    private fun parseComposed(body: String, fixedMethod: String): RawRequestMapping {
        val attrs = parseAnnotationAttributes(body)
        val paths = parseStringList(attrs["path"] ?: attrs["value"]) ?: listOf("")
        val limitations = paths.filter { it.contains("\${") }.map { "Unresolved placeholder in path: $it" }
        return RawRequestMapping(
            paths = paths,
            methods = listOf(fixedMethod),
            consumes = parseStringList(attrs["consumes"]) ?: emptyList(),
            produces = parseStringList(attrs["produces"]) ?: emptyList(),
            params = parseStringList(attrs["params"]) ?: emptyList(),
            headers = parseStringList(attrs["headers"]) ?: emptyList(),
            limitations = limitations
        )
    }

    private fun parseRequestMapping(body: String): RawRequestMapping {
        val attrs = parseAnnotationAttributes(body)
        val paths = parseStringList(attrs["path"] ?: attrs["value"]) ?: listOf("")
        val methods = parseMethodList(attrs["method"]) ?: listOf("ALL")
        val limitations = paths.filter { it.contains("\${") }.map { "Unresolved placeholder in path: $it" }

        return RawRequestMapping(
            paths = paths,
            methods = methods,
            consumes = parseStringList(attrs["consumes"]) ?: emptyList(),
            produces = parseStringList(attrs["produces"]) ?: emptyList(),
            params = parseStringList(attrs["params"]) ?: emptyList(),
            headers = parseStringList(attrs["headers"]) ?: emptyList(),
            limitations = limitations
        )
    }

    private fun parseAnnotationAttributes(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()

        val tokens = splitTopLevel(body)
        val attrs = linkedMapOf<String, String>()
        tokens.forEachIndexed { index, token ->
            val eqIdx = token.indexOf('=')
            if (eqIdx < 0) {
                if (index == 0) attrs["value"] = token.trim()
            } else {
                val key = token.substring(0, eqIdx).trim()
                val value = token.substring(eqIdx + 1).trim()
                attrs[key] = value
            }
        }
        return attrs
    }

    private fun splitTopLevel(raw: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var inString = false
        var quote = '"'
        var escape = false

        for (ch in raw) {
            if (inString) {
                sb.append(ch)
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == quote) {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"', '\'' -> {
                    inString = true
                    quote = ch
                    sb.append(ch)
                }
                '(' -> {
                    depthParen++
                    sb.append(ch)
                }
                ')' -> {
                    depthParen--
                    sb.append(ch)
                }
                '{' -> {
                    depthBrace++
                    sb.append(ch)
                }
                '}' -> {
                    depthBrace--
                    sb.append(ch)
                }
                '[' -> {
                    depthBracket++
                    sb.append(ch)
                }
                ']' -> {
                    depthBracket--
                    sb.append(ch)
                }
                ',' -> {
                    if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                        out += sb.toString().trim()
                        sb.clear()
                    } else {
                        sb.append(ch)
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) {
            out += sb.toString().trim()
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun parseStringList(raw: String?): List<String>? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return listOf("")

        val inner = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed.substring(1, trimmed.length - 1)
            trimmed.startsWith("[") && trimmed.endsWith("]") -> trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
        val values = STRING_LITERAL_REGEX.findAll(inner).map { it.groupValues[1] }.toList()
        if (values.isNotEmpty()) return values

        return splitTopLevel(inner)
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }

    private fun parseMethodList(raw: String?): List<String>? {
        if (raw == null) return null
        val values = parseStringList(raw).orEmpty()
            .map { token ->
                token.substringAfterLast('.').uppercase()
            }
            .filter { it.isNotBlank() }
            .distinct()
        return if (values.isEmpty()) null else values
    }

    companion object {
        private val CLASS_REGEX = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        private val METHOD_REGEX = Regex(
            """\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(|\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?[A-Za-z0-9_<>\[\],.? ]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*(?:throws\s+[^{;]+)?\s*(?:\{|$)"""
        )
        private val METHOD_START_REGEX = Regex(
            """\b(?:public|protected|private)\b[^{;()]*\b([A-Za-z_][A-Za-z0-9_]*)\s*\($"""
        )
        private val ANNOTATION_NAME_REGEX = Regex("""@([A-Za-z0-9_.$]+)""")
        private val STRING_LITERAL_REGEX = Regex(""""([^"\\\\]*(?:\\\\.[^"\\\\]*)*)"""")
    }
}
