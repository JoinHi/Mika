package com.wangzan101.mika.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.personal.LiveScreenStageState
import com.wangzan101.mika.personal.displayDetail
import com.wangzan101.mika.personal.live.PersonalLiveRuntime
import com.wangzan101.mika.personal.live.PersonalLiveUiState
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Mika Live 通话进行时的极简顶栏（高度压缩至 44dp）:
 * 让出全部纵向空间给中央全幅实时虚拟屏，并把触控/返回/对话切换收拢在顶栏，做到画面零遮挡。
 */
@Composable
internal fun PersonalLiveCallTopBar(
    live: PersonalLiveRuntime,
    liveState: PersonalLiveUiState,
    stage: LiveScreenStageState,
    activeTasks: List<PersonalTask>,
    showingChat: Boolean,
    touchOperate: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenTask: (PersonalTask) -> Unit,
    onToggleChat: () -> Unit,
    onToggleTouchOperate: () -> Unit,
    onOpenLiveSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primaryTask = activeTasks.firstOrNull { it.conversationId == stage.conversationId } ?: activeTasks.firstOrNull()
    val hasFrame = stage.frame != null && !stage.frame.isRecycled

    val statusTitle = when {
        !stage.appLabel.isNullOrBlank() -> stage.appLabel
        stage.webContent -> stringResource(R.string.live_stage_using_web)
        primaryTask != null -> primaryTask.displayDetail(context).ifBlank { primaryTask.title }
        else -> stringResource(R.string.live_capsule_title)
    }
    val dotColor = when {
        stage.needsAttention -> c.warn
        hasFrame && !stage.isRetainedSnapshot -> c.ok
        stage.isPreparing || primaryTask != null -> c.accent
        else -> c.ink3
    }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("personal-live-call-topbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BarIconButton(MikaIcons.Menu, stringResource(R.string.personal_open_sidebar), onOpenDrawer)

        Row(
            Modifier.weight(1f)
                .height(32.dp)
                .clip(MikaShapes.Pill)
                .background(c.fill, MikaShapes.Pill)
                .border(0.5.dp, c.edge, MikaShapes.Pill)
                .clickable(role = Role.Button) {
                    if (primaryTask != null) onOpenTask(primaryTask) else onOpenActivity()
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).background(dotColor, CircleShape))
            Text(
                statusTitle,
                Modifier.weight(1f, fill = false),
                style = MikaType.Caption.copy(fontWeight = FontWeight.SemiBold),
                color = if (stage.needsAttention) c.warn else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (primaryTask != null) {
                Icon(MikaIcons.Chevron, null, Modifier.size(12.dp), tint = c.ink3)
            }
        }

        if (!showingChat && hasFrame && stage.pipInput != null) {
            val backLabel = stringResource(R.string.live_stage_back)
            Box(
                Modifier.size(32.dp)
                    .background(c.fill, CircleShape)
                    .border(0.5.dp, c.edge, CircleShape)
                    .clip(CircleShape)
                    .testTag("live-stage-btn-back")
                    .clickable(role = Role.Button, onClickLabel = backLabel) {
                        scope.launch { stage.pipInput.back() }
                    }
                    .semantics { contentDescription = backLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Back, null, Modifier.size(16.dp), tint = c.ink)
            }

            TopBarModeChip(
                icon = if (touchOperate) MikaIcons.Hand else MikaIcons.Search,
                text = stringResource(if (touchOperate) R.string.live_stage_touch_on else R.string.live_stage_touch_off),
                highlighted = touchOperate,
                tag = "live-stage-btn-touch",
                onClick = onToggleTouchOperate,
            )
        }

        TopBarModeChip(
            icon = if (showingChat) MikaIcons.Screens else MikaIcons.Chats,
            text = stringResource(if (showingChat) R.string.live_stage_view_screen else R.string.live_stage_view_chat),
            highlighted = showingChat && hasFrame,
            liveDot = showingChat && hasFrame,
            tag = "live-stage-btn-toggle-view",
            onClick = onToggleChat,
        )

        PersonalLiveLauncherPill(
            live = live,
            state = liveState,
            onOpenSettings = onOpenLiveSettings,
        )
    }
}

