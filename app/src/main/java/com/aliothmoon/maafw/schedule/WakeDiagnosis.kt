package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 摘要只含配置状态，凭证内容及数量不能进入触发日志。 */
internal fun wakeDiagnosticText(
    type: String,
    ready: Boolean,
    recordedAtMs: Long?,
    secure: Boolean?,
    screenOn: Boolean?,
    locked: Boolean?,
): UiText {
    fun flag(value: Boolean?) = uiTextOf(when (value) {
        true -> R.string.wake_diagnostic_yes
        false -> R.string.wake_diagnostic_no
        null -> R.string.wake_diagnostic_unknown
    })
    val method = uiTextOf(when (type) {
        UnlockCredential.TYPE_PIN -> R.string.settings_wake_unlock_type_pin
        UnlockCredential.TYPE_GESTURE -> R.string.settings_wake_unlock_type_gesture
        else -> R.string.settings_wake_unlock_type_swipe
    })
    val recorded = recordedAtMs?.let {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: uiTextOf(R.string.wake_diagnostic_unknown)
    return uiTextOf(R.string.wake_diagnostic_summary, method, flag(ready), recorded, flag(secure), flag(screenOn), flag(locked))
}
