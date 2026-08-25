package com.highsockscapital.sunshine.data

import org.junit.Assert.assertTrue
import org.junit.Test

class SunshineSelfManagementToolRoutingTest {
    @Test
    fun scheduledTaskToolIsRoutedByPiHostExecutor() {
        assertTrue(SunshineToolExecutor.supports("sunshine_scheduled_task_manage"))
    }

    @Test
    fun piExtensionToolIsRoutedByPiHostExecutor() {
        assertTrue(SunshineToolExecutor.supports("sunshine_extension_manage"))
    }
}
