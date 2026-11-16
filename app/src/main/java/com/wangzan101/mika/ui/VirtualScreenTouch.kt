package com.wangzan101.mika.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import com.wangzan101.mika.cap.phone.display.PipInput
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MAX_STREAM_POINTERS = 5

/**
 * 单指静止按压超过该阈值（毫秒）仍未抬手且未越过 `touchSlop` 时，立即向虚拟屏补发 `ACTION_DOWN`：
 * - 在共享焦点系统（Android 13 及以下或无 `OWN_FOCUS` 的虚拟屏）上，手指触碰主屏会在 `+24~44ms` 触发一次
 *   SurfaceFlinger -> InputDispatcher 焦点抢占（虚拟屏窗口收到 `FocusEvent(hasFocus=false)` 并执行
 *   `View.onWindowFocusChanged(false)` 清空 `PFLAG_PRESSED` / `PFLAG_PREPRESSED` / 长按回调）；
 * - `95ms` 晚于跨屏焦点切换窗口，同时与原生可滚动容器 `ViewConfiguration.getTapTimeout() (100ms)` 对齐，
 *   并为 `500ms` 原地长按留出充足余量，使慢按与原地长按均不会被焦点切换打断。
 */
internal const val HOLD_FLUSH_MS = 95L

/**
 * 快速轻触（未越过 `touchSlop` 且在 [HOLD_FLUSH_MS] 前抬手）注入虚拟屏前的最短跨屏焦点稳定等待（毫秒）：
 * 确保主屏手指已抬起、且主屏引发的虚拟屏 `Focus leaving` 及其主线程 `MSG_WINDOW_FOCUS_CHANGED(false)` 已先处理完毕，
 * 再注入干净无干扰的 `ACTION_DOWN -> ACTION_UP` 点击对。
 */
internal const val FOCUS_SETTLE_MIN_MS = 65L

/** 跨屏焦点稳定等待上限（毫秒），防止极端掉帧场景拖慢点击响应。 */
internal const val FOCUS_SETTLE_MAX_MS = 110L

/** 宿主窗口收到 `onWindowFocusChanged(true)` 后额外预留给虚拟屏目标进程消费配对失焦消息的余量（毫秒）。 */
internal const val POST_FOCUS_GAIN_MARGIN_MS = 25L

/** 虚拟屏点击注入的按压保持时长（毫秒），对齐 `ShellService` 与 `GesturePacing` 标准点击时序。 */
internal const val TAP_HOLD_MS = 45L

/** 连续两次手势之间的最小抬手—按下间隔（毫秒），兼容系统 `DOUBLE_TAP_MIN_TIME (40ms)` 双击识别。 */
internal const val MIN_INTER_GESTURE_GAP_MS = 42L

/** 已提前刷出 `ACTION_DOWN` 的静止点击在抬手时保证的最短按压时长（毫秒）。 */
internal const val MIN_TAP_DURATION_MS = 45L

/** 将视图内像素位置归一化为 [0f, 1f]。 */
internal fun normalizeTouchCoord(px: Float, extent: Int): Float {
    if (extent <= 0 || !px.isFinite()) return 0f
    return (px / extent.toFloat()).coerceIn(0f, 1f)
}

/** 将 [0f, 1f] 归一化坐标换算到虚拟屏逻辑像素区间 [0, displayExtent - 1]。 */
internal fun scaleTouchToDisplay(norm: Float, displayExtent: Int): Float {
    if (displayExtent <= 1 || !norm.isFinite()) return 0f
    return (norm.coerceIn(0f, 1f) * (displayExtent - 1).toFloat()).coerceIn(0f, (displayExtent - 1).toFloat())
}

internal class TouchEventPacket(
    val action: Int,
    val downTime: Long,
    val eventTime: Long,
    val pointerIds: IntArray,
    val normXs: FloatArray,
    val normYs: FloatArray,
    val movedBeyondSlop: Boolean = false,
    val isDeferredTap: Boolean = false,
)

