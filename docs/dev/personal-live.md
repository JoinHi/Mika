# 个人助手语音模式（Personal Live）

在[个人助手模式](personal-agent.md)之上提供基于 Gemini Live 原生音频模型的全双工实时语音与实时虚拟屏舞台，沿用个人主会话的聊天记录、记忆、用量记账与安全审批边界。

## Gemini Live 会话契约与生命周期

- `core/live/LiveSessionContract.kt` 定义实时语音会话契约（`LiveRealtimeSession`、`LiveSessionConfig`、`LiveSessionEvent`、`LiveProviderCapabilities`）。
- `core/live/GeminiLiveProtocol.kt` 与 `core/live/GeminiLiveClient.kt` 实现 Gemini BidiGenerateContent 协议编解码、模型列表分页拉取与 WebSocket 收发。客户端经 `ApiKeyText` 校验请求头合法性并复用宿主连接池，入站控制事件与音频分片分离计数（拥塞时仅丢弃排队音频，绝不丢弃控制、转写、工具调用或用量帧），并对出站媒体载荷设上限。
- `core/live/LiveTurnCoordinator.kt` 纯函数协调插话窗口、后台任务完成/审批通知注入与静默挂断策略（`LiveSessionPolicy`），按事件 `triggerResponse` 区分**主动开口播报**与**静默上下文同步**（机主在虚拟屏手动滑动/点按默认只静默刷新经 `Untrusted` 包裹的文本界面摘要，不抢麦插话、不堆积截图），并仅在主动播报且会话声明 `supportsVisualFrames` 时附带 JPEG 视频帧。
- `app/personal/live/PersonalLiveRuntime.kt` 为每次通话创建独立的会话协程作用域，集中管理连接重连、外部来电/VoIP 占线暂挂与恢复、焦点屏幕感知截屏（前台虚拟屏已展示时优先经 `AndroidScreen` 可服务帧守卫零 OCR 读取虚拟屏画面）、`UsageLedger` 用量落盘及每日预算门禁。
- `LiveSessionToolTracker` 按调用 ID 追踪在途工具协程：收到取消帧时立即中断对应协程、跳过尚未启动的排队调用并抑制已取消调用的回包，已完成动作的记录予以保留。
- `LiveUserTextDeliverer` 统一处理通话期间的文字输入：连接或重连窗口内单次落盘并排队冲刷；发送失败或电话暂挂时直接抛错，不退回普通文字模型重复落盘。
- `app/personal/live/LiveDuplexAudioEngine.kt` 与 `app/personal/live/PersonalLiveService.kt` 管理全双工采集播放、蓝牙通信路由、硬件回声消除与前台服务常驻，原生音频句柄由各自采集/播放读写协程在退出时释放。

## 双轨硬实时手机操控与 Jev 扩展工具路由

Mika Live 以同步音频流 `< 800ms` 硬实时红线隔离「快反射同步轨」与「非阻塞虚拟屏代理轨」，严格保证同一时刻只有一个控制回路主导屏幕：

- **快反射同步轨（`quick_phone_act`，严格零云端大模型、零本地 OCR）**：1–12 步确定性手机动作（启动应用、`open_link` 深链与安卓子设置直达、点击、输入、滚动、返回、`observe` 画面观察）由 `app/personal/live/LiveDirectPhoneController.kt` 在常驻实时虚拟屏上使用 `app/cap/phone/a11y/PhoneWaitProfile.kt` 的 `TURBO` 等待配置执行。候选控件消歧仅允许亚秒级 Jev（`LIVE_FAST_DISAMBIG_TIMEOUT_MS`）选点；无论成功或未命中均保留完整 72 元素摘要并在密集页、`observe` 及未命中时直接附带虚拟屏 JPEG 返回 Gemini Live，由多模态语音模型看图单跳决策；首次打开应用时附带精简指南与已解锁深链摘要。
- **非阻塞虚拟屏代理轨（`smart_phone_goal` / `LiveDirectPhoneController.startAsyncGoal` + `app/personal/live/LiveSmartPhoneRunner.kt`）**：面向跨多页筛选比对、滚轮调参（`phone_set_wheel`）等复杂目标。调用后毫秒级立即返回 `running_on_stage` 让语音模型先自然开口应答，后台在同一常驻虚拟屏热会话串接 `RouteMatcher` + `Replayer` 经验路线回放与 `ModelRouter`（`Main`/`Light` + `KnowledgeStore` + 个人经验笔记 + `GuideCatalog` + `VisionService`），跑通后经 `RouteRecorder` 自动沉淀到 `FileRouteStore`，并向 `LiveTurnCoordinator` 投递 `StageGoalFinished` 在静默窗口播报结果；机主中途下达新屏幕指令时原子抢占取消并清理未投递旧阶段事件。
- **高频系统能力与双路扩展路由（`app/personal/live/LiveExtendedToolRouter.kt`）**：
  - `set_alarm_or_timer` 直连系统闹钟与倒计时能力（`phone_set_alarm`、`phone_set_timer`、`phone_show_alarms`，静默创建不弹虚拟屏）；
  - `notices_read` 直连本地通知中心做毫秒级只读检索；
  - `run_extended_tool(intent, details)` 由 `LiveDirectDecision.chooseExtendedToolsWithJev`（概率与适用性双 `>= 0.85` 门禁，无 Jev 时确定性词法回退）从 `schedule_task`、日历（`phone_add_calendar_event`、`phone_calendar_bulk_insert`）、`phone_call_*`、`list_device_files`、`memory_*`/`profile_*`、`skill_*`、`history_read`、`settings_*` 与已连接外部 MCP 插件能力中筛出高相关候选并经 `Light`/`Main` 模型单步填参执行；采用 750ms 快慢双路，慢调用立即释放同步通道返回 `running_async`，完成后经 `AsyncExtendedToolResult` 推送播报。
