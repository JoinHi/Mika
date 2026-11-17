package com.wangzan101.mika.voice.command

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class CommandRecording(val sessionId: Int, val source: Int, val silenced: Boolean)

/** Public recording configurations anonymize app identity; use the recording session instead. */
internal class CommandMicPolicy {
    @get:Synchronized @set:Synchronized
    var ownSessionId: Int? = null

    @get:Synchronized @set:Synchronized
    var expectingSystemCapture = false

    private var systemSessionId: Int? = null

    @Synchronized
    fun accepts(recordings: List<CommandRecording>): Boolean {
        if (recordings.any { it.sessionId <= 0 }) return false
        if (expectingSystemCapture && systemSessionId == null && recordings.size == 1) {
            val candidate = recordings.single()
            // SpeechRecognizer owns its AudioRecord and exposes no session ID. After an empty
            // preflight, admit only its first ordinary mic/recognition capture. This remains
            // best effort at simultaneous starts; never infer ownership from an app UID.
            if (candidate.source == MediaRecorder.AudioSource.MIC ||
                candidate.source == MediaRecorder.AudioSource.VOICE_RECOGNITION
            ) {
                systemSessionId = candidate.sessionId
            }
        }
        val own = ownSessionId ?: systemSessionId
        return recordings.all { it.sessionId == own && !it.silenced }
    }
}

/** Observes only for a command request. It never changes mode, route or audio focus. */
internal class MicAvailabilityGate(context: Context) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val policy = CommandMicPolicy()
    private var callback: AudioManager.AudioRecordingCallback? = null
    private var polling: Job? = null
    @Volatile private var closed = false

    private data class Snapshot(val allowed: Boolean, val recordings: List<CommandRecording>)

    private fun snapshot(): Snapshot? = runCatching {
        Snapshot(
            audio.mode == AudioManager.MODE_NORMAL && !audio.isMicrophoneMute,
            audio.activeRecordingConfigurations.map {
                CommandRecording(it.clientAudioSessionId, it.clientAudioSource, it.isClientSilenced)
            },
        )
    }.getOrNull()

    fun idle(): Boolean = snapshot()?.let { it.allowed && it.recordings.isEmpty() } == true

    fun watch(scope: CoroutineScope, onConflict: () -> Unit): Boolean {
        if (closed || !idle()) return false
        fun check() {
            if (closed) return
            val state = snapshot()
            if (state == null || !state.allowed || !policy.accepts(state.recordings)) {
                Log.d("VoiceCommand", "microphone-conflict allowed=${state?.allowed} captures=${state?.recordings?.map { "${it.sessionId}:${it.source}:${it.silenced}" }}")
                onConflict()
            }
        }
        val observer = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                // Re-query: queued callbacks can describe a recorder that has already stopped.
                check()
            }
        }
        callback = observer
        try {
            audio.registerAudioRecordingCallback(observer, handler)
        } catch (_: RuntimeException) {
            close()
            return false
        }
        if (!idle()) { close(); return false }
        polling = scope.launch {
            while (isActive) {
                delay(100)
                check() // Mode changes (including VoIP) need not produce a recording callback.
            }
        }
        return true
    }

    fun identifyOwn(sessionId: Int?) { policy.ownSessionId = sessionId }
    fun expectSystemCapture() { policy.expectingSystemCapture = true }

    /** A stopped recognizer may take a moment to relinquish its system-side AudioRecord. */
    suspend fun awaitIdle(): Boolean = withTimeoutOrNull(1_000) {
        while (!idle()) delay(50)
        true
    } ?: false

    override fun close() {
        if (closed) return
        closed = true
        polling?.cancel()
        polling = null
        callback?.let { runCatching { audio.unregisterAudioRecordingCallback(it) } }
        callback = null
    }
}
