package com.wangzan101.mika.cap.phone.a11y

/**
 * 手机操控的界面等待与输入节拍时间参数。
 *
 * 只管时间窗口与轨迹分段节拍,不携带意图门禁、审批或对外发言间隔等行为开关:
 * - [STANDARD]: 标准拟人等待窗口与分段打字/手势节奏;
 * - [TURBO]: 语音实时单步直控使用的低延迟等待窗口,跳过人为拟人停顿并收敛无障碍静默上限。
 */
data class PhoneWaitProfile(
    val name: String,
    val turbo: Boolean,
    val instantTyping: Boolean,
    val instantGesture: Boolean,
    val changePollMs: Long,
    val changeQuietMs: Long,
    val changeProbeMs: Long,
    val changeCapMs: Long,
    val screenQuietMs: Long,
    val screenCheckCapMs: Long,
    val secondLookQuietMs: Long,
    val secondLookCapMs: Long,
    val settleCapMs: Long,
    val focusWaitMs: Long,
    val readbackQuietMs: Long,
    val readbackCapMs: Long,
    val pasteWaitMs: Long,
    val popupSettleMs: Long,
    val stillPollMs: Long,
    val stillCapMs: Long,
    val batchSettleMs: Long,
) {
    companion object {
        val STANDARD = PhoneWaitProfile(
            name = "standard",
            turbo = false,
            instantTyping = false,
            instantGesture = false,
            changePollMs = 150L,
            changeQuietMs = 400L,
            changeProbeMs = 550L,
            changeCapMs = 2_500L,
            screenQuietMs = 500L,
            screenCheckCapMs = 1_500L,
            secondLookQuietMs = 300L,
            secondLookCapMs = 800L,
            settleCapMs = 2_000L,
            focusWaitMs = 2_000L,
            readbackQuietMs = 200L,
            readbackCapMs = 800L,
            pasteWaitMs = 1_500L,
            popupSettleMs = 300L,
            stillPollMs = 120L,
            stillCapMs = 1_200L,
            batchSettleMs = 1_500L,
        )

        val TURBO = PhoneWaitProfile(
            name = "turbo",
            turbo = true,
            instantTyping = true,
            instantGesture = true,
            changePollMs = 50L,
            changeQuietMs = 110L,
            changeProbeMs = 400L,
            changeCapMs = 950L,
            screenQuietMs = 110L,
            screenCheckCapMs = 450L,
            secondLookQuietMs = 70L,
            secondLookCapMs = 250L,
            settleCapMs = 600L,
            focusWaitMs = 600L,
            readbackQuietMs = 60L,
            readbackCapMs = 260L,
            pasteWaitMs = 500L,
            popupSettleMs = 100L,
            stillPollMs = 50L,
            stillCapMs = 350L,
            batchSettleMs = 180L,
        )
    }
}
