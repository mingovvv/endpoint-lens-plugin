package mingovvv.endpointlens.idea.search

import com.intellij.openapi.project.Project
import mingovvv.endpointlens.idea.index.EndpointProjectIndexService
import mingovvv.endpointlens.idea.index.IndexedEndpoint

class EndpointSearchService(private val project: Project) {
    private val indexService = EndpointProjectIndexService.getInstance(project)
    private val supportedMethods = listOf("ALL", "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")

    fun methods(): List<String> {
        return supportedMethods
    }

    fun controllers(): List<String> {
        val controllers = indexService.getAll().map { it.endpoint.controllerFqn }.toSortedSet()
        return listOf("ALL") + controllers
    }

    fun modules(): List<String> {
        val modules = indexService.getAll().map { it.moduleName }.toSortedSet()
        return listOf("ALL") + modules
    }

    fun search(
        rawQuery: String,
        methodFilter: String = "ALL",
        moduleFilter: String = "ALL",
        controllerFilter: String = "ALL"
    ): List<IndexedEndpoint> {
        val query = EndpointSearchQuery.parse(rawQuery)
        return indexService.getAll()
            .asSequence()
            .filter { matchMethod(it, query, methodFilter) }
            .filter { moduleFilter == "ALL" || it.moduleName == moduleFilter }
            .filter { controllerFilter == "ALL" || it.endpoint.controllerFqn == controllerFilter }
            .filter { matchPath(it, query) }
            .filter { matchText(it, query) }
            .sortedWith(compareBy<IndexedEndpoint> { it.endpoint.fullPath }.thenBy { it.endpoint.httpMethod })
            .toList()
    }

    private fun matchMethod(item: IndexedEndpoint, query: EndpointSearchQuery, methodFilter: String): Boolean {
        val endpointMethod = item.endpoint.httpMethod.uppercase()
        if (methodFilter != "ALL" && endpointMethod != methodFilter.uppercase()) return false
        val queryMethod = query.method ?: return true
        return endpointMethod == queryMethod
    }

    private fun matchPath(item: IndexedEndpoint, query: EndpointSearchQuery): Boolean {
        val token = query.pathToken ?: return true
        val path = normalizePathForMatch(item.endpoint.fullPath)
        val needle = normalizePathForMatch(token)
        return path.contains(needle)
    }

    private fun matchText(item: IndexedEndpoint, query: EndpointSearchQuery): Boolean {
        if (query.textTokens.isEmpty()) return true
        val haystack = buildString {
            append(item.endpoint.fullPath.lowercase())
            append(' ')
            append(item.endpoint.controllerFqn.lowercase())
            append(' ')
            append(item.endpoint.methodName.lowercase())
            append(' ')
            append(item.endpoint.responseType.orEmpty().lowercase())
            append(' ')
            append(item.moduleName.lowercase())
        }
        return query.textTokens.all { haystack.contains(it) }
    }

    private fun normalizePathForMatch(raw: String): String {
        return raw.lowercase()
            .replace('\\', '/')
            .replace(Regex("/+"), "/")
            .trim()
            .removeSuffix("/")
            .let { if (it.isEmpty()) "/" else it }
    }
}
