package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDirectConfirmationTest {

    private fun el(
        ref: String,
        name: String,
        bounds: List<Int> = listOf(50, 400, 950, 480),
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ) = UiElement(
        ref = ref,
        role = if (editable) "EditText" else if (clickable) "Button" else "TextView",
        name = name,
        centerX = (bounds[0] + bounds[2]) / 2,
        centerY = (bounds[1] + bounds[3]) / 2,
        bounds = bounds,
        resourceId = null,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
    )

    private class CustomScreen(
        var elements: List<UiElement>,
        var pkg: String = "com.example.custom",
    ) : ScreenPort by FakeScreen() {
        val taps = mutableListOf<String>()
        override suspend fun snapshot() = ScreenSnapshot("s1", pkg, elements, 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            taps += ref
            return ActionResult.Ok(changed = true)
        }
    }

    @Test
    fun `pay action never builds a confirmation card request or grant token`() {
        val screen = ScreenSnapshot(
            snapshotId = "s1",
            packageName = "com.eg.android.AlipayGphone",
            elements = listOf(
                el("e1", "确认付款", bounds = listOf(100, 80, 900, 130)),
                el("e2", "实付 ¥35.00", bounds = listOf(100, 700, 500, 760)),
                el("e3", "立即支付", bounds = listOf(600, 850, 950, 930), clickable = true),
            ),
            width = 1080,
            height = 2400,
        )
        assertNull(LiveDirectConfirmation.buildRequest(Irreversible(IrrevAction.pay, "立即支付"), screen))
        assertNull(LiveDirectConfirmation.buildGateToken(Irreversible(IrrevAction.pay, "立即支付"), screen))
        val bogusPayReq = com.wangzan101.mika.core.gate.ConfirmationRequest(IrrevAction.pay, "确认付款", "立即支付", "¥35.00", "com.eg.android.AlipayGphone")
        assertFalse(LiveDirectConfirmation.grantOnce(ConfirmationGate(), bogusPayReq, step = 1))
    }

    @Test
    fun `buildRequest extracts readable card info when present and never returns null when context is missing`() {
        val wechatScreen = ScreenSnapshot(
            snapshotId = "s2",
            packageName = "com.tencent.mm",
            elements = listOf(
                el("e0", "返回", bounds = listOf(20, 60, 120, 120), clickable = true),
                el("e1", "产品研发群", bounds = listOf(200, 60, 600, 120)),
                el("e2", "今晚八点同步版本", bounds = listOf(80, 860, 740, 940), editable = true),
                el("e3", "发送", bounds = listOf(760, 860, 960, 940), clickable = true),
            ),
            width = 1080,
            height = 2400,
        )
        val sendReq = LiveDirectConfirmation.buildRequest(Irreversible(IrrevAction.send, "发送"), wechatScreen)!!
        assertEquals("产品研发群", sendReq.target)
        assertEquals("今晚八点同步版本", sendReq.content)
        assertNull(sendReq.amount)

        // Custom UI without title band or editable draft node still builds a confirmation card
        val minimalScreen = ScreenSnapshot(
            snapshotId = "s3",
            packageName = "com.custom.im",
            elements = listOf(
                el("e1", "发送", bounds = listOf(760, 860, 960, 940), clickable = true),
            ),
            width = 1080,
            height = 2400,
        )
        val minimalSend = LiveDirectConfirmation.buildRequest(Irreversible(IrrevAction.send, "发送"), minimalScreen)
        assertNotNull(minimalSend)
        assertEquals("", minimalSend!!.target)
        assertEquals("发送", minimalSend.content)

        // Order screen extracts payable total while ignoring discount amount
        val orderScreen = ScreenSnapshot(
            snapshotId = "s4",
            packageName = "com.sankuai.meituan",
            elements = listOf(
                el("e1", "确认订单", bounds = listOf(300, 60, 700, 120)),
                el("e2", "已优惠 ¥6", bounds = listOf(80, 820, 320, 870)),
                el("e3", "合计 ¥28.50", bounds = listOf(80, 880, 480, 940)),
                el("e4", "提交订单", bounds = listOf(640, 870, 960, 950), clickable = true),
            ),
            width = 1080,
            height = 2400,
        )
        val orderReq = LiveDirectConfirmation.buildRequest(Irreversible(IrrevAction.order, "提交订单"), orderScreen)!!
        assertEquals("¥28.50", orderReq.amount)

        // Delete, submit without amount, and other irreversible actions all build valid requests
        assertNotNull(LiveDirectConfirmation.buildRequest(Irreversible(IrrevAction.delete, "删除"), minimalScreen))
        assertNotNull(LiveDirectConfirmation.buildRequest(Irreversible(IrrevAction.submit, "提交申请"), minimalScreen))
        assertNotNull(LiveDirectConfirmation.buildRequest(Irreversible(IrrevAction.other, "注销账号"), minimalScreen))
    }

    @Test
    fun `every sensitive action intercepts every single click and declining never blacklists next attempt`() = runTest {
        // Deliberately omit title band, editable draft node, and currency amount so fragile context matching would fail
        val screen = CustomScreen(
            elements = listOf(
                el("@e1", "发送", bounds = listOf(760, 860, 960, 940), clickable = true),
                el("@e2", "删除", bounds = listOf(400, 860, 600, 940), clickable = true),
                el("@e3", "确认提交", bounds = listOf(100, 860, 360, 940), clickable = true),
                el("@e4", "立即支付", bounds = listOf(600, 700, 960, 780), clickable = true),
                el("@e5", "提交订单", bounds = listOf(100, 700, 360, 780), clickable = true),
            ),
        )
        val gate = ConfirmationGate()
        val tools = toolDispatcher(screen)
        val executor = ActionExecutor(
            tools = tools,
            guards = OperationGuards(),
            confirmations = gate,
            gate = {},
            recorder = ActionRecorder(RunSink.NONE),
            approvals = ApprovalSettings.ASK,
            autonomous = false,
            risks = LiveDirectConfirmation.riskJudge(gate),
        )

        // Prime ScreenMap via ActionExecutor
        assertTrue(executor.run(Part.ToolCall("obs", "phone_observe", "{}"), Origin.Batch(0)) is ActionOutcome.Ran)

        for ((ref, expectedAction) in listOf(
            "@e1" to IrrevAction.send,
            "@e2" to IrrevAction.delete,
            "@e3" to IrrevAction.submit,
            "@e5" to IrrevAction.order,
        )) {
            screen.taps.clear()
            val call = Part.ToolCall("tap_$ref", "phone_tap", """{"ref":"$ref","snapshotId":"s1"}""")

            // 1. Batch execution intercepts immediately and asks user
            val firstAttempt = executor.run(call, Origin.Batch(1)) as ActionOutcome.Refused
            assertEquals("confirmation_required", firstAttempt.code)
            assertEquals(expectedAction, firstAttempt.hit?.action)
            assertTrue(screen.taps.isEmpty())

            // 2. Approving once executes this single step regardless of missing title/draft/amount
            val token = LiveDirectConfirmation.buildGateToken(firstAttempt.hit!!, screen.peek())!!
            assertTrue(LiveDirectConfirmation.grantOnce(gate, token, step = 2))
            val approved = executor.run(call, Origin.Model(2))
            gate.reset()
            assertTrue("Expected Ran for $expectedAction but got $approved", approved is ActionOutcome.Ran)
            assertEquals(listOf(ref), screen.taps)

            // 3. Clicking the exact same button a second time MUST ask again (both Batch and Model)
            val secondBatch = executor.run(call, Origin.Batch(3)) as ActionOutcome.Refused
            assertEquals("confirmation_required", secondBatch.code)
            val secondModel = executor.run(call, Origin.Model(4)) as ActionOutcome.Refused
            assertEquals("confirmation_required", secondModel.code)
            assertEquals(listOf(ref), screen.taps)

            // 4. Declining once (gate.reset()) cancels only the current click and does NOT blacklist subsequent confirmation
            gate.reset()
            val afterDeclineBatch = executor.run(call, Origin.Batch(5)) as ActionOutcome.Refused
            assertEquals("confirmation_required", afterDeclineBatch.code)
            val token2 = LiveDirectConfirmation.buildGateToken(afterDeclineBatch.hit!!, screen.peek())!!
            assertTrue(LiveDirectConfirmation.grantOnce(gate, token2, step = 6))
            val approvedAgain = executor.run(call, Origin.Model(6))
            gate.reset()
            assertTrue(approvedAgain is ActionOutcome.Ran)
            assertEquals(listOf(ref, ref), screen.taps)
        }

        // 5. Payment button is always refused on both Batch and Model (must be tapped directly by user)
        screen.taps.clear()
        val payCall = Part.ToolCall("tap_pay", "phone_tap", """{"ref":"@e4","snapshotId":"s1"}""")
        val payBatch = executor.run(payCall, Origin.Batch(10)) as ActionOutcome.Refused
        assertEquals("confirmation_required", payBatch.code)
        assertEquals(IrrevAction.pay, payBatch.hit?.action)
        val payModel = executor.run(payCall, Origin.Model(11)) as ActionOutcome.Refused
        assertEquals("confirmation_required", payModel.code)
        assertTrue(screen.taps.isEmpty())
    }

    @Test
    fun `grantOnce rejects TOCTOU changes to amount recipient draft app or disappearing button`() = runTest {
        val screen = CustomScreen(
            pkg = "com.sankuai.meituan",
            elements = listOf(
                el("@e1", "确认订单", bounds = listOf(300, 60, 700, 120)),
                el("@e2", "合计 ¥28.50", bounds = listOf(80, 880, 480, 940)),
                el("@e3", "提交订单", bounds = listOf(640, 870, 960, 950), clickable = true),
            ),
        )
        val gate = ConfirmationGate()
        val tools = toolDispatcher(screen)
        val executor = ActionExecutor(
            tools = tools,
            guards = OperationGuards(),
            confirmations = gate,
            gate = {},
            recorder = ActionRecorder(RunSink.NONE),
            approvals = ApprovalSettings.ASK,
            autonomous = false,
            risks = LiveDirectConfirmation.riskJudge(gate),
        )
        assertTrue(executor.run(Part.ToolCall("obs", "phone_observe", "{}"), Origin.Batch(0)) is ActionOutcome.Ran)

        val orderCall = Part.ToolCall("tap_order", "phone_tap", """{"x":800,"y":910}""")
        val refused = executor.run(orderCall, Origin.Batch(1)) as ActionOutcome.Refused
        assertEquals("confirmation_required", refused.code)
        val approvedToken = LiveDirectConfirmation.buildGateToken(refused.hit!!, screen.peek())!!
        assertEquals("¥28.50", approvedToken.amount)

        // Price mutates from ¥28.50 -> ¥288.50 before user clicks Approve -> MUST refuse with confirmation_mismatch!
        screen.elements = listOf(
            el("@e1", "确认订单", bounds = listOf(300, 60, 700, 120)),
            el("@e2", "合计 ¥288.50", bounds = listOf(80, 880, 480, 940)),
            el("@e3", "提交订单", bounds = listOf(640, 870, 960, 950), clickable = true),
        )
        assertTrue(LiveDirectConfirmation.grantOnce(gate, approvedToken, step = 2))
        val afterPriceJump = executor.run(orderCall, Origin.Model(2)) as ActionOutcome.Refused
        assertEquals("confirmation_mismatch", afterPriceJump.code)
        assertTrue(screen.taps.isEmpty())

        // Button disappears and is covered by an unlabeled overlay at (800, 910) -> MUST refuse without blind tap!
        screen.elements = listOf(
            el("@e9", "", bounds = listOf(640, 870, 960, 950), clickable = true),
        )
        assertTrue(LiveDirectConfirmation.grantOnce(gate, approvedToken, step = 3))
        val afterOverlay = executor.run(orderCall, Origin.Model(3)) as ActionOutcome.Refused
        assertEquals("confirmation_mismatch", afterOverlay.code)
        assertTrue(screen.taps.isEmpty())
    }
}
