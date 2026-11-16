package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.model.headerOrReject
import com.wangzan101.mika.core.net.NetProxy
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class GeminiLiveClient(
    http: OkHttpClient = defaultHttpClient(),
    private val connectTimeoutMs: Long = 15_000L,
) {
    private val wsHttp: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    suspend fun connect(
        baseUrl: String,
        apiKey: String,
        setup: GeminiLiveSetup,
        extraHeaders: Map<String, String> = emptyMap(),
    ): LiveRealtimeSession {
        require(apiKey.isNotBlank()) { "缺少 Gemini API Key" }
        val wsUrl = GeminiLiveProtocol.wsUrl(baseUrl)
        val ready = CompletableDeferred<Unit>()
        val closedFlag = AtomicBoolean(false)
        val queuedAudioChunks = AtomicInteger(0)
        // Control events (SetupComplete, Interrupted, TurnComplete, ToolCalls, ToolCallCancelled, Closed)
        // must never be evicted by audio bursts; cap only queued AudioDelta chunks in FIFO order.
        val channel = Channel<GeminiLiveEvent>(Channel.UNLIMITED)

        val request = Request.Builder()
            .url(wsUrl)
            .apply { extraHeaders.forEach { (k, v) -> headerOrReject(k, v) } }
            .headerOrReject("x-goog-api-key", apiKey.trim())
            .build()

        val socket = wsHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.close()
                val sent = webSocket.send(GeminiLiveProtocol.buildSetupFrame(setup))
                if (!sent && !ready.isCompleted) {
                    ready.completeExceptionally(IOException("无法发送 Gemini Live 初始化消息"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                acceptPayload(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                acceptPayload(bytes.utf8())
            }

            private fun acceptPayload(raw: String) {
                val parsed = GeminiLiveProtocol.parseServerFrame(raw)
                for (event in parsed) {
                    if (event is LiveSessionEvent.SetupComplete && !ready.isCompleted) {
                        ready.complete(Unit)
                    }
                    if (event is LiveSessionEvent.Closed) {
                        emitClosed(event.code, event.reason, event.error)
                        continue
                    }
                    if (event is LiveSessionEvent.AudioDelta) {
                        if (queuedAudioChunks.incrementAndGet() > MAX_QUEUED_AUDIO_CHUNKS) {
                            queuedAudioChunks.decrementAndGet()
                            continue
                        }
                        if (channel.trySend(event).isFailure) {
                            queuedAudioChunks.updateAndGet { if (it > 0) it - 1 else 0 }
                        }
                    } else {
                        channel.trySend(event)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                emitClosed(code, reason, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                emitClosed(code, reason, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status: Int
                val detail: String
                try {
                    status = response?.code ?: -1
                    detail = response?.message?.takeIf { it.isNotBlank() } ?: t.message.orEmpty()
                } finally {
                    response?.close()
                }
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IOException("Gemini Live 连接失败 (status=$status): $detail", t))
                }
                emitClosed(status, detail, t)
            }

            private fun emitClosed(code: Int, reason: String, error: Throwable?) {
                if (closedFlag.compareAndSet(false, true)) {
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(IOException("Gemini Live 会话关闭 ($code): $reason", error))
                    }
                    channel.trySend(LiveSessionEvent.Closed(code, reason, error))
                    channel.close()
                }
            }
        })

        try {
            withTimeoutOrNull(connectTimeoutMs) { ready.await() }
                ?: throw SocketTimeoutException("Gemini Live 握手超时 (${connectTimeoutMs}ms)")
        } catch (t: Throwable) {
            socket.cancel()
            channel.close()
            throw t
        }

        return object : LiveRealtimeSession {
            override val capabilities: LiveProviderCapabilities = GeminiLiveProtocol.GEMINI_CAPABILITIES

            override val events: Flow<LiveSessionEvent> = channel.receiveAsFlow().onEach { ev ->
                if (ev is LiveSessionEvent.AudioDelta) {
                    queuedAudioChunks.updateAndGet { if (it > 0) it - 1 else 0 }
                }
            }

            override fun sendAudioPcm16(pcm16Le: ByteArray, byteCount: Int): Boolean {
                if (closedFlag.get() || byteCount <= 0) return false
                if (socket.queueSize() > MAX_OUTBOUND_MEDIA_QUEUE_BYTES) return false
                val len = byteCount.coerceAtMost(pcm16Le.size)
                return socket.send(GeminiLiveProtocol.buildAudioChunkFrame(pcm16Le, 0, len))
            }

            override fun sendAudioStreamEnd(): Boolean {
                if (closedFlag.get()) return false
                return socket.send(GeminiLiveProtocol.buildAudioStreamEndFrame())
            }

            override fun sendVideoJpeg(jpeg: ByteArray): Boolean {
                if (closedFlag.get() || jpeg.isEmpty()) return false
                if (socket.queueSize() > MAX_OUTBOUND_MEDIA_QUEUE_BYTES) return false
                return socket.send(GeminiLiveProtocol.buildVideoFrame(jpeg))
            }

            override fun sendUserText(text: String, turnComplete: Boolean): Boolean {
                val clean = text.trim()
                if (closedFlag.get() || clean.isEmpty()) return false
                return socket.send(GeminiLiveProtocol.buildClientTextFrame(clean, role = "user", turnComplete = turnComplete))
            }

            override fun sendContextUpdate(text: String, triggerResponse: Boolean): Boolean {
                val clean = text.trim()
                if (closedFlag.get() || clean.isEmpty()) return false
                return socket.send(GeminiLiveProtocol.buildClientTextFrame(clean, role = "user", turnComplete = triggerResponse))
            }

            override fun sendToolResponse(
                id: String,
                name: String,
                output: JsonObject,
                scheduling: LiveScheduling?,
            ): Boolean {
                if (closedFlag.get()) return false
                return socket.send(GeminiLiveProtocol.buildToolResponseFrame(id, name, output, scheduling))
            }

            override fun sendToolResponses(responses: List<LiveFunctionResponse>): Boolean {
                if (closedFlag.get() || responses.isEmpty()) return false
                return socket.send(GeminiLiveProtocol.buildToolResponsesFrame(responses))
            }

            override fun close() {
                if (closedFlag.compareAndSet(false, true)) {
                    runCatching { socket.close(1000, "client_close") }
                    channel.close()
                }
            }
        }
    }

    companion object {
        internal const val MAX_QUEUED_AUDIO_CHUNKS = 160
        internal const val MAX_OUTBOUND_MEDIA_QUEUE_BYTES = 512L * 1024L

        private val sharedDefaultHttp: OkHttpClient by lazy {
            NetProxy.shared.client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(15, TimeUnit.SECONDS),
            )
        }

        fun defaultHttpClient(): OkHttpClient = sharedDefaultHttp
    }
}

object GeminiLiveModels {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_MODEL_PAGES = 5

    private val sharedModelsHttp: OkHttpClient by lazy {
        NetProxy.shared.client(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS),
        )
    }

    /**
     * Queries `GET {baseUrl}/v1beta/models` (following `nextPageToken` pagination) and returns model IDs
     * whose `supportedGenerationMethods` includes `"bidiGenerateContent"`, preferring native-audio / live models.
     */
    suspend fun listBidiModels(
        baseUrl: String,
        apiKey: String,
        http: OkHttpClient = sharedModelsHttp,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result<List<String>> = try {
        require(apiKey.isNotBlank()) { "缺少 Gemini API Key" }
        val cleanBase = GeminiLiveProtocol.restBaseUrl(baseUrl)
        val client = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val collected = LinkedHashSet<String>()
        var pageToken: String? = null
        var pages = 0
        while (pages < MAX_MODEL_PAGES) {
            pages++
            val tokenParam = pageToken?.takeIf { it.isNotBlank() }?.let {
                "&pageToken=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name())
            }.orEmpty()
            val req = Request.Builder()
                .url("$cleanBase/v1beta/models?pageSize=100$tokenParam")
                .apply { extraHeaders.forEach { (k, v) -> headerOrReject(k, v) } }
                .headerOrReject("x-goog-api-key", apiKey.trim())
                .get()
                .build()
            val body = executeAsync(client, req)
            val (ids, nextToken) = parseBidiModelPage(body)
            collected += ids
            if (nextToken.isNullOrBlank() || nextToken == pageToken) break
            pageToken = nextToken
        }
        Result.success(sortBidiModelIds(collected))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun executeAsync(client: OkHttpClient, request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            if (cont.isActive) cont.resumeWithException(IOException("HTTP ${resp.code}"))
                            return
                        }
                        val text = runCatching { resp.body?.string().orEmpty() }
                        if (!cont.isActive) return
                        text.fold(cont::resume, cont::resumeWithException)
                    }
                }
            })
        }

    internal fun parseBidiModelIds(rawBody: String): List<String> =
        sortBidiModelIds(parseBidiModelPage(rawBody).first)

    internal fun parseBidiModelPage(rawBody: String): Pair<List<String>, String?> {
        val root = json.parseToJsonElement(rawBody).jsonObject
        val nextToken = (root["nextPageToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val models = root["models"] as? JsonArray ?: return emptyList<String>() to nextToken
        val ids = models.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val methods = (obj["supportedGenerationMethods"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            id.takeIf { methods.any { m -> m.equals("bidiGenerateContent", ignoreCase = true) } }
        }
        return ids to nextToken
    }

    private fun sortBidiModelIds(ids: Collection<String>): List<String> =
        ids.distinct().sortedWith(
            compareByDescending<String> { it.contains("native-audio", ignoreCase = true) }
                .thenByDescending { it.contains("live", ignoreCase = true) }
                .thenByDescending { it.contains("2.5", ignoreCase = true) || it.contains("3.", ignoreCase = true) }
                .thenBy { it },
        )
}
