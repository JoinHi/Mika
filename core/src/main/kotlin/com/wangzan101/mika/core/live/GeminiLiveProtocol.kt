package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.model.ToolSpec
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

enum class LiveToolBehavior(val wire: String) {
    BLOCKING("BLOCKING"),
    NON_BLOCKING("NON_BLOCKING"),
}

enum class LiveScheduling(val wire: String) {
    SILENT("SILENT"),
    WHEN_IDLE("WHEN_IDLE"),
    INTERRUPT("INTERRUPT"),
}

data class LiveToolSpec(
    val spec: ToolSpec,
    val behavior: LiveToolBehavior = LiveToolBehavior.NON_BLOCKING,
)

data class LiveFunctionCall(
    val id: String,
    val name: String,
    val args: JsonObject,
)

data class LiveFunctionResponse(
    val id: String,
    val name: String,
    val output: JsonObject,
    val scheduling: LiveScheduling? = null,
)

data class GeminiLiveSetup(
    val model: String = GeminiLiveProtocol.DEFAULT_MODEL,
    val systemInstruction: String = "",
    val voiceName: String = GeminiLiveProtocol.DEFAULT_VOICE,
    val tools: List<LiveToolSpec> = emptyList(),
    val resumptionHandle: String? = null,
    val enableSlidingWindow: Boolean = true,
    val enableTranscription: Boolean = true,
    val includeNonBlockingBehavior: Boolean = true,
)

sealed interface GeminiLiveEvent {
    data object SetupComplete : GeminiLiveEvent

    class AudioDelta(val pcm24kLe: ByteArray) : GeminiLiveEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioDelta && pcm24kLe.contentEquals(other.pcm24kLe))

        override fun hashCode(): Int = pcm24kLe.contentHashCode()
    }

    data class InputTranscriptDelta(val text: String) : GeminiLiveEvent

    data class OutputTranscriptDelta(val text: String) : GeminiLiveEvent

    data object Interrupted : GeminiLiveEvent

    data object TurnComplete : GeminiLiveEvent

    data class ToolCalls(val calls: List<LiveFunctionCall>) : GeminiLiveEvent

    data class ToolCallCancelled(val ids: List<String>) : GeminiLiveEvent

    data class SessionResumptionUpdate(val newHandle: String?, val resumable: Boolean) : GeminiLiveEvent

    data class GoAway(val timeLeft: String?) : GeminiLiveEvent

    data class UsageReport(val usage: Usage) : GeminiLiveEvent

    data class Closed(val code: Int, val reason: String, val error: Throwable? = null) : GeminiLiveEvent
}

object GeminiLiveProtocol {
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
    const val DEFAULT_VOICE = "Aoede"

    val VOICES = listOf("Aoede", "Kore", "Puck", "Charon", "Fenrir", "Leda", "Orus", "Zephyr")

    private val json = Json { ignoreUnknownKeys = true }
    private val rejectedSchemaKeys = setOf("additionalProperties", "\$schema", "\$id", "\$defs")

    fun wsUrl(baseUrl: String): String {
        val raw = baseUrl.trim()
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .removeSuffix("/v1beta")
            .removeSuffix("/v1alpha")
            .removeSuffix("/v1")
            .trimEnd('/')
        require(raw.isNotBlank()) { "Gemini Live baseUrl 不能为空" }
        val wsBase = when {
            raw.startsWith("https://", ignoreCase = true) -> "wss://" + raw.substring(8)
            raw.startsWith("http://", ignoreCase = true) -> "ws://" + raw.substring(7)
            raw.startsWith("wss://", ignoreCase = true) -> "wss://" + raw.substring(6)
            raw.startsWith("ws://", ignoreCase = true) -> "ws://" + raw.substring(5)
            "://" in raw -> throw IllegalArgumentException("不支持的 Gemini Live 协议地址: $baseUrl")
            else -> "wss://$raw"
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
        val raw = baseUrl.trim()
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .removeSuffix("/v1beta")
            .removeSuffix("/v1alpha")
            .removeSuffix("/v1")
            .trimEnd('/')
        require(raw.isNotBlank()) { "Gemini API 地址不能为空" }
        val httpBase = when {
            raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("wss://", ignoreCase = true) -> "https://" + raw.substring(6)
            raw.startsWith("ws://", ignoreCase = true) -> "http://" + raw.substring(5)
            "://" in raw -> throw IllegalArgumentException("不支持的 Gemini API 协议地址: $baseUrl")
            else -> "https://$raw"
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

        if ("setupComplete" in root) {
            events += GeminiLiveEvent.SetupComplete
        }

        (root["sessionResumptionUpdate"] as? JsonObject)?.let { res ->
            val handle = (res["newHandle"] as? JsonPrimitive)?.contentOrNull
            val resumable = (res["resumable"] as? JsonPrimitive)?.booleanOrNull ?: (handle != null)
            events += GeminiLiveEvent.SessionResumptionUpdate(handle, resumable)
        }

        (root["goAway"] as? JsonObject)?.let { goAway ->
            val timeLeft = (goAway["timeLeft"] as? JsonPrimitive)?.contentOrNull
            events += GeminiLiveEvent.GoAway(timeLeft)
        }

        (root["serverContent"] as? JsonObject)?.let { content ->
            val interrupted = (content["interrupted"] as? JsonPrimitive)?.booleanOrNull == true
            if (interrupted) {
                events += GeminiLiveEvent.Interrupted
            }

            (content["inputTranscription"] as? JsonObject)?.let { tr ->
                val text = (tr["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) events += GeminiLiveEvent.InputTranscriptDelta(text)
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
                                events += GeminiLiveEvent.AudioDelta(it)
                            }
                        }
                    }
                }
            }

            (content["outputTranscription"] as? JsonObject)?.let { tr ->
                val text = (tr["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) events += GeminiLiveEvent.OutputTranscriptDelta(text)
            }

            val turnComplete = (content["turnComplete"] as? JsonPrimitive)?.booleanOrNull == true
            if (turnComplete) {
                events += GeminiLiveEvent.TurnComplete
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
            if (parsed.isNotEmpty()) events += GeminiLiveEvent.ToolCalls(parsed)
        }

        (root["toolCallCancellation"] as? JsonObject)?.let { cancel ->
            val ids = (cancel["ids"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                .orEmpty()
            if (ids.isNotEmpty()) events += GeminiLiveEvent.ToolCallCancelled(ids)
        }

        (root["usageMetadata"] as? JsonObject)?.let { um ->
            val prompt = (um["promptTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val response = (um["responseTokenCount"] as? JsonPrimitive)?.intOrNull
                ?: (um["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull
                ?: 0
            val cached = (um["cachedContentTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            val thoughts = (um["thoughtsTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0
            if (prompt > 0 || response > 0 || cached > 0 || thoughts > 0) {
                events += GeminiLiveEvent.UsageReport(
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
