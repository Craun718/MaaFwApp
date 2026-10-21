package com.aliothmoon.maafw.remote.internal

import android.os.SystemClock
import android.view.KeyEvent
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.UnlockGesture
import com.aliothmoon.maafw.bridge.InputControlUtils
import com.aliothmoon.maafw.remote.internal.ScreenPowerAttempts.SleepAction
import com.aliothmoon.maafw.remote.internal.ScreenPowerAttempts.WakeAction
import com.aliothmoon.maafw.remote.internal.WakeUnlockController.LOCK_SETTLE_MS
import com.aliothmoon.maafw.remote.internal.WakeUnlockController.lockAndSleep
import com.aliothmoon.maafw.remote.internal.WakeUnlockController.unlock
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.PowerManager
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import com.aliothmoon.maafw.third.wrappers.WindowManager
import android.os.PowerManager as OsPowerManager

/** 唤醒/解锁/锁屏；提权进程内完成，凭证支持纯数字 PIN 与录制手势 */
object WakeUnlockController {

    private const val TAG = "WakeUnlock"

    private const val SCREEN_ON_TIMEOUT_MS = 5_000L
    private const val KEY_WAKE_TIMEOUT_MS = 2_000L
    private const val KEYGUARD_GONE_TIMEOUT_MS = 5_000L

    /** bouncer 就绪基线，设备本就活跃时够用 */
    private const val BOUNCER_SETTLE_MS = 1_200L

    /** 限制稳定等待，避免拉长定时任务启动耗时 */
    private const val BOUNCER_SETTLE_MAX_MS = 5_000L
    private const val DIGIT_GAP_MS = 50L
    private const val PIN_INJECT_BUDGET_MS = 1_000L

    /** 手势回放前等锁屏首屏稳定；不弹 bouncer，比 PIN 那条路要短 */
    private const val GESTURE_SETTLE_MS = 800L

    /** 测试：上锁/息屏后等待系统稳定再解锁 */
    private const val LOCK_SETTLE_MS = 500L
    private const val SCREEN_OFF_TIMEOUT_MS = 3_000L

    /** 自测整段的 CPU 锁上限：上锁、息屏、亮屏、settle、输入、确认各步超时加起来也到不了 */
    private const val TEST_CPU_LOCK_MS = 60_000L
    private const val CPU_LOCK_TAG = "MaaFw:wake-unlock"

    @Volatile
    private var last: Pair<Long, String>? = null
    private val activeTrace = ThreadLocal<WakeTrace>()

    fun lastTrace(sinceElapsedMs: Long): String =
        last?.takeIf { it.first >= sinceElapsedMs }?.second.orEmpty()

    private inline fun traced(what: String, block: () -> Int): Int {
        if (activeTrace.get() != null) return block()
        val trace = WakeTrace()
        activeTrace.set(trace)
        trace.note(what)
        try {
            return block().also { trace.note("result=$it") }
        } finally {
            last = trace.startMs to trace.render()
            activeTrace.remove()
        }
    }

    private fun traceInfo(message: String) {
        val trace = activeTrace.get()
        if (trace != null) trace.i(message.removePrefix("$TAG: ")) else Ln.i(message)
    }

    private fun traceWarn(message: String) {
        val trace = activeTrace.get()
        if (trace != null) trace.w(message.removePrefix("$TAG: ")) else Ln.w(message)
    }

    /**
     * 设置页自测：先 [lockAndSleep]，等待 [LOCK_SETTLE_MS] 后再 [unlock]
     * 整段在提权进程内完成，避免息屏后 App 侧协程被挂起
     */
    fun testUnlock(credential: String): Int = traced("test unlock") { keepCpuAwake(TEST_CPU_LOCK_MS) {
        val lockCode = lockAndSleep()
        if (lockCode != WakeUnlockResult.OK) return@keepCpuAwake lockCode
        traceInfo("$TAG: locked for test, settle ${LOCK_SETTLE_MS}ms")
        Thread.sleep(LOCK_SETTLE_MS)
        unlock(credential)
    } }

    /** 设置页自测：手势版 */
    fun testUnlockGesture(gestureJson: String): Int = traced("test unlock") { keepCpuAwake(TEST_CPU_LOCK_MS) {
        val lockCode = lockAndSleep()
        if (lockCode != WakeUnlockResult.OK) return@keepCpuAwake lockCode
        traceInfo("$TAG: locked for gesture test, settle ${LOCK_SETTLE_MS}ms")
        Thread.sleep(LOCK_SETTLE_MS)
        unlockWithGesture(gestureJson)
    } }

