package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * 实时全双工语音会话能力声明（当前由 [GeminiLiveClient] 提供 Gemini Live BidiGenerateContent 实现）。
 */
data class LiveProviderCapabilities(
    val supportsNonBlockingTools: Boolean = true,
    val supportsVisualFrames: Boolean = true,
    val supportsResumption: Boolean = true,
) {
    companion object {
        val DEFAULT = LiveProviderCapabilities()
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

    /** 用户一句话的稳态 ASR 转写完成事件（`text` 可能为空，代表流式增量结束边界）。 */
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
        get() = LiveProviderCapabilities.DEFAULT

    val events: Flow<LiveSessionEvent>

    fun sendAudioPcm16(pcm16Le: ByteArray, byteCount: Int = pcm16Le.size): Boolean
    fun sendAudioStreamEnd(): Boolean
    fun sendVideoJpeg(jpeg: ByteArray): Boolean
    fun sendUserText(text: String, turnComplete: Boolean = true): Boolean
    fun sendContextUpdate(text: String, triggerResponse: Boolean = false): Boolean

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

typealias GeminiLiveSetup = LiveSessionConfig
typealias GeminiLiveEvent = LiveSessionEvent
