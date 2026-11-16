package com.wangzan101.mika.personal.live

import androidx.compose.ui.unit.dp
import com.wangzan101.mika.cap.phone.a11y.PhoneWaitProfile
import com.wangzan101.mika.capsule.CapsuleInputs
import com.wangzan101.mika.capsule.CapsuleKind
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.CapsuleModel
import com.wangzan101.mika.capsule.CapsuleRun
import com.wangzan101.mika.capsule.CapsuleView
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.live.GeminiLiveEvent
import com.wangzan101.mika.core.live.LiveFunctionCall
import com.wangzan101.mika.core.live.LiveFunctionResponse
import com.wangzan101.mika.core.live.LiveRealtimeSession
import com.wangzan101.mika.core.live.LiveScheduling
import com.wangzan101.mika.core.live.LiveToolBehavior
import com.wangzan101.mika.core.live.LiveTurnCoordinator
import com.wangzan101.mika.core.live.LiveTurnSignalSnapshot
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.personal.PersonalActions
import com.wangzan101.mika.core.personal.PersonalAgent
import com.wangzan101.mika.core.personal.PersonalState
import com.wangzan101.mika.core.personal.PersonalStore
import com.wangzan101.mika.core.personal.PersonalSystemException
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.search.WebSearchHit
import com.wangzan101.mika.core.search.WebSearchResult
import com.wangzan101.mika.core.uistate.AppWait
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.task.ResourceScheduler
import com.wangzan101.mika.ui.fitStageFrame
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalLiveOrchestratorTest {
    @get:Rule val texts = ChineseTexts()

    private class RecordingLiveSession(
        var acceptUserText: Boolean = true,
    ) : LiveRealtimeSession {
        override val events: Flow<GeminiLiveEvent> = emptyFlow()
        val sentUserTexts = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val sentToolResponses = CopyOnWriteArrayList<LiveFunctionResponse>()
        val sentContextUpdates = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val sentJpegs = CopyOnWriteArrayList<ByteArray>()

        override fun sendAudioPcm16(pcm16Le: ByteArray, byteCount: Int): Boolean = true
        override fun sendAudioStreamEnd(): Boolean = true
        override fun sendVideoJpeg(jpeg: ByteArray): Boolean {
            sentJpegs += jpeg
            return true
        }
        override fun sendUserText(text: String, turnComplete: Boolean): Boolean {
            if (!acceptUserText) return false
            sentUserTexts += text to turnComplete
            return true
        }
        override fun sendContextUpdate(text: String, triggerResponse: Boolean): Boolean {
            sentContextUpdates += text to triggerResponse
            return true
        }
        override fun sendToolResponse(id: String, name: String, output: JsonObject, scheduling: LiveScheduling?): Boolean {
            sentToolResponses += LiveFunctionResponse(id, name, output, scheduling)
            return true
        }
        override fun close() = Unit
    }

    @Test
    fun noVerbalApprovalSurfaceExistsInLiveToolsOrSchemas() {
        for (search in listOf(true, false)) {
            val names = PersonalLiveTools.toolsForMode(searchEnabled = search).map { it.spec.name }
            assertFalse("confirm_task must never be exposed to the model", "confirm_task" in names)
        }
        assertFalse("confirm_task" in PersonalLiveTools.ALL.map { it.spec.name })

        val quickSchema = PersonalLiveTools.QUICK_PHONE_ACT.spec.parametersJsonSchema
        assertFalse("user_confirmed must not exist on quick_phone_act", quickSchema.contains("user_confirmed"))
        assertTrue(quickSchema.contains("\"additionalProperties\":false"))
        assertTrue(quickSchema.contains("\"resourceId\""))
        assertTrue(quickSchema.contains("\"whenText\""))

        val prompt = PersonalLiveTools.buildSystemPrompt(PersonalState(), "", nowMs = 1_700_000_000_000L)
        assertTrue(prompt.contains("严禁索要口头确认"))
        assertFalse(prompt.contains("user_confirmed"))
        assertFalse(prompt.contains("confirm_task"))
    }

    @Test
    fun idleAutoHangupRespectsThreeMinutesAndActiveTasks() {
        val base = 1_000_000L
        assertFalse(
            PersonalLiveTools.shouldAutoHangupOnIdle(
                nowMs = base + 179_999L,
                lastInteractionAtMs = base,
                hasActiveTasks = false,
                isSpeakingOrConnecting = false,
            ),
        )
        assertTrue(
            PersonalLiveTools.shouldAutoHangupOnIdle(
                nowMs = base + 180_000L,
                lastInteractionAtMs = base,
                hasActiveTasks = false,
                isSpeakingOrConnecting = false,
            ),
        )
        assertFalse(
            PersonalLiveTools.shouldAutoHangupOnIdle(
                nowMs = base + 600_000L,
                lastInteractionAtMs = base,
                hasActiveTasks = true,
                isSpeakingOrConnecting = false,
            ),
        )
        assertFalse(
            PersonalLiveTools.shouldAutoHangupOnIdle(
                nowMs = base + 600_000L,
                lastInteractionAtMs = base,
                hasActiveTasks = false,
                isSpeakingOrConnecting = true,
            ),
        )
    }

    @Test
    fun liveMessagesPersistAsHandledWithoutWakingTextChatModel() = runBlocking {
        var modelCallCount = 0
        val fakeModel = object : ChatModel {
            override val capabilities = Capabilities(true, false, 128_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                modelCallCount++
                return ModelReply(listOf(Part.Text("unexpected")), StopReason.EndTurn, null)
            }
        }
        val store = object : PersonalStore {
            override val state = MutableStateFlow(PersonalState())
            override fun update(change: (PersonalState) -> PersonalState) {
                state.value = change(state.value)
            }
        }
        val startedTasks = mutableListOf<Pair<String, String>>()
        val actions = object : PersonalActions {
            override fun start(conversationId: String, goal: String) {
                startedTasks += conversationId to goal
            }
            override fun revise(conversationId: String, instruction: String) = "revised:$instruction"
            override fun cancel(conversationId: String) = "cancelled"
            override fun remember(text: String, quote: String) = "saved:$text"
            override fun memory() = "机主喜欢美式冰咖啡"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val agent = PersonalAgent(store, scope, { fakeModel }, actions)
            val userMsg = agent.recordLiveMessage("user", "帮我给张三发微信")
            agent.updateLiveUserMessage(userMsg!!.id, "帮我给张三发微信说五分钟后到")
            val task = agent.startLiveTask("给张三发微信", "打开微信给张三发消息：五分钟后到")
            // Duplicate launch for the same title/goal must reuse the active task atomically
            val duplicate = agent.startLiveTask("给张三发微信", "打开微信给张三发消息：五分钟后到")
            assertEquals(task.id, duplicate.id)
            assertEquals(1, startedTasks.size)

            // Once cancelled (terminal), re-launching the same goal must start a fresh task rather than returning the dead one
            agent.updateTask(task.id, "cancelled", detail = "已取消")
            val retried = agent.startLiveTask("给张三发微信", "打开微信给张三发消息：五分钟后到")
            assertFalse("Terminal task must not block retrying the same goal", task.id == retried.id)
            assertEquals(2, startedTasks.size)

            agent.recordLiveMessage("assistant", "好嘞，已经在后台给你准备了。")
            delay(50)

            assertEquals("Text model must never be called for Live turns", 0, modelCallCount)
            assertEquals(listOf(task.conversationId, retried.conversationId), startedTasks.map { it.first })
            val msgs = store.state.value.messages
            assertTrue(msgs.all { it.handled })
            assertEquals(1, msgs.count { it.role == "user" })
            assertTrue(msgs.any { it.role == "user" && it.text == "帮我给张三发微信说五分钟后到" })
            assertTrue(msgs.any { it.role == "assistant" && it.text.contains("好嘞") })

            val prompt = PersonalLiveTools.buildSystemPrompt(store.state.value, agent.memorySnapshot(), nowMs = 1_700_000_000_000L)
            assertTrue(prompt.contains("机主喜欢美式冰咖啡"))
            assertTrue(prompt.contains("给张三发微信"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun capsuleStaysVisibleInBackgroundWhenLiveSessionActiveEvenWithoutTasks() {
        val model = CapsuleModel()
        val v = model.update(
            CapsuleInputs(
                runs = emptyList(),
                mode = CapsuleMode.DuringTasks,
                mikaInForeground = false,
                screenInteractive = true,
                serviceAvailable = true,
                liveText = "Mika Live · 正在聆听",
            ),
            now = 100L,
        ) as CapsuleView.Shown
        assertEquals(CapsuleKind.LiveVoice, v.kind)
        assertEquals("Mika Live · 正在聆听", v.text)
        assertTrue(v.pill)

        val withTask = model.update(
            CapsuleInputs(
                runs = listOf(CapsuleRun("r1", "c1", TaskState.Running(1, "note", "tap", narration = "正在打开微信"))),
                mode = CapsuleMode.DuringTasks,
                mikaInForeground = false,
                screenInteractive = true,
                serviceAvailable = true,
                liveText = "Mika Live · 正在聆听",
            ),
            now = 200L,
        ) as CapsuleView.Shown
        assertEquals(CapsuleKind.Running, withTask.kind)
        assertEquals("正在打开微信", withTask.text)
    }

    @Test
    fun unifiedDirectControlExposesQuickPhoneActAndTunedTurboWaitProfile() {
        val turbo = PhoneWaitProfile.TURBO
        val standard = PhoneWaitProfile.STANDARD
        assertTrue(turbo.turbo)
        assertTrue(turbo.instantTyping)
        assertTrue(turbo.instantGesture)
        assertTrue(turbo.changeProbeMs >= 380L)
        assertTrue(turbo.pasteWaitMs >= 500L)
        assertTrue(turbo.settleCapMs >= 550L)
        assertTrue((turbo.changeCapMs + turbo.changePollMs) * 2 < standard.changeCapMs)
        assertTrue(turbo.settleCapMs < standard.settleCapMs)
        assertTrue(turbo.batchSettleMs < standard.batchSettleMs)
        assertTrue(turbo.pasteWaitMs < standard.pasteWaitMs)
        assertFalse(standard.turbo)
        assertFalse(standard.instantTyping)
        assertFalse(standard.instantGesture)

        val liveTools = PersonalLiveTools.toolsForMode(searchEnabled = true)
        val noSearchTools = PersonalLiveTools.toolsForMode(searchEnabled = false)
        val quickTool = liveTools.firstOrNull { it.spec.name == PersonalLiveTools.QUICK_PHONE_ACT_NAME }
        assertTrue("Mika Live must always expose quick_phone_act", quickTool != null)
        assertEquals(LiveToolBehavior.BLOCKING, quickTool!!.behavior)
        assertTrue("ALL must also include quick_phone_act", PersonalLiveTools.ALL.any { it.spec.name == PersonalLiveTools.QUICK_PHONE_ACT_NAME })
        assertTrue("No-search mode still exposes quick_phone_act", noSearchTools.any { it.spec.name == PersonalLiveTools.QUICK_PHONE_ACT_NAME })
        assertFalse("Disabled web search must omit web_search tool", noSearchTools.any { it.spec.name == PersonalLiveTools.WEB_SEARCH_NAME })

        val prompt = PersonalLiveTools.buildSystemPrompt(PersonalState(), "", nowMs = 1_700_000_000_000L)
        assertTrue(prompt.contains("quick_phone_act"))
        assertFalse(prompt.contains("标准对比模式"))
    }

    @Test
    fun directPhoneResolvesAppNamesAliasesAndPackagesDeterministically() {
        val installed = listOf(
            "com.tencent.wework" to "企业微信",
            "com.tencent.mm" to "微信",
            "com.xingin.xhs" to "小红书",
            "com.android.settings" to "设置",
        )
        assertEquals("com.tencent.mm", LiveDirectPhoneController.resolveAppPackage("微信", installed))
        assertEquals("com.tencent.mm", LiveDirectPhoneController.resolveAppPackage("WeChat", installed))
        assertEquals("com.tencent.wework", LiveDirectPhoneController.resolveAppPackage("企业微信", installed))
        assertEquals("com.xingin.xhs", LiveDirectPhoneController.resolveAppPackage("红书", installed))
        assertEquals("com.tencent.mm", LiveDirectPhoneController.resolveAppPackage("COM.TENCENT.MM", installed))
        assertEquals("org.example.custom", LiveDirectPhoneController.resolveAppPackage("org.example.custom", installed))
        assertEquals(null, LiveDirectPhoneController.resolveAppPackage("不存在的幻想应用", installed))
    }

    @Test
    fun fitStageFrameMaximizesPortraitAndLandscapeWithoutCropping() {
        val (pw, ph) = fitStageFrame(720, 1600, 380.dp, 680.dp)
        assertEquals(680f, ph.value, 0.1f)
        assertEquals(306f, pw.value, 0.1f)

        val (lw, lh) = fitStageFrame(1600, 720, 380.dp, 680.dp)
        assertEquals(380f, lw.value, 0.1f)
        assertEquals(171f, lh.value, 0.1f)
    }

    @Test
    fun extractSimpleLaunchAppCatchesPureAppOpensWithoutInterceptingComplexGoals() {
        assertEquals("QQ", PersonalLiveTools.extractSimpleLaunchApp("打开QQ", "打开QQ应用，进入消息首页"))
        assertEquals("淘宝", PersonalLiveTools.extractSimpleLaunchApp("打开淘宝", "打开淘宝App"))
        assertEquals("纵横小说", PersonalLiveTools.extractSimpleLaunchApp("打开应用", "打开纵横小说App"))
        assertEquals("微信", PersonalLiveTools.extractSimpleLaunchApp("", "帮我打开手机上的微信客户端"))

        assertEquals(null, PersonalLiveTools.extractSimpleLaunchApp("给张三发微信", "打开微信给张三发消息：五分钟后到"))
        assertEquals(null, PersonalLiveTools.extractSimpleLaunchApp("删除书籍", "在纵横小说App的书架中删除三本书籍"))
        assertEquals(null, PersonalLiveTools.extractSimpleLaunchApp("看购物车", "打开淘宝看一下购物车"))
        assertEquals(null, PersonalLiveTools.extractSimpleLaunchApp("选规格", "在淘宝点击第一个规格"))
    }

    @Test
    fun liveSettingsIgnoresLegacyTurboFieldAndDirectScreenSummaryAttachesVisualFallbackWhenNeeded() {
        val tmp = java.nio.file.Files.createTempFile("live-settings-test", ".json").toFile()
        try {
            tmp.writeText("""{"providerId":"g1","model":"gemini-3.8-live","voiceName":"Kore","turboControl":false}""")
            val reloaded = PersonalLiveSettings(tmp)
            assertEquals("g1", reloaded.state.value.providerId)
            assertEquals("gemini-3.8-live", reloaded.state.value.model)
            assertEquals("Kore", reloaded.state.value.voiceName)

            reloaded.update { it.copy(voiceName = "Puck") }
            val savedJson = tmp.readText()
            assertFalse("Obsolete turboControl must no longer be written", savedJson.contains("turboControl"))

            val delegate = PersonalLiveTools.toolsForMode().first { it.spec.name == "delegate_task" }
            assertTrue(delegate.spec.description.contains("严禁调用本工具，必须调用 quick_phone_act"))

            val prompt = PersonalLiveTools.buildSystemPrompt(PersonalState(), "", nowMs = 1_700_000_000_000L)
            assertTrue(prompt.contains("铁律·严禁嘴上空许诺不调工具"))

            val denseScreen = buildJsonObject {
                put("elements", JsonArray((1..80).map { idx -> buildJsonObject { put("name", "item_$idx") } }))
            }
            val normalScreen = buildJsonObject {
                put("elements", JsonArray((1..12).map { idx -> buildJsonObject { put("name", "item_$idx") } }))
            }
            val protectedScreen = buildJsonObject {
                put("protected", true)
                put("elements", JsonArray(emptyList()))
            }
            val compacted = LiveDirectPhoneController.compactScreen(denseScreen)
            assertEquals(72, (compacted["elements"] as JsonArray).size)
            assertEquals(JsonPrimitive(true), compacted["elementsTruncated"])

            assertTrue(LiveDirectPhoneController.shouldAttachVisualFrame("target_not_found", normalScreen))
            assertTrue(LiveDirectPhoneController.shouldAttachVisualFrame("ambiguous_target", normalScreen))
            assertTrue(LiveDirectPhoneController.shouldAttachVisualFrame(null, denseScreen))
            assertFalse(LiveDirectPhoneController.shouldAttachVisualFrame(null, normalScreen))
            assertFalse(LiveDirectPhoneController.shouldAttachVisualFrame("target_not_found", protectedScreen))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun toolCallCancelledStopsInFlightStepSkipsQueuedCancelledCallAndPreservesCompletedRecords() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = RecordingLiveSession()
        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { LiveTurnSignalSnapshot(sessionActive = true, speaking = false, liveUserTranscript = "", inputLevel = 0f) },
            userTouchingProvider = { false },
            clockMs = { 10_000L },
        )
        val tracker = LiveSessionToolTracker()
        val completedActionLog = CopyOnWriteArrayList<String>()
        val recoverySummaries = CopyOnWriteArrayList<String>()
        val step1DoneGate = CompletableDeferred<Unit>()

        val calls = listOf(
            LiveFunctionCall("call_1", PersonalLiveTools.QUICK_PHONE_ACT_NAME, buildJsonObject { put("step", 1) }),
            LiveFunctionCall("call_2", "delegate_task", buildJsonObject { put("title", "不应启动") }),
            LiveFunctionCall("call_3", "recall_history", buildJsonObject { put("query", "咖啡") }),
        )

        val batchJob = scope.launch {
            tracker.runBatch(
                session = session,
                calls = calls,
                includeNonBlockingFlag = false,
                turnCoordinator = coordinator,
                onStepCompleted = { call, _ -> recoverySummaries += call.id },
            ) { call ->
                when (call.id) {
                    "call_1" -> {
                        completedActionLog += "call_1:step_1"
                        step1DoneGate.complete(Unit)
                        delay(5_000L)
                        completedActionLog += "call_1:step_2_should_not_run"
                        ExecutedToolStep(buildJsonObject { put("ok", true) }, null)
                    }
                    "call_2" -> {
                        completedActionLog += "call_2:should_never_start"
                        ExecutedToolStep(buildJsonObject { put("ok", true) }, null)
                    }
                    else -> {
                        completedActionLog += "call_3:completed"
                        ExecutedToolStep(buildJsonObject { put("status", "ok") }, LiveScheduling.WHEN_IDLE)
                    }
                }
            }
        }

        runCurrent()
        step1DoneGate.await()
        // Server cancels in-flight call_1 and queued call_2, leaving call_3 intact
        tracker.cancelCalls(listOf("call_1", "call_2"))
        runCurrent()
        batchJob.join()

        assertEquals(listOf("call_1:step_1", "call_3:completed"), completedActionLog.toList())
        assertEquals(listOf("call_3"), session.sentToolResponses.map { it.id })
        assertEquals(listOf("call_3"), recoverySummaries.toList())

        // If all calls in a batch are cancelled, sendToolResponses must not be invoked at all
        val session2 = RecordingLiveSession()
        tracker.cancelCalls(listOf("all_cancelled"))
        tracker.runBatch(
            session = session2,
            calls = listOf(LiveFunctionCall("all_cancelled", "recall_history", JsonObject(emptyMap()))),
            includeNonBlockingFlag = false,
            turnCoordinator = coordinator,
            onStepCompleted = { _, _ -> },
        ) {
            fail("Pre-cancelled call must not execute")
            ExecutedToolStep(JsonObject(emptyMap()), null)
        }
        assertTrue(session2.sentToolResponses.isEmpty())
    }

    @Test
    fun userTextDelivererQueuesDuringConnectingAndNeverDuplicatesOrFallsBackOnFailure() {
        var phase = LiveSessionPhase.Connecting
        var activeSession: RecordingLiveSession? = null
        val persisted = mutableListOf<String>()
        val acceptedTurns = mutableListOf<String>()
        val recoveryMarked = mutableListOf<String>()

        val deliverer = LiveUserTextDeliverer(
            phaseProvider = { phase },
            sessionProvider = { activeSession },
            flushTranscripts = {},
            persistUserMessageOnce = { persisted += it },
            onAcceptedTurn = { acceptedTurns += it },
            onActiveDelivered = { recoveryMarked += it },
            onSendFailedMessage = { "连接失败" },
            onSuspendedMessage = { "通话暂挂中" },
        )

        // 1. Connecting: queues message and persists ONCE without waking text model
        deliverer.deliver("  帮我看下明天天气  ")
        assertEquals(1, deliverer.queuedCount())
        assertEquals(listOf("帮我看下明天天气"), persisted)
        assertEquals(listOf("帮我看下明天天气"), acceptedTurns)
        assertTrue("Recovery must not be marked before socket delivery", recoveryMarked.isEmpty())

        // 2. Session becomes Active: flushes queued message WITHOUT re-persisting
        val sock = RecordingLiveSession(acceptUserText = true)
        activeSession = sock
        phase = LiveSessionPhase.Active
        deliverer.flushToConnectedSession(sock)
        assertEquals(0, deliverer.queuedCount())
        assertEquals("Must still be persisted exactly once", 1, persisted.size)
        assertEquals(listOf("帮我看下明天天气" to true), sock.sentUserTexts.toList())
        assertEquals(listOf("帮我看下明天天气"), recoveryMarked)

        // 3. Socket sendUserText returns false while Active: throws PersonalSystemException, zero extra persistence, zero recovery pollution
        sock.acceptUserText = false
        try {
            deliverer.deliver("再查一下高铁票")
            fail("Expected PersonalSystemException when WebSocket sendUserText returns false")
        } catch (e: PersonalSystemException) {
            assertEquals("live_send_failed", e.systemType)
        }
        assertEquals("Failed send must not persist message", 1, persisted.size)
        assertEquals("Failed send must not pollute recovery prompt", 1, recoveryMarked.size)

        // 4. SuspendedByCall: rejects cleanly without persisting
        phase = LiveSessionPhase.SuspendedByCall
        try {
            deliverer.deliver("在吗")
            fail("Expected PersonalSystemException during SuspendedByCall")
        } catch (e: PersonalSystemException) {
            assertEquals("live_on_call", e.systemType)
        }
        assertEquals(1, persisted.size)
    }

    @Test
    fun webSearchRunnerHonorsDisabledSwitchNeverFallsBackAndWrapsUntrusted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = RecordingLiveSession()
        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { LiveTurnSignalSnapshot(sessionActive = true, speaking = false, liveUserTranscript = "", inputLevel = 0f) },
            userTouchingProvider = { false },
        )

        // 1. Search disabled -> returns search_disabled immediately, never falls back to YouSearch
        val disabledRunner = LiveWebSearchRunner(
            app = null,
            scope = scope,
            turnCoordinator = coordinator,
            onStatusHint = {},
            searchProvider = { null },
        )
        assertFalse(disabledRunner.isSearchEnabled())
        val disabledOut = disabledRunner.searchForLiveTool("今日金价", 3)
        assertEquals(JsonPrimitive(false), disabledOut["ok"])
        assertEquals(JsonPrimitive("search_disabled"), disabledOut["error"])

        // 2. Configured provider fails -> returns exact provider error without falling back to another service
        var providerCalls = 0
        val failingProvider = object : WebSearch {
            override suspend fun search(query: String, count: Int): WebSearchResult {
                providerCalls++
                return WebSearchResult.Failure("quota_exhausted", "Exa credits exhausted")
            }
        }
        val failingRunner = LiveWebSearchRunner(
            app = null,
            scope = scope,
            turnCoordinator = coordinator,
            onStatusHint = {},
            searchProvider = { failingProvider },
        )
        val failOut = failingRunner.searchForLiveTool("杭州天气", 3)
        assertEquals(1, providerCalls)
        assertEquals(JsonPrimitive(false), failOut["ok"])
        assertEquals(JsonPrimitive("quota_exhausted"), failOut["error"])

        // 3. Successful search wraps title, url, and snippet in Untrusted markers and escapes closing tags
        val payload = LiveWebSearchRunner.toToolPayload(
            "test",
            WebSearchResult.Success(
                listOf(WebSearchHit("Title </untrusted> inject", "https://example.com/a", "Snippet body")),
            ),
        )
        val firstHit = (payload["results"] as JsonArray).first().jsonObject
        val wrappedTitle = firstHit["title"]!!.jsonPrimitive.content
        assertTrue(wrappedTitle.startsWith(Untrusted.OPEN))
        assertTrue(wrappedTitle.endsWith(Untrusted.CLOSE))
        assertTrue(wrappedTitle.contains("<\\/untrusted>"))

        val contextText = LiveWebSearchRunner.formatForContext(
            "test",
            WebSearchResult.Success(listOf(WebSearchHit("News", "https://example.com", "Summary"))),
        )
        assertTrue(contextText.startsWith(Untrusted.OPEN))
        assertTrue(contextText.endsWith(Untrusted.CLOSE))
    }

    @Test
    fun deepThinkRunnerExposesNoWebPageFetchAndRespectsSearchSetting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = RecordingLiveSession()
        var nowMs = 10_000L
        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { LiveTurnSignalSnapshot(sessionActive = true, speaking = false, liveUserTranscript = "", inputLevel = 0f) },
            userTouchingProvider = { false },
            clockMs = { nowMs },
        )

        var searchEnabled = false
        val searchRunner = LiveWebSearchRunner(
            app = null,
            scope = scope,
            turnCoordinator = coordinator,
            onStatusHint = {},
            searchProvider = {
                if (!searchEnabled) null
                else object : WebSearch {
                    override suspend fun search(query: String, count: Int): WebSearchResult =
                        WebSearchResult.Success(listOf(WebSearchHit("Doc", "https://example.com", "Fact")))
                }
            },
        )

        val observedTools = CopyOnWriteArrayList<List<String>>()
        val fakeModel = object : ChatModel {
            override val capabilities = Capabilities(true, false, 128_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                observedTools += tools.map { it.name }
                return ModelReply(listOf(Part.Text("核心结论：优先选轻量方案。")), StopReason.EndTurn, null)
            }
        }

        val runner = LiveDeepThinkRunner(
            app = null,
            scope = scope,
            webSearch = searchRunner,
            turnCoordinator = coordinator,
            onStatusHint = {},
            modelProvider = { fakeModel },
        )

        // When web search is disabled, no tools at all are exposed (and read_web_page never exists)
        assertTrue(runner.exposedToolsForStep(1).isEmpty())

        // When web search is enabled, ONLY web_search is exposed (never read_web_page)
        searchEnabled = true
        val stepTools = runner.exposedToolsForStep(1).map { it.name }
        assertEquals(listOf("web_search"), stepTools)
        assertFalse("read_web_page" in stepTools)

        val started = runner.startThink("周末去哪露营", "带老人小孩", "")
        assertEquals(JsonPrimitive(true), started["ok"])
        assertEquals(JsonPrimitive("thinking"), started["status"])
    }

    @Test
    fun resourceSchedulerExternalOwnerPreventsConcurrentTaskAppContention() = runTest {
        val scheduler = ResourceScheduler()
        var directActive = true
        scheduler.registerExternalOwner("live-direct-1") { directActive }

        // Direct controller reserves WeChat
        assertTrue(
            scheduler.reserveApp(
                packageName = "com.tencent.mm",
                owner = "live-direct-1",
                holderActive = { false },
                stillUsed = { true },
            ),
        )

        // Background TaskRun attempting to use WeChat sees it busy and cannot steal it
        assertFalse(
            scheduler.appFree(
                packageName = "com.tencent.mm",
                owner = "task-run-1",
                holderActive = { it == "task-run-1" },
                stillUsed = { true },
            ),
        )
        assertFalse(
            scheduler.reserveApp(
                packageName = "com.tencent.mm",
                owner = "task-run-1",
                holderActive = { it == "task-run-1" },
                stillUsed = { true },
            ),
        )

        // Background TaskRun awaits WeChat; as soon as direct session releases, awaitApp wakes up immediately
        val waiter = async {
            scheduler.awaitApp(
                packageName = "com.tencent.mm",
                owner = "task-run-1",
                timeoutMs = 5_000L,
                holderActive = { it == "task-run-1" },
                free = {
                    scheduler.appFree(
                        packageName = "com.tencent.mm",
                        owner = "task-run-1",
                        holderActive = { it == "task-run-1" },
                        stillUsed = { true },
                    )
                },
            )
        }
        runCurrent()
        assertFalse(waiter.isCompleted)

        directActive = false
        scheduler.unregisterExternalOwner("live-direct-1")
        runCurrent()

        assertEquals(AppWait.Free, waiter.await())
        assertTrue(
            scheduler.reserveApp(
                packageName = "com.tencent.mm",
                owner = "task-run-1",
                holderActive = { it == "task-run-1" },
                stillUsed = { true },
            ),
        )
    }

    @Test
    fun directScrollStepDefaultsToScreenCenterAndHonorsExplicitCoordinates() {
        val defaultDown = LiveDirectPhoneController.buildDirectScrollStep(
            buildJsonObject { put("direction", "down") },
        )
        assertEquals("phone_scroll", defaultDown.toolName)
        assertEquals(null, defaultDown.error)
        assertEquals(JsonPrimitive("down"), defaultDown.toolArgs?.get("direction"))
        assertEquals(JsonPrimitive(3), defaultDown.toolArgs?.get("amount"))
        assertEquals(JsonPrimitive(500), defaultDown.toolArgs?.get("x"))
        assertEquals(JsonPrimitive(500), defaultDown.toolArgs?.get("y"))

        val explicitSwipe = LiveDirectPhoneController.buildDirectScrollStep(
            buildJsonObject {
                put("direction", "up")
                put("amount", 6)
                put("x", 220)
                put("y", 740)
            },
        )
        assertEquals("phone_scroll", explicitSwipe.toolName)
        assertEquals(JsonPrimitive("up"), explicitSwipe.toolArgs?.get("direction"))
        assertEquals(JsonPrimitive(6), explicitSwipe.toolArgs?.get("amount"))
        assertEquals(JsonPrimitive(220), explicitSwipe.toolArgs?.get("x"))
        assertEquals(JsonPrimitive(740), explicitSwipe.toolArgs?.get("y"))

        assertEquals(
            "invalid_direction",
            LiveDirectPhoneController.buildDirectScrollStep(buildJsonObject { put("direction", "diagonal") }).error,
        )
        assertEquals(
            "invalid_coordinates",
            LiveDirectPhoneController.buildDirectScrollStep(
                buildJsonObject {
                    put("direction", "down")
                    put("x", 1200)
                },
            ).error,
        )
    }

    @Test
    fun playbackChunkQueueNeverDrivesQueuedBytesNegativeOnBargeInAndCapsMaxBuffer() {
        val queue = PlaybackChunkQueue(maxBytes = 100)

        // Simulate barge-in right after poll() pops a chunk: clear() advances generation and resets bytesQueued to 0
        assertTrue(queue.offer(ByteArray(60) { 1 }))
        assertEquals(60, queue.queuedBytes)
        val first = queue.poll()!!
        assertEquals(0, first.generation)
        assertEquals(0, queue.queuedBytes)

        val clearedGen = queue.clear()
        assertEquals(1, clearedGen)
        assertEquals(0, queue.queuedBytes)

        // Subsequent short reply must reflect exact positive byte count so playback pre-buffering never stalls
        assertTrue(queue.offer(ByteArray(24) { 2 }))
        assertEquals(24, queue.queuedBytes)
        val second = queue.poll()!!
        assertEquals(1, second.generation)
        assertEquals(24, second.bytes.size)
        assertEquals(0, queue.queuedBytes)

        // Offering beyond maxBytes drops oldest chunks and never exceeds cap
        queue.offer(ByteArray(40) { 3 })
        queue.offer(ByteArray(40) { 4 })
        queue.offer(ByteArray(40) { 5 })
        assertEquals(80, queue.queuedBytes)
        assertEquals(4, queue.poll()!!.bytes[0].toInt())
        assertEquals(5, queue.poll()!!.bytes[0].toInt())
        assertEquals(0, queue.queuedBytes)
        assertFalse(queue.hasChunks)

        // Odd byte counts align down to 2-byte PCM16 sample boundaries
        assertTrue(queue.offer(ByteArray(25) { 7 }))
        assertEquals(24, queue.queuedBytes)
        assertEquals(24, queue.poll()!!.bytes.size)
        assertFalse(queue.offer(ByteArray(1) { 9 }))
    }

    @Test
    fun deepThinkRunnerEmitsErrorToolResultsBeyondParallelCapAndKeepsAlternatingUserRoles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val session = RecordingLiveSession()
        var nowMs = 20_000L
        val coordinator = LiveTurnCoordinator(
            scope = scope,
            sessionProvider = { session },
            signalProvider = { LiveTurnSignalSnapshot(sessionActive = true, speaking = false, liveUserTranscript = "", inputLevel = 0f) },
            userTouchingProvider = { false },
            clockMs = { nowMs },
        )

        var searchExecutions = 0
        val searchRunner = LiveWebSearchRunner(
            app = null,
            scope = scope,
            turnCoordinator = coordinator,
            onStatusHint = {},
            searchProvider = {
                object : WebSearch {
                    override suspend fun search(query: String, count: Int): WebSearchResult {
                        searchExecutions++
                        return WebSearchResult.Success(listOf(WebSearchHit("Hit:$query", "https://example.com", "Body")))
                    }
                }
            },
        )

        val capturedHistories = CopyOnWriteArrayList<List<Message>>()
        var runnerRef: LiveDeepThinkRunner? = null
        var stepCount = 0
        val fakeModel = object : ChatModel {
            override val capabilities = Capabilities(true, false, 128_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                stepCount++
                capturedHistories += messages.toList()
                return when (stepCount) {
                    1 -> {
                        // Emit 5 parallel tool calls (> MAX_PARALLEL_SEARCH_CALLS = 3)
                        val calls = (1..5).map { idx ->
                            Part.ToolCall("tc_$idx", "web_search", """{"query":"q$idx"}""")
                        }
                        ModelReply(calls, StopReason.ToolUse, null)
                    }
                    2, 3, 4 -> {
                        if (stepCount == 4) {
                            // Inject a steer note right before final summary step to verify user messages merge cleanly
                            runnerRef?.steerOrCancel("", "steer", "预算控制在500元以内")
                        }
                        ModelReply(
                            listOf(Part.ToolCall("tc_step_$stepCount", "web_search", """{"query":"step$stepCount"}""")),
                            StopReason.ToolUse,
                            null,
                        )
                    }
                    else -> ModelReply(listOf(Part.Text("最终结论：控制在五百元内选近郊露营。")), StopReason.EndTurn, null)
                }
            }
        }

        val runner = LiveDeepThinkRunner(
            app = null,
            scope = scope,
            webSearch = searchRunner,
            turnCoordinator = coordinator,
            onStatusHint = {},
            modelProvider = { fakeModel },
            ioDispatcher = dispatcher,
        )
        runnerRef = runner

        val started = runner.startThink("周末露营方案", "", "")
        assertEquals(JsonPrimitive(true), started["ok"])
        runCurrent()
        nowMs += 3_000L
        advanceTimeBy(3_000L)
        runCurrent()

        // Step 1 executed 3 searches + steps 2..4 executed 1 each = 6 total searches (tc_4 and tc_5 were capped)
        assertEquals(6, searchExecutions)
        assertEquals(5, capturedHistories.size)

        // History seen on step 2 MUST contain 5 Part.ToolResult items matching tc_1..tc_5
        val step2ToolMsg = capturedHistories[1].last { it.role == Role.Tool }
        val step2Results = step2ToolMsg.parts.filterIsInstance<Part.ToolResult>()
        assertEquals(listOf("tc_1", "tc_2", "tc_3", "tc_4", "tc_5"), step2Results.map { it.callId })
        assertFalse(step2Results[0].isError)
        assertFalse(step2Results[2].isError)
        assertTrue(step2Results[3].isError)
        assertTrue(step2Results[3].content.contains("too_many_parallel_calls"))
        assertTrue(step2Results[4].isError)

        // Final summary call (5th complete call) must use Author.User for every User message and never have adjacent User messages
        for ((callIdx, history) in capturedHistories.withIndex()) {
            for (i in history.indices) {
                val msg = history[i]
                if (msg.role == Role.User) {
                    assertEquals("Call $callIdx message $i must have Author.User", Author.User, msg.author)
                }
                if (i > 0) {
                    assertFalse(
                        "Call $callIdx must not contain consecutive Role.User messages at $i",
                        history[i - 1].role == Role.User && msg.role == Role.User,
                    )
                }
            }
        }
        val finalUserText = capturedHistories.last().last().parts.filterIsInstance<Part.Text>().single().text
        assertTrue(finalUserText.contains("预算控制在500元以内"))
        assertTrue(session.sentContextUpdates.any { it.first.contains("控制在五百元内") })
    }

    @Test
    fun systemPromptSeparatesPreemptivePhoneTrackFromBackgroundDeepThink() {
        val prompt = PersonalLiveTools.buildSystemPrompt(PersonalState(), "", nowMs = 1_700_000_000_000L)
        assertTrue(prompt.contains("绝不要让机主等待上一条任务结束"))
        assertTrue(prompt.contains("deep_think"))
        assertTrue(prompt.contains("互不阻塞"))

        val quickSchema = PersonalLiveTools.QUICK_PHONE_ACT.spec.parametersJsonSchema
        assertTrue(quickSchema.contains("向下看页面下方内容"))
    }
}
