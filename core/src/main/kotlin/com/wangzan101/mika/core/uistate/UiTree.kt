package com.wangzan101.mika.core.uistate

/** 一块屏上一个无障碍窗口。根节点读不到时仍保留窗口,让模型知道有遮挡或加载中的界面。 */
data class UiWindowTree(
    val id: Int,
    val type: String,
    val layer: Int,
    val title: String?,
    val packageName: String?,
    val active: Boolean,
    val focused: Boolean,
    val nodes: List<UiTreeNode>,
)

/** 可见无障碍节点;只有能从这一屏的元素表解析的节点才带 [ref]。 */
data class UiTreeNode(
    val role: String,
    val bounds: List<Int>,
    val ref: String? = null,
    val text: String? = null,
    val description: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val secret: Boolean = false,
    /** 看上去是滚轮选择器(一格一格转的时间、日期、地区……):用转滚轮的工具一次转到值。 */
    val wheel: Boolean = false,
    val children: List<UiTreeNode> = emptyList(),
    /** Android collectionInfo 标记的列表,即使类名是普通容器也保留分组。 */
    val listContainer: Boolean = false,
) {
    /** 稳定编号只替换模型地址;节点本身与 Android 端的引用表不动。 */
    fun withRefs(refs: Map<String, String>): UiTreeNode = copy(
        ref = ref?.let { refs[it] ?: it },
        children = children.map { it.withRefs(refs) },
    )
}

/**
 * 输出给模型的窗口树裁剪与归一化;原始树留给查询、盲区与遮挡判断:
 * - 去掉系统状态栏/导航栏背景块与无语义的小空叶子(分割线、装饰图标、空容器);
 * - 折叠单子节点的纯布局包装壳(DecorView → LinearLayout → FrameLayout 等),保留多子节点的行/卡片分组关系;
 * - 保留占屏 ≥20% 的无字视图(画布、地图等),向模型提示大面积空白;
 * - 清洗混淆资源 ID 并去掉同一节点上与 text 重复的 description / hintText。
 */
object UiTree {
    private const val BLIND_AREA = 0.2
    private const val SYSTEM_UI = "com.android.systemui"
    private val SYSTEM_DECOR_IDS = setOf(
        "android:id/statusBarBackground",
        "android:id/navigationBarBackground",
        "android:id/action_mode_bar_stub",
    )
    private val SYSTEM_BAR_TITLES = setOf("StatusBar", "NavigationBar", "状态栏", "导航栏")
    private val READABLE_ID = Regex("^[A-Za-z0-9_]{4,}$")
    private val THREE_LETTERS = Regex("[A-Za-z]{3}")

    /** 系统 DecorView 注入的纯背景占位节点,不属于应用内容。 */
    fun isSystemDecorId(resourceId: String?): Boolean = resourceId in SYSTEM_DECOR_IDS

    /**
     * 非焦点的系统状态栏/导航栏窗口:不属被操作的应用界面,也不含交互弹窗。
     * 权限框、打开方式选择器或正在交互的系统弹窗(focused 或居中)仍保留。
     */
    fun isSystemBarWindow(
        isSystemType: Boolean,
        packageName: String?,
        title: String?,
        focused: Boolean,
        bounds: List<Int>? = null,
    ): Boolean {
        if (!isSystemType || focused) return false
        if (title != null && title in SYSTEM_BAR_TITLES) return true
        if (packageName != SYSTEM_UI || bounds == null || bounds.size != 4) return false
        val topBar = bounds[1] <= 5 && bounds[3] <= 120
        val bottomBar = bounds[3] >= 990 && bounds[1] >= 880
        return topBar || bottomBar
    }

    /** `com.x:id/cart_icon` → `cart_icon`;混淆过的短名认不出用途,不给。 */
    fun readableResourceId(id: String?): String? {
        val short = id?.substringAfterLast('/') ?: return null
        return short.takeIf { READABLE_ID.matches(it) && THREE_LETTERS.containsMatchIn(it) }
    }

    /**
     * 对已递归处理完子节点的 [node] 做展示精简,不作为内部查询或诊断的数据源:
     * - 有自身语义(编号、文字、可读资源 ID、可交互/状态、列表/弹窗/WebView)的节点保留;
     * - 无语义且无子节点的空叶子:除可能构成大面积盲区的视图外直接剪除;
     * - 无语义且仅有单个子节点的纯布局壳:直接上提其唯一子节点;
     * - 无语义但有多个子节点的容器:保留以维持同行/同卡片的父子归属关系。
     */
    fun compactNode(node: UiTreeNode, listContainer: Boolean = false): List<UiTreeNode> {
        val cleaned = node.forDisplay()
        if (cleaned.hasOwnSemantics(listContainer || cleaned.listContainer)) return listOf(cleaned)
        return when (cleaned.children.size) {
            0 -> if (isBlindAreaCandidate(cleaned)) listOf(cleaned) else emptyList()
            1 -> cleaned.children
            else -> listOf(cleaned)
        }
    }

    /** 递归生成展示副本,不修改原始节点。 */
    fun compact(nodes: List<UiTreeNode>): List<UiTreeNode> =
        nodes.flatMap { n -> compactNode(n.copy(children = compact(n.children))) }

    /** 全量、增量与 OCR 关联共用同一份展示结构,保证 treePath 一致。 */
    fun compactWindows(windows: List<UiWindowTree>): List<UiWindowTree> =
        windows.map { it.copy(nodes = compact(it.nodes)) }

    private fun UiTreeNode.forDisplay(): UiTreeNode {
        val cleanText = text?.trim()?.takeIf(String::isNotEmpty)
        val cleanDesc = description?.trim()?.takeUnless { it.isEmpty() || it == cleanText }
        val cleanHint = hintText?.trim()?.takeUnless { it.isEmpty() || it == cleanText || it == cleanDesc }
        return copy(text = cleanText, description = cleanDesc, hintText = cleanHint, resourceId = readableResourceId(resourceId))
    }

    private fun UiTreeNode.hasOwnSemantics(listContainer: Boolean): Boolean =
        ref != null ||
            !text.isNullOrBlank() ||
            !description.isNullOrBlank() ||
            !hintText.isNullOrBlank() ||
            !resourceId.isNullOrBlank() ||
            clickable || editable || scrollable ||
            checkable || checked || selected ||
            !enabled || focused || secret || wheel ||
            listContainer ||
            isSemanticRole(role)

    private fun isSemanticRole(role: String): Boolean =
        role.contains("Dialog", ignoreCase = true) ||
            role.equals("WebView", ignoreCase = true) ||
            role.contains("RecyclerView", ignoreCase = true) ||
            role.contains("ListView", ignoreCase = true) ||
            role.contains("GridView", ignoreCase = true) ||
            role.equals("List", ignoreCase = true)

    private fun isBlindAreaCandidate(node: UiTreeNode): Boolean =
        !isPureLayoutGroup(node.role) && area(node.bounds) >= BLIND_AREA

    private fun isPureLayoutGroup(role: String): Boolean =
        role.endsWith("Layout", ignoreCase = true) ||
            role.endsWith("ViewGroup", ignoreCase = true) ||
            role.equals("Space", ignoreCase = true)

    private fun area(b: List<Int>): Double =
        if (b.size != 4) 0.0 else (b[2] - b[0]).coerceAtLeast(0).toDouble() * (b[3] - b[1]).coerceAtLeast(0) / (999.0 * 999.0)
}