@Composable
private fun TopBarModeChip(
    icon: ImageVector,
    text: String,
    highlighted: Boolean,
    tag: String,
    liveDot: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    val bg = if (highlighted) c.accentSoft else c.fill
    val fg = if (highlighted) c.accent else c.ink2
    val border = if (highlighted) c.accent.copy(alpha = 0.35f) else c.edge
    Row(
        Modifier.height(32.dp)
            .background(bg, CircleShape)
            .border(0.5.dp, border, CircleShape)
            .clip(CircleShape)
            .testTag(tag)
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (liveDot) {
            Box(Modifier.size(6.dp).background(c.ok, CircleShape))
        } else {
            Icon(icon, null, Modifier.size(14.dp), tint = fg)
        }
        Text(
            text,
            style = MikaType.Caption.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * 通话页中央全幅实时虚拟屏舞台:
 * - 占满顶栏与底部语音条之间的全部可用高度与宽度，按虚拟屏真实长宽比无裁切铺满;
 * - 默认支持双指捏合缩放(1x–5x)、拖动查看细小文字、双击快速放大/复位;
 * - 开启「可触控」时支持 1:1 坐标直接点击/滑动注入虚拟屏;
 * - 实时流式字幕半透明轻浮在底部，不挤压虚拟屏高度，说话时画面绝不跳动。
 */
@Composable
internal fun PersonalLiveScreenStage(
    stage: LiveScreenStageState,
    liveState: PersonalLiveUiState,
    activeTasks: List<PersonalTask>,
    touchOperate: Boolean,
    reduceMotion: Boolean,
    onWakeRetainedStage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val frame = stage.frame?.takeUnless { it.isRecycled }
    val hasSubtitles = liveState.liveUserTranscript.isNotBlank() || liveState.liveAssistantTranscript.isNotBlank()
    val primaryTask = activeTasks.firstOrNull { it.conversationId == stage.conversationId } ?: activeTasks.firstOrNull()

    val breath = if (stage.needsAttention && !reduceMotion) {
        rememberInfiniteTransition(label = "stageAttention").animateFloat(
            1f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "stageAttention",
        ).value
    } else 1f

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("personal-live-screen-stage"),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            val shape = RoundedCornerShape(16.dp)
            val (fitW, fitH) = fitStageFrame(frame.width, frame.height, maxWidth, maxHeight)
            val borderColor = when {
                stage.needsAttention -> c.warnSolid.copy(alpha = breath)
                touchOperate && stage.pipInput != null -> c.accent.copy(alpha = 0.7f)
                stage.isRetainedSnapshot -> c.edge
                else -> c.ok.copy(alpha = 0.42f)
            }
            val borderWidth = if (stage.needsAttention || (touchOperate && stage.pipInput != null)) 1.8.dp else 1.dp

            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            LaunchedEffect(touchOperate) {
                if (touchOperate) {
                    scale = 1f
                    offset = Offset.Zero
                }
            }
            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                if (!touchOperate) {
                    val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                    scale = nextScale
                    offset = if (nextScale <= 1.01f) Offset.Zero else offset + panChange
                }
            }
            val activeInput = stage.pipInput.takeIf { touchOperate }
            val canWakeSnapshot = stage.isRetainedSnapshot && touchOperate
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
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
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
            val shape = RoundedCornerShape(22.dp)
            val isPreparing = stage.isPreparing || primaryTask != null
            Box(
                Modifier.fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .background(c.surface, shape)
                    .border(0.5.dp, c.edge, shape)
                    .clip(shape)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 320.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isPreparing) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.4.dp, color = c.accent)
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
                        Box(
                            Modifier.size(52.dp).background(c.accentSoft, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MikaIcons.Screens, null, Modifier.size(24.dp), tint = c.accent)
                        }
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
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = hasSubtitles,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 6.dp),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(c.surface.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
                    .border(0.5.dp, c.edge, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .testTag("personal-live-subtitles"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (liveState.liveUserTranscript.isNotBlank()) {
                    Text(
                        stringResource(R.string.live_you_prefix, liveState.liveUserTranscript),
                        style = MikaType.Caption.copy(fontWeight = FontWeight.Medium),
                        color = c.accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (liveState.liveAssistantTranscript.isNotBlank()) {
                    Text(
                        stringResource(R.string.live_mika_prefix, liveState.liveAssistantTranscript),
                        style = MikaType.Caption,
                        color = c.ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
