package com.highsockscapital.sunshine.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformCapabilitiesTest {
    @Test
    fun androidExposesAllFeatures() {
        assertTrue(PlatformCapabilities.Android.alpine)
        assertTrue(PlatformCapabilities.Android.termux)
        assertTrue(PlatformCapabilities.Android.agentMode)
        assertTrue(PlatformCapabilities.Android.nativeMods)
        assertTrue(PlatformCapabilities.Android.layeredScreenTransitions)
    }
}
