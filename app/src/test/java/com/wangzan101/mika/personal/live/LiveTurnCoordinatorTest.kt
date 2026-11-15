package com.wangzan101.mika.personal.live

import android.view.MotionEvent
import com.wangzan101.mika.core.live.GeminiLiveEvent
import com.wangzan101.mika.core.live.LiveAsyncEvent
import com.wangzan101.mika.core.live.LiveRealtimeSession
import com.wangzan101.mika.core.live.LiveScheduling
import com.wangzan101.mika.core.live.LiveTurnCoordinator
import com.wangzan101.mika.core.live.TurnWindowSnapshot
import com.wangzan101.mika.core.search.WebSearchHit
import com.wangzan101.mika.core.search.WebSearchResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        var uiState = PersonalLiveUiState(
            phase = LiveSessionPhase.Active,
            liveUserTranscript = "那我们顺便聊聊周末去哪玩",
            inputLevel = 0.15f,
        )

        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { uiState.toSignalSnapshot() },
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

        uiState = uiState.copy(liveUserTranscript = "", inputLevel = 0.01f)
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
        assertFalse("Superseded touch guidance must be deduplicated", prompt.contains("旧页面"))
        assertTrue(prompt.contains("商品详情页 | 已选黑色"))
        assertTrue(prompt.contains("建议第一天西湖加灵隐寺"))
    }

    @Test
    fun touchCoordinatorCoalescesBurstGesturesAndBalancesPauseResumeHooks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = FakeRealtimeSession()
        var nowMs = 20_000L
        val uiState = PersonalLiveUiState(phase = LiveSessionPhase.Active)

        lateinit var touchCoordinator: LiveUserTouchCoordinator
        val turnCoordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { uiState.toSignalSnapshot() },
            userTouchingProvider = { touchCoordinator.isUserTouching },
            clockMs = { nowMs },
        )
        touchCoordinator = LiveUserTouchCoordinator(
            scope = scope,
            turnCoordinator = turnCoordinator,
            onUserActivity = {},
            debounceMs = 1_200L,
        )

        var pausedCount = 0
        var resumedCount = 0
        var captureCount = 0

        suspend fun capture(): LiveUserTouchCoordinator.CapturedGuidanceState {
            captureCount++
            return LiveUserTouchCoordinator.CapturedGuidanceState(
                appLabel = "美团",
                screenSummary = "已切换到订单详情页",
                jpegFrame = byteArrayOf(4, 5),
            )
        }

        touchCoordinator.onMotionAction(MotionEvent.ACTION_DOWN, { pausedCount++ }, { resumedCount++ }, ::capture)
        assertTrue(touchCoordinator.isUserTouching)
        touchCoordinator.onMotionAction(MotionEvent.ACTION_MOVE, { pausedCount++ }, { resumedCount++ }, ::capture)
        touchCoordinator.onMotionAction(MotionEvent.ACTION_UP, { pausedCount++ }, { resumedCount++ }, ::capture)
        assertFalse(touchCoordinator.isUserTouching)

        nowMs += 500L
        advanceTimeBy(500L)
        assertEquals(0, captureCount)
        touchCoordinator.onMotionAction(MotionEvent.ACTION_DOWN, { pausedCount++ }, { resumedCount++ }, ::capture)
        touchCoordinator.onMotionAction(MotionEvent.ACTION_UP, { pausedCount++ }, { resumedCount++ }, ::capture)

        nowMs += 1_500L
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals("Two rapid gestures should coalesce into a single settled capture", 1, captureCount)
        assertEquals(2, pausedCount)
        assertEquals(1, resumedCount)
        assertEquals(1, session.contextUpdates.size)
        val (prompt, _) = session.contextUpdates.single()
        assertTrue(prompt.contains("连续触控次数：2"))
        assertTrue(prompt.contains("已切换到订单详情页"))
    }

    @Test
    fun liveToolsExposeWebSearchAndDeepThinkWhileKeepingApprovalHumanOnly() {
        val names = PersonalLiveTools.toolsForMode(searchEnabled = true).map { it.spec.name }.toSet()
        assertTrue(PersonalLiveTools.QUICK_PHONE_ACT_NAME in names)
        assertTrue(PersonalLiveTools.WEB_SEARCH_NAME in names)
        assertTrue(PersonalLiveTools.DEEP_THINK_NAME in names)
        assertTrue(PersonalLiveTools.STEER_OR_CANCEL_THINK_NAME in names)
        assertFalse("confirm_task" in names)
        val quickSchema = PersonalLiveTools.QUICK_PHONE_ACT.spec.parametersJsonSchema
        assertFalse(quickSchema.contains("\"user_confirmed\""))

        val searchPayload = LiveWebSearchRunner.toToolPayload(
            "杭州今日天气",
            WebSearchResult.Success(listOf(WebSearchHit("杭州天气预报", "https://example.com/hz", "晴转多云 18-26℃"))),
        )
        assertEquals(JsonPrimitive(true), searchPayload["ok"])
        assertEquals(JsonPrimitive("completed"), searchPayload["status"])
    }

    @Test
    fun postToolResponseWindowBlocksPrematureContextUpdatesUntilTurnBoundaryOrTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = FakeRealtimeSession()
        var nowMs = 30_000L
        val uiState = PersonalLiveUiState(phase = LiveSessionPhase.Active)

        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { uiState.toSignalSnapshot() },
            userTouchingProvider = { false },
            clockMs = { nowMs },
        )

        coordinator.onToolCallStarted()
        coordinator.onToolResponseSent()
        coordinator.onToolCallFinished()

        coordinator.enqueue(
            LiveAsyncEvent.AsyncSearchResult(
                query = "手扶杆跑步机评价",
                summary = "带扶手款更适合长辈慢走。",
            ),
        )

        runCurrent()
        nowMs += 800L
        advanceTimeBy(800L)
        runCurrent()
        assertEquals(0, session.contextUpdates.size)

        coordinator.onAssistantTurnBoundary()
        nowMs += 300L
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(1, session.contextUpdates.size)
    }

    @Test
    fun staleSessionResumptionDetectionAndSeamlessTurnRecoveryPrevent1008CrashLoop() {
        assertTrue(
            PersonalLiveTools.shouldClearResumptionHandle(
                1008,
                "BidiGenerateContent session not found or expired",
            ),
        )
        assertTrue(PersonalLiveTools.shouldClearResumptionHandle(1007, "Invalid argument"))
        assertTrue(PersonalLiveTools.shouldClearResumptionHandle(-1, "Gemini Live 会话关闭 (1008): session expired"))
        assertFalse(PersonalLiveTools.shouldClearResumptionHandle(1000, "client_close"))
        assertFalse(PersonalLiveTools.shouldClearResumptionHandle(1006, ""))

        val withExecutedTool = PersonalLiveTools.buildSeamlessRecoveryPrompt(
            unansweredUserText = "算了，把它加入购物车吧，先不买了。",
            recentToolSummaries = listOf("""quick_phone_act: {"ok":true,"status":"ok","completed_steps":1}"""),
        )
        assertTrue(withExecutedTool != null)
        assertTrue(withExecutedTool!!.contains("算了，把它加入购物车吧，先不买了。"))
        assertTrue(withExecutedTool.contains("quick_phone_act"))
        assertTrue(withExecutedTool.contains("严禁重复执行已成功的写操作"))

        val withoutTool = PersonalLiveTools.buildSeamlessRecoveryPrompt(
            unansweredUserText = "算了，把它加入购物车吧，先不买了。",
            recentToolSummaries = emptyList(),
        )
        assertTrue(withoutTool != null)
        assertTrue(withoutTool!!.contains("尚未执行任何工具操作"))

        assertEquals(null, PersonalLiveTools.buildSeamlessRecoveryPrompt("", emptyList()))
    }

    @Test
    fun launchToolWorkHandlesImmediateNonSuspendingSuspendingAndCancelledToolsWithoutCrash() = runTest {
        val session = FakeRealtimeSession()
        val uiState = PersonalLiveUiState(phase = LiveSessionPhase.Active)
        val unconfinedScope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val coordinator = LiveTurnCoordinator(
            scope = unconfinedScope,
            sessionProvider = { session },
            signalProvider = { uiState.toSignalSnapshot() },
            userTouchingProvider = { false },
            clockMs = { 10_000L },
        )
        val inFlight = CopyOnWriteArrayList<Job>()

        var syncRan = false
        val syncJob = coordinator.launchToolWork(unconfinedScope, inFlight) {
            syncRan = true
        }
        assertTrue(syncRan)
        assertTrue(syncJob.isCompleted)
        assertEquals(0, inFlight.size)
        assertEquals(0, coordinator.snapshotNow().activeToolCalls)

        val suspendJob = coordinator.launchToolWork(unconfinedScope, inFlight) {
            delay(500L)
        }
        assertEquals(1, inFlight.size)
        assertEquals(1, coordinator.snapshotNow().activeToolCalls)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(suspendJob.isCompleted)
        assertEquals(0, inFlight.size)
        assertEquals(0, coordinator.snapshotNow().activeToolCalls)

        val childScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val midCancelJob = coordinator.launchToolWork(childScope, inFlight) {
            delay(1_000L)
        }
        assertEquals(1, inFlight.size)
        assertEquals(1, coordinator.snapshotNow().activeToolCalls)
        childScope.cancel()
        runCurrent()
        assertTrue(midCancelJob.isCompleted)
        assertEquals(0, inFlight.size)
        assertEquals(0, coordinator.snapshotNow().activeToolCalls)

        coordinator.launchToolWork(childScope, inFlight) {
            delay(1_000L)
        }
        assertEquals(0, inFlight.size)
        assertEquals(0, coordinator.snapshotNow().activeToolCalls)
    }
}
