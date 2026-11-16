package com.wangzan101.mika.personal.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.wangzan101.mika.core.live.GeminiLiveProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Abstraction over full-duplex audio capture/playback so [PersonalLiveRuntime] can coordinate
 * session lifecycle, barge-in, and unit tests deterministically without requiring hardware audio.
 */
internal interface LiveAudioController {
    val inputLevel: StateFlow<Float>
    val outputLevel: StateFlow<Float>
    val speaking: StateFlow<Boolean>
    fun isSystemInCallMode(): Boolean
    fun setMuted(value: Boolean)
    fun start(initialMuted: Boolean = false): Boolean
    fun enqueueOutputPcm24k(pcm24kLe: ByteArray)
    fun clearPlayback()
    fun pauseForPreemption() = stop()
    fun stop()
}

/**
 * Local full-duplex voice communication engine:
 * - 16 kHz mono PCM16 capture with hardware AEC / NS / AGC
 * - 24 kHz mono PCM16 playback with 60ms adaptive jitter pre-buffering and instant barge-in flush
 * - AudioFocus + Headset/Bluetooth/Speaker automatic routing
 * - Safe native resource release inside capture/playback coroutine `finally` blocks so `stop()` never races active `read`/`write`
 */
class LiveDuplexAudioEngine(
    context: Context,
    private val onInputPcm16: (ByteArray) -> Unit,
    private val onAudioFocusPreempted: (preempted: Boolean) -> Unit,
) : LiveAudioController {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val _inputLevel = MutableStateFlow(0f)
    override val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    override val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val playbackQueue = PlaybackChunkQueue()

    private var engineScope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var record: AudioRecord? = null
    private var trackHandle: TrackHandle? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphone: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (running.get()) updateCommunicationRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (running.get()) updateCommunicationRoute()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> onAudioFocusPreempted(true)
            AudioManager.AUDIOFOCUS_GAIN -> onAudioFocusPreempted(false)
        }
    }

    override fun isSystemInCallMode(): Boolean = runCatching {
        val mode = audioManager.mode
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }.getOrDefault(false)

    override fun setMuted(value: Boolean) {
        muted.set(value)
        if (value) _inputLevel.value = 0f
    }

    @Synchronized
    override fun start(initialMuted: Boolean): Boolean {
        if (running.get()) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (isSystemInCallMode()) {
            return false
        }
        muted.set(initialMuted)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val existingReq = focusRequest
        val req = existingReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        val focusRes = audioManager.requestAudioFocus(req)
        if (focusRes != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (existingReq == null) {
                runCatching { audioManager.abandonAudioFocusRequest(req) }
            }
            return false
        }
        focusRequest = req

        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Log.w(TAG, "Failed to enter MODE_IN_COMMUNICATION", it) }
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        updateCommunicationRoute()

        val minRecBuf = AudioRecord.getMinBufferSize(
            GeminiLiveProtocol.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minRecBuf <= 0) {
            cleanupAudioSystem(abandonFocus = true)
            return false
        }

        val frameShorts = GeminiLiveProtocol.INPUT_SAMPLE_RATE / 25 // 40ms = 640 samples = 1280 bytes
        val rec = createVoiceCommunicationRecord(minRecBuf, frameShorts)
        if (rec == null) {
            cleanupAudioSystem(abandonFocus = true)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            cleanupAudioSystem(abandonFocus = true)
            return false
        }

        val effects = attachAudioEffects(rec.audioSessionId)
        aec = effects.first
        ns = effects.second
        agc = effects.third

        val minPlayBuf = AudioTrack.getMinBufferSize(
            GeminiLiveProtocol.OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minPlayBuf <= 0) {
            releaseEffects(effects)
            clearEffectsRefs()
            runCatching { rec.release() }
            cleanupAudioSystem(abandonFocus = true)
            return false
        }

        val outFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(GeminiLiveProtocol.OUTPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val trk = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(outFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minPlayBuf * 2, 9_600))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Throwable) {
            releaseEffects(effects)
            clearEffectsRefs()
            runCatching { rec.release() }
            cleanupAudioSystem(abandonFocus = true)
            return false
        }

        try {
            rec.startRecording()
            trk.play()
        } catch (_: Throwable) {
            releaseEffects(effects)
            clearEffectsRefs()
            runCatching { rec.release() }
            runCatching { trk.release() }
            cleanupAudioSystem(abandonFocus = true)
            return false
        }

        val handle = TrackHandle(trk)
        record = rec
        trackHandle = handle
        playbackQueue.clear()
        running.set(true)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engineScope = scope
        captureJob = scope.launch { captureLoop(rec, effects, frameShorts) }
        playbackJob = scope.launch { playbackLoop(handle, scope) }
        return true
    }

    override fun enqueueOutputPcm24k(pcm24kLe: ByteArray) {
        if (!running.get() || pcm24kLe.isEmpty()) return
        playbackQueue.offer(pcm24kLe)
    }

    override fun clearPlayback() {
        playbackQueue.clear()
        _speaking.value = false
        _outputLevel.value = 0f
        val handle = trackHandle ?: return
        synchronized(handle.stateLock) {
            if (handle.released || !running.get()) return
            runCatching {
                handle.track.pause()
                handle.track.flush()
                if (running.get()) handle.track.play()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createVoiceCommunicationRecord(minRecBuf: Int, frameShorts: Int): AudioRecord? = try {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            GeminiLiveProtocol.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minRecBuf * 2, frameShorts * 2 * 6),
        )
    } catch (_: Throwable) {
        null
    }

    @Synchronized
    override fun pauseForPreemption() {
        stopInternal(abandonFocus = false)
    }

    @Synchronized
    override fun stop() {
        stopInternal(abandonFocus = true)
    }

    private fun stopInternal(abandonFocus: Boolean) {
        if (!running.compareAndSet(true, false)) {
            if (abandonFocus) {
                focusRequest?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
                focusRequest = null
            }
            return
        }
        playbackQueue.clear()
        _inputLevel.value = 0f
        _outputLevel.value = 0f
        _speaking.value = false

        val rec = record
        val handle = trackHandle
        record = null
        trackHandle = null
        clearEffectsRefs()

        // Unblock captureLoop's AudioRecord.read() and playbackLoop's AudioTrack.write();
        // native release() happens exclusively inside each loop's finally block after read/write exits.
        runCatching { rec?.stop() }
        if (handle != null) {
            synchronized(handle.stateLock) {
                if (!handle.released) {
                    runCatching {
                        handle.track.pause()
                        handle.track.flush()
                        handle.track.stop()
                    }
                }
            }
        }

        engineScope?.cancel()
        engineScope = null
        captureJob = null
        playbackJob = null

        cleanupAudioSystem(abandonFocus = abandonFocus)
    }

    private fun captureLoop(
        rec: AudioRecord,
        effects: Triple<AcousticEchoCanceler?, NoiseSuppressor?, AutomaticGainControl?>,
        frameShorts: Int,
    ) {
        val shorts = ShortArray(frameShorts)
        try {
            while (running.get()) {
                val read = runCatching { rec.read(shorts, 0, frameShorts) }.getOrDefault(-1)
                if (read <= 0) break
                if (muted.get()) {
                    _inputLevel.value = 0f
                    continue
                }
                var sumSq = 0.0
                val bytes = ByteArray(read * 2)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) {
                    val s = shorts[i]
                    sumSq += s.toDouble() * s.toDouble()
                    bb.putShort(s)
                }
                _inputLevel.value = (sqrt(sumSq / read) / 7500.0).coerceIn(0.0, 1.0).toFloat()
                onInputPcm16(bytes)
            }
        } finally {
            _inputLevel.value = 0f
            releaseEffects(effects)
            runCatching { rec.release() }
        }
    }

    private suspend fun playbackLoop(handle: TrackHandle, loopScope: CoroutineScope) {
        val prebufferBytes = 2_880
        var buffering = true
        var idleCycles = 0

        try {
            while (running.get() && loopScope.isActive) {
                if (buffering) {
                    if (playbackQueue.queuedBytes >= prebufferBytes) {
                        buffering = false
                    } else if (playbackQueue.hasChunks) {
                        delay(15)
                        if (playbackQueue.queuedBytes > 0) buffering = false
                        continue
                    } else {
                        if (idleCycles < 8) idleCycles++
                        if (idleCycles >= 6) {
                            _speaking.value = false
                            _outputLevel.value = 0f
                        }
                        delay(12)
                        continue
                    }
                }

                val polled = playbackQueue.poll()
                if (polled == null) {
                    buffering = true
                    idleCycles = 0
                    continue
                }
                val chunk = polled.bytes
                val genBefore = polled.generation
                idleCycles = 0
                _speaking.value = true
                _outputLevel.value = computePcm16LeLevel(chunk)

                var offset = 0
                val sliceBytes = 960
                while (offset < chunk.size && running.get() && playbackQueue.currentGeneration == genBefore) {
                    val len = minOf(sliceBytes, chunk.size - offset)
                    // Do NOT hold stateLock across blocking AudioTrack.write so main-thread clearPlayback()/stop() never stall.
                    val written = runCatching {
                        handle.track.write(chunk, offset, len, AudioTrack.WRITE_BLOCKING)
                    }.getOrDefault(-1)
                    if (written <= 0) break
                    offset += written
                }
            }
        } finally {
            _speaking.value = false
            _outputLevel.value = 0f
            synchronized(handle.stateLock) {
                if (!handle.released) {
                    handle.released = true
                    runCatching { handle.track.release() }
                }
            }
        }
    }

    private fun computePcm16LeLevel(bytes: ByteArray): Float {
        val samples = bytes.size / 2
        if (samples <= 0) return 0f
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        for (i in 0 until samples) {
            val s = bb.short.toDouble()
            sumSq += s * s
        }
        return (sqrt(sumSq / samples) / 8500.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun attachAudioEffects(sessionId: Int): Triple<AcousticEchoCanceler?, NoiseSuppressor?, AutomaticGainControl?> {
        val createdAec = runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } else null
        }.getOrNull()
        val createdNs = runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.apply { enabled = true } else null
        }.getOrNull()
        val createdAgc = runCatching {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.apply { enabled = true } else null
        }.getOrNull()
        return Triple(createdAec, createdNs, createdAgc)
    }

    private fun releaseEffects(effects: Triple<AcousticEchoCanceler?, NoiseSuppressor?, AutomaticGainControl?>) {
        runCatching { effects.first?.release() }
        runCatching { effects.second?.release() }
        runCatching { effects.third?.release() }
    }

    private fun clearEffectsRefs() {
        aec = null
        ns = null
        agc = null
    }

    private fun updateCommunicationRoute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = audioManager.availableCommunicationDevices
                val externalHeadset = commDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                val target = externalHeadset ?: commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (target != null) {
                    audioManager.setCommunicationDevice(target)
                }
            } else {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val hasBtSco = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val hasExternal = hasBtSco || devices.any {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                @Suppress("DEPRECATION")
                if (hasBtSco) {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } else {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.isSpeakerphoneOn = !hasExternal
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed to update communication route", t)
        }
    }

    private fun cleanupAudioSystem(abandonFocus: Boolean) {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.isSpeakerphoneOn = previousSpeakerphone
            }
        }
        if (!isSystemInCallMode()) {
            runCatching { audioManager.mode = previousAudioMode }
        }
        if (abandonFocus) {
            focusRequest?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
            focusRequest = null
        }
    }

    private class TrackHandle(val track: AudioTrack) {
        val stateLock = Any()
        var released: Boolean = false
    }

    companion object {
        private const val TAG = "LiveAudioEngine"
        /** Cap buffered 24kHz PCM16 audio to ~30s (1.44 MB) so a stalled output device can never OOM. */
        internal const val MAX_QUEUED_PLAYBACK_BYTES = GeminiLiveProtocol.OUTPUT_SAMPLE_RATE * 2 * 30
    }
}

