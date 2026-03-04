package mingovvv.endpointlens.idea.preview

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import mingovvv.endpointlens.core.model.EndpointConfidence
import mingovvv.endpointlens.core.model.HttpEndpoint
import mingovvv.endpointlens.idea.preview.model.EndpointStructurePreview
import mingovvv.endpointlens.idea.preview.model.StructureNode

class EndpointStructurePreviewService(private val project: Project) {
    private val maxDepth = 3
    private val typeFileCache = ConcurrentHashMap<String, VirtualFile?>()
    private val typeFieldsCache = ConcurrentHashMap<String, List<Pair<String, String>>>()

    fun build(endpoint: HttpEndpoint): EndpointStructurePreview {
        val limitations = mutableListOf<String>()
        val methodBlock = readMethodBlock(endpoint, limitations)

        val requestType = methodBlock?.let { extractRequestType(it, endpoint.methodName) }
        val responseType = methodBlock?.let { extractResponseType(it, endpoint.methodName) }

        val requestNode = requestType?.let { resolveTypeTree("RequestBody", it, 0, mutableSetOf(), limitations) }
        val responseNode = responseType?.let { resolveTypeTree("Response", unwrapCommonWrapper(it), 0, mutableSetOf(), limitations) }

        val confidence = when {
            limitations.isEmpty() -> EndpointConfidence.HIGH
            requestNode == null && responseNode == null -> EndpointConfidence.LOW
            else -> EndpointConfidence.MEDIUM
        }
        return EndpointStructurePreview(
            request = requestNode,
            response = responseNode,
            confidence = confidence,
            limitations = limitations.distinct()
        )
    }

