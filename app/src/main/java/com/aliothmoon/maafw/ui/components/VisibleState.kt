package com.aliothmoon.maafw.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** 离屏保留最后一帧，重新可见时恢复订阅当前状态。 */
@Composable
fun <T> StateFlow<T>.collectWhileVisible(visible: Boolean): State<T> {
    val source = remember(this, visible) { if (visible) this else emptyFlow() }
    val initial = remember { value }
    return source.collectAsStateWithLifecycle(initial)
}
