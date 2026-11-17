package com.wangzan101.mika.voice.command

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal object CommandLaunchPolicy {
    val internalTargetExtras = setOf(
        MainActivity.EXTRA_OPEN_CONVERSATION,
        MainActivity.EXTRA_OPEN_CALLS,
        MainActivity.EXTRA_CALL_HISTORY_ID,
        MainActivity.EXTRA_OPEN_TEACH,
    )

    fun hasInternalTarget(extraKeys: Set<String>): Boolean = extraKeys.any { it in internalTargetExtras }

    fun accepts(action: String?, launcher: Boolean, internalTarget: Boolean, fromHistory: Boolean): Boolean =
        action == "android.intent.action.MAIN" && launcher && !internalTarget && !fromHistory

    fun isInsideBanner(bounds: Rect?, x: Float, y: Float): Boolean =
        bounds != null && x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
}

/** The Activity only forwards launch/focus/touch events; all command state stays isolated here. */
internal class VoiceCommandEntry(
    private val activity: ComponentActivity,
    private val onStartLive: () -> Unit,
) : DefaultLifecycleObserver {
    private val app get() = activity.application as MikaApp
    private val settings = VoiceCommandSettings.get(activity)
    private val source by lazy { CommandSpeechSource(app) }
    private var pending = false
    private var bannerBounds: Rect? = null
    val controller = VoiceCommandController(
        activity.lifecycleScope,
        eligible = ::eligible,
        open = { onConflict -> source.prepare()?.let {
            Log.d("VoiceCommand", "backend=${it.backend} streaming=${it.streaming}")
            source.open(it, activity.lifecycleScope, onConflict)
        } },
        onMatched = onStartLive,
        diagnostic = { Log.d("VoiceCommand", it) },
    )

    init {
        activity.lifecycle.addObserver(this)
        activity.lifecycleScope.launch {
            controller.phase.collectLatest { phase ->
                if (phase != VoiceCommandPhase.Idle) {
                    while (true) {
                        delay(100)
                        if (!lightweightEligibility()) cancel()
                    }
                }
            }
        }
    }

    fun offer(intent: Intent, fresh: Boolean = true) {
        cancel()
        val extraKeys = intent.extras?.keySet().orEmpty()
        pending = fresh && settings.enabled && CommandLaunchPolicy.accepts(
            intent.action, intent.hasCategory(Intent.CATEGORY_LAUNCHER),
            CommandLaunchPolicy.hasInternalTarget(extraKeys),
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
        )
        tryStart()
    }

    private fun lightweightEligibility(): Boolean = settings.enabled &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus() &&
        !activity.isFinishing && !activity.isDestroyed &&
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        !activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        !app.personal.live.uiState.value.phase.isRunning && app.calls.state.value.phase == CallPhase.IDLE

    private fun eligible(): Boolean {
        if (!settings.enabled || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !activity.hasWindowFocus() || activity.isFinishing || activity.isDestroyed
        ) return false
        val live = app.personal.live
        val credentials = live.settings.resolve(app.providers)
        val budget = credentials?.provider?.dailyTokenBudget
        return CommandPrerequisites(
            enabled = settings.enabled, liveConfigured = credentials != null,
            liveRunning = live.uiState.value.phase.isRunning,
            budgetAvailable = budget == null || app.usage.providerTokensToday(requireNotNull(credentials).provider.id) < budget,
            recordPermission = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            unlocked = !activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            callBusy = app.calls.state.value.phase != CallPhase.IDLE,
        ).allowed
    }

    private fun tryStart() {
        if (!pending || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !activity.hasWindowFocus()) return
        pending = false
        controller.start()
    }

    fun onWindowFocusChanged(focused: Boolean) {
        if (focused) tryStart() else if (controller.phase.value != VoiceCommandPhase.Idle) cancel()
    }

    override fun onResume(owner: LifecycleOwner) { tryStart() }
    override fun onPause(owner: LifecycleOwner) { cancel() }
    override fun onDestroy(owner: LifecycleOwner) { cancel() }

    fun updateBannerBounds(bounds: Rect?) {
        bannerBounds = bounds
    }

    fun isTouchInsideBanner(x: Float, y: Float): Boolean =
        CommandLaunchPolicy.isInsideBanner(bannerBounds, x, y)

    fun cancel() {
        pending = false
        controller.cancel()
    }
}