internal data class PointerSample(
    val id: Long,
    val x: Float,
    val y: Float,
    val pressed: Boolean,
    val canceled: Boolean = false,
)

private class ActiveSlot(
    val slot: Int,
    var normX: Float,
    var normY: Float,
)

/**
 * 焦点感知 + 触摸死区钳制状态机（Focus-Safe Deferred Down + Touch-Slop Deadzone Clamping）：
 * 1. **按下暂存（Pending Down）**：手指落到主屏时暂不立即向虚拟屏发 `ACTION_DOWN`，避开共享焦点系统上主屏抢焦点导致的
 *    虚拟屏窗口 `onWindowFocusChanged(false)` 清空点击/长按按下态；
 * 2. **滑动/多指零延迟解锁**：一旦按压期间越过 [touchSlop] 或落下第二根手指，立即补发初始点 `ACTION_DOWN` 并紧跟实时
 *    `ACTION_MOVE` / `ACTION_POINTER_DOWN`，保持 120Hz 跟手滑动与双指缩放零延迟；
 * 3. **原地长按定时刷出**：静止按压满 [HOLD_FLUSH_MS] 时由 [flushPendingDown] 补发 `ACTION_DOWN`，此时跨屏焦点已稳定，
 *    原生按下反馈与 500ms 长按稳定触发；
 * 4. **快速点击原子落定**：在 [HOLD_FLUSH_MS] 内未越过死区即抬手时，忽略抬手帧电容剥离漂移，输出 `isDeferredTap` 包，
 *    由后台协程在跨屏焦点稳定后注入坐标严丝合缝的 `ACTION_DOWN` + `ACTION_UP`。
 */
