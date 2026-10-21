package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTriggerLogStorageTest {
    @Test
    fun unavailableStorageDoesNotBreakTriggerOrHistoryLoading() = runTest {
        mockkObject(AppPaths)
        try {
            every { AppPaths.LOG_DIR } throws SecurityException("storage not ready")
            val log = ScheduleTriggerLog()
            log.append(TriggerLogEntry("s", "schedule", 1L, 2L, TriggerResult.FAILED_START))
            assertTrue(log.readAll().isEmpty())
            log.clear()
        } finally {
            unmockkObject(AppPaths)
        }
    }
}
