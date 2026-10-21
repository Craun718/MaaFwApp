package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/** 先留出首屏时间，只有窗口空闲时才逐帧增加常驻页面。 */
@Composable
internal fun rememberStagedBeyondViewportCount(pager: PagerState, max: Int, preload: PagerPreloadState): Int {
    var count by remember(pager) { mutableIntStateOf(0) }
    LaunchedEffect(pager, max, preload) {
        delay(400L)
        while (count < max) {
            preload.awaitIdle(snapshotFlow { pager.isScrollInProgress })
            withFrameNanos { frame ->
                if (!pager.isScrollInProgress && preload.tryPreload(frame)) count++
            }
        }
    }
    return count.coerceAtMost(max)
}
