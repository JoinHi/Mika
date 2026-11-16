package com.wangzan101.mika.personal.live

import com.wangzan101.mika.R
import com.wangzan101.mika.core.live.GeminiLiveModels
import com.wangzan101.mika.core.live.GeminiLiveProtocol
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class PersonalLiveConfig(
    val providerId: String = "",
    val model: String = GeminiLiveProtocol.DEFAULT_MODEL,
    val voiceName: String = GeminiLiveProtocol.DEFAULT_VOICE,
    val configured: Boolean = false,
)

data class ResolvedLiveCredentials(
    val provider: Provider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voiceName: String,
)

class PersonalLiveSettings(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<PersonalLiveConfig> = _state.asStateFlow()

    private val _discoveredModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val discoveredModels: StateFlow<Map<String, List<String>>> = _discoveredModels.asStateFlow()

    @Synchronized
    fun update(change: (PersonalLiveConfig) -> PersonalLiveConfig) {
        val next = change(_state.value)
        val normalized = next.copy(
            model = next.model.trim().ifBlank { GeminiLiveProtocol.DEFAULT_MODEL },
            voiceName = next.voiceName.trim().ifBlank { GeminiLiveProtocol.DEFAULT_VOICE },
        )
        persist(normalized)
        _state.value = normalized
    }

    fun geminiProviders(store: ProviderStore): List<Provider> =
        store.providers.value.filter { it.kind == ProviderConfig.Kind.Gemini && store.hasKey(it.id) }

    fun rememberDiscoveredModels(providerId: String, models: List<String>) {
        if (providerId.isNotBlank() && models.isNotEmpty()) {
            _discoveredModels.value = _discoveredModels.value + (providerId to models)
        }
    }

    fun resolveCandidateProvider(store: ProviderStore, providerIdOverride: String? = null): Provider? {
        val candidates = geminiProviders(store)
        val explicitId = providerIdOverride?.trim()?.takeIf { it.isNotEmpty() } ?: _state.value.providerId.trim()
        return if (explicitId.isNotEmpty()) {
            candidates.firstOrNull { it.id == explicitId }
        } else {
            candidates.firstOrNull()
        }
    }

    fun resolve(store: ProviderStore): ResolvedLiveCredentials? {
        val cfg = _state.value
        if (!cfg.configured) return null
        val chosen = resolveCandidateProvider(store) ?: return null
        val key = store.providerKey(chosen.id)?.takeIf { it.isNotBlank() } ?: return null
        val discovered = _discoveredModels.value[chosen.id].orEmpty()
        val chosenModel = cfg.model.trim().takeIf { it.isNotBlank() }
            ?: discovered.firstOrNull()
            ?: GeminiLiveProtocol.DEFAULT_MODEL
        return ResolvedLiveCredentials(
            provider = chosen,
            apiKey = key,
            baseUrl = chosen.baseUrl.ifBlank { "https://generativelanguage.googleapis.com" },
            model = chosenModel,
            voiceName = cfg.voiceName.ifBlank { GeminiLiveProtocol.DEFAULT_VOICE },
        )
    }

    suspend fun refreshModels(store: ProviderStore, providerIdOverride: String? = null): Result<List<String>> = withContext(Dispatchers.IO) {
        val chosen = resolveCandidateProvider(store, providerIdOverride)
            ?: return@withContext Result.failure(IllegalStateException(tr(R.string.live_error_no_gemini_key)))
        val key = store.providerKey(chosen.id)?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException(tr(R.string.live_error_no_gemini_key)))
        val baseUrl = chosen.baseUrl.ifBlank { "https://generativelanguage.googleapis.com" }
        val res = GeminiLiveModels.listBidiModels(
            baseUrl = baseUrl,
            apiKey = key,
            http = ProviderStore.modelHttp,
            extraHeaders = store.clientHeaders(chosen),
        )
        res.onSuccess { list ->
            if (list.isNotEmpty()) {
                _discoveredModels.value = _discoveredModels.value + (chosen.id to list)
                val current = _state.value
                if (current.model == GeminiLiveProtocol.DEFAULT_MODEL && current.model !in list) {
                    update { it.copy(model = list.first()) }
                }
            }
        }
        res
    }

    private fun load(): PersonalLiveConfig {
        if (!file.isFile) return PersonalLiveConfig(configured = false)
        return runCatching {
            val raw = file.readText()
            val decoded = json.decodeFromString<PersonalLiveConfig>(raw)
            if ("\"configured\"" !in raw) decoded.copy(configured = true) else decoded
        }.getOrDefault(PersonalLiveConfig(configured = false))
    }

    private fun persist(cfg: PersonalLiveConfig) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(cfg))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
