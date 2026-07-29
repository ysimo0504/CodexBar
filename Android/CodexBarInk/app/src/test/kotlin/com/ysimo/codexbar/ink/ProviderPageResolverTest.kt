package com.ysimo.codexbar.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPageResolverTest {
    @Test
    fun `release always resolves to a whole page`() {
        assertEquals(1, ProviderPageResolver.targetPage(0, 3, 600, 290, 80f, null))
        assertEquals(0, ProviderPageResolver.targetPage(0, 3, 600, 80, 20f, null))
        assertEquals(2, ProviderPageResolver.targetPage(1, 3, 600, 700, 30f, 1_400))
        assertEquals(0, ProviderPageResolver.targetPage(1, 3, 600, 500, -30f, -1_400))
    }
}