    /**
     * 息屏后 CPU 一睡 Thread.sleep 就停住；自测从设置页发起，App 侧没有定时那把锁兜着，提权进程自己持一把
     * 拿不到就照旧跑，限时兜底漏放
     */
    internal inline fun <T> keepCpuAwake(timeoutMs: Long, block: () -> T): T {
        val lock = acquireCpuLock(timeoutMs)
        try {
            return block()
        } finally {
            lock?.let { if (it.isHeld) runCatching { it.release() } }
        }
    }

    internal fun acquireCpuLock(timeoutMs: Long): OsPowerManager.WakeLock? = runCatching {
        val pm = FakeContext.get().getSystemService(OsPowerManager::class.java)
            ?: return@runCatching null
        pm.newWakeLock(OsPowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }.getOrElse {
        traceWarn("$TAG: cannot hold cpu wakelock: $it")
        null
    }

    /**
     * lockNow 上锁并 goToSleep 息屏
     * @param isCancelled 录制被取消后不再息屏；lockNow 已经发出去，上锁本身收不回
     */
    fun lockAndSleep(isCancelled: () -> Boolean = { false }): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        if (!wm.lockNow()) {
            traceWarn("$TAG: lockNow unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        val keyguardUp = pollUntil(KEYGUARD_GONE_TIMEOUT_MS) {
            isCancelled() || wm.isKeyguardLocked == true
        }
        if (isCancelled()) {
            traceInfo("$TAG: cancelled after lockNow, leave screen on")
            return WakeUnlockResult.RECORD_CANCELLED
        }
        if (!keyguardUp) {
            // 锁屏方式为「无」时 lockNow 后 keyguard 永不出现；滑动/密码锁屏均会出现，
            // 超时且非 secure 即视为未设置锁屏，此时也无需息屏验证
            if (wm.isKeyguardSecure(0) != true) {
                traceInfo("$TAG: keyguard never appeared and not secure — no lock screen configured")
                return WakeUnlockResult.NO_KEYGUARD
            }
            traceWarn("$TAG: keyguard did not lock after lockNow")
            return WakeUnlockResult.LOCK_FAILED
        }

        val off = ensureScreenOff(pm, isCancelled)
        if (isCancelled()) {
            traceInfo("$TAG: cancelled while turning screen off")
            return WakeUnlockResult.RECORD_CANCELLED
        }
        if (!off) {
            traceWarn("$TAG: screen still on after sleep attempts (keyguard already locked)")
        } else {
            traceInfo("$TAG: screen locked and off")
        }
        return WakeUnlockResult.OK
    }

    internal fun bouncerSettleMs(wakeCostMs: Long): Long =
        (BOUNCER_SETTLE_MS + wakeCostMs * 2).coerceAtMost(BOUNCER_SETTLE_MAX_MS)

    /**
     * 亮屏并确认 keyguard 还在
     * @return 非 null 即调用方应当直接返回的结果码
     */
    private fun wakeAndRequireKeyguard(pm: PowerManager, wm: WindowManager): Int? {
        val startMs = SystemClock.elapsedRealtime()
        val screenWasOn = pm.isScreenOn(0)
        if (!ensureScreenOn(pm)) {
            traceWarn("$TAG: screen did not turn on after wakeUp and key fallback")
            return WakeUnlockResult.WAKE_FAILED
        }
        traceInfo(
            "$TAG: screen on in ${SystemClock.elapsedRealtime() - startMs}ms," +
                    " wasOn=$screenWasOn"
        )

        val locked = wm.isKeyguardLocked
        if (locked == null) {
            traceWarn("$TAG: isKeyguardLocked unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (!locked) {
            traceInfo("$TAG: keyguard not showing, nothing to do")
            return WakeUnlockResult.OK
        }
        return null
    }

    /** 亮屏并解除锁屏；@param credential 纯数字 PIN，无凭证锁屏传空串 */
    fun unlock(credential: String): Int = traced("unlock, ${if (credential.isEmpty()) "no credential" else "PIN"}") {
        doUnlock(credential)
    }

    private fun doUnlock(credential: String): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        val wakeStartMs = SystemClock.elapsedRealtime()
        wakeAndRequireKeyguard(pm, wm)?.let { return it }
        val wakeCostMs = SystemClock.elapsedRealtime() - wakeStartMs

        val secure = wm.isKeyguardSecure(0) ?: false
        traceInfo("$TAG: keyguard locked, secure=$secure")

        if (!wm.dismissKeyguard()) {
            traceWarn("$TAG: dismissKeyguard unavailable on this ROM")
            return WakeUnlockResult.UNSUPPORTED
        }
        traceInfo("$TAG: dismissKeyguard requested")

        if (!secure) {
            return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked == false }) {
                traceInfo("$TAG: unlocked (insecure keyguard)")
                WakeUnlockResult.OK
            } else {
                traceWarn("$TAG: insecure keyguard did not dismiss")
                WakeUnlockResult.CREDENTIAL_REJECTED
            }
        }

