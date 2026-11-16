package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.model.Usage
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object GeminiLiveProtocol {
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
    const val DEFAULT_VOICE = "Aoede"

    val GEMINI_CAPABILITIES = LiveProviderCapabilities(
        supportsNonBlockingTools = true,
        supportsVisualFrames = true,
        supportsResumption = true,
    )

    val VOICES = listOf("Aoede", "Kore", "Puck", "Charon", "Fenrir", "Leda", "Orus", "Zephyr")

    private val json = Json { ignoreUnknownKeys = true }
    private val rejectedSchemaKeys = setOf("additionalProperties", "\$schema", "\$id", "\$defs")

    private fun stripVersionSuffix(url: String): String = url
        .trimEnd('/')
        .removeSuffix("/v1beta")
        .removeSuffix("/v1alpha")
        .removeSuffix("/v1")
        .trimEnd('/')

    fun wsUrl(baseUrl: String): String {
        val cleaned = baseUrl.trim()
            .substringBefore('#')
            .substringBefore('?')
            .trim()
        require(cleaned.isNotBlank()) { "Gemini Live baseUrl 不能为空" }
        val wsBase = when {
            cleaned.startsWith("https://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(8))
                require(rest.isNotBlank()) { "无效的 Gemini Live 主机地址: $baseUrl" }
                "wss://$rest"
            }
            cleaned.startsWith("http://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(7))
                require(rest.isNotBlank()) { "无效的 Gemini Live 主机地址: $baseUrl" }
                "ws://$rest"
            }
            cleaned.startsWith("wss://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(6))
                require(rest.isNotBlank()) { "无效的 Gemini Live 主机地址: $baseUrl" }
                "wss://$rest"
            }
            cleaned.startsWith("ws://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(5))
                require(rest.isNotBlank()) { "无效的 Gemini Live 主机地址: $baseUrl" }
                "ws://$rest"
            }
            "://" in cleaned -> throw IllegalArgumentException("不支持的 Gemini Live 协议地址: $baseUrl")
            else -> {
                val rest = stripVersionSuffix(cleaned)
                require(rest.isNotBlank()) { "无效的 Gemini Live 主机地址: $baseUrl" }
                "wss://$rest"
            }
        }
        val parsed = try {
            URI(wsBase)
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的 Gemini Live 地址: $baseUrl", e)
        }
        require(parsed.userInfo == null && !parsed.host.isNullOrBlank()) { "无效的 Gemini Live 主机地址: $baseUrl" }
        return "$wsBase/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    fun restBaseUrl(baseUrl: String): String {
        val cleaned = baseUrl.trim()
            .substringBefore('#')
            .substringBefore('?')
            .trim()
        require(cleaned.isNotBlank()) { "Gemini API 地址不能为空" }
        val httpBase = when {
            cleaned.startsWith("https://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(8))
                require(rest.isNotBlank()) { "无效的 Gemini API 主机地址: $baseUrl" }
                "https://$rest"
            }
            cleaned.startsWith("http://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(7))
                require(rest.isNotBlank()) { "无效的 Gemini API 主机地址: $baseUrl" }
                "http://$rest"
            }
            cleaned.startsWith("wss://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(6))
                require(rest.isNotBlank()) { "无效的 Gemini API 主机地址: $baseUrl" }
                "https://$rest"
            }
            cleaned.startsWith("ws://", ignoreCase = true) -> {
                val rest = stripVersionSuffix(cleaned.substring(5))
                require(rest.isNotBlank()) { "无效的 Gemini API 主机地址: $baseUrl" }
                "http://$rest"
            }
            "://" in cleaned -> throw IllegalArgumentException("不支持的 Gemini API 协议地址: $baseUrl")
            else -> {
                val rest = stripVersionSuffix(cleaned)
                require(rest.isNotBlank()) { "无效的 Gemini API 主机地址: $baseUrl" }
                "https://$rest"
            }
        }
        val parsed = try {
            URI(httpBase)
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的 Gemini API 地址: $baseUrl", e)
        }
        require(parsed.userInfo == null && !parsed.host.isNullOrBlank()) { "无效的 Gemini API 主机地址: $baseUrl" }
        return httpBase
    }

    fun buildSetupFrame(setup: GeminiLiveSetup): String {
        val modelPath = "models/" + setup.model.trim().removePrefix("models/")
        val payload = buildJsonObject {
            putJsonObject("setup") {
                put("model", modelPath)
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                    if (setup.voiceName.isNotBlank()) {
                        putJsonObject("speechConfig") {
                            putJsonObject("voiceConfig") {
                                putJsonObject("prebuiltVoiceConfig") {
                                    put("voiceName", setup.voiceName.trim())
                                }
                            }
                        }
                    }
                }
                if (setup.systemInstruction.isNotBlank()) {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", setup.systemInstruction) })
                        }
                    }
                }
                if (setup.enableTranscription) {
                    putJsonObject("inputAudioTranscription") {}
                    putJsonObject("outputAudioTranscription") {}
                }
                if (setup.enableSlidingWindow) {
                    putJsonObject("contextWindowCompression") {
                        putJsonObject("slidingWindow") {}
                    }
                }
                putJsonObject("sessionResumption") {
                    setup.resumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
                }
                if (setup.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        add(buildJsonObject {
                            putJsonArray("functionDeclarations") {
                                setup.tools.forEach { add(toolDeclaration(it, setup.includeNonBlockingBehavior)) }
                            }
                        })
                    }
                }
            }
        }
        return payload.toString()
    }

    fun buildAudioChunkFrame(
        pcm16Le: ByteArray,
        offset: Int = 0,
        length: Int = pcm16Le.size - offset,
        sampleRate: Int = INPUT_SAMPLE_RATE,
    ): String {
        val slice = if (offset == 0 && length == pcm16Le.size) pcm16Le else pcm16Le.copyOfRange(offset, offset + length)
        val encoded = Base64.getEncoder().encodeToString(slice)
        return buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("mimeType", "audio/pcm;rate=$sampleRate")
                    put("data", encoded)
                }
            }
        }.toString()
    }

    fun buildAudioStreamEndFrame(): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            put("audioStreamEnd", true)
        }
    }.toString()

    fun buildVideoFrame(jpeg: ByteArray, mimeType: String = "image/jpeg"): String {
        val encoded = Base64.getEncoder().encodeToString(jpeg)
        return buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("video") {
                    put("mimeType", mimeType)
                    put("data", encoded)
                }
            }
        }.toString()
    }

    fun buildClientTextFrame(text: String, role: String = "user", turnComplete: Boolean = true): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    put("role", role)
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", text) })
                    }
                })
            }
            put("turnComplete", turnComplete)
        }
    }.toString()

    fun buildToolResponseFrame(
        id: String,
        name: String,
        output: JsonObject,
        scheduling: LiveScheduling? = null,
    ): String = buildToolResponsesFrame(
        listOf(LiveFunctionResponse(id = id, name = name, output = output, scheduling = scheduling)),
    )

    fun buildToolResponsesFrame(responses: List<LiveFunctionResponse>): String = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                for (r in responses) {
                    add(buildJsonObject {
                        put("id", r.id)
                        put("name", r.name)
                        put("response", r.output)
                        if (r.scheduling != null) put("scheduling", r.scheduling.wire)
                    })
                }
            }
        }
    }.toString()

    /**
     * Parses a single JSON server message into ordered [GeminiLiveEvent]s.
     * One server payload may carry multiple pieces (e.g., audio chunks + outputTranscription + turnComplete + usageMetadata).
     */
    fun parseServerFrame(rawJson: String): List<GeminiLiveEvent> {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val events = ArrayList<GeminiLiveEvent>(4)

        (root["error"] as? JsonObject)?.let { err ->
            val code = (err["code"] as? JsonPrimitive)?.intOrNull ?: -1
            val message = (err["message"] as? JsonPrimitive)?.contentOrNull
                ?: (err["status"] as? JsonPrimitive)?.contentOrNull
                ?: "Gemini Live server error"
            events += LiveSessionEvent.Closed(code, message)
        }

        if ("setupComplete" in root) {
            events += LiveSessionEvent.SetupComplete
        }

        (root["sessionResumptionUpdate"] as? JsonObject)?.let { res ->
            val handle = (res["newHandle"] as? JsonPrimitive)?.contentOrNull
            val resumable = (res["resumable"] as? JsonPrimitive)?.booleanOrNull ?: (handle != null)
            events += LiveSessionEvent.SessionResumptionUpdate(handle, resumable)
        }

        (root["goAway"] as? JsonObject)?.let { goAway ->
            val timeLeft = (goAway["timeLeft"] as? JsonPrimitive)?.contentOrNull
            events += LiveSessionEvent.GoAway(timeLeft)
        }

        (root["serverContent"] as? JsonObject)?.let { content ->
            val interrupted = (content["interrupted"] as? JsonPrimitive)?.booleanOrNull == true
            if (interrupted) {
                events += LiveSessionEvent.Interrupted
            }

            (content["inputTranscription"] as? JsonObject)?.let { tr ->
                val text = (tr["text"] as? JsonPrimitive)?.contentOrNull
                val finished = (tr["finished"] as? JsonPrimitive)?.booleanOrNull == true
                if (finished) {
                    events += LiveSessionEvent.InputTranscriptCompleted(text?.trim().orEmpty())
                } else if (!text.isNullOrEmpty()) {
                    events += LiveSessionEvent.InputTranscriptDelta(text)
                }
            }

            (content["modelTurn"] as? JsonObject)?.let { turn ->
                val parts = turn["parts"] as? JsonArray
                parts?.forEach { el ->
                    val part = el as? JsonObject ?: return@forEach
                    val inline = part["inlineData"] as? JsonObject
                    if (inline != null) {
                        val mime = (inline["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val data = (inline["data"] as? JsonPrimitive)?.contentOrNull
                        if (!data.isNullOrEmpty() && mime.startsWith("audio/pcm", ignoreCase = true)) {
                            runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                                events += LiveSessionEvent.AudioDelta(it)
                            }
                        }
                    }
                }
            }

            (content["outputTranscription"] as? JsonObject)?.let { tr ->
                val text = (tr["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) events += LiveSessionEvent.OutputTranscriptDelta(text)
            }

            val turnComplete = (content["turnComplete"] as? JsonPrimitive)?.booleanOrNull == true
            if (turnComplete) {
                events += LiveSessionEvent.TurnComplete
            }
        }

        (root["toolCall"] as? JsonObject)?.let { tc ->
            val rawCalls = tc["functionCalls"] as? JsonArray
            val parsed = rawCalls?.mapIndexedNotNull { idx, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "live-$idx-$name"
                val args = (obj["args"] as? JsonObject) ?: JsonObject(emptyMap())
                LiveFunctionCall(id, name, args)
            }.orEmpty()
            if (parsed.isNotEmpty()) events += LiveSessionEvent.ToolCalls(parsed)
        }

        (root["toolCallCancellation"] as? JsonObject)?.let { cancel ->
            val ids = (cancel["ids"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                .orEmpty()
            if (ids.isNotEmpty()) events += LiveSessionEvent.ToolCallCancelled(ids)
        }

        (root["usageMetadata"] as? JsonObject)?.let { um ->
            val prompt = (um["promptTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val response = (um["responseTokenCount"] as? JsonPrimitive)?.intOrNull
                ?: (um["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull
                ?: 0
            val cached = (um["cachedContentTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val thoughts = (um["thoughtsTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            if (prompt > 0 || response > 0 || cached > 0 || thoughts > 0) {
                events += LiveSessionEvent.UsageReport(
                    Usage(
                        inputTokens = prompt.coerceAtLeast(0),
                        outputTokens = (response + thoughts).coerceAtLeast(0),
                        cachedInputTokens = cached.coerceIn(0, prompt.coerceAtLeast(0)),
                        reasoningTokens = thoughts.coerceAtLeast(0),
                    ),
                )
            }
        }

        return events
    }

    private fun sanitizeSchema(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.filterKeys { it !in rejectedSchemaKeys }.mapValues { (k, v) ->
            if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { sanitizeSchema(it.value) }) else sanitizeSchema(v)
        })
        is JsonArray -> JsonArray(el.map(::sanitizeSchema))
        else -> el
    }

    private fun toolDeclaration(tool: LiveToolSpec, includeNonBlocking: Boolean): JsonObject = buildJsonObject {
        put("name", tool.spec.name)
        put("description", tool.spec.description)
        val rawSchema = json.parseToJsonElement(tool.spec.parametersJsonSchema)
        val clean = sanitizeSchema(rawSchema)
        val hasProps = (clean as? JsonObject)?.get("properties")?.let { it is JsonObject && it.isNotEmpty() } == true
        if (clean !is JsonObject || ("properties" !in clean) || hasProps) {
            put("parameters", clean)
        }
        if (includeNonBlocking && tool.behavior == LiveToolBehavior.NON_BLOCKING) {
            put("behavior", LiveToolBehavior.NON_BLOCKING.wire)
        }
    }
}
