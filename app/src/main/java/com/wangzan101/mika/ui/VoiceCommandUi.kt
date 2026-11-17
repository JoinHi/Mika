package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.command.CommandSpeechSource
import com.wangzan101.mika.voice.command.VoiceCommandController
import com.wangzan101.mika.voice.command.VoiceCommandFeedback
import com.wangzan101.mika.voice.command.VoiceCommandPhase
import com.wangzan101.mika.voice.command.VoiceCommandSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CommandSettingReadiness(
    val liveConfigured: Boolean,
    val recognitionAvailable: Boolean,
)

@Composable
internal fun VoiceCommandSettingRow(
    onOpenModels: () -> Unit,
    onOpenLiveVoice: () -> Unit = onOpenModels,
    onOpenSpeech: () -> Unit = onOpenModels,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings = remember { VoiceCommandSettings.get(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var hasMicPermission by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = settings.enabled
                hasMicPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                resumeTick++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val liveConfig by app.personal.live.settings.state.collectAsState()
    val providers by app.providers.providers.collectAsState()
    val keyRevs by app.providers.providerKeyRevisions.collectAsState()
    val speechChoice by app.speech.choice.collectAsState()
    val systemSilent by app.speech.systemSilent.collectAsState()

    val readiness by produceState(
        initialValue = CommandSettingReadiness(liveConfigured = liveConfig.configured, recognitionAvailable = true),
        liveConfig,
        providers,
        keyRevs,
        speechChoice,
        systemSilent,
        enabled,
        resumeTick,
    ) {
        value = withContext(Dispatchers.IO) {
            CommandSettingReadiness(
                liveConfigured = app.personal.live.settings.resolve(app.providers) != null,
                recognitionAvailable = CommandSpeechSource(app).prepare() != null,
            )
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMicPermission) {
            scope.launch {
                val (liveOk, asrOk) = withContext(Dispatchers.IO) {
                    (app.personal.live.settings.resolve(app.providers) != null) to (CommandSpeechSource(app).prepare() != null)
                }
                if (liveOk && asrOk) {
                    settings.enabled = true
                    enabled = true
                }
            }
        } else {
            Toast.makeText(context, R.string.voice_command_permission, Toast.LENGTH_SHORT).show()
        }
    }

    val change: (Boolean) -> Unit = { value ->
        if (!value) {
            settings.enabled = false
            enabled = false
        } else {
            scope.launch {
                val (liveOk, asrOk) = withContext(Dispatchers.IO) {
                    (app.personal.live.settings.resolve(app.providers) != null) to (CommandSpeechSource(app).prepare() != null)
                }
                when {
                    !liveOk -> {
                        Toast.makeText(context, R.string.voice_command_need_live, Toast.LENGTH_SHORT).show()
                        onOpenLiveVoice()
                    }
                    !asrOk -> {
                        Toast.makeText(context, R.string.voice_command_need_recognition, Toast.LENGTH_SHORT).show()
                        onOpenSpeech()
                    }
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    else -> {
                        hasMicPermission = true
                        settings.enabled = true
                        enabled = true
                    }
                }
            }
        }
    }

    val effectiveEnabled = enabled && readiness.liveConfigured && readiness.recognitionAvailable && hasMicPermission
    val title = stringResource(R.string.voice_command_setting)
    ListRow(
        MikaIcons.Sound,
        title,
        stringResource(
            when {
                !readiness.liveConfigured -> R.string.voice_command_need_live
                !readiness.recognitionAvailable -> R.string.voice_command_need_recognition
                enabled && !hasMicPermission -> R.string.voice_command_permission
                else -> R.string.voice_command_setting_sub
            },
        ),
        onClick = { change(!effectiveEnabled) },
    ) {
        MikaSwitch(effectiveEnabled, change, Modifier.testTag("voice-command-setting").semantics { contentDescription = title })
    }
}

@Composable
internal fun VoiceCommandBanner(
    controller: VoiceCommandController,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect?) -> Unit = {},
) {
    val phase by controller.phase.collectAsState()
    val feedback by controller.feedback.collectAsState()
    val heardText by controller.heardText.collectAsState()
    LaunchedEffect(feedback) {
        if (feedback != null) { delay(4_000); controller.clearFeedback() }
    }
    val visible = phase != VoiceCommandPhase.Idle || feedback != null
    DisposableEffect(visible) {
        if (!visible) onBoundsChanged(null)
        onDispose { onBoundsChanged(null) }
    }
    if (!visible) return
    val colors = MikaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val isListeningPhase = phase != VoiceCommandPhase.Idle
    val title = when (val result = feedback) {
        VoiceCommandFeedback.NoSpeech -> stringResource(R.string.voice_command_no_speech)
        VoiceCommandFeedback.NotMatched -> stringResource(R.string.voice_command_not_matched)
        VoiceCommandFeedback.TimedOut -> stringResource(R.string.voice_command_timeout)
        VoiceCommandFeedback.MicrophoneBusy -> stringResource(R.string.voice_command_busy)
        VoiceCommandFeedback.Unavailable -> stringResource(R.string.voice_command_unavailable)
        is VoiceCommandFeedback.Failed -> VoiceCopy.failure(result.why)
        null -> stringResource(if (phase == VoiceCommandPhase.Listening) R.string.voice_command_listening else R.string.voice_command_recognizing)
    }
    val actionLabel = stringResource(if (isListeningPhase) R.string.voice_command_cancel else R.string.personal_close)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .onGloballyPositioned { onBoundsChanged(it.boundsInWindow()) }
            .floatShadow(colors, shape)
            .cardSurface(colors, shape)
            .clip(shape)
            .pointerInput(Unit) {}
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag(if (isListeningPhase) "voice-command-listening" else "voice-command-feedback"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.ink, style = MikaType.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                heardText.ifBlank { stringResource(R.string.voice_command_phrase) },
                color = colors.ink2,
                style = MikaType.Caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCancel) { Text(actionLabel) }
    }
}
