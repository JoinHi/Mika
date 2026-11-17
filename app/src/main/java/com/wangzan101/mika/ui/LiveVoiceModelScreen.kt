package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.live.GeminiLiveModels
import com.wangzan101.mika.core.live.GeminiLiveProtocol
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.personal.live.PersonalLiveRuntime
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderConnection
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"

/**
 * 设置 › 模型 › 语音模式模型:
 * - 未配置 Gemini 服务商时可直接内联填写 Base URL 与 API Key 一键连接并启用，无需二次跳转；
 * - 统一管理 Gemini 服务商切换、语音音色（Aoede / Kore / Puck 等）与 `bidiGenerateContent` 实时音频模型；
 * - 语音模式进行中修改音色或模型会自动无缝重连生效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveVoiceModelScreen(
    store: ProviderStore,
    live: PersonalLiveRuntime,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    val scope = rememberCoroutineScope()
    val cfg by live.settings.state.collectAsState()
    val discoveredMap by live.settings.discoveredModels.collectAsState()
    val allProviders by store.providers.collectAsState()
    val keyRevs by store.providerKeyRevisions.collectAsState()

    val geminiProviders = remember(allProviders, keyRevs) {
        live.settings.geminiProviders(store)
    }
    val chosenProvider = geminiProviders.firstOrNull { it.id == cfg.providerId } ?: geminiProviders.firstOrNull()

    var addingNewProvider by rememberSaveable { mutableStateOf(false) }
    val showConnectForm = geminiProviders.isEmpty() || addingNewProvider

    BackHandler(enabled = addingNewProvider && geminiProviders.isNotEmpty()) {
        addingNewProvider = false
    }

    var baseUrl by rememberSaveable { mutableStateOf(DEFAULT_GEMINI_BASE_URL) }
    var apiKey by remember { mutableStateOf("") }
    var keyNotice by remember { mutableStateOf(ApiKeyText.Notice.None) }
    var showKey by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }

    var selectedVoice by rememberSaveable(cfg.voiceName) { mutableStateOf(cfg.voiceName.ifBlank { GeminiLiveProtocol.DEFAULT_VOICE }) }
    var selectedModel by rememberSaveable(cfg.model) { mutableStateOf(cfg.model.ifBlank { GeminiLiveProtocol.DEFAULT_MODEL }) }
    var manualModel by rememberSaveable { mutableStateOf("") }

    val discoveredModels = chosenProvider?.let { discoveredMap[it.id] }.orEmpty()
    val modelChoices = remember(discoveredModels, selectedModel) {
        buildList {
            addAll(discoveredModels)
            if (GeminiLiveProtocol.DEFAULT_MODEL !in this) add(GeminiLiveProtocol.DEFAULT_MODEL)
            if (selectedModel.isNotBlank() && selectedModel !in this) add(selectedModel)
        }
    }

    LaunchedEffect(chosenProvider?.id) {
        val pid = chosenProvider?.id ?: return@LaunchedEffect
        if (discoveredMap[pid].isNullOrEmpty()) {
            refreshing = true
            refreshError = null
            live.settings.refreshModels(store, pid).onFailure {
                refreshError = it.message
            }
            refreshing = false
        }
    }

    val urlProblem = when {
        baseUrl.isBlank() -> tr(R.string.model_need_url)
        !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> tr(R.string.model_url_scheme)
        else -> null
    }
    val keyRejected = keyNotice == ApiKeyText.Notice.IllegalCharacters
    val canConnect = urlProblem == null &&
        apiKey.isNotBlank() &&
        !keyRejected &&
        ApiKeyText.isHeaderLegal(apiKey.trim()) &&
        !connecting

    fun applyImmediateIfConfigured(
        nextProviderId: String = chosenProvider?.id ?: cfg.providerId,
        nextModel: String = selectedModel,
        nextVoice: String = selectedVoice,
        forceEnable: Boolean = false,
    ) {
        val shouldPersistConfigured = forceEnable || cfg.configured
        live.settings.update {
            it.copy(
                providerId = nextProviderId,
                model = nextModel,
                voiceName = nextVoice,
                configured = shouldPersistConfigured,
            )
        }
        if (shouldPersistConfigured) {
            live.restartIfActive()
        }
    }

    fun connectAndEnable(skipListCheck: Boolean) {
        if (!canConnect) return
        val cleanUrl = baseUrl.trim()
        val cleanKey = apiKey.trim()
        val typedManual = manualModel.trim()
        connecting = true
        connectError = null
        scope.launch {
            if (skipListCheck) {
                val saved = withContext(Dispatchers.IO) {
                    val name = if (cleanUrl.trimEnd('/') == DEFAULT_GEMINI_BASE_URL) {
                        "Gemini"
                    } else {
                        ProviderConnection.nameForUrl(cleanUrl)
                    }
                    store.connect(
                        name = name,
                        kind = ProviderConfig.Kind.Gemini,
                        baseUrl = cleanUrl,
                        apiKey = cleanKey,
                        models = emptyList(),
                        sessionHeader = null,
                        untested = true,
                    )
                }
                val finalModel = typedManual.ifBlank { selectedModel.ifBlank { GeminiLiveProtocol.DEFAULT_MODEL } }
                selectedModel = finalModel
                live.settings.update {
                    it.copy(
                        providerId = saved.id,
                        model = finalModel,
                        voiceName = selectedVoice,
                        configured = true,
                    )
                }
                live.restartIfActive()
                apiKey = ""
                keyNotice = ApiKeyText.Notice.None
                connecting = false
                if (addingNewProvider) {
                    addingNewProvider = false
                } else {
                    onBack()
                }
                return@launch
            }

            val (listRes, bidiRes) = withContext(Dispatchers.IO) {
                val general = try {
                    store.listModels(ProviderConfig.Kind.Gemini, cleanUrl, cleanKey, null)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: ModelError.IllegalHeader) {
                    ModelListResult.Failed(ListFailure.IllegalKey)
                } catch (e: Exception) {
                    ModelListResult.Failed(ListFailure.of(e), e.message.orEmpty())
                }
                val bidi = GeminiLiveModels.listBidiModels(cleanUrl, cleanKey, ProviderStore.modelHttp)
                general to bidi
            }
            val bidiModels = bidiRes.getOrNull().orEmpty()
            val generalModels = (listRes as? ModelListResult.Ok)?.models.orEmpty()
            if (bidiRes.isFailure && listRes is ModelListResult.Failed) {
                connecting = false
                val bidiErr = when (val ex = bidiRes.exceptionOrNull()) {
                    is ModelError.IllegalHeader -> ModelSetupCopy.KEY_ILLEGAL
                    else -> ex?.message
                }
                connectError = listRes.message.ifBlank { bidiErr ?: tr(R.string.live_error_connect_failed) }
                return@launch
            }

            val saved = withContext(Dispatchers.IO) {
                val name = if (cleanUrl.trimEnd('/') == DEFAULT_GEMINI_BASE_URL) {
                    "Gemini"
                } else {
                    ProviderConnection.nameForUrl(cleanUrl)
                }
                store.connect(
                    name = name,
                    kind = ProviderConfig.Kind.Gemini,
                    baseUrl = cleanUrl,
                    apiKey = cleanKey,
                    models = generalModels,
                    sessionHeader = null,
                )
            }
            if (bidiModels.isNotEmpty()) {
                live.settings.rememberDiscoveredModels(saved.id, bidiModels)
            }
            val finalModel = when {
                typedManual.isNotBlank() -> typedManual
                selectedModel.isNotBlank() && (bidiModels.isEmpty() || selectedModel in bidiModels) -> selectedModel
                bidiModels.isNotEmpty() -> bidiModels.first()
                else -> GeminiLiveProtocol.DEFAULT_MODEL
            }
            selectedModel = finalModel
            live.settings.update {
                it.copy(
                    providerId = saved.id,
                    model = finalModel,
                    voiceName = selectedVoice,
                    configured = true,
                )
            }
            live.restartIfActive()
            apiKey = ""
            keyNotice = ApiKeyText.Notice.None
            connecting = false
            if (addingNewProvider) {
                addingNewProvider = false
            } else {
                onBack()
            }
        }
    }

    Page(tr(R.string.role_live_voice), onBack) {
        Text(
            tr(R.string.live_voice_setup_lead),
            Modifier.padding(top = 4.dp, bottom = 12.dp),
            fontSize = 14.5.sp,
            lineHeight = 22.sp,
            color = c.ink2,
        )

        if (showConnectForm) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GroupLabel(tr(R.string.live_voice_connect_title), Modifier.weight(1f))
                if (geminiProviders.isNotEmpty()) {
                    Text(
                        ModelSetupCopy.CANCEL,
                        Modifier.clickable {
                            addingNewProvider = false
                            connectError = null
                        }.padding(horizontal = 4.dp, vertical = 6.dp),
                        fontSize = 13.sp,
                        color = c.accent,
                    )
                }
            }
            GroupCard {
                InputRow(
                    ModelSetupCopy.BASE_URL,
                    baseUrl,
                    { baseUrl = it; connectError = null },
                    DEFAULT_GEMINI_BASE_URL,
                    first = true,
                    keyboard = KeyboardType.Uri,
                    modifier = Modifier.testTag("live-voice-base-url"),
                )
                InputRow(
                    ModelSetupCopy.API_KEY,
                    apiKey,
                    { raw ->
                        val accepted = ApiKeyText.accept(raw)
                        apiKey = accepted.text
                        keyNotice = accepted.notice
                        connectError = null
                    },
                    ModelSetupCopy.KEY_HINT,
                    keyboard = KeyboardType.Password,
                    secret = !showKey,
                    modifier = Modifier.testTag("live-voice-api-key"),
                    onClear = {
                        val cleared = ApiKeyText.accept("")
                        apiKey = cleared.text
                        keyNotice = cleared.notice
                        connectError = null
                    },
                    clearDescription = stringResource(R.string.model_key_clear),
                    clearModifier = Modifier.testTag("live-voice-api-key-clear"),
                ) {
                    Text(
                        if (showKey) stringResource(R.string.hide) else stringResource(R.string.show),
                        Modifier.clickable { showKey = !showKey },
                        fontSize = 13.sp,
                        color = c.accent,
                    )
                }
            }
            ApiKeyNoticeNote(keyNotice, tag = "live-voice-key-notice")
            urlProblem?.takeIf { baseUrl.isNotBlank() }?.let {
                FooterNote(it, color = c.bad)
            }
        } else {
            GroupLabel(tr(R.string.live_settings_provider_label))
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                geminiProviders.forEach { p ->
                    SelectChip(
                        text = ModelsCopy.providerName(p),
                        selected = p.id == chosenProvider?.id,
                        onClick = {
                            refreshError = null
                            applyImmediateIfConfigured(nextProviderId = p.id)
                        },
                        small = true,
                    )
                }
                SelectChip(
                    text = "+ ${ModelsCopy.ADD_PROVIDER}",
                    selected = false,
                    onClick = {
                        connectError = null
                        addingNewProvider = true
                    },
                    small = true,
                )
            }
        }

        GroupLabel(tr(R.string.live_settings_voice_label))
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GeminiLiveProtocol.VOICES.forEach { voice ->
                SelectChip(
                    text = voice,
                    selected = selectedVoice.equals(voice, ignoreCase = true),
                    onClick = {
                        selectedVoice = voice
                        if (!showConnectForm && chosenProvider != null) {
                            applyImmediateIfConfigured(nextProviderId = chosenProvider.id, nextVoice = voice)
                        }
                    },
                    modifier = Modifier.testTag("live-voice-chip-$voice"),
                    small = true,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(tr(R.string.live_settings_model_label), Modifier.weight(1f))
            if (!showConnectForm && chosenProvider != null) {
                BarIconButton(
                    MikaIcons.Refresh,
                    ModelsCopy.REFRESH,
                    onClick = {
                        if (!refreshing) {
                            scope.launch {
                                refreshing = true
                                refreshError = null
                                live.settings.refreshModels(store, chosenProvider.id).onFailure {
                                    refreshError = it.message
                                }
                                refreshing = false
                            }
                        }
                    },
                    enabled = !refreshing,
                )
            }
        }

        GroupCard {
            modelChoices.forEachIndexed { index, modelId ->
                RadioRow(
                    modelId,
                    selected = selectedModel == modelId,
                    onClick = {
                        selectedModel = modelId
                        if (!showConnectForm && chosenProvider != null) {
                            applyImmediateIfConfigured(
                                nextProviderId = chosenProvider.id,
                                nextModel = modelId,
                                forceEnable = true,
                            )
                        }
                    },
                    first = index == 0,
                    titleStyle = ModelIdStyle,
                )
            }
            val useManualEnabled = manualModel.isNotBlank() && !connecting
            InputRow(
                ModelsCopy.MODEL_NAME,
                manualModel,
                { manualModel = it },
                GeminiLiveProtocol.DEFAULT_MODEL,
                first = modelChoices.isEmpty(),
                keyboard = KeyboardType.Uri,
                modifier = Modifier.testTag("live-voice-manual-model"),
            ) {
                if (!showConnectForm && chosenProvider != null) {
                    Text(
                        ModelsCopy.USE,
                        Modifier.clickable(enabled = useManualEnabled) {
                            val clean = manualModel.trim()
                            if (clean.isNotBlank()) {
                                selectedModel = clean
                                manualModel = ""
                                applyImmediateIfConfigured(
                                    nextProviderId = chosenProvider.id,
                                    nextModel = clean,
                                    forceEnable = true,
                                )
                            }
                        }.testTag("live-voice-manual-use"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (useManualEnabled) c.accent else c.ink3,
                    )
                }
            }
        }

        if (refreshing) {
            FooterNote(ModelsCopy.LISTING)
        } else if (!refreshError.isNullOrBlank() && !showConnectForm) {
            FooterNote(ModelSetupCopy.failed(refreshError.orEmpty()), color = c.bad)
        }

        if (showConnectForm) {
            MikaButton(
                if (connecting) ModelSetupCopy.TESTING else tr(R.string.live_voice_connect_save),
                onClick = { connectAndEnable(skipListCheck = false) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("live-voice-connect-save"),
                kind = ButtonKind.Primary,
                size = ButtonSize.Large,
                enabled = canConnect,
            )
            connectError?.let { err ->
                FooterNote(ModelSetupCopy.failed(err), Modifier.padding(top = 6.dp), color = c.bad)
                MikaButton(
                    ModelSetupCopy.SAVE_UNTESTED,
                    onClick = { connectAndEnable(skipListCheck = true) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("live-voice-save-untested"),
                    kind = ButtonKind.Tonal,
                    size = ButtonSize.Small,
                    enabled = canConnect,
                )
            }
            FooterNote(ModelsCopy.KEY_NOTE, Modifier.padding(top = 8.dp))
        } else if (chosenProvider != null) {
            if (!cfg.configured) {
                MikaButton(
                    tr(R.string.live_voice_enable_save),
                    onClick = {
                        val finalModel = manualModel.trim().ifBlank { selectedModel.ifBlank { GeminiLiveProtocol.DEFAULT_MODEL } }
                        selectedModel = finalModel
                        manualModel = ""
                        applyImmediateIfConfigured(
                            nextProviderId = chosenProvider.id,
                            nextModel = finalModel,
                            nextVoice = selectedVoice,
                            forceEnable = true,
                        )
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("live-voice-enable"),
                    kind = ButtonKind.Primary,
                    size = ButtonSize.Large,
                )
            } else {
                val pendingManual = manualModel.trim()
                if (pendingManual.isNotBlank() && pendingManual != selectedModel) {
                    MikaButton(
                        ModelsCopy.SAVE,
                        onClick = {
                            selectedModel = pendingManual
                            manualModel = ""
                            applyImmediateIfConfigured(
                                nextProviderId = chosenProvider.id,
                                nextModel = pendingManual,
                                nextVoice = selectedVoice,
                                forceEnable = true,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("live-voice-save-manual"),
                        kind = ButtonKind.Primary,
                        size = ButtonSize.Medium,
                    )
                }
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        tr(R.string.live_voice_disable),
                        Modifier.clip(MikaShapes.Pill)
                            .clickable {
                                if (live.isSessionActive) live.stop()
                                live.settings.update { it.copy(configured = false) }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("live-voice-disable"),
                        style = MikaType.Body,
                        color = c.bad,
                    )
                }
            }
        }
    }
}