- **超长任务委派（`delegate_task`）**：超过 12 步的跨应用复杂流程、网页浏览与沙箱文件操作交给后台 `TaskRun` 执行；纯单应用启动误调时自动收敛为直控启动。
- **本地零延迟祈使放行与 Jev 控件消歧（`core/live/LiveDirectDecision.kt`）**：
  1. 明确祈使操控话语（不含疑问、否定、假设与过去时叙述）本地 `0ms` 直接放行（`isUnmistakableActionUtterance`），仅对疑似提问/闲聊/否定话语调用 Jev `IntentGate` 校验并在高置信度（`>= 0.85`）判定为 `DISCUSS` 时返回 `discuss_only`；
  2. 直控按控件归属 `actionRef`/`editRef` 合并同卡片子节点消除假歧义，超密列表截断时自动合并 `phone_list_elements` 稳定引用；遇到字面未命中或同名多按钮歧义时由 Jev（双 `>= 0.85` 门禁）选点。

## 安全边界、资源调度与人工审批

`LiveDirectPhoneController`、`LiveSmartPhoneRunner` 与 `LiveExtendedToolRouter` 复用 Mika 统一执行入口与设备治理约束：

- **统一资源互斥**：通过 `app/task/ResourceScheduler.kt` 登记直控占用的目标包名，一旦后台 `TaskRun` 启动或进入示范录制，直控立即让出虚拟屏与目标应用。
- **逐动作实时门禁**：每次写动作前核对 `DeviceStateWatcher` 的锁屏、Shell 连接、应用切走状态、非屏幕守卫（`OffScreenGuard`）以及用户在实时舞台上的触控接管；外发动作保持 `OutwardPacing` 节流，私密界面受 `SmsPrivateScreen` 遮罩保护。
- **仅限界面人工审批**：语音转写与模型参数均不能放行高风险或不可逆操作。直控敏感动作、电话拨打/接听/挂断、外部插件不可逆操作与个人设置变更均拦截并生成绑定当前上下文指纹的单次审批请求（`LiveConfirmationPrompt`），在界面确认卡片上交由用户本人点击批准或拒绝；一旦确认后界面控件或目标发生漂移（TOCTOU）立即作废重审；支付与验密动作强制要求用户在实时画面上亲手点按。

## 联网检索与深度推理

- `app/personal/live/LiveWebSearchRunner.kt` 严格遵守 `WebSearchSettings` 总开关与选定服务商，检索结果均经 `Untrusted` 包裹后再交给模型。
- `app/personal/live/LiveDeepThinkRunner.kt` 复用推理模型在后台独立推演，仅在搜索开启时挂载 `web_search`，绝不直接抓取任意网页地址。

## 验证

核心与宿主单测覆盖：Gemini Live 协议帧编解码与 `InputTranscriptCompleted`、无视觉帧能力守卫、音频拥塞保控制帧、工具调用取消中断、虚拟屏流式触控死区钳制与系统手势取消保护、超密列表控件消歧与扩展工具动态路由、屏幕摘要瘦身与指南裁剪、高风险动作上下文绑定单次界面审批、搜索开关遵从与不可信包裹。
