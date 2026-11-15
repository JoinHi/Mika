package com.wangzan101.mika.core.live

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTurnCoordinatorTest {

    private class FakeRealtimeSession : LiveRealtimeSession {
        override val events: Flow<GeminiLiveEvent> = emptyFlow()
        val videoJpegs = mutableListOf<ByteArray>()
        val contextUpdates = mutableListOf<Pair<String, Boolean>>()

        override fun sendAudioPcm16(pcm16Le: ByteArray, byteCount: Int): Boolean = true
        override fun sendAudioStreamEnd(): Boolean = true
        override fun sendVideoJpeg(jpeg: ByteArray): Boolean {
            videoJpegs += jpeg
            return true
        }
        override fun sendUserText(text: String, turnComplete: Boolean): Boolean = true
        override fun sendContextUpdate(text: String, triggerResponse: Boolean): Boolean {
            contextUpdates += text to triggerResponse
            return true
        }
        override fun sendToolResponse(id: String, name: String, output: JsonObject, scheduling: LiveScheduling?): Boolean = true
        override fun close() = Unit
    }

    @Test
    fun isSafeDeliveryWindowYieldsDuringSpeechTailToolCallAndTouch() {
        val quiet = TurnWindowSnapshot(
            nowMs = 10_000L,
            assistantSpeaking = false,
            lastAssistantAudioAtMs = 8_000L,
            liveUserTranscriptNotBlank = false,
            inputLevel = 0.01f,
            lastUserSpeechAtMs = 8_000L,
            activeToolCalls = 0,
            userTouchingStage = false,
        )
        assertTrue(LiveTurnCoordinator.isSafeDeliveryWindow(quiet))

        assertFalse(LiveTurnCoordinator.isSafeDeliveryWindow(quiet.copy(userTouchingStage = true)))
        assertFalse(LiveTurnCoordinator.isSafeDeliveryWindow(quiet.copy(activeToolCalls = 1)))
        assertFalse(LiveTurnCoordinator.isSafeDeliveryWindow(quiet.copy(assistantSpeaking = true)))
        assertFalse(LiveTurnCoordinator.isSafeDeliveryWindow(quiet.copy(lastAssistantAudioAtMs = 9_600L)))
        assertFalse(LiveTurnCoordinator.isSafeDeliveryWindow(quiet.copy(liveUserTranscriptNotBlank = true)))
        assertFalse(LiveTurnCoordinator.isSafeDeliveryWindow(quiet.copy(inputLevel = 0.12f)))
        assertFalse(LiveTurnCoordinator.isSafeDeliveryWindow(quiet.copy(lastUserSpeechAtMs = 9_200L)))
    }

    @Test
    fun coordinatorWaitsForUserSpeechToFinishAndDeduplicatesTouchGuidance() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = FakeRealtimeSession()
        var nowMs = 5_000L
        var signal = LiveTurnSignalSnapshot(
            sessionActive = true,
            speaking = false,
            liveUserTranscript = "那我们顺便聊聊周末去哪玩",
            inputLevel = 0.15f,
        )

        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { signal },
            userTouchingProvider = { false },
            clockMs = { nowMs },
        )

        coordinator.enqueue(
            LiveAsyncEvent.UserTouchGuidance(
                appLabel = "淘宝",
                screenSummary = "旧页面",
                jpegFrame = byteArrayOf(1),
                touchCount = 1,
            ),
        )
        coordinator.enqueue(
            LiveAsyncEvent.DeepThinkResult(
                thinkId = "think_1",
                question = "杭州两天一夜怎么安排",
                spokenConclusion = "建议第一天西湖加灵隐寺，第二天西溪湿地。",
            ),
        )
        coordinator.enqueue(
            LiveAsyncEvent.UserTouchGuidance(
                appLabel = "淘宝",
                screenSummary = "商品详情页 | 已选黑色",
                jpegFrame = byteArrayOf(9, 8, 7),
                touchCount = 2,
            ),
        )

        runCurrent()
        nowMs += 500L
        advanceTimeBy(500L)
        assertEquals(0, session.contextUpdates.size)

        signal = signal.copy(liveUserTranscript = "", inputLevel = 0.01f)
        nowMs += 600L
        advanceTimeBy(600L)
        assertEquals(0, session.contextUpdates.size)

        nowMs += 700L
        advanceTimeBy(700L)
        runCurrent()

        assertEquals(1, session.videoJpegs.size)
        assertEquals(3, session.videoJpegs.single().size)
        assertEquals(1, session.contextUpdates.size)
        val (prompt, trigger) = session.contextUpdates.single()
        assertTrue(trigger)
        assertFalse(prompt.contains("旧页面"))
        assertTrue(prompt.contains("商品详情页 | 已选黑色"))
        assertTrue(prompt.contains("建议第一天西湖加灵隐寺"))
    }

    @Test
    fun launchToolWorkTracksCallIdAndReleasesActiveCounterOnCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = FakeRealtimeSession()
        val signal = LiveTurnSignalSnapshot(sessionActive = true, speaking = false, liveUserTranscript = "", inputLevel = 0f)
        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { signal },
            userTouchingProvider = { false },
            clockMs = { 10_000L },
        )
        val registry = CopyOnWriteArrayList<Job>()
        val byCallId = ConcurrentHashMap<String, Job>()

        val job = coordinator.launchToolWork(scope, registry, callId = "call-42", byCallId = byCallId) {
            delay(10_000L)
        }
        assertEquals(1, coordinator.snapshotNow().activeToolCalls)
        assertEquals(job, byCallId["call-42"])

        byCallId["call-42"]?.cancel()
        runCurrent()

        assertEquals(0, coordinator.snapshotNow().activeToolCalls)
        assertFalse(byCallId.containsKey("call-42"))
        assertTrue(registry.isEmpty())
    }

    @Test
    fun sessionPolicyEvaluatesIdleHangupAndResumptionResetDeterministically() {
        assertFalse(LiveSessionPolicy.shouldAutoHangupOnIdle(1_179_999L, 1_000_000L, hasActiveTasks = false, isSpeakingOrConnecting = false))
        assertTrue(LiveSessionPolicy.shouldAutoHangupOnIdle(1_180_000L, 1_000_000L, hasActiveTasks = false, isSpeakingOrConnecting = false))
        assertFalse(LiveSessionPolicy.shouldAutoHangupOnIdle(1_900_000L, 1_000_000L, hasActiveTasks = true, isSpeakingOrConnecting = false))

        assertTrue(LiveSessionPolicy.shouldClearResumptionHandle(1007, "Invalid resumption token"))
        assertTrue(LiveSessionPolicy.shouldClearResumptionHandle(404, "session not found"))
        assertFalse(LiveSessionPolicy.shouldClearResumptionHandle(1006, "network eof"))
    }
}
