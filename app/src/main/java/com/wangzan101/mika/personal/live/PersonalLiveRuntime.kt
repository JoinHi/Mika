package com.wangzan101.mika.personal.live

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.cap.phone.a11y.PhoneWaitProfile
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.root.ROOT_CONFIRM_APP
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.live.GeminiLiveClient
import com.wangzan101.mika.core.live.GeminiLiveEvent
import com.wangzan101.mika.core.live.GeminiLiveProtocol
import com.wangzan101.mika.core.live.GeminiLiveSetup
import com.wangzan101.mika.core.live.LiveAsyncEvent
import com.wangzan101.mika.core.live.LiveDirectDecision
import com.wangzan101.mika.core.live.LiveFunctionCall
import com.wangzan101.mika.core.live.LiveFunctionResponse
import com.wangzan101.mika.core.live.LiveRealtimeSession
import com.wangzan101.mika.core.live.LiveScheduling
import com.wangzan101.mika.core.live.LiveTurnCoordinator
import com.wangzan101.mika.core.live.LiveTurnSignalSnapshot
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.personal.PersonalAgent
import com.wangzan101.mika.core.personal.PersonalSystemException
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.voice.WakeWordService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class LiveSessionPhase {
    Idle,
    Connecting,
    Active,
    Reconnecting,
    SuspendedByCall,
    Error,
    ;

    val isRunning: Boolean
        get() = this == Connecting || this == Active || this == Reconnecting || this == SuspendedByCall

    val keepServiceAlive: Boolean
        get() = isRunning
}

data class PersonalLiveUiState(
    val phase: LiveSessionPhase = LiveSessionPhase.Idle,
    val muted: Boolean = false,
    val speaking: Boolean = false,
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
    val liveUserTranscript: String = "",
    val liveAssistantTranscript: String = "",
    val errorMessage: String? = null,
    val statusHint: String? = null,
    val activeTaskCount: Int = 0,
    val providerConfigured: Boolean = false,
    val selectedModel: String = GeminiLiveProtocol.DEFAULT_MODEL,
    val selectedVoice: String = GeminiLiveProtocol.DEFAULT_VOICE,
) {
    fun toSignalSnapshot(): LiveTurnSignalSnapshot = LiveTurnSignalSnapshot(
        sessionActive = phase == LiveSessionPhase.Active,
        speaking = speaking,
        liveUserTranscript = liveUserTranscript,
        inputLevel = inputLevel,
    )
}

data class RetainedLiveStage(
    val frame: Bitmap,
    val appLabel: String?,
    val packageName: String? = null,
)

sealed interface LiveConfirmationSource {
    data class Direct(val id: Int) : LiveConfirmationSource
    data class Task(val runKey: String, val requestKey: Int, val taskId: String) : LiveConfirmationSource
}

/**
 * 语音模式下的统一界面审批模型（仅能由机主在 Compose 卡片或系统通知亲手点击，模型无任何审批放行工具）：
 * - [LiveConfirmationSource.Direct]：对应 `quick_phone_act` 被拦截的单步动作；
 * - [LiveConfirmationSource.Task]：对应后台 `TaskRun` 进入 `WaitingConfirmation` 的步骤。
 */
data class LiveConfirmationPrompt(
    val source: LiveConfirmationSource,
    val request: ConfirmationRequest,
    val appLabel: String,
) {
    val promptKey: String
        get() = when (source) {
            is LiveConfirmationSource.Direct -> "direct:${source.id}"
            is LiveConfirmationSource.Task -> "task:${source.runKey}:${source.requestKey}"
        }
}

internal class ExecutedToolStep(
    val output: JsonObject,
    val scheduling: LiveScheduling?,
    val deferredJpeg: ByteArray? = null,
)

/**
 * 按连接维度追踪在途工具调用与 `ToolCallCancelled` 取消编号：
 * - 在途调用一旦收到 `ToolCallCancelled`，立即取消对应子协程，停在动作边界；
 * - 同一批次尚未启动的被取消调用直接跳过，不执行任何写动作；
 * - 已取消的调用绝不回传 `toolResponse`，未被取消的同批调用照常完成，已生效的步骤记录完整保留。
 */
internal class LiveSessionToolTracker {
    private val cancelledCallIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val activeCallJobs = ConcurrentHashMap<String, Job>()

    fun isCancelled(callId: String): Boolean = callId in cancelledCallIds

    fun cancelCalls(ids: Collection<String>) {
        for (id in ids) {
            if (id.isNotBlank()) {
                cancelledCallIds += id
                activeCallJobs.remove(id)?.cancel()
            }
        }
    }

    fun clear() {
        cancelledCallIds.clear()
        val jobs = activeCallJobs.values.toList()
        activeCallJobs.clear()
        jobs.forEach { it.cancel() }
    }

    suspend fun runBatch(
        session: LiveRealtimeSession,
        calls: List<LiveFunctionCall>,
        includeNonBlockingFlag: Boolean,
        turnCoordinator: LiveTurnCoordinator,
        onStepCompleted: (LiveFunctionCall, ExecutedToolStep) -> Unit,
        executeCall: suspend (LiveFunctionCall) -> ExecutedToolStep,
    ) {
        val responses = ArrayList<LiveFunctionResponse>(calls.size)
        var latestDeferredJpeg: ByteArray? = null

        supervisorScope {
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                if (call.id in cancelledCallIds) continue

                val deferred = async {
                    executeCall(call)
                }
                activeCallJobs[call.id] = deferred
                deferred.invokeOnCompletion { activeCallJobs.remove(call.id, deferred) }
                if (call.id in cancelledCallIds) {
                    deferred.cancel()
                }

                val step = try {
                    deferred.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }

                if (call.id in cancelledCallIds) {
                    continue
                }

                onStepCompleted(call, step)
                if (step.deferredJpeg != null) latestDeferredJpeg = step.deferredJpeg
                responses += LiveFunctionResponse(
                    id = call.id,
                    name = call.name,
                    output = step.output,
                    scheduling = step.scheduling?.takeIf { includeNonBlockingFlag },
                )
            }
        }

        if (responses.isNotEmpty() && session.sendToolResponses(responses)) {
            turnCoordinator.onToolResponseSent()
            if (latestDeferredJpeg != null) {
                session.sendVideoJpeg(latestDeferredJpeg)
            }
        }
    }
}

/**
 * 语音会话内打字消息的单一路径投递器：
 * - 连接中/重连中（`Connecting` / `Reconnecting`）排队缓冲并**仅落盘一次**，会话就绪后自动补发，绝不重复落盘或唤醒文字聊天模型；
 * - 活跃态（`Active`）先向 `LiveRealtimeSession.sendUserText` 发送，发送失败直接抛出 [PersonalSystemException]（零落盘、零文字模型回退、零恢复提示污染）；
 * - 外部电话挂起态（`SuspendedByCall`）明确拒绝并保留机主输入草稿。
 */
internal class LiveUserTextDeliverer(
    private val phaseProvider: () -> LiveSessionPhase,
    private val sessionProvider: () -> LiveRealtimeSession?,
    private val flushTranscripts: () -> Unit,
    private val persistUserMessageOnce: (String) -> Unit,
    private val onAcceptedTurn: (String) -> Unit,
    private val onActiveDelivered: (String) -> Unit,
    private val onSendFailedMessage: () -> String,
    private val onSuspendedMessage: () -> String,
    private val maxQueuedMessages: Int = MAX_QUEUED_TYPED_MESSAGES,
) {
    private val lock = Any()
    private val pendingQueue = ArrayDeque<String>()

    fun deliver(rawText: String) {
        val clean = rawText.trim()
        if (clean.isEmpty()) return

        flushTranscripts()
        when (val phase = phaseProvider()) {
            LiveSessionPhase.SuspendedByCall -> {
                throw PersonalSystemException("live_on_call", onSuspendedMessage())
            }
            LiveSessionPhase.Idle, LiveSessionPhase.Error -> {
                throw PersonalSystemException("live_send_failed", onSendFailedMessage())
            }
            LiveSessionPhase.Connecting, LiveSessionPhase.Reconnecting -> {
                enqueueWhileConnecting(clean)
            }
            LiveSessionPhase.Active -> {
                val session = sessionProvider()
                if (session == null) {
                    enqueueWhileConnecting(clean)
                    return
                }
                if (!session.sendUserText(clean, turnComplete = true)) {
                    throw PersonalSystemException("live_send_failed", onSendFailedMessage())
                }
                persistUserMessageOnce(clean)
                onAcceptedTurn(clean)
                onActiveDelivered(clean)
            }
        }
    }

    private fun enqueueWhileConnecting(clean: String) {
        synchronized(lock) {
            if (pendingQueue.size >= maxQueuedMessages) {
                throw PersonalSystemException("live_send_failed", onSendFailedMessage())
            }
            pendingQueue.addLast(clean)
        }
        persistUserMessageOnce(clean)
        onAcceptedTurn(clean)
    }

    fun flushToConnectedSession(session: LiveRealtimeSession) {
        while (true) {
            val next = synchronized(lock) { pendingQueue.pollFirst() } ?: break
            if (session.sendUserText(next, turnComplete = true)) {
                onActiveDelivered(next)
            } else {
                synchronized(lock) { pendingQueue.addFirst(next) }
                break
            }
        }
    }

    fun queuedCount(): Int = synchronized(lock) { pendingQueue.size }

    fun clear() {
        synchronized(lock) { pendingQueue.clear() }
    }

    companion object {
        const val MAX_QUEUED_TYPED_MESSAGES = 8
    }
}

