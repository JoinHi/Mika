package com.wangzan101.mika.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.live.GeminiLiveProtocol
import com.wangzan101.mika.personal.live.LiveConfirmationPrompt
import com.wangzan101.mika.personal.live.LiveSessionPhase
import com.wangzan101.mika.personal.live.PersonalLiveRuntime
import com.wangzan101.mika.personal.live.PersonalLiveUiState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.components.pageBackground
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 个人助手顶栏右上角的「Mika Live 实时语音」入口胶囊:
 * - 空闲时点击检查麦克风权限与 Gemini API Key,就绪则直接开启全双工实时通话,未配置 Key 则弹出设置面板;
 * - 通话中显示绿色呼吸态,点击打开音色/模型设置面板。
 */
@Composable
internal fun PersonalLiveLauncherPill(
    live: PersonalLiveRuntime,
    state: PersonalLiveUiState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onStarted: () -> Unit = {},
    onTapRunning: (() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    val app = LocalContext.current.applicationContext as MikaApp
    val running = state.phase.isRunning
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (state.providerConfigured || live.settings.resolve(app.providers) != null) {
                live.start()
                onStarted()
            } else {
                onOpenSettings()
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
            .then(if (running) Modifier.background(bg, CircleShape).border(0.5.dp, c.ok.copy(alpha = 0.35f), CircleShape) else Modifier.floatShadow(c, CircleShape).background(bg, CircleShape).border(0.5.dp, c.edge, CircleShape))
            .clip(CircleShape)
            .testTag("personal-live-launcher")
            .clickable(role = Role.Button, onClickLabel = label) {
                if (running) {
                    if (onTapRunning != null) onTapRunning() else onOpenSettings()
                } else if (!state.providerConfigured && live.settings.resolve(app.providers) == null) {
                    onOpenSettings()
                } else if (!live.hasRecordPermission()) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    live.start()
                    onStarted()
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

@Composable
internal fun personalLiveStatusText(state: PersonalLiveUiState): String = when (state.phase) {
    LiveSessionPhase.Connecting -> stringResource(R.string.live_status_connecting)
    LiveSessionPhase.Reconnecting -> stringResource(R.string.live_status_reconnecting)
    LiveSessionPhase.SuspendedByCall -> stringResource(R.string.live_status_suspended_call)
    LiveSessionPhase.Active -> when {
        state.muted -> stringResource(R.string.live_status_muted)
        state.speaking -> stringResource(R.string.live_status_speaking)
        state.activeTaskCount > 0 -> stringResource(R.string.live_status_listening_tasks, state.activeTaskCount)
        else -> stringResource(R.string.live_status_listening)
    }
    LiveSessionPhase.Idle, LiveSessionPhase.Error -> stringResource(R.string.live_status_listening)
}

/**
 * 通话开启后的嵌入式底部实时语音控制栏（替代原 Composer）:
 * - 上方悬浮流式字幕卡片（实时显示正在识别的你与 Mika 的话，说完落入上方聊天流后自动清空）;
 * - 底部玻璃胶囊栏：动态声波 + 状态文案 + 闭麦/开麦 + 发当前屏幕 + 切键盘输入 + 音色设置 + 红色挂断。
 */
@Composable
internal fun PersonalLiveBar(
    state: PersonalLiveUiState,
    reduceMotion: Boolean,
    onToggleMute: () -> Unit,
    onShareScreen: () -> Unit,
    onSwitchToKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissHint: () -> Unit,
    onHangup: () -> Unit,
    showSubtitlesInline: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val fade = remember(c) { Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.6f to c.bg, 1f to c.bg) }
    val waveLevel = when {
        state.phase != LiveSessionPhase.Active -> 0f
        state.speaking -> state.outputLevel
        state.muted -> 0f
        else -> state.inputLevel
    }
    val waveFlat = state.phase != LiveSessionPhase.Active || (state.muted && !state.speaking)
    val waveColor = when {
        state.phase == LiveSessionPhase.SuspendedByCall || state.muted -> c.warn
        state.speaking -> c.ok
        else -> c.accent
    }
    val hasSubtitle = showSubtitlesInline && (state.liveUserTranscript.isNotBlank() || state.liveAssistantTranscript.isNotBlank())
    val hintText = state.statusHint?.takeIf { it.isNotBlank() }

    Box(
        modifier
            .fillMaxWidth()
            .pageBackground(c.bg, fade)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = if (showSubtitlesInline) 8.dp else 4.dp,
                bottom = if (showSubtitlesInline) 6.dp else 4.dp,
            )
            .testTag("personal-live-bar"),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (showSubtitlesInline) 8.dp else 6.dp)) {
            if (hintText != null) {
                LiveHintChip(hintText, onDismiss = onDismissHint, modifier = Modifier.fillMaxWidth())
            }

            AnimatedVisibility(
                visible = hasSubtitle,
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut() + slideOutVertically { it / 4 },
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .cardSurface(c, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("personal-live-subtitles"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.liveUserTranscript.isNotBlank()) {
                        Text(
                            stringResource(R.string.live_you_prefix, state.liveUserTranscript),
                            style = MikaType.Caption.copy(fontWeight = FontWeight.Medium),
                            color = c.accent,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.liveAssistantTranscript.isNotBlank()) {
                        Text(
                            stringResource(R.string.live_mika_prefix, state.liveAssistantTranscript),
                            style = MikaType.Body,
                            color = c.ink,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            GlassBar(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.weight(1f).padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.phase == LiveSessionPhase.Connecting || state.phase == LiveSessionPhase.Reconnecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                    } else {
                        VoiceWave(level = waveLevel, flat = waveFlat, reduceMotion = reduceMotion, color = waveColor)
                    }
                    Text(
                        personalLiveStatusText(state),
                        style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold),
                        color = if (state.muted || state.phase == LiveSessionPhase.SuspendedByCall) c.warn else c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                LiveActionCircle(
                    icon = if (state.muted) MikaIcons.Mute else MikaIcons.Mic,
                    label = stringResource(if (state.muted) R.string.live_action_unmute else R.string.live_action_mute),
                    bg = if (state.muted) c.warnSoft else c.greySoft,
                    tint = if (state.muted) c.warn else c.ink,
                    tag = "live-btn-mute",
                    onClick = onToggleMute,
                )
                LiveActionCircle(
                    icon = MikaIcons.Camera,
                    label = stringResource(R.string.live_action_share_screen),
                    bg = c.greySoft,
                    tint = c.ink,
                    tag = "live-btn-screen",
                    enabled = state.phase == LiveSessionPhase.Active,
                    onClick = onShareScreen,
                )
                LiveActionCircle(
                    icon = MikaIcons.Edit,
                    label = stringResource(R.string.live_action_type_message),
                    bg = c.greySoft,
                    tint = c.ink,
                    tag = "live-btn-keyboard",
                    onClick = onSwitchToKeyboard,
                )
                LiveActionCircle(
                    icon = MikaIcons.Gear,
                    label = stringResource(R.string.live_action_settings),
                    bg = c.greySoft,
                    tint = c.ink,
                    tag = "live-btn-settings",
                    onClick = onOpenSettings,
                )
                LiveActionCircle(
                    icon = MikaIcons.Close,
                    label = stringResource(R.string.live_action_hangup),
                    bg = c.bad,
                    tint = c.onAccent,
                    tag = "live-btn-hangup",
                    onClick = onHangup,
                )
            }
        }
    }
}

/**
 * 在 Mika Live 通话中临时切到键盘输入时，浮在 Composer 上方的精简语音状态条:
 * 保持实时语音不断线，可随时闭麦、一键切回语音控制栏或直接挂断。
 */
@Composable
internal fun PersonalLiveCompactBanner(
    state: PersonalLiveUiState,
    onBackToVoiceBar: () -> Unit,
    onToggleMute: () -> Unit,
    onHangup: () -> Unit,
) {
    val c = MikaTheme.colors
    val preview = state.liveAssistantTranscript.ifBlank { state.liveUserTranscript }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .cardSurface(c, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.live_action_back_to_voice), onClick = onBackToVoiceBar)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("personal-live-compact-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (state.muted) c.warn else c.ok, CircleShape))
        Text(
            if (preview.isNotBlank()) preview else personalLiveStatusText(state),
            Modifier.weight(1f),
            style = MikaType.Caption.copy(fontWeight = FontWeight.Medium),
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.live_action_back_to_voice),
            style = MikaType.Caption.copy(fontWeight = FontWeight.SemiBold),
            color = c.accent,
            maxLines = 1,
        )
        Box(
            Modifier.size(28.dp).background(if (state.muted) c.warnSoft else c.fill, CircleShape).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onToggleMute),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (state.muted) MikaIcons.Mute else MikaIcons.Mic, null, Modifier.size(15.dp), tint = if (state.muted) c.warn else c.ink)
        }
        Box(
            Modifier.size(28.dp).background(c.badSoft, CircleShape).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onHangup),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(14.dp), tint = c.bad)
        }
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
            Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(14.dp), tint = c.ink3)
        }
    }
}