internal class StreamTouchTracker(
    private val touchSlop: Float,
) {
    private val active = LinkedHashMap<Long, ActiveSlot>()
    private var downTime: Long = 0L
    private var primaryId: Long = 0L
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var slopUnlocked: Boolean = false
    private var downFlushed: Boolean = false

    val hasActivePointers: Boolean
        get() = active.isNotEmpty()

    val isSlopUnlocked: Boolean
        get() = slopUnlocked

    val isDownFlushed: Boolean
        get() = downFlushed

    private fun allocSlot(): Int? {
        val used = active.values.mapTo(HashSet()) { it.slot }
        return (0 until MAX_STREAM_POINTERS).firstOrNull { it !in used }
    }

    private fun snapshotArrays(): Triple<IntArray, FloatArray, FloatArray> {
        val ordered = active.values.sortedBy { it.slot }
        val n = ordered.size
        val ids = IntArray(n) { ordered[it].slot }
        val xs = FloatArray(n) { ordered[it].normX }
        val ys = FloatArray(n) { ordered[it].normY }
        return Triple(ids, xs, ys)
    }

    private fun indexOfSlot(slot: Int): Int =
        active.values.sortedBy { it.slot }.indexOfFirst { it.slot == slot }

    fun onDown(
        id: Long,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        downTimeMs: Long,
    ) {
        active.clear()
        downTime = downTimeMs
        primaryId = id
        startX = x
        startY = y
        slopUnlocked = false
        downFlushed = false
        active[id] = ActiveSlot(
            slot = 0,
            normX = normalizeTouchCoord(x, width),
            normY = normalizeTouchCoord(y, height),
        )
    }

    /**
     * 静止按压达到 [HOLD_FLUSH_MS] 时刷出暂存的 `ACTION_DOWN`，并将手势 `downTime` 对齐到刷出时刻，
     * 使后续长按计时（500ms）与抬手事件共享一致的基准时间。
     */
    fun flushPendingDown(flushTimeMs: Long): TouchEventPacket? {
        if (active.isEmpty() || downFlushed) return null
        downFlushed = true
        downTime = maxOf(downTime, flushTimeMs)
        val (ids, xs, ys) = snapshotArrays()
        return TouchEventPacket(
            action = MotionEvent.ACTION_DOWN,
            downTime = downTime,
            eventTime = downTime,
            pointerIds = ids,
            normXs = xs,
            normYs = ys,
            movedBeyondSlop = slopUnlocked,
        )
    }

    fun onPointerEvent(
        changes: List<PointerSample>,
        width: Int,
        height: Int,
        eventTimeMs: Long,
    ): List<TouchEventPacket> {
        if (active.isEmpty()) return emptyList()
        if (changes.any { it.canceled }) {
            return listOfNotNull(cancelRemaining(eventTimeMs))
        }
        val out = ArrayList<TouchEventPacket>(4)

        // 0) 判定是否解锁死区：多指按下立即解锁；单指仅在持续按压期间（pressed == true）越过 touchSlop 才解锁，忽略抬指瞬间的电容剥离漂移
        if (!slopUnlocked) {
            if (changes.any { it.pressed && it.id !in active }) {
                slopUnlocked = true
            } else {
                val primary = changes.firstOrNull { it.id == primaryId }
                if (primary != null && primary.pressed && hypot(primary.x - startX, primary.y - startY) > touchSlop) {
                    slopUnlocked = true
                }
            }
        }

        // 0.5) 一旦在按压期间解锁死区而 ACTION_DOWN 尚未发出，先在初始按下点补发 ACTION_DOWN，再紧跟后续移动或多指按下
        if (!downFlushed && slopUnlocked) {
            downFlushed = true
            val (dIds, dXs, dYs) = snapshotArrays()
            out += TouchEventPacket(
                action = MotionEvent.ACTION_DOWN,
                downTime = downTime,
                eventTime = downTime,
                pointerIds = dIds,
                normXs = dXs,
                normYs = dYs,
                movedBeyondSlop = true,
            )
        }

        // 1) 已解锁死区后同步在场触点的最新坐标；未解锁时保持按下时的初始坐标严格不变
        var anyPressedPositionChanged = false
        if (slopUnlocked) {
            for (c in changes) {
                val existing = active[c.id] ?: continue
                val nx = normalizeTouchCoord(c.x, width)
                val ny = normalizeTouchCoord(c.y, height)
                if (nx != existing.normX || ny != existing.normY) {
                    existing.normX = nx
                    existing.normY = ny
                    if (c.pressed) {
                        anyPressedPositionChanged = true
                    }
                }
            }
        }

        // 2) 新按下的手指 -> ACTION_POINTER_DOWN
        var emittedPointerDown = false
        for (c in changes) {
            if (c.pressed && c.id !in active) {
                val slot = allocSlot() ?: continue
                active[c.id] = ActiveSlot(
                    slot = slot,
                    normX = normalizeTouchCoord(c.x, width),
                    normY = normalizeTouchCoord(c.y, height),
                )
                val idx = indexOfSlot(slot)
                if (idx >= 0 && active.size > 1) {
                    val (ids, xs, ys) = snapshotArrays()
                    val act = MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    out += TouchEventPacket(act, downTime, maxOf(eventTimeMs, downTime), ids, xs, ys, slopUnlocked)
                    emittedPointerDown = true
                }
            }
        }

        // 3) 移动事件 -> ACTION_MOVE（死区内不发；最后一指抬起帧不重复补发 0ms 间隔的冗余 MOVE）
        val presentPressedIds = changes.filter { it.pressed }.mapTo(HashSet()) { it.id }
        if (downFlushed && slopUnlocked && anyPressedPositionChanged && !emittedPointerDown && presentPressedIds.isNotEmpty() && active.isNotEmpty()) {
            val (ids, xs, ys) = snapshotArrays()
            out += TouchEventPacket(
                action = MotionEvent.ACTION_MOVE,
                downTime = downTime,
                eventTime = maxOf(eventTimeMs, downTime),
                pointerIds = ids,
                normXs = xs,
                normYs = ys,
                movedBeyondSlop = true,
            )
        }

        // 4) 抬起或消失的手指 -> ACTION_POINTER_UP / ACTION_UP（若尚未发出 DOWN 且未越过死区，则作为原子延迟点击包发出）
        val releasedIds = active.keys.filter { it !in presentPressedIds }
        for (rid in releasedIds) {
            val slotObj = active[rid] ?: continue
            if (active.size > 1) {
                val idx = indexOfSlot(slotObj.slot)
                val (ids, xs, ys) = snapshotArrays()
                active.remove(rid)
                if (idx >= 0 && downFlushed) {
                    val act = MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    out += TouchEventPacket(act, downTime, maxOf(eventTimeMs, downTime), ids, xs, ys, slopUnlocked)
                }
            } else {
                val (ids, xs, ys) = snapshotArrays()
                active.remove(rid)
                if (!downFlushed) {
                    out += TouchEventPacket(
                        action = MotionEvent.ACTION_UP,
                        downTime = downTime,
                        eventTime = maxOf(eventTimeMs, downTime),
                        pointerIds = ids,
                        normXs = xs,
                        normYs = ys,
                        movedBeyondSlop = false,
                        isDeferredTap = true,
                    )
                } else {
                    out += TouchEventPacket(
                        action = MotionEvent.ACTION_UP,
                        downTime = downTime,
                        eventTime = maxOf(eventTimeMs, downTime),
                        pointerIds = ids,
                        normXs = xs,
                        normYs = ys,
                        movedBeyondSlop = slopUnlocked,
                    )
                }
            }
        }

        return out
    }

    fun cancelRemaining(cancelTimeMs: Long): TouchEventPacket? {
        if (active.isEmpty()) return null
        val shouldSendCancel = downFlushed
        val (ids, xs, ys) = snapshotArrays()
        active.clear()
        downFlushed = false
        if (!shouldSendCancel) return null
        return TouchEventPacket(
            action = MotionEvent.ACTION_CANCEL,
            downTime = downTime,
            eventTime = maxOf(cancelTimeMs, downTime),
            pointerIds = ids,
            normXs = xs,
            normYs = ys,
            movedBeyondSlop = slopUnlocked,
        )
    }
}

