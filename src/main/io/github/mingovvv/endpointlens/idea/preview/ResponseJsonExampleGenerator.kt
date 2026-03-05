package mingovvv.endpointlens.idea.preview

import mingovvv.endpointlens.idea.preview.model.StructureNode

object ResponseJsonExampleGenerator {
    fun generate(responseType: String?): String {
        val normalized = normalizeType(responseType)
        return prettyPrintJson(buildJson(normalized, 0))
    }

    fun generateFromNode(node: StructureNode?): String {
        if (node == null) return "null"
        return prettyPrintJson(buildJsonFromNode(node, 0))
    }

    private fun buildJson(type: String, depth: Int): String {
        if (depth > 5) return "null"
        val t = type.removeSuffix("?")

        when {
            t.equals("void", ignoreCase = true) || t.equals("Unit", ignoreCase = true) || t.equals("Void", ignoreCase = true) ->
                return "null"
            isStringLike(t) -> return "\"\""
            isDateTimeLike(t) -> return "\"$t\""
            isEnumLike(t) -> return "\"ENUM\""
            isBooleanLike(t) -> return "false"
            isIntegerLike(t) -> return "0"
            isFloatLike(t) -> return "0.0"
            isMapType(t) -> return "{}"
            isCollectionType(t) -> {
                val elementType = firstTypeArgument(t)
                val item = buildJson(elementType ?: "Any", depth + 1)
                return "[\n  $item\n]"
            }
            isArrayType(t) -> {
                val elementType = t.substringAfter("Array<", "").substringBeforeLast(">")
                val item = buildJson(elementType.ifBlank { "Any" }, depth + 1)
                return "[\n  $item\n]"
            }
        }

        // Dynamic unwrap: if the type has a generic argument, try resolving it
        // instead of returning empty. Handles Callable<T>, ResponseEntity<T>, etc.
        val typeArg = firstTypeArgument(t)
        if (typeArg != null) {
            return buildJson(typeArg, depth + 1)
        }

        return "{}"
    }

    private fun buildJsonFromNode(node: StructureNode, depth: Int): String {
        if (depth > 6) return "null"
        if (isCollectionType(node.type) || isArrayType(node.type)) {
            val item = node.children.firstOrNull()
            val rendered = if (item == null) {
                "null"
            } else {
                buildJsonFromNode(item, depth + 1)
            }
            return "[\n  $rendered\n]"
        }

        if (node.children.isNotEmpty()) {
            val rendered = node.children.joinToString(",\n") { child ->
                val value = buildJsonFromNode(child, depth + 1)
                """  "${child.name}": $value"""
            }
            return "{\n$rendered\n}"
        }

        // Use @Schema example if available
        if (node.example != null) {
            return formatExample(node.example, node.type)
        }
        return buildJson(node.type, depth)
    }

    private fun formatExample(example: String, type: String): String {
        if (isBooleanLike(type)) return example
        if (isIntegerLike(type) || isFloatLike(type)) return example
        return "\"$example\""
    }

    private fun prettyPrintJson(raw: String): String {
        if (raw.isBlank()) return raw
        val out = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false

        fun appendIndent(level: Int) {
            repeat(level) { out.append("  ") }
        }

        for (ch in raw) {
            if (inString) {
                out.append(ch)
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> {
                    inString = true
                    out.append(ch)
                }
                '{', '[' -> {
                    out.append(ch).append('\n')
                    indent++
                    appendIndent(indent)
                }
                '}', ']' -> {
                    out.append('\n')
                    indent = (indent - 1).coerceAtLeast(0)
                    appendIndent(indent)
                    out.append(ch)
                }
                ',' -> {
                    out.append(ch).append('\n')
                    appendIndent(indent)
                }
                ':' -> out.append(": ")
                ' ', '\n', '\r', '\t' -> Unit
                else -> out.append(ch)
            }
        }

        return out.toString().trim()
    }

    private fun normalizeType(type: String?): String {
        return type.orEmpty()
            .trim()
            .ifBlank { "Any" }
            .replace("kotlin.", "")
            .replace("java.lang.", "")
            .replace("java.util.", "")
    }

    private fun firstTypeArgument(type: String): String? {
        val start = type.indexOf('<')
        val end = type.lastIndexOf('>')
        if (start < 0 || end <= start) return null
        val inner = type.substring(start + 1, end).trim()
        if (inner.isBlank()) return null
        return splitTopLevel(inner).firstOrNull()?.trim()
    }

    private fun splitTopLevel(raw: String): List<String> {
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

    private fun isCollectionType(type: String): Boolean {
        val short = type.substringAfterLast('.')
        return short.startsWith("List<") || short.startsWith("Set<") || short.startsWith("Collection<")
    }

    private fun isArrayType(type: String): Boolean {
        val short = type.substringAfterLast('.')
        return short.startsWith("Array<") && short.endsWith(">")
    }
    private fun isMapType(type: String): Boolean {
        val short = type.substringAfterLast('.')
        return short.startsWith("Map<") || short == "Map"
    }
    private fun isStringLike(type: String): Boolean = type in setOf("String", "Char", "CharSequence", "UUID")
    private fun isDateTimeLike(type: String): Boolean = type in setOf(
        "LocalDate", "LocalDateTime", "LocalTime",
        "OffsetDateTime", "OffsetTime", "ZonedDateTime",
        "Instant", "Date", "Timestamp"
    )
    private fun isBooleanLike(type: String): Boolean = type == "Boolean" || type == "boolean"
    private fun isIntegerLike(type: String): Boolean = type in setOf("Int", "Integer", "Long", "Short", "Byte", "BigInteger", "int", "long", "short", "byte")
    private fun isFloatLike(type: String): Boolean = type in setOf("Double", "Float", "BigDecimal", "double", "float")
    private fun isEnumLike(type: String): Boolean = type == "ENUM"
}
