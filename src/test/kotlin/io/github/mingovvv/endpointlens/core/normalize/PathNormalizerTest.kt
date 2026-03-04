package io.github.mingovvv.endpointlens.core.normalize

import kotlin.test.Test
import kotlin.test.assertEquals

class PathNormalizerTest {
    @Test
    fun `normalizes slash and trailing slash`() {
        assertEquals("/users", PathNormalizer.normalize("users/"))
        assertEquals("/api/users", PathNormalizer.normalize("//api///users//"))
        assertEquals("/", PathNormalizer.normalize(""))
    }

    @Test
    fun `combines class and method paths`() {
        assertEquals("/api/users", PathNormalizer.combine("/api", "/users"))
        assertEquals("/users", PathNormalizer.combine("", "/users"))
        assertEquals("/api", PathNormalizer.combine("/api", ""))
        assertEquals("/", PathNormalizer.combine("", ""))
    }
}

