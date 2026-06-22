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
    private val maxDepth = 6
    private val typeFileCache = ConcurrentHashMap<String, VirtualFile>()
    private val typeFieldsCache = ConcurrentHashMap<String, List<Triple<String, String, String?>>>()

    fun build(endpoint: HttpEndpoint): EndpointStructurePreview {
        val limitations = mutableListOf<String>()
        val sourceText = loadSourceText(endpoint, limitations)
        val methodBlock = sourceText?.let { readMethodBlock(it, endpoint) }

        val requestType = methodBlock?.let { extractRequestType(it, endpoint.methodName) }
        val responseType = methodBlock?.let { extractResponseType(it, endpoint.methodName) }

        // The controller source is the initial resolution context: a request/response type may
        // itself be a type nested inside the controller file rather than a standalone file.
        val requestNode = requestType?.let { resolveTypeTree("RequestBody", it, 0, mutableSetOf(), limitations, contextText = sourceText) }
        val responseNode = responseType?.let { resolveTypeTree("Response", it, 0, mutableSetOf(), limitations, contextText = sourceText) }

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

    private fun loadSourceText(endpoint: HttpEndpoint, limitations: MutableList<String>): String? {
        val file = ReadAction.compute<VirtualFile?, RuntimeException> {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(endpoint.sourceFile)
        } ?: run {
            limitations += "Source file not found"
            return null
        }
        return ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) }
    }

    private fun readMethodBlock(text: String, endpoint: HttpEndpoint): String {
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
        limitations: MutableList<String>,
        example: String? = null,
        contextText: String? = null
    ): StructureNode {
        val type = normalizeTypeName(rawType)
        if (depth >= maxDepth || isPrimitiveLike(type)) {
            return StructureNode(name = label, type = type, example = example)
        }

        if (isCollectionType(type)) {
            val elementType = parseTypeArguments(type).firstOrNull().orEmpty().ifBlank { "Any" }
            val child = resolveTypeTree("item", elementType, depth + 1, visited.toMutableSet(), limitations, contextText = contextText)
            return StructureNode(name = label, type = type, children = listOf(child))
        }

        if (isArrayType(type)) {
            val elementType = parseTypeArguments(type).firstOrNull().orEmpty().ifBlank { "Any" }
            val child = resolveTypeTree("item", elementType, depth + 1, visited.toMutableSet(), limitations, contextText = contextText)
            return StructureNode(name = label, type = type, children = listOf(child))
        }

        if (isMapType(type)) {
            return StructureNode(name = label, type = type)
        }

        val short = shortType(type)
        if (!visited.add(short)) {
            return StructureNode(name = label, type = "$type (cycle)")
        }

        // Resolve the text that declares this type: a standalone file, or — if there is none —
        // a type nested inside the current context file (e.g. a record/class declared in the
        // same file as its parent or in the controller).
        val declText = resolveDeclaringText(short, contextText)
        if (declText != null && isEnumDeclaration(declText, short)) {
            return StructureNode(name = label, type = "ENUM")
        }

        val fields = if (declText == null) emptyList() else readFieldsFromType(short, type, declText, limitations)
        if (fields.isEmpty()) {
            // Dynamic unwrap: if the type has generic arguments but we can't find its source,
            // it's likely a framework/JDK wrapper — resolve the first type argument instead.
            val typeArgs = parseTypeArguments(type)
            if (typeArgs.isNotEmpty()) {
                return resolveTypeTree(label, typeArgs.first(), depth, visited, limitations, contextText = contextText)
            }
            return StructureNode(name = label, type = type)
        }
        // Children resolve against the file that declared this type, so further nesting works.
        val children = fields.map { (name, childType, childExample) ->
            resolveTypeTree(name, childType, depth + 1, visited.toMutableSet(), limitations, childExample, contextText = declText)
        }
        return StructureNode(name = label, type = type, children = children)
    }

    private fun resolveDeclaringText(short: String, contextText: String?): String? {
        if (contextText != null && declaresType(contextText, short)) return contextText
        val file = findTypeFile(short) ?: return null
        return ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) }
    }

    private fun declaresType(text: String, typeName: String): Boolean {
        return Regex("""\b(?:class|interface|record|enum)\s+""" + Regex.escape(typeName) + """\b""").containsMatchIn(text)
    }

    // Returns Triple(fieldName, fieldType, schemaExample?)
    private fun readFieldsFromType(typeName: String, typeSignature: String, text: String, limitations: MutableList<String>): List<Triple<String, String, String?>> {
        // Key includes the declaring text so a nested type does not collide with a same-named
        // standalone type resolved from a different file.
        val cacheKey = "$typeName#${text.hashCode()}"
        val rawFields = typeFieldsCache[cacheKey] ?: extractRawFields(typeName, text, limitations).also {
            typeFieldsCache[cacheKey] = it
        }
        if (rawFields.isEmpty()) return emptyList()

        val typeParams = parseTypeParameters(typeName, text)
        val typeArgs = parseTypeArguments(typeSignature)
        if (typeParams.isEmpty() || typeArgs.isEmpty()) return rawFields
        val genericMap = typeParams.zip(typeArgs).toMap()
        return rawFields.map { (name, childType, example) ->
            Triple(name, applyGenericMap(childType, genericMap), example)
        }
    }

    private fun extractSchemaExample(token: String): String? {
        return Regex("""\bexample\s*=\s*"([^"]*)"""").find(token)?.groupValues?.getOrNull(1)
    }

    private fun extractRawFields(typeName: String, text: String, limitations: MutableList<String>): List<Triple<String, String, String?>> {
        val javaRecord = extractRecordComponents(text, typeName).orEmpty()
        val javaRecordFields = splitTopLevel(javaRecord)
            .mapNotNull { token ->
                val example = extractSchemaExample(token)
                val cleaned = token.trim().replace(Regex("""@\w+(?:\([^)]*\))?"""), " ").replace(Regex("""\s+"""), " ")
                val m = Regex("""([A-Za-z_][A-Za-z0-9_<>.\[\]?]+)\s+([A-Za-z_][A-Za-z0-9_]*)$""").find(cleaned)
                m?.let { Triple(it.groupValues[2], it.groupValues[1], example) }
            }
        if (javaRecordFields.isNotEmpty()) {
            return javaRecordFields
        }

        val kotlinCtor = Regex("""(?:data\s+)?class\s+""" + Regex.escape(typeName) + """\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val ctorFields = kotlinCtor.split(',')
            .mapNotNull { token ->
                val example = extractSchemaExample(token)
                val t = token.trim()
                val m = Regex("""(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z0-9_<>\[\].?]+)""").find(t)
                m?.let { Triple(it.groupValues[1], it.groupValues[2], example) }
            }
        if (ctorFields.isNotEmpty()) {
            return ctorFields
        }

        // Body-property fallbacks: scope to this type's own brace body, then drop nested brace
        // groups (method bodies, nested types) so only this type's top-level fields remain.
        val body = extractTypeBody(text, typeName)?.let { stripNestedBraces(it) } ?: ""

        val kotlinBodyFields = Regex("""(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z0-9_<>\[\].?]+)""")
            .findAll(body).map { Triple(it.groupValues[1], it.groupValues[2], null as String?) }.toList()
        if (kotlinBodyFields.isNotEmpty()) {
            return kotlinBodyFields.distinctBy { it.first }
        }

        val javaFields = Regex(
            """(?:private|protected|public)\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*([A-Za-z0-9_<>\[\].?]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=[^;]*)?;"""
        )
            .findAll(body).map { Triple(it.groupValues[2], it.groupValues[1], null as String?) }.toList()
        if (javaFields.isNotEmpty()) {
            return javaFields
        }

        limitations += "Could not resolve fields for type $typeName"
        return emptyList()
    }

    // The brace body of `typeName`'s declaration, or null if it has none (e.g. a bare record).
    private fun extractTypeBody(text: String, typeName: String): String? {
        val decl = Regex("""\b(?:class|interface|record|enum)\s+""" + Regex.escape(typeName) + """\b""")
            .find(text) ?: return null
        var i = decl.range.last + 1
        while (i < text.length && text[i] != '{' && text[i] != ';') i++
        if (i >= text.length || text[i] != '{') return null
        val start = i + 1
        var depth = 1
        i = start
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i)
                }
            }
            i++
        }
        return text.substring(start)
    }

    // Removes everything inside nested `{...}` groups, keeping only top-level text. Used so a
    // class body's field scan ignores method bodies and nested type declarations.
    private fun stripNestedBraces(body: String): String {
        val sb = StringBuilder()
        var depth = 0
        for (ch in body) {
            when (ch) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun extractRecordComponents(text: String, typeName: String): String? {
        val headerRegex = Regex("""\brecord\s+""" + Regex.escape(typeName) + """\b""")
        val headerMatch = headerRegex.find(text) ?: return null
        var i = headerMatch.range.last + 1
        while (i < text.length && text[i].isWhitespace()) i++
        // Skip generic type parameters: record PageResponseDto<T>(...)
        if (i < text.length && text[i] == '<') {
            var angleDepth = 1
            i++
            while (i < text.length && angleDepth > 0) {
                if (text[i] == '<') angleDepth++
                else if (text[i] == '>') angleDepth--
                i++
            }
            while (i < text.length && text[i].isWhitespace()) i++
        }
        if (i >= text.length || text[i] != '(') return null

        val start = i + 1
        var depth = 1
        var inString = false
        var quote = '"'
        var escape = false
        i = start

        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == quote) {
                    inString = false
                }
                i++
                continue
            }

            when (ch) {
                '"', '\'' -> {
                    inString = true
                    quote = ch
                }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun parseTypeParameters(typeName: String, text: String): List<String> {
        val raw = Regex("""(?:class|record)\s+""" + Regex.escape(typeName) + """\s*<([^>]+)>""")
            .find(text)?.groupValues?.getOrNull(1)
            ?.trim()
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return splitTopLevel(raw).map { token ->
            token.trim().substringBefore(':').substringBefore("extends").trim()
        }.filter { it.isNotBlank() }
    }

    private fun parseTypeArguments(typeSignature: String): List<String> {
        val start = typeSignature.indexOf('<')
        val end = typeSignature.lastIndexOf('>')
        if (start < 0 || end <= start) return emptyList()
        val raw = typeSignature.substring(start + 1, end).trim()
        if (raw.isBlank()) return emptyList()
        return splitTopLevel(raw).map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun applyGenericMap(type: String, genericMap: Map<String, String>): String {
        var resolved = type
        genericMap.forEach { (k, v) ->
            resolved = resolved.replace(Regex("""\b""" + Regex.escape(k) + """\b"""), v)
        }
        return resolved
    }

    private fun findTypeFile(type: String): VirtualFile? {
        typeFileCache[type]?.let { return it }

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
        if (resolved != null) {
            typeFileCache[type] = resolved
        }
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
        return type.substringBefore('<').substringAfterLast('.')
    }

    private fun isPrimitiveLike(type: String): Boolean {
        val t = type.substringAfterLast('.').removeSuffix("?")
        return t in setOf(
            "String", "Int", "Long", "Double", "Float", "Boolean", "Short", "Byte",
            "Integer", "BigDecimal", "void", "Unit",
            "LocalDate", "LocalDateTime", "LocalTime",
            "OffsetDateTime", "OffsetTime", "ZonedDateTime",
            "Instant", "Date", "Timestamp",
            "ENUM"
        )
    }

    private fun isEnumDeclaration(text: String, typeName: String): Boolean {
        return Regex("""\benum\s+(class\s+)?""" + Regex.escape(typeName) + """\b""").containsMatchIn(text)
    }

    private fun isCollectionType(type: String): Boolean {
        val short = type.substringAfterLast('.')
        return short.startsWith("List<") || short.startsWith("Set<") || short.startsWith("Collection<")
    }

    private fun isArrayType(type: String): Boolean {
        val short = type.substringAfterLast('.')
        return short.startsWith("Array<")
    }

    private fun isMapType(type: String): Boolean {
        val short = type.substringAfterLast('.')
        return short.startsWith("Map<") || short == "Map"
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
