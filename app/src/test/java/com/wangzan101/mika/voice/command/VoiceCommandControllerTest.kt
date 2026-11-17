package com.wangzan101.mika.voice.command

import com.wangzan101.mika.voice.SpeechEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceCommandControllerTest {
    private class Capture : CommandCapture {
        val input = Channel<SpeechEvent>(Channel.UNLIMITED)
        override val events = input.receiveAsFlow()
        var releases = 0
        var closes = 0
        val closeAwaitIdleCalls = mutableListOf<Boolean>()
        var quiet = true
        override var resultWaitMs = 3_000L
        var closeGate: CompletableDeferred<Unit>? = null
        var beforeClose: () -> Unit = {}
        override fun release() { releases++ }
        override suspend fun close(awaitMicIdle: Boolean): Boolean {
            closeAwaitIdleCalls += awaitMicIdle
            beforeClose()
            closeGate?.await()
            closes++
            return quiet
        }
    }

    @Test fun missingPrerequisitesDoNotOpenCapture() = runTest {
        val ready = CommandPrerequisites(true, true, false, true, true, true, false)
        val blocked = listOf(
            ready.copy(enabled = false), ready.copy(liveConfigured = false), ready.copy(liveRunning = true),
            ready.copy(budgetAvailable = false), ready.copy(recordPermission = false),
            ready.copy(unlocked = false), ready.copy(callBusy = true),
        )
        var opened = 0
        for (state in blocked) {
            VoiceCommandController(this, { state.allowed }, { opened++; Capture() }, { fail("started") }).start()
        }
        advanceUntilIdle()
        assertEquals(0, opened)
        assertTrue(ready.allowed)
    }

    @Test fun matchWaitsForReleaseAndRechecksLiveConfiguration() = runTest {
        val capture = Capture().apply { closeGate = CompletableDeferred() }
        var configured = true
        var started = 0
        val controller = VoiceCommandController(this, { configured }, { capture }, { started++ })
        controller.start()
        runCurrent()
        capture.input.send(SpeechEvent.Final("开启语音模式。"))
        runCurrent()
        assertEquals(0, started)
        configured = false // Model removed while an asynchronous cleanup/result was pending.
        capture.closeGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, capture.closes)
        assertEquals(0, started)
        assertEquals(VoiceCommandPhase.Idle, controller.phase.value)
    }

    @Test fun captureStopsAtFiveSecondsThenFinalStartsLiveOnce() = runTest {
        val capture = Capture()
        var started = 0
        val controller = VoiceCommandController(this, { true }, { capture }, {
            assertEquals(1, capture.closes)
            started++
        })
        controller.start()
        runCurrent()
        advanceTimeBy(4_999)
        assertEquals(0, capture.releases)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, capture.releases)
        assertEquals(VoiceCommandPhase.Recognizing, controller.phase.value)
        capture.input.send(SpeechEvent.Final("开启语音模式"))
        advanceUntilIdle()
        assertEquals(1, started)
        advanceTimeBy(10_000)
        assertEquals(1, started) // The old five-second timer cannot stop or restart Live.
    }

    @Test fun cancellationAndMicConflictIgnoreLateResults() = runTest {
        for (conflict in listOf(false, true)) {
            val capture = Capture()
            var conflictCallback: () -> Unit = {}
            var started = 0
            val controller = VoiceCommandController(this, { true }, { callback ->
                conflictCallback = callback
                capture
            }, { started++ })
            controller.start()
            runCurrent()
            if (conflict) conflictCallback() else controller.cancel()
            capture.input.send(SpeechEvent.Final("开启语音模式"))
            advanceUntilIdle()
            assertEquals(1, capture.closes)
            assertEquals(0, started)
        }
    }

    @Test fun aNewRequestWaitsForThePreviousMicrophoneToClose() = runTest {
        val first = Capture().apply { closeGate = CompletableDeferred() }
        val second = Capture()
        var opens = 0
        val controller = VoiceCommandController(this, { true }, { if (++opens == 1) first else second }, {})
        controller.start()
        runCurrent()
        controller.start()
        runCurrent()
        assertEquals(1, opens)
        first.closeGate!!.complete(Unit)
        runCurrent()
        assertEquals(2, opens)
        controller.cancel()
        advanceUntilIdle()
        assertEquals(1, second.closes)
    }

    @Test fun noResultOrMicrophoneStillBusyNeverStartsLive() = runTest {
        for (final in listOf(false, true)) {
            val capture = Capture().apply { quiet = false }
            val controller = VoiceCommandController(this, { true }, { capture }, { fail("started") })
            controller.start()
            runCurrent()
            if (final) capture.input.send(SpeechEvent.Final("开启语音模式"))
            advanceUntilIdle()
            assertEquals(1, capture.closes)
            assertEquals(VoiceCommandPhase.Idle, controller.phase.value)
        }
    }

    @Test fun intermediateResultsAndOrdinarySpeechDoNotActivate() = runTest {
        val capture = Capture()
        val controller = VoiceCommandController(this, { true }, { capture }, { fail("started") })
        controller.start()
        runCurrent()
        capture.input.send(SpeechEvent.Partial("开启语音模式", ""))
        runCurrent()
        assertEquals(0, capture.closes)
        capture.input.send(SpeechEvent.Final("不要开启语音模式"))
        advanceUntilIdle()
        assertEquals(1, capture.closes)
        assertEquals(VoiceCommandFeedback.NotMatched, controller.feedback.value)
        assertEquals("不要开启语音模式", controller.heardText.value)
    }

    @Test fun remoteTranscriptionCanReplyAfterThreeSecondsWithoutKeepingTheMicOpen() = runTest {
        val capture = Capture().apply { resultWaitMs = 31_000 }
        var started = false
        val controller = VoiceCommandController(this, { true }, { capture }, { started = true })
        controller.start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, capture.releases)
        advanceTimeBy(8_000) // The previous eight-second total deadline used to discard this result.
        runCurrent()
        assertEquals(0, capture.closes)
        capture.input.send(SpeechEvent.Final("开启语音模式。"))
        advanceUntilIdle()
        assertTrue(started)
        assertEquals(1, capture.releases)
    }

    @Test fun recognitionFailuresAndTimeoutsAreVisible() = runTest {
        val failed = Capture()
        val first = VoiceCommandController(this, { true }, { failed }, { fail("started") })
        first.start()
        runCurrent()
        failed.input.send(SpeechEvent.Failed(com.wangzan101.mika.voice.SpeechFailure.Network))
        advanceUntilIdle()
        assertEquals(VoiceCommandFeedback.Failed(com.wangzan101.mika.voice.SpeechFailure.Network), first.feedback.value)

        val stalled = Capture()
        val second = VoiceCommandController(this, { true }, { stalled }, { fail("started") })
        second.start()
        advanceUntilIdle()
        assertEquals(VoiceCommandFeedback.TimedOut, second.feedback.value)
        assertEquals(1, stalled.releases)
        assertEquals(1, stalled.closes)
        assertEquals(listOf(false), failed.closeAwaitIdleCalls)
        assertEquals(listOf(false), stalled.closeAwaitIdleCalls)
    }

    @Test fun onlyMatchedCommandsWaitForSystemMicIdle() = runTest {
        val unmatched = Capture()
        val first = VoiceCommandController(this, { true }, { unmatched }, { fail("started") })
        first.start()
        runCurrent()
        unmatched.input.send(SpeechEvent.Final("随便说一句"))
        advanceUntilIdle()
        assertEquals(listOf(false), unmatched.closeAwaitIdleCalls)

        val cancelled = Capture()
        val second = VoiceCommandController(this, { true }, { cancelled }, { fail("started") })
        second.start()
        runCurrent()
        second.cancel(VoiceCommandFeedback.MicrophoneBusy)
        advanceUntilIdle()
        assertEquals(listOf(false), cancelled.closeAwaitIdleCalls)

        val matched = Capture()
        var started = 0
        val third = VoiceCommandController(this, { true }, { matched }, { started++ })
        third.start()
        runCurrent()
        matched.input.send(SpeechEvent.Final("开启语音模式"))
        advanceUntilIdle()
        assertEquals(1, started)
        assertEquals(listOf(true), matched.closeAwaitIdleCalls)
    }
}
