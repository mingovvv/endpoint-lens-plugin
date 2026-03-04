package mingovvv.endpointlens.core.compose

import mingovvv.endpointlens.core.model.EndpointConfidence
import mingovvv.endpointlens.core.model.HttpEndpoint
import mingovvv.endpointlens.core.model.RawRequestMapping
import mingovvv.endpointlens.core.normalize.PathNormalizer

object EndpointComposer {
    fun compose(
        controllerFqn: String,
        methodName: String,
        responseType: String? = null,
        sourceFile: String,
        line: Int,
        classMapping: mingovvv.endpointlens.core.model.RawRequestMapping,
        methodMapping: mingovvv.endpointlens.core.model.RawRequestMapping,
        confidence: mingovvv.endpointlens.core.model.EndpointConfidence = _root_ide_package_.mingovvv.endpointlens.core.model.EndpointConfidence.MEDIUM
    ): List<mingovvv.endpointlens.core.model.HttpEndpoint> {
        val methods = resolveMethods(classMapping.methods, methodMapping.methods)
        val paths = combinePaths(classMapping.paths, methodMapping.paths)
        val limitations = (classMapping.limitations + methodMapping.limitations).distinct()

        return methods.flatMap { method ->
            paths.map { path ->
                HttpEndpoint(
                    httpMethod = method,
                    fullPath = path,
                    controllerFqn = controllerFqn,
                    methodName = methodName,
                    responseType = responseType,
                    sourceFile = sourceFile,
                    line = line,
                    consumes = mergeConditions(classMapping.consumes, methodMapping.consumes),
                    produces = mergeConditions(classMapping.produces, methodMapping.produces),
                    params = mergeConditions(classMapping.params, methodMapping.params),
                    headers = mergeConditions(classMapping.headers, methodMapping.headers),
                    confidence = confidence,
                    limitations = limitations
                )
            }
        }
    }

    private fun combinePaths(classPaths: List<String>, methodPaths: List<String>): List<String> {
        val cPaths = if (classPaths.isEmpty()) listOf("") else classPaths
        val mPaths = if (methodPaths.isEmpty()) listOf("") else methodPaths
        return cPaths.flatMap { c -> mPaths.map { m -> PathNormalizer.combine(c, m) } }.distinct()
    }

    private fun resolveMethods(classMethods: List<String>, methodMethods: List<String>): List<String> {
        val classNorm = normalizeMethods(classMethods)
        val methodNorm = normalizeMethods(methodMethods)

        val classSpecific = classNorm.filterNot { it == "ALL" }
        val methodSpecific = methodNorm.filterNot { it == "ALL" }
        return when {
            methodSpecific.isNotEmpty() && classSpecific.isNotEmpty() ->
                methodSpecific.intersect(classSpecific.toSet()).ifEmpty { methodSpecific.toSet() }.toList()
            methodSpecific.isNotEmpty() -> methodSpecific
            classSpecific.isNotEmpty() -> classSpecific
            else -> listOf("ALL")
        }
    }

    private fun normalizeMethods(input: List<String>): List<String> {
        if (input.isEmpty()) return listOf("ALL")
        return input.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.ifEmpty { listOf("ALL") }.distinct()
    }

    private fun mergeConditions(base: List<String>, override: List<String>): List<String> {
        return if (override.isEmpty()) base.distinct() else (base + override).distinct()
    }
}
