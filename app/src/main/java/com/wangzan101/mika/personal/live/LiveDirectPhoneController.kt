package com.wangzan101.mika.personal.live

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.a11y.PhoneWaitProfile
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.phone.AppPacePlan
import com.wangzan101.mika.core.cap.phone.AppPacing
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.decision.DecisionModel
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.live.LiveDirectConfirmation
import com.wangzan101.mika.core.live.LiveDirectDecision
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.personal.PersonalAgent
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.replay.ReplayOutcome
import com.wangzan101.mika.core.replay.Replayer
import com.wangzan101.mika.core.replay.RouteChoice
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.AfterTaskMarks
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.task.AppMuter
import com.wangzan101.mika.task.AppsAfterTaskEnd
import com.wangzan101.mika.task.AudioMute
import com.wangzan101.mika.task.DeviceStateWatcher
import com.wangzan101.mika.task.TaskRecovery
import com.wangzan101.mika.ui.StatusCopy
import com.wangzan101.mika.ui.scaleTouchToDisplay
import com.wangzan101.mika.vision.BitmapImageOps
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Mika Live 统一低延迟直控控制器：
 *
 * 在 Gemini Live 热会话内复用虚拟屏 [AgentDisplay] + [AndroidScreen] + [AppAccessScreenPort] + [SmsPrivateScreen] + [ActionExecutor]，
 * 以逐步可取消的方式执行 1–12 步轻量级手机动作，并在候选控件不唯一或字面未命中时可选复用 Jev [DecisionModel] 做语义/位置选点，与 [TaskRun] 共享同一套资源与治理不变量：
 * - 统一登记至 [com.wangzan101.mika.task.ResourceScheduler]，开启 `checkOtherTasks = true` 避免与后台任务争用同一 App；
 * - 每个写动作前经过逐动作关卡（锁屏/熄屏、助手连接、用户实时触控让行、前台被挪回主屏、示范模式与后台任务抢占）；
 * - 模型批次动作统一以 [Origin.Batch] 执行（永不消费审批凭据），高风险动作仅能由机主亲手点按 UI 确认卡片以 [Origin.Model] 单次放行，支付动作一律要求机主亲手触控完成。
 */
