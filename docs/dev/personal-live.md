# 个人助手实时语音（Mika Live）

在[个人助手模式](personal-agent.md)之上提供全双工实时语音与实时虚拟屏舞台，由 Gemini Live WebSocket 原生音频驱动，沿用个人主会话的聊天记录、记忆、用量记账与安全审批边界。

## 分层与会话生命周期

- `core/live/GeminiLiveProtocol.kt` 与 `core/live/GeminiLiveClient.kt` 负责 BidiGenerateContent 协议编解码、模型列表分页拉取与 WebSocket 收发。客户端复用宿主连接池，入站控制事件与音频分片分离计数（拥塞时仅丢弃排队音频，绝不丢弃控制、转写、工具调用或用量帧），并对出站媒体载荷设上限。
- `core/live/LiveTurnCoordinator.kt` 纯函数协调插话窗口、后台任务完成/审批通知注入与静默挂断策略（`LiveSessionPolicy`）。
- `app/personal/live/PersonalLiveRuntime.kt` 为每次通话创建独立的会话协程作用域，集中管理连接重连、电话占线暂挂、唤醒词让位与恢复、`UsageLedger` 用量落盘及每日预算门禁。
- `LiveSessionToolTracker` 按调用 ID 追踪在途工具协程：收到取消帧时立即中断对应协程、跳过尚未启动的排队调用并抑制已取消调用的回包，已完成动作的记录予以保留。
- `LiveUserTextDeliverer` 统一处理通话期间的文字输入：连接或重连窗口内单次落盘并排队冲刷；发送失败或电话暂挂时直接抛错，不退回普通文字模型重复落盘。
- `app/personal/live/LiveDuplexAudioEngine.kt` 与 `app/personal/live/PersonalLiveService.kt` 管理全双工采集播放、硬件回声消除与前台常驻，原生音频句柄由各自采集/播放读写协程在退出时释放。

## 统一执行架构与 Jev 辅助判别

Mika Live 采用统一的「极速直控优先 + 超长任务自动委派 + 可选 Jev 判别辅助」架构：

- **低延迟语音直控（`quick_phone_act`）**：1–12 步常见手机操作（启动应用、点击、输入、滚动、返回、界面观察）直接由 `app/personal/live/LiveDirectPhoneController.kt` 在常驻实时虚拟屏上执行，使用 `app/cap/phone/a11y/PhoneWaitProfile.kt` 的 `TURBO` 等待配置，跳过人为拟人停顿的同时保留足够的异步点击探测与剪贴板粘贴稳定窗口。等待配置仅作用于当前活跃语音直控虚拟屏，不影响普通聊天或定时后台 `TaskRun` 的 `STANDARD` 配置。
- **复杂长任务委派（`delegate_task`）**：超过 12 步的跨应用流程、网页浏览、运营商电话与沙箱文件操作自动交给后台 `TaskRun` 执行；若模型误将纯单应用启动发往 `delegate_task`，运行时自动在空闲时收敛为直控启动。
- **Jev（`DecisionModel`）消歧与闲聊防误触（`core/live/LiveDirectDecision.kt`）**：复用用户已在设置中启用的 Jev 决策模型（`TaskEnvironment.decisionModel`）与 `IntentGate` 的 `intent_decision` 规则：
  1. 本轮首次非只读手机操作前校验用户原话意图，高置信度（`>= 0.85`）判定为纯口头探讨（`DISCUSS`）时直接返回 `discuss_only` 阻止误打开/误点击；
  2. 直控按控件归属 `actionRef`/`editRef` 合并同卡片子节点消除假歧义；当遇到字面未命中（`target_not_found`）或同名多按钮歧义（`ambiguous_target`）时，交由 Jev 结合用户原话、阅读序、归一化坐标与父容器上下文选点（概率与适用性双 `>= 0.85` 门禁），未启用或置信度不足时无副作用回退并附带实时截图供主模型补救。

## 安全边界、资源调度与人工审批

`LiveDirectPhoneController` 复用 Mika 现有的单入口与设备治理约束，不绕过安全关卡：

- **统一资源互斥**：通过 `app/task/ResourceScheduler.kt` 登记直控占用的目标包名，并在虚拟屏上开启跨任务占用检查；一旦 `TaskRun` 启动或进入示范录制，直控立即让出虚拟屏与目标应用。
- **逐动作实时门禁**：直控向 `ActionExecutor` 注入逐动作门禁，每次写动作前核对 `DeviceStateWatcher` 的锁屏、Shell 连接、应用切走状态以及用户在实时舞台上的触控接管；话轮切换时重置 `OperationGuards`，外发动作保持 `OutwardPacing` 节流，短信等私密界面继续受 `SmsPrivateScreen` 遮罩保护。
- **仅限界面人工审批**：语音转写与模型参数均不能放行高风险或不可逆操作。直控以批处理来源运行模型步骤，遇到敏感动作立即拦截并经 `core/live/LiveDirectConfirmation.kt` 生成单次审批请求（`LiveConfirmationPrompt`），在界面确认卡片上交由用户本人点击批准或拒绝；后台任务的待批请求同样汇总到该卡片，只在用户点击按钮后放行对应单次操作。

## 联网检索与深度推理

- `app/personal/live/LiveWebSearchRunner.kt` 严格遵守 `WebSearchSettings` 的总开关与用户选定的搜索服务商，关闭或失败时如实返回原因，不暗自回退到未配置的服务商；检索结果的标题、链接与摘要均经 `Untrusted` 包裹后再交给模型。
- `app/personal/live/LiveDeepThinkRunner.kt` 复用用户已配置的推理模型处理复杂分析问题，仅在联网搜索已开启时挂载搜索能力，不直接抓取任意网页地址，避免服务端重定向探测内网风险。

## 验证

核心与宿主单测覆盖：协议帧编解码、音频拥塞保控制帧、工具调用取消中断、跨重连文字单次落盘与防双路回退、极速等待参数隔离、直控逐动作锁屏/断连/触控门禁、后台任务抢占让位、高风险动作拦截与单次界面审批、搜索开关遵从与不可信包裹。真机重点验收双工回声消除、锁屏拦截、舞台触控接管与开关切换体验。
