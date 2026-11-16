package com.wangzan101.mika.personal.live

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.live.LiveAsyncEvent
import com.wangzan101.mika.core.live.LiveTurnCoordinator
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.search.WebSearchHit
import com.wangzan101.mika.core.search.WebSearchResult
import com.wangzan101.mika.settings.WebSearchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mika Live 语音联网检索器（严格遵守 [WebSearchSettings] 用户开关与选定服务商）：
 * - 仅当机主在设置中启用网页搜索并配置好服务商（[WebSearchSettings.service] 非空）时发起请求，绝不暗中回退至未授权的第三方搜索；
 * - 搜索结果标题、链接与摘要统一包裹 [Untrusted.wrap] 不可信边界标记；
 * - 支持 2.6 秒快慢双路：快路径当轮直接返回工具结果口播；慢速网络时立即释放工具调用让语音不断流，后台搜完经 [LiveTurnCoordinator] 播报。
 */
internal class LiveWebSearchRunner(
    private val app: MikaApp?,
    private val scope: CoroutineScope,
    private val turnCoordinator: LiveTurnCoordinator,
    private val onStatusHint: (String?) -> Unit,
    private val searchProvider: () -> WebSearch? = { app?.let { WebSearchSettings.get(it).service() } },
) {
    private val lock = Any()
    private val asyncJobs = HashSet<Job>()
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun isSearchEnabled(): Boolean = searchProvider() != null

    suspend fun searchForLiveTool(rawQuery: String, rawCount: Int?): JsonObject {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return buildJsonObject {
                put("ok", false)
                put("error", "empty_query")
                put("message", "搜索关键词不能为空。")
            }
        }
        val service = searchProvider()
        if (service == null) {
            return toToolPayload(
                query,
                WebSearchResult.Failure(
                    "search_disabled",
                    "机主尚未在设置中开启网页搜索服务；如需联网浏览网页，可改用 delegate_task 启动浏览器查询。",
                ),
            )
        }
        val count = (rawCount ?: DEFAULT_COUNT).coerceIn(1, MAX_LIVE_COUNT)
        _activeCount.update { it + 1 }
        val deferred = scope.async(Dispatchers.IO) {
            try {
                service.search(query, count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WebSearchResult.Failure("search_error", e.message ?: "联网检索请求失败。")
            }
        }
        trackJob(deferred)

        val immediate = try {
            withTimeoutOrNull(FAST_SYNC_TIMEOUT_MS) { deferred.await() }
        } catch (e: CancellationException) {
            deferred.cancel()
            _activeCount.update { (it - 1).coerceAtLeast(0) }
            throw e
        }
        if (immediate != null) {
            _activeCount.update { (it - 1).coerceAtLeast(0) }
            return toToolPayload(query, immediate)
        }

        onStatusHint("正在联网查询…")
        val followUpJob = scope.launch {
            try {
                val res = deferred.await()
                val text = formatForContext(query, res)
                turnCoordinator.enqueue(LiveAsyncEvent.AsyncSearchResult(query = query, summary = text))
            } catch (e: CancellationException) {
                deferred.cancel()
                throw e
            } finally {
                var remaining = 0
                _activeCount.update { cur ->
                    remaining = (cur - 1).coerceAtLeast(0)
                    remaining
                }
                if (remaining == 0) {
                    onStatusHint(null)
                }
            }
        }
        trackJob(followUpJob)

        return buildJsonObject {
            put("ok", true)
            put("status", "searching_async")
            put("query", query)
            put("message", "联网检索正在后台继续进行，你可以先自然回应机主一句或继续聊天；结果返回后系统会自动推送给你播报。")
        }
    }

    /** 供 [LiveDeepThinkRunner] 后台深度思考直接同步调用。 */
    suspend fun searchDirect(rawQuery: String, count: Int = DEFAULT_COUNT): WebSearchResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return WebSearchResult.Failure("empty_query", "Query must not be blank.")
        val service = searchProvider()
            ?: return WebSearchResult.Failure("search_disabled", "Web search is disabled in settings.")
        return service.search(query, count.coerceIn(1, WebSearch.MAX_COUNT))
    }

    fun cancelAll() {
        val jobs = synchronized(lock) {
            val copy = asyncJobs.toList()
            asyncJobs.clear()
            copy
        }
        jobs.forEach { it.cancel() }
        _activeCount.value = 0
    }

    private fun trackJob(job: Job) {
        synchronized(lock) { asyncJobs += job }
        job.invokeOnCompletion {
            synchronized(lock) { asyncJobs.remove(job) }
        }
    }

    companion object {
        const val FAST_SYNC_TIMEOUT_MS = 2_600L
        private const val DEFAULT_COUNT = 5
        private const val MAX_LIVE_COUNT = 6

        internal fun toToolPayload(query: String, result: WebSearchResult): JsonObject = when (result) {
            is WebSearchResult.Success -> buildJsonObject {
                put("ok", true)
                put("status", "completed")
                put("query", query)
                put("count", result.hits.size)
                put("results", hitsToJson(result.hits))
                if (result.hits.isEmpty()) {
                    put("note", "未检索到直接匹配的网页结果，可换个关键词再试。")
                }
            }
            is WebSearchResult.Failure -> buildJsonObject {
                put("ok", false)
                put("error", result.code)
                put("query", query)
                put("message", result.message)
                result.retryAfterSeconds?.let { put("retryAfterSeconds", it) }
            }
        }

        internal fun formatForContext(query: String, result: WebSearchResult): String = when (result) {
            is WebSearchResult.Success -> {
                if (result.hits.isEmpty()) {
                    "未搜到与「$query」直接相关的公开网页结果。"
                } else {
                    val body = result.hits.mapIndexed { idx, hit ->
                        "${idx + 1}. ${hit.title}\n   摘要：${hit.snippet}\n   来源：${hit.url}"
                    }.joinToString("\n")
                    Untrusted.wrap(body)
                }
            }
            is WebSearchResult.Failure -> "检索失败（${result.code}）：${result.message}"
        }

        private fun hitsToJson(hits: List<WebSearchHit>): JsonArray = JsonArray(
            hits.map { h ->
                buildJsonObject {
                    put("title", Untrusted.wrap(h.title))
                    put("url", Untrusted.wrap(h.url))
                    put("snippet", Untrusted.wrap(h.snippet))
                }
            },
        )
    }
}
