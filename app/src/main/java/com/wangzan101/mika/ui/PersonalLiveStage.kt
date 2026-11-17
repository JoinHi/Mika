package com.wangzan101.mika.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.personal.LiveScreenStageState
import com.wangzan101.mika.personal.displayDetail
import com.wangzan101.mika.personal.live.LiveAudioLevels
import com.wangzan101.mika.personal.live.LiveSessionPhase
import com.wangzan101.mika.personal.live.PersonalLiveUiState
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow

private enum class DynamicPillMode {
    Connecting,
    Suspended,
    NeedsAttention,
    Executing,
    Speaking,
    Thinking,
    ListeningReady,
}

/**
 * 语音模式专属顶栏（方案一：灵动岛单胶囊顶栏，高度 48dp）:
 * 左侧保留侧边栏入口，中央自适应灵动胶囊四态合一：
 * 1) 等待指令：`正在聆听 · 请下指令`（绿点呼吸）
 * 2) 思考检索：`Mika 思考中…` / `正在联网搜索…` / `正在深度思考…`
 * 3) 执行操控：`美团 · 正在点击「搜索框」(2/3)` 或后台任务进度（点击展开任务抽屉）
 * 4) 开口回应：`Mika 说话中`（动态声波柱）
 */
@Composable
internal fun PersonalLiveDynamicTopBar(
    liveState: PersonalLiveUiState,
    stage: LiveScreenStageState,
    activeTasks: List<PersonalTask>,
    hasPendingConfirmation: Boolean,
    reduceMotion: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenTask: (PersonalTask) -> Unit,
    audioLevels: StateFlow<LiveAudioLevels>? = null,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val primaryTask = activeTasks.firstOrNull { it.conversationId == stage.conversationId } ?: activeTasks.firstOrNull()
    val hasActiveStageFrame = stage.frame != null && !stage.frame.isRecycled && !stage.isRetainedSnapshot

    val mode = when {
        liveState.phase == LiveSessionPhase.Connecting || liveState.phase == LiveSessionPhase.Reconnecting -> DynamicPillMode.Connecting
        liveState.phase == LiveSessionPhase.SuspendedByCall -> DynamicPillMode.Suspended
        stage.needsAttention || hasPendingConfirmation -> DynamicPillMode.NeedsAttention
        !liveState.activeActivityText.isNullOrBlank() || primaryTask != null || stage.isPreparing -> DynamicPillMode.Executing
        liveState.speaking -> DynamicPillMode.Speaking
        liveState.thinking -> DynamicPillMode.Thinking
        else -> DynamicPillMode.ListeningReady
    }

    val statusText = when (mode) {
        DynamicPillMode.Connecting -> stringResource(
            if (liveState.phase == LiveSessionPhase.Reconnecting) R.string.live_status_reconnecting else R.string.live_status_connecting,
        )
        DynamicPillMode.Suspended -> stringResource(R.string.live_status_suspended_call)
        DynamicPillMode.NeedsAttention -> stringResource(R.string.live_status_needs_attention)
        DynamicPillMode.Executing -> when {
            !liveState.activeActivityText.isNullOrBlank() -> liveState.activeActivityText
            primaryTask != null -> primaryTask.displayDetail(context).ifBlank { primaryTask.title }
            else -> stringResource(R.string.live_stage_preparing)
        }
        DynamicPillMode.Speaking -> {
            val appPrefix = stage.appLabel?.takeIf { it.isNotBlank() && hasActiveStageFrame }?.let { "$it · " }.orEmpty()
            "$appPrefix${stringResource(R.string.live_status_speaking)}"
        }
        DynamicPillMode.Thinking -> stringResource(R.string.live_status_thinking)
        DynamicPillMode.ListeningReady -> {
            val readyText = stringResource(
                if (liveState.muted) R.string.live_status_muted else R.string.live_status_listening_ready,
            )
            val appPrefix = stage.appLabel?.takeIf { it.isNotBlank() && hasActiveStageFrame }?.let { "$it · " }.orEmpty()
            "$appPrefix$readyText"
        }
    }

    val (pillBg, pillBorder, textColor) = when (mode) {
        DynamicPillMode.NeedsAttention, DynamicPillMode.Suspended -> Triple(c.warnSoft, c.warn.copy(alpha = 0.42f), c.warn)
        DynamicPillMode.Executing, DynamicPillMode.Thinking, DynamicPillMode.Connecting -> Triple(
            c.accentSoft.copy(alpha = 0.72f),
            c.accent.copy(alpha = 0.32f),
            c.ink,
        )
        DynamicPillMode.Speaking -> Triple(c.okSoft, c.ok.copy(alpha = 0.38f), c.ink)
        DynamicPillMode.ListeningReady -> if (liveState.muted) {
            Triple(c.warnSoft, c.warn.copy(alpha = 0.35f), c.warn)
        } else {
            Triple(c.surface, c.edge, c.ink)
        }
    }

    val dotAlpha = if (mode == DynamicPillMode.ListeningReady && !liveState.muted && !reduceMotion) {
        rememberInfiniteTransition(label = "listenPulse").animateFloat(
            1f,
            0.38f,
            infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "listenDotAlpha",
        ).value
    } else {
        1f
    }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("personal-live-call-topbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(MikaIcons.Menu, stringResource(R.string.personal_open_sidebar), onOpenDrawer)

        Box(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .height(34.dp)
                    .widthIn(max = 300.dp)
                    .floatShadow(c, MikaShapes.Pill)
                    .background(pillBg, MikaShapes.Pill)
                    .border(0.5.dp, pillBorder, MikaShapes.Pill)
                    .clip(MikaShapes.Pill)
                    .testTag("personal-live-dynamic-pill")
                    .clickable(role = Role.Button) {
                        if (primaryTask != null) onOpenTask(primaryTask) else onOpenActivity()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when (mode) {
                    DynamicPillMode.Connecting, DynamicPillMode.Executing, DynamicPillMode.Thinking -> {
                        CircularProgressIndicator(
                            Modifier.size(13.dp),
                            strokeWidth = 1.8.dp,
                            color = c.accent,
                        )
                    }
                    DynamicPillMode.NeedsAttention -> {
                        Icon(MikaIcons.Info, null, Modifier.size(14.dp), tint = c.warn)
                    }
                    DynamicPillMode.Suspended -> {
                        Box(Modifier.size(7.dp).background(c.warn, CircleShape))
                    }
                    DynamicPillMode.Speaking -> {
                        val levels = audioLevels?.collectAsState()?.value
                        MiniPillWave(
                            level = levels?.outputLevel ?: liveState.outputLevel,
                            reduceMotion = reduceMotion,
                            color = c.ok,
                        )
                    }
                    DynamicPillMode.ListeningReady -> {
                        val dotTint = if (liveState.muted) c.warn else c.ok.copy(alpha = dotAlpha)
                        Box(Modifier.size(8.dp).background(dotTint, CircleShape))
                    }
                }

                Text(
                    statusText,
                    Modifier.weight(1f, fill = false),
                    style = MikaType.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (primaryTask != null) {
                    DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(12.dp), tint = c.ink3)
                }
            }
        }

        // Balance spacer matching the left menu button width so the dynamic pill stays centered.
        Spacer(Modifier.size(40.dp))
    }
}