        if (credential.isEmpty()) {
            traceWarn("$TAG: secure keyguard but no credential configured")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }
        if (credential.any { !it.isDigit() }) {
            traceWarn("$TAG: only numeric PIN is supported")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }

        // bouncer 弹出期间 isKeyguardLocked 仍为 true，先 settle
        val settleMs = bouncerSettleMs(wakeCostMs)
        Thread.sleep(settleMs)
        // 人脸/信任代理可能在 settle 期间解掉了锁屏，这时再按数字就落进前台应用了
        if (keyguardGone(wm)) {
            traceInfo("$TAG: keyguard gone during ${settleMs}ms settle, skip PIN injection")
            return WakeUnlockResult.OK
        }
        traceInfo(
            "$TAG: injecting PIN after ${settleMs}ms settle" +
                    " (wake ${wakeCostMs}ms)"
        )

        val injectStartMs = SystemClock.elapsedRealtime()
        if (!withSensitiveInput { injectPin(wm, credential) }) {
            traceInfo("$TAG: keyguard gone mid-injection, stopped")
            return WakeUnlockResult.OK
        }
        val padMs = PIN_INJECT_BUDGET_MS - (SystemClock.elapsedRealtime() - injectStartMs)
        if (padMs > 0) Thread.sleep(padMs)
        traceInfo("$TAG: PIN injected")

