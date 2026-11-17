package com.wangzan101.mika.voice.command

import com.wangzan101.mika.MikaApp
import android.util.Log
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.voice.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal enum class CommandBackend { Asr, System }

/** Preparation is local only. Constructing a SpeechSession is deferred until mic preflight passes. */
internal class CommandSpeechSource(private val app: MikaApp) {
    internal class Prepared(
        val backend: CommandBackend,
        val streaming: Boolean = true,
        val resultWaitMs: Long = 11_000,
        val create: (CommandRecorder?, CoroutineScope) -> SpeechSession,
    )

    fun prepare(): Prepared? {
        configuredAsr()?.let { return it }
        val capability = SystemSpeech.capability(app, app.speech.systemSilent.value)
        if (!capability.usable) return null
        val compatibility = SystemSpeech.serviceCompatibility()
        val attempts = SystemSpeech.attempts(SystemSpeech.services(app), app.speech.systemService, compatibility)
        return Prepared(CommandBackend.System, resultWaitMs = 3_000) { _, _ ->
            // The short command timeout must not mark the normal input service as unresponsive.
            SystemSpeechSession(
                app, capability.onDevice, "zh-CN", attempts = attempts, compatibility = compatibility,
                serviceWithoutMic = { named, onDevice -> SpeechServiceMic.missing(app, named, onDevice) },
                singleUtterance = true,
            )
        }
    }

    private fun configuredAsr(): Prepared? = when (val choice = app.speech.choice.value) {
        is SpeechChoice.Doubao -> app.speech.doubaoCredentials()
            ?.takeIf { it.valid && choice.resourceId in DoubaoProtocol.resources }
            ?.let { credentials ->
                Prepared(CommandBackend.Asr) { recorder, scope ->
                    DoubaoSpeech(requireNotNull(recorder), { DoubaoTransport().connect(credentials, choice.resourceId) },
                        scope, connectTimeoutMs = 5_000)
                }
            }
        is SpeechChoice.FunAsr -> FunAsrProtocol.normalize(choice.url)?.let { url ->
            Prepared(CommandBackend.Asr) { recorder, scope ->
                LiveSpeech(requireNotNull(recorder), FunAsrProtocol(choice.language), { LiveAsrTransport().connect(url) },
                    scope, connectTimeoutMs = 5_000)
            }
        }
        is SpeechChoice.Bailian -> app.speech.bailianKey(choice.region)?.let { key ->
            Prepared(CommandBackend.Asr) { recorder, scope ->
                LiveSpeech(requireNotNull(recorder), BailianProtocol(choice.model, choice.language), {
                    LiveAsrTransport().connect(choice.region.endpoint(choice.workspace), mapOf("Authorization" to "Bearer $key"))
                }, scope, connectTimeoutMs = 5_000)
            }
        }
        is SpeechChoice.Provider -> app.providers.providers.value.firstOrNull { it.id == choice.providerId }
            ?.takeIf { choice.model.isNotBlank() && it.baseUrl.isNotBlank() }
            ?.let { provider ->
                val key = app.providers.providerKey(provider.id)
                if (app.providers.hasKey(provider.id) && key.isNullOrBlank()) null else {
                    val transcriber = Transcriber(ProviderStore.modelHttp, app.providers.clientHeaders(provider))
                    Prepared(CommandBackend.Asr, streaming = false, resultWaitMs = ProviderSpeech.TRANSCRIBE_TIMEOUT_MS + 1_000) { recorder, scope ->
                        ProviderSpeech(requireNotNull(recorder), { wav ->
                            Log.d("VoiceCommand", "transcription-input wavBytes=${wav.size}")
                            transcriber.transcribe(provider, key, choice.model, choice.method, wav, "zh")
                        }, scope)
                    }
                }
            }
        SpeechChoice.Auto, SpeechChoice.System, SpeechChoice.Off -> null
    }

    fun open(prepared: Prepared, scope: CoroutineScope, onConflict: () -> Unit): CommandCapture? {
        val gate = MicAvailabilityGate(app)
        if (!gate.watch(scope, onConflict)) { gate.close(); return null }
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        val recorder = if (prepared.backend == CommandBackend.Asr) CommandRecorder(prepared.streaming, gate) else null
        if (prepared.backend == CommandBackend.System) gate.expectSystemCapture()
        val session = try {
            prepared.create(recorder, sessionScope)
        } catch (_: Exception) {
            gate.close()
            sessionScope.cancel()
            recorder?.cancel()
            return null
        }
        return object : CommandCapture {
            override val events = session.events
            override val resultWaitMs = prepared.resultWaitMs
            private var closed = false
            override fun release() = session.release()
            override suspend fun close(awaitMicIdle: Boolean): Boolean {
                if (!closed) {
                    closed = true
                    // All command observers and timers are gone before Live gets ownership.
                    gate.close()
                    session.cancel()
                    sessionScope.cancel()
                    recorder?.close()
                }
                return if (awaitMicIdle) gate.awaitIdle() else gate.idle()
            }
        }
    }
}
