package com.wangzan101.mika.ui

import android.view.MotionEvent
import com.wangzan101.mika.cap.phone.display.PipInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenTouchTest {
    @Test
    fun normalizeTouchCoordClampsEdgesAndHandlesDegenerateInputs() {
        assertEquals(0f, normalizeTouchCoord(0f, 1080), 0.0001f)
        assertEquals(0.5f, normalizeTouchCoord(540f, 1080), 0.0001f)
        assertEquals(1f, normalizeTouchCoord(1080f, 1080), 0.0001f)

        // Out-of-bounds drag coordinates clamp cleanly into [0, 1]
        assertEquals(0f, normalizeTouchCoord(-42f, 1080), 0.0001f)
        assertEquals(1f, normalizeTouchCoord(1500f, 1080), 0.0001f)

        // Degenerate extents & non-finite floats never produce NaN
        assertEquals(0f, normalizeTouchCoord(100f, 0), 0.0001f)
        assertEquals(0f, normalizeTouchCoord(Float.NaN, 1080), 0.0001f)
        assertEquals(0f, normalizeTouchCoord(Float.POSITIVE_INFINITY, 1080), 0.0001f)
    }

    @Test
    fun scaleTouchToDisplayMapsFullNormalizedRangeWithinPixelBounds() {
        assertEquals(0f, scaleTouchToDisplay(0f, 1080), 0.0001f)
        assertEquals(1079f, scaleTouchToDisplay(1f, 1080), 0.0001f)
        assertEquals(539.5f, scaleTouchToDisplay(0.5f, 1080), 0.01f)
        assertEquals(2399f, scaleTouchToDisplay(1f, 2400), 0.0001f)

        // Clamps out-of-range and degenerate values safely
        assertEquals(0f, scaleTouchToDisplay(-0.2f, 1080), 0.0001f)
        assertEquals(1079f, scaleTouchToDisplay(1.4f, 1080), 0.0001f)
        assertEquals(0f, scaleTouchToDisplay(0.5f, 1), 0.0001f)
        assertEquals(0f, scaleTouchToDisplay(Float.NaN, 1080), 0.0001f)
    }

    @Test
    fun quickTapDefersDownClampsSubSlopJitterAndIgnoresLiftOffPeelDrift() {
        val tracker = StreamTouchTracker(touchSlop = 24f)
        val width = 540
        val height = 1200

        tracker.onDown(id = 1L, x = 270f, y = 600f, width = width, height = height, downTimeMs = 1000L)
        assertTrue(tracker.hasActivePointers)
        assertFalse(tracker.isDownFlushed)
        assertFalse(tracker.isSlopUnlocked)

        // Press micro-jitter within touchSlop (hypot(12, -10) = 15.62 <= 24): suppressed completely
        val move1 = tracker.onPointerEvent(
            changes = listOf(PointerSample(id = 1L, x = 282f, y = 590f, pressed = true)),
            width = width,
            height = height,
            eventTimeMs = 1016L,
        )
        assertTrue(move1.isEmpty())
        assertFalse(tracker.isDownFlushed)

        val move2 = tracker.onPointerEvent(
            changes = listOf(PointerSample(id = 1L, x = 260f, y = 615f, pressed = true)),
            width = width,
            height = height,
            eventTimeMs = 1032L,
        )
        assertTrue(move2.isEmpty())

        // Capacitive peel drift on release frame (!pressed) exceeds touchSlop (hypot(32, 32) = 45.25 > 24),
        // but must NOT unlock deadzone or shift the tap coordinate; emits atomic isDeferredTap packet.
        val upPackets = tracker.onPointerEvent(
            changes = listOf(PointerSample(id = 1L, x = 302f, y = 632f, pressed = false)),
            width = width,
            height = height,
            eventTimeMs = 1055L,
        )
        assertEquals(1, upPackets.size)
        val up = upPackets.single()
        assertEquals(MotionEvent.ACTION_UP, up.action)
        assertTrue(up.isDeferredTap)
        assertEquals(1000L, up.downTime)
        assertEquals(1055L, up.eventTime)
        assertEquals(0.5f, up.normXs[0], 0.0001f)
        assertEquals(0.5f, up.normYs[0], 0.0001f)
        assertFalse(up.movedBeyondSlop)
        assertFalse(tracker.hasActivePointers)
    }

    @Test
    fun stationaryLongPressFlushesPendingDownAfterHoldThresholdAndStaysClampedWithoutMoveNoise() {
        val tracker = StreamTouchTracker(touchSlop = 24f)
        tracker.onDown(id = 7L, x = 108f, y = 240f, width = 540, height = 1200, downTimeMs = 2000L)
        assertFalse(tracker.isDownFlushed)

        // Sub-slop wobble before HOLD_FLUSH_MS stays buffered
        assertTrue(
            tracker.onPointerEvent(
                changes = listOf(PointerSample(id = 7L, x = 114f, y = 235f, pressed = true)),
                width = 540,
                height = 1200,
                eventTimeMs = 2050L,
            ).isEmpty(),
        )

        // Hold timeout at 2095ms flushes clean ACTION_DOWN after cross-display focus has settled
        val flushedDown = tracker.flushPendingDown(2095L)
        assertNotNull(flushedDown)
        assertEquals(MotionEvent.ACTION_DOWN, flushedDown!!.action)
        assertEquals(2095L, flushedDown.downTime)
        assertEquals(2095L, flushedDown.eventTime)
        assertEquals(0.2f, flushedDown.normXs[0], 0.0001f)
        assertEquals(0.2f, flushedDown.normYs[0], 0.0001f)
        assertFalse(flushedDown.isDeferredTap)
        assertTrue(tracker.isDownFlushed)
        // Idempotent if called again
        assertNull(tracker.flushPendingDown(2120L))

        // Hold for another 550ms with small finger wobble inside touchSlop -> zero MOVE noise
        for (i in 1..9) {
            val pkts = tracker.onPointerEvent(
                changes = listOf(PointerSample(id = 7L, x = 108f + (i % 5) * 3f, y = 240f - (i % 4) * 3f, pressed = true)),
                width = 540,
                height = 1200,
                eventTimeMs = 2100L + i * 60L,
            )
            assertTrue(pkts.isEmpty())
        }

        val up = tracker.onPointerEvent(
            changes = listOf(PointerSample(id = 7L, x = 115f, y = 248f, pressed = false)),
            width = 540,
            height = 1200,
            eventTimeMs = 2700L,
        ).single()
        assertEquals(MotionEvent.ACTION_UP, up.action)
        assertFalse(up.isDeferredTap)
        assertEquals(2095L, up.downTime)
        assertEquals(2700L, up.eventTime)
        assertEquals(0.2f, up.normXs[0], 0.0001f)
        assertEquals(0.2f, up.normYs[0], 0.0001f)
        assertFalse(up.movedBeyondSlop)
    }

    @Test
    fun dragCrossingSlopImmediatelyPrependsPendingDownFollowedByRealTimeMove() {
        val tracker = StreamTouchTracker(touchSlop = 20f)
        val width = 500
        val height = 1000

        tracker.onDown(id = 1L, x = 250f, y = 500f, width = width, height = height, downTimeMs = 100L)

        // Sub-slop frame stays silent
        assertTrue(
            tracker.onPointerEvent(
                changes = listOf(PointerSample(id = 1L, x = 250f, y = 512f, pressed = true)),
                width = width,
                height = height,
                eventTimeMs = 116L,
            ).isEmpty(),
        )
        assertFalse(tracker.isDownFlushed)

        // Crosses touchSlop while pressed -> immediately flushes initial ACTION_DOWN + real-time ACTION_MOVE
        val unlockFrame = tracker.onPointerEvent(
            changes = listOf(PointerSample(id = 1L, x = 250f, y = 530f, pressed = true)),
            width = width,
            height = height,
            eventTimeMs = 132L,
        )
        assertEquals(2, unlockFrame.size)
        assertEquals(MotionEvent.ACTION_DOWN, unlockFrame[0].action)
        assertEquals(0.5f, unlockFrame[0].normXs[0], 0.0001f)
        assertEquals(0.5f, unlockFrame[0].normYs[0], 0.0001f)
        assertEquals(100L, unlockFrame[0].downTime)
        assertTrue(unlockFrame[0].movedBeyondSlop)

        assertEquals(MotionEvent.ACTION_MOVE, unlockFrame[1].action)
        assertEquals(0.53f, unlockFrame[1].normYs[0], 0.0001f)
        assertEquals(132L, unlockFrame[1].eventTime)
        assertTrue(unlockFrame[1].movedBeyondSlop)

        // Subsequent drag frame continues streaming immediately (even small 2px increments once unlocked)
        val fineMoveFrame = tracker.onPointerEvent(
            changes = listOf(PointerSample(id = 1L, x = 250f, y = 532f, pressed = true)),
            width = width,
            height = height,
            eventTimeMs = 148L,
        )
        assertEquals(1, fineMoveFrame.size)
        assertEquals(MotionEvent.ACTION_MOVE, fineMoveFrame[0].action)
        assertEquals(0.532f, fineMoveFrame[0].normYs[0], 0.0001f)

        // Final release frame updates coordinate on ACTION_UP without emitting redundant 0ms-delta ACTION_MOVE
        val releaseFrame = tracker.onPointerEvent(
            changes = listOf(PointerSample(id = 1L, x = 250f, y = 700f, pressed = false)),
            width = width,
            height = height,
            eventTimeMs = 164L,
        )
        assertEquals(1, releaseFrame.size)
        assertEquals(MotionEvent.ACTION_UP, releaseFrame[0].action)
        assertFalse(releaseFrame[0].isDeferredTap)
        assertEquals(0.7f, releaseFrame[0].normYs[0], 0.0001f)
        assertTrue(releaseFrame[0].movedBeyondSlop)
    }

    @Test
    fun multiTouchUnlocksImmediatelyPrependingPrimaryDownBeforeSecondPointerDown() {
        val tracker = StreamTouchTracker(touchSlop = 24f)
        val width = 1000
        val height = 1000

        tracker.onDown(id = 10L, x = 400f, y = 400f, width = width, height = height, downTimeMs = 500L)

        // Finger 1 shifts slightly (5px < 24px) while Finger 2 lands -> flushes primary DOWN + POINTER_DOWN
        val secondDown = tracker.onPointerEvent(
            changes = listOf(
                PointerSample(id = 10L, x = 405f, y = 400f, pressed = true),
                PointerSample(id = 20L, x = 600f, y = 600f, pressed = true),
            ),
            width = width,
            height = height,
            eventTimeMs = 520L,
        )
        assertEquals(2, secondDown.size)
        assertEquals(MotionEvent.ACTION_DOWN, secondDown[0].action)
        assertArrayEquals(intArrayOf(0), secondDown[0].pointerIds)
        assertEquals(0.4f, secondDown[0].normXs[0], 0.0001f)

        val expectedPointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        assertEquals(expectedPointerDown, secondDown[1].action)
        assertArrayEquals(intArrayOf(0, 1), secondDown[1].pointerIds)
        assertEquals(0.405f, secondDown[1].normXs[0], 0.0001f)
        assertEquals(0.6f, secondDown[1].normXs[1], 0.0001f)
        assertTrue(secondDown[1].movedBeyondSlop)

        // Subtle 4px pinch immediately streams ACTION_MOVE
        val pinchMove = tracker.onPointerEvent(
            changes = listOf(
                PointerSample(id = 10L, x = 401f, y = 400f, pressed = true),
                PointerSample(id = 20L, x = 604f, y = 600f, pressed = true),
            ),
            width = width,
            height = height,
            eventTimeMs = 536L,
        )
        assertEquals(1, pinchMove.size)
        assertEquals(MotionEvent.ACTION_MOVE, pinchMove[0].action)
        assertEquals(0.401f, pinchMove[0].normXs[0], 0.0001f)
        assertEquals(0.604f, pinchMove[0].normXs[1], 0.0001f)

        // Finger 2 lifts while Finger 1 stays steady
        val pointerUp = tracker.onPointerEvent(
            changes = listOf(
                PointerSample(id = 10L, x = 401f, y = 400f, pressed = true),
                PointerSample(id = 20L, x = 608f, y = 600f, pressed = false),
            ),
            width = width,
            height = height,
            eventTimeMs = 552L,
        )
        assertEquals(1, pointerUp.size)
        val expectedPointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        assertEquals(expectedPointerUp, pointerUp[0].action)
    }

    @Test
    fun cancelRemainingOnlyEmitsCancelWhenDownWasAlreadyFlushed() {
        val unFlushed = StreamTouchTracker(touchSlop = 24f)
        unFlushed.onDown(id = 1L, x = 100f, y = 100f, width = 500, height = 500, downTimeMs = 300L)
        assertNull(unFlushed.cancelRemaining(320L))
        assertFalse(unFlushed.hasActivePointers)

        val flushed = StreamTouchTracker(touchSlop = 24f)
        flushed.onDown(id = 2L, x = 100f, y = 100f, width = 500, height = 500, downTimeMs = 400L)
        assertNotNull(flushed.flushPendingDown(495L))
        val cancelPkt = flushed.cancelRemaining(510L)
        assertNotNull(cancelPkt)
        assertEquals(MotionEvent.ACTION_CANCEL, cancelPkt!!.action)
        assertEquals(510L, cancelPkt.eventTime)
    }

    @Test
    fun systemCanceledPointerChangesNeverInjectPhantomDeferredTapAndEmitActionCancelWhenFlushed() {
        // 1) System edge-back / 3-finger screenshot cancels pointer BEFORE HOLD_FLUSH_MS: must NOT emit isDeferredTap!
        val quickCanceled = StreamTouchTracker(touchSlop = 24f)
        quickCanceled.onDown(id = 1L, x = 20f, y = 600f, width = 540, height = 1200, downTimeMs = 1000L)
        val preFlushOut = quickCanceled.onPointerEvent(
            changes = listOf(PointerSample(id = 1L, x = 28f, y = 600f, pressed = false, canceled = true)),
            width = 540,
            height = 1200,
            eventTimeMs = 1035L,
        )
        assertTrue("Canceled pre-flush gesture must never inject phantom tap", preFlushOut.isEmpty())
        assertFalse(quickCanceled.hasActivePointers)

        // 2) System gesture cancels pointer AFTER drag unlocked: must emit ACTION_CANCEL instead of ACTION_UP!
        val dragCanceled = StreamTouchTracker(touchSlop = 20f)
        dragCanceled.onDown(id = 2L, x = 250f, y = 500f, width = 500, height = 1000, downTimeMs = 2000L)
        val movePkts = dragCanceled.onPointerEvent(
            changes = listOf(PointerSample(id = 2L, x = 250f, y = 540f, pressed = true)),
            width = 500,
            height = 1000,
            eventTimeMs = 2020L,
        )
        assertEquals(2, movePkts.size)
        val cancelOut = dragCanceled.onPointerEvent(
            changes = listOf(PointerSample(id = 2L, x = 250f, y = 560f, pressed = false, canceled = true)),
            width = 500,
            height = 1000,
            eventTimeMs = 2040L,
        )
        assertEquals(1, cancelOut.size)
        assertEquals(MotionEvent.ACTION_CANCEL, cancelOut.single().action)
        assertFalse(cancelOut.single().isDeferredTap)
        assertFalse(dragCanceled.hasActivePointers)
    }

    @Test
    fun consumeTouchChannelWaitsForFocusSettleAndInjectsCleanDeferredTapPair() = runTest {
        val input = RecordingPipInput(streamingSupported = true)
        var clockMs = 1030L
        var hostFocusGainMs = 0L
        val sleeps = ArrayList<Long>()
        val ch = Channel<TouchEventPacket>(Channel.UNLIMITED)

        // Tap #1: down at 1000ms, lifted at 1030ms.
        // During pass 1 sleep (from 1030 -> 1065ms), Display 0 focus gain arrives late at 1052ms,
        // so pass 2 waits an extra (1052 + 25) - 1065 = 12ms until 1077ms before injecting DOWN!
        ch.trySend(
            TouchEventPacket(
                action = MotionEvent.ACTION_UP,
                downTime = 1000L,
                eventTime = 1030L,
                pointerIds = intArrayOf(0),
                normXs = floatArrayOf(0.5f),
                normYs = floatArrayOf(0.6f),
                movedBeyondSlop = false,
                isDeferredTap = true,
            ),
        )
        // Tap #2: rapid second tap starting at 1100ms, lifted at 1125ms; must also respect MIN_INTER_GESTURE_GAP_MS (42ms)
        ch.trySend(
            TouchEventPacket(
                action = MotionEvent.ACTION_UP,
                downTime = 1100L,
                eventTime = 1125L,
                pointerIds = intArrayOf(0),
                normXs = floatArrayOf(0.5f),
                normYs = floatArrayOf(0.6f),
                movedBeyondSlop = false,
                isDeferredTap = true,
            ),
        )
        ch.close()

        consumeTouchChannel(
            channel = ch,
            input = input,
            nowUptimeMs = { clockMs },
            sleepMs = { ms ->
                sleeps += ms
                if (sleeps.size == 1) {
                    // Simulate SurfaceFlinger/InputDispatcher delivering host window focus at +52ms
                    hostFocusGainMs = 1052L
                }
                clockMs += ms
            },
            lastHostFocusGainUptimeMs = { hostFocusGainMs },
        )

        // Pass 1 waited 35ms (1030->1065), Pass 2 waited 12ms (1065->1077), Hold waited 45ms (1077->1122);
        // Tap #2 at 1122ms targets max(1100 + 65 = 1165, 1122 + 42 = 1164) -> waits 43ms (1122->1165), Hold 45ms (1165->1210).
        assertEquals(listOf(35L, 12L, 45L, 43L, 45L), sleeps)
        assertEquals(4, input.injected.size)

        assertEquals(MotionEvent.ACTION_DOWN, input.injected[0].action)
        assertEquals(1077L, input.injected[0].downTime)
        assertEquals(1077L, input.injected[0].eventTime)

        assertEquals(MotionEvent.ACTION_UP, input.injected[1].action)
        assertEquals(1077L, input.injected[1].downTime)
        assertEquals(1122L, input.injected[1].eventTime)

        assertEquals(MotionEvent.ACTION_DOWN, input.injected[2].action)
        assertEquals(1165L, input.injected[2].downTime)
        assertEquals(MotionEvent.ACTION_UP, input.injected[3].action)
        assertEquals(1210L, input.injected[3].eventTime)
    }

    @Test
    fun consumeTouchChannelExtendsFlushedTapDurationWithoutDelayingLongHoldsOrSwipes() = runTest {
        val input = RecordingPipInput(streamingSupported = true)
        var clockMs = 1012L
        val sleeps = ArrayList<Long>()
        val ch = Channel<TouchEventPacket>(Channel.UNLIMITED)

        // 1) Flushed stationary tap lifted 10ms after flush: extended to MIN_TAP_DURATION_MS (45ms)
        ch.trySend(TouchEventPacket(MotionEvent.ACTION_DOWN, 1000L, 1000L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.5f), false))
        ch.trySend(TouchEventPacket(MotionEvent.ACTION_UP, 1000L, 1010L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.5f), false))

        // 2) Flushed long hold (already >= MIN_TAP_DURATION_MS): zero extra wait
        ch.trySend(TouchEventPacket(MotionEvent.ACTION_DOWN, 2000L, 2000L, intArrayOf(0), floatArrayOf(0.3f), floatArrayOf(0.4f), false))
        ch.trySend(TouchEventPacket(MotionEvent.ACTION_UP, 2000L, 2060L, intArrayOf(0), floatArrayOf(0.3f), floatArrayOf(0.4f), false))

        // 3) Fast flick (movedBeyondSlop = true): never artificially delayed even if < 45ms
        ch.trySend(TouchEventPacket(MotionEvent.ACTION_DOWN, 3000L, 3000L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.8f), true))
        ch.trySend(TouchEventPacket(MotionEvent.ACTION_MOVE, 3000L, 3010L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.6f), true))
        ch.trySend(TouchEventPacket(MotionEvent.ACTION_UP, 3000L, 3020L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.3f), true))
        ch.close()

        consumeTouchChannel(
            channel = ch,
            input = input,
            nowUptimeMs = {
                if (input.injected.size == 3 && clockMs < 2062L) {
                    clockMs = 2062L
                }
                clockMs
            },
            sleepMs = { ms ->
                sleeps += ms
                clockMs += ms
            },
        )

        // Only the short flushed tap slept (1045 - 1012 = 33ms)
        assertEquals(listOf(33L), sleeps)
        assertEquals(7, input.injected.size)
        assertEquals(MotionEvent.ACTION_UP, input.injected[1].action)
        assertEquals(1045L, input.injected[1].eventTime)
        assertEquals(MotionEvent.ACTION_UP, input.injected[3].action)
        assertEquals(2060L, input.injected[3].eventTime)
        assertEquals(MotionEvent.ACTION_UP, input.injected[6].action)
        assertEquals(3020L, input.injected[6].eventTime)
    }

    @Test
    fun consumeTouchChannelCoalescesBackloggedMovesAndHandlesFallbackAndCancel() = runTest {
        // Part A: Streaming coalesces backlogged MOVEs before UP
        val streamInput = RecordingPipInput(streamingSupported = true)
        val streamCh = Channel<TouchEventPacket>(Channel.UNLIMITED)
        streamCh.trySend(TouchEventPacket(MotionEvent.ACTION_DOWN, 100L, 100L, intArrayOf(0), floatArrayOf(0.1f), floatArrayOf(0.1f), true))
        streamCh.trySend(TouchEventPacket(MotionEvent.ACTION_MOVE, 100L, 116L, intArrayOf(0), floatArrayOf(0.2f), floatArrayOf(0.2f), true))
        streamCh.trySend(TouchEventPacket(MotionEvent.ACTION_MOVE, 100L, 132L, intArrayOf(0), floatArrayOf(0.3f), floatArrayOf(0.3f), true))
        streamCh.trySend(TouchEventPacket(MotionEvent.ACTION_MOVE, 100L, 148L, intArrayOf(0), floatArrayOf(0.4f), floatArrayOf(0.4f), true))
        streamCh.trySend(TouchEventPacket(MotionEvent.ACTION_UP, 100L, 164L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.5f), true))
        streamCh.close()

        consumeTouchChannel(streamCh, streamInput, nowUptimeMs = { 200L }, sleepMs = {})
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            streamInput.injected.map { it.action },
        )
        assertEquals(0.4f, streamInput.injected[1].normXs[0], 0.0001f)
        assertEquals(0.5f, streamInput.injected[2].normXs[0], 0.0001f)

        // Part B: Fallback mode dispatches deferred tap, flushed tap, and swipe cleanly
        val fallbackInput = RecordingPipInput(streamingSupported = false)
        val fallbackCh = Channel<TouchEventPacket>(Channel.UNLIMITED)
        fallbackCh.trySend(
            TouchEventPacket(
                action = MotionEvent.ACTION_UP,
                downTime = 300L,
                eventTime = 340L,
                pointerIds = intArrayOf(0),
                normXs = floatArrayOf(0.15f),
                normYs = floatArrayOf(0.85f),
                movedBeyondSlop = false,
                isDeferredTap = true,
            ),
        )
        fallbackCh.trySend(TouchEventPacket(MotionEvent.ACTION_DOWN, 500L, 500L, intArrayOf(0), floatArrayOf(0.25f), floatArrayOf(0.75f), false))
        fallbackCh.trySend(TouchEventPacket(MotionEvent.ACTION_UP, 500L, 540L, intArrayOf(0), floatArrayOf(0.25f), floatArrayOf(0.75f), false))
        fallbackCh.trySend(TouchEventPacket(MotionEvent.ACTION_DOWN, 700L, 700L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.8f), true))
        fallbackCh.trySend(TouchEventPacket(MotionEvent.ACTION_MOVE, 700L, 760L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.5f), true))
        fallbackCh.trySend(TouchEventPacket(MotionEvent.ACTION_UP, 700L, 820L, intArrayOf(0), floatArrayOf(0.5f), floatArrayOf(0.2f), true))
        fallbackCh.close()

        consumeTouchChannel(fallbackCh, fallbackInput, nowUptimeMs = { 900L }, sleepMs = {})
        assertEquals(listOf(0.15f to 0.85f, 0.25f to 0.75f), fallbackInput.taps)
        assertEquals(1, fallbackInput.swipes.size)
        assertEquals(120L, fallbackInput.swipes[0].second)

        // Part C: Cancellation mid-deferred-tap hold injects ACTION_CANCEL cleanly
        val downSeen = CompletableDeferred<Unit>()
        val holdGate = CompletableDeferred<Unit>()
        val cancelInput = RecordingPipInput(
            streamingSupported = true,
            onInject = { action ->
                if (action == MotionEvent.ACTION_DOWN) downSeen.complete(Unit)
            },
        )
        val cancelCh = Channel<TouchEventPacket>(Channel.UNLIMITED)
        val job = launch {
            consumeTouchChannel(
                channel = cancelCh,
                input = cancelInput,
                nowUptimeMs = { 1500L },
                sleepMs = { ms ->
                    if (ms == TAP_HOLD_MS) holdGate.await()
                },
            )
        }
        cancelCh.trySend(
            TouchEventPacket(
                action = MotionEvent.ACTION_UP,
                downTime = 1400L,
                eventTime = 1430L,
                pointerIds = intArrayOf(0),
                normXs = floatArrayOf(0.6f),
                normYs = floatArrayOf(0.6f),
                movedBeyondSlop = false,
                isDeferredTap = true,
            ),
        )
        downSeen.await()
        job.cancel(CancellationException("unmount"))
        job.join()

        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL),
            cancelInput.injected.map { it.action },
        )
        assertEquals(1500L, cancelInput.injected.last().eventTime)
    }

    private data class InjectedRecord(
        val action: Int,
        val downTime: Long,
        val eventTime: Long,
        val pointerIds: IntArray,
        val normXs: FloatArray,
        val normYs: FloatArray,
    )

    private class RecordingPipInput(
        private val streamingSupported: Boolean,
        private val onInject: (Int) -> Unit = {},
    ) : PipInput {
        val injected = ArrayList<InjectedRecord>()
        val taps = ArrayList<Pair<Float, Float>>()
        val swipes = ArrayList<Pair<List<Pair<Float, Float>>, Long>>()

        override fun injectTouch(
            action: Int,
            downTime: Long,
            eventTime: Long,
            pointerIds: IntArray,
            normXs: FloatArray,
            normYs: FloatArray,
        ): Boolean {
            if (!streamingSupported) return false
            injected += InjectedRecord(
                action = action,
                downTime = downTime,
                eventTime = eventTime,
                pointerIds = pointerIds.copyOf(),
                normXs = normXs.copyOf(),
                normYs = normYs.copyOf(),
            )
            onInject(action)
            return true
        }

        override suspend fun tap(fx: Float, fy: Float) {
            taps += fx to fy
        }

        override suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
            swipes += points to durationMs
        }

        override suspend fun back() = Unit
    }
}