internal suspend fun consumeTouchChannel(
    channel: ReceiveChannel<TouchEventPacket>,
    input: PipInput,
    nowUptimeMs: () -> Long = SystemClock::uptimeMillis,
    sleepMs: suspend (Long) -> Unit = ::delay,
    lastHostFocusGainUptimeMs: () -> Long = { 0L },
) {
    var streaming = false
    var gestureActive = false
    var activeStreamDownTime = 0L
    var lastInjectedUpTime = 0L
    var fallbackDownTime = 0L
    var fallbackLastTime = 0L
    var fallbackMoved = false
    val fallbackPoints = ArrayList<Pair<Float, Float>>(32)
    var lastPacket: TouchEventPacket? = null

    try {
        for (first in channel) {
            var pkt = first
            // 若通道中连续积压了多个纯 MOVE 事件，合并到最新一帧以消除跟手延迟；按下/抬起/多指变更绝不跳过
            if (!pkt.isDeferredTap && (pkt.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE) {
                while (true) {
                    val next = channel.tryReceive().getOrNull() ?: break
                    if (!next.isDeferredTap && (next.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE) {
                        pkt = TouchEventPacket(
                            action = next.action,
                            downTime = next.downTime,
                            eventTime = next.eventTime,
                            pointerIds = next.pointerIds,
                            normXs = next.normXs,
                            normYs = next.normYs,
                            movedBeyondSlop = pkt.movedBeyondSlop || next.movedBeyondSlop,
                        )
                    } else {
                        // 先处理合并后的最新 MOVE，再紧接着处理状态跳变包
                        if (streaming && gestureActive) {
                            val evTime = maxOf(pkt.eventTime, activeStreamDownTime)
                            runCatching {
                                input.injectTouch(pkt.action, activeStreamDownTime, evTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                            }
                            lastPacket = TouchEventPacket(
                                action = pkt.action,
                                downTime = activeStreamDownTime,
                                eventTime = evTime,
                                pointerIds = pkt.pointerIds,
                                normXs = pkt.normXs,
                                normYs = pkt.normYs,
                                movedBeyondSlop = pkt.movedBeyondSlop,
                            )
                        } else if (gestureActive && pkt.normXs.isNotEmpty()) {
                            fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                            fallbackLastTime = pkt.eventTime
                            if (pkt.movedBeyondSlop) fallbackMoved = true
                        }
                        pkt = next
                        break
                    }
                }
            }

            if (pkt.isDeferredTap) {
                repeat(2) { pass ->
                    val now = nowUptimeMs()
                    val focusGain = lastHostFocusGainUptimeMs()
                    val focusTarget = if (focusGain >= pkt.downTime) {
                        (focusGain + POST_FOCUS_GAIN_MARGIN_MS).coerceAtMost(pkt.downTime + FOCUS_SETTLE_MAX_MS)
                    } else {
                        0L
                    }
                    val gapTarget = if (lastInjectedUpTime > 0L) lastInjectedUpTime + MIN_INTER_GESTURE_GAP_MS else 0L
                    val target = maxOf(pkt.downTime + FOCUS_SETTLE_MIN_MS, focusTarget, gapTarget)
                    val wait = (target - now).coerceAtMost(FOCUS_SETTLE_MAX_MS)
                    if (wait <= 0L) return@repeat
                    sleepMs(wait)
                    if (nowUptimeMs() <= now && pass == 0) return@repeat
                }

                val tapDownTime = nowUptimeMs()
                val downOk = runCatching {
                    input.injectTouch(
                        MotionEvent.ACTION_DOWN,
                        tapDownTime,
                        tapDownTime,
                        pkt.pointerIds,
                        pkt.normXs,
                        pkt.normYs,
                    )
                }.getOrDefault(false)

                if (downOk) {
                    streaming = true
                    gestureActive = true
                    activeStreamDownTime = tapDownTime
                    lastPacket = TouchEventPacket(
                        action = MotionEvent.ACTION_DOWN,
                        downTime = tapDownTime,
                        eventTime = tapDownTime,
                        pointerIds = pkt.pointerIds,
                        normXs = pkt.normXs,
                        normYs = pkt.normYs,
                        movedBeyondSlop = false,
                    )
                    sleepMs(TAP_HOLD_MS)
                    val tapUpTime = maxOf(nowUptimeMs(), tapDownTime + TAP_HOLD_MS)
                    runCatching {
                        input.injectTouch(
                            MotionEvent.ACTION_UP,
                            tapDownTime,
                            tapUpTime,
                            pkt.pointerIds,
                            pkt.normXs,
                            pkt.normYs,
                        )
                    }
                    lastInjectedUpTime = tapUpTime
                } else {
                    val fx = pkt.normXs.getOrElse(0) { 0f }
                    val fy = pkt.normYs.getOrElse(0) { 0f }
                    try {
                        input.tap(fx, fy)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                    }
                }
                gestureActive = false
                streaming = false
                fallbackPoints.clear()
                lastPacket = null
                continue
            }

            val masked = pkt.action and MotionEvent.ACTION_MASK
            when (masked) {
                MotionEvent.ACTION_DOWN -> {
                    fallbackPoints.clear()
                    val downTs = if (lastInjectedUpTime > 0L && pkt.downTime <= lastInjectedUpTime) {
                        maxOf(nowUptimeMs(), lastInjectedUpTime)
                    } else {
                        pkt.downTime
                    }
                    activeStreamDownTime = downTs
                    fallbackDownTime = pkt.downTime
                    fallbackLastTime = pkt.eventTime
                    fallbackMoved = pkt.movedBeyondSlop
                    if (pkt.normXs.isNotEmpty()) fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                    streaming = runCatching {
                        input.injectTouch(pkt.action, downTs, downTs, pkt.pointerIds, pkt.normXs, pkt.normYs)
                    }.getOrDefault(false)
                    gestureActive = true
                    lastPacket = TouchEventPacket(
                        action = pkt.action,
                        downTime = downTs,
                        eventTime = downTs,
                        pointerIds = pkt.pointerIds,
                        normXs = pkt.normXs,
                        normYs = pkt.normYs,
                        movedBeyondSlop = pkt.movedBeyondSlop,
                    )
                }
                MotionEvent.ACTION_UP -> {
                    if (streaming) {
                        val baseEventTime = maxOf(pkt.eventTime, activeStreamDownTime)
                        val upEventTime = if (!pkt.movedBeyondSlop) {
                            val targetTime = maxOf(baseEventTime, activeStreamDownTime + MIN_TAP_DURATION_MS)
                            val waitMs = (targetTime - nowUptimeMs()).coerceAtMost(MIN_TAP_DURATION_MS)
                            if (waitMs > 0L) {
                                sleepMs(waitMs)
                                maxOf(nowUptimeMs(), targetTime)
                            } else {
                                targetTime
                            }
                        } else {
                            baseEventTime
                        }
                        runCatching {
                            input.injectTouch(pkt.action, activeStreamDownTime, upEventTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                        }
                        lastInjectedUpTime = upEventTime
                    } else if (gestureActive) {
                        if (pkt.normXs.isNotEmpty()) fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                        fallbackLastTime = maxOf(pkt.eventTime, fallbackDownTime)
                        if (pkt.movedBeyondSlop) fallbackMoved = true
                        val pts = fallbackPoints.toList()
                        if (pts.isNotEmpty()) {
                            try {
                                if (!fallbackMoved) {
                                    val (fx, fy) = pts.first()
                                    input.tap(fx, fy)
                                } else {
                                    val duration = (fallbackLastTime - fallbackDownTime).coerceIn(80L, 600L)
                                    input.swipe(pts, duration)
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                            }
                        }
                    }
                    gestureActive = false
                    streaming = false
                    fallbackPoints.clear()
                    lastPacket = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (gestureActive) {
                        val evTime = maxOf(pkt.eventTime, activeStreamDownTime)
                        runCatching {
                            input.injectTouch(pkt.action, activeStreamDownTime, evTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                        }
                        if (streaming) {
                            lastInjectedUpTime = evTime
                        }
                    }
                    gestureActive = false
                    streaming = false
                    fallbackPoints.clear()
                    lastPacket = null
                }
                else -> {
                    if (streaming && gestureActive) {
                        val evTime = maxOf(pkt.eventTime, activeStreamDownTime)
                        runCatching {
                            input.injectTouch(pkt.action, activeStreamDownTime, evTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                        }
                        lastPacket = TouchEventPacket(
                            action = pkt.action,
                            downTime = activeStreamDownTime,
                            eventTime = evTime,
                            pointerIds = pkt.pointerIds,
                            normXs = pkt.normXs,
                            normYs = pkt.normYs,
                            movedBeyondSlop = pkt.movedBeyondSlop,
                        )
                    } else if (gestureActive && masked == MotionEvent.ACTION_MOVE && pkt.normXs.isNotEmpty()) {
                        fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                        fallbackLastTime = maxOf(pkt.eventTime, fallbackDownTime)
                        if (pkt.movedBeyondSlop) fallbackMoved = true
                    }
                }
            }
        }
    } finally {
        if (gestureActive) {
            val tail = lastPacket
            if (tail != null && tail.pointerIds.isNotEmpty()) {
                withContext(NonCancellable) {
                    val now = nowUptimeMs().coerceAtLeast(tail.eventTime)
                    runCatching {
                        input.injectTouch(
                            MotionEvent.ACTION_CANCEL,
                            tail.downTime,
                            now,
                            tail.pointerIds,
                            tail.normXs,
                            tail.normYs,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 虚拟屏实时流式多指触摸修饰符（供展开态 [AgentPip] 与 [PersonalLiveScreenStage] 共用）：
 * - 实时透传 `DOWN` / `POINTER_DOWN` / `MOVE` / `POINTER_UP` / `UP` 到 [PipInput.injectTouch]；
 * - 通过 [StreamTouchTracker] 结合宿主窗口焦点监听化解共享焦点系统上的跨屏焦点抢占，并执行触摸死区钳制，
 *   确保轻触点击稳定触发，同时保留零延迟滑动、原地长按与双指缩放；
 * - 单协程后台保序发送并自动合并积压的 `MOVE`，不阻塞 UI 主线程；
 * - 手势中断、协程取消或组件卸载时必补发 `ACTION_CANCEL`，杜绝虚拟屏残留按下态；
 * - 若底层助手尚未升级不支持流式注入，自动无感降级到 [PipInput.tap] / [PipInput.swipe]。
 */
@Composable
internal fun Modifier.virtualScreenTouch(input: PipInput): Modifier {
    val hostView = LocalView.current
    val lastHostFocusGainUptimeMs = remember { AtomicLong(0L) }
    DisposableEffect(hostView) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                lastHostFocusGainUptimeMs.set(SystemClock.uptimeMillis())
            }
        }
        hostView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            hostView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }

    return this.pointerInput(input) {
        coroutineScope {
            val channel = Channel<TouchEventPacket>(Channel.UNLIMITED)
            val worker = launch(Dispatchers.IO) {
                consumeTouchChannel(
                    channel = channel,
                    input = input,
                    lastHostFocusGainUptimeMs = lastHostFocusGainUptimeMs::get,
                )
            }

            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (size.width <= 0 || size.height <= 0) return@awaitEachGesture

                    val tracker = StreamTouchTracker(touchSlop = viewConfiguration.touchSlop)
                    val downUptime = down.uptimeMillis
                    tracker.onDown(
                        id = down.id.value,
                        x = down.position.x,
                        y = down.position.y,
                        width = size.width,
                        height = size.height,
                        downTimeMs = downUptime,
                    )

                    var finishedCleanly = false
                    try {
                        while (tracker.hasActivePointers) {
                            val event = if (!tracker.isDownFlushed) {
                                val elapsed = (SystemClock.uptimeMillis() - downUptime).coerceAtLeast(0L)
                                val remainMs = (HOLD_FLUSH_MS - elapsed).coerceAtLeast(1L)
                                withTimeoutOrNull(remainMs) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                tracker.flushPendingDown(SystemClock.uptimeMillis())?.let { channel.trySend(it) }
                                continue
                            }

                            if (size.width <= 0 || size.height <= 0) break
                            val changes = event.changes
                            val samples = changes.map { c ->
                                PointerSample(
                                    id = c.id.value,
                                    x = c.position.x,
                                    y = c.position.y,
                                    pressed = c.pressed,
                                    canceled = c.isConsumed,
                                )
                            }
                            changes.forEach { if (!it.isConsumed) it.consume() }
                            val eventTime = changes.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis()

                            val packets = tracker.onPointerEvent(
                                changes = samples,
                                width = size.width,
                                height = size.height,
                                eventTimeMs = eventTime,
                            )

                            for (pkt in packets) {
                                channel.trySend(pkt)
                                val maskedAction = pkt.action and MotionEvent.ACTION_MASK
                                if (maskedAction == MotionEvent.ACTION_UP || maskedAction == MotionEvent.ACTION_CANCEL) {
                                    finishedCleanly = true
                                }
                            }
                            if (samples.any { it.canceled }) {
                                finishedCleanly = true
                                break
                            }
                        }
                    } finally {
                        if (!finishedCleanly && tracker.hasActivePointers) {
                            tracker.cancelRemaining(SystemClock.uptimeMillis())?.let { channel.trySend(it) }
                        }
                    }
                }
            } finally {
                channel.close()
                worker.cancel()
            }
        }
    }
}