@Composable
private fun LiveActionCircle(
    icon: ImageVector,
    label: String,
    bg: Color,
    tint: Color,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(38.dp)
            .background(if (enabled) bg else bg.copy(alpha = 0.45f), CircleShape)
            .clip(CircleShape)
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) tint else tint.copy(alpha = 0.45f))
    }
}

/**
 * Mika Live 音色、模型与 Gemini 服务商配置弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PersonalLiveSettingsSheet(
    live: PersonalLiveRuntime,
    state: PersonalLiveUiState,
    onDismiss: () -> Unit,
    onOpenModels: () -> Unit,
    onStarted: () -> Unit = {},
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val cfg by live.settings.state.collectAsState()
    val discoveredMap by live.settings.discoveredModels.collectAsState()
    val allProviders by app.providers.providers.collectAsState()
    val keyRevs by app.providers.providerKeyRevisions.collectAsState()

    val geminiProviders = remember(allProviders, keyRevs) {
        live.settings.geminiProviders(app.providers)
    }
    val chosenProvider = geminiProviders.firstOrNull { it.id == cfg.providerId } ?: geminiProviders.firstOrNull()
    val discoveredModels = chosenProvider?.let { discoveredMap[it.id] }.orEmpty()
    val modelChoices = remember(discoveredModels, cfg.model) {
        buildList {
            addAll(discoveredModels)
            if (GeminiLiveProtocol.DEFAULT_MODEL !in this) add(GeminiLiveProtocol.DEFAULT_MODEL)
            if (cfg.model.isNotBlank() && cfg.model !in this) add(cfg.model)
        }
    }

    var modelDraft by remember(cfg.model) { mutableStateOf(cfg.model) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(modelDraft) {
        val clean = modelDraft.trim()
        if (clean.isNotBlank() && clean != cfg.model) {
            delay(300L)
            live.settings.update { it.copy(model = clean) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onDismiss()
            live.start()
            onStarted()
        } else {
            live.start()
        }
    }

    LaunchedEffect(chosenProvider?.id) {
        if (chosenProvider != null && discoveredModels.isEmpty()) {
            refreshing = true
            live.settings.refreshModels(app.providers)
            refreshing = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MikaSpace.S20, vertical = MikaSpace.S8)
                .testTag("personal-live-settings-sheet"),
            verticalArrangement = Arrangement.spacedBy(MikaSpace.S16),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.live_settings_title), style = MikaType.Title.copy(fontSize = 19.sp), color = c.ink)
                Text(stringResource(R.string.live_settings_subtitle), style = MikaType.Caption, color = c.ink2)
            }

            if (geminiProviders.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(c.warnSoft, MikaShapes.Card).padding(MikaSpace.S16),
                    verticalArrangement = Arrangement.spacedBy(MikaSpace.S12),
                ) {
                    Text(stringResource(R.string.live_settings_no_gemini_body), style = MikaType.Body, color = c.ink)
                    MikaButton(
                        stringResource(R.string.live_settings_open_models),
                        onClick = {
                            onDismiss()
                            onOpenModels()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        size = ButtonSize.Medium,
                    )
                }
            } else {
                if (geminiProviders.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(MikaSpace.S8)) {
                        Text(stringResource(R.string.live_settings_provider_label), style = MikaType.Label, color = c.ink2)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            geminiProviders.forEach { p ->
                                SelectChip(
                                    text = p.name,
                                    selected = p.id == chosenProvider?.id,
                                    onClick = { live.settings.update { it.copy(providerId = p.id) } },
                                    small = true,
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(MikaSpace.S8)) {
                    Text(stringResource(R.string.live_settings_voice_label), style = MikaType.Label, color = c.ink2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeminiLiveProtocol.VOICES.forEach { v ->
                            SelectChip(
                                text = v,
                                selected = cfg.voiceName.equals(v, ignoreCase = true),
                                onClick = { live.settings.update { it.copy(voiceName = v) } },
                                small = true,
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(MikaSpace.S8)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.live_settings_model_label), Modifier.weight(1f), style = MikaType.Label, color = c.ink2)
                        Row(
                            Modifier.clip(CircleShape)
                                .clickable(enabled = !refreshing) {
                                    scope.launch {
                                        refreshing = true
                                        live.settings.refreshModels(app.providers)
                                        refreshing = false
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp, color = c.accent)
                            } else {
                                Icon(MikaIcons.Refresh, null, Modifier.size(14.dp), tint = c.accent)
                            }
                            Text(stringResource(R.string.live_settings_refresh_models), style = MikaType.Caption, color = c.accent)
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modelChoices.forEach { m ->
                            SelectChip(
                                text = m,
                                selected = cfg.model == m,
                                onClick = {
                                    modelDraft = m
                                    live.settings.update { it.copy(model = m) }
                                },
                                small = true,
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .background(c.fill, RoundedCornerShape(12.dp))
                            .border(0.5.dp, c.edge, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = modelDraft,
                            onValueChange = { next ->
                                modelDraft = next
                            },
                            singleLine = true,
                            textStyle = MikaType.Body.copy(color = c.ink, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                HorizontalDivider(color = c.hair)

                if (state.phase.isRunning) {
                    MikaButton(
                        stringResource(R.string.live_settings_reconnect_apply),
                        onClick = {
                            onDismiss()
                            live.restartIfActive()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Large,
                    )
                } else {
                    MikaButton(
                        stringResource(R.string.live_settings_start_now),
                        onClick = {
                            if (!live.hasRecordPermission()) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                onDismiss()
                                live.start()
                                onStarted()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        kind = ButtonKind.Primary,
                        size = ButtonSize.Large,
                    )
                }
            }
            Spacer(Modifier.height(MikaSpace.S8))
        }
    }
}
