package com.wangzan101.mika.personal.live

import com.wangzan101.mika.ui.formatCallDurationSeconds
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSubtitleAndSettingsTest {

    @Test
    fun extractActiveClauseSlidesByPunctuationAndPreservesDecimals() {
        assertEquals("", LiveSubtitleFormatter.extractActiveClause("   "))
        assertEquals(
            "好的，正在帮你打开美团",
            LiveSubtitleFormatter.extractActiveClause("好的，正在帮你打开美团。"),
        )
        assertEquals(
            "现在正在搜索瑞幸咖啡！",
            LiveSubtitleFormatter.extractActiveClause("我已经帮你打开了美团。现在正在搜索瑞幸咖啡！"),
        )
        assertEquals(
            "要帮你直接下单吗？",
            LiveSubtitleFormatter.extractActiveClause("现在正在搜索瑞幸咖啡！要帮你直接下单吗？"),
        )
        assertEquals(
            "找到了，这家店评分是 4.8 分",
            LiveSubtitleFormatter.extractActiveClause("找到了，这家店评分是 4.8 分。"),
        )
        assertEquals(
            "距离只有 1.2 公里",
            LiveSubtitleFormatter.extractActiveClause("找到了，这家店评分是 4.8 分，距离只有 1.2 公里。"),
        )
        assertEquals(
            "啊？马上帮你看下",
            LiveSubtitleFormatter.extractActiveClause("啊？马上帮你看下。"),
        )

        val longSentence = "这是一段没有任何标点符号的超长连续语音转写文本用来验证单行字幕尾部滑动窗口裁剪效果"
        val windowed = LiveSubtitleFormatter.extractActiveClause(longSentence, maxUnits = 16)
        assertTrue("Expected ellipsis prefix on sliding tail: $windowed", windowed.startsWith("…"))
        assertTrue(windowed.endsWith("滑动窗口裁剪效果"))
    }

    @Test
    fun personalLiveSettingsDefaultsUnconfiguredAndMigratesLegacyConfig() {
        val dir = Files.createTempDirectory("live-settings-unit").toFile()
        try {
            val freshFile = File(dir, "fresh.json")
            val fresh = PersonalLiveSettings(freshFile)
            assertFalse("Fresh installation must require explicit voice model setup", fresh.state.value.configured)

            fresh.update { it.copy(providerId = "g1", model = "gemini-live-test", voiceName = "Aoede", configured = true) }
            val reloadedConfigured = PersonalLiveSettings(freshFile)
            assertTrue(reloadedConfigured.state.value.configured)
            assertEquals("gemini-live-test", reloadedConfigured.state.value.model)

            reloadedConfigured.update { it.copy(configured = false) }
            assertFalse(PersonalLiveSettings(freshFile).state.value.configured)

            val legacyFile = File(dir, "legacy.json")
            legacyFile.writeText("""{"providerId":"g_legacy","model":"gemini-2.5-live","voiceName":"Puck"}""")
            val migrated = PersonalLiveSettings(legacyFile)
            assertTrue("Existing legacy config without 'configured' key must migrate as configured", migrated.state.value.configured)
            assertEquals("Puck", migrated.state.value.voiceName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun callDurationFormattingHandlesMinutesAndHours() {
        assertEquals("00:00", formatCallDurationSeconds(0L))
        assertEquals("01:28", formatCallDurationSeconds(88L))
        assertEquals("1:01:05", formatCallDurationSeconds(3665L))
    }
}
