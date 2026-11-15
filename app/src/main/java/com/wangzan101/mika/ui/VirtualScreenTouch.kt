package com.wangzan101.mika.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import com.wangzan101.mika.cap.phone.display.PipInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MAX_STREAM_POINTERS = 5

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
)

private class ActiveSlot(
    val slot: Int,
    var normX: Float,
    var normY: Float,
)

/**
 * 虚拟屏实时流式多指触摸修饰符（供展开态 [AgentPip] 与 [PersonalLiveScreenStage] 共用）：
 * - 实时透传 `DOWN` / `POINTER_DOWN` / `MOVE` / `POINTER_UP` / `UP` 到 [PipInput.injectTouch]；
 * - 单协程后台保序发送并自动合并积压的 `MOVE`，不阻塞 UI 主线程；
 * - 手势中断、协程取消或组件卸载时必补发 `ACTION_CANCEL`，杜绝虚拟屏残留按下态；
 * - 若底层助手尚未升级不支持流式注入，自动无感降级到 [PipInput.tap] / [PipInput.swipe]。
 */
internal fun Modifier.virtualScreenTouch(input: PipInput): Modifier = pointerInput(input) {
    coroutineScope {
        val channel = Channel<TouchEventPacket>(Channel.UNLIMITED)
        val worker = launch(Dispatchers.IO) {
            var streaming = false
            var gestureActive = false
            var fallbackDownTime = 0L
            var fallbackLastTime = 0L
            var fallbackMoved = false
            val fallbackPoints = ArrayList<Pair<Float, Float>>(32)
            var lastPacket: TouchEventPacket? = null

            try {
                for (first in channel) {
                    var pkt = first
                    // 若通道中连续积压了多个纯 MOVE 事件，合并到最新一帧以消除跟手延迟；按下/抬起/多指变更绝不跳过
                    if ((pkt.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE) {
                        while (true) {
                            val next = channel.tryReceive().getOrNull() ?: break
                            if ((next.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE) {
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
                                if (streaming) {
                                    input.injectTouch(pkt.action, pkt.downTime, pkt.eventTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                                } else if (pkt.normXs.isNotEmpty()) {
                                    fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                                    fallbackLastTime = pkt.eventTime
                                    if (pkt.movedBeyondSlop) fallbackMoved = true
                                }
                                lastPacket = pkt
                                pkt = next
                                break
                            }
                        }
                    }

                    val masked = pkt.action and MotionEvent.ACTION_MASK
                    when (masked) {
                        MotionEvent.ACTION_DOWN -> {
                            fallbackPoints.clear()
                            fallbackDownTime = pkt.downTime
                            fallbackLastTime = pkt.eventTime
                            fallbackMoved = false
                            if (pkt.normXs.isNotEmpty()) fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                            streaming = runCatching {
                                input.injectTouch(pkt.action, pkt.downTime, pkt.eventTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                            }.getOrDefault(false)
                            gestureActive = true
                            lastPacket = pkt
                        }
                        MotionEvent.ACTION_UP -> {
                            if (streaming) {
                                runCatching {
                                    input.injectTouch(pkt.action, pkt.downTime, pkt.eventTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                                }
                            } else if (gestureActive) {
                                if (pkt.normXs.isNotEmpty()) fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                                fallbackLastTime = pkt.eventTime
                                if (pkt.movedBeyondSlop) fallbackMoved = true
                                val pts = fallbackPoints.toList()
                                if (pts.isNotEmpty()) {
                                    if (!fallbackMoved) {
                                        val (fx, fy) = pts.last()
                                        runCatching { input.tap(fx, fy) }
                                    } else {
                                        val duration = (fallbackLastTime - fallbackDownTime).coerceIn(80L, 600L)
                                        runCatching { input.swipe(pts, duration) }
                                    }
                                }
                            }
                            gestureActive = false
                            streaming = false
                            fallbackPoints.clear()
                            lastPacket = null
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (streaming && gestureActive) {
                                runCatching {
                                    input.injectTouch(pkt.action, pkt.downTime, pkt.eventTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                                }
                            }
                            gestureActive = false
                            streaming = false
                            fallbackPoints.clear()
                            lastPacket = null
                        }
                        else -> {
                            if (streaming && gestureActive) {
                                runCatching {
                                    input.injectTouch(pkt.action, pkt.downTime, pkt.eventTime, pkt.pointerIds, pkt.normXs, pkt.normYs)
                                }
                                lastPacket = pkt
                            } else if (gestureActive && masked == MotionEvent.ACTION_MOVE && pkt.normXs.isNotEmpty()) {
                                fallbackPoints += pkt.normXs[0] to pkt.normYs[0]
                                fallbackLastTime = pkt.eventTime
                                if (pkt.movedBeyondSlop) fallbackMoved = true
                            }
                        }
                    }
                }
            } finally {
                if (streaming && gestureActive) {
                    val tail = lastPacket
                    if (tail != null && tail.pointerIds.isNotEmpty()) {
                        withContext(NonCancellable) {
                            val now = SystemClock.uptimeMillis().coerceAtLeast(tail.eventTime)
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

        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                if (size.width <= 0 || size.height <= 0) return@awaitEachGesture

                val active = LinkedHashMap<PointerId, ActiveSlot>()
                val downTime = down.uptimeMillis
                val startPos: Offset = down.position
                var movedBeyondSlop = false

                fun allocSlot(): Int? {
                    val used = active.values.mapTo(HashSet()) { it.slot }
                    return (0 until MAX_STREAM_POINTERS).firstOrNull { it !in used }
                }

                fun snapshotArrays(): Triple<IntArray, FloatArray, FloatArray> {
                    val ordered = active.values.sortedBy { it.slot }
                    val n = ordered.size
                    val ids = IntArray(n) { ordered[it].slot }
                    val xs = FloatArray(n) { ordered[it].normX }
                    val ys = FloatArray(n) { ordered[it].normY }
                    return Triple(ids, xs, ys)
                }

                fun indexOfSlot(slot: Int): Int =
                    active.values.sortedBy { it.slot }.indexOfFirst { it.slot == slot }

                active[down.id] = ActiveSlot(
                    slot = 0,
                    normX = normalizeTouchCoord(down.position.x, size.width),
                    normY = normalizeTouchCoord(down.position.y, size.height),
                )
                run {
                    val (ids, xs, ys) = snapshotArrays()
                    channel.trySend(TouchEventPacket(MotionEvent.ACTION_DOWN, downTime, downTime, ids, xs, ys))
                }

                var finishedCleanly = false
                try {
                    while (active.isNotEmpty()) {
                        val event = awaitPointerEvent()
                        if (size.width <= 0 || size.height <= 0) break
                        val changes = event.changes
                        changes.forEach { it.consume() }
                        val eventTime = changes.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis()

                        // 1) 先同步仍在场触点的最新坐标
                        var anyPositionChanged = false
                        for (c in changes) {
                            val existing = active[c.id] ?: continue
                            val nx = normalizeTouchCoord(c.position.x, size.width)
                            val ny = normalizeTouchCoord(c.position.y, size.height)
                            if (nx != existing.normX || ny != existing.normY) {
                                existing.normX = nx
                                existing.normY = ny
                                anyPositionChanged = true
                            }
                            if (c.id == down.id && (c.position - startPos).getDistance() > viewConfiguration.touchSlop) {
                                movedBeyondSlop = true
                            }
                        }

                        // 2) 新按下的手指 -> ACTION_POINTER_DOWN
                        for (c in changes) {
                            if (c.pressed && c.id !in active) {
                                val slot = allocSlot() ?: continue
                                active[c.id] = ActiveSlot(
                                    slot = slot,
                                    normX = normalizeTouchCoord(c.position.x, size.width),
                                    normY = normalizeTouchCoord(c.position.y, size.height),
                                )
                                val idx = indexOfSlot(slot)
                                if (idx >= 0 && active.size > 1) {
                                    val (ids, xs, ys) = snapshotArrays()
                                    val act = MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                                    channel.trySend(TouchEventPacket(act, downTime, eventTime, ids, xs, ys, movedBeyondSlop))
                                }
                            }
                        }

                        // 3) 移动事件 -> ACTION_MOVE
                        if (anyPositionChanged && active.isNotEmpty()) {
                            val (ids, xs, ys) = snapshotArrays()
                            channel.trySend(TouchEventPacket(MotionEvent.ACTION_MOVE, downTime, eventTime, ids, xs, ys, movedBeyondSlop))
                        }

                        // 4) 抬起或消失的手指 -> ACTION_POINTER_UP / ACTION_UP
                        val presentPressedIds = changes.filter { it.pressed }.mapTo(HashSet()) { it.id }
                        val releasedIds = active.keys.filter { it !in presentPressedIds }
                        for (rid in releasedIds) {
                            val slotObj = active[rid] ?: continue
                            if (active.size > 1) {
                                val idx = indexOfSlot(slotObj.slot)
                                val (ids, xs, ys) = snapshotArrays()
                                active.remove(rid)
                                if (idx >= 0) {
                                    val act = MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                                    channel.trySend(TouchEventPacket(act, downTime, eventTime, ids, xs, ys, movedBeyondSlop))
                                }
                            } else {
                                val (ids, xs, ys) = snapshotArrays()
                                active.remove(rid)
                                channel.trySend(TouchEventPacket(MotionEvent.ACTION_UP, downTime, eventTime, ids, xs, ys, movedBeyondSlop))
                                finishedCleanly = true
                            }
                        }
                    }
                } finally {
                    if (!finishedCleanly && active.isNotEmpty()) {
                        val (ids, xs, ys) = snapshotArrays()
                        active.clear()
                        channel.trySend(
                            TouchEventPacket(MotionEvent.ACTION_CANCEL, downTime, SystemClock.uptimeMillis(), ids, xs, ys, movedBeyondSlop),
                        )
                    }
                }
            }
        } finally {
            channel.close()
            worker.cancel()
        }
    }
}
