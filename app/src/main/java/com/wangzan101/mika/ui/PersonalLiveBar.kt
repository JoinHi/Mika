package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.personal.live.LiveConfirmationPrompt
import com.wangzan101.mika.personal.live.LiveSessionPhase
import com.wangzan101.mika.personal.live.LiveSubtitleFormatter
import com.wangzan101.mika.personal.live.PersonalLiveRuntime
import com.wangzan101.mika.personal.live.PersonalLiveUiState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 个人助手普通顶栏右上角的「语音模式」入口胶囊:
 * - 未配置语音模式模型时，点击直接跳转「设置 › 模型 › 语音模式模型」引导连接 Gemini 与选择音色；
 * - 已配置时检查麦克风权限并立即开启全双工语音模式。
 */
@Composable
internal fun PersonalLiveLauncherPill(
    live: PersonalLiveRuntime,
    state: PersonalLiveUiState,
    onOpenLiveVoice: () -> Unit,
    modifier: Modifier = Modifier,
    onStarted: () -> Unit = {},
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val running = state.phase.isRunning
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true || live.hasRecordPermission()
        if (micGranted) {
            if (state.providerConfigured || live.settings.resolve(app.providers) != null) {
                live.start()
                onStarted()
            } else {
                onOpenLiveVoice()
            }
        } else {
            live.start() // surfaces localized mic permission error
        }
    }

    val label = stringResource(if (running) R.string.live_entry_active else R.string.live_entry_button)
    val bg = if (running) c.okSoft else c.surface
    val fg = if (running) c.ok else c.ink

    Row(
        modifier = modifier
            .height(36.dp)
            .then(
                if (running) {
                    Modifier.background(bg, CircleShape).border(0.5.dp, c.ok.copy(alpha = 0.35f), CircleShape)
                } else {
                    Modifier.floatShadow(c, CircleShape).background(bg, CircleShape).border(0.5.dp, c.edge, CircleShape)
                },
            )
            .clip(CircleShape)
            .testTag("personal-live-launcher")
            .clickable(role = Role.Button, onClickLabel = label) {
                if (!running) {
                    val needsNotificationPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (!state.providerConfigured && live.settings.resolve(app.providers) == null) {
                        onOpenLiveVoice()
                    } else if (!live.hasRecordPermission()) {
                        val perms = if (needsNotificationPerm) {
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            arrayOf(Manifest.permission.RECORD_AUDIO)
                        }
                        permissionLauncher.launch(perms)
                    } else {
                        if (needsNotificationPerm) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        live.start()
                        onStarted()
                    }
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (running) {
            Box(Modifier.size(8.dp).background(c.ok, CircleShape))
        } else {
            Icon(MikaIcons.Sound, null, Modifier.size(16.dp), tint = fg)
        }
        Text(
            label,
            style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
        )
    }
}

private enum class SubtitleRole {
    Idle,
    User,
    Assistant,
}

/**
 * 舞台下方定高单行分句字幕槽（固定 36dp 高度，确保全屏虚拟屏高度绝对不跳动）:
 * 配合 [LiveSubtitleFormatter.extractActiveClause] 按标点滑窗 + [PersonalLiveRuntime] 播放排空门控与 3.5 秒余韵驻留，
 * 让 Mika 与机主的对话清晰易读、绝不一闪而过。
 */
@Composable
internal fun PersonalLiveSubtitleRow(
    state: PersonalLiveUiState,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val assistantClause = remember(state.liveAssistantTranscript) {
        LiveSubtitleFormatter.extractActiveClause(state.liveAssistantTranscript)
    }
    val userClause = remember(state.liveUserTranscript) {
        LiveSubtitleFormatter.extractActiveClause(state.liveUserTranscript)
    }

    val (role, displayLine) = when {
        assistantClause.isNotBlank() -> SubtitleRole.Assistant to stringResource(R.string.live_mika_prefix, assistantClause)
        userClause.isNotBlank() -> SubtitleRole.User to stringResource(R.string.live_you_prefix, userClause)
        else -> SubtitleRole.Idle to stringResource(R.string.live_subtitle_idle_hint)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 18.dp)
            .testTag("personal-live-subtitles"),
        contentAlignment = Alignment.Center,
    ) {
        if (reduceMotion) {
            SubtitleLineText(role = role, text = displayLine)
        } else {
            AnimatedContent(
                targetState = role to displayLine,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(110)) },
                label = "liveSubtitleSlide",
            ) { (targetRole, targetText) ->
                SubtitleLineText(role = targetRole, text = targetText)
            }
        }
    }
}