    private fun readMethodBlock(endpoint: HttpEndpoint, limitations: MutableList<String>): String? {
        val file = ReadAction.compute<VirtualFile?, RuntimeException> {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(endpoint.sourceFile)
        } ?: run {
            limitations += "Source file not found"
            return null
        }
        val text = ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) }
        val lines = text.lines()
        val idx = (endpoint.line - 1).coerceIn(0, lines.lastIndex)

        var start = idx
        while (start > 0 && !lines[start].contains(endpoint.methodName)) {
            if (lines[start].trim().startsWith("@")) break
            start--
        }
        if (!lines[start].contains(endpoint.methodName)) {
            val fallback = lines.withIndex().firstOrNull { it.value.contains(endpoint.methodName) }?.index
            if (fallback != null) start = fallback
        }

        val sb = StringBuilder()
        var i = start
        var braceCount = 0
        var seenHeader = false
        var guard = 0
        while (i < lines.size && guard < 200) {
            val line = lines[i]
            sb.append(line).append('\n')
            if (line.contains(endpoint.methodName)) seenHeader = true
            line.forEach { ch ->
                if (ch == '{') braceCount++
                if (ch == '}') braceCount--
            }
            if (seenHeader && braceCount > 0 && line.contains("}")) break
            if (seenHeader && braceCount == 0 && line.trim().endsWith(";")) break
            i++
            guard++
        }
        return sb.toString()
    }

    private fun extractResponseType(methodBlock: String, methodName: String): String? {
        val normalized = methodBlock.replace('\n', ' ')
        val kotlin = Regex("""fun\s+""" + Regex.escape(methodName) + """\s*\([^)]*\)\s*(?::\s*([A-Za-z0-9_<>\[\].?, ]+))?""")
            .find(normalized)?.groupValues?.getOrNull(1)?.trim()
        if (!kotlin.isNullOrBlank()) return kotlin

        val java = Regex(
            """(?:public|protected|private|static|final|synchronized|abstract|default|native|strictfp|\s)+([A-Za-z0-9_<>\[\].?, ]+)\s+""" +
                Regex.escape(methodName) + """\s*\("""
        ).find(normalized)?.groupValues?.getOrNull(1)?.trim()
        return java?.takeIf { it.isNotBlank() && it != "void" } ?: "void"
    }

    private fun extractRequestType(methodBlock: String, methodName: String): String? {
        val normalized = methodBlock.replace('\n', ' ')
        val headerOnly = extractMethodParameterList(normalized, methodName).orEmpty()
        if (headerOnly.isBlank()) return null

        val params = splitTopLevel(headerOnly)
        for (param in params) {
            if (!param.contains("@RequestBody")) continue
            val kotlinMatch = Regex("""[A-Za-z_][A-Za-z0-9_]*\s*:\s*([A-Za-z_][A-Za-z0-9_<>.\[\]?]+)""")
                .find(param)?.groupValues?.getOrNull(1)
            if (!kotlinMatch.isNullOrBlank()) return normalizeTypeName(kotlinMatch)

            val cleaned = param
                .replace(Regex("""@\w+(?:\([^)]*\))?"""), " ")
                .replace(Regex("""\b(final|volatile|transient)\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val javaMatch = Regex("""([A-Za-z_][A-Za-z0-9_<>.\[\]?]+)\s+[A-Za-z_][A-Za-z0-9_]*$""")
                .find(cleaned)?.groupValues?.getOrNull(1)
            if (!javaMatch.isNullOrBlank()) return normalizeTypeName(javaMatch)
        }
        return null
    }

    private fun resolveTypeTree(
        label: String,
        rawType: String,
        depth: Int,
        visited: MutableSet<String>,
        limitations: MutableList<String>
    ): StructureNode {
        val type = normalizeTypeName(rawType)
        if (depth >= maxDepth || isPrimitiveLike(type)) {
            return StructureNode(name = label, type = type)
        }
        val short = shortType(type)
        if (!visited.add(short)) {
            return StructureNode(name = label, type = "$type (cycle)")
        }

        val fields = readFieldsFromType(short, limitations)
        if (fields.isEmpty()) {
            return StructureNode(name = label, type = type)
        }
        val children = fields.map { (name, childType) ->
            resolveTypeTree(name, childType, depth + 1, visited.toMutableSet(), limitations)
        }
        return StructureNode(name = label, type = type, children = children)
    }

    private fun readFieldsFromType(type: String, limitations: MutableList<String>): List<Pair<String, String>> {
        typeFieldsCache[type]?.let { return it }
        val file = findTypeFile(type) ?: return emptyList()
        val text = ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) }

        val javaRecord = Regex("""record\s+""" + Regex.escape(type) + """\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val javaRecordFields = splitTopLevel(javaRecord)
            .mapNotNull { token ->
                val cleaned = token.trim().replace(Regex("""@\w+(?:\([^)]*\))?"""), " ").replace(Regex("""\s+"""), " ")
                val m = Regex("""([A-Za-z_][A-Za-z0-9_<>.\[\]?]+)\s+([A-Za-z_][A-Za-z0-9_]*)$""").find(cleaned)
                m?.let { it.groupValues[2] to it.groupValues[1] }
            }
        if (javaRecordFields.isNotEmpty()) {
            typeFieldsCache[type] = javaRecordFields
            return javaRecordFields
        }

        val kotlinCtor = Regex("""(?:data\s+)?class\s+""" + Regex.escape(type) + """\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val ctorFields = kotlinCtor.split(',')
            .mapNotNull { token ->
                val t = token.trim()
                val m = Regex("""(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z0-9_<>\[\].?]+)""").find(t)
                m?.let { it.groupValues[1] to it.groupValues[2] }
            }
        if (ctorFields.isNotEmpty()) {
            typeFieldsCache[type] = ctorFields
            return ctorFields
        }

        val kotlinBodyFields = Regex("""(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z0-9_<>\[\].?]+)""")
            .findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        if (kotlinBodyFields.isNotEmpty()) {
            val out = kotlinBodyFields.distinctBy { it.first }
            typeFieldsCache[type] = out
            return out
        }

        val javaFields = Regex("""(?:private|protected|public)\s+([A-Za-z0-9_<>\[\].?]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*;""")
            .findAll(text).map { it.groupValues[2] to it.groupValues[1] }.toList()
        if (javaFields.isNotEmpty()) {
            typeFieldsCache[type] = javaFields
            return javaFields
        }

        limitations += "Could not resolve fields for type $type"
        typeFieldsCache[type] = emptyList()
        return emptyList()
    }

    private fun findTypeFile(type: String): VirtualFile? {
        if (typeFileCache.containsKey(type)) return typeFileCache[type]
        val candidates = setOf("$type.java", "$type.kt")
        val resolved = ReadAction.compute<VirtualFile?, RuntimeException> {
            var found: VirtualFile? = null
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (!file.isValid || file.isDirectory) return@iterateContent true
                if (file.name !in candidates) return@iterateContent true
                found = file
                false
            }
            found
        }
        typeFileCache[type] = resolved
        return resolved
    }

    private fun normalizeTypeName(type: String): String {
        return type.trim().removeSuffix("?").replace("kotlin.", "")
    }

    private fun extractMethodParameterList(normalizedMethodBlock: String, methodName: String): String? {
        val idx = normalizedMethodBlock.indexOf("$methodName(")
        if (idx < 0) return null
        var i = idx + methodName.length + 1
        var depth = 1
        val sb = StringBuilder()
        while (i < normalizedMethodBlock.length) {
            val ch = normalizedMethodBlock[i]
            if (ch == '(') depth++
            if (ch == ')') depth--
            if (depth == 0) break
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun shortType(type: String): String {
        val unwrapped = unwrapCommonWrapper(type)
        return unwrapped.substringAfterLast('.')
    }

    private fun unwrapCommonWrapper(type: String): String {
        val wrappers = listOf("ResponseEntity", "BaseResponse", "Mono", "Flux", "Optional")
        val raw = type.trim()
        val short = raw.substringAfterLast('.')
        wrappers.forEach { wrapper ->
            if (short.startsWith("$wrapper<") && short.endsWith(">")) {
                return short.substringAfter('<', short).substringBeforeLast('>')
            }
        }
        return short
    }

    private fun isPrimitiveLike(type: String): Boolean {
        val t = type.substringAfterLast('.').removeSuffix("?")
        if (t.startsWith("List<") || t.startsWith("Set<") || t.startsWith("Map<")) return true
        return t in setOf(
            "String", "Int", "Long", "Double", "Float", "Boolean", "Short", "Byte",
            "Integer", "Long", "Double", "Float", "Boolean", "BigDecimal", "LocalDate", "LocalDateTime",
            "void", "Unit"
        )
    }

    private fun splitTopLevel(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var angle = 0
        var round = 0
        var square = 0
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
                '<' -> {
                    angle++
                    sb.append(ch)
                }
                '>' -> {
                    angle--
                    sb.append(ch)
                }
                '(' -> {
                    round++
                    sb.append(ch)
                }
                ')' -> {
                    round--
                    sb.append(ch)
                }
                '[' -> {
                    square++
                    sb.append(ch)
                }
                ']' -> {
                    square--
                    sb.append(ch)
                }
                ',' -> {
                    if (angle == 0 && round == 0 && square == 0) {
                        out += sb.toString().trim()
                        sb.clear()
                    } else {
                        sb.append(ch)
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotBlank() }
    }
}
