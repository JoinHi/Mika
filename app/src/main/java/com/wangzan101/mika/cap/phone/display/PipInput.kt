package com.wangzan101.mika.cap.phone.display

/**
 * 对话里虚拟屏小窗的输入口:用户在预览画面上点 / 滑,换算成虚拟屏逻辑坐标注入。
 * [fx] / [fy] 是相对预览画面的 0..1 坐标(左上角 0,0、右下角 1,1),由实现按虚拟屏当前逻辑宽高换算。
 * 只在用户开了「允许我在小窗里直接操作」、且小窗已展开时才会被调用。
 */
interface PipInput {
    /** 在预览画面的 (fx, fy) 点一下。 */
    suspend fun tap(fx: Float, fy: Float)

    /** 一根手指沿 [points](0..1 坐标)滑一笔,从按下到抬起约 [durationMs] 毫秒;至少两个点。 */
    suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long)

    /** 在虚拟屏上按一次返回键。 */
    suspend fun back()

    /**
     * 实时流式注入一次单指或多指触摸事件([action] 为 [android.view.MotionEvent] 动作掩码与指针索引组合,
     * [normXs]/[normYs] 为相对画面的 0..1 归一化坐标)。
     * 返回 false 表示底层不支持实时流式注入(例如旧版助手进程),调用方自动回退到 [tap] / [swipe]。
     */
    fun injectTouch(
        action: Int,
        downTime: Long,
        eventTime: Long,
        pointerIds: IntArray,
        normXs: FloatArray,
        normYs: FloatArray,
    ): Boolean = false

    /** 界面进入或退出实时触控状态,供底层动态切换高帧率高清预览(720p@30fps ↔ 后台省电档)。 */
    fun onInteractiveChanged(interactive: Boolean) {}
}
