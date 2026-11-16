package com.wangzan101.mika.core.live

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.ClassifiedRisk
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.action.RiskJudge
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.gate.DeclareResult
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.Hant
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Live 语音直控模式的单步敏感动作拦截与确认单构建规则：
 * - **逢敏感必确认，单次有效，实时核验上下文（防 TOCTOU）**：所有非支付类敏感动作（`send`、`delete`、`submit`、`order`、`other`）
 *   每次触发均必须弹出界面确认卡片；机主点击「同意」时按实时重读屏幕逐项比对应用包名、页面目标、草稿内容与应付金额（缺省字段允许一致为空，
 *   但一旦确认期间切走应用、切换聊天对象、修改草稿、价格变动或原按钮消失被遮挡，立即以 `confirmation_mismatch` 拒绝执行）；
 * - **拒绝不拉黑**：机主点击「不同意」仅取消当前这一次操作，不污染后续同应用同类操作的再次确认；
 * - **支付绝对手点**：支付动作（[IrrevAction.pay]）一律返回 `null`，从不生成代点确认卡，必须由机主亲手在实时虚拟屏上点击完成。
 */
object LiveDirectConfirmation {
    private val CHANGED_SCREEN_SENTINEL = ConfirmationRequest(
        action = IrrevAction.other,
        target = "__target_disappeared__",
        content = "__target_disappeared__",
        amount = null,
        app = "__target_disappeared__",
    )

    private const val MAX_TARGET_LEN = 40
    private const val MAX_CONTENT_LEN = 200
    private const val DISCOUNT_WINDOW = 4
    private val GENERIC_NAV_LABELS = setOf("返回", "关闭", "取消", "更多", "back", "close", "cancel", "more")
    private val CURRENCY = Regex("[¥￥$＄]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元")
    private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
    private val TOTAL_LABELS = IrreversibleDetector.TOTAL_LABELS
    private val DISCOUNTS = listOf("优惠", "减", "抵扣", "运费", "券", "红包", "已省")

    /**
     * 生成与当前屏幕上下文严格绑定的一次性放行凭据（绑定 `action`、`app`、`target`、屏幕草稿 `content` 与应付 `amount`）。
     * 支付动作（[IrrevAction.pay]）永远返回 `null`，绝不允许自动放行。
     */
    fun buildGateToken(
        hit: Irreversible,
        screen: ScreenSnapshot?,
        fallbackApp: String? = null,
    ): ConfirmationRequest? = buildRequest(
        hit = hit,
        screen = screen,
        typingText = null,
        fallbackApp = fallbackApp,
    )

    /**
     * 在机主于确认卡片点击「同意」后，向 [gate] 写入仅对当前单步（[step]）生效且绑定上下文的一次性放行凭据。
     * 支付动作（[IrrevAction.pay]）始终返回 `false`。
     */
    fun grantOnce(gate: ConfirmationGate, token: ConfirmationRequest, step: Int): Boolean {
        if (token.action == IrrevAction.pay) return false
        gate.reset()
        if (gate.declare(token, token.app) != DeclareResult.Pending) return false
        gate.decide(ConfirmChoice.Once, step)
        return true
    }

    /**
     * 专供 Live 语音直控 `ActionExecutor` 使用的风险判定器（不影响普通聊天 `TaskRun` 默认策略）：
     * - 凡命中 `Irreversible` 的非支付敏感动作，按实时屏幕生成绑定 `app/target/content/amount` 的确认请求；
     * - 支付动作保持 `payment = true, ask = null` 拒绝代点；
     * - 若当前存在待核销的单次授权（`gate.granted != null`）但新读屏幕已不再命中原不可逆控件（`hit == null`，如原按钮被遮罩/无标签弹窗覆盖），
     *   强制触发 `confirmation_mismatch` 拦截盲点。
     */
    fun riskJudge(
        gate: ConfirmationGate? = null,
        fallbackAppProvider: () -> String? = { null },
    ): RiskJudge = RiskJudge { verb, hit, screen ->
        val base = ClassifiedRisk.assess(verb, hit, screen)
        when {
            hit == null -> {
                if (gate?.granted != null) {
                    Risk(ActionRisk.Irreversible, payment = true, ask = CHANGED_SCREEN_SENTINEL)
                } else {
                    base
                }
            }
            hit.action == IrrevAction.pay -> Risk(ActionRisk.Irreversible, payment = true, ask = null)
            else -> Risk(
                level = base.level,
                payment = true,
                ask = buildGateToken(hit, screen(), fallbackApp = fallbackAppProvider()),
            )
        }
    }

    val riskJudge: RiskJudge = riskJudge(gate = null)

    /**
     * 构建展示在 UI 确认卡片上的可读信息（不因缺少标题栏、自绘输入框或无金额字样而返回 `null` 漏拦）。
     * 仅 [IrrevAction.pay] 返回 `null`。
     */
    fun buildRequest(
        hit: Irreversible,
        screen: ScreenSnapshot?,
        typingText: String? = null,
        fallbackApp: String? = null,
    ): ConfirmationRequest? {
        if (hit.action == IrrevAction.pay) return null
        val pkg = screen?.packageName?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackApp?.trim()?.takeIf { it.isNotEmpty() }
            ?: "unknown"

        val target = screen?.let { resolveDisplayTarget(hit, it) }.orEmpty()
        val content = resolveDisplayContent(hit, screen, typingText)
        val amount = screen?.let { resolveDisplayAmount(hit, it) }

        return ConfirmationRequest(
            action = hit.action,
            target = target,
            content = content,
            amount = amount,
            app = pkg,
        )
    }

