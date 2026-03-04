package io.github.mingovvv.endpointlens.core.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import mingovvv.endpointlens.core.model.RawRequestMapping

class EndpointComposerTest {
    @Test
    fun `composes cartesian product of paths and methods`() {
        val endpoints = EndpointComposer.compose(
            controllerFqn = "sample.UserController",
            methodName = "getUser",
            sourceFile = "UserController.kt",
            line = 10,
            classMapping = RawRequestMapping(paths = listOf("/api", "/v1"), methods = listOf("ALL")),
            methodMapping = RawRequestMapping(paths = listOf("/users/{id}"), methods = listOf("GET", "POST"))
        )

        assertEquals(4, endpoints.size)
        assertEquals(setOf("/api/users/{id}", "/v1/users/{id}"), endpoints.map { it.fullPath }.toSet())
        assertEquals(setOf("GET", "POST"), endpoints.map { it.httpMethod }.toSet())
    }

    @Test
    fun `uses class method when method mapping has ALL`() {
        val endpoints = EndpointComposer.compose(
            controllerFqn = "sample.UserController",
            methodName = "search",
            sourceFile = "UserController.kt",
            line = 20,
            classMapping = RawRequestMapping(paths = listOf("/api"), methods = listOf("GET")),
            methodMapping = RawRequestMapping(paths = listOf("/search"), methods = listOf("ALL"))
        )

        assertEquals(1, endpoints.size)
        assertEquals("GET", endpoints.first().httpMethod)
    }
}

