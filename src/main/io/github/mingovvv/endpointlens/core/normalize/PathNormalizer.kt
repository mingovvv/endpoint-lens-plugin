package mingovvv.endpointlens.core.normalize

object PathNormalizer {
    fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return "/"
        }

        val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val collapsed = withLeadingSlash.replace(Regex("/+"), "/")
        return if (collapsed.length > 1 && collapsed.endsWith("/")) {
            collapsed.dropLast(1)
        } else {
            collapsed
        }
    }

    fun combine(classPath: String, methodPath: String): String {
        val classPart = classPath.trim().trim('/')
        val methodPart = methodPath.trim().trim('/')
        return when {
            classPart.isEmpty() && methodPart.isEmpty() -> "/"
            classPart.isEmpty() -> normalize(methodPart)
            methodPart.isEmpty() -> normalize(classPart)
            else -> normalize("$classPart/$methodPart")
        }
    }
}

