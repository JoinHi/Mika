package com.wangzan101.mika.ui

import android.Manifest
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.voice.command.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run with -PdevInstall. Local capture tests never send audio to a recognition service. */
@RunWith(AndroidJUnit4::class)
class VoiceCommandDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun grantMic() {
        val inst = InstrumentationRegistry.getInstrumentation()
        // Never use the user's normal app preferences or permissions for these device checks.
        assumeTrue(inst.targetContext.packageName.endsWith(".dev"))
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test fun commandBannerCancelsWithoutActivatingLiveAndBlocksUnderlyingClicks() {
        val input = Channel<SpeechEvent>(Channel.UNLIMITED)
        var closed = false
        var started = false
        var underClicked = false
        lateinit var controller: VoiceCommandController
        compose.runOnUiThread {
            controller = VoiceCommandController(compose.activity.lifecycleScope, { true }, {
                object : CommandCapture {
                    override val events = input.receiveAsFlow()
                    override fun release() = Unit
                    override suspend fun close(awaitMicIdle: Boolean): Boolean { closed = true; return true }
                }
            }, { started = true })
        }
        compose.setContent {
            MikaTheme {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.TextButton(onClick = { underClicked = true }) {
                        androidx.compose.material3.Text("底层按钮")
                    }
                    VoiceCommandBanner(controller, { controller.cancel() })
                }
            }
        }
        compose.runOnIdle { controller.start() }
        compose.onNodeWithText("正在听口令 · 最多 5 秒").assertIsDisplayed()
        compose.onNodeWithTag("voice-command-listening").performClick()
        assertFalse(underClicked)
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(5_000) { closed }
        input.trySend(SpeechEvent.Final("开启语音模式"))
        compose.waitForIdle()
        compose.onNodeWithTag("voice-command-listening").assertDoesNotExist()
        assertFalse(started)
        assertFalse(underClicked)
    }

    @Test fun preparationPrefersConfiguredAsrAndOtherwiseUsesSystemWithoutRecording() {
        val app = compose.activity.application as MikaApp
        val original = app.speech.choice.value
        val audio = compose.activity.getSystemService(AudioManager::class.java)
        val before = audio.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet()
        try {
            compose.runOnIdle {
                val source = CommandSpeechSource(app)
                app.speech.set(SpeechChoice.FunAsr("ws://127.0.0.1:9"))
                assertEquals(CommandBackend.Asr, source.prepare()?.backend)
                app.speech.set(SpeechChoice.System)
                val fallback = source.prepare()
                if (SystemSpeech.capability(app, app.speech.systemSilent.value).usable) assertEquals(CommandBackend.System, fallback?.backend)
                else assertNull(fallback)
                app.speech.set(SpeechChoice.Doubao("invalid-resource"))
                val incompleteAsr = source.prepare()
                if (SystemSpeech.capability(app, app.speech.systemSilent.value).usable) assertEquals(CommandBackend.System, incompleteAsr?.backend)
                else assertNull(incompleteAsr)
            }
            assertEquals(before, audio.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet())
        } finally {
            compose.runOnIdle { app.speech.set(original) }
        }
    }

    @Test fun existingRecordingBlocksCommandBeforeItStarts() {
        val gate = MicAvailabilityGate(compose.activity)
        assumeTrue("Another app is recording; leave it alone", gate.idle())
        val recorder = PcmRecorder(maxMs = 5_000)
        try {
            compose.runOnIdle { assertTrue(recorder.start()) }
            compose.waitUntil(3_000) { !gate.idle() }
            compose.runOnIdle { assertFalse(gate.watch(compose.activity.lifecycleScope) { fail("must not start watching") }) }
        } finally {
            compose.runOnIdle { gate.close(); recorder.cancel() }
        }
    }

    @Test fun ownCaptureIsExcludedAndAnAdditionalCaptureCancelsTheCommand() {
        val audio = compose.activity.getSystemService(AudioManager::class.java)
        val gate = MicAvailabilityGate(compose.activity)
        assumeTrue("Another app is recording; leave it alone", gate.idle())
        val conflict = AtomicBoolean(false)
        val own = CommandRecorder(streaming = false, gate)
        val other = PcmRecorder(maxMs = 5_000)
        try {
            compose.runOnIdle {
                assertTrue(gate.watch(compose.activity.lifecycleScope) { conflict.set(true) })
                assertTrue(own.start())
            }
            compose.waitUntil(3_000) { audio.activeRecordingConfigurations.isNotEmpty() }
            // Let the recording callback and the mode poll both observe our own session.
            Thread.sleep(250)
            assertFalse(conflict.get())
            compose.runOnIdle { assertTrue(other.start()) }
            compose.waitUntil(3_000) { conflict.get() }
            assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        } finally {
            compose.runOnIdle { gate.close(); own.cancel(); other.cancel() }
        }
    }
}
