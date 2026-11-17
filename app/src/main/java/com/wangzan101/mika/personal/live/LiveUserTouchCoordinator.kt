package com.wangzan101.mika.personal.live

import android.view.MotionEvent
import com.wangzan101.mika.core.live.LiveAsyncEvent
import com.wangzan101.mika.core.live.LiveTurnCoordinator
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 语音专属虚拟大屏（[com.wangzan101.mika.ui.PersonalLiveScreenStage]）机主手动触控协同器：
 * - 机主点按/滑动虚拟大屏上的 App 时**绝不中断语音模式**；
 * - 手指按压期间标记 [isUserTouching] 为 true，延后空闲释放与话轮抢答；
 * - 手指抬起后经 [SETTLE_DEBOUNCE_MS] 连续手势合并防抖（多次滑动/点按合并为一次状态同步），
 *   等待新界面稳定后采集轻量无障碍控件摘要，默认以**静默上下文同步（`speakAloud = false`）**投递给 [LiveTurnCoordinator]，
 *   既让语音模型实时掌握最新界面上下文，又不主动插话打断机主、也不堆积冗余截图 token。
 */
internal class LiveUserTouchCoordinator(
    private val scope: CoroutineScope,
    private val turnCoordinator: LiveTurnCoordinator,
    private val onUserActivity: () -> Unit,
    private val debounceMs: Long = SETTLE_DEBOUNCE_MS,
) {
    data class CapturedGuidanceState(
        val appLabel: String?,
        val screenSummary: String,
        val jpegFrame: ByteArray?,
    )

    private val touching = AtomicBoolean(false)
    private val burstTouchCount = AtomicInteger(0)
    private val lock = Any()
    private var settleJob: Job? = null
    private var pendingResumeHook: (() -> Unit)? = null
    private var lastSyncedSignature: String? = null

    val isUserTouching: Boolean
        get() = touching.get()

    fun onMotionAction(
        maskedAction: Int,
        pauseDuringGesture: (() -> Unit)? = null,
        resumeAfterGesture: (() -> Unit)? = null,
        captureAfterSettle: suspend () -> CapturedGuidanceState?,
    ) {
        onUserActivity()
        when (maskedAction) {
            MotionEvent.ACTION_DOWN -> {
                touching.set(true)
                burstTouchCount.incrementAndGet()
                synchronized(lock) {
                    settleJob?.cancel()
                    settleJob = null
                    if (resumeAfterGesture != null) pendingResumeHook = resumeAfterGesture
                }
                pauseDuringGesture?.invoke()
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                touching.set(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching.set(false)
                scheduleSettle(maskedAction == MotionEvent.ACTION_CANCEL, resumeAfterGesture, captureAfterSettle)
            }
        }
    }

    /** 兼容旧版降级单次点击/滑动或顶栏返回键。 */
    fun onDiscreteAction(
        pauseDuringGesture: (() -> Unit)? = null,
        resumeAfterGesture: (() -> Unit)? = null,
        captureAfterSettle: suspend () -> CapturedGuidanceState?,
    ) {
        onUserActivity()
        touching.set(false)
        burstTouchCount.incrementAndGet()
        pauseDuringGesture?.invoke()
        scheduleSettle(wasCancel = false, resumeAfterGesture = resumeAfterGesture, captureAfterSettle = captureAfterSettle)
    }

    private fun scheduleSettle(
        wasCancel: Boolean,
        resumeAfterGesture: (() -> Unit)?,
        captureAfterSettle: suspend () -> CapturedGuidanceState?,
    ) {
        synchronized(lock) {
            settleJob?.cancel()
            if (resumeAfterGesture != null) pendingResumeHook = resumeAfterGesture
            if (wasCancel) {
                val resume = pendingResumeHook
                pendingResumeHook = null
                burstTouchCount.set(0)
                resume?.invoke()
                return
            }
            settleJob = scope.launch {
                try {
                    delay(debounceMs)
                    if (touching.get()) return@launch
                    val count = burstTouchCount.getAndSet(0).coerceAtLeast(1)
                    val captured = try {
                        captureAfterSettle()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (captured != null && (captured.screenSummary.isNotBlank() || captured.jpegFrame != null)) {
                        val sig = "${captured.appLabel.orEmpty()}|${captured.screenSummary}"
                        val shouldEmit = synchronized(lock) {
                            if (captured.screenSummary.isNotBlank() && sig == lastSyncedSignature) {
                                false
                            } else {
                                lastSyncedSignature = sig
                                true
                            }
                        }
                        if (shouldEmit) {
                            turnCoordinator.enqueue(
                                LiveAsyncEvent.UserTouchGuidance(
                                    appLabel = captured.appLabel,
                                    screenSummary = captured.screenSummary,
                                    jpegFrame = captured.jpegFrame,
                                    touchCount = count,
                                    speakAloud = false,
                                ),
                            )
                        }
                    }
                } finally {
                    if (!touching.get()) {
                        val resume = synchronized(lock) {
                            val hook = pendingResumeHook
                            pendingResumeHook = null
                            hook
                        }
                        resume?.invoke()
                    }
                }
            }
        }
    }

    fun clear() {
        touching.set(false)
        burstTouchCount.set(0)
        val resume = synchronized(lock) {
            settleJob?.cancel()
            settleJob = null
            lastSyncedSignature = null
            val hook = pendingResumeHook
            pendingResumeHook = null
            hook
        }
        resume?.invoke()
    }

    companion object {
        const val SETTLE_DEBOUNCE_MS = 1_200L

        internal fun summarizeSnapshot(snapshot: ScreenSnapshot?): String {
            if (snapshot == null) return ""
            val texts = ArrayList<String>(24)
            val buttons = ArrayList<String>(16)
            for (el in snapshot.elements) {
                if (el.secret) continue
                val name = el.name.trim()
                if (name.isEmpty()) continue
                if (el.clickable || el.editable) {
                    if (buttons.size < 16 && name !in buttons) {
                        buttons += if (el.editable) "[输入框] $name" else "[可点] $name"
                    }
                } else {
                    if (texts.size < 20 && name !in texts) {
                        texts += name
                    }
                }
            }
            return buildString {
                snapshot.packageName?.takeIf { it.isNotBlank() }?.let { appendLine("包名: $it") }
                if (texts.isNotEmpty()) {
                    appendLine("页面主要文字: ${texts.joinToString(" | ").take(900)}")
                }
                if (buttons.isNotEmpty()) {
                    append("可见交互项: ${buttons.joinToString("、").take(600)}")
                }
            }.trim()
        }
    }
}
