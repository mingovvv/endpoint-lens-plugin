package io.github.mingovvv.endpointlens.idea.extractor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mingovvv.endpointlens.core.model.EndpointConfidence

class SpringMappingExtractorTest {
    private val extractor = SpringMappingExtractor()

    @Test
    fun `extracts kotlin controller endpoints`() {
        val source = """
            package sample.api

            @RequestMapping(path = ["/api"])
            class UserController {
                @GetMapping("/users/{id}")
                fun getUser() {
                }

                @RequestMapping(path = ["/users"], method = [RequestMethod.POST])
                fun createUser() {
                }
            }
        """.trimIndent()

        val endpoints = extractor.extractFromSource(source, "UserController.kt")
        assertEquals(2, endpoints.size)

        val paths = endpoints.map { "${it.httpMethod} ${it.fullPath}" }.toSet()
        assertEquals(setOf("GET /api/users/{id}", "POST /api/users"), paths)
        assertTrue(endpoints.all { it.confidence == EndpointConfidence.HIGH })
    }

    @Test
    fun `extracts request mapping arrays`() {
        val source = """
            package sample.api;

            @RequestMapping(path={"/base"})
            public class MultiController {
                @RequestMapping(path={"/a","/b"}, method={RequestMethod.GET, RequestMethod.POST})
                public String route() {
                    return "ok";
                }
            }
        """.trimIndent()

        val endpoints = extractor.extractFromSource(source, "MultiController.java")
        assertEquals(4, endpoints.size)
    }

    @Test
    fun `extracts delete mapping from multiline java method declaration`() {
        val source = """
            package sample.api;

            @RequestMapping(path={"/scheduler"})
            public class SchedulerController {
                @DeleteMapping("/group-users")
                public BaseResponse<Boolean> processGdiUserSoftDelete(
                ) {
                    return null;
                }
            }
        """.trimIndent()

        val endpoints = extractor.extractFromSource(source, "SchedulerController.java")
        assertEquals(1, endpoints.size)
        assertEquals("DELETE", endpoints.first().httpMethod)
        assertEquals("/scheduler/group-users", endpoints.first().fullPath)
    }

    @Test
    fun `downgrades confidence when unresolved placeholder exists`() {
        val source = """
            package sample.api

            @RequestMapping(path = ["/api"])
            class UserController {
                @GetMapping("/users/${'$'}{id}")
                fun getUser() {
                }
            }
        """.trimIndent()

        val endpoints = extractor.extractFromSource(source, "UserController.kt")
        assertEquals(1, endpoints.size)
        assertEquals(EndpointConfidence.MEDIUM, endpoints.first().confidence)
        assertTrue(endpoints.first().limitations.any { it.contains("Unresolved placeholder") })
    }
}
