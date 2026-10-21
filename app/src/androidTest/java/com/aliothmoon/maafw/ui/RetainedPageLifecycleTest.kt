package com.aliothmoon.maafw.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.runner.DisplayResolution
import com.aliothmoon.maafw.ui.components.MaaPreviewSurface
import com.aliothmoon.maafw.ui.components.collectWhileVisible
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetainedPageLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hiddenPageStopsCollectingAndCatchesUpWhenShown() {
        val visible = mutableStateOf(true)
        val source = MutableStateFlow(1)
        val observed = AtomicInteger()
        compose.setContent {
            val value by source.collectWhileVisible(visible.value)
            SideEffect { observed.set(value) }
        }
        compose.waitUntil { source.subscriptionCount.value == 1 }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil { source.subscriptionCount.value == 0 }
        source.value = 2
        compose.runOnIdle { assertEquals(1, observed.get()) }
        compose.runOnIdle { visible.value = true }
        compose.waitUntil { observed.get() == 2 }
    }

    @Test
    fun retainedPreviewReleasesItsSurfaceWhileHiddenAndReattachesWhenShown() {
        val visible = mutableStateOf(true)
        val available = AtomicInteger()
        val destroyed = AtomicInteger()
        compose.setContent {
            Box(Modifier.size(200.dp)) {
                MaaPreviewSurface(
                    resolution = DisplayResolution(320, 180),
                    surfaceEpoch = 0,
                    visible = visible.value,
                    onSurfaceCreated = {},
                    onSurfaceAvailable = { available.incrementAndGet() },
                    onSurfaceDestroyed = { destroyed.incrementAndGet() },
                )
            }
        }
        compose.waitUntil(5_000) { available.get() > 0 }
        val before = available.get()
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5_000) { destroyed.get() > 0 }
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(5_000) { available.get() > before }
    }
}