    private fun resolveDisplayTarget(hit: Irreversible, screen: ScreenSnapshot): String? {
        val titleCandidates = screen.elements.filter { e ->
            !e.editable &&
                e.bounds.size == 4 &&
                e.bounds[1] <= ConfirmationGate.TITLE_BAND &&
                norm(e.name).length >= 2
        }
        val preferredTitle = titleCandidates.firstOrNull { !it.clickable && norm(it.name) !in GENERIC_NAV_LABELS }
            ?: titleCandidates.firstOrNull { norm(it.name) !in GENERIC_NAV_LABELS }
            ?: titleCandidates.firstOrNull()

        if (preferredTitle != null) {
            return preferredTitle.name.trim().take(MAX_TARGET_LEN)
        }
        if (hit.action == IrrevAction.send) {
            return null
        }

        val matchedNorm = norm(hit.matched).take(ConfirmationGate.MATCH_CHARS)
        return screen.elements
            .firstOrNull { e ->
                !e.editable && !e.hint && norm(e.name).length >= 2 &&
                    norm(e.name) != matchedNorm && norm(e.name) !in GENERIC_NAV_LABELS
            }
            ?.name
            ?.trim()
            ?.take(MAX_TARGET_LEN)
    }

    private fun resolveDisplayContent(hit: Irreversible, screen: ScreenSnapshot?, typingText: String?): String {
        if (hit.action == IrrevAction.send) {
            val raw = typingText?.takeIf { norm(it).isNotEmpty() }
                ?: screen?.elements?.firstOrNull { it.editable && !it.hint && norm(it.name).isNotEmpty() }?.name
            if (raw != null) {
                val trimmed = raw.trim()
                return if (trimmed.length > MAX_CONTENT_LEN) {
                    trimmed.take(MAX_CONTENT_LEN) + "…"
                } else {
                    trimmed
                }
            }
        }
        return hit.matched.trim().ifEmpty { hit.action.name }.take(80)
    }

    private fun resolveDisplayAmount(hit: Irreversible, screen: ScreenSnapshot): String? {
        val shouldShowAmount = when (hit.action) {
            IrrevAction.order -> true
            IrrevAction.send, IrrevAction.pay -> false
            IrrevAction.submit, IrrevAction.delete, IrrevAction.other -> IrreversibleDetector.hasPayableAmount(screen)
        }
        if (!shouldShowAmount) return null

        val shown = screen.elements.filter { !it.editable && !it.hint }
        val scrollables = screen.elements.filter { it.scrollable }
        fun outside(e: UiElement) = scrollables.none { inside(e, it) }

        val labels = shown.filter { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } }
        if (labels.isNotEmpty()) {
            val totals = labels.mapNotNull { label -> totalOf(label, shown)?.let { label to it } }
            val out = totals.filter { outside(it.first) }
            if (out.isNotEmpty()) {
                return "¥${out.first().second.toPlainString()}"
            }
            val button = shown.firstOrNull { it.name.trim() == hit.matched }
            val near = totals.minByOrNull { (e, _) ->
                if (button == null) -top(e) else abs(centerY(e) - centerY(button))
            }
            if (near != null) {
                return "¥${near.second.toPlainString()}"
            }
        }

        val largest = shown.filter(::outside).flatMap { amounts(it.name) }.maxOrNull() ?: return null
        return "¥${largest.toPlainString()}"
    }

    private fun totalOf(label: UiElement, shown: List<UiElement>): BigDecimal? {
        fun after(text: String) = TOTAL_LABELS
            .mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 }?.let { text.substring(it + w.length) } }
            .firstNotNullOfOrNull { amounts(it).firstOrNull() }
        return after(Hant.fold(label.name)) ?: after(Hant.fold(IrreversibleDetector.rowText(label, shown)))
    }

    private fun amounts(name: String): List<BigDecimal> {
        val clean = Hant.fold(name).replace(THOUSANDS, "")
        return CURRENCY.findAll(clean).filter { m ->
            val before = clean.substring(maxOf(0, m.range.first - DISCOUNT_WINDOW), m.range.first)
            DISCOUNTS.none { it in before } && !before.trimEnd().endsWith("-")
        }.mapNotNull { m ->
            m.groupValues[1].ifEmpty { m.groupValues[2] }.toBigDecimalOrNull()
        }.toList()
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0
    private fun centerY(e: UiElement) = if (e.bounds.size == 4) (e.bounds[1] + e.bounds[3]) / 2 else 0

    private fun inside(c: UiElement, p: UiElement): Boolean {
        val a = c.bounds
        val b = p.bounds
        return a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    }

    private fun norm(s: String): String = s.filterNot(Char::isWhitespace).lowercase()
}
