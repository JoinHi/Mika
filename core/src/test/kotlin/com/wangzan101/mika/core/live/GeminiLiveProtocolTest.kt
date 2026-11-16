package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.personal.PersonalActions
import com.wangzan101.mika.core.personal.PersonalAgent
import com.wangzan101.mika.core.personal.PersonalState
import com.wangzan101.mika.core.personal.PersonalStore
import com.wangzan101.mika.core.personal.PersonalSystemException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun wsUrlNormalizesHttpAndVersionSuffixesAndRejectsMalformedInput() {
        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            GeminiLiveProtocol.wsUrl("https://generativelanguage.googleapis.com"),
        )
        assertEquals(
            "wss://proxy.example.com/gemini/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            GeminiLiveProtocol.wsUrl("https://proxy.example.com/gemini/v1beta/?foo=1#bar"),
        )
        assertEquals(
            "ws://127.0.0.1:8080/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            GeminiLiveProtocol.wsUrl("http://127.0.0.1:8080/v1"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.wsUrl("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.wsUrl("https://user:secret@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.wsUrl("ftp://example.com")
        }
    }

    @Test
    fun setupFrameSanitizesSchemasAndHandlesZeroPropertyTools() {
        val withParams = LiveToolSpec(
            spec = ToolSpec(
                name = "delegate_task",
                description = "Run phone task",
                parametersJsonSchema = """{"type":"object","properties":{"goal":{"type":"string"}},"required":["goal"],"additionalProperties":false}""",
            ),
            behavior = LiveToolBehavior.NON_BLOCKING,
        )
        val noParams = LiveToolSpec(
            spec = ToolSpec(
                name = "ping_status",
                description = "Zero arg status",
                parametersJsonSchema = """{"type":"object","properties":{},"additionalProperties":false}""",
            ),
            behavior = LiveToolBehavior.BLOCKING,
        )
        val raw = GeminiLiveProtocol.buildSetupFrame(
            GeminiLiveSetup(
                model = "gemini-2.5-flash-native-audio-preview-12-2025",
                systemInstruction = "你是 Mika。",
                voiceName = "Kore",
                tools = listOf(withParams, noParams),
                resumptionHandle = "resume-tok-99",
            ),
        )
        val setup = json.parseToJsonElement(raw).jsonObject.getValue("setup").jsonObject
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", setup.getValue("model").jsonPrimitive.content)
        assertEquals("resume-tok-99", setup.getValue("sessionResumption").jsonObject.getValue("handle").jsonPrimitive.content)

        val decls = setup.getValue("tools").jsonArray.single().jsonObject
            .getValue("functionDeclarations").jsonArray
        val delegateDecl = decls[0].jsonObject
        assertEquals("delegate_task", delegateDecl.getValue("name").jsonPrimitive.content)
        assertEquals("NON_BLOCKING", delegateDecl.getValue("behavior").jsonPrimitive.content)
        val params = delegateDecl.getValue("parameters").jsonObject
        assertFalse("additionalProperties" in params)
        assertTrue("goal" in params.getValue("properties").jsonObject)

        val zeroArgDecl = decls[1].jsonObject
        assertEquals("ping_status", zeroArgDecl.getValue("name").jsonPrimitive.content)
        assertFalse("Zero-arg tool must omit empty parameters object", "parameters" in zeroArgDecl)
    }

    @Test
    fun realtimeFramesAndToolResponsesUseModernWireFields() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertEquals(LiveSessionEvent.AudioDelta(byteArrayOf(1, 2, 3, 4)), LiveSessionEvent.AudioDelta(pcm))
        assertNotEquals(LiveSessionEvent.AudioDelta(byteArrayOf(9, 9)), LiveSessionEvent.AudioDelta(pcm))

        val audioObj = json.parseToJsonElement(GeminiLiveProtocol.buildAudioChunkFrame(pcm)).jsonObject
            .getValue("realtimeInput").jsonObject.getValue("audio").jsonObject
        assertEquals("audio/pcm;rate=16000", audioObj.getValue("mimeType").jsonPrimitive.content)
        assertArrayEquals(pcm, Base64.getDecoder().decode(audioObj.getValue("data").jsonPrimitive.content))

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00)
        val videoObj = json.parseToJsonElement(GeminiLiveProtocol.buildVideoFrame(jpeg)).jsonObject
            .getValue("realtimeInput").jsonObject.getValue("video").jsonObject
        assertEquals("image/jpeg", videoObj.getValue("mimeType").jsonPrimitive.content)

        val batchResp = json.parseToJsonElement(
            GeminiLiveProtocol.buildToolResponsesFrame(
                listOf(
                    LiveFunctionResponse("c1", "quick_phone_act", buildJsonObject { put("ok", true) }),
                    LiveFunctionResponse("c2", "remember", buildJsonObject { put("status", "ok") }, LiveScheduling.SILENT),
                ),
            ),
        ).jsonObject.getValue("toolResponse").jsonObject.getValue("functionResponses").jsonArray
        assertEquals(2, batchResp.size)
        assertEquals("c1", batchResp[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("c2", batchResp[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("SILENT", batchResp[1].jsonObject.getValue("scheduling").jsonPrimitive.content)
    }

    @Test
    fun parseServerFrameExtractsAudioTranscriptsToolCallsUsageAndLifecycleSignals() {
        val pcm24k = byteArrayOf(10, 20, 30, 40)
        val encoded = Base64.getEncoder().encodeToString(pcm24k)
        val frame = """
            {
              "sessionResumptionUpdate": {"newHandle": "h-42", "resumable": true},
              "goAway": {"timeLeft": "30s"},
              "serverContent": {
                "interrupted": true,
                "inputTranscription": {"text": "帮我打开微信"},
                "modelTurn": {
                  "parts": [
                    {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$encoded"}}
                  ]
                },
                "outputTranscription": {"text": "好的，正在打开微信"},
                "turnComplete": true
              },
              "toolCall": {
                "functionCalls": [
                  {"id": "fc-1", "name": "delegate_task", "args": {"title": "打开微信", "goal": "打开微信"}},
                  {"name": "remember", "args": {"text": "偏好"}}
                ]
              },
              "toolCallCancellation": {"ids": ["fc-old"]},
              "usageMetadata": {
                "promptTokenCount": 320,
                "responseTokenCount": 85,
                "cachedContentTokenCount": 120,
                "thoughtsTokenCount": 15
              }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerFrame(frame)
        assertTrue(events.any { it is LiveSessionEvent.SessionResumptionUpdate && it.newHandle == "h-42" && it.resumable })
        assertTrue(events.any { it is LiveSessionEvent.GoAway && it.timeLeft == "30s" })
        assertTrue(events.any { it is LiveSessionEvent.Interrupted })
        assertTrue(events.any { it is LiveSessionEvent.InputTranscriptDelta && it.text == "帮我打开微信" })
        val audio = events.filterIsInstance<LiveSessionEvent.AudioDelta>().single()
        assertArrayEquals(pcm24k, audio.pcm24kLe)
        assertTrue(events.any { it is LiveSessionEvent.OutputTranscriptDelta && it.text == "好的，正在打开微信" })
        assertTrue(events.any { it is LiveSessionEvent.TurnComplete })
        val calls = events.filterIsInstance<LiveSessionEvent.ToolCalls>().single().calls
        assertEquals(2, calls.size)
        assertEquals("fc-1", calls[0].id)
        assertEquals("live-1-remember", calls[1].id)
        assertEquals(listOf("fc-old"), events.filterIsInstance<LiveSessionEvent.ToolCallCancelled>().single().ids)
        val usage = events.filterIsInstance<LiveSessionEvent.UsageReport>().single().usage
        assertEquals(Usage(inputTokens = 320, outputTokens = 100, cachedInputTokens = 120, reasoningTokens = 15), usage)
        assertFalse(events.any { it is LiveSessionEvent.InputTranscriptCompleted })

        val finishedFrame = """
            {
              "serverContent": {
                "inputTranscription": {"text": "  帮我定明天早上七点闹钟  ", "finished": true}
              }
            }
        """.trimIndent()
        val finishedEvents = GeminiLiveProtocol.parseServerFrame(finishedFrame)
        assertFalse("Finished frame must emit Completed without duplicate Delta", finishedEvents.any { it is LiveSessionEvent.InputTranscriptDelta })
        assertEquals(
            "帮我定明天早上七点闹钟",
            finishedEvents.filterIsInstance<LiveSessionEvent.InputTranscriptCompleted>().single().text,
        )

        val emptyFinishedFrame = """{"serverContent":{"inputTranscription":{"text":"","finished":true}}}"""
        val emptyFinishedEvents = GeminiLiveProtocol.parseServerFrame(emptyFinishedFrame)
        assertEquals(
            "",
            emptyFinishedEvents.filterIsInstance<LiveSessionEvent.InputTranscriptCompleted>().single().text,
        )

        assertTrue(GeminiLiveProtocol.GEMINI_CAPABILITIES.supportsVisualFrames)
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.wsUrl("https://")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.restBaseUrl("https://")
        }
    }

    @Test
    fun listBidiModelsFollowsPaginationAndSortsNativeAudioFirst() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "models": [
                    {"name": "models/gemini-2.5-pro", "supportedGenerationMethods": ["generateContent"]},
                    {"name": "models/gemini-2.0-flash-exp", "supportedGenerationMethods": ["bidiGenerateContent"]}
                  ],
                  "nextPageToken": "page-2"
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "models": [
                    {"name": "models/gemini-2.5-flash-native-audio-preview-12-2025", "supportedGenerationMethods": ["bidiGenerateContent"]}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val baseUrl = "http://${server.hostName}:${server.port}"
            val result = GeminiLiveModels.listBidiModels(
                baseUrl = baseUrl,
                apiKey = "k-123",
                http = OkHttpClient(),
                extraHeaders = mapOf("User-Agent" to "MikaTest/1.0"),
            )
            assertEquals(
                listOf("gemini-2.5-flash-native-audio-preview-12-2025", "gemini-2.0-flash-exp"),
                result.getOrThrow(),
            )
            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            assertEquals("MikaTest/1.0", req1.getHeader("User-Agent"))
            assertTrue(req2.path.orEmpty().contains("pageToken=page-2"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun clientCapsAudioBurstWithoutEverDroppingControlEvents() = runBlocking {
        val server = MockWebServer()
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val burstFlushedLatch = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    assertEquals("test-key", response.request.header("x-goog-api-key"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"setupComplete":{}}""")
                    repeat(220) {
                        webSocket.send("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$encoded"}}]}}}""")
                    }
                    webSocket.send("""{"toolCallCancellation":{"ids":["c-99"]}}""")
                    webSocket.send("""{"serverContent":{"outputTranscription":{"text":"你好！"},"turnComplete":true}}""")
                    webSocket.close(1000, "burst_done")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    burstFlushedLatch.countDown()
                }
            }),
        )
        server.start()
        try {
            val baseUrl = "http://${server.hostName}:${server.port}"
            val client = GeminiLiveClient(http = OkHttpClient())
            client.connect(baseUrl, "test-key", GeminiLiveSetup()).use { session ->
                assertTrue("Timed out waiting for burst close handshake", burstFlushedLatch.await(5, TimeUnit.SECONDS))
                val collected = session.events.toList()
                val audioCount = collected.count { it is LiveSessionEvent.AudioDelta }
                assertEquals(GeminiLiveClient.MAX_QUEUED_AUDIO_CHUNKS, audioCount)
                assertTrue("ToolCallCancelled must never be dropped", collected.any { it is LiveSessionEvent.ToolCallCancelled })
                assertTrue("Transcript must never be dropped", collected.any { it is LiveSessionEvent.OutputTranscriptDelta && it.text == "你好！" })
                assertTrue("TurnComplete must arrive intact", collected.any { it is LiveSessionEvent.TurnComplete })
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun personalAgentLiveMethodsPersistHandledMessagesDeduplicateAtomicallyAndThrowSystemExceptions(): Unit = runBlocking {
        val stateFlow = MutableStateFlow(PersonalState())
        val store = object : PersonalStore {
            override val state = stateFlow
            override fun update(change: (PersonalState) -> PersonalState) {
                stateFlow.value = change(stateFlow.value)
            }
        }
        val textModelCalls = AtomicInteger(0)
        val startedTasks = ArrayList<Pair<String, String>>()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val agent = PersonalAgent(
                store = store,
                scope = scope,
                model = {
                    textModelCalls.incrementAndGet()
                    null
                },
                actions = object : PersonalActions {
                    override fun start(conversationId: String, goal: String) {
                        startedTasks += conversationId to goal
                    }
                    override fun revise(conversationId: String, instruction: String) = "ok"
                    override fun cancel(conversationId: String) = "cancelled"
                    override fun remember(text: String, quote: String) = "saved:$text"
                    override fun memory() = ""
                },
            )
            val userMsg = agent.recordLiveMessage("user", "帮我看看微信")
            val botMsg = agent.recordLiveMessage("assistant", "好嘞，马上打开微信")
            assertNotNull(userMsg)
            assertNotNull(botMsg)
            assertTrue(store.state.value.messages.all { it.handled })
            assertEquals(0, textModelCalls.get())

            val task1 = agent.startLiveTask("查看微信", "打开微信看看未读消息", sourceId = "src-1")
            val task2 = agent.startLiveTask("查看微信", "打开微信看看未读消息", sourceId = "src-1")
            assertEquals(task1.id, task2.id)
            assertEquals(1, startedTasks.size)

            // Once task1 reaches terminal state, re-issuing the same goal in the same turn must start a fresh task
            agent.updateTask(task1.id, "cancelled", result = "已取消")
            val task3 = agent.startLiveTask("查看微信", "打开微信看看未读消息", sourceId = "src-1")
            assertFalse(task1.id == task3.id)
            assertEquals(2, startedTasks.size)

            assertThrows(PersonalSystemException::class.java) {
                agent.reviseLiveTask("missing-id", "改成飞书")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun illegalApiKeyAndHandshakeTimeoutFailCleanlyWithoutLeakingOrSwallowing(): Unit = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Intentionally never send setupComplete to simulate stalled handshake
                }
            }),
        )
        server.start()
        try {
            val baseUrl = "http://${server.hostName}:${server.port}"
            val illegalRes = GeminiLiveModels.listBidiModels(baseUrl, "密钥：sk-abc\n123", OkHttpClient())
            assertTrue(illegalRes.isFailure)
            assertTrue(illegalRes.exceptionOrNull() is com.wangzan101.mika.core.model.ModelError.IllegalHeader)
            assertEquals(0, server.requestCount)

            assertThrows(com.wangzan101.mika.core.model.ModelError.IllegalHeader::class.java) {
                runBlocking {
                    GeminiLiveClient(http = OkHttpClient()).connect(baseUrl, "sk-\nabc", GeminiLiveSetup())
                }
            }
            assertEquals(0, server.requestCount)

            assertThrows(java.net.SocketTimeoutException::class.java) {
                runBlocking {
                    GeminiLiveClient(http = OkHttpClient(), connectTimeoutMs = 180L).connect(baseUrl, "valid-key", GeminiLiveSetup())
                }
            }
        } finally {
            server.shutdown()
        }
    }
}