@Composable
private fun MiniPillWave(level: Float, reduceMotion: Boolean, color: Color) {
    val loud by animateFloatAsState(
        level.coerceIn(0f, 1f),
        if (reduceMotion) snap() else tween(100),
        label = "pillWaveLevel",
    )
    val phase = if (reduceMotion) 0f else rememberInfiniteTransition(label = "pillWave").animateFloat(
        0f,
        (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Restart),
        label = "pillWavePhase",
    ).value
    Row(
        Modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { i ->
            val swing = if (reduceMotion) 1f else 0.55f + 0.45f * abs(sin(phase + i * 1.1f))
            val h = 4f + 9f * (0.35f + 0.65f * loud) * swing
            Box(Modifier.width(2.4.dp).height(h.dp).background(color, RoundedCornerShape(1.5.dp)))
        }
    }
}

/**
 * 语音模式中央全幅实时虚拟屏舞台（占屏幕绝大部分面积，无悬浮字幕遮挡）:
 * - 有虚拟屏画面时按真实长宽比无裁切铺满，支持直接 1:1 触控注入；
 * - 尚未拉起应用时显示纯净的 Mika 声波呼吸球待机态。
 */
@Composable
internal fun PersonalLiveScreenStage(
    stage: LiveScreenStageState,
    liveState: PersonalLiveUiState,
    activeTasks: List<PersonalTask>,
    touchOperate: Boolean,
    reduceMotion: Boolean,
    onWakeRetainedStage: () -> Unit = {},
    audioLevels: StateFlow<LiveAudioLevels>? = null,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val frame = stage.frame?.takeUnless { it.isRecycled }
    val primaryTask = activeTasks.firstOrNull { it.conversationId == stage.conversationId } ?: activeTasks.firstOrNull()

    val breath = if (stage.needsAttention && !reduceMotion) {
        rememberInfiniteTransition(label = "stageAttention").animateFloat(
            1f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "stageAttention",
        ).value
    } else 1f

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("personal-live-screen-stage"),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            val shape = RoundedCornerShape(18.dp)
            val (fitW, fitH) = fitStageFrame(frame.width, frame.height, maxWidth, maxHeight)
            val borderColor = when {
                stage.needsAttention -> c.warnSolid.copy(alpha = breath)
                touchOperate && stage.pipInput != null -> c.accent.copy(alpha = 0.55f)
                stage.isRetainedSnapshot -> c.edge
                else -> c.ok.copy(alpha = 0.42f)
            }
            val borderWidth = if (stage.needsAttention || (touchOperate && stage.pipInput != null)) 1.6.dp else 1.dp

            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val activeInput = stage.pipInput.takeIf { touchOperate }
            LaunchedEffect(touchOperate, activeInput != null) {
                if (touchOperate && activeInput != null) {
                    scale = 1f
                    offset = Offset.Zero
                }
            }
            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                if (activeInput == null) {
                    val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                    scale = nextScale
                    offset = if (nextScale <= 1.01f) Offset.Zero else offset + panChange
                }
            }
            val canWakeSnapshot = stage.isRetainedSnapshot && touchOperate
            val renderScale = if (activeInput != null) 1f else scale
            val renderOffset = if (activeInput != null) Offset.Zero else offset
            val imageBitmap = remember(frame) { frame.asImageBitmap() }

            Box(
                Modifier.size(fitW, fitH)
                    .clip(shape)
                    .background(c.surface, shape)
                    .border(borderWidth, borderColor, shape)
                    .clipToBounds()
                    .testTag("personal-live-screen-viewport")
                    .then(
                        if (activeInput != null) {
                            Modifier.virtualScreenTouch(activeInput)
                        } else {
                            Modifier
                                .transformable(transformState)
                                .pointerInput(canWakeSnapshot) {
                                    detectTapGestures(
                                        onTap = if (canWakeSnapshot) {
                                            { onWakeRetainedStage() }
                                        } else {
                                            null
                                        },
                                        onDoubleTap = {
                                            if (scale > 1.05f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                            } else {
                                                scale = 2.2f
                                            }
                                        },
                                    )
                                }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = stringResource(R.string.personal_replay_live_screen),
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = renderScale,
                        scaleY = renderScale,
                        translationX = renderOffset.x,
                        translationY = renderOffset.y,
                    ),
                    contentScale = ContentScale.Fit,
                )
                if (canWakeSnapshot) {
                    Text(
                        text = stringResource(R.string.live_stage_snapshot_wake),
                        style = MikaType.Caption,
                        color = c.ink,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .background(c.surface.copy(alpha = 0.88f), CircleShape)
                            .border(0.5.dp, c.edge, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        } else {
            val shape = RoundedCornerShape(24.dp)
            val isPreparing = stage.isPreparing || primaryTask != null
            Box(
                Modifier.fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .background(c.surface, shape)
                    .border(0.5.dp, c.edge, shape)
                    .clip(shape)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 320.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isPreparing) {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.6.dp, color = c.accent)
                        Text(
                            stringResource(R.string.live_stage_preparing),
                            style = MikaType.BodyStrong,
                            color = c.ink,
                            textAlign = TextAlign.Center,
                        )
                        val detail = primaryTask?.let { it.displayDetail(context).ifBlank { it.title } }.orEmpty()
                        if (detail.isNotBlank()) {
                            Text(
                                detail,
                                style = MikaType.Caption,
                                color = c.ink2,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        val levels = audioLevels?.collectAsState()?.value
                        val inLvl = levels?.inputLevel ?: liveState.inputLevel
                        val outLvl = levels?.outputLevel ?: liveState.outputLevel
                        MikaLiveBreathingOrb(
                            audioLevel = maxOf(inLvl, outLvl),
                            speaking = liveState.speaking,
                            reduceMotion = reduceMotion,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.live_stage_idle_title),
                            style = MikaType.BodyStrong,
                            color = c.ink,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.live_stage_idle_body),
                            style = MikaType.Caption,
                            color = c.ink2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MikaLiveBreathingOrb(
    audioLevel: Float,
    speaking: Boolean,
    reduceMotion: Boolean,
) {
    val c = MikaTheme.colors
    val smoothLevel by animateFloatAsState(
        audioLevel.coerceIn(0f, 1f),
        if (reduceMotion) snap() else tween(140),
        label = "orbAudio",
    )
    val idleBreath = if (reduceMotion) 0f else rememberInfiniteTransition(label = "orbBreath").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "orbBreathVal",
    ).value
    val ringTint = if (speaking) c.ok else c.accent
    val outerScale = 1f + 0.08f * idleBreath + 0.24f * smoothLevel
    val innerScale = 1f + 0.04f * idleBreath + 0.14f * smoothLevel

    Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(104.dp)
                .graphicsLayer(scaleX = outerScale, scaleY = outerScale)
                .background(ringTint.copy(alpha = 0.09f + 0.12f * smoothLevel), CircleShape),
        )
        Box(
            Modifier.size(82.dp)
                .graphicsLayer(scaleX = innerScale, scaleY = innerScale)
                .background(ringTint.copy(alpha = 0.16f + 0.16f * smoothLevel), CircleShape),
        )
        Box(
            Modifier.size(64.dp)
                .floatShadow(c, CircleShape)
                .background(c.surface, CircleShape)
                .border(0.5.dp, c.edge, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MikaLogo(58.dp, corner = 29.dp)
        }
    }
}

internal fun fitStageFrame(frameW: Int, frameH: Int, maxW: Dp, maxH: Dp): Pair<Dp, Dp> {
    if (frameW <= 0 || frameH <= 0 || maxW <= 0.dp || maxH <= 0.dp) return maxW to maxH
    val aspect = frameW.toFloat() / frameH.toFloat()
    val byWidthH = maxW / aspect
    return if (byWidthH <= maxH) {
        maxW to byWidthH
    } else {
        (maxH * aspect) to maxH
    }
}
