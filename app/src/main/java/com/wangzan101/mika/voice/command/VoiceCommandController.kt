package com.wangzan101.mika.voice.command

import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class VoiceCommandPhase { Idle, Listening, Recognizing }

internal sealed interface VoiceCommandFeedback {
    data object NoSpeech : VoiceCommandFeedback
    data object NotMatched : VoiceCommandFeedback
    data object TimedOut : VoiceCommandFeedback
    data object MicrophoneBusy : VoiceCommandFeedback
    data object Unavailable : VoiceCommandFeedback
    data class Failed(val why: SpeechFailure) : VoiceCommandFeedback
}

/** Only this short-lived capture is owned here; the Live session has its own lifecycle. */
internal interface CommandCapture {
    val events: Flow<SpeechEvent>
    /** Capture still ends at five seconds; a remote file transcriber may need longer to reply. */
    val resultWaitMs: Long get() = 3_000
    fun release()
    /** Releases capture and observers; when [awaitMicIdle] is true, confirms the microphone is idle before Live starts. */
    suspend fun close(awaitMicIdle: Boolean = true): Boolean
}

internal object VoiceCommandMatcher {
    fun matches(text: String): Boolean = text.filterNot(Char::isWhitespace)
        .trimEnd('。', '！', '!', '.', '，', ',', '、', '；', ';') == "开启语音模式"
}

internal data class CommandPrerequisites(
    val enabled: Boolean,
    val liveConfigured: Boolean,
    val liveRunning: Boolean,
    val budgetAvailable: Boolean,
    val recordPermission: Boolean,
    val unlocked: Boolean,
    val callBusy: Boolean,
) {
    val allowed: Boolean get() = enabled && liveConfigured && !liveRunning && budgetAvailable &&
        recordPermission && unlocked && !callBusy
}

/** Runs on the main thread. Each request owns its capture, deadline and late-result guard. */
internal class VoiceCommandController(
    private val scope: CoroutineScope,
    private val eligible: () -> Boolean,
    private val open: (onConflict: () -> Unit) -> CommandCapture?,
    private val onMatched: () -> Unit,
    private val captureMs: Long = 5_000,
    private val resultWaitMs: Long = 3_000,
    private val diagnostic: (String) -> Unit = {},
) {
    private val mutablePhase = MutableStateFlow(VoiceCommandPhase.Idle)
    val phase = mutablePhase.asStateFlow()
    private val mutableFeedback = MutableStateFlow<VoiceCommandFeedback?>(null)
    val feedback = mutableFeedback.asStateFlow()
    private val mutableHeardText = MutableStateFlow("")
    val heardText = mutableHeardText.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun start() {
        cancel()
        mutableHeardText.value = ""
        if (!eligible()) { diagnostic("skipped-prerequisites"); return }
        val request = generation
        val previous = job
        job = scope.launch(start = CoroutineStart.LAZY) {
            // A canceled request must finish releasing its microphone before a new one opens it.
            previous?.join()
            if (request != generation || !eligible()) return@launch
            var capture: CommandCapture? = null
            try {
                capture = open { cancel(VoiceCommandFeedback.MicrophoneBusy) } ?: run {
                    mutableFeedback.value = VoiceCommandFeedback.Unavailable
                    diagnostic("capture-unavailable")
                    return@launch
                }
                if (!isActive || request != generation) return@launch
                mutablePhase.value = VoiceCommandPhase.Listening
                diagnostic("listening")
                val activeCapture = capture
                var failure: SpeechFailure? = null
                var peakLevel = 0f
                val text = coroutineScope {
                    val result = CompletableDeferred<String?>()
                    val events = launch {
                        activeCapture.events.collect { event ->
                            when (event) {
                                is SpeechEvent.Level -> peakLevel = maxOf(peakLevel, event.value)
                                is SpeechEvent.Partial -> mutableHeardText.value = (event.stable + event.tail).take(120)
                                is SpeechEvent.Final -> {
                                    mutableHeardText.value = event.text.take(120)
                                    diagnostic("final chars=${event.text.length} matched=${VoiceCommandMatcher.matches(event.text)} inputPeak=${(peakLevel * 100).toInt()}")
                                    result.complete(event.text)
                                }
                                is SpeechEvent.Failed -> {
                                    failure = event.why
                                    diagnostic("recognition-failed type=${event.why.javaClass.simpleName}")
                                    result.complete(null)
                                }
                                else -> Unit
                            }
                        }
                        result.complete(null)
                    }
                    val deadline = launch {
                        delay(captureMs)
                        mutablePhase.value = VoiceCommandPhase.Recognizing
                        diagnostic("capture-ended-awaiting-result")
                        activeCapture.release()
                    }
                    try {
                        withTimeoutOrNull(captureMs + maxOf(resultWaitMs, activeCapture.resultWaitMs)) { result.await() }
                    } finally {
                        deadline.cancelAndJoin()
                        events.cancelAndJoin()
                    }
                }
                val matched = failure == null && text != null && VoiceCommandMatcher.matches(text) &&
                    isActive && request == generation
                if (matched && mutablePhase.value == VoiceCommandPhase.Listening) {
                    mutablePhase.value = VoiceCommandPhase.Recognizing
                }
                val quiet = withContext(NonCancellable) { activeCapture.close(awaitMicIdle = matched) }
                capture = null
                if (isActive && request == generation) {
                    when {
                        failure != null -> mutableFeedback.value = VoiceCommandFeedback.Failed(requireNotNull(failure))
                        text == null -> mutableFeedback.value = VoiceCommandFeedback.TimedOut
                        text.isBlank() -> mutableFeedback.value = VoiceCommandFeedback.NoSpeech
                        !VoiceCommandMatcher.matches(text) -> mutableFeedback.value = VoiceCommandFeedback.NotMatched
                        !quiet -> mutableFeedback.value = VoiceCommandFeedback.MicrophoneBusy
                        !eligible() -> mutableFeedback.value = VoiceCommandFeedback.Unavailable
                        else -> {
                            mutablePhase.value = VoiceCommandPhase.Idle
                            diagnostic("starting-live")
                            onMatched()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed optional command must never interrupt opening the normal app.
                diagnostic("command-failed type=${error.javaClass.simpleName}")
                if (request == generation) mutableFeedback.value = VoiceCommandFeedback.Unavailable
            } finally {
                withContext(NonCancellable) { capture?.close(awaitMicIdle = false) }
                if (request == generation) mutablePhase.value = VoiceCommandPhase.Idle
            }
        }.also { it.start() }
    }

    fun cancel(feedback: VoiceCommandFeedback? = null) {
        generation++
        job?.cancel()
        mutablePhase.value = VoiceCommandPhase.Idle
        mutableFeedback.value = feedback
    }

    fun clearFeedback() { mutableFeedback.value = null }
}