class LiveDirectPhoneController internal constructor(
    private val app: MikaApp,
    private val scope: CoroutineScope,
    private val onStageTouchMotion: ((maskedAction: Int, captureAfterSettle: suspend () -> LiveUserTouchCoordinator.CapturedGuidanceState?) -> Unit)? = null,
    private val onStageDiscreteTouch: ((captureAfterSettle: suspend () -> LiveUserTouchCoordinator.CapturedGuidanceState?) -> Unit)? = null,
    private val waitProfileProvider: () -> PhoneWaitProfile,
    private val decisionModelProvider: () -> DecisionModel? = { null },
    private val userUtteranceProvider: () -> String? = { null },
) {
    data class DirectActResult(
        val payload: JsonObject,
        val jpegFrame: ByteArray? = null,
        val elapsedMs: Long,
        val completedSteps: Int,
        val totalSteps: Int,
        val ok: Boolean,
    )

    /** 仅可由机主在 Compose 界面上点按「允许 / 取消」消费的单次高风险确认项。 */
    data class PendingDirectConfirmation(
        val id: Int,
        val request: ConfirmationRequest,
        val gateToken: ConfirmationRequest = request,
        val appLabel: String,
        val matchedText: String,
        val toolName: String,
        val toolArgs: JsonObject,
    )

    data class CompletedDirectStep(
        val callTag: String,
        val stepIndex: Int,
        val op: String,
        val atElapsedMs: Long,
    )

    data class DirectStepProgress(
        val op: String,
        val target: String?,
        val stepIndex: Int,
        val totalSteps: Int,
    )

    sealed interface DirectStageLaunch {
        data class Started(val goalId: String) : DirectStageLaunch
        data class Rejected(val result: DirectActResult) : DirectStageLaunch
    }

    data class DirectStageObservation(
        val appLabel: String,
        val screenText: String,
        val blocked: Boolean,
        val jpegFrame: ByteArray?,
    )

    internal class DirectGateStopException(
        val reason: String,
        val userMessage: String,
    ) : Exception(userMessage)

    internal class WarmSession(
        val key: String,
        val sideScope: CoroutineScope,
        val resources: ResourceScope,
        val connection: ShellBinding,
        val shell: com.wangzan101.mika.shell.IMikaShell,
        val watcher: DeviceStateWatcher,
        val shellUp: MutableStateFlow<Boolean>,
        val movedAway: MutableStateFlow<Boolean>,
        val display: LazyAgentDisplay,
        val screen: AndroidScreen,
        val afterTask: AfterTaskMarks,
        val accessGuard: AppAccessScreenPort,
        val privateScreen: SmsPrivateScreen,
        val dispatcher: ToolDispatcher,
        val knowledge: KnowledgeStore,
        val catalog: GuideCatalog,
        val visionService: VisionService?,
        val routeStore: RouteStore,
        val turnSink: RunSink,
        private val turnEventsBuffer: MutableList<RunEvent>,
        private val turnEventsLock: Any,
        val approval: RunApproval,
        val confirmations: ConfirmationGate,
        val pacing: OutwardPacing,
        val mute: AudioMute,
        val recorder: ActionRecorder,
        val appShown: MutableStateFlow<Boolean>,
        val syncPreview: suspend () -> Unit,
        val passGate: suspend () -> Unit,
        val selfPackage: String,
        val fallbackPackageProvider: () -> String? = { null },
    ) {
        private val unlockedPackages = HashSet<String>()
        private val deliveredGuidePackages = HashSet<String>()

        @Volatile
        var guards: OperationGuards = OperationGuards()
            private set

        @Volatile
        var executor: ActionExecutor = buildExecutor(guards)
            private set

        fun resetTurnGuards() {
            val nextGuards = OperationGuards()
            guards = nextGuards
            executor = buildExecutor(nextGuards)
            clearTurnEvents()
        }

        fun clearTurnEvents() {
            synchronized(turnEventsLock) {
                turnEventsBuffer.clear()
            }
        }

        fun snapshotTurnEventsWithEnd(endEvent: RunEvent.TaskEnded): List<RunEvent> = synchronized(turnEventsLock) {
            ArrayList<RunEvent>(turnEventsBuffer.size + 1).apply {
                addAll(turnEventsBuffer)
                add(endEvent)
            }
        }

        fun currentForegroundPackage(): String? = screen.target.value?.packageName

        @Synchronized
        fun unlockPackageLinks(pkg: String) {
            if (pkg.isBlank() || !unlockedPackages.add(pkg)) return
            knowledge.byPackage(pkg)?.let { entry ->
                if (entry.links.isNotEmpty()) {
                    dispatcher.allowLinks(entry.links)
                }
            }
        }

        @Synchronized
        fun unlockLinksForUri(uri: String?, explicitPkg: String?, installed: List<Pair<String, String>>): String? {
            if (!explicitPkg.isNullOrBlank()) {
                unlockPackageLinks(explicitPkg)
                return explicitPkg
            }
            val cleanUri = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            for ((pkg, _) in installed) {
                val entry = knowledge.byPackage(pkg) ?: continue
                val matched = entry.links.firstOrNull { it.uri == cleanUri }
                if (matched != null) {
                    unlockPackageLinks(pkg)
                    return matched.packageName ?: pkg
                }
            }
            return null
        }

        @Synchronized
        fun consumeCompactAppGuideOnce(pkg: String?): String? {
            val clean = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!deliveredGuidePackages.add(clean)) return null
            val entry = knowledge.byPackage(clean) ?: return null
            val linksLine = entry.links.take(4)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { "${it.name}=${it.uri}" }
                ?.let { "[直达深链(open_link): $it]" }
            val bodyLine = entry.body?.trim()?.takeIf { it.isNotEmpty() }?.let {
                compactGuideSnippet(it, MAX_INLINE_APP_GUIDE_CHARS)
            }
            return listOfNotNull(linksLine, bodyLine).joinToString(" | ").takeIf { it.isNotEmpty() }
        }

        private fun buildExecutor(currentGuards: OperationGuards): ActionExecutor = ActionExecutor(
            tools = dispatcher,
            guards = currentGuards,
            confirmations = confirmations,
            gate = passGate,
            recorder = recorder,
            offScreen = dispatcher.offScreenGuard(),
            approvals = ApprovalSettings.ASK,
            provenance = RunProvenance(RunOrigin.Ui, Attendance.Present),
            selfPackage = selfPackage,
            autonomous = false,
            pacing = pacing,
            risks = LiveDirectConfirmation.riskJudge(confirmations) {
                screen.target.value?.packageName ?: fallbackPackageProvider()
            },
        )
    }

    private suspend inline fun <T> runSuspendOrNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private val mutex = Mutex()
    private val activeActLock = Any()
    private var activeActJob: Job? = null
    private val stepSeq = AtomicInteger(0)
    private val stageGoalSeq = AtomicInteger(0)
    private val confirmSeq = AtomicInteger(0)
    @Volatile
    private var session: WarmSession? = null
    @Volatile
    private var pendingTurnGuardReset: Boolean = false
    private var idleJob: Job? = null
    private var touchReleaseJob: Job? = null

    private val userTouching = MutableStateFlow(false)

    private val _previewFrames = MutableStateFlow<Bitmap?>(null)
    val previewFrames: StateFlow<Bitmap?> = _previewFrames.asStateFlow()

    private val _pipInput = MutableStateFlow<PipInput?>(null)
    val pipInput: StateFlow<PipInput?> = _pipInput.asStateFlow()

    private val _foregroundApp = MutableStateFlow<String?>(null)
    val foregroundApp: StateFlow<String?> = _foregroundApp.asStateFlow()

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private val _activating = MutableStateFlow(false)
    val activating: StateFlow<Boolean> = _activating.asStateFlow()

    private val _stepProgress = MutableStateFlow<DirectStepProgress?>(null)
    val stepProgress: StateFlow<DirectStepProgress?> = _stepProgress.asStateFlow()

    private val _actionSeq = MutableStateFlow(0L)
    val actionSeq: StateFlow<Long> = _actionSeq.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingDirectConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingDirectConfirmation?> = _pendingConfirmation.asStateFlow()

    private val recentCompletedSteps = CopyOnWriteArrayList<CompletedDirectStep>()

    init {
        scope.launch {
            app.taskState.runs.collect { runs ->
                if (runs.isNotEmpty()) {
                    cancelInFlightAct()
                    if (session != null) {
                        release()
                    }
                }
            }
        }
    }

    fun completedStepsSnapshot(): List<CompletedDirectStep> = recentCompletedSteps.toList()

    /** 新一轮用户开口说话时标记待重置单轮循环守卫（在 mutex 内安全生效，保留对外频率限制）。 */
    fun onNewUserTurn() {
        pendingTurnGuardReset = true
    }

    /** 是否存在正在执行的同步直控批次或非阻塞虚拟屏多步目标（供空闲挂断守卫使用）。 */
    val hasActiveWork: Boolean
        get() = _activating.value || synchronized(activeActLock) { activeActJob?.isActive == true }

    /** 立即中断任何正在排队或逐步执行中的 `act` / `startAsyncGoal` 调用（供新屏幕指令抢占时调用）。 */
    fun cancelInFlightAct() {
        val runningJob = synchronized(activeActLock) {
            val prev = activeActJob
            activeActJob = null
            prev
        }
        runningJob?.cancel()
    }

    /**
     * 在非阻塞虚拟屏代理轨道上启动多步目标（`< 20ms` 立即返回启动凭据）：
     * - 原子抢占上一条仍在执行的屏幕动作，后台在同一热会话运行 `RouteMatcher + Replayer -> LiveSmartPhoneRunner`；
     * - 若执行期间机主说出新屏幕指令被抢占取消，协程抛 `CancellationException` 静默退出，绝不触发过期 [onFinished] 播报。
     */
    fun startAsyncGoal(
        goal: String,
        appName: String? = null,
        callTag: String = "smart_async",
        onFinished: (goalId: String, result: DirectActResult) -> Unit,
    ): DirectStageLaunch {
        val startedMs = SystemClock.elapsedRealtime()
        val cleanGoal = goal.trim()
        if (cleanGoal.isEmpty()) {
            return DirectStageLaunch.Rejected(failEarly(startedMs, 0, "missing_goal", "goal 不能为空。"))
        }
        if (app.teachReady && app.teach.blocksTasks()) {
            return DirectStageLaunch.Rejected(
                statusEarly(startedMs, 0, "teach_active", "当前正在进行人工示范录制，无法同时执行直控操作。"),
            )
        }
        if (app.taskState.runs.value.isNotEmpty()) {
            return DirectStageLaunch.Rejected(
                statusEarly(startedMs, 0, "task_running", "当前已有后台任务正在占用手机屏幕。"),
            )
        }
        val goalId = "stage_${stageGoalSeq.incrementAndGet()}"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val res = actGoal(goal = cleanGoal, appName = appName, callTag = "${callTag}_$goalId")
            currentCoroutineContext().ensureActive()
            onFinished(goalId, res)
        }
        val previous = synchronized(activeActLock) {
            val prev = activeActJob
            activeActJob = job
            prev
        }
        if (previous != null && previous !== job) {
            previous.cancel()
        }
        job.invokeOnCompletion {
            synchronized(activeActLock) {
                if (activeActJob === job) {
                    activeActJob = null
                }
            }
        }
        job.start()
        return DirectStageLaunch.Started(goalId)
    }

    suspend fun act(commands: JsonArray, callTag: String = "direct"): DirectActResult {
        val startedMs = SystemClock.elapsedRealtime()
        if (commands.isEmpty() || commands.size > MAX_DIRECT_COMMANDS) {
            return failEarly(
                startedMs,
                commands.size,
                "invalid_commands",
                "commands 必须包含 1 到 $MAX_DIRECT_COMMANDS 个步骤；超长复杂流程请改用 delegate_task。",
            )
        }

        if (app.teachReady && app.teach.blocksTasks()) {
            return statusEarly(
                startedMs,
                commands.size,
                "teach_active",
                "当前正在进行人工示范录制，无法同时执行直控操作。",
            )
        }

        if (app.taskState.runs.value.isNotEmpty()) {
            return statusEarly(
                startedMs,
                commands.size,
                "task_running",
                "当前已有后台任务正在占用手机屏幕。",
            )
        }

        val myJob = currentCoroutineContext()[Job]
        val previousJob = synchronized(activeActLock) {
            val prev = activeActJob
            activeActJob = myJob
            prev
        }
        if (previousJob != null && previousJob !== myJob) {
            previousJob.cancel()
        }

        return try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (app.taskState.runs.value.isNotEmpty()) {
                    closeSessionLocked()
                    return@withLock statusEarly(
                        startedMs,
                        commands.size,
                        "task_running",
                        "当前已有后台任务启动，直控已让出手机屏幕。",
                    )
                }
                if (app.teachReady && app.teach.blocksTasks()) {
                    closeSessionLocked()
                    return@withLock statusEarly(
                        startedMs,
                        commands.size,
                        "teach_active",
                        "当前正在进行人工示范录制，无法同时执行直控操作。",
                    )
                }
                idleJob?.cancel()
                // 模型发起新批次时作废此前尚未点按的旧确认卡片，防止陈旧审批悬空
                if (_pendingConfirmation.value != null) {
                    _pendingConfirmation.value = null
                }
                _actionSeq.value = _actionSeq.value + 1L
                _activating.value = true
                try {
                    executeLocked(commands, startedMs, callTag)
                } finally {
                    _stepProgress.value = null
                    _activating.value = false
                    if (app.taskState.runs.value.isNotEmpty() || (app.teachReady && app.teach.blocksTasks())) {
                        closeSessionLocked()
                    } else {
                        scheduleIdleRelease()
                    }
                }
            }
        } finally {
            synchronized(activeActLock) {
                if (activeActJob === myJob) {
                    activeActJob = null
                }
            }
        }
    }

    /**
     * 在常驻实时虚拟屏上执行中等复杂的单应用多步界面目标（`smart_phone_goal`）：
     * 先走 `RouteMatcher + Replayer` 经验路线零推理快放；若无匹配或中途分叉，无缝交给 [LiveSmartPhoneRunner] 在同一热会话内收尾。
     */
    suspend fun actGoal(goal: String, appName: String? = null, callTag: String = "goal"): DirectActResult {
        val startedMs = SystemClock.elapsedRealtime()
        val cleanGoal = goal.trim()
        if (cleanGoal.isEmpty()) {
            return failEarly(startedMs, 0, "missing_goal", "goal 不能为空。")
        }
        if (app.teachReady && app.teach.blocksTasks()) {
            return statusEarly(startedMs, 0, "teach_active", "当前正在进行人工示范录制，无法同时执行直控操作。")
        }
        if (app.taskState.runs.value.isNotEmpty()) {
            return statusEarly(startedMs, 0, "task_running", "当前已有后台任务正在占用手机屏幕。")
        }

        val myJob = currentCoroutineContext()[Job]
        val previousJob = synchronized(activeActLock) {
            val prev = activeActJob
            activeActJob = myJob
            prev
        }
        if (previousJob != null && previousJob !== myJob) {
            previousJob.cancel()
        }

        return try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (app.taskState.runs.value.isNotEmpty()) {
                    closeSessionLocked()
                    return@withLock statusEarly(startedMs, 0, "task_running", "当前已有后台任务启动，直控已让出手机屏幕。")
                }
                if (app.teachReady && app.teach.blocksTasks()) {
                    closeSessionLocked()
                    return@withLock statusEarly(startedMs, 0, "teach_active", "当前正在进行人工示范录制，无法同时执行直控操作。")
                }
                idleJob?.cancel()
                if (_pendingConfirmation.value != null) {
                    _pendingConfirmation.value = null
                }
                _actionSeq.value = _actionSeq.value + 1L
                _activating.value = true
                try {
                    executeGoalLocked(cleanGoal, appName, startedMs, callTag)
                } finally {
                    _stepProgress.value = null
                    _activating.value = false
                    if (app.taskState.runs.value.isNotEmpty() || (app.teachReady && app.teach.blocksTasks())) {
                        closeSessionLocked()
                    } else {
                        scheduleIdleRelease()
                    }
                }
            }
        } finally {
            synchronized(activeActLock) {
                if (activeActJob === myJob) {
                    activeActJob = null
                }
            }
        }
    }

    /**
     * 执行单个底层手机或系统动作工具（如 `phone_set_alarm`、`phone_set_timer`、`phone_show_alarms`、`phone_add_calendar_event`、`phone_calendar_bulk_insert`、`phone_open_link`）：
     * 复用同一虚拟屏与权限/确认网关；对静默系统工具（如设闹钟、计时器、批量写日历）默认不主动弹开虚拟屏画面，仅当系统应用实际弹出可视页面时才同步画面。
     */
    suspend fun executePhoneTool(
        toolName: String,
        args: JsonObject,
        callTag: String = "ext_phone",
        revealStageOnlyIfShowing: Boolean = false,
    ): DirectActResult {
        val startedMs = SystemClock.elapsedRealtime()
        if (app.teachReady && app.teach.blocksTasks()) {
            return statusEarly(startedMs, 1, "teach_active", "当前正在进行人工示范录制，无法同时执行操作。")
        }
        if (app.taskState.runs.value.isNotEmpty()) {
            return statusEarly(startedMs, 1, "task_running", "当前已有后台任务正在占用手机屏幕。")
        }
        val myJob = currentCoroutineContext()[Job]
        synchronized(activeActLock) {
            if (activeActJob == null) activeActJob = myJob
        }
        return try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (app.taskState.runs.value.isNotEmpty()) {
                    closeSessionLocked()
                    return@withLock statusEarly(startedMs, 1, "task_running", "当前已有后台任务启动，直控已让出手机屏幕。")
                }
                if (app.teachReady && app.teach.blocksTasks()) {
                    closeSessionLocked()
                    return@withLock statusEarly(startedMs, 1, "teach_active", "当前正在进行人工示范录制，无法同时执行操作。")
                }
                val warm = try {
                    ensureSessionLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhoneUnavailable) {
                return@withLock failEarly(startedMs, 1, "phone_unavailable", e.message ?: "手机操控助手当前未就绪。")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open session for $toolName", e)
                return@withLock failEarly(startedMs, 1, "phone_session_failed", e.message ?: "无法建立手机会话。")
            }
            if (pendingTurnGuardReset) {
                pendingTurnGuardReset = false
                warm.resetTurnGuards()
            }
            args.string("package")?.let { warm.unlockPackageLinks(it) }
            idleJob?.cancel()
            try {
                val outcome = try {
                    warm.executor.run(
                        Part.ToolCall("live_${callTag}_$toolName", toolName, args.toString()),
                        Origin.Batch(stepSeq.incrementAndGet()),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DirectGateStopException) {
                    return@withLock statusEarly(startedMs, 1, e.reason, e.userMessage)
                } catch (e: Exception) {
                    Log.w(TAG, "executePhoneTool failed: $toolName", e)
                    closeSessionLocked()
                    return@withLock failEarly(startedMs, 1, "execution_error", e.message ?: "执行手机工具异常。")
                }
                val elapsed = SystemClock.elapsedRealtime() - startedMs
                when (outcome) {
                    is ActionOutcome.Ran -> {
                        val content = outcome.exec.content
                        val ok = content["ok"] == JsonPrimitive(true)
                        val showing = content["showing"] == JsonPrimitive(true)
                        if (!revealStageOnlyIfShowing || showing) {
                            syncDisplayStateLocked(warm)
                        }
                        if (ok) recordCompletedStep(callTag, 0, toolName)
                        DirectActResult(
                            payload = content,
                            elapsedMs = elapsed,
                            completedSteps = if (ok) 1 else 0,
                            totalSteps = 1,
                            ok = ok,
                        )
                    }
                    is ActionOutcome.Refused -> {
                        val (reason, msg) = handleRefusedStep(warm, outcome, toolName, args)
                        syncDisplayStateLocked(warm)
                        DirectActResult(
                            payload = buildJsonObject {
                                put("ok", false)
                                put("stop_reason", reason)
                                msg?.let { put("message", it) }
                                put("elapsed_ms", elapsed)
                            },
                            elapsedMs = elapsed,
                            completedSteps = 0,
                            totalSteps = 1,
                            ok = false,
                        )
                    }
                    is ActionOutcome.Cancelled -> failEarly(startedMs, 1, "cancelled", "操作已取消。")
                }
                } finally {
                    if (app.taskState.runs.value.isNotEmpty() || (app.teachReady && app.teach.blocksTasks())) {
                        closeSessionLocked()
                    } else {
                        scheduleIdleRelease()
                    }
                }
            }
        } finally {
            synchronized(activeActLock) {
                if (activeActJob === myJob) {
                    activeActJob = null
                }
            }
        }
    }

    /**
     * 仅可由机主在应用内确认卡片上点击「允许」触发：
     * 原子消费对应的 [id]，生成单次放行凭据以 [Origin.Model] 仅执行这一步动作，执行后立即清空凭据（下次同按钮仍须再次确认）。
     */
    suspend fun approvePendingConfirmation(id: Int): DirectActResult? {
        val startedMs = SystemClock.elapsedRealtime()
        return mutex.withLock {
            val pending = _pendingConfirmation.value?.takeIf { it.id == id } ?: return@withLock null
            _pendingConfirmation.value = null
            val warm = session
            if (warm == null || !warm.shell.asBinder().isBinderAlive) {
                return@withLock failEarly(startedMs, 1, "phone_session_closed", "直控会话已结束，请重新发起操作。")
            }
            val step = stepSeq.incrementAndGet()
            if (!LiveDirectConfirmation.grantOnce(warm.confirmations, pending.gateToken, step)) {
                return@withLock statusEarly(
                    startedMs,
                    1,
                    "payment_requires_user_touch",
                    "支付步骤不支持代点确认，请机主直接在实时虚拟屏上点击完成。",
                )
            }
            idleJob?.cancel()
            _actionSeq.value = _actionSeq.value + 1L
            _activating.value = true
            try {
                val outcome = try {
                    warm.executor.run(
                        Part.ToolCall("live_confirm_${pending.id}", pending.toolName, pending.toolArgs.toString()),
                        Origin.Model(step),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DirectGateStopException) {
                    return@withLock statusEarly(startedMs, 1, e.reason, e.userMessage)
                } catch (e: Exception) {
                    Log.w(TAG, "Approved direct action failed", e)
                    closeSessionLocked()
                    return@withLock failEarly(startedMs, 1, "execution_error", e.message ?: "执行确认动作失败。")
                } finally {
                    warm.confirmations.reset()
                }
                val readOutcome = runSuspendOrNull {
                    warm.executor.run(
                        Part.ToolCall("live_confirm_${pending.id}_read", "phone_list_elements", "{}"),
                        Origin.Batch(stepSeq.incrementAndGet()),
                    )
                }
                syncDisplayStateLocked(warm)
                val elapsed = SystemClock.elapsedRealtime() - startedMs
                val finalScreen = (readOutcome as? ActionOutcome.Ran)?.exec?.content?.takeIf { it["ok"] == JsonPrimitive(true) }
                when (outcome) {
                    is ActionOutcome.Ran -> {
                        val ok = outcome.exec.content["ok"] == JsonPrimitive(true)
                        val err = (outcome.exec.content["error"] as? JsonPrimitive)?.contentOrNull
                        if (ok) recordCompletedStep("confirm_${pending.id}", 0, pending.toolName)
                        val rawScreen = finalScreen ?: (outcome.exec.content["screen"] as? JsonObject)
                        val compact = rawScreen?.let(::compactScreen)
                        val jpeg = if (shouldAttachVisualFrame(err, rawScreen)) capturePreviewJpegLocked(warm) else null
                        DirectActResult(
                            payload = buildJsonObject {
                                put("ok", ok)
                                put("confirmed_id", pending.id)
                                put("completed", if (ok) 1 else 0)
                                put("total", 1)
                                put("elapsed_ms", elapsed)
                                err?.let { put("stop_reason", it) }
                                compact?.let { put("screen", it) }
                                if (jpeg != null) put("visual_frame_attached", true)
                            },
                            jpegFrame = jpeg,
                            elapsedMs = elapsed,
                            completedSteps = if (ok) 1 else 0,
                            totalSteps = 1,
                            ok = ok,
                        )
                    }
                    is ActionOutcome.Refused -> {
                        val msg = (outcome.content["message"] as? JsonPrimitive)?.contentOrNull
                            ?: "页面内容已发生变化，请机主直接在实时画面上点击完成。"
                        DirectActResult(
                            payload = buildJsonObject {
                                put("ok", false)
                                put("confirmed_id", pending.id)
                                put("stop_reason", outcome.code)
                                put("message", msg)
                                put("elapsed_ms", elapsed)
                                finalScreen?.let { put("screen", compactScreen(it)) }
                            },
                            elapsedMs = elapsed,
                            completedSteps = 0,
                            totalSteps = 1,
                            ok = false,
                        )
                    }
                    is ActionOutcome.Cancelled -> failEarly(startedMs, 1, "cancelled", "确认动作已取消。")
                }
            } finally {
                _activating.value = false
                if (app.taskState.runs.value.isNotEmpty() || (app.teachReady && app.teach.blocksTasks())) {
                    closeSessionLocked()
                } else {
                    scheduleIdleRelease()
                }
            }
        }
    }

    /** 仅可由机主在应用内确认卡片上点击「拒绝 / 取消」触发；仅取消本次操作，不拉黑后续同按钮确认。 */
    fun declinePendingConfirmation(id: Int): PendingDirectConfirmation? {
        val current = _pendingConfirmation.value?.takeIf { it.id == id } ?: return null
        if (!_pendingConfirmation.compareAndSet(current, null)) return null
        session?.confirmations?.reset()
        return current
    }

    suspend fun release() {
        cancelInFlightAct()
        mutex.withLock {
            idleJob?.cancel()
            idleJob = null
            _pendingConfirmation.value = null
            closeSessionLocked()
        }
    }

    @Synchronized
    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        if (session == null) return
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            release()
        }
    }

    @Synchronized
    private fun onUserTouchMotion(maskedAction: Int) {
        when (maskedAction) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                if (maskedAction == MotionEvent.ACTION_DOWN && _pendingConfirmation.value != null) {
                    _pendingConfirmation.value = null
                    session?.confirmations?.reset()
                }
                touchReleaseJob?.cancel()
                userTouching.value = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchReleaseJob?.cancel()
                touchReleaseJob = scope.launch {
                    delay(80L)
                    session?.syncPreview?.invoke()
                    delay((TOUCH_SETTLE_HOLD_MS - 80L).coerceAtLeast(0L))
                    userTouching.value = false
                }
            }
        }
        session?.guards?.pageChanged()
        if (session != null && !mutex.isLocked) scheduleIdleRelease()
    }

    @Synchronized
    private fun onUserDiscreteTouch() {
        if (_pendingConfirmation.value != null) {
            _pendingConfirmation.value = null
            session?.confirmations?.reset()
        }
        touchReleaseJob?.cancel()
        userTouching.value = true
        touchReleaseJob = scope.launch {
            delay(80L)
            session?.syncPreview?.invoke()
            delay((TOUCH_SETTLE_HOLD_MS - 80L).coerceAtLeast(0L))
            userTouching.value = false
        }
        session?.guards?.pageChanged()
        if (session != null && !mutex.isLocked) scheduleIdleRelease()
    }

    private suspend fun executeLocked(
        rawCommands: JsonArray,
        startedMs: Long,
        callTag: String,
    ): DirectActResult {
        val installed by lazy {
            runCatching { launchableApps(app.packageManager, app.packageName) }.getOrDefault(emptyList())
        }
        val normalized = mutableListOf<JsonObject>()
        for ((index, element) in rawCommands.withIndex()) {
            val cmd = element as? JsonObject ?: return failEarly(
                startedMs, rawCommands.size, "invalid_command", "第 ${index + 1} 步格式错误，必须是 JSON 对象。",
            )
            val op = cmd.string("op") ?: return failEarly(
                startedMs, rawCommands.size, "missing_op", "第 ${index + 1} 步缺少 op 字段。",
            )
            if (op !in SUPPORTED_OPS) {
                return failEarly(startedMs, rawCommands.size, "unsupported_op", "不支持的直控步骤类型：$op。")
            }
            if (op == "launch") {
                val rawApp = cmd.string("packageName") ?: cmd.string("appName")
                    ?: return failEarly(startedMs, rawCommands.size, "missing_app", "launch 步骤需要提供 appName 或 packageName。")
                val resolvedPkg = resolveAppPackage(rawApp, installed)
                if (resolvedPkg == null) {
                    val suggestions = suggestApps(rawApp, installed)
                    val elapsed = SystemClock.elapsedRealtime() - startedMs
                    return DirectActResult(
                        payload = buildJsonObject {
                            put("ok", false)
                            put("error", "app_not_installed")
                            put("requested", rawApp)
                            put("message", "未找到已安装应用「$rawApp」。")
                            if (suggestions.isNotEmpty()) {
                                put("candidates", JsonArray(suggestions.map { (pkg, label) ->
                                    buildJsonObject { put("appName", label); put("packageName", pkg) }
                                }))
                            }
                            put("elapsed_ms", elapsed)
                        },
                        elapsedMs = elapsed,
                        completedSteps = 0,
                        totalSteps = rawCommands.size,
                        ok = false,
                    )
                }
                val clean = LinkedHashMap<String, JsonElement>()
                for ((k, v) in cmd) {
                    if (k != "appName" && k != "packageName") clean[k] = v
                }
                clean["op"] = JsonPrimitive("launch")
                clean["packageName"] = JsonPrimitive(resolvedPkg)
                normalized += JsonObject(clean)
            } else if (op == "open_link") {
                val rawApp = cmd.string("packageName") ?: cmd.string("appName")
                val resolvedPkg = if (rawApp != null) {
                    resolveAppPackage(rawApp, installed) ?: run {
                        val suggestions = suggestApps(rawApp, installed)
                        val elapsed = SystemClock.elapsedRealtime() - startedMs
                        return DirectActResult(
                            payload = buildJsonObject {
                                put("ok", false)
                                put("error", "app_not_installed")
                                put("requested", rawApp)
                                put("message", "未找到已安装应用「$rawApp」。")
                                if (suggestions.isNotEmpty()) {
                                    put("candidates", JsonArray(suggestions.map { (pkg, label) ->
                                        buildJsonObject { put("appName", label); put("packageName", pkg) }
                                    }))
                                }
                                put("elapsed_ms", elapsed)
                            },
                            elapsedMs = elapsed,
                            completedSteps = 0,
                            totalSteps = rawCommands.size,
                            ok = false,
                        )
                    }
                } else {
                    null
                }
                val clean = LinkedHashMap<String, JsonElement>()
                for ((k, v) in cmd) {
                    if (k != "appName" && k != "packageName") clean[k] = v
                }
                clean["op"] = JsonPrimitive("open_link")
                val settingsAction = cmd.string("settingsAction") ?: cmd.string("action")
                if (settingsAction != null) {
                    clean["settingsAction"] = JsonPrimitive(settingsAction)
                    if (settingsAction == LinkPolicy.APP_DETAILS && cmd.string("uri") == null && resolvedPkg != null) {
                        clean["uri"] = JsonPrimitive("package:$resolvedPkg")
                    }
                } else if (resolvedPkg != null) {
                    clean["packageName"] = JsonPrimitive(resolvedPkg)
                }
                normalized += JsonObject(clean)
            } else {
                val clean = LinkedHashMap<String, JsonElement>()
                for ((k, v) in cmd) {
                    if (k != "appName") clean[k] = v
                }
                normalized += JsonObject(clean)
            }
        }

        val warm = try {
            ensureSessionLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneUnavailable) {
            return failEarly(
                startedMs,
                normalized.size,
                "phone_unavailable",
                e.message ?: "手机操控助手当前未就绪，如需网页或通话操作可尝试 delegate_task。",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open direct phone session", e)
            return failEarly(startedMs, normalized.size, "phone_session_failed", e.message ?: "无法建立直控手机会话。")
        }

        if (pendingTurnGuardReset) {
            pendingTurnGuardReset = false
            warm.resetTurnGuards()
        }

        val executor = warm.executor
        val steps = mutableListOf<JsonObject>()
        var stopReason: String? = null
        var stopMessage: String? = null
        var finalScreen: JsonObject? = null
        var skipFinalRead = false

        for ((index, command) in normalized.withIndex()) {
            currentCoroutineContext().ensureActive()
            val op = command.string("op")!!
            val effectiveCommand = if (op == "open_link" && command.string("settingsAction") == null) {
                val matchedPkg = warm.unlockLinksForUri(
                    uri = command.string("uri"),
                    explicitPkg = command.string("packageName"),
                    installed = installed,
                )
                if (matchedPkg != null && command.string("packageName") == null) {
                    val uriVal = command.string("uri").orEmpty()
                    if (uriVal.startsWith("http://", ignoreCase = true) || uriVal.startsWith("https://", ignoreCase = true)) {
                        JsonObject(command + ("packageName" to JsonPrimitive(matchedPkg)))
                    } else {
                        command
                    }
                } else {
                    command
                }
            } else {
                if (op == "launch") {
                    command.string("packageName")?.let { warm.unlockPackageLinks(it) }
                }
                command
            }
            val targetLabel = effectiveCommand.string("appName")
                ?: effectiveCommand.string("targetText")
                ?: effectiveCommand.string("text")
                ?: effectiveCommand.string("uri")
                ?: effectiveCommand.string("settingsAction")
            _stepProgress.value = DirectStepProgress(
                op = op,
                target = targetLabel?.takeIf { it.isNotBlank() },
                stepIndex = index + 1,
                totalSteps = normalized.size,
            )
            val whenText = effectiveCommand.string("whenText")
            if (whenText != null) {
                val matched = hasTextOnScreen(executor, callTag, index, whenText)
                if (!matched) {
                    stopReason = "condition_unmet"
                    steps += stepRow(index, op, ok = false, error = stopReason, executed = false)
                    break
                }
            }

            if (op == "wait" || op == "assert") {
                val targetText = effectiveCommand.string("targetText")
                if (targetText == null) {
                    stopReason = "target_required"
                    steps += stepRow(index, op, ok = false, error = stopReason, executed = false)
                    break
                }
                val present = effectiveCommand.boolean("present") ?: true
                val timeoutMs = (if (op == "assert") 0 else (effectiveCommand.integer("timeoutMs") ?: 5_000)).coerceIn(0, 10_000)
                val satisfied = awaitTextOnScreen(warm, executor, callTag, index, targetText, present, timeoutMs)
                steps += stepRow(index, op, ok = satisfied, error = if (satisfied) null else "condition_unmet", executed = true)
                if (satisfied) {
                    recordCompletedStep(callTag, index, op)
                } else {
                    stopReason = "condition_unmet"
                    break
                }
                continue
            }

            val resolvedCall = try {
                resolveToolCall(warm, executor, callTag, index, op, effectiveCommand)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DirectGateStopException) {
                stopReason = e.reason
                stopMessage = e.userMessage
                skipFinalRead = true
                steps += stepRow(index, op, ok = false, error = e.reason, executed = false)
                break
            }

            if (resolvedCall.error != null) {
                stopReason = resolvedCall.error
                steps += stepRow(index, op, ok = false, error = stopReason, executed = false)
                break
            }

            val toolName = resolvedCall.toolName!!
            val toolArgs = resolvedCall.toolArgs!!
            val stepNum = stepSeq.incrementAndGet()
            val outcome = try {
                executor.run(
                    Part.ToolCall("live_${callTag}_${index + 1}_$toolName", toolName, toolArgs.toString()),
                    Origin.Batch(stepNum),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DirectGateStopException) {
                stopReason = e.reason
                stopMessage = e.userMessage
                skipFinalRead = true
                steps += stepRow(index, op, ok = false, error = e.reason, executed = false)
                break
            } catch (e: Exception) {
                Log.w(TAG, "Direct phone step failed: $toolName", e)
                closeSessionLocked()
                return failEarly(startedMs, normalized.size, "execution_error", e.message ?: "直控步骤执行异常。")
            }

            when (outcome) {
                is ActionOutcome.Ran -> {
                    val content = outcome.exec.content
                    val ok = content["ok"] == JsonPrimitive(true)
                    val err = (content["error"] as? JsonPrimitive)?.contentOrNull
                    val executed = content["executed"] != JsonPrimitive(false)
                    steps += stepRow(index, op, ok = ok, error = err, executed = executed)
                    (content["screen"] as? JsonObject)?.let { finalScreen = it }
                    if (op == "observe") finalScreen = content
                    if (!ok) {
                        stopReason = err ?: "failed"
                        break
                    }
                    recordCompletedStep(callTag, index, op)
                    if (content["app_switched"] != null) {
                        stopReason = "app_switched"
                        break
                    }
                    if (content["opened"] != null) {
                        stopReason = "opened_elsewhere"
                        break
                    }
                    val screenObj = content["screen"] as? JsonObject
                    if (content["captureError"] != null || screenObj?.get("captureError") != null) {
                        stopReason = "display_capture_unavailable"
                        break
                    }
                    if (screenObj?.get("imageReason") != null) {
                        stopReason = "screen_needs_review"
                        break
                    }
                }
                is ActionOutcome.Refused -> {
                    steps += stepRow(index, op, ok = false, error = outcome.code, executed = false)
                    val refusal = handleRefusedStep(warm, outcome, toolName, toolArgs)
                    stopReason = refusal.first
                    stopMessage = refusal.second
                    if (AppAccessDenied.isAppAccessCode(outcome.code)) {
                        skipFinalRead = true
                    }
                    break
                }
                is ActionOutcome.Cancelled -> {
                    stopReason = "cancelled"
                    skipFinalRead = true
                    steps += stepRow(index, op, ok = false, error = "cancelled", executed = false)
                    break
                }
            }
        }

        currentCoroutineContext().ensureActive()
        if (!skipFinalRead && warm.shell.asBinder().isBinderAlive) {
            val readOut = runSuspendOrNull {
                executor.run(
                    Part.ToolCall("live_${callTag}_final_read", "phone_list_elements", "{}"),
                    Origin.Batch(stepSeq.incrementAndGet()),
                )
            }
            val readContent = (readOut as? ActionOutcome.Ran)?.exec?.content
            if (readContent?.get("ok") == JsonPrimitive(true)) {
                finalScreen = readContent
            } else if (stopReason == null && finalScreen == null) {
                stopReason = "final_read_failed"
            }
        }

        syncDisplayStateLocked(warm)
        _foregroundPackage.value?.let { warm.unlockPackageLinks(it) }

        val completedCount = steps.count { it["ok"] == JsonPrimitive(true) }
        val ok = stopReason == null
        val lastOpWasObserve = normalized.lastOrNull()?.string("op") == "observe"
        val compact = finalScreen?.let { compactScreen(it, slimSuccess = false) }
        val appGuide = if (!skipFinalRead) warm.consumeCompactAppGuideOnce(_foregroundPackage.value) else null
        val forceObserveFrame = lastOpWasObserve && finalScreen?.get("protected") != JsonPrimitive(true)
        val jpeg = if (
            !skipFinalRead &&
            (forceObserveFrame || shouldAttachVisualFrame(stopReason, finalScreen, suppressDenseSuccessFrame = false))
        ) {
            capturePreviewJpegLocked(warm)
        } else {
            null
        }
        val elapsed = SystemClock.elapsedRealtime() - startedMs
        val payload = buildJsonObject {
            put("ok", ok)
            put("mode", if (waitProfileProvider().turbo) "turbo_direct" else "standard_direct")
            put("completed", completedCount)
            put("total", normalized.size)
            put("elapsed_ms", elapsed)
            stopReason?.let { put("stop_reason", it) }
            stopMessage?.let { put("message", it) }
            put("steps", JsonArray(steps))
            compact?.let { put("screen", it) }
            appGuide?.let { put("appGuide", it) }
            if (jpeg != null) put("visual_frame_attached", true)
        }
        return DirectActResult(
            payload = payload,
            jpegFrame = jpeg,
            elapsedMs = elapsed,
            completedSteps = completedCount,
            totalSteps = normalized.size,
            ok = ok,
        )
    }

    private sealed interface ReplayAttempt {
        data object NotApplicable : ReplayAttempt
        data class Finished(val result: DirectActResult) : ReplayAttempt
        data class DivergedAfterPartialProgress(val handoffNote: String) : ReplayAttempt
    }

    private suspend fun executeGoalLocked(
        goal: String,
        appName: String?,
        startedMs: Long,
        callTag: String,
    ): DirectActResult {
        val installed = runCatching { launchableApps(app.packageManager, app.packageName) }.getOrDefault(emptyList())
        val resolvedPkg = appName?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveAppPackage(it, installed) }
        val warm = try {
            ensureSessionLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneUnavailable) {
            return failEarly(
                startedMs,
                0,
                "phone_unavailable",
                e.message ?: "手机操控助手当前未就绪，如需网页或通话操作可尝试 delegate_task。",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open session for actGoal", e)
            return failEarly(startedMs, 0, "phone_session_failed", e.message ?: "无法建立直控手机会话。")
        }

        if (pendingTurnGuardReset) {
            pendingTurnGuardReset = false
            warm.resetTurnGuards()
        } else {
            warm.clearTurnEvents()
        }
        resolvedPkg?.let { warm.unlockPackageLinks(it) }

        var replayHandoffNote: String? = null
        if (FeatureFlags.get(app).speedFlags().replay) {
            when (val replay = tryReplayGoalLocked(warm, goal, startedMs, callTag)) {
                is ReplayAttempt.Finished -> return replay.result
                is ReplayAttempt.DivergedAfterPartialProgress -> replayHandoffNote = replay.handoffNote
                ReplayAttempt.NotApplicable -> Unit
            }
        }

        return LiveSmartPhoneRunner.runOnWarmSession(
            app = app,
            warm = warm,
            goal = goal,
            appName = appName,
            resolvedPackage = resolvedPkg,
            installedApps = installed,
            replayHandoffNote = replayHandoffNote,
            maxSteps = LiveSmartPhoneRunner.DEFAULT_MAX_STEPS,
            startedMs = startedMs,
            callTag = callTag,
            nextStepNum = stepSeq::incrementAndGet,
            onStepProgress = { op, target, idx, total ->
                _stepProgress.value = DirectStepProgress(op, target, idx, total)
            },
            onRefusedStep = { refused, tName, tArgs -> handleRefusedStep(warm, refused, tName, tArgs) },
            onStepCompleted = { idx, tName -> recordCompletedStep(callTag, idx, tName) },
            syncDisplayState = { syncDisplayStateLocked(warm) },
            captureJpeg = { capturePreviewJpegLocked(warm) },
        )
    }

    private suspend fun tryReplayGoalLocked(
        warm: WarmSession,
        goal: String,
        startedMs: Long,
        callTag: String,
    ): ReplayAttempt {
        val candidateCount = withContext(Dispatchers.IO) { runCatching { warm.routeStore.count() }.getOrDefault(0) }
        if (candidateCount <= 0) return ReplayAttempt.NotApplicable
        val modelSession = ModelSession.of(PersonalAgent.MAIN_CONVERSATION_ID)
        val matcherModel = app.tasks.lightModelFactory(app, modelSession)
            ?: app.tasks.modelFactory(app, modelSession)
            ?: return ReplayAttempt.NotApplicable

        val matcher = RouteMatcher(
            model = matcherModel,
            store = warm.routeStore,
            knowledge = warm.knowledge,
            decision = decisionModelProvider(),
        )
        if (withContext(Dispatchers.IO) { runCatching { matcher.candidates(goal).isEmpty() }.getOrDefault(true) }) {
            return ReplayAttempt.NotApplicable
        }
        val choice: RouteChoice = withTimeoutOrNull(LIVE_ROUTE_MATCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runSuspendOrNull { matcher.choose(goal) }
            }
        } ?: return ReplayAttempt.NotApplicable

        choice.route.packages.forEach { warm.unlockPackageLinks(it) }
        val total = choice.route.steps.size
        _stepProgress.value = DirectStepProgress("replay", null, 1, total)

        val replayer = Replayer(
            tools = warm.dispatcher,
            executor = warm.executor,
            gate = warm.passGate,
            sink = object : RunSink {
                override fun event(e: RunEvent) {
                    warm.turnSink.event(e)
                    if (e is RunEvent.Replay && e.matched && e.step < e.total) {
                        _stepProgress.value = DirectStepProgress("replay", null, e.step + 1, e.total)
                    }
                }
                override fun media(mediaId: Int, png: ByteArray) = Unit
            },
        )

        val out = try {
            replayer.run(choice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DirectGateStopException) {
            return ReplayAttempt.Finished(statusEarly(startedMs, total, e.reason, e.userMessage))
        } catch (e: Exception) {
            Log.w(TAG, "Route replay failed, falling back", e)
            return ReplayAttempt.NotApplicable
        }

        val nowMs = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching {
                warm.routeStore.update(choice.route.id) { r ->
                    when (out) {
                        is ReplayOutcome.Completed -> r.copy(
                            successes = r.successes + 1,
                            consecutiveMisses = 0,
                            missStep = null,
                            lastUsedMs = nowMs,
                        )
                        is ReplayOutcome.Diverged -> {
                            val misses = if (r.missStep == out.done) r.consecutiveMisses + 1 else 1
                            r.copy(
                                consecutiveMisses = misses,
                                missStep = out.done,
                                disabled = r.disabled || misses >= ROUTE_MISSES_TO_DISABLE,
                                lastUsedMs = nowMs,
                            )
                        }
                        is ReplayOutcome.StoppedForConfirmation -> r.copy(lastUsedMs = nowMs)
                    }
                }
            }
        }

        return when (out) {
            is ReplayOutcome.Completed -> {
                repeat(out.done) { idx -> recordCompletedStep(callTag, idx, choice.route.steps[idx].tool) }
                val readOut = runSuspendOrNull {
                    warm.executor.run(
                        Part.ToolCall("live_${callTag}_replay_read", "phone_list_elements", "{}"),
                        Origin.Batch(stepSeq.incrementAndGet()),
                    )
                }
                val finalScreen = (readOut as? ActionOutcome.Ran)?.exec?.content?.takeIf { it["ok"] == JsonPrimitive(true) }
                syncDisplayStateLocked(warm)
                val elapsed = SystemClock.elapsedRealtime() - startedMs
                val doneLines = choice.route.steps.take(out.done)
                    .mapIndexed { i, st -> "${i + 1}. ${Replayer.describe(st, choice.slots)}" }
                    .joinToString("；")
                ReplayAttempt.Finished(
                    DirectActResult(
                        payload = buildJsonObject {
                            put("ok", true)
                            put("mode", "route_replay")
                            put("replayed", true)
                            put("completed", out.done)
                            put("total", total)
                            put("elapsed_ms", elapsed)
                            put("summary", "已按历史经验路线完成：${doneLines.take(180)}")
                            finalScreen?.let { put("screen", compactScreen(it, slimSuccess = true)) }
                        },
                        elapsedMs = elapsed,
                        completedSteps = out.done,
                        totalSteps = total,
                        ok = true,
                    ),
                )
            }
            is ReplayOutcome.StoppedForConfirmation -> {
                repeat(out.done) { idx -> recordCompletedStep(callTag, idx, choice.route.steps[idx].tool) }
                syncDisplayStateLocked(warm)
                val confirmStep = choice.route.steps.getOrNull(out.done)
                if (confirmStep != null) {
                    val triggered = triggerReplayStepConfirmationLocked(warm, confirmStep, choice.slots, callTag)
                    if (triggered != null) {
                        val elapsed = SystemClock.elapsedRealtime() - startedMs
                        return ReplayAttempt.Finished(
                            DirectActResult(
                                payload = buildJsonObject {
                                    put("ok", false)
                                    put("mode", "route_replay")
                                    put("completed", out.done)
                                    put("total", total)
                                    put("stop_reason", triggered.first)
                                    triggered.second?.let { put("message", it) }
                                    put("elapsed_ms", elapsed)
                                },
                                elapsedMs = elapsed,
                                completedSteps = out.done,
                                totalSteps = total,
                                ok = false,
                            ),
                        )
                    }
                }
                val doneDesc = choice.route.steps.take(out.done)
                    .mapIndexed { i, st -> "${i + 1}. ${Replayer.describe(st, choice.slots)}" }
                    .joinToString("\n")
                ReplayAttempt.DivergedAfterPartialProgress(
                    "经验路线已完成前 ${out.done}/$total 步：\n$doneDesc\n下一步涉及不可逆确认动作，请在当前屏幕上定位并触发对应按钮确认。",
                )
            }
            is ReplayOutcome.Diverged -> {
                if (out.done == 0 && !out.executed) {
                    ReplayAttempt.NotApplicable
                } else {
                    repeat(out.done) { idx -> recordCompletedStep(callTag, idx, choice.route.steps[idx].tool) }
                    val doneDesc = choice.route.steps.take(out.done)
                        .mapIndexed { i, st -> "${i + 1}. ${Replayer.describe(st, choice.slots)}" }
                        .joinToString("\n")
                    ReplayAttempt.DivergedAfterPartialProgress(
                        "经验路线已完成前 ${out.done}/$total 步：\n$doneDesc\n在第 ${out.done + 1} 步出现界面差异（预期：${out.expected}），请基于当前屏幕继续完成目标。",
                    )
                }
            }
        }
    }

    private suspend fun triggerReplayStepConfirmationLocked(
        warm: WarmSession,
        step: com.wangzan101.mika.core.replay.RouteStep,
        slots: List<String>,
        callTag: String,
    ): Pair<String, String?>? {
        val rawArgs = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(step.argsJson) as? JsonObject
        }.getOrNull() ?: return null
        val filledBase = LinkedHashMap<String, JsonElement>()
        for ((k, v) in rawArgs) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            filledBase[k] = if (v is JsonPrimitive && v.isString) {
                JsonPrimitive(fillReplaySlots(v.content, slots) ?: return null)
            } else {
                v
            }
        }
        val targetSel = step.target
        if (targetSel != null) {
            val wantedName = fillReplaySlots(targetSel.name, slots)?.takeIf { it.isNotEmpty() }
            val qArgs = buildJsonObject {
                wantedName?.let { put("text", it) }
                targetSel.resourceId?.let { put("resourceId", it) }
                targetSel.role?.let { put("role", it) }
                put("limit", 50)
            }
            val qOut = warm.executor.run(
                Part.ToolCall("live_${callTag}_replay_confirm_q", "phone_query", qArgs.toString()),
                Origin.Script(stepSeq.incrementAndGet()),
            )
            val qContent = (qOut as? ActionOutcome.Ran)?.exec?.content ?: return null
            val sid = (qContent["snapshotId"] as? JsonPrimitive)?.contentOrNull ?: return null
            val matches = (qContent["matches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val editable = step.tool == "phone_type_text" || step.tool == "phone_clear_text"
            val candidates = LiveDirectDecision.selectNarrowedCandidates(matches, wantedName, editable)
            val chosen = candidates.singleOrNull() ?: return null
            filledBase["ref"] = JsonPrimitive(chosen.ref)
            filledBase["snapshotId"] = JsonPrimitive(sid)
        }
        val callArgs = JsonObject(filledBase)
        val out = warm.executor.run(
            Part.ToolCall("live_${callTag}_replay_confirm", step.tool, callArgs.toString()),
            Origin.Batch(stepSeq.incrementAndGet()),
        )
        return if (out is ActionOutcome.Refused) {
            handleRefusedStep(warm, out, step.tool, callArgs)
        } else {
            null
        }
    }

    internal data class ResolvedToolStep(
        val toolName: String? = null,
        val toolArgs: JsonObject? = null,
        val error: String? = null,
    )

    private suspend fun resolveToolCall(
        warm: WarmSession,
        executor: ActionExecutor,
        callTag: String,
        index: Int,
        op: String,
        command: JsonObject,
    ): ResolvedToolStep = when (op) {
        "launch" -> {
            val pkg = command.string("packageName") ?: return ResolvedToolStep(error = "missing_app")
            ResolvedToolStep(
                toolName = "phone_launch_app",
                toolArgs = buildJsonObject {
                    put("package", pkg)
                    if (command.boolean("cold") == true) put("cold", true)
                },
            )
        }
        "open_link" -> buildDirectOpenLinkStep(command)
        "tap", "type", "clear" -> {
            val editable = op != "tap"
            val target = selectTargetOnScreen(warm, executor, callTag, index, op, command, editable)
            if (target.error != null) {
                ResolvedToolStep(error = target.error)
            } else if (op == "tap") {
                val args = buildJsonObject {
                    if (target.ref != null && target.snapshotId != null) {
                        put("ref", target.ref)
                        put("snapshotId", target.snapshotId)
                    } else {
                        val x = command.integer("x") ?: return ResolvedToolStep(error = "target_required")
                        val y = command.integer("y") ?: return ResolvedToolStep(error = "target_required")
                        put("x", x)
                        put("y", y)
                        command.string("targetText")?.let { put("target", it) }
                    }
                }
                ResolvedToolStep("phone_tap", args)
            } else {
                if (op == "type" && command.string("text") == null) {
                    ResolvedToolStep(error = "missing_text")
                } else {
                    val args = buildJsonObject {
                        if (op == "type") {
                            put("text", command.string("text")!!)
                            if (command.boolean("pressEnter") == true) put("pressEnter", true)
                        }
                        if (target.ref != null && target.snapshotId != null) {
                            put("ref", target.ref)
                            put("snapshotId", target.snapshotId)
                        }
                    }
                    ResolvedToolStep(if (op == "type") "phone_type_text" else "phone_clear_text", args)
                }
            }
        }
        "scroll" -> buildDirectScrollStep(command)
        "swipe" -> {
            val sx = command.integer("startX")
            val sy = command.integer("startY")
            val ex = command.integer("endX")
            val ey = command.integer("endY")
            if (sx !in 0..999 || sy !in 0..999 || ex !in 0..999 || ey !in 0..999) {
                ResolvedToolStep(error = "invalid_coordinates")
            } else {
                ResolvedToolStep(
                    "phone_swipe",
                    buildJsonObject {
                        put("startX", sx!!)
                        put("startY", sy!!)
                        put("endX", ex!!)
                        put("endY", ey!!)
                        command.integer("durationMs")?.let { put("durationMs", it) }
                    },
                )
            }
        }
        "key" -> {
            val key = command.string("key") ?: return ResolvedToolStep(error = "missing_key")
            ResolvedToolStep("phone_press_key", buildJsonObject { put("key", key) })
        }
        "back" -> ResolvedToolStep("phone_back", JsonObject(emptyMap()))
        else -> ResolvedToolStep("phone_list_elements", JsonObject(emptyMap()))
    }

    private data class SelectedTarget(
        val ref: String? = null,
        val snapshotId: String? = null,
        val error: String? = null,
    )

    private suspend fun selectTargetOnScreen(
        warm: WarmSession,
        executor: ActionExecutor,
        callTag: String,
        index: Int,
        op: String,
        command: JsonObject,
        editable: Boolean,
    ): SelectedTarget {
        val text = command.string("targetText")
        val id = command.string("resourceId")
        val role = command.string("role")
        if (text == null && id == null && role == null) {
            if (editable) return SelectedTarget()
            val x = command.integer("x")
            val y = command.integer("y")
            return if (x in 0..999 && y in 0..999) SelectedTarget() else SelectedTarget(error = "target_required")
        }
        val qArgs = buildJsonObject {
            text?.let { put("text", it) }
            id?.let { put("resourceId", it) }
            role?.let { put("role", it) }
            put("limit", 100)
        }
        val qOut = executor.run(
            Part.ToolCall("live_${callTag}_${index + 1}_query", "phone_query", qArgs.toString()),
            Origin.Script(stepSeq.incrementAndGet()),
        )
        if (qOut is ActionOutcome.Refused) return SelectedTarget(error = qOut.code)
        val qContent = (qOut as? ActionOutcome.Ran)?.exec?.content ?: return SelectedTarget(error = "query_failed")
        if (qContent["ok"] != JsonPrimitive(true)) return SelectedTarget(error = "query_failed")
        val truncated = qContent["truncated"] == JsonPrimitive(true)
        val sid = (qContent["snapshotId"] as? JsonPrimitive)?.contentOrNull ?: return SelectedTarget(error = "no_snapshot")
        val rawMatches = (qContent["matches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val narrowed = if (!truncated) {
            LiveDirectDecision.selectNarrowedCandidates(rawMatches, text, editable)
        } else {
            emptyList()
        }
        if (narrowed.size == 1) {
            return SelectedTarget(ref = narrowed.single().ref, snapshotId = sid)
        }

        val fallbackError = if (narrowed.isEmpty() && !truncated) "target_not_found" else "ambiguous_target"
        val decision = decisionModelProvider()
        val currentApp = (qContent["currentApp"] as? JsonPrimitive)?.contentOrNull ?: _foregroundApp.value

        if (decision != null && narrowed.size >= 2 && !truncated) {
            val chosenRef = LiveDirectDecision.chooseTargetWithJev(
                decision = decision,
                op = op,
                command = command,
                candidates = narrowed,
                currentApp = currentApp,
                userUtterance = userUtteranceProvider(),
                timeoutMs = LiveDirectDecision.LIVE_FAST_DISAMBIG_TIMEOUT_MS,
            )
            if (chosenRef != null) {
                return SelectedTarget(ref = chosenRef, snapshotId = sid)
            }
            return SelectedTarget(error = fallbackError)
        }

        if (decision != null) {
            val fullOut = executor.run(
                Part.ToolCall(
                    "live_${callTag}_${index + 1}_full_query",
                    "phone_query",
                    buildJsonObject { put("limit", 100) }.toString(),
                ),
                Origin.Script(stepSeq.incrementAndGet()),
            )
            if (fullOut is ActionOutcome.Refused) return SelectedTarget(error = fullOut.code)
            val fullContent = (fullOut as? ActionOutcome.Ran)?.exec?.content
            if (fullContent?.get("ok") == JsonPrimitive(true)) {
                var fullSid = (fullContent["snapshotId"] as? JsonPrimitive)?.contentOrNull ?: sid
                val fullMatches = (fullContent["matches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val baseCandidates = LiveDirectDecision.extractScreenCandidates(
                    matches = fullMatches,
                    editable = editable,
                    allowTapToFocusFallback = op == "type",
                )
                val screenCandidates = if (fullContent["truncated"] == JsonPrimitive(true)) {
                    val listOut = runSuspendOrNull {
                        executor.run(
                            Part.ToolCall("live_${callTag}_${index + 1}_list_all", "phone_list_elements", "{}"),
                            Origin.Script(stepSeq.incrementAndGet()),
                        )
                    }
                    val listContent = (listOut as? ActionOutcome.Ran)?.exec?.content
                    if (listContent?.get("ok") == JsonPrimitive(true)) {
                        (listContent["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let { fullSid = it }
                        val listElements = (listContent["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        LiveDirectDecision.mergeListElementsIntoCandidates(
                            primary = baseCandidates,
                            listElements = listElements,
                            editable = editable,
                            allowTapToFocusFallback = op == "type",
                        )
                    } else {
                        baseCandidates
                    }
                } else {
                    baseCandidates
                }
                val chosenRef = LiveDirectDecision.chooseTargetWithJev(
                    decision = decision,
                    op = op,
                    command = command,
                    candidates = screenCandidates,
                    currentApp = (fullContent["currentApp"] as? JsonPrimitive)?.contentOrNull ?: currentApp,
                    userUtterance = userUtteranceProvider(),
                    timeoutMs = LiveDirectDecision.LIVE_FAST_DISAMBIG_TIMEOUT_MS,
                )
                if (chosenRef != null) {
                    return SelectedTarget(ref = chosenRef, snapshotId = fullSid)
                }
            }
        }

        return SelectedTarget(error = fallbackError)
    }

    private suspend fun hasTextOnScreen(
        executor: ActionExecutor,
        callTag: String,
        index: Int,
        text: String,
    ): Boolean {
        val qOut = executor.run(
            Part.ToolCall(
                "live_${callTag}_${index + 1}_when",
                "phone_query",
                buildJsonObject { put("text", text); put("limit", 1) }.toString(),
            ),
            Origin.Script(stepSeq.incrementAndGet()),
        )
        val content = (qOut as? ActionOutcome.Ran)?.exec?.content ?: return false
        return content["ok"] == JsonPrimitive(true) && content["total"] != JsonPrimitive(0)
    }

    private suspend fun awaitTextOnScreen(
        warm: WarmSession,
        executor: ActionExecutor,
        callTag: String,
        index: Int,
        text: String,
        present: Boolean,
        timeoutMs: Int,
    ): Boolean {
        if (hasTextOnScreen(executor, callTag, index, text) == present) return true
        if (timeoutMs <= 0) return false
        return runSuspendOrNull {
            warm.privateScreen.waitFor(if (present) "appear" else "disappear", text, timeoutMs.toLong())
        } ?: false
    }

    private suspend fun handleRefusedStep(
        warm: WarmSession,
        outcome: ActionOutcome.Refused,
        toolName: String,
        toolArgs: JsonObject,
    ): Pair<String, String?> {
        val msg = (outcome.content["message"] as? JsonPrimitive)?.contentOrNull
        if (AppAccessDenied.isAppAccessCode(outcome.code)) {
            return outcome.code to (msg ?: "该应用已被访问权限策略限制操作。")
        }
        val hit: Irreversible? = outcome.hit
        if ((outcome.code == "confirmation_required" || outcome.code == "confirmation_mismatch") && hit != null) {
            if (hit.action == IrrevAction.pay) {
                return "payment_requires_user_touch" to
                    "已停在支付步骤（${hit.matched}）。支付与验密步骤不接受口头或自动放行，请提醒机主直接在实时画面上亲手点击完成。"
            }
            val snap = runSuspendOrNull { warm.privateScreen.peek() }
            val typing = if (toolName == "phone_type_text") toolArgs.string("text") else null
            val fallbackPkg = warm.screen.target.value?.packageName ?: _foregroundPackage.value
            val req = LiveDirectConfirmation.buildRequest(hit, snap, typing, fallbackApp = fallbackPkg)
            val gateToken = LiveDirectConfirmation.buildGateToken(hit, snap, fallbackApp = fallbackPkg)
            if (req != null && gateToken != null) {
                val card = PendingDirectConfirmation(
                    id = confirmSeq.incrementAndGet(),
                    request = req,
                    gateToken = gateToken,
                    appLabel = ConfirmationNotifier.appLabel(app, req.app),
                    matchedText = hit.matched,
                    toolName = toolName,
                    toolArgs = toolArgs,
                )
                _pendingConfirmation.value = card
                return "awaiting_ui_confirmation" to
                    "已在应用界面弹出确认卡片（${card.appLabel} · ${hit.matched}）。请简短提醒机主在屏幕上点按「同意」按钮或直接在实时画面上点击完成；切勿向用户索要口头确认，也不要重复调用工具重试这一步。"
            }
            return "user_touch_required" to
                "该高风险步骤（${hit.action.name}: ${hit.matched}）需由机主本人确认，请提醒机主直接在实时画面上点击对应按钮完成。"
        }
        return outcome.code to msg
    }

    private fun recordCompletedStep(callTag: String, stepIndex: Int, op: String) {
        recentCompletedSteps += CompletedDirectStep(callTag, stepIndex, op, SystemClock.elapsedRealtime())
        while (recentCompletedSteps.size > MAX_RECENT_STEPS) {
            recentCompletedSteps.removeAt(0)
        }
    }

    private suspend fun syncDisplayStateLocked(warm: WarmSession) {
        warm.display.current?.let { d ->
            val targetPkg = warm.screen.target.value?.packageName ?: withContext(Dispatchers.IO) {
                runCatching {
                    val topTask = warm.shell.topTaskId(d.displayId)
                    if (topTask >= 0) warm.shell.packageOfTask(topTask) else null
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            if (targetPkg != null) {
                warm.appShown.value = true
                _foregroundPackage.value = targetPkg
                _foregroundApp.value = ConfirmationNotifier.appLabel(app, targetPkg)
            }
            warm.syncPreview()
        }
    }

    internal suspend fun captureSettledStageGuidance(includeJpeg: Boolean = false): LiveUserTouchCoordinator.CapturedGuidanceState? {
        val warm = session ?: return null
        if (warm.display.current == null || !warm.shell.asBinder().isBinderAlive) return null
        return withContext(Dispatchers.IO) {
            runSuspendOrNull { warm.screen.waitStable(600L) }
            if (warm.privateScreen.imagesPrivate) return@withContext null
            val snap = runSuspendOrNull { warm.privateScreen.peek() }
            val targetPkg = snap?.packageName ?: warm.screen.target.value?.packageName
            if (!targetPkg.isNullOrBlank()) {
                _foregroundPackage.value = targetPkg
                _foregroundApp.value = ConfirmationNotifier.appLabel(app, targetPkg)
            }
            warm.syncPreview()
            val jpeg = if (includeJpeg) capturePreviewJpegLocked(warm) else null
            val summary = LiveUserTouchCoordinator.summarizeSnapshot(snap)
            if (summary.isBlank() && jpeg == null) null
            else LiveUserTouchCoordinator.CapturedGuidanceState(
                appLabel = _foregroundApp.value,
                screenSummary = summary,
                jpegFrame = jpeg,
            )
        }
    }

    /**
     * 当机主正在前台查看实时虚拟屏舞台时，按需零 OCR 读取当前虚拟屏画面与可读控件摘要（`< 80ms`），
     * 避免误调用物理主屏 `MediaProjection` 截取到 Mika 自身外壳或触发慢速 OCR。
     */
    internal suspend fun observeActiveStageIfOpen(): DirectStageObservation? {
        val warm = session ?: return null
        if (warm.display.current == null || !warm.appShown.value || !warm.shell.asBinder().isBinderAlive) return null
        return withContext(Dispatchers.IO) {
            val label = _foregroundApp.value ?: _foregroundPackage.value.orEmpty()
            if (warm.privateScreen.imagesPrivate) {
                return@withContext DirectStageObservation(
                    appLabel = label,
                    screenText = "",
                    blocked = true,
                    jpegFrame = null,
                )
            }
            val snap = runSuspendOrNull { warm.privateScreen.peek() }
            if (snap?.protected == true || snap?.captureError == "display_capture_unavailable") {
                return@withContext DirectStageObservation(
                    appLabel = label,
                    screenText = "",
                    blocked = true,
                    jpegFrame = null,
                )
            }
            val jpeg = capturePreviewJpegLocked(warm)
            val summary = LiveUserTouchCoordinator.summarizeSnapshot(snap)
            if (jpeg == null && summary.isBlank()) return@withContext null
            DirectStageObservation(
                appLabel = label,
                screenText = summary,
                blocked = false,
                jpegFrame = jpeg,
            )
        }
    }

    private suspend fun passDirectGate(warm: WarmSession) {
        currentCoroutineContext().ensureActive()
        if (app.taskState.runs.value.isNotEmpty()) {
            throw DirectGateStopException("task_running", "后台任务已启动，直控已自动让出手机资源。")
        }
        if (app.teachReady && app.teach.blocksTasks()) {
            throw DirectGateStopException("teach_active", "当前正在进行人工示范录制，已暂停直控操作。")
        }
        warm.watcher.refresh()
        if (warm.watcher.locked.value) {
            throw DirectGateStopException("device_locked", "手机处于锁屏或熄屏状态，请先提醒机主解锁屏幕后再继续。")
        }
        if (!warm.shellUp.value || !warm.shell.asBinder().isBinderAlive) {
            throw DirectGateStopException("helper_disconnected", "手机操控助手连接已断开。")
        }
        if (userTouching.value) {
            val settled = withTimeoutOrNull(USER_TOUCH_WAIT_MS) {
                userTouching.first { !it }
                true
            } ?: false
            currentCoroutineContext().ensureActive()
            if (!settled) {
                throw DirectGateStopException("user_touching", "机主正在实时画面上触控操作，已暂停模型动作避免抢手。")
            }
            warm.watcher.refresh()
            if (warm.watcher.locked.value) {
                throw DirectGateStopException("device_locked", "手机处于锁屏或熄屏状态，请先提醒机主解锁屏幕后再继续。")
            }
        }
        val d = warm.display.current
        val target = warm.screen.target.value
        if (d != null && target != null && target.taskId >= 0) {
            val moved = withContext(Dispatchers.IO) {
                runCatching {
                    DeviceStateWatcher.isMovedAway(
                        warm.shell.topTaskId(d.displayId),
                        warm.shell.topTaskId(Display.DEFAULT_DISPLAY),
                        target.taskId,
                    )
                }.getOrDefault(warm.movedAway.value)
            }
            if (moved) {
                warm.movedAway.value = true
                throw DirectGateStopException(
                    "app_moved_to_main_screen",
                    "目标应用已被切到手机主屏幕，为避免干扰机主前台操作已暂停直控。",
                )
            }
        }
    }

    private suspend fun ensureSessionLocked(): WarmSession {
        session?.let { existing ->
            if (existing.shell.asBinder().isBinderAlive) return existing
            closeSessionLocked()
        }
        if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) {
            throw PhoneUnavailable("当前 Android 版本低于最低要求，不支持后台虚拟屏操控。")
        }
        val phoneServices = PhoneServices.of(app)
        val recovery = TaskRecovery.of(app)
        val runKey = "live-direct-${UUID.randomUUID()}"
        val resources = ResourceScope()
        val sideScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
        var connection: ShellBinding? = null
        var watcher: DeviceStateWatcher? = null
        var approval: RunApproval? = null
        var mute: AudioMute? = null
        var smsPrivacy: SmsPrivateScreen? = null
        var recoveryBegun = false
        var registeredOwner = false
        try {
            val conn = phoneServices.sharedShell.acquire().also { connection = it }
            val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
                ?: run {
                    phoneServices.setupPrompt.request()
                    val hint = if (phoneServices.shellControl.restart == HelperRestart.UsbCommand) {
                        "手机操控助手未连接（需重新执行 USB 启动命令）。"
                    } else {
                        "手机操控助手未就绪，请先在设置中完成配对/启动。"
                    }
                    throw PhoneUnavailable(hint)
                }
            val a11y = AccessibilityRepair.awaitService(
                ScreenAccessibility.instance,
                phoneServices.recovery.accessibilityRepair.phase,
                A11Y_WAIT_MS,
            ) ?: throw PhoneUnavailable(StatusCopy.accessibilityUnavailable(phoneServices.recovery.accessibility.value.enabled))

            val deviceWatcher = DeviceStateWatcher(app) { id ->
                runCatching { shell.topTaskId(id) }.getOrDefault(-1)
            }.also { watcher = it }
            val shellUp = MutableStateFlow(true)
            val movedAway = MutableStateFlow(false)
            sideScope.launch {
                conn.shell.map { it != null }.collect { shellUp.value = it }
            }

            app.resources.registerExternalOwner(runKey) {
                session?.key == runKey && shell.asBinder().isBinderAlive
            }
            registeredOwner = true

            recovery.beginRun(shell, runKey)
            recoveryBegun = true

            val metrics = mainScreenMetrics()
            val appShown = MutableStateFlow(false)
            val windowTick = MutableStateFlow(0L)
            var accessGuardRef: AppAccessScreenPort? = null
            var directInputRef: DirectPipInput? = null
            suspend fun previewAllowed(): Boolean {
                if (smsPrivacy?.imagesPrivate == true) return false
                val guard = accessGuardRef ?: return false
                return try {
                    guard.checkAppAccess()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: AppAccessDenied) {
                    false
                }
            }

            sideScope.launch(Dispatchers.IO) {
                a11y.windowChanges().collect {
                    windowTick.value = windowTick.value + 1L
                }
            }

            var lazyDisplayRef: LazyAgentDisplay? = null
            suspend fun syncPreviewNow() {
                val d = lazyDisplayRef?.current ?: return
                val allowed = appShown.value && previewAllowed()
                if (allowed) {
                    _previewFrames.value = d.frames.value ?: d.refreshPreviewFrame()
                    _pipInput.value = directInputRef
                } else {
                    _previewFrames.value = null
                    _pipInput.value = null
                }
            }

            val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                resources.acquire(Dispatchers.IO, AgentDisplay::release) {
                    AgentDisplay(
                        shell,
                        metrics.widthPixels,
                        metrics.heightPixels,
                        metrics.densityDpi,
                        app.getSystemService(DisplayManager::class.java),
                        previewShortSide = AgentDisplay.LIVE_PREVIEW_SHORT_SIDE,
                        frameIntervalMs = AgentDisplay.LIVE_FRAME_INTERVAL_MS,
                    )
                }.also { d ->
                    val directInput = DirectPipInput(
                        shell = shell,
                        display = d,
                        isLocked = { deviceWatcher.locked.value },
                        onMotion = { masked ->
                            onUserTouchMotion(masked)
                            onStageTouchMotion?.invoke(masked) { captureSettledStageGuidance() }
                        },
                        onDiscrete = {
                            onUserDiscreteTouch()
                            onStageDiscreteTouch?.invoke { captureSettledStageGuidance() }
                        },
                    ).also { directInputRef = it }
                    sideScope.launch {
                        combine(d.frames, appShown, windowTick) { frame, shown, _ -> frame to shown }.collect { (frame, shown) ->
                            val allowed = shown && previewAllowed()
                            _previewFrames.value = if (allowed) (frame ?: d.refreshPreviewFrame()) else null
                            _pipInput.value = if (allowed) directInput else null
                        }
                    }
                    sideScope.launch {
                        app.appAccess.state.collect {
                            windowTick.value = windowTick.value + 1L
                            if (!previewAllowed()) {
                                _previewFrames.value = null
                                _pipInput.value = null
                            }
                        }
                    }
                }
            }.also { lazyDisplayRef = it }

            val audio = recovery.audioMute(shell, runKey).also { mute = it }
            val muter = buildAppMuter(shell, audio)
            val taskActive: (String) -> Boolean = { k -> app.taskState.runs.value.any { it.key == k } }
            fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
            fun stillUsed(pkg: String, user: Int?): () -> Boolean = {
                runCatching {
                    if (user == null) shell.displayOfPackage(pkg) else shell.displayOfPackageInUser(pkg, user)
                }.getOrDefault(-1) >= 0
            }

            fun pacePlan(): AppPacePlan {
                val flags = FeatureFlags.get(app)
                return AppPacePlan(flags.operatingPace(), flags.appPaceOverrides())
            }

            val screen = AndroidScreen(
                a11y = a11y,
                shell = shell,
                display = agentDisplay,
                clipHeld = { recovery.markClipHeld(it) },
                clipLock = app.resources.clipboard,
                checkOtherTasks = true,
                reserve = { pkg -> app.resources.reserveApp(pkg, runKey, taskActive, stillUsed(pkg, null)) },
                reserveCopy = { pkg, user -> app.resources.reserveApp(claimKey(pkg, user), runKey, taskActive, stillUsed(pkg, user)) },
                appFree = { pkg, user -> app.resources.appFree(claimKey(pkg, user), runKey, taskActive, stillUsed(pkg, user)) },
                awaitApp = { pkg, user, timeoutMs, free ->
                    app.resources.awaitApp(claimKey(pkg, user), runKey, timeoutMs, taskActive, free)
                },
                clipSaved = { original, ours -> recovery.saveClip(original, ours) },
                typing = { pkg ->
                    val p = if (waitProfileProvider().instantTyping) OperatingPace.FAST else pacePlan().of(pkg)
                    p.typing()
                },
                beforeOpen = muter::beforeOpen,
                notOpened = muter::notOpened,
                appAccess = app.appAccess,
                waitProfile = waitProfileProvider,
            )

            sideScope.launch(Dispatchers.IO) {
                screen.target.collectLatest { t ->
                    movedAway.value = false
                    if (t != null) {
                        appShown.value = true
                        _foregroundPackage.value = t.packageName
                        _foregroundApp.value = ConfirmationNotifier.appLabel(app, t.packageName)
                        val copyUser = t.userId.takeIf { it >= 0 && it != AppCopyReader.myUser }
                        muter.opened(t.packageName, copyUser)
                        val displayId = agentDisplay.current?.displayId
                        if (displayId != null && t.taskId >= 0) {
                            deviceWatcher.taskMovedAway(displayId, t.taskId).collect {
                                movedAway.value = true
                            }
                        }
                    }
                }
            }
            sideScope.launch(Dispatchers.IO) {
                screen.foregroundApps().collect { t ->
                    appShown.value = true
                    _foregroundPackage.value = t.packageName
                    _foregroundApp.value = ConfirmationNotifier.appLabel(app, t.packageName)
                    val copyUser = t.userId.takeIf { it >= 0 && it != AppCopyReader.myUser }
                    muter.opened(t.packageName, copyUser)
                    delay(FOREGROUND_GAP_MS)
                }
            }

            val lazyScreen = LazyPhoneScreen(
                metrics.widthPixels,
                metrics.heightPixels,
                listApps = { withContext(Dispatchers.IO) { launchableApps(app.packageManager, app.packageName) } },
                appCopies = {
                    withContext(Dispatchers.IO) {
                        runCatching { AppCopyReader.read(shell, launchableApps(app.packageManager, app.packageName)) }.getOrNull().orEmpty()
                    }
                },
            ) { screen }

            val accessGuard = AppAccessScreenPort(
                lazyScreen,
                app.appAccess,
                packages = { screen.accessPackages() },
                protectedUi = { app.appAccess.sensitiveUi },
                ownPackage = app.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() },
            ).also { accessGuardRef = it }

            val privateScreen = SmsPrivateScreen(accessGuard).also { smsPrivacy = it }

            val speedFlags = FeatureFlags.get(app).speedFlags()
            val memoryRuntime = MemoryRuntime.of(app)
            val legacy = legacyNames()
            val userNotes = withContext(Dispatchers.IO) {
                runCatching { KnowledgeStore.loadNotes(File(app.filesDir, "personal")) }.getOrDefault(emptyMap())
            }
            val learnedNotes = withContext(Dispatchers.IO) {
                runCatching {
                    memoryRuntime.appKnowledge().notes().mapValues { (_, note) ->
                        note.copy(text = legacy.rewrite(note.text))
                    }
                }.getOrDefault(emptyMap())
            }
            val sessionKnowledge = KnowledgeStore(bundledEntries, userNotes, learnedNotes)
            val sessionCatalog = GuideCatalog.withPersonal(
                bundledCatalog,
                memoryRuntime.skills(),
                legacy::rewrite,
                plugins = app.plugins.skillsSnapshot(),
            )
            val modelSession = ModelSession.of(PersonalAgent.MAIN_CONVERSATION_ID)
            val visionService = app.tasks.visionModelFactory(app, modelSession)?.let { VisionService(it, BitmapImageOps) }
            val routeStore = FileRouteStore.shared(app)
            val turnEventsLock = Any()
            val turnEventsBuffer = ArrayList<RunEvent>(64)
            val turnSink = object : RunSink {
                override fun event(e: RunEvent) {
                    synchronized(turnEventsLock) {
                        if (turnEventsBuffer.size >= MAX_TURN_EVENTS) {
                            turnEventsBuffer.removeAt(0)
                        }
                        turnEventsBuffer += e
                    }
                }
                override fun media(mediaId: Int, png: ByteArray) = Unit
            }

            val runApproval = app.approvalControl.start(alwaysAsk = true).also { approval = it }
            val confirmations = runApproval.gate() ?: ConfirmationGate()
            val spec = RunSpec(
                id = RunId(runKey),
                goal = "Mika Live direct phone control",
                threadId = PersonalAgent.MAIN_CONVERSATION_ID,
                provenance = RunProvenance(RunOrigin.Ui, Attendance.Present),
                capabilities = setOf("phone"),
            )
            val dispatcher = toolDispatcher(
                screen = privateScreen,
                visionEnabled = true,
                skills = sessionCatalog,
                vision = visionService,
                stableRefs = true,
                inlineScreen = true,
                adaptiveVision = true,
                deepLinks = speedFlags.deepLinks,
                appPacing = {
                    if (waitProfileProvider().turbo) AppPacing.Off
                    else pacePlan().appPacing()
                },
                appTempo = { pkg -> pacePlan().of(pkg).tempo },
                appCapped = { pkg -> pacePlan().capped(pkg) },
                appIdleScroll = { null },
                imageOps = BitmapImageOps,
                runSpec = spec,
                conversationId = PersonalAgent.MAIN_CONVERSATION_ID,
            ).also {
                it.captureTargets = speedFlags.replay
            }

            lateinit var warmRef: WarmSession
            warmRef = WarmSession(
                key = runKey,
                sideScope = sideScope,
                resources = resources,
                connection = conn,
                shell = shell,
                watcher = deviceWatcher,
                shellUp = shellUp,
                movedAway = movedAway,
                display = agentDisplay,
                screen = screen,
                afterTask = lazyScreen.afterTask,
                accessGuard = accessGuard,
                privateScreen = privateScreen,
                dispatcher = dispatcher,
                knowledge = sessionKnowledge,
                catalog = sessionCatalog,
                visionService = visionService,
                routeStore = routeStore,
                turnSink = turnSink,
                turnEventsBuffer = turnEventsBuffer,
                turnEventsLock = turnEventsLock,
                approval = runApproval,
                confirmations = confirmations,
                pacing = OutwardPacing.human(),
                mute = audio,
                recorder = ActionRecorder(turnSink),
                appShown = appShown,
                syncPreview = ::syncPreviewNow,
                passGate = { passDirectGate(warmRef) },
                selfPackage = app.packageName,
                fallbackPackageProvider = { _foregroundPackage.value },
            )
            session = warmRef
            return warmRef
        } catch (t: Throwable) {
            sideScope.cancel()
            watcher?.close()
            smsPrivacy?.close()
            _previewFrames.value = null
            _pipInput.value = null
            _foregroundApp.value = null
            _foregroundPackage.value = null
            approval?.let { app.approvalControl.end(it) }
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    if (recoveryBegun) {
                        try {
                            mute?.restoreAll()
                        } finally {
                            recovery.endRun(runKey)
                        }
                    }
                } finally {
                    resources.close()
                }
            }
            if (registeredOwner) app.resources.unregisterExternalOwner(runKey)
            if (connection != null) phoneServices.sharedShell.release()
            throw t
        }
    }

    private suspend fun closeSessionLocked() {
        val current = session ?: return
        session = null
        _pendingConfirmation.value = null
        current.sideScope.cancel()
        current.watcher.close()
        current.privateScreen.close()
        _previewFrames.value = null
        _pipInput.value = null
        _foregroundApp.value = null
        _foregroundPackage.value = null
        app.approvalControl.end(current.approval)
        val recovery = TaskRecovery.of(app)
        val phoneServices = PhoneServices.of(app)
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                if (current.shell.asBinder().isBinderAlive) {
                    current.display.current?.let { d ->
                        runCatching { AppsAfterTaskEnd.finish(current.shell, d.displayId, current.afterTask) }
                    }
                }
                try {
                    current.mute.restoreAll()
                } finally {
                    recovery.endRun(current.key)
                }
            } finally {
                current.resources.close()
                app.resources.unregisterExternalOwner(current.key)
            }
        }
        phoneServices.sharedShell.release()
    }

    private suspend fun capturePreviewJpegLocked(warm: WarmSession): ByteArray? {
        if (warm.privateScreen.imagesPrivate) return null
        val allowed = try {
            warm.accessGuard.checkAppAccess()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: AppAccessDenied) {
            false
        }
        if (!allowed) return null
        val bmp = warm.screen.servablePreviewBitmap() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { compressBitmapJpeg(bmp) }.getOrNull()
        }
    }

    private fun buildAppMuter(shell: com.wangzan101.mika.shell.IMikaShell, audio: AudioMute): AppMuter {
        val pm = app.packageManager
        val own = app.packageName
        fun isSystem(pkg: String) = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrElse { runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false) }
        return AppMuter(
            audio,
            enabled = { FeatureFlags.get(app).muteTarget() },
            mutable = { pkg -> pkg != own && (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null || !isSystem(pkg)) },
            onMainScreen = { pkg, user -> runCatching { shell.visibleOnMainScreen(pkg, user ?: AppCopyReader.myUser) }.getOrDefault(true) },
            failed = {},
        )
    }

    @Suppress("DEPRECATION")
    private fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    private fun failEarly(startedMs: Long, total: Int, code: String, message: String): DirectActResult {
        val elapsed = SystemClock.elapsedRealtime() - startedMs
        return DirectActResult(
            payload = buildJsonObject {
                put("ok", false)
                put("error", code)
                put("message", message)
                put("elapsed_ms", elapsed)
            },
            elapsedMs = elapsed,
            completedSteps = 0,
            totalSteps = total,
            ok = false,
        )
    }

    private fun statusEarly(startedMs: Long, total: Int, status: String, message: String): DirectActResult {
        val elapsed = SystemClock.elapsedRealtime() - startedMs
        return DirectActResult(
            payload = buildJsonObject {
                put("ok", false)
                put("status", status)
                put("message", message)
                put("elapsed_ms", elapsed)
            },
            elapsedMs = elapsed,
            completedSteps = 0,
            totalSteps = total,
            ok = false,
        )
    }

    private fun stepRow(index: Int, op: String, ok: Boolean, error: String?, executed: Boolean): JsonObject = buildJsonObject {
        put("n", index + 1)
        put("op", op)
        put("ok", ok)
        if (!executed) put("executed", false)
        error?.let { put("error", it) }
    }

    private class DirectPipInput(
        private val shell: com.wangzan101.mika.shell.IMikaShell,
        private val display: AgentDisplay,
        private val isLocked: () -> Boolean,
        private val onMotion: (Int) -> Unit,
        private val onDiscrete: () -> Unit,
    ) : PipInput {
        override fun onInteractiveChanged(interactive: Boolean) {
            display.setInteractiveQuality(interactive)
        }

        override fun injectTouch(
            action: Int,
            downTime: Long,
            eventTime: Long,
            pointerIds: IntArray,
            normXs: FloatArray,
            normYs: FloatArray,
        ): Boolean {
            val masked = action and MotionEvent.ACTION_MASK
            val isReleaseOrCancel = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL
            if (isLocked() && !isReleaseOrCancel) return false
            if (pointerIds.isEmpty() || pointerIds.size != normXs.size || pointerIds.size != normYs.size) {
                if (isReleaseOrCancel) onMotion(masked)
                return false
            }
            val n = pointerIds.size
            val px = FloatArray(n) { scaleTouchToDisplay(normXs[it], display.width) }
            val py = FloatArray(n) { scaleTouchToDisplay(normYs[it], display.height) }
            val ok = runCatching {
                shell.injectTouchEvent(display.displayId, action, downTime, eventTime, pointerIds, px, py)
            }.getOrDefault(false)
            if (ok || isReleaseOrCancel) {
                onMotion(masked)
            }
            return ok
        }

        override suspend fun tap(fx: Float, fy: Float) {
            if (isLocked()) return
            withContext(Dispatchers.IO) {
                runCatching { shell.injectTap(display.displayId, (fx * display.width).toInt(), (fy * display.height).toInt()) }
            }
            onDiscrete()
        }

        override suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
            if (isLocked()) return
            if (points.size < 2) {
                if (points.size == 1) tap(points[0].first, points[0].second)
                return
            }
            withContext(Dispatchers.IO) {
                val n = points.size
                runCatching {
                    shell.injectStroke(
                        display.displayId,
                        IntArray(n) { (points[it].first * display.width).toInt() },
                        IntArray(n) { (points[it].second * display.height).toInt() },
                        LongArray(n) { durationMs * it / (n - 1) },
                    )
                }
            }
            onDiscrete()
        }

        override suspend fun back() {
            if (isLocked()) return
            withContext(Dispatchers.IO) {
                runCatching { shell.injectKey(display.displayId, android.view.KeyEvent.KEYCODE_BACK) }
            }
            onDiscrete()
        }
    }

    companion object {
        private const val TAG = "LiveDirectPhone"
        private const val MAX_DIRECT_COMMANDS = 12
        private const val MAX_RECENT_STEPS = 64
        private const val SHELL_WAIT_MS = 6_000L
        private const val A11Y_WAIT_MS = 3_000L
        private const val FOREGROUND_GAP_MS = 250L
        private const val IDLE_RELEASE_MS = 180_000L
        private const val TOUCH_SETTLE_HOLD_MS = 350L
        private const val USER_TOUCH_WAIT_MS = 2_500L
        private const val DEFAULT_SCROLL_CENTER = 500

        /**
         * 将直控 `scroll` 指令解析为底层 `phone_scroll` 工具参数：
         * 显式传入屏幕中心归一化坐标 `(500, 500)`（除非调用方手动指定了 `x`/`y`），强制走屏幕中轴方向手势滑动，
         * 避免无坐标默认分支命中顶部横向 TabLayout / Banner `ViewPager` 的 `ACTION_SCROLL_FORWARD` 导致“说向下滚却左右翻页”。
         */
        internal fun buildDirectScrollStep(command: JsonObject): ResolvedToolStep {
            val dir = command.string("direction")
            val amount = command.integer("amount") ?: 3
            val cx = command.integer("x") ?: DEFAULT_SCROLL_CENTER
            val cy = command.integer("y") ?: DEFAULT_SCROLL_CENTER
            return when {
                dir !in setOf("up", "down", "left", "right") -> ResolvedToolStep(error = "invalid_direction")
                amount !in 1..20 -> ResolvedToolStep(error = "invalid_amount")
                cx !in 0..999 || cy !in 0..999 -> ResolvedToolStep(error = "invalid_coordinates")
                else -> ResolvedToolStep(
                    "phone_scroll",
                    buildJsonObject {
                        put("direction", dir!!)
                        put("amount", amount)
                        put("x", cx)
                        put("y", cy)
                    },
                )
            }
        }
        internal fun buildDirectOpenLinkStep(command: JsonObject): ResolvedToolStep {
            val action = command.string("settingsAction") ?: command.string("action")
            val uri = command.string("uri")
            val pkg = if (action == null) command.string("packageName") ?: command.string("package") else null
            val policyError = LinkPolicy.check(action = action, uri = uri, pkg = pkg)
            if (policyError != null) {
                return ResolvedToolStep(error = "invalid_link")
            }
            return ResolvedToolStep(
                toolName = "phone_open_link",
                toolArgs = buildJsonObject {
                    uri?.let { put("uri", it) }
                    action?.let { put("action", it) }
                    pkg?.let { put("package", it) }
                },
            )
        }

        internal const val MAX_ELEMENTS_IN_SUMMARY = 72
        internal const val MAX_SLIM_ELEMENTS_IN_SUMMARY = 24
        internal const val MAX_VISIBLE_TEXT_IN_SUMMARY = 40
        internal const val MAX_SLIM_VISIBLE_TEXT_IN_SUMMARY = 16
        internal const val MAX_INLINE_APP_GUIDE_CHARS = 320
        private const val LIVE_ROUTE_MATCH_TIMEOUT_MS = 3_000L
        private const val ROUTE_MISSES_TO_DISABLE = 2
        private const val MAX_TURN_EVENTS = 160
        private const val MAX_PREVIEW_LONG_EDGE = 720
        private const val PREVIEW_JPEG_QUALITY = 68

        private val SLOT_TOKEN_RE = Regex("\\{(\\d+)\\}")

        private val SUPPORTED_OPS = setOf(
            "launch", "open_link", "tap", "type", "clear", "scroll", "swipe", "key", "back", "wait", "assert", "observe",
        )

        private val bundledEntries by lazy {
            runCatching { KnowledgeStore.loadBundled() }.getOrElse { emptyList() }
        }

        private val bundledCatalog: GuideCatalog by lazy {
            runCatching { GuideCatalog.loadBundled() }.getOrElse { error("Bundled GuideCatalog unavailable") }
        }

        private val bundledKnowledge: KnowledgeStore by lazy {
            KnowledgeStore(bundledEntries)
        }

        private val COMMON_APP_ALIASES: Map<String, List<String>> = mapOf(
            "微信" to listOf("com.tencent.mm"),
            "wechat" to listOf("com.tencent.mm"),
            "qq" to listOf("com.tencent.mobileqq", "com.tencent.tim"),
            "抖音" to listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"),
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "美团" to listOf("com.sankuai.meituan"),
            "淘宝" to listOf("com.taobao.taobao"),
            "支付宝" to listOf("com.eg.android.AlipayGphone"),
            "alipay" to listOf("com.eg.android.AlipayGphone"),
            "小红书" to listOf("com.xingin.xhs"),
            "高德" to listOf("com.autonavi.minimap"),
            "高德地图" to listOf("com.autonavi.minimap"),
            "百度地图" to listOf("com.baidu.BaiduMap"),
            "b站" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
            "哔哩哔哩" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
            "bilibili" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
            "京东" to listOf("com.jingdong.app.mall"),
            "拼多多" to listOf("com.xunneng.pinduoduo", "com.xunmeng.pinduoduo"),
            "饿了么" to listOf("me.ele"),
            "网易云" to listOf("com.netease.cloudmusic"),
            "网易云音乐" to listOf("com.netease.cloudmusic"),
            "qq音乐" to listOf("com.tencent.qqmusic"),
            "飞书" to listOf("com.ss.android.lark"),
            "lark" to listOf("com.ss.android.lark", "com.larksuite.suite"),
            "钉钉" to listOf("com.alibaba.android.rimet"),
            "企业微信" to listOf("com.tencent.wework"),
            "携程" to listOf("ctrip.android.view"),
            "滴滴" to listOf("com.sdu.didi.psnger"),
            "知乎" to listOf("com.zhihu.android"),
            "微博" to listOf("com.sina.weibo"),
            "闲鱼" to listOf("com.taobao.idlefish"),
            "豆包" to listOf("com.larus.nova"),
            "chrome" to listOf("com.android.chrome"),
            "谷歌浏览器" to listOf("com.android.chrome"),
            "设置" to listOf("com.android.settings"),
            "系统设置" to listOf("com.android.settings"),
        )

        /**
         * 将模型给出的应用名称或包名解析为设备上真实可启动的包名：
         * 1. 精确包名匹配已安装列表
         * 2. 内置 App 知识库 [KnowledgeStore.packageFor] 映射（仅返回已安装项）
         * 3. 常见中英文别名兜底（仅返回已安装项）
         * 4. 应用显示名称精确匹配（忽略大小写与空白）
         * 5. 标准包名格式匹配
         * 6. 应用显示名称包含匹配（优先匹配名字最短的正式应用，例如「微信」优先于「企业微信」）
         */
        internal fun resolveAppPackage(query: String, installed: List<Pair<String, String>>): String? {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return null
            installed.firstOrNull { it.first.equals(trimmed, ignoreCase = true) }?.let { return it.first }

            bundledKnowledge.packageFor(trimmed)?.let { knowledgePkg ->
                installed.firstOrNull { it.first.equals(knowledgePkg, ignoreCase = true) }?.let { return it.first }
            }

            val norm = normalizeAppKey(trimmed)
            COMMON_APP_ALIASES[norm]?.let { candidates ->
                candidates.firstOrNull { pkg -> installed.any { it.first.equals(pkg, ignoreCase = true) } }
                    ?.let { matched -> return installed.first { it.first.equals(matched, ignoreCase = true) }.first }
            }

            installed.firstOrNull { normalizeAppKey(it.second) == norm }?.let { return it.first }

            if (PACKAGE_NAME_RE.matches(trimmed) && '.' in trimmed) {
                return trimmed
            }

            if (norm.length >= 2) {
                val containsMatches = installed.filter { (_, label) ->
                    val labelNorm = normalizeAppKey(label)
                    labelNorm.isNotEmpty() && (labelNorm.contains(norm) || (labelNorm.length >= 2 && norm.contains(labelNorm)))
                }
                if (containsMatches.isNotEmpty()) {
                    return containsMatches.minByOrNull { it.second.length }?.first
                }
            }
            return null
        }

        internal fun suggestApps(query: String, installed: List<Pair<String, String>>, limit: Int = 6): List<Pair<String, String>> {
            val norm = normalizeAppKey(query)
            val filtered = installed.filter { (pkg, label) ->
                val l = normalizeAppKey(label)
                (norm.isNotEmpty() && l.contains(norm.take(1))) || pkg.contains(norm, ignoreCase = true)
            }
            return (filtered.takeIf { it.isNotEmpty() } ?: installed).take(limit)
        }

        private fun normalizeAppKey(raw: String): String =
            raw.trim().lowercase().replace(" ", "").replace("　", "")

        internal fun compactScreen(screen: JsonObject, slimSuccess: Boolean = false): JsonObject = buildJsonObject {
            val elemCap = if (slimSuccess) MAX_SLIM_ELEMENTS_IN_SUMMARY else MAX_ELEMENTS_IN_SUMMARY
            val textCap = if (slimSuccess) MAX_SLIM_VISIBLE_TEXT_IN_SUMMARY else MAX_VISIBLE_TEXT_IN_SUMMARY
            (screen["currentApp"] as? JsonPrimitive)?.contentOrNull?.let { put("currentApp", it) }
            (screen["protected"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) put("protected", true) }
            (screen["readable"] as? JsonPrimitive)?.booleanOrNull?.let { put("readable", it) }
            (screen["imageReason"] as? JsonPrimitive)?.contentOrNull?.let { put("imageReason", it) }
            (screen["hint"] as? JsonPrimitive)?.contentOrNull?.let { put("hint", it) }
            (screen["elements"] as? JsonArray)?.let { elements ->
                put("elementCount", elements.size)
                put("elements", JsonArray(elements.take(elemCap)))
                if (elements.size > elemCap) put("elementsTruncated", true)
            }
            (screen["visibleText"] as? JsonArray)?.let { lines ->
                if (lines.isNotEmpty()) {
                    put("visibleText", JsonArray(lines.take(textCap)))
                }
            }
        }

        internal fun shouldAttachVisualFrame(
            stopReason: String?,
            rawScreen: JsonObject?,
            suppressDenseSuccessFrame: Boolean = false,
        ): Boolean {
            if (rawScreen?.get("protected") == JsonPrimitive(true)) return false
            if (
                stopReason == "screen_needs_review" ||
                stopReason == "target_not_found" ||
                stopReason == "ambiguous_target"
            ) {
                return true
            }
            if (rawScreen?.get("imageReason") != null) return true
            val elements = rawScreen?.get("elements") as? JsonArray ?: return false
            if (elements.size <= 2) return true
            if (suppressDenseSuccessFrame && stopReason == null) return false
            return elements.size > MAX_ELEMENTS_IN_SUMMARY
        }

        internal fun compactGuideSnippet(rawBody: String, maxChars: Int = MAX_INLINE_APP_GUIDE_CHARS): String {
            val compact = rawBody.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("```") }
                .joinToString(" | ")
            return if (compact.length <= maxChars) compact else compact.take(maxChars - 1).trimEnd() + "…"
        }

        private fun fillReplaySlots(template: String, slots: List<String>): String? {
            var ok = true
            val replaced = SLOT_TOKEN_RE.replace(template) { m ->
                val idx = (m.groupValues[1].toIntOrNull() ?: 0) - 1
                if (idx in slots.indices) slots[idx] else {
                    ok = false
                    ""
                }
            }
            return if (ok) replaced else null
        }

        private fun compressBitmapJpeg(bitmap: Bitmap): ByteArray {
            val w = bitmap.width
            val h = bitmap.height
            val longEdge = maxOf(w, h)
            val scaled = if (longEdge > MAX_PREVIEW_LONG_EDGE && longEdge > 0) {
                val ratio = MAX_PREVIEW_LONG_EDGE.toFloat() / longEdge.toFloat()
                val nw = (w * ratio).toInt().coerceAtLeast(1)
                val nh = (h * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, nw, nh, true)
            } else {
                bitmap
            }
            return try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, out)
                out.toByteArray()
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.integer(key: String): Int? =
            (this[key] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.boolean(key: String): Boolean? = when (this[key]) {
            JsonPrimitive(true) -> true
            JsonPrimitive(false) -> false
            else -> null
        }
    }
}
