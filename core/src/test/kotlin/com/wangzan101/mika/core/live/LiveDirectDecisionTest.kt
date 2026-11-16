package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.decision.DecisionChoice
import com.wangzan101.mika.core.decision.DecisionModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDirectDecisionTest {

    private fun node(
        ref: String,
        name: String? = null,
        role: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        actionRef: String? = null,
        editRef: String? = null,
        resourceId: String? = null,
        bounds: List<Int> = listOf(100, 100, 300, 180),
        parents: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("ref", ref)
        name?.let { put("name", it) }
        put("role", role)
        put("clickable", clickable)
        put("editable", editable)
        put("enabled", true)
        actionRef?.let { put("actionRef", it) }
        editRef?.let { put("editRef", it) }
        resourceId?.let { put("resourceId", it) }
        put("bounds", JsonArray(bounds.map(::JsonPrimitive)))
        if (parents.isNotEmpty()) {
            put("parents", JsonArray(parents.map(::JsonPrimitive)))
        }
    }

    @Test
    fun selectNarrowedCandidatesMergesChildNodesSharingSameActionRef() {
        val matches = listOf(
            node(ref = "@e5", name = "拿铁咖啡", role = "android.view.ViewGroup", clickable = true, actionRef = "@e5", bounds = listOf(40, 200, 960, 360)),
            node(ref = "@e6", name = "拿铁咖啡 ¥18", role = "android.widget.TextView", clickable = false, actionRef = "@e5", bounds = listOf(80, 220, 500, 280)),
            node(ref = "@e7", name = "拿铁咖啡 标准冰", role = "android.widget.TextView", clickable = false, actionRef = "@e5", bounds = listOf(80, 290, 500, 340)),
        )

        val narrowed = LiveDirectDecision.selectNarrowedCandidates(matches, targetText = "拿铁", editable = false)
        assertEquals(1, narrowed.size)
        assertEquals("@e5", narrowed.single().ref)
        assertTrue(narrowed.single().labels.contains("拿铁咖啡 ¥18"))
    }

    @Test
    fun chooseTargetWithJevResolvesDisambiguationAndSynonymsUnderStrictThreshold() = runTest {
        val ambiguous = LiveDirectDecision.selectNarrowedCandidates(
            matches = listOf(
                node(ref = "@e20", name = "去结算", role = "android.widget.Button", clickable = true, actionRef = "@e20", bounds = listOf(700, 620, 940, 700), parents = listOf("瑞幸咖啡 门店二")),
                node(ref = "@e10", name = "去结算", role = "android.widget.Button", clickable = true, actionRef = "@e10", bounds = listOf(700, 300, 940, 380), parents = listOf("星巴克 门店一")),
            ),
            targetText = "去结算",
            editable = false,
        )
        // Sorted by top-to-bottom reading order: @e10 is #1 (opt_0), @e20 is #2 (opt_1)
        assertEquals(listOf("@e10", "@e20"), ambiguous.map { it.ref })

        var capturedOptions: Map<String, String> = emptyMap()
        val confidentJev = DecisionModel { _, _, options, _ ->
            capturedOptions = options
            DecisionChoice(choice = "opt_1", probability = 0.93, applicable = 0.91)
        }

        val chosen = LiveDirectDecision.chooseTargetWithJev(
            decision = confidentJev,
            op = "tap",
            command = buildJsonObject { put("op", "tap"); put("targetText", "去结算") },
            candidates = ambiguous,
            currentApp = "美团",
            userUtterance = "帮我点第二个去结算",
        )
        assertEquals("@e20", chosen)
        assertTrue(capturedOptions["opt_0"].orEmpty().contains("#1"))
        assertTrue(capturedOptions["opt_1"].orEmpty().contains("瑞幸咖啡"))

        // Reject when applicable < 0.85 even if choice probability is high
        val unapplicableJev = DecisionModel { _, _, _, _ ->
            DecisionChoice(choice = "opt_0", probability = 0.96, applicable = 0.62)
        }
        assertNull(
            LiveDirectDecision.chooseTargetWithJev(
                decision = unapplicableJev,
                op = "tap",
                command = buildJsonObject { put("op", "tap"); put("targetText", "不存在的按钮") },
                candidates = ambiguous,
            ),
        )

        // Reject cleanly on timeout
        val slowJev = DecisionModel { _, _, _, _ ->
            delay(5_000L)
            DecisionChoice(choice = "opt_0", probability = 0.99, applicable = 0.99)
        }
        assertNull(
            LiveDirectDecision.chooseTargetWithJev(
                decision = slowJev,
                op = "tap",
                command = buildJsonObject { put("op", "tap"); put("targetText", "去结算") },
                candidates = ambiguous,
                timeoutMs = 100L,
            ),
        )
    }

    @Test
    fun extractScreenCandidatesSupportsTapToFocusSearchBarForTypeOnly() {
        val noEditTextScreen = listOf(
            node(ref = "@e3", name = "搜一搜", role = "android.widget.LinearLayout", clickable = true, actionRef = "@e3", resourceId = "com.tencent.mm:id/search_bar"),
        )
        val forType = LiveDirectDecision.extractScreenCandidates(noEditTextScreen, editable = true, allowTapToFocusFallback = true)
        assertEquals(listOf("@e3"), forType.map { it.ref })

        val forClear = LiveDirectDecision.extractScreenCandidates(noEditTextScreen, editable = true, allowTapToFocusFallback = false)
        assertTrue(forClear.isEmpty())
    }

    @Test
    fun shouldBlockAsDiscussionBlocksCasualChatWhileAllowingReadActPassiveAndShortUtterances() = runTest {
        val discussJev = DecisionModel { state, _, _, _ ->
            val msg = ((state as? JsonObject)?.get("currentMessage") as? JsonPrimitive)?.content.orEmpty()
            when {
                "朋友圈怎么关" in msg -> DecisionChoice("DISCUSS", probability = 0.94)
                "帮我打开微信" in msg -> DecisionChoice("ACT", probability = 0.96)
                else -> DecisionChoice("DISCUSS", probability = 0.70) // below 0.85 -> fail open
            }
        }

        val launchCommands = JsonArray(
            listOf(buildJsonObject { put("op", "launch"); put("appName", "微信") }),
        )
        val observeCommands = JsonArray(
            listOf(buildJsonObject { put("op", "observe") }),
        )

        assertTrue(
            LiveDirectDecision.shouldBlockAsDiscussion(
                decision = discussJev,
                currentTurnUserText = "微信的朋友圈怎么关闭入口呀？",
                recentContext = "assistant: 你想了解哪个设置？",
                commands = launchCommands,
            ),
        )
        assertFalse(
            LiveDirectDecision.shouldBlockAsDiscussion(
                decision = discussJev,
                currentTurnUserText = "帮我打开微信看下消息",
                commands = launchCommands,
            ),
        )
        assertFalse(
            "Low-confidence DISCUSS must fail open",
            LiveDirectDecision.shouldBlockAsDiscussion(
                decision = discussJev,
                currentTurnUserText = "顺便看看那个东西",
                commands = launchCommands,
            ),
        )
        assertFalse(
            "Passive observation must never be blocked",
            LiveDirectDecision.shouldBlockAsDiscussion(
                decision = discussJev,
                currentTurnUserText = "微信的朋友圈怎么关闭入口呀？",
                commands = observeCommands,
            ),
        )
        assertFalse(
            "Short partial ASR transcript (< 4 chars) must fail open",
            LiveDirectDecision.shouldBlockAsDiscussion(
                decision = discussJev,
                currentTurnUserText = "打开",
                commands = launchCommands,
            ),
        )

        var jevCalls = 0
        val countingJev = DecisionModel { _, _, _, _ ->
            jevCalls++
            DecisionChoice("DISCUSS", probability = 0.99)
        }
        // Unmistakable imperative phone commands must pass in 0ms with ZERO cloud Jev calls
        for (cmdText in listOf(
            "打开淘宝",
            "搜索银河乒乓球桌",
            "点击屏幕中间的自行车",
            "返回淘宝的首页",
            "加入购物车",
            "往下滑一下",
            "帮我打开微信看下消息",
        )) {
            assertFalse(
                "Imperative '$cmdText' must fast-pass locally",
                LiveDirectDecision.shouldBlockAsDiscussion(
                    decision = countingJev,
                    currentTurnUserText = cmdText,
                    commands = launchCommands,
                ),
            )
        }
        assertEquals("Explicit imperatives must invoke cloud IntentGate 0 times", 0, jevCalls)

        // Question / negation / conditional / Traditional Chinese utterances MUST still consult IntentGate
        for (nonImperative in listOf(
            "我昨天打开淘宝买了个杯子",
            "先不要打开微信",
            "千万不能打开微信",
            "先別關閉藍牙",
            "要不要打开蓝牙",
            "刚才打开淘宝闪退了",
        )) {
            assertTrue(
                "Expected '$nonImperative' to consult IntentGate",
                LiveDirectDecision.shouldBlockAsDiscussion(
                    decision = countingJev,
                    currentTurnUserText = nonImperative,
                    commands = launchCommands,
                ),
            )
        }
        assertEquals(6, jevCalls)
    }

    @Test
    fun mergeListElementsSupplementsUpTo200CandidatesAndExtendedToolRouterHonorsJevAndFallback() = runTest {
        assertEquals(200, LiveDirectDecision.MAX_JEV_CANDIDATES)
        val primary = LiveDirectDecision.extractScreenCandidates(
            matches = listOf(
                node(ref = "@e1", name = "顶部按钮", clickable = true, actionRef = "@e1", bounds = listOf(10, 20, 200, 80)),
            ),
            editable = false,
        )
        val listElements = (2..150).map { idx ->
            buildJsonObject {
                put("ref", "@e$idx")
                put("role", "android.widget.Button")
                put("name", "条目_$idx")
                put("bounds", JsonArray(listOf(10, idx * 10, 200, idx * 10 + 8).map(::JsonPrimitive)))
            }
        }
        val merged = LiveDirectDecision.mergeListElementsIntoCandidates(primary, listElements, editable = false)
        assertEquals(150, merged.size)
        assertEquals("@e150", merged.last().ref)

        val candidates = listOf(
            LiveDirectDecision.ExtendedToolCandidate("schedule_create", "Create a recurring or one-shot reminder/schedule"),
            LiveDirectDecision.ExtendedToolCandidate("schedule_list", "List active schedules and reminders"),
            LiveDirectDecision.ExtendedToolCandidate("phone_add_calendar_event", "Add an event to system calendar"),
            LiveDirectDecision.ExtendedToolCandidate("phone_call_dial", "Place a carrier phone call"),
            LiveDirectDecision.ExtendedToolCandidate("phone_call_state", "Check current phone call state"),
            LiveDirectDecision.ExtendedToolCandidate("memory_search", "Search long-term personal memory"),
        )

        val confidentJev = DecisionModel { _, _, options, _ ->
            val scheduleKey = options.entries.first { it.value.startsWith("schedule_create:") }.key
            DecisionChoice(choice = scheduleKey, probability = 0.92, applicable = 0.90)
        }
        val selectedByJev = LiveDirectDecision.chooseExtendedToolsWithJev(
            decision = confidentJev,
            intent = "每天早上8点提醒我吃维生素",
            details = "08:00 每天重复",
            userUtterance = "帮我设个每天早上八点吃维生素的提醒",
            catalog = candidates,
        )
        assertEquals("schedule_create", selectedByJev.first())
        assertTrue(selectedByJev.size in 1..3)
        assertTrue("Same-family companion tool should be included", "schedule_list" in selectedByJev)

        // Low applicability (< 0.85) or null Jev falls back to deterministic lexical ranking
        val lowAppJev = DecisionModel { _, _, options, _ ->
            DecisionChoice(choice = options.keys.first(), probability = 0.95, applicable = 0.50)
        }
        val fallbackSelected = LiveDirectDecision.chooseExtendedToolsWithJev(
            decision = lowAppJev,
            intent = "把明天下午三点项目评审加到系统日历",
            details = "15:00-16:00",
            userUtterance = "把明天下午三点项目评审写进日历",
            catalog = candidates,
        )
        assertTrue(fallbackSelected.isNotEmpty())
        assertEquals("phone_add_calendar_event", fallbackSelected.first())
    }
}
