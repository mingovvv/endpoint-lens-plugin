package mingovvv.endpointlens.idea.search

data class EndpointSearchQuery(
    val method: String? = null,
    val textTokens: List<String> = emptyList(),
    val pathToken: String? = null
) {
    companion object {
        private val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "ALL")
        private val METHOD_ALIASES = mapOf(
            "@GETMAPPING" to "GET",
            "@POSTMAPPING" to "POST",
            "@PUTMAPPING" to "PUT",
            "@PATCHMAPPING" to "PATCH",
            "@DELETEMAPPING" to "DELETE",
            "GETMAPPING" to "GET",
            "POSTMAPPING" to "POST",
            "PUTMAPPING" to "PUT",
            "PATCHMAPPING" to "PATCH",
            "DELETEMAPPING" to "DELETE",
            "REQUESTMETHOD.GET" to "GET",
            "REQUESTMETHOD.POST" to "POST",
            "REQUESTMETHOD.PUT" to "PUT",
            "REQUESTMETHOD.PATCH" to "PATCH",
            "REQUESTMETHOD.DELETE" to "DELETE",
            "REQUESTMETHOD.OPTIONS" to "OPTIONS",
            "REQUESTMETHOD.HEAD" to "HEAD"
        )

        fun parse(input: String): EndpointSearchQuery {
            val rawTokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (rawTokens.isEmpty()) return EndpointSearchQuery()

            val first = normalizeMethodToken(rawTokens.first())
            val explicitMethod = when {
                first in HTTP_METHODS -> first
                METHOD_ALIASES.containsKey(first) -> METHOD_ALIASES[first]
                else -> null
            }

            val inferredMethod = explicitMethod ?: rawTokens
                .asSequence()
                .map { normalizeMethodToken(it) }
                .mapNotNull { METHOD_ALIASES[it] }
                .firstOrNull()

            val remainder = rawTokens.filterNot { token ->
                val normalized = normalizeMethodToken(token)
                normalized == explicitMethod || METHOD_ALIASES.containsKey(normalized)
            }
            val pathToken = remainder.firstOrNull { it.startsWith("/") }
            val textTokens = remainder.filter { !it.startsWith("/") }.map { it.lowercase() }
            return EndpointSearchQuery(method = inferredMethod, textTokens = textTokens, pathToken = pathToken?.lowercase())
        }

        private fun normalizeMethodToken(token: String): String {
            return token.trim().uppercase()
        }
    }
}
