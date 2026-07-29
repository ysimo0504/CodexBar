package com.ysimo.codexbar.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutPolicyTest {
    @Test
    fun `portrait screens stack provider content vertically`() {
        val portrait = DashboardLayoutPolicy.resolve(widthDp = 432, heightDp = 960)
        val readerPortrait = DashboardLayoutPolicy.resolve(
            widthDp = 674,
            heightDp = 896,
            smallestWidthDp = 674,
        )

        assertTrue(portrait.compactPhoneChrome)
        assertFalse(portrait.readerChrome)
        assertFalse(portrait.horizontalProviderContent)
        assertTrue(portrait.metricColumns == 2)
        assertTrue(readerPortrait.readerChrome)
        assertFalse(readerPortrait.horizontalProviderContent)
        assertTrue(readerPortrait.metricColumns == 3)
    }

    @Test
    fun `landscape and square screens lay provider content out horizontally`() {
        val phoneLandscape = DashboardLayoutPolicy.resolve(widthDp = 960, heightDp = 432)
        val landscape = DashboardLayoutPolicy.resolve(
            widthDp = 896,
            heightDp = 592,
            smallestWidthDp = 674,
        )
        val square = DashboardLayoutPolicy.resolve(widthDp = 800, heightDp = 800)

        assertTrue(phoneLandscape.horizontalProviderContent)
        assertTrue(phoneLandscape.metricColumns == 2)
        assertTrue(landscape.readerChrome)
        assertTrue(landscape.horizontalProviderContent)
        assertTrue(landscape.metricColumns == 2)
        assertTrue(square.horizontalProviderContent)
    }
}
