package io.github.mingovvv.endpointlens.idea.extractor

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectSourceScannerTest {
    @Test
    fun `extracts endpoints from single source file`() {
        val fixture = Path.of("src", "test", "resources", "fixtures", "UserController.kt")
        val endpoints = SourceFileEndpointExtractor().extractFromFile(fixture)

        assertEquals(2, endpoints.size)
        assertTrue(endpoints.any { it.fullPath == "/api/users/{id}" && it.httpMethod == "GET" })
        assertTrue(endpoints.any { it.fullPath == "/api/users" && it.httpMethod == "POST" })
    }

    @Test
    fun `scans project fixture directory`() {
        val fixtureRoot = Path.of("src", "test", "resources", "fixtures")
        val endpoints = ProjectSourceScanner().scan(fixtureRoot)

        assertEquals(6, endpoints.size)
        assertTrue(endpoints.any { it.controllerFqn == "fixture.api.UserController" && it.methodName == "getUser" })
        assertTrue(endpoints.any { it.controllerFqn == "fixture.api.AdminController" && it.methodName == "manage" && it.httpMethod == "DELETE" })
    }
}

