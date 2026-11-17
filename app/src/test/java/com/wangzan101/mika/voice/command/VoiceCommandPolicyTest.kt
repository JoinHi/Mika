package com.wangzan101.mika.voice.command

import androidx.compose.ui.geometry.Rect
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test

class VoiceCommandPolicyTest {
    @Test fun onlyTheWholeCommandMatches() {
        for (text in listOf("开启语音模式", " 开启 语音模式。\n", "开启语音模式！")) assertTrue(text, VoiceCommandMatcher.matches(text))
        for (text in listOf("", "不要开启语音模式", "怎么开启语音模式", "开启语音模式吗？", "开启语音模式帮我发消息", "他说开启语音模式")) {
            assertFalse(text, VoiceCommandMatcher.matches(text))
        }
    }

    @Test fun otherRecordingsAreConflictsEvenWhenTheyAreSilenced() {
        val own = CommandRecording(21, 1, false)
        val policy = CommandMicPolicy().apply { ownSessionId = 21 }
        assertTrue(policy.accepts(emptyList()))
        assertTrue(policy.accepts(listOf(own)))
        assertFalse(policy.accepts(listOf(own.copy(silenced = true))))
        assertFalse(policy.accepts(listOf(own, CommandRecording(22, 1, true))))
        assertFalse(policy.accepts(listOf(CommandRecording(22, 1, false))))
        assertFalse(policy.accepts(listOf(CommandRecording(0, 1, false))))
    }

    @Test fun systemRecognitionAdmitsOnlyOneInitialOrdinaryCapture() {
        val policy = CommandMicPolicy().apply { expectingSystemCapture = true }
        val own = CommandRecording(31, 6, false)
        assertTrue(policy.accepts(listOf(own)))
        assertFalse(policy.accepts(listOf(own, CommandRecording(32, 1, false))))
        assertFalse(policy.accepts(listOf(CommandRecording(33, 1, false))))
        val simultaneous = CommandMicPolicy().apply { expectingSystemCapture = true }
        assertFalse(simultaneous.accepts(listOf(own, CommandRecording(32, 1, false))))
        val call = CommandMicPolicy().apply { expectingSystemCapture = true }
        assertFalse(call.accepts(listOf(CommandRecording(34, 7, false))))
    }

    @Test fun launchesExcludeHistorySharingAndInternalNotificationTargets() {
        assertTrue(CommandLaunchPolicy.accepts("android.intent.action.MAIN", true, false, false))
        assertFalse(CommandLaunchPolicy.accepts("android.intent.action.SEND", true, false, false))
        assertFalse(CommandLaunchPolicy.accepts("android.intent.action.MAIN", true, true, false))
        assertFalse(CommandLaunchPolicy.accepts("android.intent.action.MAIN", true, false, true))
        assertFalse(CommandLaunchPolicy.accepts("android.intent.action.MAIN", false, false, false))
        assertFalse(CommandLaunchPolicy.hasInternalTarget(emptySet()))
        assertFalse(CommandLaunchPolicy.hasInternalTarget(setOf("unrelated")))
        for (extra in listOf(
            MainActivity.EXTRA_OPEN_CONVERSATION,
            MainActivity.EXTRA_OPEN_CALLS,
            MainActivity.EXTRA_CALL_HISTORY_ID,
            MainActivity.EXTRA_OPEN_TEACH,
        )) {
            assertTrue(extra, CommandLaunchPolicy.hasInternalTarget(setOf(extra)))
        }
    }

    @Test fun bannerBoundsHitTestingDistinguishesInsideFromOutsideTouches() {
        val bounds = Rect(left = 16f, top = 40f, right = 344f, bottom = 104f)
        assertFalse(CommandLaunchPolicy.isInsideBanner(null, 100f, 60f))
        assertTrue(CommandLaunchPolicy.isInsideBanner(bounds, 100f, 60f))
        assertTrue(CommandLaunchPolicy.isInsideBanner(bounds, 16f, 40f))
        assertFalse(CommandLaunchPolicy.isInsideBanner(bounds, 15f, 60f))
        assertFalse(CommandLaunchPolicy.isInsideBanner(bounds, 100f, 120f))
    }

    @Test fun listeningIsOptInAndDoesNotChangeSpeechSettings() {
        val prefs = MemoryPrefs()
        val settings = VoiceCommandSettings(prefs)
        assertFalse(settings.enabled)
        settings.enabled = true
        assertTrue(VoiceCommandSettings(prefs).enabled)
        settings.enabled = false
        assertFalse(VoiceCommandSettings(prefs).enabled)
        assertFalse(prefs.contains("speech_choice"))
    }
}