/**
 * Thread-safe bounded PCM playback queue whose byte counter and barge-in generation
 * transition atomically, preventing negative `queuedBytes` stalls when `clear()` races `poll()`.
 */
internal class PlaybackChunkQueue(
    private val maxBytes: Int = LiveDuplexAudioEngine.MAX_QUEUED_PLAYBACK_BYTES,
) {
    class PolledChunk(val bytes: ByteArray, val generation: Int)

    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var bytesQueued: Int = 0
    private var generation: Int = 0

    val currentGeneration: Int
        get() = synchronized(lock) { generation }

    val queuedBytes: Int
        get() = synchronized(lock) { bytesQueued }

    val hasChunks: Boolean
        get() = synchronized(lock) { queue.isNotEmpty() }

    fun offer(pcm: ByteArray): Boolean {
        val evenSize = pcm.size and 1.inv()
        if (evenSize <= 0) return false
        val safeCap = maxOf(2, maxBytes and 1.inv())
        val clipped = when {
            evenSize > safeCap -> pcm.copyOfRange(evenSize - safeCap, evenSize)
            evenSize < pcm.size -> pcm.copyOfRange(0, evenSize)
            else -> pcm
        }
        synchronized(lock) {
            while (queue.isNotEmpty() && bytesQueued + clipped.size > safeCap) {
                val dropped = queue.removeFirst()
                bytesQueued = (bytesQueued - dropped.size).coerceAtLeast(0)
            }
            queue.addLast(clipped)
            bytesQueued += clipped.size
        }
        return true
    }

    fun poll(): PolledChunk? = synchronized(lock) {
        val head = queue.pollFirst() ?: return null
        bytesQueued = (bytesQueued - head.size).coerceAtLeast(0)
        if (queue.isEmpty()) bytesQueued = 0
        PolledChunk(head, generation)
    }

    fun clear(): Int = synchronized(lock) {
        generation++
        queue.clear()
        bytesQueued = 0
        generation
    }
}
