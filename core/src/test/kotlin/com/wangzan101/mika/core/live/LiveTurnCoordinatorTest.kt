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

    private class FakeRealtimeSession(
        override val capabilities: LiveProviderCapabilities = LiveProviderCapabilities.DEFAULT,
        var allowContextSend: Boolean = true,
    ) : LiveRealtimeSession {
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
            if (!allowContextSend) return false
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

    @Test
    fun coordinatorSuppressesVideoJpegWhenProviderDoesNotSupportVisualFrames() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val audioOnlySession = FakeRealtimeSession(capabilities = LiveProviderCapabilities(supportsVisualFrames = false))
        var nowMs = 10_000L
        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { audioOnlySession },
            signalProvider = { LiveTurnSignalSnapshot(sessionActive = true, speaking = false, liveUserTranscript = "", inputLevel = 0f) },
            userTouchingProvider = { false },
            clockMs = { nowMs },
        )

        coordinator.enqueue(
            LiveAsyncEvent.DirectConfirmationFeedback(
                prompt = "[系统事件·操作完成]",
                jpegFrame = byteArrayOf(1, 2, 3, 4),
            ),
        )
        runCurrent()
        nowMs += 1_500L
        advanceTimeBy(1_500L)
        runCurrent()

        assertTrue("Audio-only providers must never receive sendVideoJpeg", audioOnlySession.videoJpegs.isEmpty())
        assertEquals(1, audioOnlySession.contextUpdates.size)
        assertTrue(audioOnlySession.contextUpdates.single().first.contains("操作完成"))
    }

    @Test
    fun silentTouchGuidanceSyncsContextWithoutTriggeringSpeechAndCancelPendingStageEventsPurgesPreemptedWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = FakeRealtimeSession()
        var nowMs = 10_000L
        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { LiveTurnSignalSnapshot(sessionActive = true, speaking = false, liveUserTranscript = "", inputLevel = 0f) },
            userTouchingProvider = { false },
            clockMs = { nowMs },
        )

        // 1. Standalone manual touch defaults to silent state sync (triggerResponse=false, zero JPEG frame spam)
        coordinator.enqueue(
            LiveAsyncEvent.UserTouchGuidance(
                appLabel = "设置",
                screenSummary = "[可点] 应用设置",
                jpegFrame = byteArrayOf(7, 8, 9),
                touchCount = 3,
            ),
        )
        runCurrent()
        assertEquals("Silent touch must not spam JPEG frames when no spoken turn is triggered", 0, session.videoJpegs.size)
        assertEquals(1, session.contextUpdates.size)
        val (touchPrompt, touchTrigger) = session.contextUpdates.single()
        assertFalse("Manual touch sync must not force unsolicited speech", touchTrigger)
        assertTrue(touchPrompt.contains("静默界面同步"))

        // 2. StageGoalFinished deduplicates older stage results, and cancelPendingStageEvents purges stage/touch events while keeping DeepThink
        var userSpeaking = true
        val gatedCoordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = {
                LiveTurnSignalSnapshot(
                    sessionActive = true,
                    speaking = false,
                    liveUserTranscript = if (userSpeaking) "帮我换一个打开微信" else "",
                    inputLevel = if (userSpeaking) 0.2f else 0f,
                )
            },
            userTouchingProvider = { false },
            clockMs = { nowMs },
        )
        gatedCoordinator.enqueue(
            LiveAsyncEvent.StageGoalFinished(
                goalId = "stage_1",
                goal = "搜乒乓球桌",
                ok = true,
                summary = "旧结果",
            ),
        )
        gatedCoordinator.enqueue(
            LiveAsyncEvent.AsyncExtendedToolResult(
                intent = "创建每天早上8点提醒",
                resultSummary = """{"ok":true,"id":"s1"}""",
            ),
        )
        // New phone command preempts the stage track -> purges stage_1, keeps extended tool result
        gatedCoordinator.cancelPendingStageEvents()
        userSpeaking = false
        nowMs += 2_000L
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(2, session.contextUpdates.size)
        val (secondPrompt, secondTrigger) = session.contextUpdates[1]
        assertTrue(secondTrigger)
        assertFalse("Preempted stage goal event must be purged", secondPrompt.contains("搜乒乓球桌"))
        assertTrue(secondPrompt.contains("创建每天早上8点提醒"))
        assertTrue("External payload must be wrapped in untrusted tags", secondPrompt.contains("<untrusted>") && secondPrompt.contains("</untrusted>"))

        // 3. Transient sendContextUpdate failure retains queued events until session recovers
        session.allowContextSend = false
        coordinator.enqueue(LiveAsyncEvent.DeepThinkResult("t1", "推理题", "结论A"))
        runCurrent()
        assertEquals(2, session.contextUpdates.size)
        assertTrue(coordinator.hasPendingEvents())

        session.allowContextSend = true
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(3, session.contextUpdates.size)
        assertTrue(session.contextUpdates[2].first.contains("结论A"))
    }
}
