package com.wangzan101.mika.voice.command

import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.StreamingRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A separate instance and a hard sample limit; normal hold-to-talk keeps its own defaults. */
internal class CommandRecorder(streaming: Boolean, private val gate: MicAvailabilityGate) : StreamingRecorder {
    private val recorder = PcmRecorder(maxMs = 5_000, streaming = streaming, unbounded = false)
    private val stopped = CompletableDeferred<Unit>()
    override val chunks get() = recorder.chunks
    override val level get() = recorder.level

    override fun start(): Boolean {
        if (!gate.idle()) return false
        val started = recorder.start()
        if (started) gate.identifyOwn(recorder.audioSessionId) else stopped.complete(Unit)
        return started
    }

    override fun stop(): ShortArray = try { recorder.stop() } finally { stopped.complete(Unit) }
    override fun cancel() { try { recorder.cancel() } finally { stopped.complete(Unit) } }

    suspend fun close() {
        withContext(Dispatchers.IO) { cancel() }
        stopped.await()
    }
}
