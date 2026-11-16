package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * 实时语音模型的工具调用能力分级：
 * - [RICH_TOOLS]：原生支持多工具 + 结构化 JSON 参数 + 异步回执（如 Gemini Live、OpenAI Realtime）；
 * - [LITE_INTENT]：仅支持少量极简扁平单字符串参数工具，复杂推理与参数路由下沉给端侧 Jev / Light 模型（如千问 Qwen-Omni Realtime）；
 * - [SIDECAR_ONLY]：不向语音模型暴露工具（0 工具），语音模型专注自然全双工对话，端侧监听 [LiveSessionEvent.InputTranscriptCompleted]
 *   并通过本地快速规则 + Jev 意图旁路执行手机/工具动作后再注入播报上下文（如豆包实时语音大模型 VolcRealtime）。
 */
enum class LiveToolTier {
    RICH_TOOLS,
    LITE_INTENT,
    SIDECAR_ONLY,
}

data class LiveProviderCapabilities(
    val toolTier: LiveToolTier = LiveToolTier.RICH_TOOLS,
    val supportsNonBlockingTools: Boolean = true,
    val supportsVisualFrames: Boolean = true,
    val supportsResumption: Boolean = true,
    val inputSampleRateHz: Int = 16_000,
    val outputSampleRateHz: Int = 24_000,
) {
    companion object {
        val DEFAULT_RICH = LiveProviderCapabilities()

        val OPENAI_REALTIME = LiveProviderCapabilities(
            toolTier = LiveToolTier.RICH_TOOLS,
            supportsNonBlockingTools = false,
            supportsVisualFrames = false,
            supportsResumption = false,
            inputSampleRateHz = 24_000,
            outputSampleRateHz = 24_000,
        )

        val QWEN_OMNI_LITE = LiveProviderCapabilities(
            toolTier = LiveToolTier.LITE_INTENT,
            supportsNonBlockingTools = false,
            supportsVisualFrames = true,
            supportsResumption = false,
            inputSampleRateHz = 16_000,
            outputSampleRateHz = 24_000,
        )

        val VOLC_SIDECAR_ONLY = LiveProviderCapabilities(
            toolTier = LiveToolTier.SIDECAR_ONLY,
            supportsNonBlockingTools = false,
            supportsVisualFrames = false,
            supportsResumption = false,
            inputSampleRateHz = 16_000,
            outputSampleRateHz = 24_000,
        )
    }
}

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

data class LiveSessionConfig(
    val model: String = GeminiLiveProtocol.DEFAULT_MODEL,
    val systemInstruction: String = "",
    val voiceName: String = GeminiLiveProtocol.DEFAULT_VOICE,
    val tools: List<LiveToolSpec> = emptyList(),
    val resumptionHandle: String? = null,
    val enableSlidingWindow: Boolean = true,
    val enableTranscription: Boolean = true,
    val includeNonBlockingBehavior: Boolean = true,
)

sealed interface LiveSessionEvent {
    data object SetupComplete : LiveSessionEvent

    class AudioDelta(val pcm24kLe: ByteArray) : LiveSessionEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioDelta && pcm24kLe.contentEquals(other.pcm24kLe))

        override fun hashCode(): Int = pcm24kLe.contentHashCode()
    }

    data class InputTranscriptDelta(val text: String) : LiveSessionEvent

    /** 用户一句话的稳态 ASR 转写完成事件（供 [LiveToolTier.SIDECAR_ONLY] 旁路意图拦截及精确话轮归档使用）。 */
    data class InputTranscriptCompleted(val text: String) : LiveSessionEvent

    data class OutputTranscriptDelta(val text: String) : LiveSessionEvent

    data object Interrupted : LiveSessionEvent

    data object TurnComplete : LiveSessionEvent

    data class ToolCalls(val calls: List<LiveFunctionCall>) : LiveSessionEvent

    data class ToolCallCancelled(val ids: List<String>) : LiveSessionEvent

    data class SessionResumptionUpdate(val newHandle: String?, val resumable: Boolean) : LiveSessionEvent

    data class GoAway(val timeLeft: String?) : LiveSessionEvent

    data class UsageReport(val usage: Usage) : LiveSessionEvent

    data class Closed(val code: Int, val reason: String, val error: Throwable? = null) : LiveSessionEvent
}

interface LiveRealtimeSession : AutoCloseable {
    val capabilities: LiveProviderCapabilities
        get() = LiveProviderCapabilities.DEFAULT_RICH

    val events: Flow<LiveSessionEvent>

    fun sendAudioPcm16(pcm16Le: ByteArray, byteCount: Int = pcm16Le.size): Boolean
    fun sendAudioStreamEnd(): Boolean
    fun sendVideoJpeg(jpeg: ByteArray): Boolean
    fun sendUserText(text: String, turnComplete: Boolean = true): Boolean
    fun sendContextUpdate(text: String, triggerResponse: Boolean = false): Boolean

    /** 主动取消当前正在生成的模型语音回复（供旁路接管话轮时抑制抢答）。 */
    fun cancelCurrentResponse(): Boolean = false

    fun sendToolResponse(
        id: String,
        name: String,
        output: JsonObject,
        scheduling: LiveScheduling? = null,
    ): Boolean

    fun sendToolResponses(responses: List<LiveFunctionResponse>): Boolean {
        if (responses.isEmpty()) return false
        var ok = true
        for (r in responses) {
            if (!sendToolResponse(r.id, r.name, r.output, r.scheduling)) ok = false
        }
        return ok
    }

    override fun close()
}

fun interface LiveRealtimeConnector {
    suspend fun connect(config: LiveSessionConfig): LiveRealtimeSession
}

typealias GeminiLiveSetup = LiveSessionConfig
typealias GeminiLiveEvent = LiveSessionEvent