@Composable
private fun SubtitleLineText(role: SubtitleRole, text: String) {
    val c = MikaTheme.colors
    val textColor = when (role) {
        SubtitleRole.Assistant -> c.ink
        SubtitleRole.User -> c.accent
        SubtitleRole.Idle -> c.ink3.copy(alpha = 0.72f)
    }
    val style = when (role) {
        SubtitleRole.Idle -> MikaType.Caption
        SubtitleRole.User, SubtitleRole.Assistant -> MikaType.Body.copy(fontWeight = FontWeight.Medium)
    }
    Text(
        text = text,
        style = style,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 语音模式最底部极简双段胶囊入口:
 * 去除了闭麦、发屏幕、切键盘、设置等冗余按钮，仅保留 `[ ● 语音模式 01:28  |  结束 ]`。
 */
@Composable
internal fun PersonalLiveBottomCallCapsule(
    state: PersonalLiveUiState,
    reduceMotion: Boolean,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val elapsedSec by produceState(initialValue = 0L, key1 = state.connectedAtElapsedMs, key2 = state.phase) {
        if (state.connectedAtElapsedMs <= 0L || !state.phase.isRunning) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = ((SystemClock.elapsedRealtime() - state.connectedAtElapsedMs) / 1000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }

    val dotPulse = if (state.phase == LiveSessionPhase.Active && !reduceMotion) {
        rememberInfiniteTransition(label = "callCapsulePulse").animateFloat(
            1f,
            0.35f,
            infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "callCapsuleDot",
        ).value
    } else {
        1f
    }

    val hangupLabel = stringResource(R.string.live_action_hangup)

    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("personal-live-bar"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(46.dp)
                .floatShadow(c, CircleShape)
                .cardSurface(c, CircleShape)
                .padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.phase) {
                    LiveSessionPhase.Connecting, LiveSessionPhase.Reconnecting -> {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.8.dp, color = c.accent)
                    }
                    LiveSessionPhase.SuspendedByCall -> {
                        Box(Modifier.size(8.dp).background(c.warn, CircleShape))
                    }
                    else -> {
                        Box(Modifier.size(8.dp).background(c.ok.copy(alpha = dotPulse), CircleShape))
                    }
                }
                Text(
                    text = stringResource(R.string.live_in_call_label),
                    style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                    maxLines = 1,
                )
                if (state.connectedAtElapsedMs > 0L) {
                    Text(
                        text = formatCallDurationSeconds(elapsedSec),
                        style = MikaType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                        color = c.ink2,
                        maxLines = 1,
                    )
                }
            }

            Box(Modifier.width(1.dp).height(18.dp).background(c.hair))

            Row(
                Modifier
                    .height(36.dp)
                    .background(c.bad, CircleShape)
                    .clip(CircleShape)
                    .testTag("live-btn-hangup")
                    .clickable(role = Role.Button, onClickLabel = hangupLabel, onClick = onHangup)
                    .semantics { contentDescription = hangupLabel }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MikaIcons.Close, null, Modifier.size(15.dp), tint = c.onAccent)
                Text(
                    text = hangupLabel,
                    style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold),
                    color = c.onAccent,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun formatCallDurationSeconds(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val seconds = safe % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

@Composable
internal fun LiveConfirmationCard(
    prompt: LiveConfirmationPrompt,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val title = remember(prompt) { ConfirmationCopy.title(prompt.request) }
    val detail = remember(prompt) { ConfirmationCopy.detail(prompt.request, prompt.appLabel) }
    val note = remember { ConfirmationCopy.NOTE }
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .cardSurface(c, shape)
            .border(1.dp, c.warn.copy(alpha = 0.45f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("live-confirm-card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(22.dp).background(c.warnSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Info, null, Modifier.size(13.dp), tint = c.warn)
            }
            Text(
                title,
                Modifier.weight(1f),
                style = MikaType.BodyStrong,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            detail,
            style = MikaType.Caption,
            color = c.ink,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            note,
            style = MikaType.Caption,
            color = c.ink3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MikaButton(
                text = stringResource(R.string.action_decline),
                onClick = onDecline,
                modifier = Modifier.weight(1f).testTag("live-confirm-decline"),
                kind = ButtonKind.Tonal,
                size = ButtonSize.Medium,
            )
            MikaButton(
                text = stringResource(R.string.action_approve),
                onClick = onApprove,
                modifier = Modifier.weight(1f).testTag("live-confirm-approve"),
                kind = ButtonKind.Primary,
                size = ButtonSize.Medium,
            )
        }
    }
}

@Composable
internal fun LiveHintChip(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val closeLabel = stringResource(R.string.personal_close)
    Row(
        modifier.fillMaxWidth()
            .cardSurface(c, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Info, null, Modifier.size(16.dp), tint = c.ink2)
        Text(text, Modifier.weight(1f), style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.size(24.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = closeLabel, onClick = onDismiss)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(14.dp), tint = c.ink3)
        }
    }
}