fun interface LiveSessionConnector {
    suspend fun connect(
        creds: ResolvedLiveCredentials,
        setup: GeminiLiveSetup,
    ): LiveRealtimeSession
}

class PersonalLiveRuntime internal constructor(
    private val app: MikaApp,
    private val agent: PersonalAgent,
    private val liveConnector: LiveSessionConnector? = null,
    audioControllerOverride: LiveAudioController? = null,
) {
    constructor(app: MikaApp, agent: PersonalAgent) : this(app, agent, null, null)

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception in PersonalLiveRuntime", throwable)
        if (_uiState.value.phase.isRunning) {
            failAndStop(throwable.message ?: tr(R.string.live_error_connect_failed))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    val settings = PersonalLiveSettings(File(app.filesDir, "personal/live-settings.json"))
    private val defaultLiveClient by lazy { GeminiLiveClient(http = ProviderStore.modelHttp) }

    private val _uiState = MutableStateFlow(PersonalLiveUiState())
    val uiState: StateFlow<PersonalLiveUiState> = _uiState.asStateFlow()

    val isSessionActive: Boolean
        get() = _uiState.value.phase.isRunning

    /**
     * 当前语音直控会话（`LiveDirectPhoneController`）生效的纯等待时间配置：
     * - 仅影响语音直控虚拟屏自身的稳定窗口与打字/手势节奏，绝不污染普通聊天或定时后台 `TaskRun`；
     * - 挂断语音后自动回退为 [PhoneWaitProfile.STANDARD]。
     */
    fun effectiveWaitProfile(): PhoneWaitProfile =
        if (isSessionActive) PhoneWaitProfile.TURBO else PhoneWaitProfile.STANDARD

    @Volatile private var touchCoordinatorRef: LiveUserTouchCoordinator? = null

    private val turnCoordinator = LiveTurnCoordinator(
        scope = scope,
        sessionProvider = { currentSession },
        signalProvider = { _uiState.value.toSignalSnapshot() },
        userTouchingProvider = { touchCoordinatorRef?.isUserTouching == true },
        onDelivered = { touchInteraction() },
    )

    private val touchCoordinator = LiveUserTouchCoordinator(
        scope = scope,
        turnCoordinator = turnCoordinator,
        onUserActivity = { touchInteraction() },
    ).also { touchCoordinatorRef = it }

    private val webSearchRunner = LiveWebSearchRunner(
        app = app,
        scope = scope,
        turnCoordinator = turnCoordinator,
        onStatusHint = { hint -> _uiState.update { it.copy(statusHint = hint) } },
    )

    private val deepThinkRunner = LiveDeepThinkRunner(
        app = app,
        scope = scope,
        webSearch = webSearchRunner,
        turnCoordinator = turnCoordinator,
        onStatusHint = { hint -> _uiState.update { it.copy(statusHint = hint) } },
    )

    private val directPhone = LiveDirectPhoneController(
        app = app,
        scope = scope,
        onStageTouchMotion = { masked, capture ->
            touchCoordinator.onMotionAction(maskedAction = masked, captureAfterSettle = capture)
        },
        onStageDiscreteTouch = { capture ->
            touchCoordinator.onDiscreteAction(captureAfterSettle = capture)
        },
        waitProfileProvider = { effectiveWaitProfile() },
        decisionModelProvider = { app.tasks.decisionModel(app) },
        userUtteranceProvider = { currentTurnUtteranceSnapshot() ?: recentUserQuotes.peekLast() },
    )
    val directPhonePreview: StateFlow<Bitmap?> get() = directPhone.previewFrames
    val directPhonePipInput: StateFlow<PipInput?> get() = directPhone.pipInput
    val directPhoneApp: StateFlow<String?> get() = directPhone.foregroundApp
    val directPhonePackage: StateFlow<String?> get() = directPhone.foregroundPackage
    val directPhoneActivating: StateFlow<Boolean> get() = directPhone.activating
    val directPhoneActionSeq: StateFlow<Long> get() = directPhone.actionSeq
    val directPendingConfirmation: StateFlow<LiveDirectPhoneController.PendingDirectConfirmation?>
        get() = directPhone.pendingConfirmation

    private val _taskActionSeq = MutableStateFlow(0L)
    val taskActionSeq: StateFlow<Long> = _taskActionSeq.asStateFlow()

    private val _retainedStage = MutableStateFlow<RetainedLiveStage?>(null)
    val retainedStage: StateFlow<RetainedLiveStage?> = _retainedStage.asStateFlow()

    internal fun rememberStageFrame(frame: Bitmap, appLabel: String?, packageName: String? = null) {
        if (!isSessionActive || frame.isRecycled) return
        val prev = _retainedStage.value
        val nextLabel = appLabel?.takeIf { it.isNotBlank() } ?: prev?.appLabel
        val nextPkg = packageName?.takeIf { it.isNotBlank() } ?: prev?.packageName
        if (prev?.frame !== frame || prev.appLabel != nextLabel || prev.packageName != nextPkg) {
            _retainedStage.value = RetainedLiveStage(frame, nextLabel, nextPkg)
        }
    }

    /** 机主在休眠留存帧上点击时立即唤醒并重新拉起对应应用的实时可触控虚拟屏。 */
    fun wakeRetainedStage() {
        if (!isSessionActive || directPhoneActivating.value) return
        val retained = _retainedStage.value ?: return
        val pkg = retained.packageName?.takeIf { it.isNotBlank() }
        val label = retained.appLabel?.takeIf { it.isNotBlank() }
        if (pkg == null && label == null) return
        touchInteraction()
        scope.launch {
            preemptRunningPhoneTasks()
            val commands = JsonArray(
                listOf(
                    buildJsonObject {
                        put("op", "launch")
                        if (pkg != null) put("packageName", pkg) else put("appName", label!!)
                    },
                ),
            )
            directPhone.act(commands, callTag = "wake_stage")
        }
    }

    private val audioFocusPreempted = MutableStateFlow(false)
    private val lastInteractionAt = AtomicLong(System.currentTimeMillis())

    private val audioController: LiveAudioController = audioControllerOverride ?: LiveDuplexAudioEngine(
        context = app,
        onInputPcm16 = { pcm -> currentSession?.sendAudioPcm16(pcm) },
        onAudioFocusPreempted = { preempted -> audioFocusPreempted.value = preempted },
    )

    @Volatile private var activeSessionScope: CoroutineScope? = null
    @Volatile private var currentSession: LiveRealtimeSession? = null
    @Volatile private var sessionJob: Job? = null
    @Volatile private var callResumeWatchJob: Job? = null
    @Volatile private var resumptionHandle: String? = null
    @Volatile private var resumptionHandleAtMs: Long = 0L
    @Volatile private var includeNonBlockingFlag: Boolean = false
    @Volatile private var suspendedSinceMs: Long = 0L
    @Volatile private var suspendedLastCallId: String? = null
    @Volatile private var pendingPostResumeNotice: String? = null
    @Volatile private var pausedWakeWordForLive: Boolean = false

    private val userTextDeliverer = LiveUserTextDeliverer(
        phaseProvider = { _uiState.value.phase },
        sessionProvider = { currentSession },
        flushTranscripts = ::flushPendingTranscripts,
        persistUserMessageOnce = { clean -> agent.recordLiveMessage("user", clean) },
        onAcceptedTurn = { clean ->
            touchInteraction()
            turnCoordinator.onUserSpeechActivity()
            directPhone.onNewUserTurn()
            recordTurnUserUtterance(clean)
            recordRecentUserQuote(clean)
        },
        onActiveDelivered = { clean ->
            markUserTurnPendingRecovery(clean)
            audioController.clearPlayback()
        },
        onSendFailedMessage = { tr(R.string.live_error_connect_failed) },
        onSuspendedMessage = { tr(R.string.live_status_suspended_call) },
    )

    private val userTurnBuffer = StringBuilder()
    private val assistantTurnBuffer = StringBuilder()
    private var currentTurnUserUtterance: String? = null
    private var currentTurnIntentAssessed: Boolean = false
    private var unansweredUserTurnText: String? = null
    private var unansweredTurnAtMs: Long = 0L
    private val unansweredToolSummaries = ArrayList<String>()
    private var assistantEmittedInCurrentTurn: Boolean = false
    private val recentUserQuotes = ConcurrentLinkedDeque<String>()
    private val announcedWaitKeys = ConcurrentHashMap<String, String>()
    private val announcedTerminalIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val sessionPreExistingTerminalIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val delegatedTaskStartedAt = ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            combine(settings.state, app.providers.providers, app.providers.providerKeyRevisions) { cfg, _, _ ->
                cfg to (settings.resolve(app.providers) != null)
            }.collect { (cfg, ready) ->
                _uiState.update {
                    it.copy(
                        providerConfigured = ready,
                        selectedModel = cfg.model,
                        selectedVoice = cfg.voiceName,
                    )
                }
            }
        }

        scope.launch {
            combine(audioController.inputLevel, audioController.outputLevel, audioController.speaking) { inLvl, outLvl, spk ->
                Triple(inLvl, outLvl, spk)
            }.collect { (inLvl, outLvl, spk) ->
                if (spk || inLvl > 0.14f) touchInteraction()
                turnCoordinator.onAudioLevels(inLvl, spk)
                _uiState.update { it.copy(inputLevel = inLvl, outputLevel = outLvl, speaking = spk) }
            }
        }

        scope.launch {
            agent.state.map { s -> s.tasks.count { !it.terminal } }.distinctUntilChanged().collect { count ->
                _uiState.update { it.copy(activeTaskCount = count) }
            }
        }

        scope.launch {
            combine(
                app.calls.state.map { it.phase != CallPhase.IDLE }.distinctUntilChanged(),
                audioFocusPreempted,
            ) { carrierBusy, focusLost -> carrierBusy || focusLost }
                .distinctUntilChanged()
                .collect { externallyPreempted ->
                    if (externallyPreempted) {
                        suspendForExternalCall()
                    } else {
                        resumeAfterExternalCall()
                    }
                }
        }
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        val phase = _uiState.value.phase
        if (phase.isRunning) return
        if (!hasRecordPermission()) {
            _uiState.update { it.copy(phase = LiveSessionPhase.Error, errorMessage = tr(R.string.live_error_mic_permission)) }
            return
        }
        val creds = settings.resolve(app.providers)
        if (creds == null) {
            _uiState.update { it.copy(phase = LiveSessionPhase.Error, errorMessage = tr(R.string.live_error_no_gemini_key)) }
            return
        }
        val budget = creds.provider.dailyTokenBudget
        if (budget != null) {
            val used = app.usage.providerTokensToday(creds.provider.id)
            if (used >= budget) {
                val msg = ModelError.DailyBudgetExceeded(creds.provider.name, budget, used).message ?: tr(R.string.live_error_connect_failed)
                _uiState.update { it.copy(phase = LiveSessionPhase.Error, errorMessage = msg) }
                return
            }
        }
        if (app.calls.state.value.phase != CallPhase.IDLE) {
            _uiState.update { it.copy(phase = LiveSessionPhase.Error, errorMessage = tr(R.string.live_error_in_carrier_call)) }
            return
        }

        activeSessionScope?.cancel()
        val newSessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + exceptionHandler)
        activeSessionScope = newSessionScope

        resumptionHandle = null
        resumptionHandleAtMs = 0L
        includeNonBlockingFlag = false
        pendingPostResumeNotice = null
        audioFocusPreempted.value = false
        callResumeWatchJob?.cancel()
        callResumeWatchJob = null
        _retainedStage.value = null
        userTextDeliverer.clear()
        turnCoordinator.clear()
        touchCoordinator.clear()
        synchronized(this) {
            userTurnBuffer.setLength(0)
            assistantTurnBuffer.setLength(0)
            currentTurnUserUtterance = null
            currentTurnIntentAssessed = false
            unansweredUserTurnText = null
            unansweredTurnAtMs = 0L
            unansweredToolSummaries.clear()
            assistantEmittedInCurrentTurn = false
            announcedWaitKeys.clear()
            announcedTerminalIds.clear()
            sessionPreExistingTerminalIds.clear()
            agent.state.value.tasks.filter { it.terminal }.forEach { sessionPreExistingTerminalIds += it.id }
        }
        touchInteraction()

        _uiState.update {
            it.copy(
                phase = LiveSessionPhase.Connecting,
                muted = false,
                speaking = false,
                liveUserTranscript = "",
                liveAssistantTranscript = "",
                errorMessage = null,
                statusHint = null,
            )
        }
        PersonalLiveService.startOrUpdate(app)
        newSessionScope.launch { settings.refreshModels(app.providers) }
        launchSessionLoop(newSessionScope, isReconnect = false)
    }

    fun stop(statusHint: String? = null) {
        sessionJob?.cancel()
        sessionJob = null
        callResumeWatchJob?.cancel()
        callResumeWatchJob = null
        activeSessionScope?.cancel()
        activeSessionScope = null
        flushPendingTranscripts()
        runCatching { currentSession?.close() }
        currentSession = null
        audioController.stop()
        restoreWakeWordIfNeeded()
        resumptionHandle = null
        resumptionHandleAtMs = 0L
        pendingPostResumeNotice = null
        clearPendingTurnRecovery()
        userTextDeliverer.clear()
        audioFocusPreempted.value = false
        _retainedStage.value = null
        delegatedTaskStartedAt.clear()
        turnCoordinator.clear()
        touchCoordinator.clear()
        webSearchRunner.cancelAll()
        deepThinkRunner.cancelAll()
        scope.launch { directPhone.release() }
        _uiState.update {
            it.copy(
                phase = LiveSessionPhase.Idle,
                muted = false,
                speaking = false,
                inputLevel = 0f,
                outputLevel = 0f,
                liveUserTranscript = "",
                liveAssistantTranscript = "",
                errorMessage = null,
                statusHint = statusHint,
            )
        }
        PersonalLiveService.stop(app)
    }

    fun clearError() {
        _uiState.update {
            if (it.phase == LiveSessionPhase.Error) it.copy(phase = LiveSessionPhase.Idle, errorMessage = null, statusHint = null)
            else it.copy(errorMessage = null, statusHint = null)
        }
    }

    fun toggleMute() = setMuted(!_uiState.value.muted)

    fun restartIfActive() {
        if (!isSessionActive) return
        val wasMuted = _uiState.value.muted
        stop()
        start()
        if (wasMuted) setMuted(true)
    }

    fun setMuted(muted: Boolean) {
        audioController.setMuted(muted)
        if (muted) {
            currentSession?.sendAudioStreamEnd()
        } else {
            touchInteraction()
        }
        _uiState.update { it.copy(muted = muted) }
    }

    /**
     * Single-brain text routing while Mika Live is active:
     * queues during `Connecting`/`Reconnecting`, throws [PersonalSystemException] without duplicate persistence on failure,
     * and never falls back to the separate text-chat `PersonalAgent`.
     */
    fun deliverUserText(text: String) {
        userTextDeliverer.deliver(text)
    }

    /**
     * 仅由机主点击 Compose 界面确认卡片上的「同意」按钮触发（无任何模型工具入口）。
     */
    fun approveConfirmationFromUi(prompt: LiveConfirmationPrompt) {
        touchInteraction()
        val locked = app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        if (locked && (prompt.request.action == IrrevAction.pay || prompt.request.app == ROOT_CONFIRM_APP)) {
            _uiState.update { it.copy(statusHint = tr(R.string.confirmation_locked_hint)) }
            return
        }
        when (val src = prompt.source) {
            is LiveConfirmationSource.Direct -> {
                scope.launch {
                    val res = directPhone.approvePendingConfirmation(src.id) ?: return@launch
                    if (res.ok || res.completedSteps > 0) {
                        _uiState.update {
                            it.copy(statusHint = tr(R.string.live_hint_direct_elapsed, res.completedSteps, formatSeconds(res.elapsedMs)))
                        }
                    }
                    val feedback = if (res.ok) {
                        "[系统事件·机主已在确认卡片上点击「同意」并完成了该步操作]\n执行回执：${res.payload.toString().take(900)}\n请结合最新画面简短自然地向机主反馈结果或继续下一步。"
                    } else {
                        "[系统事件·机主点击「同意」后该步未能完成]\n执行回执：${res.payload.toString().take(700)}\n请简短向机主说明原因。"
                    }
                    turnCoordinator.enqueue(LiveAsyncEvent.DirectConfirmationFeedback(feedback, res.jpegFrame))
                }
            }
            is LiveConfirmationSource.Task -> {
                TaskService.approve(app, src.requestKey, src.runKey)
            }
        }
    }

    /**
     * 仅由机主点击 Compose 界面确认卡片上的「不同意」按钮触发（无任何模型工具入口）。
     */
    fun declineConfirmationFromUi(prompt: LiveConfirmationPrompt) {
        touchInteraction()
        when (val src = prompt.source) {
            is LiveConfirmationSource.Direct -> {
                val declined = directPhone.declinePendingConfirmation(src.id) ?: return
                turnCoordinator.enqueue(
                    LiveAsyncEvent.DirectConfirmationFeedback(
                        "[系统事件·机主已在确认卡片上点击「不同意」，取消了操作（${declined.appLabel} · ${declined.matchedText}）]\n请简短自然地应答已取消，切勿再次重试该操作。",
                    ),
                )
            }
            is LiveConfirmationSource.Task -> {
                TaskService.decline(app, src.requestKey, src.runKey)
            }
        }
    }

    /** Triggered when the user taps the manual "Share Current Screen" button on the Live bar or Capsule. */
    fun shareMainScreenNow() {
        scope.launch {
            val session = currentSession ?: return@launch
            touchInteraction()
            val captured = captureMainScreenPayload()
            val result = captured.payload
            val status = (result["status"]?.jsonPrimitive?.contentOrNull).orEmpty()
            if (status == "ok") {
                captured.jpeg?.let { session.sendVideoJpeg(it) }
                val label = result["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val textSummary = result["screen_text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                session.sendContextUpdate(
                    "[机主主动分享了当前主屏画面] 前台应用：$label\n屏幕可读文字：\n${textSummary.take(1500)}\n请结合刚才收到的屏幕画面直接回应机主。",
                    triggerResponse = true,
                )
            } else {
                val reason = result["reason"]?.jsonPrimitive?.contentOrNull ?: tr(R.string.live_screen_unavailable)
                _uiState.update { it.copy(statusHint = reason) }
            }
        }
    }

    internal fun onForegroundServiceFailed(t: Throwable) {
        Log.w(TAG, "Microphone foreground service could not start", t)
    }

    private fun pauseWakeWordForLiveAudio() {
        if (WakeWordService.active.value) {
            pausedWakeWordForLive = true
            runCatching { app.stopService(Intent(app, WakeWordService::class.java)) }
        }
    }

    private fun restoreWakeWordIfNeeded() {
        if (!pausedWakeWordForLive) return
        pausedWakeWordForLive = false
        if (app.foreground.visible.value) {
            runCatching { WakeWordService.restoreIfRequested(app) }
        }
    }

    private fun suspendForExternalCall() {
        val phase = _uiState.value.phase
        if (phase != LiveSessionPhase.Connecting && phase != LiveSessionPhase.Active && phase != LiveSessionPhase.Reconnecting) return
        Log.i(TAG, "Suspending Mika Live session due to external call / audio focus preemption")
        suspendedSinceMs = System.currentTimeMillis()
        suspendedLastCallId = app.calls.history.value.firstOrNull()?.id
        sessionJob?.cancel()
        sessionJob = null
        flushPendingTranscripts()
        runCatching { currentSession?.close() }
        currentSession = null
        audioController.stop()
        touchCoordinator.clear()
        scope.launch { directPhone.release() }
        _uiState.update {
            it.copy(
                phase = LiveSessionPhase.SuspendedByCall,
                speaking = false,
                inputLevel = 0f,
                outputLevel = 0f,
                liveUserTranscript = "",
                liveAssistantTranscript = "",
            )
        }
        val sessionScope = activeSessionScope ?: scope
        callResumeWatchJob?.cancel()
        callResumeWatchJob = sessionScope.launch {
            while (isActive && _uiState.value.phase == LiveSessionPhase.SuspendedByCall) {
                delay(800L)
                if (app.calls.state.value.phase == CallPhase.IDLE && !audioController.isSystemInCallMode()) {
                    audioFocusPreempted.value = false
                    break
                }
            }
        }
    }

    private fun resumeAfterExternalCall() {
        if (_uiState.value.phase != LiveSessionPhase.SuspendedByCall) return
        callResumeWatchJob?.cancel()
        callResumeWatchJob = null
        Log.i(TAG, "External call ended; resuming Mika Live session")
        val latestRecord = app.calls.history.value.firstOrNull()
        if (latestRecord != null && latestRecord.id != suspendedLastCallId && latestRecord.endedAtMs >= suspendedSinceMs) {
            pendingPostResumeNotice = buildCallReportNotice(latestRecord)
        }
        touchInteraction()
        val sessionScope = activeSessionScope ?: CoroutineScope(
            SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + exceptionHandler,
        ).also { activeSessionScope = it }
        _uiState.update { it.copy(phase = LiveSessionPhase.Reconnecting, errorMessage = null) }
        launchSessionLoop(sessionScope, isReconnect = true)
    }

    private fun buildCallReportNotice(record: CallRecord): String {
        val lines = record.transcript.takeLast(10).joinToString("\n") { "${it.role}: ${it.text}" }
        val summaryConclusion = record.summary?.conclusion?.takeIf { it.isNotBlank() }
        return buildString {
            appendLine("[系统事件·刚才的电话已结束]")
            appendLine("通话方向：${record.direction}，结果：${record.result}")
            if (summaryConclusion != null) appendLine("通话结论：$summaryConclusion")
            if (lines.isNotBlank()) appendLine("对话记录摘录：\n$lines")
            append("请用自然简洁的口语向机主汇报刚才这通电话的结果。")
        }
    }

    private fun launchSessionLoop(sessionScope: CoroutineScope, isReconnect: Boolean) {
        sessionJob?.cancel()
        sessionJob = sessionScope.launch {
            var connectFailures = 0
            var rapidCloseFailures = 0
            var reconnect = isReconnect
            while (isActive) {
                val creds = settings.resolve(app.providers)
                if (creds == null) {
                    failAndStop(tr(R.string.live_error_no_gemini_key))
                    return@launch
                }
                val budget = creds.provider.dailyTokenBudget
                if (budget != null) {
                    val used = app.usage.providerTokensToday(creds.provider.id)
                    if (used >= budget) {
                        failAndStop(ModelError.DailyBudgetExceeded(creds.provider.name, budget, used).message ?: tr(R.string.live_error_connect_failed))
                        return@launch
                    }
                }
                _uiState.update {
                    it.copy(
                        phase = if (reconnect || connectFailures > 0 || rapidCloseFailures > 0) {
                            LiveSessionPhase.Reconnecting
                        } else {
                            LiveSessionPhase.Connecting
                        },
                        errorMessage = null,
                    )
                }

                val nowElapsed = SystemClock.elapsedRealtime()
                val candidateHandle = resumptionHandle?.takeIf { handle ->
                    handle.isNotBlank() &&
                        resumptionHandleAtMs > 0L &&
                        (nowElapsed - resumptionHandleAtMs) <= PersonalLiveTools.RESUMPTION_HANDLE_MAX_AGE_MS
                }
                if (candidateHandle == null && resumptionHandle != null) {
                    Log.i(TAG, "Clearing expired Gemini Live resumption handle before connect")
                    resumptionHandle = null
                    resumptionHandleAtMs = 0L
                }

                val searchEnabled = webSearchRunner.isSearchEnabled()
                val systemPrompt = PersonalLiveTools.buildSystemPrompt(
                    state = agent.state.value,
                    memoryText = runCatching { agent.memorySnapshot() }.getOrDefault(""),
                    searchEnabled = searchEnabled,
                )
                val setup = GeminiLiveSetup(
                    model = creds.model,
                    systemInstruction = systemPrompt,
                    voiceName = creds.voiceName,
                    tools = PersonalLiveTools.toolsForMode(searchEnabled = searchEnabled),
                    resumptionHandle = candidateHandle,
                    enableSlidingWindow = true,
                    enableTranscription = true,
                    includeNonBlockingBehavior = includeNonBlockingFlag,
                )

                val session = try {
                    val connector = liveConnector
                    if (connector != null) {
                        connector.connect(creds, setup)
                    } else {
                        defaultLiveClient.connect(
                            baseUrl = creds.baseUrl,
                            apiKey = creds.apiKey,
                            setup = setup,
                            extraHeaders = app.providers.clientHeaders(creds.provider),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (!candidateHandle.isNullOrBlank()) {
                        Log.w(TAG, "Gemini Live resumption handle rejected; falling back immediately to fresh session", t)
                        resumptionHandle = null
                        resumptionHandleAtMs = 0L
                        reconnect = true
                        continue
                    }
                    Log.w(TAG, "Gemini Live connect failed (connectFailures=$connectFailures)", t)
                    if (connectFailures == 0) {
                        settings.refreshModels(app.providers)
                    }
                    connectFailures++
                    if (connectFailures > MAX_RECONNECT_ATTEMPTS) {
                        failAndStop(t.message ?: tr(R.string.live_error_connect_failed))
                        return@launch
                    }
                    delay(900L * connectFailures)
                    continue
                }

                pauseWakeWordForLiveAudio()
                currentSession = session
                val audioStarted = audioController.start(initialMuted = _uiState.value.muted)
                if (!audioStarted) {
                    runCatching { session.close() }
                    currentSession = null
                    if (app.calls.state.value.phase != CallPhase.IDLE || audioFocusPreempted.value) {
                        suspendForExternalCall()
                        return@launch
                    }
                    failAndStop(tr(R.string.live_error_audio_init))
                    return@launch
                }

                connectFailures = 0
                val connectedAtMs = SystemClock.elapsedRealtime()
                var hadSessionTraffic = false
                touchInteraction()
                _uiState.update { it.copy(phase = LiveSessionPhase.Active, errorMessage = null) }

                userTextDeliverer.flushToConnectedSession(session)

                val postCallNotice = pendingPostResumeNotice
                if (postCallNotice != null) {
                    pendingPostResumeNotice = null
                    clearPendingTurnRecovery()
                    session.sendContextUpdate(postCallNotice, triggerResponse = true)
                } else if (reconnect && candidateHandle == null && userTextDeliverer.queuedCount() == 0) {
                    consumeSeamlessRecoveryPrompt(SystemClock.elapsedRealtime())?.let { recoveryPrompt ->
                        Log.i(TAG, "Injecting seamless recovery prompt after fresh session reconnect")
                        sendTaskFrameIfAvailable(session, directPhonePreview.value)
                        session.sendContextUpdate(recoveryPrompt, triggerResponse = true)
                    }
                }

                val watcherJob = launch { watchTasksAndIdle(session, sessionScope) }
                val inFlightToolJobs = CopyOnWriteArrayList<Job>()
                val toolTracker = LiveSessionToolTracker()
                var seamlessReconnect = false
                var closedReason = ""
                var closedCode = -1

                try {
                    session.events.collect { event ->
                        when (event) {
                            GeminiLiveEvent.SetupComplete -> Unit
                            is GeminiLiveEvent.SessionResumptionUpdate -> {
                                if (event.resumable && !event.newHandle.isNullOrBlank()) {
                                    resumptionHandle = event.newHandle
                                    resumptionHandleAtMs = SystemClock.elapsedRealtime()
                                }
                            }
                            is GeminiLiveEvent.GoAway -> {
                                Log.i(TAG, "Received GoAway (timeLeft=${event.timeLeft}); scheduling seamless reconnect")
                                seamlessReconnect = true
                                session.close()
                            }
                            GeminiLiveEvent.Interrupted -> {
                                turnCoordinator.onAssistantTurnBoundary()
                                audioController.clearPlayback()
                                clearCurrentTurnIntentState()
                                if (commitAssistantTurnIfAny() || consumeAssistantEmittedFlag()) {
                                    clearPendingTurnRecovery()
                                }
                            }
                            is GeminiLiveEvent.InputTranscriptDelta -> {
                                hadSessionTraffic = true
                                touchInteraction()
                                turnCoordinator.onUserSpeechActivity()
                                commitAssistantTurnIfAny()
                                val snapshot = synchronized(this@PersonalLiveRuntime) {
                                    userTurnBuffer.append(event.text)
                                    userTurnBuffer.toString().trim()
                                }
                                _uiState.update { it.copy(liveUserTranscript = snapshot) }
                            }
                            is GeminiLiveEvent.AudioDelta -> {
                                hadSessionTraffic = true
                                markAssistantEmittedInTurn()
                                touchInteraction()
                                turnCoordinator.onAssistantActivity()
                                commitUserTurnIfAny()
                                audioController.enqueueOutputPcm24k(event.pcm24kLe)
                            }
                            is GeminiLiveEvent.OutputTranscriptDelta -> {
                                hadSessionTraffic = true
                                markAssistantEmittedInTurn()
                                touchInteraction()
                                turnCoordinator.onAssistantActivity()
                                commitUserTurnIfAny()
                                val snapshot = synchronized(this@PersonalLiveRuntime) {
                                    assistantTurnBuffer.append(event.text)
                                    assistantTurnBuffer.toString().trim()
                                }
                                _uiState.update { it.copy(liveAssistantTranscript = snapshot) }
                            }
                            GeminiLiveEvent.TurnComplete -> {
                                hadSessionTraffic = true
                                turnCoordinator.onAssistantTurnBoundary()
                                commitUserTurnIfAny()
                                clearCurrentTurnIntentState()
                                if (commitAssistantTurnIfAny() || consumeAssistantEmittedFlag()) {
                                    clearPendingTurnRecovery()
                                }
                            }
                            is GeminiLiveEvent.UsageReport -> {
                                app.usage.record(
                                    UsageSource(
                                        providerId = creds.provider.id,
                                        providerName = creds.provider.name,
                                        model = creds.model,
                                        purpose = UsagePurpose.Main,
                                    ),
                                    event.usage,
                                    httpAttempts = 1,
                                )
                                val limit = creds.provider.dailyTokenBudget
                                if (limit != null) {
                                    val usedNow = app.usage.providerTokensToday(creds.provider.id)
                                    if (usedNow >= limit) {
                                        failAndStop(
                                            ModelError.DailyBudgetExceeded(creds.provider.name, limit, usedNow).message
                                                ?: tr(R.string.live_error_connect_failed),
                                        )
                                        return@collect
                                    }
                                }
                            }
                            is GeminiLiveEvent.ToolCalls -> {
                                hadSessionTraffic = true
                                touchInteraction()
                                commitUserTurnIfAny()
                                val calls = event.calls
                                if (calls.isNotEmpty()) {
                                    turnCoordinator.launchToolWork(
                                        parentScope = this,
                                        inFlightRegistry = inFlightToolJobs,
                                    ) {
                                        toolTracker.runBatch(
                                            session = session,
                                            calls = calls,
                                            includeNonBlockingFlag = includeNonBlockingFlag,
                                            turnCoordinator = turnCoordinator,
                                            onStepCompleted = { call, step ->
                                                markToolExecutedPendingRecovery(call.name, step.output)
                                            },
                                            executeCall = ::executeSingleToolCall,
                                        )
                                    }
                                }
                            }
                            is GeminiLiveEvent.ToolCallCancelled -> {
                                Log.i(TAG, "Cancelling tool calls requested by server: ${event.ids}")
                                toolTracker.cancelCalls(event.ids)
                            }
                            is GeminiLiveEvent.Closed -> {
                                closedCode = event.code
                                closedReason = event.reason
                            }
                        }
                    }
                } finally {
                    watcherJob.cancel()
                    if (isActive && _uiState.value.phase.isRunning && _uiState.value.phase != LiveSessionPhase.SuspendedByCall && inFlightToolJobs.isNotEmpty()) {
                        withTimeoutOrNull(TOOL_FINISH_GRACE_MS) {
                            for (j in inFlightToolJobs.toList()) j.join()
                        }
                    }
                    toolTracker.clear()
                    inFlightToolJobs.forEach { it.cancel() }
                    flushPendingTranscripts()
                    runCatching { session.close() }
                    if (currentSession === session) currentSession = null
                }

                if (
                    !isActive ||
                    _uiState.value.phase == LiveSessionPhase.Idle ||
                    _uiState.value.phase == LiveSessionPhase.SuspendedByCall ||
                    _uiState.value.phase == LiveSessionPhase.Error
                ) {
                    return@launch
                }

                if (closedCode == 1007 && includeNonBlockingFlag) {
                    Log.w(TAG, "Server returned 1007 ($closedReason); retrying with standard tool declarations")
                    includeNonBlockingFlag = false
                    resumptionHandle = null
                    resumptionHandleAtMs = 0L
                    reconnect = true
                    continue
                }

                if (PersonalLiveTools.shouldClearResumptionHandle(closedCode, closedReason)) {
                    Log.w(TAG, "Gemini Live session closed ($closedCode: $closedReason); clearing stale resumption handle")
                    resumptionHandle = null
                    resumptionHandleAtMs = 0L
                }

                val sessionAliveMs = SystemClock.elapsedRealtime() - connectedAtMs
                Log.i(
                    TAG,
                    "Gemini Live WebSocket ended while session active (code=$closedCode, reason=$closedReason, aliveMs=$sessionAliveMs, seamless=$seamlessReconnect); auto-reconnecting",
                )
                if (hadSessionTraffic || sessionAliveMs >= STABLE_SESSION_MS || seamlessReconnect) {
                    rapidCloseFailures = 0
                } else {
                    rapidCloseFailures++
                    if (rapidCloseFailures > MAX_RECONNECT_ATTEMPTS) {
                        val detail = closedReason.ifBlank { tr(R.string.live_error_connect_failed) }
                        failAndStop("Gemini Live 会话关闭 ($closedCode): $detail")
                        return@launch
                    }
                }

                reconnect = true
                val backoffMs = when {
                    seamlessReconnect -> 0L
                    rapidCloseFailures > 0 -> 800L * rapidCloseFailures
                    else -> 250L
                }
                if (backoffMs > 0L) delay(backoffMs)
            }
        }
    }

    private suspend fun watchTasksAndIdle(session: LiveRealtimeSession, sessionScope: CoroutineScope) {
        while (sessionScope.isActive && currentSession === session) {
            delay(700L)
            if (_uiState.value.phase != LiveSessionPhase.Active) continue

            val snapshot = agent.state.value
            val activeTasks = snapshot.tasks.filterNot { it.terminal }
            val hasAsyncWork = activeTasks.isNotEmpty() ||
                deepThinkRunner.hasActiveThinking ||
                touchCoordinator.isUserTouching ||
                turnCoordinator.hasPendingEvents() ||
                directPhone.pendingConfirmation.value != null

            if (PersonalLiveTools.shouldAutoHangupOnIdle(
                    nowMs = System.currentTimeMillis(),
                    lastInteractionAtMs = lastInteractionAt.get(),
                    hasActiveTasks = hasAsyncWork,
                    isSpeakingOrConnecting = _uiState.value.speaking,
                )
            ) {
                Log.i(TAG, "Auto-hanging up Mika Live after 3 minutes of idle silence")
                stop(statusHint = tr(R.string.live_hint_idle_hangup))
                return
            }

            for (task in activeTasks) {
                val run = app.taskState.run(task.conversationId) ?: continue
                val rs = run.state.value
                when (rs) {
                    is TaskState.WaitingInput -> {
                        val waitId = "input:${rs.prompt}"
                        if (announcedWaitKeys[task.id] != waitId) {
                            announcedWaitKeys[task.id] = waitId
                            touchInteraction()
                            turnCoordinator.enqueue(
                                LiveAsyncEvent.BackgroundTaskWaiting(
                                    taskId = task.id,
                                    prompt = "[系统事件·后台任务等待机主回答] task_id=${task.id}，任务「${task.title}」提问：${rs.prompt}\n请立即简短向机主口头转达这个问题；等机主回答后调用 answer_task。",
                                    jpegFrame = encodeTaskFrame(run.phoneFrames.value),
                                ),
                            )
                        }
                    }
                    is TaskState.WaitingConfirmation -> {
                        val reqKey = ConfirmationNotifier.requestKey(rs)
                        val waitId = "confirm:$reqKey"
                        if (announcedWaitKeys[task.id] != waitId) {
                            announcedWaitKeys[task.id] = waitId
                            touchInteraction()
                            val appName = ConfirmationNotifier.appLabel(app, rs.request.app)
                            val detail = ConfirmationCopy.detail(rs.request, appName)
                            val prompt = "[系统事件·后台任务等待机主在屏幕上点击确认] 任务「${task.title}」已弹出确认卡片：\n$detail\n请简短告知机主操作详情，并提醒机主直接在屏幕确认卡片或通知上点击「同意 / 不同意」（系统不支持口头代点审批）。"
                            turnCoordinator.enqueue(
                                LiveAsyncEvent.BackgroundTaskWaiting(
                                    taskId = task.id,
                                    prompt = prompt,
                                    jpegFrame = encodeTaskFrame(run.phoneFrames.value),
                                ),
                            )
                        }
                    }
                    is TaskState.WaitingTakeover -> {
                        val waitId = "takeover:${rs.message}"
                        if (announcedWaitKeys[task.id] != waitId) {
                            announcedWaitKeys[task.id] = waitId
                            touchInteraction()
                            turnCoordinator.enqueue(
                                LiveAsyncEvent.BackgroundTaskWaiting(
                                    taskId = task.id,
                                    prompt = "[系统事件·后台任务需要人工接管] task_id=${task.id}，任务「${task.title}」说明：${rs.message}\n请简短提醒机主点击通知或悬浮胶囊亲自处理这一步。",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }

            val newlyFinished = snapshot.tasks.filter {
                it.terminal && it.id !in sessionPreExistingTerminalIds && it.id !in announcedTerminalIds
            }
            if (newlyFinished.isNotEmpty()) {
                for (done in newlyFinished) {
                    announcedTerminalIds += done.id
                    announcedWaitKeys.remove(done.id)
                    delegatedTaskStartedAt.remove(done.id)?.let { startedAt ->
                        val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                        _uiState.update { it.copy(statusHint = tr(R.string.live_hint_task_turbo_elapsed, formatSeconds(elapsedMs))) }
                    }
                    touchInteraction()
                    val outcome = done.result?.takeIf { it.isNotBlank() } ?: done.detail.ifBlank { done.status }
                    turnCoordinator.enqueue(
                        LiveAsyncEvent.BackgroundTaskDone(
                            taskId = done.id,
                            title = done.title,
                            status = done.status,
                            outcome = outcome,
                        ),
                    )
                }
            }
        }
    }

    private class CapturedMainScreen(
        val payload: JsonObject,
        val jpeg: ByteArray? = null,
    )

    /**
     * 屏幕轨道抢占：当机主下达新的手机操作指令（`quick_phone_act` 或新的 `delegate_task`）时，
     * 自动终止上一条仍在占用虚拟屏的手机后台任务并等待其释放屏幕资源（绝不触碰纯后台的 `deep_think` 与 `web_search`）。
     */
    private suspend fun preemptRunningPhoneTasks(): Boolean {
        val running = app.taskState.runs.value
        if (running.isEmpty()) return true
        val activeTasksByConv = agent.state.value.tasks.filterNot { it.terminal }.associateBy { it.conversationId }
        for (run in running) {
            activeTasksByConv[run.conversationId]?.let { task ->
                announcedTerminalIds += task.id
                announcedWaitKeys.remove(task.id)
                delegatedTaskStartedAt.remove(task.id)
            }
            TaskService.cancel(app, run.key)
        }
        return withTimeoutOrNull(PHONE_TASK_PREEMPT_WAIT_MS) {
            app.taskState.runs.first { it.isEmpty() }
            true
        } ?: app.taskState.runs.value.isEmpty()
    }

    private suspend fun executeSingleToolCall(call: LiveFunctionCall): ExecutedToolStep {
        fun str(key: String): String = call.args[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        return try {
            when (call.name) {
                PersonalLiveTools.QUICK_PHONE_ACT_NAME -> {
                    val commands = call.args["commands"] as? JsonArray ?: JsonArray(emptyList())
                    if (shouldBlockPhoneToolAsDiscussion(commands)) {
                        return ExecutedToolStep(buildDiscussOnlyPayload(), null)
                    }
                    if (app.taskState.runs.value.isNotEmpty()) {
                        preemptRunningPhoneTasks()
                    }
                    val res = directPhone.act(commands, callTag = call.id)
                    if (res.completedSteps > 0 || res.ok) {
                        _uiState.update {
                            it.copy(statusHint = tr(R.string.live_hint_direct_elapsed, res.completedSteps, formatSeconds(res.elapsedMs)))
                        }
                    }
                    ExecutedToolStep(res.payload, null, deferredJpeg = res.jpegFrame)
                }
                PersonalLiveTools.WEB_SEARCH_NAME -> {
                    val query = str("query")
                    val count = call.args["count"]?.jsonPrimitive?.intOrNull
                    ExecutedToolStep(webSearchRunner.searchForLiveTool(query, count), LiveScheduling.WHEN_IDLE)
                }
                PersonalLiveTools.DEEP_THINK_NAME -> {
                    val question = str("question")
                    val context = str("context")
                    ExecutedToolStep(deepThinkRunner.startThink(question, context, recentUserQuotesSnapshot()), LiveScheduling.WHEN_IDLE)
                }
                PersonalLiveTools.STEER_OR_CANCEL_THINK_NAME -> {
                    ExecutedToolStep(
                        deepThinkRunner.steerOrCancel(
                            thinkIdRaw = str("think_id"),
                            actionRaw = str("action"),
                            noteRaw = str("note"),
                        ),
                        LiveScheduling.SILENT,
                    )
                }
                "delegate_task" -> {
                    if (shouldBlockPhoneToolAsDiscussion(commands = null)) {
                        return ExecutedToolStep(buildDiscussOnlyPayload(), LiveScheduling.WHEN_IDLE)
                    }
                    val goal = str("goal")
                    val title = str("title")
                    directPhone.cancelInFlightAct()
                    if (app.taskState.runs.value.isNotEmpty()) {
                        preemptRunningPhoneTasks()
                    }
                    val simpleApp = if (app.taskState.runs.value.isEmpty()) {
                        PersonalLiveTools.extractSimpleLaunchApp(title, goal)
                    } else {
                        null
                    }
                    if (simpleApp != null) {
                        val commands = JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("op", "launch")
                                    put("appName", simpleApp)
                                },
                            ),
                        )
                        val res = directPhone.act(commands, callTag = call.id)
                        if (res.completedSteps > 0 || res.ok) {
                            _uiState.update {
                                it.copy(statusHint = tr(R.string.live_hint_direct_elapsed, res.completedSteps, formatSeconds(res.elapsedMs)))
                            }
                            ExecutedToolStep(res.payload, null, deferredJpeg = res.jpegFrame)
                        } else {
                            directPhone.release()
                            _taskActionSeq.value = _taskActionSeq.value + 1L
                            val task = agent.startLiveTask(title, goal)
                            delegatedTaskStartedAt[task.id] = SystemClock.elapsedRealtime()
                            ExecutedToolStep(
                                buildJsonObject {
                                    put("status", "started")
                                    put("task_id", task.id)
                                    put("title", task.title)
                                },
                                LiveScheduling.SILENT,
                            )
                        }
                    } else {
                        directPhone.release()
                        _taskActionSeq.value = _taskActionSeq.value + 1L
                        val task = agent.startLiveTask(title, goal)
                        delegatedTaskStartedAt[task.id] = SystemClock.elapsedRealtime()
                        ExecutedToolStep(
                            buildJsonObject {
                                put("status", "started")
                                put("task_id", task.id)
                                put("title", task.title)
                            },
                            LiveScheduling.SILENT,
                        )
                    }
                }
                "revise_task" -> {
                    val res = agent.reviseLiveTask(str("task_id"), str("instruction"))
                    ExecutedToolStep(
                        buildJsonObject {
                            put("status", "ok")
                            put("detail", res)
                        },
                        LiveScheduling.SILENT,
                    )
                }
                "cancel_task" -> {
                    val res = agent.cancelLiveTask(str("task_id"))
                    ExecutedToolStep(
                        buildJsonObject {
                            put("status", "ok")
                            put("detail", res)
                        },
                        LiveScheduling.SILENT,
                    )
                }
                "answer_task" -> {
                    val taskId = str("task_id")
                    val answer = str("answer")
                    require(answer.isNotBlank()) { "回答内容不能为空" }
                    val task = agent.state.value.tasks.find { it.id == taskId } ?: error("找不到任务 $taskId")
                    val run = app.taskState.run(task.conversationId) ?: error("任务已不在运行")
                    TaskService.answer(app, answer, run.key)
                    ExecutedToolStep(buildJsonObject { put("status", "answered") }, LiveScheduling.SILENT)
                }
                "inspect_task_screen" -> {
                    val taskId = str("task_id")
                    val activeTask = agent.state.value.tasks.find { it.id == taskId && !it.terminal }
                        ?: agent.state.value.tasks.lastOrNull { !it.terminal }
                    if (activeTask != null) {
                        val run = app.taskState.run(activeTask.conversationId)
                        val jpeg = encodeTaskFrame(run?.phoneFrames?.value)
                        val hasFrame = jpeg != null
                        ExecutedToolStep(
                            buildJsonObject {
                                put("status", activeTask.status)
                                put("title", activeTask.title)
                                put("detail", activeTask.detail)
                                put("virtual_screen_open", hasFrame)
                                put("frame_attached", hasFrame)
                                activeTask.result?.let { put("result", it.take(800)) }
                            },
                            LiveScheduling.WHEN_IDLE,
                            deferredJpeg = jpeg,
                        )
                    } else {
                        val directFrame = directPhonePreview.value
                        if (directFrame != null && !directFrame.isRecycled) {
                            val jpeg = encodeTaskFrame(directFrame)
                            val hasFrame = jpeg != null
                            ExecutedToolStep(
                                buildJsonObject {
                                    put("status", "direct_active")
                                    put("app", directPhoneApp.value.orEmpty())
                                    put("virtual_screen_open", true)
                                    put("frame_attached", hasFrame)
                                },
                                LiveScheduling.WHEN_IDLE,
                                deferredJpeg = jpeg,
                            )
                        } else {
                            val finishedTask = agent.state.value.tasks.find { it.id == taskId }
                            ExecutedToolStep(
                                buildJsonObject {
                                    put("status", finishedTask?.status ?: "idle")
                                    finishedTask?.title?.let { put("title", it) }
                                    put("virtual_screen_open", false)
                                    put("frame_attached", false)
                                    put(
                                        "note",
                                        "当前没有正在打开的实时虚拟屏（之前的后台任务已收尾并释放了临时屏幕）。若机主想看到或继续操作该应用，请立即调用 quick_phone_act 传入 launch 打开它。",
                                    )
                                    finishedTask?.result?.let { put("last_result", it.take(500)) }
                                },
                                LiveScheduling.WHEN_IDLE,
                            )
                        }
                    }
                }
                "capture_user_screen" -> {
                    val captured = captureMainScreenPayload()
                    ExecutedToolStep(captured.payload, LiveScheduling.WHEN_IDLE, deferredJpeg = captured.jpeg)
                }
                "remember" -> {
                    val quote = recentUserQuotesSnapshot()
                    val saved = agent.rememberFromLive(str("text"), quote)
                    ExecutedToolStep(
                        buildJsonObject {
                            put("status", "ok")
                            put("detail", saved)
                        },
                        LiveScheduling.SILENT,
                    )
                }
                "recall_history" -> {
                    val history = agent.recallHistoryFromLive(str("query"))
                    ExecutedToolStep(
                        buildJsonObject {
                            put("status", "ok")
                            put("history", history.ifBlank { "未找到相关历史记录" })
                        },
                        LiveScheduling.WHEN_IDLE,
                    )
                }
                else -> ExecutedToolStep(
                    buildJsonObject {
                        put("status", "error")
                        put("reason", "未知工具: ${call.name}")
                    },
                    LiveScheduling.WHEN_IDLE,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExecutedToolStep(
                buildJsonObject {
                    put("status", "error")
                    put("reason", e.message ?: "工具执行失败")
                },
                LiveScheduling.WHEN_IDLE,
            )
        }
    }

    internal fun onTaskStageTouchMotion(
        maskedAction: Int,
        pauseDuringGesture: () -> Unit,
        resumeAfterGesture: () -> Unit,
        captureAfterSettle: suspend () -> LiveUserTouchCoordinator.CapturedGuidanceState?,
    ) {
        touchCoordinator.onMotionAction(
            maskedAction = maskedAction,
            pauseDuringGesture = pauseDuringGesture,
            resumeAfterGesture = resumeAfterGesture,
            captureAfterSettle = captureAfterSettle,
        )
    }

    internal fun onTaskStageDiscreteTouch(
        pauseDuringGesture: () -> Unit,
        resumeAfterGesture: () -> Unit,
        captureAfterSettle: suspend () -> LiveUserTouchCoordinator.CapturedGuidanceState?,
    ) {
        touchCoordinator.onDiscreteAction(
            pauseDuringGesture = pauseDuringGesture,
            resumeAfterGesture = resumeAfterGesture,
            captureAfterSettle = captureAfterSettle,
        )
    }

    internal fun encodeGuidanceJpeg(bmp: Bitmap?): ByteArray? =
        bmp?.takeUnless { it.isRecycled }?.let { encodeScaledJpeg(it, maxEdge = 768, quality = 68) }

    private suspend fun captureMainScreenPayload(): CapturedMainScreen {
        val shot = app.capsule.captureMainScreenForLive()
            ?: return CapturedMainScreen(
                buildJsonObject {
                    put("status", "unavailable")
                    put("reason", tr(R.string.live_screen_unavailable))
                },
            )
        val jpeg = shot.jpeg
        if (shot.screen.blocked || jpeg == null) {
            return CapturedMainScreen(
                buildJsonObject {
                    put("status", "blocked_secure_window")
                    put("app", shot.screen.appLabel ?: shot.screen.packageName.orEmpty())
                    put("reason", "当前窗口启用了防截屏保护（如密码或金融页面），无法获取画面")
                },
            )
        }
        val lines = shot.screen.lines.joinToString("\n") { it.text }.take(2500)
        return CapturedMainScreen(
            payload = buildJsonObject {
                put("status", "ok")
                put("app", shot.screen.appLabel ?: shot.screen.packageName.orEmpty())
                put("screen_text", lines)
            },
            jpeg = jpeg,
        )
    }

    private fun encodeTaskFrame(bitmap: Bitmap?): ByteArray? {
        val bmp = bitmap?.takeUnless { it.isRecycled } ?: return null
        return encodeScaledJpeg(bmp)
    }

    private fun sendTaskFrameIfAvailable(session: LiveRealtimeSession, bitmap: Bitmap?): Boolean {
        val jpeg = encodeTaskFrame(bitmap) ?: return false
        return session.sendVideoJpeg(jpeg)
    }

    private fun encodeScaledJpeg(bmp: Bitmap, maxEdge: Int = 1024, quality: Int = 72): ByteArray? = runCatching {
        if (bmp.isRecycled) return null
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(w, h))
        val target = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(bmp, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        } else {
            bmp
        }
        try {
            val out = ByteArrayOutputStream()
            if (!target.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
        } finally {
            if (target !== bmp) target.recycle()
        }
    }.getOrNull()

    private fun commitUserTurnIfAny() {
        val text = synchronized(this) {
            val raw = userTurnBuffer.toString().trim()
            userTurnBuffer.setLength(0)
            raw
        }
        if (text.isNotEmpty()) {
            directPhone.onNewUserTurn()
            recordTurnUserUtterance(text)
            markUserTurnPendingRecovery(text)
            recordRecentUserQuote(text)
            agent.recordLiveMessage("user", text)
            _uiState.update { it.copy(liveUserTranscript = "") }
        }
    }

    private fun recordTurnUserUtterance(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(this) {
            val prev = currentTurnUserUtterance
            currentTurnUserUtterance = if (prev.isNullOrBlank()) clean else "$prev $clean"
            currentTurnIntentAssessed = false
        }
    }

    private fun clearCurrentTurnIntentState() {
        synchronized(this) {
            currentTurnUserUtterance = null
            currentTurnIntentAssessed = false
        }
    }

    private fun currentTurnUtteranceSnapshot(): String? = synchronized(this) {
        val committed = currentTurnUserUtterance?.trim().orEmpty()
        val pending = userTurnBuffer.toString().trim()
        when {
            committed.isEmpty() -> pending.ifEmpty { null }
            pending.isEmpty() -> committed
            else -> "$committed $pending"
        }
    }

    private fun recentConversationContextForIntent(currentMessage: String): String {
        val nonTask = agent.state.value.messages.filter { it.role != "task" }
        val priorList = if (
            nonTask.lastOrNull()?.role == "user" &&
            nonTask.last().conversationText().trim() == currentMessage.trim()
        ) {
            nonTask.dropLast(1)
        } else {
            nonTask
        }
        val history = priorList.asReversed()
            .asSequence()
            .mapNotNull { m -> m.conversationText().takeIf(String::isNotBlank)?.let { "${m.role}: ${it.take(240)}" } }
            .take(4)
            .toList()
            .asReversed()
            .joinToString("\n")
        val openApp = directPhoneApp.value?.takeIf { it.isNotBlank() }
        return if (openApp != null) {
            buildString {
                if (history.isNotBlank()) appendLine(history)
                append("[Active virtual screen app: ").append(openApp).append(']')
            }
        } else {
            history
        }
    }

    private suspend fun shouldBlockPhoneToolAsDiscussion(commands: JsonArray?): Boolean {
        if (commands != null && LiveDirectDecision.isPassiveObservationOnly(commands)) return false
        val decision = app.tasks.decisionModel(app) ?: return false
        val utterance = synchronized(this) {
            if (currentTurnIntentAssessed) return@synchronized null
            val committed = currentTurnUserUtterance?.trim().orEmpty()
            val pending = userTurnBuffer.toString().trim()
            val merged = when {
                committed.isEmpty() -> pending
                pending.isEmpty() -> committed
                else -> "$committed $pending"
            }.trim()
            if (merged.length < 4) return@synchronized null
            currentTurnIntentAssessed = true
            merged
        } ?: return false
        return LiveDirectDecision.shouldBlockAsDiscussion(
            decision = decision,
            currentTurnUserText = utterance,
            recentContext = recentConversationContextForIntent(utterance),
            commands = commands,
        )
    }

    private fun buildDiscussOnlyPayload(): JsonObject = buildJsonObject {
        put("ok", false)
        put("status", "discuss_only")
        put("message", "机主当前只是口头探讨或提问，并未要求立即操作手机；请直接用自然口语回答机主，不要打开或点击应用。")
    }

    private fun commitAssistantTurnIfAny(): Boolean {
        val text = synchronized(this) {
            val raw = assistantTurnBuffer.toString().trim()
            assistantTurnBuffer.setLength(0)
            raw
        }
        if (text.isNotEmpty()) {
            agent.recordLiveMessage("assistant", text)
            _uiState.update { it.copy(liveAssistantTranscript = "") }
            return true
        }
        return false
    }

    private fun flushPendingTranscripts() {
        commitUserTurnIfAny()
        commitAssistantTurnIfAny()
    }

    private fun markUserTurnPendingRecovery(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(this) {
            val prev = unansweredUserTurnText
            unansweredUserTurnText = if (prev.isNullOrBlank()) clean else "$prev $clean"
            unansweredTurnAtMs = SystemClock.elapsedRealtime()
            assistantEmittedInCurrentTurn = false
        }
    }

    private fun markToolExecutedPendingRecovery(toolName: String, output: JsonObject) {
        synchronized(this) {
            unansweredToolSummaries += "$toolName: ${output.toString().take(520)}"
            unansweredTurnAtMs = SystemClock.elapsedRealtime()
            assistantEmittedInCurrentTurn = false
        }
    }

    private fun markAssistantEmittedInTurn() {
        synchronized(this) {
            assistantEmittedInCurrentTurn = true
        }
    }

    private fun consumeAssistantEmittedFlag(): Boolean = synchronized(this) {
        val had = assistantEmittedInCurrentTurn
        assistantEmittedInCurrentTurn = false
        had
    }

    private fun clearPendingTurnRecovery() {
        synchronized(this) {
            currentTurnUserUtterance = null
            currentTurnIntentAssessed = false
            unansweredUserTurnText = null
            unansweredTurnAtMs = 0L
            unansweredToolSummaries.clear()
            assistantEmittedInCurrentTurn = false
        }
    }

    private fun consumeSeamlessRecoveryPrompt(nowElapsedMs: Long): String? = synchronized(this) {
        if (unansweredTurnAtMs <= 0L || nowElapsedMs - unansweredTurnAtMs > PersonalLiveTools.UNANSWERED_TURN_RECOVERY_WINDOW_MS) {
            unansweredUserTurnText = null
            unansweredTurnAtMs = 0L
            unansweredToolSummaries.clear()
            assistantEmittedInCurrentTurn = false
            return null
        }
        val prompt = PersonalLiveTools.buildSeamlessRecoveryPrompt(
            unansweredUserText = unansweredUserTurnText,
            recentToolSummaries = unansweredToolSummaries.toList(),
        )
        unansweredUserTurnText = null
        unansweredTurnAtMs = 0L
        unansweredToolSummaries.clear()
        assistantEmittedInCurrentTurn = false
        prompt
    }

    private fun recordRecentUserQuote(text: String) {
        recentUserQuotes.addLast(text)
        while (recentUserQuotes.size > 5) recentUserQuotes.pollFirst()
    }

    private fun recentUserQuotesSnapshot(): String = recentUserQuotes.joinToString("\n")

    private fun touchInteraction() {
        lastInteractionAt.set(System.currentTimeMillis())
    }

    private fun failAndStop(message: String) {
        sessionJob?.cancel()
        sessionJob = null
        callResumeWatchJob?.cancel()
        callResumeWatchJob = null
        activeSessionScope?.cancel()
        activeSessionScope = null
        flushPendingTranscripts()
        runCatching { currentSession?.close() }
        currentSession = null
        audioController.stop()
        restoreWakeWordIfNeeded()
        resumptionHandle = null
        resumptionHandleAtMs = 0L
        clearPendingTurnRecovery()
        userTextDeliverer.clear()
        audioFocusPreempted.value = false
        _retainedStage.value = null
        delegatedTaskStartedAt.clear()
        turnCoordinator.clear()
        touchCoordinator.clear()
        webSearchRunner.cancelAll()
        deepThinkRunner.cancelAll()
        scope.launch { directPhone.release() }
        PersonalLiveService.stop(app)
        _uiState.update {
            it.copy(
                phase = LiveSessionPhase.Error,
                speaking = false,
                inputLevel = 0f,
                outputLevel = 0f,
                liveUserTranscript = "",
                liveAssistantTranscript = "",
                errorMessage = message,
            )
        }
    }

    private fun formatSeconds(elapsedMs: Long): String =
        String.format(Locale.US, "%.1f", elapsedMs / 1000.0)

    companion object {
        private const val TAG = "MikaLive"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val STABLE_SESSION_MS = 8_000L
        private const val TOOL_FINISH_GRACE_MS = 3_500L
        private const val PHONE_TASK_PREEMPT_WAIT_MS = 3_000L
    }
}