        val pollStartMs = SystemClock.elapsedRealtime()
        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked == false }) {
            traceInfo("$TAG: unlocked (PIN accepted) after ${SystemClock.elapsedRealtime() - pollStartMs}ms")
            WakeUnlockResult.OK
        } else {
            // 不重试，避免连续输错触发系统锁定
            traceWarn(
                "$TAG: still locked ${SystemClock.elapsedRealtime() - pollStartMs}ms after PIN" +
                        " injection — wrong PIN, or bouncer was not ready and swallowed the keys"
            )
            WakeUnlockResult.CREDENTIAL_REJECTED
        }
    }

    /** 每个键前都确认 keyguard 还在；@return false 表示锁屏中途消失，余下的没注入 */
    private fun injectPin(wm: WindowManager, credential: String): Boolean {
        for (c in credential) {
            if (keyguardGone(wm)) return false
            injectKey(KeyEvent.KEYCODE_0 + (c - '0'))
            Thread.sleep(DIGIT_GAP_MS)
        }
        // 部分 ROM 会自动提交；补 ENTER 兼容需确认的 PIN，已自动提交的别让 ENTER 落到前台
        if (keyguardGone(wm)) return false
        injectKey(KeyEvent.KEYCODE_ENTER)
        return true
    }

    /** 查不到按还在处理：只有确认消失才放弃注入 */
    private fun keyguardGone(wm: WindowManager): Boolean = wm.isKeyguardLocked == false

    /**
     * 亮屏并回放录制的解锁手势
     * @param gestureJson 空串表示未录制
     */
    fun unlockWithGesture(gestureJson: String): Int = traced("unlock, gesture") {
        doUnlockWithGesture(gestureJson)
    }

    private fun doUnlockWithGesture(gestureJson: String): Int {
        val gesture = UnlockGesture.parseOrNull(gestureJson) { traceWarn("$TAG: $it") }
        if (gesture == null || gesture.steps.isEmpty()) {
            traceWarn("$TAG: no usable gesture recorded")
            return WakeUnlockResult.GESTURE_EMPTY
        }

        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        wakeAndRequireKeyguard(pm, wm)?.let { return it }

        // 与录制一致，在 keyguard 还在时采样
        val screen = ScreenGeometry.current()
        if (screen == null) {
            traceWarn("$TAG: display info unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (screen.rotation != gesture.rotation) {
            traceWarn("$TAG: rotation ${screen.rotation} != recorded ${gesture.rotation}")
            return WakeUnlockResult.GESTURE_SCREEN_MISMATCH
        }
        if (screen.width != gesture.screenWidth || screen.height != gesture.screenHeight) {
            traceWarn(
                "$TAG: screen ${screen.width}x${screen.height} != recorded" +
                        " ${gesture.screenWidth}x${gesture.screenHeight}, scaling"
            )
        }

        // 录制起点就是亮屏后的锁屏首屏，这里不能再 dismissKeyguard 打乱状态
        Thread.sleep(GESTURE_SETTLE_MS)
        if (keyguardGone(wm)) {
            traceInfo("$TAG: keyguard gone during settle, skip gesture replay")
            return WakeUnlockResult.OK
        }
        val actions = UnlockGestureReplay.timeline(gesture, screen.width, screen.height)
        if (actions.none { it is InjectAction.Down }) {
            traceWarn("$TAG: gesture has nothing to replay")
            return WakeUnlockResult.GESTURE_EMPTY
        }
        traceInfo("$TAG: replaying gesture")
        val stats = UnlockGestureReplay.executeDetailed(actions) { !keyguardGone(wm) }
        traceInfo("$TAG: replay done, injected $stats")
        if (!stats.completed) {
            traceInfo("$TAG: keyguard gone mid-replay, stopped")
            return WakeUnlockResult.OK
        }

        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked == false }) {
            traceInfo("$TAG: unlocked (gesture accepted)")
            WakeUnlockResult.OK
        } else {
            // 与 PIN 一致：不重试，避免连错触发系统锁定
            val cause = if (stats.allAccepted) "all injected: stale trajectory, or keyguard ignores injected events"
                else "some injections not delivered: events likely blocked by the system"
            traceWarn("$TAG: still locked after gesture replay — $cause")
            WakeUnlockResult.CREDENTIAL_REJECTED
        }
    }

    /** 只亮屏，不碰 keyguard；录制手势时用，和回放走同一条唤醒路径 */
    fun wakeScreen(): Boolean = ensureScreenOn(ServiceManager.getPowerManager())

    private fun ensureScreenOn(pm: PowerManager): Boolean =
        ScreenPowerAttempts.run(
            actions = ScreenPowerAttempts.wakeActions,
            alreadyDone = { pm.isScreenOn(0) },
            perform = { action ->
                when (action) {
                    WakeAction.BINDER -> {
                        if (!pm.wakeUp()) {
                            traceWarn("$TAG: wakeUp() invoke failed, polling then falling back to keys")
                        }
                    }

                    WakeAction.KEY_WAKEUP -> {
                        traceWarn("$TAG: injecting KEYCODE_WAKEUP")
                        injectKey(KeyEvent.KEYCODE_WAKEUP)
                    }

                    WakeAction.KEY_POWER -> if (!pm.isScreenOn(0)) {
                        traceWarn("$TAG: injecting KEYCODE_POWER")
                        injectKey(KeyEvent.KEYCODE_POWER)
                    }
                }
            },
            pollAfter = { action ->
                val timeout = if (action == WakeAction.BINDER) {
                    SCREEN_ON_TIMEOUT_MS
                } else {
                    KEY_WAKE_TIMEOUT_MS
                }
                val on = pollUntil(timeout) { pm.isScreenOn(0) }
                if (!on) traceWarn("$TAG: screen still off after $action")
                on
            },
        )

    /** 取消后不再发下一步息屏动作，返回值此时无意义，调用方自己查 [isCancelled] */
    private fun ensureScreenOff(pm: PowerManager, isCancelled: () -> Boolean): Boolean {
        return ScreenPowerAttempts.run(
            actions = ScreenPowerAttempts.sleepActions,
            alreadyDone = { !pm.isScreenOn(0) },
            perform = { action ->
                if (isCancelled()) return false
                when (action) {
                    SleepAction.BINDER -> {
                        if (!pm.goToSleep()) {
                            traceWarn("$TAG: goToSleep() invoke failed, polling then falling back to keys")
                        }
                    }

                    SleepAction.KEY_SLEEP -> {
                        traceWarn("$TAG: injecting KEYCODE_SLEEP")
                        injectKey(KeyEvent.KEYCODE_SLEEP)
                    }

                    SleepAction.KEY_POWER -> if (pm.isScreenOn(0)) {
                        traceWarn("$TAG: injecting KEYCODE_POWER")
                        injectKey(KeyEvent.KEYCODE_POWER)
                    }
                }
            },
            pollAfter = { action ->
                val timeout = if (action == SleepAction.BINDER) {
                    SCREEN_OFF_TIMEOUT_MS
                } else {
                    KEY_WAKE_TIMEOUT_MS
                }
                val off = pollUntil(timeout) { isCancelled() || !pm.isScreenOn(0) }
                if (!off) traceWarn("$TAG: screen still on after $action")
                off
            },
        )
    }

    private fun injectKey(keyCode: Int) {
        InputControlUtils.keyDown(keyCode, 0)
        InputControlUtils.keyUp(keyCode, 0)
    }

}

/** PIN 与手势回放期间注入层不记键码/坐标、不回调触控预览 */
internal inline fun <T> withSensitiveInput(block: () -> T): T {
    InputControlUtils.beginSensitiveInput()
    try {
        return block()
    } finally {
        InputControlUtils.endSensitiveInput()
    }
}
