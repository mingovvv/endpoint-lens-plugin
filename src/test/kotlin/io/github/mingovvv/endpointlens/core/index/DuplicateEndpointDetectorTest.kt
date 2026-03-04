package io.github.mingovvv.endpointlens.core.index

import kotlin.test.Test
import kotlin.test.assertEquals
import mingovvv.endpointlens.core.model.HttpEndpoint

class DuplicateEndpointDetectorTest {
    @Test
    fun `detects duplicate by method and normalized path`() {
        val a = HttpEndpoint(
            httpMethod = "get",
            fullPath = "/api//users/",
            controllerFqn = "a.C1",
            methodName = "m1",
            sourceFile = "A.kt",
            line = 10
        )
        val b = HttpEndpoint(
            httpMethod = "GET",
            fullPath = "/api/users",
            controllerFqn = "a.C2",
            methodName = "m2",
            sourceFile = "B.kt",
            line = 20
        )
        val c = HttpEndpoint(
            httpMethod = "POST",
            fullPath = "/api/users",
            controllerFqn = "a.C3",
            methodName = "m3",
            sourceFile = "C.kt",
            line = 30
        )

        val duplicates = DuplicateEndpointDetector.detect(listOf(a, b, c))
        assertEquals(1, duplicates.size)
        assertEquals("GET /api/users", duplicates.first().key)
        assertEquals(2, duplicates.first().endpoints.size)
    }
}

